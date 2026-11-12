package com.sb.phototool.ui.collage

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BorderInner
import androidx.compose.material.icons.outlined.BorderOuter
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.RoundedCorner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sb.phototool.media.*
import com.sb.phototool.model.*

@Composable
internal fun PhotoAdjustmentControls(editor: CollageEditorState, ids: Set<String>, onDone: () -> Unit, onCancel: () -> Unit) {
    val indices = selectedCollagePhotoIndices(editor.document, ids)
    val tile = indices.firstOrNull()?.let { editor.document.tiles[it] } ?: return
    fun change(transform: (PhotoAdjustments) -> PhotoAdjustments) {
        editor.drag(updateSelectedCollagePhotos(editor.document, ids) { _, photo -> photo.copy(adjustments = transform(photo.adjustments)) })
    }
    Text("사진 필터·보정", style = MaterialTheme.typography.titleLarge)
    Text("미리보기와 저장 결과에 함께 적용됩니다.")
    if (indices.size > 1) Text("선택한 ${indices.size}장에 지금 조절한 항목만 적용합니다.", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PhotoFilter.entries.forEach { filter -> FilterChip(indices.all { editor.document.tiles[it].adjustments.filter == filter }, { change { it.copy(filter = filter) } },
            label = { Text(filter.label) }, modifier = Modifier.testTag("photo-filter-${filter.name}")) }
    }
    DesignSlider("필터 강도", tile.adjustments.intensity, 0f..1f, { v -> change { it.copy(intensity = v) } }, {})
    DesignSlider("밝기", tile.adjustments.brightness, -1f..1f, { v -> change { it.copy(brightness = v) } }, {})
    DesignSlider("대비", tile.adjustments.contrast, .5f..1.5f, { v -> change { it.copy(contrast = v) } }, {})
    DesignSlider("채도", tile.adjustments.saturation, 0f..2f, { v -> change { it.copy(saturation = v) } }, {})
    TextButton(onClick = { change { PhotoAdjustments() } }) { Text("보정 초기화") }
    Row { TextButton(onClick = onCancel) { Text("취소") }; Button(onClick = onDone, modifier = Modifier.testTag("photo-adjust-apply")) { Text("보정 적용") } }
}

@Composable
internal fun PhotoPrecisionControls(editor: CollageEditorState, ids: Set<String>, canvasAspect: Float) {
    val indices = selectedCollagePhotoIndices(editor.document, ids)
    val index = indices.firstOrNull() ?: return
    val tile = editor.document.tiles[index]
    val moveFrame = editor.document.mode == CollageMode.FREEFORM && !editor.document.placementLocked
    Text("사진 정밀 조정", style = MaterialTheme.typography.titleLarge)
    Text(if (moveFrame) "방향 버튼으로 사진틀 위치를 조절합니다." else "방향 버튼으로 사진 위치를 조절합니다.", style = MaterialTheme.typography.bodySmall)
    if (editor.document.mode == CollageMode.FREEFORM && !editor.document.placementLocked) {
        Text("사진틀 자르기 비율")
        val frameAspects = indices.map { i -> freeformFrame(editor.document, i).let { (it.right - it.left) / (it.bottom - it.top) * canvasAspect } }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("1:1" to 1f, "4:5" to .8f, "3:4" to .75f, "4:3" to 4f/3f, "9:16" to 9f/16f, "16:9" to 16f/9f).forEach { (label, ratio) ->
                CollageRatioOption(label, ratio, selected = frameAspects.all { kotlin.math.abs(it - ratio) < .01f },
                    enabled = !tile.locked, modifier = Modifier.testTag("photo-crop-" + label)) {
                    val job = editor.document
                    editor.update(updateSelectedCollagePhotos(job, ids) { i, photo ->
                        photo.copy(freeform = freeformAspect(freeformFrame(job, i), ratio, canvasAspect))
                    })
                }
            }
        }
    }
    fun move(dx: Float, dy: Float) { editor.update(moveSelectedCollagePhotos(editor.document, ids, dx, dy, moveFrame)) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("왼쪽" to (-.04f to 0f), "오른쪽" to (.04f to 0f), "위" to (0f to -.04f), "아래" to (0f to .04f)).forEach { (label, delta) ->
            OutlinedButton(onClick = { move(delta.first, delta.second) }, enabled = !tile.locked, modifier = Modifier.testTag("photo-nudge-$label")) { Text(label) }
        }
    }
    fun zoom(value: Float, gesture: Boolean) {
        val next = updateSelectedCollagePhotos(editor.document, ids) { _, photo -> photo.copy(zoom = value.coerceIn(1f, 3f)) }
        if (gesture) editor.drag(next) else editor.update(next)
    }
    DesignSlider("사진 확대", tile.zoom, 1f..3f, { zoom(it, true) }, editor::finishGesture)
    Row { TextButton(onClick = { editor.update(updateSelectedCollagePhotos(editor.document, ids) { _, photo -> photo.copy(zoom = (photo.zoom - .05f).coerceIn(1f, 3f)) }) }) { Text("축소 −") }
        TextButton(onClick = { editor.update(updateSelectedCollagePhotos(editor.document, ids) { _, photo -> photo.copy(zoom = (photo.zoom + .05f).coerceIn(1f, 3f)) }) }) { Text("확대 ＋") } }
}

@Composable
internal fun PhotoRotationControls(editor: CollageEditorState, ids: Set<String>) {
    val indices = selectedCollagePhotoIndices(editor.document, ids)
    val index = indices.firstOrNull() ?: return
    val tile = editor.document.tiles[index]
    fun change(action: (TileTransform) -> TileTransform) {
        editor.update(updateSelectedCollagePhotos(editor.document, ids) { _, photo -> action(photo) })
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { change { it.copy(quarterTurns = (it.quarterTurns + 1).mod(4), zoom = 1f, panX = 0f, panY = 0f) } },
            enabled = !tile.locked, modifier = Modifier.testTag("photo-rotate-quarter-turn")) {
            Icon(Icons.Outlined.Rotate90DegreesCw, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("90도")
        }
        OutlinedButton(onClick = { change { it.copy(flipHorizontal = !it.flipHorizontal) } }, enabled = !tile.locked, modifier = Modifier.testTag("photo-flip-horizontal")) {
            Icon(Icons.Outlined.Flip, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("좌우 반전")
        }
        OutlinedButton(onClick = { change { it.copy(flipVertical = !it.flipVertical) } }, enabled = !tile.locked, modifier = Modifier.testTag("photo-flip-vertical")) {
            Icon(Icons.Outlined.FlipCameraAndroid, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("상하 반전")
        }
    }
    if (indices.size > 1) Text("90도 회전·반전은 각 사진에 적용하고, 슬라이더는 같은 각도로 맞춥니다.", style = MaterialTheme.typography.bodySmall)
    CollageRotationSlider(ids.joinToString(), collagePhotoRotationSliderValue(tile.frameRotation, tile.quarterTurns), { angle ->
        editor.drag(updateSelectedCollagePhotos(editor.document, ids) { _, photo ->
            photo.copy(frameRotation = collagePhotoFrameRotation(angle, photo.quarterTurns))
        })
    }, editor::finishGesture)
}

@Composable
internal fun CollageMarginControls(editor: CollageEditorState) {
    val job = editor.document
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("여백·모서리", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        IconButton(onClick = { editor.update(editor.document.copy(outerMarginPercent = 0f, gapPercent = 0f, cornerPercent = 0f)) }) {
            Icon(Icons.Outlined.Block, "여백·모서리 모두 제거")
        }
    }
    if (job.mode == CollageMode.LAYOUT) {
        CollageMarginSlider("바깥 여백", Icons.Outlined.BorderOuter, job.outerMarginPercent, 0f.. .12f,
            { editor.drag(editor.document.copy(outerMarginPercent = it)) }, editor::finishGesture)
        CollageMarginSlider("사진 사이 간격", Icons.Outlined.BorderInner, job.gapPercent, 0f.. .08f,
            { editor.drag(editor.document.copy(gapPercent = it)) }, editor::finishGesture)
    } else Text("자유형에서는 사진틀을 직접 이동해 간격을 조절합니다.")
    CollageMarginSlider("둥근 모서리", Icons.Outlined.RoundedCorner, job.cornerPercent, 0f.. .12f,
        { editor.drag(editor.document.copy(cornerPercent = it)) }, editor::finishGesture)
}

@Composable
internal fun CollageBorderControls(editor: CollageEditorState) {
    Text("테두리", style = MaterialTheme.typography.titleLarge)
    DesignSlider("테두리 두께", editor.document.borderWidthPercent, 0f.. .04f, { editor.drag(editor.document.copy(borderWidthPercent = it)) }, editor::finishGesture)
    DesignColorEditor("테두리 색", editor.document.borderColor) { editor.update(editor.document.copy(borderColor = it)) }
    TextButton(onClick = { editor.update(editor.document.copy(borderWidthPercent = 0f)) }) { Text("테두리 제거") }
}
