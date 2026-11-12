package com.sb.phototool.ui.collage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sb.phototool.media.*
import com.sb.phototool.model.*
import java.util.UUID

@Composable
internal fun DesignColorEditor(label: String, value: Int, onChange: (Int) -> Unit) {
    var hex by remember(value) { mutableStateOf("%06X".format(value and 0xFFFFFF)) }
    Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0xFFFFFF, 0xEEE9DD, 0xD6C1A5, 0x8C684B, 0x262626, 0x000000, 0xD47777, 0xF3B6C5, 0xE2AF55, 0xBCD4B6, 0x618775, 0x95BED5, 0x537AA3, 0xB4A0CA).forEach { rgb ->
            val color = (0xFF000000L or rgb.toLong()).toInt()
            Box(Modifier.size(44.dp).padding(4.dp).clip(CircleShape).background(Color(color))
                .border(if (value == color) 3.dp else 1.dp, if (value == color) EditorAccent else Color.Gray, CircleShape)
                .clickable { onChange(color) }.testTag("design-color-" + rgb.toString(16)))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(hex, { hex = it.filter { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' }.take(6).uppercase() },
            label = { Text("색상 코드 #RRGGBB") }, singleLine = true, modifier = Modifier.weight(1f).testTag("design-color-hex"))
        TextButton(enabled = hex.length == 6, onClick = { onChange((0xFF000000L or hex.toLong(16)).toInt()) }) { Text("적용") }
    }
}

@Composable
internal fun DesignSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: () -> Unit) {
    CollageCompactSlider(label, if (label == "사진 확대") String.format(java.util.Locale.ROOT, "%.2f×", value) else if (range.endInclusive > 2f) value.toInt().toString() else (value * 100).toInt().toString() + "%",
        value, range, onChange, onDone)
}

@Composable
internal fun BackgroundDesignControls(editor: CollageEditorState, onPhoto: (BackgroundKind) -> Unit) {
    val job = editor.document; val bg = job.background
    Text("배경", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BackgroundKind.entries.forEach { k -> FilterChip(bg.kind == k, {
            if ((k == BackgroundKind.PHOTO || k == BackgroundKind.BLUR) && bg.uri.isBlank()) onPhoto(k)
            else editor.update(job.copy(background = bg.copy(kind = k)))
        }, label = { Text(k.label) }) }
    }
    DesignColorEditor("배경색", job.backgroundColor) { editor.update(editor.document.copy(backgroundColor = it)) }
    when (bg.kind) {
        BackgroundKind.GRADIENT -> {
            DesignColorEditor("두 번째 색", bg.secondaryColor) { editor.update(editor.document.copy(background = editor.document.background.copy(secondaryColor = it))) }
            DesignSlider("그라데이션 각도", bg.angle, 0f..360f, { editor.drag(editor.document.copy(background = editor.document.background.copy(angle = it))) }, editor::finishGesture)
        }
        BackgroundKind.PATTERN -> {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackgroundPattern.entries.forEach { p -> FilterChip(bg.pattern == p, { editor.update(job.copy(background = bg.copy(pattern = p))) }, label = { Text(p.label) }) }
            }
            DesignColorEditor("무늬 색", bg.secondaryColor) { editor.update(editor.document.copy(background = editor.document.background.copy(secondaryColor = it))) }
        }
        BackgroundKind.PHOTO, BackgroundKind.BLUR -> {
            OutlinedButton(onClick = { onPhoto(bg.kind) }) { Text("배경 사진 선택·교체") }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(BackgroundKind.PHOTO, BackgroundKind.BLUR).forEach { k -> FilterChip(bg.kind == k, { editor.update(job.copy(background = bg.copy(kind = k))) }, label = { Text(k.label) }) }
            }
            if (bg.kind == BackgroundKind.BLUR) DesignSlider("흐림 강도", bg.blur, 0f..1f,
                { editor.drag(editor.document.copy(background = editor.document.background.copy(blur = it))) }, editor::finishGesture)
        }
        else -> Unit
    }
}

@Composable
internal fun TextDesignControls(editor: CollageEditorState, id: String, onDone: () -> Unit) {
    val existing = editor.document.texts.firstOrNull { it.id == id } ?: return
    var encoded by rememberSaveable(id) { mutableStateOf(JobCodec.encode(editor.document.copy(texts = listOf(existing)))) }
    val text = (JobCodec.decode(encoded) as CollageJob).texts.single()
    fun change(next: CollageText) { encoded = JobCodec.encode(editor.document.copy(texts = listOf(next))) }
    val context = LocalContext.current
    Text("글자 편집", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
    Canvas(Modifier.fillMaxWidth().height(100.dp).background(Color(0xFFECE6DB), RoundedCornerShape(12.dp))) {
        drawIntoCanvas { c -> drawCollageText(c.nativeCanvas, text.copy(frame = NormalizedCell(.04f, .04f, .96f, .96f), rotation = 0f,
            size = (text.size * 3).coerceIn(.04f, .3f)), size.width, size.height, CollageFonts.get(context, text.font)) }
    }
    OutlinedTextField(text.text, { change(text.copy(text = it.take(1000))) }, label = { Text("내용") }, modifier = Modifier.fillMaxWidth().testTag("design-text-content"), minLines = 2, maxLines = 6)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CollageFont.entries.forEach { f -> FilterChip(text.font == f, { change(text.copy(font = f)) }, label = { Text(f.label) }) }
    }
    DesignSlider("글자 크기", text.size, .012f.. .25f, { change(text.copy(size = it)) }, {})
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextAlignment.entries.forEach { a -> FilterChip(text.alignment == a, { change(text.copy(alignment = a)) }, label = { Text(a.label) }) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(text.bold, { change(text.copy(bold = !text.bold)) }, label = { Text("굵게") })
        FilterChip(text.italic, { change(text.copy(italic = !text.italic)) }, label = { Text("기울임") })
        FilterChip(text.underline, { change(text.copy(underline = !text.underline)) }, label = { Text("밑줄") })
        FilterChip(text.strikeThrough, { change(text.copy(strikeThrough = !text.strikeThrough)) }, label = { Text("취소선") })
    }
    DesignSlider("자간", text.letterSpacing, -.05f.. .3f, { change(text.copy(letterSpacing = it)) }, {})
    DesignSlider("행간", text.lineSpacing, .7f..2f, { change(text.copy(lineSpacing = it)) }, {})
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { change(text.copy(font = CollageFont.SANS, bold = false, italic = false, underline = false, strikeThrough = false, outline = 0f, shadow = false, letterSpacing = 0f, lineSpacing = 1f)) }) { Text("기본 스타일") }
        TextButton(onClick = { change(text.copy(font = CollageFont.SERIF, bold = true, letterSpacing = .08f, lineSpacing = 1.25f, shadow = false, outline = 0f)) }) { Text("제목 스타일") }
        TextButton(onClick = { change(text.copy(font = CollageFont.HAND, bold = false, italic = false, letterSpacing = .02f, lineSpacing = 1.35f, shadow = true, outline = 0f)) }) { Text("손글씨 스타일") }
    }
    DesignColorEditor("글자색", text.color) { change(text.copy(color = it)) }
    DesignSlider("외곽선", text.outline, 0f.. .15f, { change(text.copy(outline = it)) }, {})
    if (text.outline > 0) DesignColorEditor("외곽선 색", text.outlineColor) { change(text.copy(outlineColor = it)) }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("그림자", Modifier.weight(1f)); Switch(text.shadow, { change(text.copy(shadow = it)) }) }
    if (!editor.document.placementLocked) CollageRotationSlider(text.id, text.rotation, { change(text.copy(rotation = it)) }, {})
    Button(onClick = {
        editor.update(editor.document.copy(texts = editor.document.texts.map { if (it.id == id) text else it }))
        onDone()
    }, modifier = Modifier.fillMaxWidth().testTag("design-text-apply")) { Text("글자 적용") }
}

@Composable
internal fun ShapeDesignControls(editor: CollageEditorState, ids: Set<String>) {
    val job = editor.document
    val photos = job.tiles.filterIndexed { i, t -> photoElementId(t, i) in ids && !t.locked }
    Text("사진틀 모양", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
    if (photos.isEmpty()) Text("사진을 선택한 뒤 모양을 바꿀 수 있어요.")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("design-shape-list"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PhotoShape.entries.forEach { shape -> FilterChip(photos.isNotEmpty() && photos.all { it.shape == shape }, {
            editor.update(job.copy(tiles = job.tiles.mapIndexed { i, p -> if (!p.locked && photoElementId(p, i) in ids) p.copy(shape = shape) else p }))
        }, label = { Text(shape.label) }, enabled = photos.isNotEmpty(), modifier = Modifier.testTag("design-shape-" + shape.name)) }
    }
    if (photos.isNotEmpty()) {
        val selected = photos.first()
        DesignSlider("사진틀 여백", selected.matte, 0f.. .15f, { value ->
            editor.drag(editor.document.copy(tiles = editor.document.tiles.mapIndexed { i, p -> if (!p.locked && photoElementId(p, i) in ids) p.copy(matte = value) else p }))
        }, editor::finishGesture)
        DesignSlider("사진틀 아래 여백", selected.bottomMatte, 0f.. .25f, { value ->
            editor.drag(editor.document.copy(tiles = editor.document.tiles.mapIndexed { i, p -> if (!p.locked && photoElementId(p, i) in ids) p.copy(bottomMatte = value) else p }))
        }, editor::finishGesture)
        DesignColorEditor("사진틀 색", selected.frameColor) { color -> editor.update(editor.document.copy(tiles = editor.document.tiles.mapIndexed { i, p -> if (!p.locked && photoElementId(p, i) in ids) p.copy(frameColor = color) else p })) }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DecorationDesignControls(editor: CollageEditorState, onSelect: (String) -> Unit) {
    Text("기본 장식", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
    Text("종이·테이프·프레임과 어울리는 기본 장식입니다.", fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DecorationKind.entries.forEach { kind -> OutlinedButton(onClick = {
            val id = "decoration-" + UUID.randomUUID()
            val maxLayer = collageDesignElements(editor.document).maxOfOrNull { it.layer } ?: 0
            val frame = if (kind == DecorationKind.TAPE || kind == DecorationKind.LINE) NormalizedCell(.25f, .44f, .75f, .51f) else NormalizedCell(.3f, .3f, .7f, .7f)
            editor.update(editor.document.copy(decorations = editor.document.decorations + CollageDecoration(id, kind, frame, layer = maxLayer + 1)))
            onSelect(id)
        }) { Text(kind.label) } }
    }
}
