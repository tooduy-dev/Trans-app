package com.example.model

import android.net.Uri

enum class TranslationStatus {
    IDLE,
    TRANSLATING,
    COMPLETED,
    ERROR
}

data class ComicPage(
    val id: String,
    val name: String,
    val uri: Uri,
    val width: Int = 0,
    val height: Int = 0,
    val translationStatus: TranslationStatus = TranslationStatus.IDLE,
    val bubbles: List<SpeechBubble> = emptyList(),
    val errorMessage: String? = null
) {
    val aspectRatio: Float
        get() = if (height > 0 && width > 0) width.toFloat() / height.toFloat() else 0.707f // Default ~A4 / manga ratio
}
