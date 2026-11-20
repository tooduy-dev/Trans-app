package com.example.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ComicRepository
import com.example.model.ComicPage
import com.example.model.TranslationStatus
import com.example.network.GeminiComicTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

enum class ViewMode {
    PAGE_BY_PAGE,
    CONTINUOUS_SCROLL
}

data class ComicUiState(
    val pages: List<ComicPage> = emptyList(),
    val selectedPageIndex: Int = 0,
    val selectedBubbleIndex: Int? = null,
    val isBatchTranslating: Boolean = false,
    val batchProgress: Float = 0f,
    val batchCurrentPage: Int = 0,
    val batchTotalPages: Int = 0,
    val folderName: String = "",
    val showBubbleHints: Boolean = true,
    val showSchemaDialog: Boolean = false,
    val showSettingsDialog: Boolean = false,
    val overlayScale: Float = 1.35f,
    val fontSizeSp: Float = 12f,
    val autoFitFont: Boolean = true,
    val customApiKeysText: String = "",
    val customKeyCount: Int = 0,
    val activeKeyInfo: String = "Đang kiểm tra...",
    val activeModelName: String = GeminiComicTranslator.DEFAULT_MODEL,
    val viewMode: ViewMode = ViewMode.PAGE_BY_PAGE,
    val statusMessage: String? = null
) {
    val currentPage: ComicPage?
        get() = pages.getOrNull(selectedPageIndex)

    val translatedCount: Int
        get() = pages.count { it.translationStatus == TranslationStatus.COMPLETED }
}

class ComicViewModel(
    private val repository: ComicRepository = ComicRepository(),
    private val translator: GeminiComicTranslator = GeminiComicTranslator()
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComicUiState())
    val uiState: StateFlow<ComicUiState> = _uiState.asStateFlow()

    private var batchTranslationJob: Job? = null
    private var prefetchJob: Job? = null
    private var appContext: Context? = null

    /**
     * Loads comic pages from a folder chosen via OpenDocumentTree.
     * Automatically restores any previously cached translations from disk.
     */
    fun loadFolder(context: Context, treeUri: Uri) {
        this.appContext = context.applicationContext
        viewModelScope.launch {
            _uiState.update { it.copy(statusMessage = "Đang tải danh sách ảnh trong thư mục...") }
            val pages = repository.loadPagesFromFolderUri(context, treeUri)
            val folderDisplayName = treeUri.lastPathSegment?.substringAfterLast(':') ?: "Thư mục truyện"
            if (pages.isEmpty()) {
                _uiState.update {
                    it.copy(
                        statusMessage = "Không tìm thấy file ảnh nào trong thư mục đã chọn.",
                        pages = emptyList()
                    )
                }
            } else {
                val cachedCount = pages.count { it.translationStatus == TranslationStatus.COMPLETED }
                val statusMsg = if (cachedCount > 0) {
                    "Đã nạp ${pages.size} trang ($cachedCount trang đã nạp từ cache máy)"
                } else {
                    "Đã nạp ${pages.size} trang truyện thành công."
                }
                _uiState.update {
                    it.copy(
                        pages = pages,
                        selectedPageIndex = 0,
                        selectedBubbleIndex = null,
                        folderName = folderDisplayName,
                        statusMessage = statusMsg
                    )
                }
                // Prefetch next 2 pages if not already translated
                prefetchPages(context, 0)
            }
        }
    }

    /**
     * Loads the built-in demo comic pages for instant testing.
     */
    fun loadDemoComic(context: Context) {
        this.appContext = context.applicationContext
        viewModelScope.launch {
            _uiState.update { it.copy(statusMessage = "Đang chuẩn bị bộ truyện mẫu...") }
            val demoPages = repository.getDemoComicPages(context)
            _uiState.update {
                it.copy(
                    pages = demoPages,
                    selectedPageIndex = 0,
                    selectedBubbleIndex = null,
                    folderName = "Truyện mẫu: Dị Giới Phiêu Lưu",
                    statusMessage = "Đã nạp ${demoPages.size} trang truyện mẫu. Chạm vào ô thoại để xem bản dịch!"
                )
            }
        }
    }

    /**
     * Batch translates all pages in parallel (2 concurrent workers via Semaphore)
     * with rate limiting pacing (1200ms delay) and disk caching.
     */
    fun startBatchTranslation(context: Context) {
        this.appContext = context.applicationContext
        val pagesToTranslate = _uiState.value.pages
        if (pagesToTranslate.isEmpty()) return

        batchTranslationJob?.cancel()
        batchTranslationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBatchTranslating = true,
                    batchProgress = 0f,
                    batchCurrentPage = 0,
                    batchTotalPages = pagesToTranslate.size,
                    statusMessage = "Bắt đầu dịch hàng loạt ${pagesToTranslate.size} trang (song song 2 luồng)..."
                )
            }

            val semaphore = Semaphore(permits = 2)
            val totalPages = pagesToTranslate.size
            val completedCounter = AtomicInteger(0)

            val jobs = pagesToTranslate.indices.map { index ->
                async(Dispatchers.IO) {
                    val page = _uiState.value.pages[index]

                    // If already completed in memory, increment and skip
                    if (page.translationStatus == TranslationStatus.COMPLETED && page.bubbles.isNotEmpty()) {
                        val count = completedCounter.incrementAndGet()
                        _uiState.update {
                            it.copy(
                                batchProgress = count.toFloat() / totalPages,
                                batchCurrentPage = count
                            )
                        }
                        return@async
                    }

                    semaphore.withPermit {
                        // 1. Check local disk cache first
                        val cached = repository.loadPageTranslationCache(
                            context,
                            page.name,
                            page.uri,
                            page.width,
                            page.height
                        )
                        if (cached != null) {
                            _uiState.update { state ->
                                val updated = state.pages.toMutableList()
                                if (index in updated.indices) {
                                    updated[index] = updated[index].copy(
                                        translationStatus = TranslationStatus.COMPLETED,
                                        bubbles = cached.first,
                                        width = cached.second.first,
                                        height = cached.second.second,
                                        errorMessage = null
                                    )
                                }
                                state.copy(pages = updated)
                            }
                            val count = completedCounter.incrementAndGet()
                            _uiState.update {
                                it.copy(
                                    batchProgress = count.toFloat() / totalPages,
                                    batchCurrentPage = count,
                                    statusMessage = "Đã nạp cache trang ${index + 1}/$totalPages"
                                )
                            }
                            return@withPermit
                        }

                        // 2. Call Gemini API
                        updatePageStatus(index, TranslationStatus.TRANSLATING, null)
                        val bitmap = repository.loadBitmap(context, page.uri)
                        if (bitmap != null) {
                            val result = translator.translateComicPage(bitmap)
                            if (result.isSuccess) {
                                val bubbles = result.getOrNull() ?: emptyList()
                                val actualWidth = bitmap.width
                                val actualHeight = bitmap.height
                                val updatedPage = page.copy(
                                    translationStatus = TranslationStatus.COMPLETED,
                                    bubbles = bubbles,
                                    width = actualWidth,
                                    height = actualHeight,
                                    errorMessage = null
                                )
                                // Save to local disk cache
                                repository.savePageTranslationCache(context, updatedPage)

                                _uiState.update { state ->
                                    val updated = state.pages.toMutableList()
                                    if (index in updated.indices) {
                                        updated[index] = updatedPage
                                    }
                                    state.copy(
                                        pages = updated,
                                        activeModelName = translator.getActiveModelName(),
                                        activeKeyInfo = translator.getActiveKeyInfo()
                                    )
                                }
                            } else {
                                val error = result.exceptionOrNull()?.message ?: "Lỗi dịch ảnh"
                                updatePageStatus(index, TranslationStatus.ERROR, error)
                                _uiState.update {
                                    it.copy(
                                        activeModelName = translator.getActiveModelName(),
                                        activeKeyInfo = translator.getActiveKeyInfo()
                                    )
                                }
                            }
                        } else {
                            updatePageStatus(index, TranslationStatus.ERROR, "Không đọc được file ảnh")
                        }

                        val count = completedCounter.incrementAndGet()
                        _uiState.update {
                            it.copy(
                                batchProgress = count.toFloat() / totalPages,
                                batchCurrentPage = count,
                                statusMessage = "Đã dịch $count/$totalPages trang"
                            )
                        }

                        // Pacing delay (1200ms) to avoid RPM/TPM API overload limits
                        delay(1200L)
                    }
                }
            }

            jobs.awaitAll()

            _uiState.update {
                it.copy(
                    isBatchTranslating = false,
                    batchProgress = 1f,
                    statusMessage = "Hoàn thành dịch hàng loạt! Chạm vào bất kỳ ô thoại nào để xem bản dịch."
                )
            }
        }
    }

    /**
     * Cancels an ongoing batch translation.
     */
    fun cancelBatchTranslation() {
        batchTranslationJob?.cancel()
        _uiState.update {
            it.copy(
                isBatchTranslating = false,
                statusMessage = "Đã dừng tiến trình dịch hàng loạt."
            )
        }
    }

    /**
     * Translates a single page with disk cache check and subsequent prefetching.
     */
    fun translateSinglePage(context: Context, pageIndex: Int) {
        this.appContext = context.applicationContext
        val pages = _uiState.value.pages
        if (pageIndex !in pages.indices) return

        viewModelScope.launch {
            val page = pages[pageIndex]

            // 1. Check disk cache first
            val cached = repository.loadPageTranslationCache(
                context,
                page.name,
                page.uri,
                page.width,
                page.height
            )
            if (cached != null) {
                _uiState.update { state ->
                    val updated = state.pages.toMutableList()
                    if (pageIndex in updated.indices) {
                        updated[pageIndex] = updated[pageIndex].copy(
                            translationStatus = TranslationStatus.COMPLETED,
                            bubbles = cached.first,
                            width = cached.second.first,
                            height = cached.second.second,
                            errorMessage = null
                        )
                    }
                    state.copy(
                        pages = updated,
                        statusMessage = "Đã nạp bản dịch trang ${pageIndex + 1} từ bộ nhớ đệm máy!"
                    )
                }
                // Prefetch next 2 pages in background
                prefetchPages(context, pageIndex)
                return@launch
            }

            // 2. Call Gemini API
            updatePageStatus(pageIndex, TranslationStatus.TRANSLATING, null)
            _uiState.update {
                it.copy(statusMessage = "Đang gửi ảnh trang ${pageIndex + 1} lên Gemini...")
            }

            val bitmap = repository.loadBitmap(context, page.uri)
            if (bitmap == null) {
                updatePageStatus(pageIndex, TranslationStatus.ERROR, "Không đọc được file ảnh")
                return@launch
            }

            val result = translator.translateComicPage(bitmap)
            if (result.isSuccess) {
                val bubbles = result.getOrNull() ?: emptyList()
                val updatedPage = page.copy(
                    translationStatus = TranslationStatus.COMPLETED,
                    bubbles = bubbles,
                    width = bitmap.width,
                    height = bitmap.height,
                    errorMessage = null
                )
                // Save translation to cache
                repository.savePageTranslationCache(context, updatedPage)

                _uiState.update { state ->
                    val updated = state.pages.toMutableList()
                    if (pageIndex in updated.indices) {
                        updated[pageIndex] = updatedPage
                    }
                    state.copy(
                        pages = updated,
                        activeModelName = translator.getActiveModelName(),
                        activeKeyInfo = translator.getActiveKeyInfo(),
                        statusMessage = "Đã dịch xong trang ${pageIndex + 1}: phát hiện ${bubbles.size} ô thoại!"
                    )
                }

                // Smart prefetch next 2 pages in background
                prefetchPages(context, pageIndex)
            } else {
                val err = result.exceptionOrNull()?.message ?: "Lỗi không xác định"
                updatePageStatus(pageIndex, TranslationStatus.ERROR, err)
                _uiState.update {
                    it.copy(
                        activeModelName = translator.getActiveModelName(),
                        activeKeyInfo = translator.getActiveKeyInfo(),
                        statusMessage = "Lỗi khi dịch trang: $err"
                    )
                }
            }
        }
    }

    /**
     * Smart prefetching: automatically pre-translates the next 2 pages in background
     * so user has translations immediately when flipping pages.
     */
    fun prefetchPages(context: Context, currentIndex: Int) {
        if (_uiState.value.isBatchTranslating) return

        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val pages = _uiState.value.pages
            val targetIndices = listOf(currentIndex + 1, currentIndex + 2)

            for (targetIdx in targetIndices) {
                if (targetIdx in pages.indices) {
                    val targetPage = _uiState.value.pages[targetIdx]
                    if (targetPage.translationStatus == TranslationStatus.IDLE) {
                        // Check disk cache
                        val cached = repository.loadPageTranslationCache(
                            context,
                            targetPage.name,
                            targetPage.uri,
                            targetPage.width,
                            targetPage.height
                        )
                        if (cached != null) {
                            _uiState.update { state ->
                                val updated = state.pages.toMutableList()
                                if (targetIdx in updated.indices) {
                                    updated[targetIdx] = updated[targetIdx].copy(
                                        translationStatus = TranslationStatus.COMPLETED,
                                        bubbles = cached.first,
                                        width = cached.second.first,
                                        height = cached.second.second,
                                        errorMessage = null
                                    )
                                }
                                state.copy(pages = updated)
                            }
                            continue
                        }

                        // Background API translate with safe delay
                        val bitmap = repository.loadBitmap(context, targetPage.uri) ?: continue
                        val result = translator.translateComicPage(bitmap)
                        if (result.isSuccess) {
                            val bubbles = result.getOrNull() ?: emptyList()
                            val updatedPage = targetPage.copy(
                                translationStatus = TranslationStatus.COMPLETED,
                                bubbles = bubbles,
                                width = bitmap.width,
                                height = bitmap.height,
                                errorMessage = null
                            )
                            repository.savePageTranslationCache(context, updatedPage)
                            _uiState.update { state ->
                                val updated = state.pages.toMutableList()
                                if (targetIdx in updated.indices) {
                                    updated[targetIdx] = updatedPage
                                }
                                state.copy(pages = updated)
                            }
                        }
                        // Pacing between prefetch calls
                        delay(1200L)
                    }
                }
            }
        }
    }

    private fun updatePageStatus(index: Int, status: TranslationStatus, error: String?) {
        _uiState.update { state ->
            val updated = state.pages.toMutableList()
            if (index in updated.indices) {
                updated[index] = updated[index].copy(
                    translationStatus = status,
                    errorMessage = error
                )
            }
            state.copy(pages = updated)
        }
    }

    fun selectPage(index: Int, context: Context? = null) {
        if (index in _uiState.value.pages.indices) {
            _uiState.update {
                it.copy(
                    selectedPageIndex = index,
                    selectedBubbleIndex = null
                )
            }
            val ctx = context ?: appContext
            if (ctx != null) {
                prefetchPages(ctx, index)
            }
        }
    }

    fun nextPage(context: Context? = null) {
        val current = _uiState.value.selectedPageIndex
        if (current < _uiState.value.pages.size - 1) {
            selectPage(current + 1, context)
        }
    }

    fun previousPage(context: Context? = null) {
        val current = _uiState.value.selectedPageIndex
        if (current > 0) {
            selectPage(current - 1, context)
        }
    }

    fun onBubbleSelected(bubbleIndex: Int?) {
        _uiState.update { it.copy(selectedBubbleIndex = bubbleIndex) }
    }

    fun toggleHints() {
        _uiState.update { it.copy(showBubbleHints = !it.showBubbleHints) }
    }

    fun toggleSchemaDialog(show: Boolean) {
        _uiState.update { it.copy(showSchemaDialog = show) }
    }

    fun toggleSettingsDialog(show: Boolean) {
        _uiState.update { it.copy(showSettingsDialog = show) }
    }

    fun setOverlayScale(scale: Float) {
        _uiState.update { it.copy(overlayScale = scale.coerceIn(0.8f, 2.2f)) }
    }

    fun setFontSize(sizeSp: Float) {
        _uiState.update { it.copy(fontSizeSp = sizeSp.coerceIn(8f, 20f)) }
    }

    fun increaseFontSize() {
        _uiState.update { it.copy(fontSizeSp = (it.fontSizeSp + 1f).coerceAtMost(22f), autoFitFont = false) }
    }

    fun decreaseFontSize() {
        _uiState.update { it.copy(fontSizeSp = (it.fontSizeSp - 1f).coerceAtLeast(8f), autoFitFont = false) }
    }

    fun toggleAutoFitFont() {
        _uiState.update { it.copy(autoFitFont = !it.autoFitFont) }
    }

    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode, selectedBubbleIndex = null) }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    companion object {
        private const val PREFS_NAME = "mangalens_prefs"
        private const val KEY_CUSTOM_API_KEYS = "custom_gemini_api_keys"
    }

    /**
     * Loads saved custom API keys from SharedPreferences into memory and GeminiComicTranslator.
     */
    fun loadCustomApiKeys(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedText = prefs.getString(KEY_CUSTOM_API_KEYS, "") ?: ""
        val keys = parseApiKeys(savedText)
        translator.setCustomApiKeys(keys)
        _uiState.update {
            it.copy(
                customApiKeysText = savedText,
                customKeyCount = keys.size,
                activeKeyInfo = translator.getActiveKeyInfo()
            )
        }
    }

    /**
     * Saves user-entered API keys to SharedPreferences and updates GeminiComicTranslator.
     */
    fun saveCustomApiKeys(context: Context, keysText: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_API_KEYS, keysText.trim()).apply()
        val keys = parseApiKeys(keysText)
        translator.setCustomApiKeys(keys)
        _uiState.update {
            it.copy(
                customApiKeysText = keysText.trim(),
                customKeyCount = keys.size,
                activeKeyInfo = translator.getActiveKeyInfo(),
                statusMessage = if (keys.isNotEmpty()) "Đã lưu ${keys.size} Gemini API Key thành công!" else "Đã xóa danh sách key tùy chỉnh"
            )
        }
    }

    /**
     * Clears all custom API keys and reverts to the default system API key.
     */
    fun clearCustomApiKeys(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_CUSTOM_API_KEYS).apply()
        translator.setCustomApiKeys(emptyList())
        _uiState.update {
            it.copy(
                customApiKeysText = "",
                customKeyCount = 0,
                activeKeyInfo = translator.getActiveKeyInfo(),
                statusMessage = "Đã xóa API keys tùy chỉnh, trở về key mặc định hệ thống."
            )
        }
    }

    private fun parseApiKeys(input: String): List<String> {
        return input.split("\n", ",", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}
