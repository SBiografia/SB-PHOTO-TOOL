package com.sb.phototool.ui.collage

import kotlin.math.abs
import kotlin.math.roundToInt

internal fun collageRotationSliderValue(rotation: Float): Float {
    if (!rotation.isFinite()) return 0f
    if (rotation in 0f..360f) return rotation
    return ((rotation % 360f) + 360f) % 360f
}

internal fun collagePhotoRotationSliderValue(frameRotation: Float, quarterTurns: Int): Float =
    collageRotationSliderValue(frameRotation + quarterTurns.mod(4) * 90f)

internal fun collagePhotoFrameRotation(sliderAngle: Float, quarterTurns: Int): Float =
    sliderAngle - quarterTurns.mod(4) * 90f

internal fun snapCollageRotation(value: Float, activeAnchor: Float? = null): Float {
    val angle = value.coerceIn(0f, 360f)
    // A wider release zone prevents repeated clicks at the edge of a detent.
    if (activeAnchor != null && abs(angle - activeAnchor) <= 5f) return activeAnchor
    val nearest = (angle / 90f).roundToInt() * 90f
    return if (abs(angle - nearest) <= 3f) nearest else angle
}
