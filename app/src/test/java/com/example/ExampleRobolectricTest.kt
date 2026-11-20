package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.SpeechBubble
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun readStringFromContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("MangaLens", appName)
    }

    @Test
    fun speechBubble_parsingAndCoordinates_isCorrect() {
        val jsonStr = """
            {
                "box_2d": [100, 200, 300, 500],
                "original_text": "こんにちは",
                "translated_text": "Xin chào!"
            }
        """.trimIndent()

        val jsonObject = JSONObject(jsonStr)
        val bubble = SpeechBubble.fromJsonObject(jsonObject)

        assertNotNull(bubble)
        assertEquals(100, bubble!!.ymin)
        assertEquals(200, bubble.xmin)
        assertEquals(300, bubble.ymax)
        assertEquals(500, bubble.xmax)
        assertEquals("Xin chào!", bubble.translatedText)

        // Test normalized coordinate calculations
        assertEquals(0.1f, bubble.topPercent, 0.001f)
        assertEquals(0.2f, bubble.leftPercent, 0.001f)
        assertEquals(0.3f, bubble.bottomPercent, 0.001f)
        assertEquals(0.5f, bubble.rightPercent, 0.001f)

        // Point inside bubble: x=0.35, y=0.20
        assertTrue(bubble.containsNormalizedPoint(0.35f, 0.20f))

        // Point outside bubble: x=0.8, y=0.8
        assertFalse(bubble.containsNormalizedPoint(0.8f, 0.8f))
    }

    @Test
    fun speechBubbleArray_parsing_isCorrect() {
        val arrayJson = """
            [
                {"box_2d": [50, 50, 150, 250], "translated_text": "Hả?!"},
                {"box_2d": [400, 500, 600, 800], "translated_text": "Chạy mau!"}
            ]
        """.trimIndent()

        val bubbles = SpeechBubble.fromJsonArray(JSONArray(arrayJson))
        assertEquals(2, bubbles.size)
        assertEquals("Hả?!", bubbles[0].translatedText)
        assertEquals("Chạy mau!", bubbles[1].translatedText)
    }

    @Test
    fun translator_multiKeysManagement_worksCorrectly() {
        val translator = com.example.network.GeminiComicTranslator()
        assertEquals(0, translator.getCustomKeyCount())

        translator.setCustomApiKeys(listOf("AIzaSyTestKey1", "AIzaSyTestKey2", "  ", ""))
        assertEquals(2, translator.getCustomKeyCount())
        val available = translator.getAllAvailableKeys()
        assertTrue(available.contains("AIzaSyTestKey1"))
        assertTrue(available.contains("AIzaSyTestKey2"))

        val activeInfo = translator.getActiveKeyInfo()
        assertTrue(activeInfo.contains("Đang dùng Key tùy chỉnh"))
    }

    @Test
    fun viewModel_customKeysPersistence_savesAndLoads() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = com.example.ui.ComicViewModel()

        viewModel.saveCustomApiKeys(context, "keyA\nkeyB,keyC;keyD")
        assertEquals(4, viewModel.uiState.value.customKeyCount)

        // Clear and verify
        viewModel.clearCustomApiKeys(context)
        assertEquals(0, viewModel.uiState.value.customKeyCount)
    }
}
