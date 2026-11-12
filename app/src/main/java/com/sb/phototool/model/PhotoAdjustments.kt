package com.sb.phototool.model

enum class PhotoFilter(val label: String) { NONE("원본"), WARM("따뜻하게"), COOL("시원하게"), MONO("흑백"), FADE("부드럽게"), VINTAGE("빈티지") }

data class PhotoAdjustments(
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val filter: PhotoFilter = PhotoFilter.NONE,
    val intensity: Float = 1f,
)
