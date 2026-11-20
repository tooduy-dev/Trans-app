package com.example.network

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.model.SpeechBubble
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class GeminiComicTranslator {

    companion object {
        private const val TAG = "GeminiTranslator"

        // Current production Gemini Flash models (gemini-2.5-flash is deprecated)
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        val FALLBACK_MODELS = listOf(
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite",
            "gemini-flash-latest",
            "gemini-flash-lite-latest"
        )

        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val SYSTEM_INSTRUCTION = """
            Bạn là chuyên gia OCR và dịch thuật truyện tranh manga/manhwa/comic chuyên nghiệp.
            Nhiệm vụ của bạn là nhận diện chính xác tọa độ khung chữ nhật của tất cả các ô thoại và dịch sang Tiếng Việt tự nhiên, sống động, sát ngữ cảnh truyện tranh.
            Luôn trả về đúng định dạng JSON Array tuân thủ tuyệt đối responseSchema.
        """.trimIndent()

        val PROMPT_INSTRUCTION = """
            Hãy phân tích trang truyện tranh này và nhận diện TẤT CẢ các ô hội thoại (speech bubbles, thought bubbles, text captions, sound effects quan trọng).

            Với mỗi ô thoại được phát hiện:
            1. Xác định tọa độ khung chữ nhật `box_2d` theo chuẩn [ymin, xmin, ymax, xmax] trên thang đo 0 đến 1000 (0 là góc trên/trái, 1000 là góc dưới/phải của ảnh gốc).
            2. Dịch trọn vẹn nội dung sang Tiếng Việt với văn phong truyện tranh sống động, tự nhiên, biểu cảm sắc nét, chân thực theo đúng bối cảnh truyện (không kiểm duyệt gượng gạo, giữ trọn ngữ khí nhân vật) vào `translated_text`.

            BẮT BUỘC trả về định dạng mảng JSON thuần túy (JSON Array). Nếu trang không có chữ/ô thoại nào, trả về [].
        """.trimIndent()

        val JSON_SCHEMA_STRING = """
            {
              "type": "ARRAY",
              "items": {
                "type": "OBJECT",
                "properties": {
                  "box_2d": {
                    "type": "ARRAY",
                    "items": { "type": "INTEGER" },
                    "description": "[ymin, xmin, ymax, xmax] on a 0-1000 normalized scale"
                  },
                  "translated_text": {
                    "type": "STRING",
                    "description": "Natural Vietnamese comic translation"
                  }
                },
                "required": ["box_2d", "translated_text"]
              }
            }
        """.trimIndent()

        private val SAFETY_CATEGORIES = listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentModel = DEFAULT_MODEL

    @Volatile
    private var customApiKeys: List<String> = emptyList()

    @Volatile
    private var currentKeyIndex: Int = 0

    fun getActiveModelName(): String = currentModel

    fun setCustomApiKeys(keys: List<String>) {
        customApiKeys = keys.map { it.trim() }.filter { it.isNotBlank() }
        currentKeyIndex = 0
    }

    fun getAllAvailableKeys(): List<String> {
        val buildKey = BuildConfig.GEMINI_API_KEY.trim()
        val validBuildKey = if (
            buildKey.isNotBlank() &&
            buildKey != "MY_GEMINI_API_KEY" &&
            !buildKey.startsWith("YOUR_")
        ) {
            listOf(buildKey)
        } else {
            emptyList()
        }
        return (customApiKeys + validBuildKey).distinct()
    }

    fun getCustomKeyCount(): Int = customApiKeys.size

    fun getActiveKeyInfo(): String {
        val allKeys = getAllAvailableKeys()
        if (allKeys.isEmpty()) return "Chưa có API Key nào (cần thêm trong Cài đặt hoặc Secrets)"
        val activeIndex = (currentKeyIndex % allKeys.size) + 1
        val buildKey = BuildConfig.GEMINI_API_KEY.trim()
        val hasBuildKey = buildKey.isNotBlank() && buildKey != "MY_GEMINI_API_KEY"
        return if (customApiKeys.isNotEmpty()) {
            "Đang dùng Key tùy chỉnh ($activeIndex/${customApiKeys.size})${if (hasBuildKey) " + 1 key hệ thống" else ""}"
        } else {
            "Đang dùng Key môi trường (Secrets / BuildConfig)"
        }
    }

    /**
     * Sends an image bitmap to the Gemini REST API (v1beta) to detect and translate speech bubbles.
     * - Passes API key securely via `x-goog-api-key` header (instead of URL query parameter).
     * - Uses current Gemini 3.x Flash models with automatic fallback chain.
     * - Automatically rotates across multiple API keys on quota/auth errors.
     * - Applies exponential backoff with jitter on 429/503/transient failures.
     */
    suspend fun translateComicPage(bitmap: Bitmap): Result<List<SpeechBubble>> = withContext(Dispatchers.IO) {
        val availableKeys = getAllAvailableKeys()
        if (availableKeys.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("Chưa cấu hình Gemini API Key. Vui lòng thêm key trong Cài đặt hoặc biến môi trường GEMINI_API_KEY.")
            )
        }

        val scaledBitmap = prepareBitmap(bitmap)
        val shouldRecycleScaled = scaledBitmap !== bitmap

        try {
            val base64Image = bitmapToBase64(scaledBitmap)
            val requestBodyJson = buildRequestBodyJson(base64Image)
            val requestBodyString = requestBodyJson.toString()

            val totalKeys = availableKeys.size
            var lastErrorMsg = "Không nhận được phản hồi từ Gemini API"

            for (keyAttempt in 0 until totalKeys) {
                val activeKeyIdx = (currentKeyIndex + keyAttempt) % totalKeys
                val apiKey = availableKeys[activeKeyIdx]
                val keyMasked = if (apiKey.length > 8) "${apiKey.take(4)}...${apiKey.takeLast(4)}" else "***"

                val modelsToTry = (listOf(currentModel) + FALLBACK_MODELS).distinct()
                var switchToNextKey = false

                for (model in modelsToTry) {
                    if (switchToNextKey) break
                    val url = "$BASE_URL/$model:generateContent"

                    for (attempt in 1..3) {
                        val request = Request.Builder()
                            .url(url)
                            .addHeader("x-goog-api-key", apiKey)
                            .addHeader("Content-Type", "application/json; charset=utf-8")
                            .post(requestBodyString.toRequestBody(JSON_MEDIA_TYPE))
                            .build()

                        try {
                            client.newCall(request).execute().use { response ->
                                val responseBody = response.body?.string().orEmpty()
                                if (response.isSuccessful) {
                                    val parseResult = parseGeminiResponse(responseBody)
                                    if (parseResult.isSuccess) {
                                        currentModel = model
                                        currentKeyIndex = activeKeyIdx
                                        val bubbles = parseResult.getOrDefault(emptyList())
                                        Log.d(TAG, "[$model - key $keyMasked] Detected ${bubbles.size} bubbles")
                                        return@withContext Result.success(bubbles)
                                    } else {
                                        val parseError = parseResult.exceptionOrNull()?.message
                                            ?: "Không thể phân tích phản hồi JSON"
                                        Log.w(TAG, "[$model] Parse/safety issue on attempt $attempt: $parseError")
                                        lastErrorMsg = parseError
                                        if (attempt < 2) {
                                            delay(1200L)
                                        } else {
                                            break // Try next fallback model
                                        }
                                    }
                                } else {
                                    val errorMsg = extractApiErrorMessage(response.code, responseBody)
                                    Log.w(TAG, "Gemini API error ($model, key $keyMasked, HTTP ${response.code}, attempt $attempt): $errorMsg")
                                    lastErrorMsg = errorMsg

                                    val isInvalidKeyOrAuth = response.code == 401 ||
                                        response.code == 403 ||
                                        errorMsg.contains("API_KEY_INVALID", ignoreCase = true) ||
                                        errorMsg.contains("API key not valid", ignoreCase = true) ||
                                        errorMsg.contains("permission denied", ignoreCase = true)

                                    val isQuotaExhausted = response.code == 429 ||
                                        errorMsg.contains("quota", ignoreCase = true) ||
                                        errorMsg.contains("resource exhausted", ignoreCase = true) ||
                                        errorMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true)

                                    if ((isInvalidKeyOrAuth || isQuotaExhausted) && totalKeys > 1) {
                                        Log.w(TAG, "Key $keyMasked hit auth/quota error. Rotating to next key...")
                                        currentKeyIndex = (activeKeyIdx + 1) % totalKeys
                                        switchToNextKey = true
                                        break
                                    }

                                    if (isInvalidKeyOrAuth) {
                                        return@withContext Result.failure(
                                            Exception("API Key không hợp lệ hoặc bị từ chối: $errorMsg")
                                        )
                                    }

                                    val isModelUnavailable = response.code == 404 ||
                                        errorMsg.contains("not found", ignoreCase = true) ||
                                        errorMsg.contains("no longer available", ignoreCase = true) ||
                                        errorMsg.contains("deprecated", ignoreCase = true) ||
                                        errorMsg.contains("not supported", ignoreCase = true)

                                    if (isModelUnavailable) {
                                        Log.w(TAG, "[$model] Unavailable ($errorMsg). Falling back immediately...")
                                        break // Try next model
                                    }

                                    val isTransientOverload = response.code == 429 ||
                                        response.code == 500 ||
                                        response.code == 503 ||
                                        response.code == 504 ||
                                        errorMsg.contains("high demand", ignoreCase = true) ||
                                        errorMsg.contains("overloaded", ignoreCase = true) ||
                                        errorMsg.contains("too many requests", ignoreCase = true) ||
                                        errorMsg.contains("unavailable", ignoreCase = true)

                                    if (isTransientOverload) {
                                        if (attempt < 3) {
                                            val baseDelay = when (attempt) {
                                                1 -> 2000L
                                                2 -> 4000L
                                                else -> 6000L
                                            }
                                            val jitter = Random.nextLong(150L, 650L)
                                            Log.i(TAG, "[$model] Overloaded, retrying in ${baseDelay + jitter}ms (attempt $attempt/3)...")
                                            delay(baseDelay + jitter)
                                        } else {
                                            Log.w(TAG, "[$model] Still overloaded after 3 attempts. Switching model...")
                                            break // Try next model
                                        }
                                    } else {
                                        // Other 4xx client error
                                        return@withContext Result.failure(Exception("Lỗi Gemini API: $errorMsg"))
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            lastErrorMsg = e.message ?: "Lỗi kết nối mạng"
                            Log.w(TAG, "Network failure on $model (attempt $attempt): $lastErrorMsg")
                            if (attempt < 3) {
                                val baseDelay = if (attempt == 1) 1800L else 3600L
                                delay(baseDelay + Random.nextLong(100L, 500L))
                            } else {
                                break
                            }
                        }
                    }
                }
            }

            Result.failure(Exception("Lỗi Gemini API: $lastErrorMsg"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed", e)
            Result.failure(e)
        } finally {
            if (shouldRecycleScaled && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
        }
    }

    private fun extractApiErrorMessage(httpCode: Int, responseBody: String): String {
        return try {
            val errJson = JSONObject(responseBody)
            val errObj = errJson.optJSONObject("error")
            val message = errObj?.optString("message").orEmpty()
            val status = errObj?.optString("status").orEmpty()
            when {
                message.isNotBlank() && status.isNotBlank() -> "$message ($status)"
                message.isNotBlank() -> message
                else -> "HTTP $httpCode"
            }
        } catch (_: Exception) {
            val preview = responseBody.take(180).trim()
            if (preview.isNotBlank()) "HTTP $httpCode: $preview" else "HTTP $httpCode"
        }
    }

    private fun prepareBitmap(original: Bitmap): Bitmap {
        val maxDimension = 1280
        val width = original.width
        val height = original.height

        if (width <= maxDimension && height <= maxDimension) {
            return original
        }

        val ratio = width.toFloat() / height.toFloat()
        val targetWidth: Int
        val targetHeight: Int

        if (width > height) {
            targetWidth = maxDimension
            targetHeight = (maxDimension / ratio).toInt().coerceAtLeast(1)
        } else {
            targetHeight = maxDimension
            targetWidth = (maxDimension * ratio).toInt().coerceAtLeast(1)
        }

        return Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        val byteArray = stream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun buildRequestBodyJson(base64Image: String): JSONObject {
        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply {
                    put("text", SYSTEM_INSTRUCTION)
                })
            })
        }

        val partsArray = JSONArray().apply {
            put(JSONObject().apply {
                put("text", PROMPT_INSTRUCTION)
            })
            put(JSONObject().apply {
                put("inlineData", JSONObject().apply {
                    put("mimeType", "image/jpeg")
                    put("data", base64Image)
                })
            })
        }

        val contentsArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("parts", partsArray)
            })
        }

        val generationConfig = JSONObject().apply {
            put("responseMimeType", "application/json")
            put("temperature", 0.25)
            put("responseSchema", JSONObject(JSON_SCHEMA_STRING))
        }

        val safetySettings = JSONArray().apply {
            SAFETY_CATEGORIES.forEach { category ->
                put(JSONObject().apply {
                    put("category", category)
                    put("threshold", "BLOCK_NONE")
                })
            }
        }

        return JSONObject().apply {
            put("systemInstruction", systemInstruction)
            put("contents", contentsArray)
            put("generationConfig", generationConfig)
            put("safetySettings", safetySettings)
        }
    }

    private fun parseGeminiResponse(responseJsonStr: String): Result<List<SpeechBubble>> {
        val root = try {
            JSONObject(responseJsonStr)
        } catch (e: Exception) {
            return Result.failure(Exception("Phản hồi API không phải JSON hợp lệ"))
        }

        // Check top-level prompt feedback blocking
        val promptFeedback = root.optJSONObject("promptFeedback")
        val blockReason = promptFeedback?.optString("blockReason", "").orEmpty()
        if (blockReason.isNotBlank()) {
            return Result.failure(Exception("Nội dung bị bộ lọc API chặn ($blockReason)"))
        }

        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return Result.failure(Exception("API không trả về kết quả nào cho trang này"))
        }

        val firstCandidate = candidates.getJSONObject(0)
        val finishReason = firstCandidate.optString("finishReason", "")
        val content = firstCandidate.optJSONObject("content")
        val parts = content?.optJSONArray("parts")

        if (parts == null || parts.length() == 0) {
            return if (finishReason.isNotBlank() && finishReason != "STOP") {
                Result.failure(Exception("API dừng xử lý sớm ($finishReason)"))
            } else {
                Result.success(emptyList())
            }
        }

        // Concatenate all text parts (handles multi-part responses cleanly)
        val rawTextBuilder = StringBuilder()
        for (i in 0 until parts.length()) {
            val partText = parts.optJSONObject(i)?.optString("text", "").orEmpty()
            if (partText.isNotBlank()) {
                rawTextBuilder.append(partText)
            }
        }

        val rawText = rawTextBuilder.toString().trim()
        if (rawText.isBlank()) {
            return Result.success(emptyList())
        }

        // Strip markdown code fences if present
        val stripped = if (rawText.startsWith("```")) {
            rawText.substringAfter("\n").substringBeforeLast("```").trim()
        } else {
            rawText
        }

        // Primary attempt: parse as JSONArray directly
        try {
            val jsonArray = JSONArray(stripped)
            return Result.success(SpeechBubble.fromJsonArray(jsonArray))
        } catch (_: Exception) {
            // Secondary attempt: extract embedded [...] JSON array
            val startBracket = stripped.indexOf('[')
            val endBracket = stripped.lastIndexOf(']')
            if (startBracket >= 0 && endBracket > startBracket) {
                try {
                    val extractedArray = JSONArray(stripped.substring(startBracket, endBracket + 1))
                    return Result.success(SpeechBubble.fromJsonArray(extractedArray))
                } catch (_: Exception) {
                    // Fall through to object wrapper check
                }
            }

            // Tertiary attempt: wrapped object {"bubbles": [...]}
            return try {
                val jsonObject = JSONObject(stripped)
                val array = jsonObject.optJSONArray("bubbles")
                    ?: jsonObject.optJSONArray("items")
                    ?: jsonObject.optJSONArray("dialogues")
                if (array != null) {
                    Result.success(SpeechBubble.fromJsonArray(array))
                } else {
                    Result.failure(Exception("Định dạng JSON trả về không đúng cấu trúc ô thoại"))
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to parse bubbles JSON: $stripped", e2)
                Result.failure(Exception("Không thể đọc dữ liệu JSON từ Gemini"))
            }
        }
    }
}
