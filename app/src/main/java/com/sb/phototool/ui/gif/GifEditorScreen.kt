package com.sb.phototool.ui.gif

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sb.phototool.EditorPreviewState
import com.sb.phototool.loadEditorPreview
import com.sb.phototool.model.GifJob
import com.sb.phototool.model.GifOutputFormat
import com.sb.phototool.model.JobSpec
import com.sb.phototool.ui.editor.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.min
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GifEditorScreen(
    photoUris: List<Uri>,
    onBack: () -> Unit,
    onPickPhotos: () -> Unit,
    onPhotosChanged: (List<Uri>) -> Unit,
    onSubmit: (JobSpec) -> Unit,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var delayMs by rememberSaveable { mutableIntStateOf(300) }
    var outputFormat by rememberSaveable { mutableStateOf(GifOutputFormat.GIF) }
    var edge by rememberSaveable { mutableStateOf("720") }
    var customEdge by rememberSaveable { mutableStateOf(false) }
    var forever by rememberSaveable { mutableStateOf(true) }
    var repeats by rememberSaveable { mutableStateOf("1") }
    var background by rememberSaveable { mutableIntStateOf(android.graphics.Color.WHITE) }
    var playing by remember { mutableStateOf(false) }
    var frame by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    val photos = remember { mutableStateMapOf<Uri, EditorPreviewState>() }
    val keys = photoUris.toSet()
    val edgeBudget = min(512, sqrt(12.0 * 1024 * 1024 / (keys.size.coerceAtLeast(1) * 4)).toInt()).coerceAtLeast(128)
    val timeline = rememberLazyListState()

    LaunchedEffect(keys, edgeBudget, retry) {
        photos.clear()
        for (uri in keys) {
            val loaded = withContext(Dispatchers.IO) { loadEditorPreview(context, uri, "gif", edgeBudget) }
            ensureActive()
            photos[uri] = loaded
        }
    }
    val ready = photoUris.isNotEmpty() && photoUris.all { photos[it] is EditorPreviewState.Ready }
    val failedCount = photos.values.count { it is EditorPreviewState.Failed }
    LaunchedEffect(photoUris) {
        playing = false
        selected = selected.coerceIn(0, (photoUris.size - 1).coerceAtLeast(0))
        frame = selected
    }
    LaunchedEffect(playing, delayMs, photoUris, ready) {
        if (playing && ready) while (true) {
            delay(delayMs.toLong())
            frame = (frame + 1) % photoUris.size
        }
    }
    LaunchedEffect(selected) { if (selected in photoUris.indices) timeline.animateScrollToItem(selected) }

    val duration = photoUris.size * delayMs.toLong()
    val edgeValue = edge.toIntOrNull()
    val valid = ready && edgeValue != null && edgeValue in 160..1920 && delayMs in 20..2000 &&
        (outputFormat == GifOutputFormat.MP4 || forever || repeats.toIntOrNull() in 1..100)

    ToolEditor("사진 애니메이션", onBack, onPickPhotos, if (photoUris.isEmpty()) "사진 선택" else "다시 선택") {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (photoUris.isEmpty()) {
                EmptyEditor("사진 여러 장으로 GIF 또는 MP4 만들기", onPickPhotos, Modifier.fillMaxSize())
            } else {
                val first = (photos[photoUris.first()] as? EditorPreviewState.Ready)?.bitmap
                val current = (photos[photoUris.getOrNull(frame)] as? EditorPreviewState.Ready)?.bitmap
                val ratio = first?.let { it.width.toFloat() / it.height } ?: 1f
                val width = min(maxWidth.value, maxHeight.value * ratio).dp
                Box(Modifier.size(width, width / ratio).background(Color(background)), contentAlignment = Alignment.Center) {
                    if (current != null) {
                        Image(current.asImageBitmap(), "사진 애니메이션 미리보기", Modifier.fillMaxSize().testTag("gif-preview"), contentScale = ContentScale.Fit)
                    } else if (failedCount > 0) {
                        TextButton(onClick = { retry++ }) { Text("사진을 열지 못했어요 · 재시도", color = MaterialTheme.colorScheme.error) }
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { playing = !playing }, enabled = ready) {
                Icon(if (playing) Icons.Outlined.PauseCircle else Icons.Outlined.PlayCircle,
                    if (playing) "미리보기 일시정지" else "미리보기 재생", Modifier.size(36.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("${gifTime(duration)} · ${photoUris.size}프레임")
                ToolHint(if (photoUris.isEmpty()) "사진을 선택해 주세요" else "${(frame + 1).coerceAtMost(photoUris.size)} / ${photoUris.size} · 미리보기는 반복 재생")
            }
        }
        if (photoUris.isNotEmpty()) {
            LazyRow(
                state = timeline,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("gif-timeline"),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(photoUris, key = { _, uri -> uri.toString() }) { index, uri ->
                    Box(
                        Modifier.size(58.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(if (selected == index) 2.dp else 0.dp,
                                if (selected == index) ToolAccent else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { selected = index; frame = index; playing = false }
                            .testTag("gif-frame-$index"),
                    ) {
                        (photos[uri] as? EditorPreviewState.Ready)?.let {
                            Image(it.bitmap.asImageBitmap(), "사진 ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Text("${index + 1}", Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .65f)).padding(horizontal = 4.dp), color = Color.White)
                    }
                }
            }
        }
        ToolDock(listOf("순서", "속도", "출력"), tab, { tab = it }, compact = true) {
            when (tab) {
                0 -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (photoUris.isEmpty()) "사진 순서" else "사진 ${selected + 1}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        IconButton(enabled = selected > 0, onClick = { onPhotosChanged(movedFrame(photoUris, selected, selected - 1)); selected-- }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "사진 앞으로 이동")
                        }
                        IconButton(enabled = selected < photoUris.lastIndex, onClick = { onPhotosChanged(movedFrame(photoUris, selected, selected + 1)); selected++ }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, "사진 뒤로 이동")
                        }
                        IconButton(enabled = photoUris.isNotEmpty(), onClick = { onPhotosChanged(photoUris.filterIndexed { i, _ -> i != selected }) }) {
                            Icon(Icons.Outlined.DeleteOutline, "애니메이션에서 사진 제외")
                        }
                        IconButton(enabled = photoUris.size > 1, onClick = { onPhotosChanged(photoUris.reversed()); selected = photoUris.lastIndex - selected }) {
                            Icon(Icons.Outlined.SwapVert, "순서 반대로")
                        }
                    }
                    ToolHint(if (failedCount > 0) "${failedCount}장을 열지 못했어요. 제외하거나 다시 선택하세요." else "화살표로 순서를 바꿔요. 원본 사진은 변경되지 않습니다.")
                }
                1 -> {
                    Choices(listOf(100, 300, 500, 1000), delayMs, { gifTime(it.toLong()) }, { delayMs = it })
                    CompactSlider("장당 간격", gifTime(delayMs.toLong()), delayMs.toFloat(),
                        { delayMs = ((it / 10).toInt() * 10).coerceIn(20, 2000) }, 20f..2000f)
                }
                2 -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("파일 형식", Modifier.width(68.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Choices(GifOutputFormat.entries, outputFormat, { it.name }, { outputFormat = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("긴 변", Modifier.width(68.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Choices(listOf("240", "480", "720", "1080", "custom"), if (customEdge) "custom" else edge,
                            { if (it == "custom") "직접 입력" else "${it}px" },
                            { if (it == "custom") customEdge = true else { edge = it; customEdge = false } })
                    }
                    if (customEdge) NumericInput("긴 변 · 160~1920px", edge, edge.toIntOrNull() in 160..1920, { edge = it })
                    if (outputFormat == GifOutputFormat.GIF) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("계속 반복", Modifier.weight(1f), fontSize = 12.sp)
                            Switch(checked = forever, onCheckedChange = { forever = it }, modifier = Modifier.height(36.dp))
                            if (!forever) NumericInput("반복 횟수", repeats, repeats.toIntOrNull() in 1..100, { repeats = it }, Modifier.width(120.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("배경", Modifier.width(68.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Choices(listOf(android.graphics.Color.WHITE, android.graphics.Color.BLACK), background,
                            { if (it == android.graphics.Color.WHITE) "흰색" else "검정" }, { background = it })
                    }
                    ToolHint(if (outputFormat == GifOutputFormat.MP4) "MP4는 12 FPS 동영상으로 저장돼요." else "GIF는 첫 사진 비율에 맞춰 저장돼요.")
                }
            }
        }
        ToolFooter(if (outputFormat == GifOutputFormat.GIF) "GIF 저장" else "MP4 저장", valid, {
            playing = false
            onSubmit(
                GifJob(
                    id = UUID.randomUUID().toString(),
                    uris = photoUris.map(Uri::toString),
                    outputFormat = outputFormat,
                    fps = 12,
                    targetLongEdge = edgeValue ?: 720,
                    loopCount = if (outputFormat == GifOutputFormat.GIF && !forever) repeats.toInt() else 0,
                    photoDelayMs = delayMs,
                    backgroundColor = background,
                ),
            )
        })
    }
}
