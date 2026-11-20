package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.ComicPage
import com.example.model.TranslationStatus
import com.example.ui.ComicUiState
import com.example.ui.ComicViewModel
import com.example.ui.components.InteractiveComicImage
import com.example.ui.dialogs.ApiSchemaDialog
import com.example.ui.dialogs.OverlaySettingsDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicReaderScreen(
    viewModel: ComicViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {
                // Provider may not support persistable permissions
            }
            viewModel.loadFolder(context, uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadCustomApiKeys(context)
    }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    if (uiState.showSchemaDialog) {
        ApiSchemaDialog(
            onDismiss = { viewModel.toggleSchemaDialog(false) }
        )
    }

    if (uiState.showSettingsDialog) {
        OverlaySettingsDialog(
            overlayScale = uiState.overlayScale,
            fontSizeSp = uiState.fontSizeSp,
            autoFitFont = uiState.autoFitFont,
            customKeysText = uiState.customApiKeysText,
            activeKeyInfo = uiState.activeKeyInfo,
            activeModelName = uiState.activeModelName,
            onScaleChange = { viewModel.setOverlayScale(it) },
            onFontSizeChange = { viewModel.setFontSize(it) },
            onAutoFitChange = { viewModel.toggleAutoFitFont() },
            onSaveApiKeys = { viewModel.saveCustomApiKeys(context, it) },
            onClearApiKeys = { viewModel.clearCustomApiKeys(context) },
            onResetDefaults = {
                viewModel.setOverlayScale(1.35f)
                viewModel.setFontSize(12f)
                if (!uiState.autoFitFont) viewModel.toggleAutoFitFont()
            },
            onDismiss = { viewModel.toggleSettingsDialog(false) }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding()
            )
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (uiState.pages.isNotEmpty()) uiState.folderName else "MangaLens",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (uiState.pages.isNotEmpty()) {
                            Text(
                                text = "Trang ${uiState.selectedPageIndex + 1}/${uiState.pages.size} • Đã dịch ${uiState.translatedCount}/${uiState.pages.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (uiState.pages.isNotEmpty()) {
                        IconButton(
                            onClick = { folderPickerLauncher.launch(null) },
                            modifier = Modifier.testTag("change_folder_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "Đổi thư mục",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                actions = {
                    if (uiState.pages.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.toggleHints() },
                            modifier = Modifier.testTag("toggle_hints_button")
                        ) {
                            Icon(
                                imageVector = if (uiState.showBubbleHints) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Bật/Tắt khung gợi ý ô thoại",
                                tint = if (uiState.showBubbleHints) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        IconButton(
                            onClick = { viewModel.startBatchTranslation(context) },
                            enabled = !uiState.isBatchTranslating,
                            modifier = Modifier.testTag("batch_translate_top_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = "Dịch toàn bộ",
                                tint = if (!uiState.isBatchTranslating) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                }
                            )
                        }
                    }

                    IconButton(
                        onClick = { viewModel.toggleSchemaDialog(true) },
                        modifier = Modifier.testTag("schema_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "Xem Prompt & Schema",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { viewModel.toggleSettingsDialog(true) },
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Cài đặt & API Key",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.pages.isEmpty()) {
                FolderPickerPromptView(
                    activeModelName = uiState.activeModelName,
                    onPickFolder = { folderPickerLauncher.launch(null) },
                    onLoadDemo = { viewModel.loadDemoComic(context) },
                    onViewSchema = { viewModel.toggleSchemaDialog(true) }
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Compact Batch Progress Banner
                    AnimatedVisibility(
                        visible = uiState.isBatchTranslating,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.5.dp,
                                        modifier = Modifier.size(16.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Đang dịch (${uiState.activeModelName}): ${uiState.batchCurrentPage}/${uiState.batchTotalPages}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { viewModel.cancelBatchTranslation() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Cancel,
                                            contentDescription = "Dừng dịch hàng loạt",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { uiState.batchProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(CircleShape),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Main Interactive Comic Canvas
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF09090B))
                    ) {
                        val currentPage = uiState.currentPage
                        if (currentPage != null) {
                            InteractiveComicImage(
                                page = currentPage,
                                selectedBubbleIndex = uiState.selectedBubbleIndex,
                                onBubbleSelected = { viewModel.onBubbleSelected(it) },
                                showHints = uiState.showBubbleHints,
                                overlayScale = uiState.overlayScale,
                                fontSizeSp = uiState.fontSizeSp,
                                autoFitFont = uiState.autoFitFont,
                                onIncreaseFontSize = { viewModel.increaseFontSize() },
                                onDecreaseFontSize = { viewModel.decreaseFontSize() },
                                onNextPage = { viewModel.nextPage(context) },
                                onPreviousPage = { viewModel.previousPage(context) },
                                modifier = Modifier.fillMaxSize()
                            )

                            PageStatusBadge(
                                page = currentPage,
                                onTranslatePage = {
                                    viewModel.translateSinglePage(context, uiState.selectedPageIndex)
                                },
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(12.dp)
                            )
                        }
                    }

                    // Sleek Bottom Navigation & Auto-Scrolling Thumbnail Filmstrip
                    BottomNavigationBar(
                        uiState = uiState,
                        onPrevPage = { viewModel.previousPage(context) },
                        onNextPage = { viewModel.nextPage(context) },
                        onPageSelect = { viewModel.selectPage(it, context) },
                        onTranslateCurrent = {
                            viewModel.translateSinglePage(context, uiState.selectedPageIndex)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun FolderPickerPromptView(
    activeModelName: String,
    onPickFolder: () -> Unit,
    onLoadDemo: () -> Unit,
    onViewSchema: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Editorial Hero Emblem
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .background(
                        brush = Brush.linearGradient(
                            listOf(Color(0xFF6366F1), Color(0xFFEC4899))
                        ),
                        shape = RoundedCornerShape(24.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Active Model Pill
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = activeModelName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Dịch Truyện Tranh Thông Minh",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Tự động nhận diện khung thoại và hiển thị bản dịch tiếng Việt sắc nét ngay trên trang truyện gốc.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Clean Feature Highlights Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    FeatureRow(
                        icon = Icons.Default.Layers,
                        title = "Giữ nguyên nét vẽ gốc 100%",
                        subtitle = "Chạm ô thoại để hiện khung dịch nổi, kéo di chuyển & đổi cỡ tự do"
                    )
                    FeatureRow(
                        icon = Icons.Default.ZoomIn,
                        title = "Thu phóng & lật trang mượt mà",
                        subtitle = "Hỗ trợ chụm tay, chạm đúp, cuộn chuột và nút +/- phóng to không vỡ khung"
                    )
                    FeatureRow(
                        icon = Icons.Default.AutoAwesome,
                        title = "Dịch nhanh song song & lưu đệm",
                        subtitle = "Tự động chuyển đổi model dự phòng và xoay vòng nhiều API Key"
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Primary CTA
            Button(
                onClick = onPickFolder,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("pick_folder_button"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Chọn Thư Mục Truyện",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Secondary CTA
            FilledTonalButton(
                onClick = onLoadDemo,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("load_demo_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Mở Bộ Truyện Mẫu (Demo)",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Tertiary CTA
            OutlinedButton(
                onClick = onViewSchema,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .testTag("view_spec_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Xem Prompt & JSON Schema",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
fun FeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PageStatusBadge(
    page: ComicPage,
    onTranslatePage: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Keep reading view completely uncluttered once translation is completed
    if (page.translationStatus == TranslationStatus.COMPLETED) {
        return
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = Color(0xD90B1120),
        border = BorderStroke(1.dp, Color(0x40FFFFFF)),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (page.translationStatus) {
                TranslationStatus.COMPLETED -> Unit
                TranslationStatus.TRANSLATING -> {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                        color = Color(0xFF818CF8)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Đang dịch trang...",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                TranslationStatus.ERROR -> {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Lỗi dịch",
                        tint = Color(0xFFF87171),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Lỗi dịch • Chạm thử lại",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { onTranslatePage() }
                    )
                }
                TranslationStatus.IDLE -> {
                    Text(
                        text = "Chưa dịch",
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF4F46E5))
                            .clickable { onTranslatePage() }
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "Dịch ngay",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BottomNavigationBar(
    uiState: ComicUiState,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onPageSelect: (Int) -> Unit,
    onTranslateCurrent: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filmstripState = rememberLazyListState()

    // Automatically keep active page thumbnail centered in view when flipping pages
    LaunchedEffect(uiState.selectedPageIndex) {
        if (uiState.pages.isNotEmpty() && uiState.selectedPageIndex in uiState.pages.indices) {
            filmstripState.animateScrollToItem(
                index = (uiState.selectedPageIndex - 2).coerceAtLeast(0)
            )
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 6.dp)
            ) {
                // Horizontal Thumbnail Filmstrip
                LazyRow(
                    state = filmstripState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(uiState.pages, key = { idx, p -> "${p.id}_$idx" }) { index, page ->
                        val isSelected = index == uiState.selectedPageIndex
                        val isTranslated = page.translationStatus == TranslationStatus.COMPLETED

                        Box(
                            modifier = Modifier
                                .width(42.dp)
                                .height(58.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onPageSelect(index) }
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(page.uri)
                                    .size(120, 160)
                                    .build(),
                                contentDescription = page.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )

                            if (isTranslated) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(3.dp)
                                        .size(7.dp)
                                        .background(Color(0xFF10B981), CircleShape)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(Color(0xA6000000))
                                    .padding(vertical = 1.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Controls Row: Prev | Slider | Next | Compact Translate Icon
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 720.dp)
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onPrevPage,
                        enabled = uiState.selectedPageIndex > 0,
                        modifier = Modifier.testTag("prev_page_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Trang trước"
                        )
                    }

                    if (uiState.pages.size > 1) {
                        Slider(
                            value = uiState.selectedPageIndex.toFloat(),
                            onValueChange = { onPageSelect(it.toInt()) },
                            valueRange = 0f..(uiState.pages.size - 1).toFloat(),
                            steps = (uiState.pages.size - 2).coerceAtLeast(0),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    IconButton(
                        onClick = onNextPage,
                        enabled = uiState.selectedPageIndex < uiState.pages.size - 1,
                        modifier = Modifier.testTag("next_page_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Trang sau"
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    val isCompleted = uiState.currentPage?.translationStatus == TranslationStatus.COMPLETED
                    val isTranslating = uiState.currentPage?.translationStatus == TranslationStatus.TRANSLATING

                    FilledIconButton(
                        onClick = onTranslateCurrent,
                        enabled = !isTranslating,
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("translate_current_page_button"),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isCompleted) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            contentColor = if (isCompleted) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            }
                        )
                    ) {
                        if (isTranslating) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = if (isCompleted) Icons.Default.Refresh else Icons.Default.Translate,
                                contentDescription = if (isCompleted) "Dịch lại trang này" else "Dịch trang này",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
