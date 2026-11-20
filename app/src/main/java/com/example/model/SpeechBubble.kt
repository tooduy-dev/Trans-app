package com.example.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a detected speech bubble or dialogue box in a comic page.
 * Coordinates ymin, xmin, ymax, xmax are normalized to [0, 1000] per Gemini standard.
 */
data class SpeechBubble(
    val ymin: Int,
    val xmin: Int,
    val ymax: Int,
    val xmax: Int,
    val originalText: String = "",
    val translatedText: String
) {
    val topPercent: Float get() = (ymin / 1000f).coerceIn(0f, 1f)
    val leftPercent: Float get() = (xmin / 1000f).coerceIn(0f, 1f)
    val bottomPercent: Float get() = (ymax / 1000f).coerceIn(0f, 1f)
    val rightPercent: Float get() = (xmax / 1000f).coerceIn(0f, 1f)

    val widthPercent: Float get() = (rightPercent - leftPercent).coerceAtLeast(0.01f)
    val heightPercent: Float get() = (bottomPercent - topPercent).coerceAtLeast(0.01f)

    /**
     * Checks if a point (normalized 0..1) falls within this bubble.
     */
    fun containsNormalizedPoint(xNorm: Float, yNorm: Float, paddingPercent: Float = 0.015f): Boolean {
        return xNorm >= (leftPercent - paddingPercent) &&
                xNorm <= (rightPercent + paddingPercent) &&
                yNorm >= (topPercent - paddingPercent) &&
                yNorm <= (bottomPercent + paddingPercent)
    }

    companion object {
        fun fromJsonObject(json: JSONObject): SpeechBubble? {
            return try {
                val boxArray = json.optJSONArray("box_2d") ?: return null
                if (boxArray.length() < 4) return null
                val ymin = boxArray.getInt(0)
                val xmin = boxArray.getInt(1)
                val ymax = boxArray.getInt(2)
                val xmax = boxArray.getInt(3)
                val orig = json.optString("original_text", "")
                val trans = json.optString("translated_text", "")
                if (trans.isBlank()) return null
                SpeechBubble(
                    ymin = ymin,
                    xmin = xmin,
                    ymax = ymax,
                    xmax = xmax,
                    originalText = orig,
                    translatedText = trans
                )
            } catch (e: Exception) {
                null
            }
        }

        fun fromJsonArray(jsonArray: JSONArray): List<SpeechBubble> {
            val list = mutableListOf<SpeechBubble>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                fromJsonObject(obj)?.let { list.add(it) }
            }
            return list
        }
    }
}
