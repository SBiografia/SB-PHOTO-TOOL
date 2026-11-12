package com.sb.phototool.ui.collage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun CollageRotationSlider(elementId: String, value: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var anchor by remember(elementId) { mutableStateOf<Float?>(null) }
    val angle = collageRotationSliderValue(value)
    CollageCompactSlider("회전", "${angle.roundToInt()}°", angle, 0f..360f, { raw ->
        val snapped = snapCollageRotation(raw, anchor)
        val nextAnchor = snapped.takeIf { it % 90f == 0f }
        if (nextAnchor != null && nextAnchor != anchor) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        anchor = nextAnchor
        onChange(snapped)
    }, { anchor = null; onDone() })
}

@Composable
internal fun CollageRatioOption(
    label: String,
    aspectRatio: Float,
    selected: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val color = if (selected) EditorAccent else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.widthIn(min = 64.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) EditorAccent.copy(alpha = .12f) else Color.Transparent)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(56.dp)) {
            val ratio = aspectRatio.coerceIn(.1f, 10f)
            val edge = 48.dp.toPx()
            val width = if (ratio >= 1f) edge else edge * ratio
            val height = if (ratio >= 1f) edge / ratio else edge
            drawRoundRect(
                color.copy(alpha = if (!enabled) .25f else if (selected) 1f else .5f),
                topLeft = Offset((size.width - width) / 2, (size.height - height) / 2),
                size = Size(width, height), cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
        Text(label, fontSize = 12.sp, color = color.copy(alpha = if (enabled) 1f else .38f), maxLines = 1)
    }
}

@Composable
internal fun CollageMarginSlider(
    label: String,
    icon: ImageVector,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    CollageCompactSlider(label, (value * 100).toInt().toString() + "%", value, range, onChange, onDone, icon)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollageCompactSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    icon: ImageVector? = null,
) {
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .25f)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, textAlign = TextAlign.Center)
        }
        Slider(
            value = value.coerceIn(range), onValueChange = onChange, valueRange = range,
            onValueChangeFinished = onDone,
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
            thumb = { Box(Modifier.size(14.dp).background(EditorAccent, CircleShape)) },
            track = { state ->
                Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                    val fraction = ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    val start = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width else 0f, center.y)
                    val end = Offset(if (layoutDirection == LayoutDirection.Rtl) 0f else size.width, center.y)
                    val activeEnd = start + (end - start) * fraction
                    drawLine(trackColor, start, end, strokeWidth = size.height, cap = StrokeCap.Round)
                    if (fraction > 0f) drawLine(EditorAccent, start, activeEnd, strokeWidth = size.height, cap = StrokeCap.Round)
                }
            },
        )
        Text(valueLabel, Modifier.widthIn(min = 36.dp).padding(start = 4.dp),
            fontSize = 11.sp, textAlign = TextAlign.End, maxLines = 1)
    }
}
