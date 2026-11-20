package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.example.model.ComicPage
import com.example.model.SpeechBubble
import com.example.model.TranslationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class ComicRepository {

    /**
     * Reads all image files from a user-selected SAF Tree Uri.
     */
    suspend fun loadPagesFromFolderUri(context: Context, treeUri: Uri): List<ComicPage> = withContext(Dispatchers.IO) {
        val pages = mutableListOf<ComicPage>()
        try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val childDocId = cursor.getString(idIndex)
                    val name = cursor.getString(nameIndex) ?: "Page_${cursor.position}"
                    val mimeType = cursor.getString(mimeIndex) ?: ""

                    val isImage = mimeType.startsWith("image/") ||
                            name.endsWith(".jpg", true) ||
                            name.endsWith(".jpeg", true) ||
                            name.endsWith(".png", true) ||
                            name.endsWith(".webp", true)

                    if (isImage) {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                        val dimensions = getImageDimensions(context, fileUri)
                        val cached = loadPageTranslationCache(context, name, fileUri, dimensions.first, dimensions.second)
                        val status = if (cached != null) TranslationStatus.COMPLETED else TranslationStatus.IDLE
                        val bubbles = cached?.first ?: emptyList()
                        val actualWidth = cached?.second?.first ?: dimensions.first
                        val actualHeight = cached?.second?.second ?: dimensions.second

                        pages.add(
                            ComicPage(
                                id = childDocId,
                                name = name,
                                uri = fileUri,
                                width = actualWidth,
                                height = actualHeight,
                                translationStatus = status,
                                bubbles = bubbles
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Sort files by natural alphanumeric order (e.g. 1, 2, 10 instead of 1, 10, 2)
        return@withContext pages.sortedWith(Comparator { a, b ->
            naturalCompare(a.name, b.name)
        })
    }

    /**
     * Reads image dimensions without fully loading the bitmap into memory.
     */
    private fun getImageDimensions(context: Context, uri: Uri): Pair<Int, Int> {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            Pair(options.outWidth.coerceAtLeast(800), options.outHeight.coerceAtLeast(1200))
        } catch (e: Exception) {
            Pair(800, 1200)
        }
    }

    /**
     * Loads a Bitmap from a content Uri or file Uri.
     */
    suspend fun loadBitmap(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            var stream: InputStream? = context.contentResolver.openInputStream(uri)
            if (stream == null) {
                // If it's a file path
                val file = File(uri.path ?: return@withContext null)
                if (file.exists()) {
                    return@withContext BitmapFactory.decodeFile(file.absolutePath)
                }
            }
            stream?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getCacheKey(pageName: String, uri: Uri, width: Int, height: Int): String {
        val raw = "${pageName}_${width}x${height}_${uri.lastPathSegment ?: uri}"
        return try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(raw.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            raw.replace("[^a-zA-Z0-9]".toRegex(), "_")
        }
    }

    fun savePageTranslationCache(context: Context, page: ComicPage) {
        if (page.bubbles.isEmpty() || page.translationStatus != TranslationStatus.COMPLETED) return
        try {
            val cacheDir = File(context.filesDir, "translation_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val key = getCacheKey(page.name, page.uri, page.width, page.height)
            val file = File(cacheDir, "$key.json")

            val jsonArray = JSONArray()
            for (b in page.bubbles) {
                val obj = JSONObject().apply {
                    val box = JSONArray().apply {
                        put(b.ymin)
                        put(b.xmin)
                        put(b.ymax)
                        put(b.xmax)
                    }
                    put("box_2d", box)
                    put("translated_text", b.translatedText)
                    if (b.originalText.isNotBlank()) put("original_text", b.originalText)
                }
                jsonArray.put(obj)
            }

            val wrapper = JSONObject().apply {
                put("name", page.name)
                put("width", page.width)
                put("height", page.height)
                put("bubbles", jsonArray)
            }
            file.writeText(wrapper.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadPageTranslationCache(context: Context, pageName: String, uri: Uri, width: Int, height: Int): Pair<List<SpeechBubble>, Pair<Int, Int>>? {
        try {
            val cacheDir = File(context.filesDir, "translation_cache")
            val key = getCacheKey(pageName, uri, width, height)
            val file = File(cacheDir, "$key.json")
            if (!file.exists()) return null
            val text = file.readText()
            val wrapper = JSONObject(text)
            val bubblesArray = wrapper.optJSONArray("bubbles") ?: return null
            val bubbles = SpeechBubble.fromJsonArray(bubblesArray)
            if (bubbles.isEmpty()) return null
            val w = wrapper.optInt("width", width)
            val h = wrapper.optInt("height", height)
            return Pair(bubbles, Pair(w, h))
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Creates and loads 3 sample manga pages for instant testing in the emulator.
     */
    suspend fun getDemoComicPages(context: Context): List<ComicPage> = withContext(Dispatchers.IO) {
        val demoDir = File(context.cacheDir, "sample_manga")
        if (!demoDir.exists()) {
            demoDir.mkdirs()
        }

        val pages = mutableListOf<ComicPage>()
        val pageConfigs = listOf(
            Triple("Trang 1 - Khởi đầu kỳ bí", "page_01.jpg", listOf(
                SampleBubbleInfo(120, 580, 260, 920, "ここはどこだ…？！", "Nơi quái quỷ này là đâu thế này...?!"),
                SampleBubbleInfo(540, 80, 720, 450, "Cẩn thận! Kẻ thù đang ẩn nấp trong bóng tối!", "Cẩn thận! Kẻ thù đang ẩn nấp trong bóng tối!")
            )),
            Triple("Trang 2 - Cuộc chạm trán", "page_02.jpg", listOf(
                SampleBubbleInfo(80, 520, 240, 880, "Dừng lại! Ngươi là ai mà dám bén mảng tới đây?!", "Dừng lại! Ngươi là ai mà dám bén mảng tới đây?!"),
                SampleBubbleInfo(420, 100, 580, 480, "Hừ, đừng hòng thoát khỏi tay ta!", "Hừ, đừng hòng thoát khỏi tay ta!"),
                SampleBubbleInfo(760, 550, 910, 920, "Biến hình! Tuyệt kỹ bộc phá!", "Biến hình! Tuyệt kỹ bộc phá!")
            )),
            Triple("Trang 3 - Hồi kết gay cấn", "page_03.jpg", listOf(
                SampleBubbleInfo(100, 120, 280, 520, "Sức mạnh này... không thể nào tin được!", "Sức mạnh này... không thể nào tin được!"),
                SampleBubbleInfo(600, 550, 800, 900, "Mau đi thôi, trước khi quá muộn!", "Mau đi thôi, trước khi quá muộn!")
            ))
        )

        for ((title, filename, bubbles) in pageConfigs) {
            val file = File(demoDir, filename)
            if (!file.exists()) {
                val bitmap = createSampleMangaBitmap(title, bubbles)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }

            val uri = Uri.fromFile(file)
            val bubbleList = bubbles.map {
                SpeechBubble(
                    ymin = it.ymin,
                    xmin = it.xmin,
                    ymax = it.ymax,
                    xmax = it.xmax,
                    originalText = it.orig,
                    translatedText = it.trans
                )
            }

            pages.add(
                ComicPage(
                    id = "demo_$filename",
                    name = title,
                    uri = uri,
                    width = 900,
                    height = 1400,
                    translationStatus = TranslationStatus.COMPLETED,
                    bubbles = bubbleList
                )
            )
        }

        return@withContext pages
    }

    private data class SampleBubbleInfo(
        val ymin: Int,
        val xmin: Int,
        val ymax: Int,
        val xmax: Int,
        val orig: String,
        val trans: String
    )

    private fun createSampleMangaBitmap(title: String, bubbles: List<SampleBubbleInfo>): Bitmap {
        val width = 900
        val height = 1400
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Manga background (paper tone)
        canvas.drawColor(Color.parseColor("#F8F9FA"))

        val paintBorder = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }

        val paintFill = Paint().apply {
            color = Color.parseColor("#18181B")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val paintText = Paint().apply {
            color = Color.BLACK
            textSize = 28f
            isAntiAlias = true
            isFakeBoldText = true
        }

        // Draw comic panels
        canvas.drawRect(40f, 40f, (width - 40).toFloat(), 400f, paintBorder)
        canvas.drawRect(40f, 430f, 430f, 920f, paintBorder)
        canvas.drawRect(460f, 430f, (width - 40).toFloat(), 920f, paintBorder)
        canvas.drawRect(40f, 950f, (width - 40).toFloat(), (height - 60).toFloat(), paintBorder)

        // Panel art / action silhouettes
        val paintGray = Paint().apply {
            color = Color.parseColor("#E4E4E7")
            style = Paint.Style.FILL
        }
        canvas.drawRect(45f, 45f, (width - 45).toFloat(), 395f, paintGray)

        // Title banner
        paintText.textSize = 34f
        paintText.color = Color.parseColor("#09090B")
        canvas.drawText("MANGA DEMO • $title", 70f, 100f, paintText)

        // Dynamic manga action lines
        val paintActionLine = Paint().apply {
            color = Color.parseColor("#71717A")
            strokeWidth = 2f
            isAntiAlias = true
        }
        for (i in 0..12) {
            canvas.drawLine(60f + i * 60f, 140f, 400f, 380f, paintActionLine)
        }

        // Draw character silhouette in panel 2
        val silhouettePaint = Paint().apply {
            color = Color.parseColor("#27272A")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(235f, 650f, 90f, silhouettePaint)
        canvas.drawRect(120f, 730f, 350f, 910f, silhouettePaint)

        // Draw bubbles based on coordinates
        val bubblePaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val bubbleStroke = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 5f
            isAntiAlias = true
        }
        val bubbleTextPaint = Paint().apply {
            color = Color.BLACK
            textSize = 26f
            isAntiAlias = true
            isFakeBoldText = true
        }

        for (b in bubbles) {
            val left = (b.xmin / 1000f) * width
            val top = (b.ymin / 1000f) * height
            val right = (b.xmax / 1000f) * width
            val bottom = (b.ymax / 1000f) * height

            val rect = RectF(left, top, right, bottom)
            canvas.drawRoundRect(rect, 40f, 40f, bubblePaint)
            canvas.drawRoundRect(rect, 40f, 40f, bubbleStroke)

            // Draw a speech pointer tail
            val tailPath = Path().apply {
                moveTo(rect.centerX() - 15f, rect.bottom - 2f)
                lineTo(rect.centerX(), rect.bottom + 25f)
                lineTo(rect.centerX() + 20f, rect.bottom - 2f)
                close()
            }
            canvas.drawPath(tailPath, bubblePaint)
            canvas.drawPath(tailPath, bubbleStroke)

            // Text inside bubble (original comic text)
            val words = b.orig.chunked(12)
            var textY = rect.top + 50f
            for (line in words) {
                if (textY + 20f < rect.bottom) {
                    canvas.drawText(line, rect.left + 25f, textY, bubbleTextPaint)
                    textY += 34f
                }
            }
        }

        return bitmap
    }

    /**
     * Natural string sorting comparing numbers inside filenames naturally.
     */
    private fun naturalCompare(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        var nza: Int
        var nzb: Int
        var ca: Char
        var cb: Char

        while (true) {
            // Only count the number of zeroes leading the last number compared
            nza = 0
            nzb = 0

            ca = charAt(a, ia)
            cb = charAt(b, ib)

            // Skip over leading spaces or zeros
            while (Character.isSpaceChar(ca) || ca == '0') {
                if (ca == '0') {
                    nza++
                } else {
                    // Only count consecutive zeroes
                    nza = 0
                }
                ca = charAt(a, ++ia)
            }

            while (Character.isSpaceChar(cb) || cb == '0') {
                if (cb == '0') {
                    nzb++
                } else {
                    nzb = 0
                }
                cb = charAt(b, ++ib)
            }

            // Process run of digits
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                var bias = 0
                while (true) {
                    if (!Character.isDigit(ca) && !Character.isDigit(cb)) {
                        if (bias != 0) return bias
                        if (nza != nzb) return nza - nzb
                        break
                    }
                    if (!Character.isDigit(ca)) return -1
                    if (!Character.isDigit(cb)) return 1
                    if (ca < cb) {
                        if (bias == 0) bias = -1
                    } else if (ca > cb) {
                        if (bias == 0) bias = 1
                    }
                    ca = charAt(a, ++ia)
                    cb = charAt(b, ++ib)
                }
            }

            if (ca.code == 0 && cb.code == 0) {
                // The strings compare the same
                return nza - nzb
            }

            if (ca < cb) return -1
            if (ca > cb) return 1

            ++ia
            ++ib
        }
    }

    private fun charAt(s: String, i: Int): Char {
        return if (i >= s.length) 0.toChar() else s[i]
    }
}
