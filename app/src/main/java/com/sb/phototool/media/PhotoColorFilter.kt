package com.sb.phototool.media

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import com.sb.phototool.model.PhotoAdjustments
import com.sb.phototool.model.PhotoFilter

fun photoColorFilter(value: PhotoAdjustments): ColorMatrixColorFilter? {
    if (value == PhotoAdjustments()) return null
    val matrix = ColorMatrix().apply { setSaturation(value.saturation.coerceIn(0f, 2f)) }
    val contrast = value.contrast.coerceIn(.5f, 1.5f)
    val shift = 128f * (1 - contrast) + 255f * value.brightness.coerceIn(-1f, 1f)
    matrix.postConcat(ColorMatrix(floatArrayOf(contrast,0f,0f,0f,shift, 0f,contrast,0f,0f,shift, 0f,0f,contrast,0f,shift, 0f,0f,0f,1f,0f)))
    val preset = when(value.filter) {
        PhotoFilter.NONE -> ColorMatrix()
        PhotoFilter.MONO -> ColorMatrix().apply { setSaturation(0f) }
        PhotoFilter.WARM -> ColorMatrix(floatArrayOf(1.08f,0f,0f,0f,6f, 0f,1f,0f,0f,2f, 0f,0f,.9f,0f,0f, 0f,0f,0f,1f,0f))
        PhotoFilter.COOL -> ColorMatrix(floatArrayOf(.93f,0f,0f,0f,0f, 0f,1f,0f,0f,2f, 0f,0f,1.1f,0f,6f, 0f,0f,0f,1f,0f))
        PhotoFilter.FADE -> ColorMatrix(floatArrayOf(.85f,0f,0f,0f,23f, 0f,.85f,0f,0f,23f, 0f,0f,.85f,0f,23f, 0f,0f,0f,1f,0f))
        PhotoFilter.VINTAGE -> ColorMatrix(floatArrayOf(.393f,.769f,.189f,0f,0f, .349f,.686f,.168f,0f,0f, .272f,.534f,.131f,0f,0f, 0f,0f,0f,1f,0f))
    }
    val identity = ColorMatrix().array; val amount = value.intensity.coerceIn(0f, 1f)
    matrix.postConcat(ColorMatrix(FloatArray(20) { identity[it] + (preset.array[it] - identity[it]) * amount }))
    return ColorMatrixColorFilter(matrix)
}
