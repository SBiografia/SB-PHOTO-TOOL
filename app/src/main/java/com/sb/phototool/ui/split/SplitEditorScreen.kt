package com.sb.phototool.ui.split

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sb.phototool.EditorPreviewState
import com.sb.phototool.media.*
import com.sb.phototool.model.*
import com.sb.phototool.ui.editor.*
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun SplitEditorScreen(uri: Uri?, onBack: () -> Unit, onPick: () -> Unit, onSubmit: (JobSpec) -> Unit) {
    var columns by rememberSaveable { mutableIntStateOf(3) }
    var rows by rememberSaveable { mutableIntStateOf(3) }
    var fit by rememberSaveable { mutableStateOf(SplitFitMode.FILL) }
    var cropValues by rememberSaveable(uri) { mutableStateOf<List<Float>?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var blur by rememberSaveable { mutableStateOf(false) }
    var background by rememberSaveable { mutableIntStateOf(android.graphics.Color.WHITE) }
    var exportMode by rememberSaveable { mutableStateOf(ExportSizeMode.ORIGINAL) }
    var customEdge by rememberSaveable { mutableStateOf("2048") }
    var customGrid by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val preview = rememberPhoto(uri, "split", retry, HIGH_QUALITY_PREVIEW_MAX_EDGE, editorPreviewPixelBudget(1))
    val bitmap = (preview as? EditorPreviewState.Ready)?.bitmap
    val sourceRatio = bitmap?.let { it.width.toFloat() / it.height } ?: 1f
    val crop = cropValues?.let { NormalizedCell(it[0], it[1], it[2], it[3]) } ?: gridCrop(sourceRatio, columns, rows)
    fun setCrop(value: NormalizedCell) { cropValues = listOf(value.left, value.top, value.right, value.bottom) }
    fun setGrid(c: Int, r: Int) {
        columns = c; rows = r
        if (bitmap == null) cropValues = null
        else setCrop(gridCrop(sourceRatio, c, r, centerX = (crop.left + crop.right) / 2, centerY = (crop.top + crop.bottom) / 2))
    }
    val exportValid = exportMode != ExportSizeMode.CUSTOM || customEdge.toIntOrNull() in 320..4096
    val tileAxis = maxOf(columns, rows)
    val customLongEdge = customEdge.toIntOrNull()?.takeIf { it in 320..4096 }
    fun saveSplit() {
        val selectedUri = uri ?: return
        onSubmit(SplitJob(UUID.randomUUID().toString(), selectedUri.toString(), columns, rows, fit, background, blur, ExportSize(exportMode, customLongEdge ?: 2048), if (fit == SplitFitMode.FILL) crop else null))
    }

    ToolEditor(
        "그리드 분할",
        onBack,
        onPick,
        if (uri == null) "사진 선택" else "다시 선택",
        headerAction = {
            TextButton(onClick = onPick, modifier = Modifier.height(40.dp)) { Text(if (uri == null) "사진 선택" else "다시 선택", fontSize = 12.sp) }
            Button(
                onClick = ::saveSplit,
                enabled = bitmap != null && exportValid,
                modifier = Modifier.height(40.dp).testTag("tool-save"),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) { Text("저장", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
        },
    ) {
        if (uri == null) EmptyEditor("한 장의 사진을 여러 장으로", onPick, Modifier.weight(1f).fillMaxWidth())
        else when (preview) {
            EditorPreviewState.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            EditorPreviewState.Failed -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { TextButton(onClick = { retry++ }) { Text("사진을 열지 못했어요 · 다시 시도") } }
            is EditorPreviewState.Ready -> GridCropCanvas(preview.bitmap, crop, columns, rows, fit, background, blur, ::setCrop, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolHint(if (fit == SplitFitMode.FILL) "영역을 드래그해 조정하세요" else "사진 전체를 정사각형 조각에 맞춰 넣어요", Modifier.weight(1f))
            TextButton(onClick = { cropValues = null }, enabled = bitmap != null && fit == SplitFitMode.FILL) { Text("초기화") }
        }
        ToolDock(listOf("격자", "맞춤", "출력"), tab, { tab = it }, compact = true) {
            when (tab) {
                0 -> {
                    Choices(listOf(2 to 1, 2 to 2, 2 to 3, 3 to 1, 3 to 2, 3 to 3, 3 to 4, 1 to 3), columns to rows, { "${it.first} × ${it.second}" }, { setGrid(it.first, it.second) })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("가로 ${columns}칸 · 세로 ${rows}칸"); ToolHint("총 ${columns * rows}장 · 각 조각은 정사각형") }
                        OutlinedButton(onClick = { customGrid = true }) { Text("직접 설정") }
                    }
                    ToolHint("오른쪽 아래부터 1번으로 저장해요. 게시할 서비스의 격자 비율도 확인하세요.")
                }
                1 -> {
                    Choices(SplitFitMode.entries, fit, { if (it == SplitFitMode.FILL) "영역 자르기" else "사진 전체" }, { fit = it })
                    if (fit == SplitFitMode.FIT) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("흐린 사진 배경", Modifier.weight(1f)); Switch(blur, { blur = it }) }
                        if (!blur) Choices(listOf(android.graphics.Color.WHITE, android.graphics.Color.BLACK, 0xFFE2EAF8.toInt()), background, { when (it) { -1 -> "흰색"; android.graphics.Color.BLACK -> "검정"; else -> "블루" } }, { background = it })
                    } else ToolHint("흰 테두리 안의 영역만 저장됩니다. 원본 사진은 바뀌지 않아요.")
                }
                2 -> {
                    Choices(ExportSizeMode.entries, exportMode, { when (it) { ExportSizeMode.ORIGINAL -> "원본 기준"; ExportSizeMode.SOCIAL_1080 -> "1080px 전체"; else -> "직접 입력" } }, { exportMode = it })
                    if (exportMode == ExportSizeMode.CUSTOM) NumericInput("전체 격자 긴 변 · 320~4096px", customEdge, exportValid, { customEdge = it })
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        when (exportMode) {
                            ExportSizeMode.ORIGINAL -> {
                                ToolHint("전체 격자 긴 변: 원본 기준, 최대 4096px")
                                ToolHint("정사각형 타일 한 변은 긴 변 ÷ ${tileAxis}칸")
                            }
                            ExportSizeMode.SOCIAL_1080 -> {
                                ToolHint("1080px는 한 장이 아닌 전체 격자의 긴 변입니다")
                                ToolHint("타일 약 ${1080 / tileAxis} × ${1080 / tileAxis}px · 총 ${columns * rows}장")
                            }
                            ExportSizeMode.CUSTOM -> {
                                ToolHint("전체 격자 긴 변: ${customLongEdge ?: "320~4096"}px")
                                if (customLongEdge != null) ToolHint("타일 한 변 약 ${customLongEdge / tileAxis}px · 총 ${columns * rows}장")
                            }
                        }
                        ToolHint("저장 위치: Pictures / SB PHOTO TOOL / Split")
                    }
                }
            }
        }
        if (customGrid) {
            var c by rememberSaveable { mutableStateOf(columns.toString()) }
            var r by rememberSaveable { mutableStateOf(rows.toString()) }
            val valid = c.toIntOrNull() in 1..MAX_GRID_AXIS && r.toIntOrNull() in 1..MAX_GRID_AXIS
            AlertDialog(onDismissRequest = { customGrid = false }, title = { Text("직접 격자 만들기") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumericInput("가로(열) · 1~10", c, c.toIntOrNull() in 1..10, { c = it })
                    NumericInput("세로(행) · 1~10", r, r.toIntOrNull() in 1..10, { r = it })
                    ToolHint("예: 가로 4, 세로 1 → 가로로 4장")
                }
            }, confirmButton = { TextButton(enabled = valid, onClick = { setGrid(c.toInt(), r.toInt()); customGrid = false }) { Text("적용") } }, dismissButton = { TextButton(onClick = { customGrid = false }) { Text("취소") } })
        }
    }
}

@Composable
private fun GridCropCanvas(bitmap: Bitmap, crop: NormalizedCell, columns: Int, rows: Int, fit: SplitFitMode, background: Int, blur: Boolean, onCrop: (NormalizedCell) -> Unit, modifier: Modifier) {
    val latestCrop by rememberUpdatedState(crop)
    val latestOnCrop by rememberUpdatedState(onCrop)
    val density = LocalDensity.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = if (fit == SplitFitMode.FILL) bitmap.width.toFloat() / bitmap.height else columns.toFloat() / rows
        val width = min(constraints.maxWidth.toFloat(), constraints.maxHeight * ratio)
        val height = width / ratio
        val rect = if (fit == SplitFitMode.FILL) crop else NormalizedCell(0f, 0f, 1f, 1f)
        Box(Modifier.size(with(density) { width.toDp() }, with(density) { height.toDp() }).testTag("grid-canvas").then(if (fit == SplitFitMode.FILL) Modifier.pointerInput(bitmap, fit, width, height) {
            var origin = latestCrop
            var edge: String? = null
            var distance = Offset.Zero
            detectDragGestures(onDragStart = { point ->
                origin = latestCrop; distance = Offset.Zero
                val handles = listOf("left" to Offset(origin.left * width, (origin.top + origin.bottom) * height / 2), "right" to Offset(origin.right * width, (origin.top + origin.bottom) * height / 2), "top" to Offset((origin.left + origin.right) * width / 2, origin.top * height), "bottom" to Offset((origin.left + origin.right) * width / 2, origin.bottom * height))
                edge = handles.minByOrNull { (_, p) -> (point - p).getDistance() }?.takeIf { (_, p) -> (point - p).getDistance() <= 32.dp.toPx() }?.first
            }, onDrag = { change, delta ->
                change.consume(); distance += delta
                latestOnCrop(if (edge == null) moveGridCrop(origin, distance.x / width, distance.y / height)
                else resizeGridCrop(origin, edge!!, if (edge == "left" || edge == "right") distance.x / width else distance.y / height))
            })
        } else Modifier)) {
            Box(Modifier.fillMaxSize().clipToBounds().background(Color(background))) {
                if (fit == SplitFitMode.FIT && blur) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().blur(18.dp), contentScale = ContentScale.Crop)
                Image(bitmap.asImageBitmap(), "분할 미리보기", Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
                Canvas(Modifier.fillMaxSize()) {
                    val l = rect.left * size.width; val t = rect.top * size.height
                    val r = rect.right * size.width; val b = rect.bottom * size.height
                    val shade = Color.Black.copy(alpha = .62f)
                    drawRect(shade, size = Size(size.width, t))
                    drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
                    drawRect(shade, Offset(0f, t), Size(l, b - t))
                    drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
                    drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(1.5.dp.toPx()))
                    for (i in 1 until columns) drawLine(Color.White.copy(alpha = .85f), Offset(l + (r - l) * i / columns, t), Offset(l + (r - l) * i / columns, b), 1.dp.toPx())
                    for (i in 1 until rows) drawLine(Color.White.copy(alpha = .85f), Offset(l, t + (b - t) * i / rows), Offset(r, t + (b - t) * i / rows), 1.dp.toPx())
                }
            }
            for (row in 0 until rows) for (col in 0 until columns) {
                val x = (rect.left + (rect.right - rect.left) * (col + .5f) / columns) * width
                val y = (rect.top + (rect.bottom - rect.top) * (row + .5f) / rows) * height
                val badge = with(density) { 22.dp.toPx() }
                Box(Modifier.offset { IntOffset((x - badge / 2).roundToInt(), (y - badge / 2).roundToInt()) }.size(22.dp).background(Color.Black.copy(alpha = .5f), CircleShape), contentAlignment = Alignment.Center) { Text("${columns * rows - row * columns - col}", color = Color.White, fontSize = 10.sp) }
            }
            if (fit == SplitFitMode.FILL) {
                val handles = listOf("left" to Offset(rect.left, (rect.top + rect.bottom) / 2), "right" to Offset(rect.right, (rect.top + rect.bottom) / 2), "top" to Offset((rect.left + rect.right) / 2, rect.top), "bottom" to Offset((rect.left + rect.right) / 2, rect.bottom))
                handles.forEach { (edge, position) ->
                    val hit = with(density) { 48.dp.toPx() }
                    Box(Modifier.offset { IntOffset((position.x * width - hit / 2).roundToInt(), (position.y * height - hit / 2).roundToInt()) }.size(48.dp).testTag("crop-$edge").semantics { contentDescription = "자르기 영역 ${when (edge) { "left" -> "왼쪽"; "right" -> "오른쪽"; "top" -> "위쪽"; else -> "아래쪽" }} 크기 조절" }, contentAlignment = Alignment.Center) { Box(Modifier.size(12.dp).background(Color.White, CircleShape)) }
                }
            }
        }
    }
}
