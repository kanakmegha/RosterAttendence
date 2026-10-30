package com.example.medicalstaffscheduletracker.engine

data class OcrWord(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val cx: Float = x + width / 2f,
    val cy: Float = y + height / 2f,
    val confidence: Float = 1.0f
)
