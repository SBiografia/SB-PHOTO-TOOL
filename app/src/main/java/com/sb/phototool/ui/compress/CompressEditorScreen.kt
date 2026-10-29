package com.sb.phototool.ui.compress

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sb.phototool.data.DiagnosticLog
import com.sb.phototool.media.CompressionPreview
import com.sb.phototool.media.MediaProcessor
import com.sb.phototool.model.*
import com.sb.phototool.ui.editor.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun CompressEditorScreen(uris: List<Uri>, folder: Uri?, onBack: () -> Unit, onPick: () -> Unit, onPickFolder: () -> Unit,
                         onResolveFolder: suspend (Uri, Boolean) -> Unit, onRemove: (Uri) -> Unit, onSubmit: (JobSpec) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var maxKb by rememberSaveable { mutableStateOf("1024") }
    var customLimit by rememberSaveable { mutableStateOf(false) }
    var resize by rememberSaveable { mutableStateOf(ResizeMode.PERCENT) }
    var percent by rememberSaveable { mutableIntStateOf(70) }
    var longEdge by rememberSaveable { mutableStateOf("1920") }
    var format by rememberSaveable { mutableStateOf(ImageFormat.JPEG) }
    var policy by rememberSaveable { mutableStateOf(ConflictPolicy.MAX_SIZE_FIRST) }
    var metadata by rememberSaveable { mutableStateOf(true) }
    var includeChildren by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<CompressionPreview?>(null) }
    var testTask by remember { mutableStateOf<Job?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    val currentUri = uris.getOrNull(selected.coerceIn(0, (uris.size - 1).coerceAtLeast(0)))
    val valid = (maxKb.toLongOrNull()?.let { it in 0..102400 } == true) && (resize != ResizeMode.PIXELS || longEdge.toIntOrNull() in 1..8192)
    val settingsKey = listOf(currentUri, maxKb, resize, percent, longEdge, format, policy, metadata)
    LaunchedEffect(settingsKey) { testTask?.cancel(); result = null; error = null; showOriginal = false }
    LaunchedEffect(uris.size) { selected = selected.coerceIn(0, (uris.size - 1).coerceAtLeast(0)) }
    fun spec(inputs: List<Uri>) = CompressJob(UUID.randomUUID().toString(), inputs.map(Uri::toString), format, resize, percent, longEdge.toIntOrNull() ?: 1920, (maxKb.toLongOrNull() ?: 0) * 1024, policy, metadata)

    ToolEditor("사진 압축", onBack, onPick, if (uris.isEmpty() && folder == null) "사진 선택" else "다시 선택") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (uris.isEmpty()) "원본은 그대로, 사본으로 저장" else "${uris.size}장 선택 · ${selected + 1}번째 사진", Modifier.weight(1f))
            IconButton(onClick = onPickFolder, enabled = !scanning) { Icon(Icons.Outlined.FolderOpen, "폴더 선택") }
            if (currentUri != null) IconButton(onClick = { onRemove(currentUri) }, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "선택에서 제외") }
        }
        if (folder != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("하위 폴더 포함", Modifier.weight(1f)); Switch(includeChildren, { includeChildren = it }, enabled = !scanning)
                    TextButton(enabled = !scanning, onClick = {
                        scope.launch {
                            scanning = true; error = null
                            try { onResolveFolder(folder, includeChildren) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { DiagnosticLog.recordException(context, "compress/folder", failure); error = failure.message ?: "폴더를 읽지 못했어요." }
                            finally { scanning = false }
                        }
                    }) { Text(if (scanning) "읽는 중…" else "폴더 불러오기") }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            if (currentUri == null) EmptyEditor(if (scanning) "폴더에서 사진을 찾고 있어요" else "여러 장을 한 번에 가볍게", onPick, Modifier.fillMaxSize())
            else {
                if (result != null && !showOriginal) Image(result!!.bitmap.asImageBitmap(), "압축 결과 미리보기", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else PreviewPhoto(currentUri, Modifier.fillMaxSize())
                if (result != null) Surface(Modifier.align(Alignment.TopCenter), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f)) {
                    Row { TextButton(onClick = { showOriginal = true }) { Text("원본", color = if (showOriginal) ToolAccent else MaterialTheme.colorScheme.onSurfaceVariant) }; TextButton(onClick = { showOriginal = false }) { Text("결과", color = if (!showOriginal) ToolAccent else MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
                if (busy) Surface(shape = RoundedCornerShape(18.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text("1장 테스트 중") } }
            }
        }
        result?.let { preview ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("${formatBytes(preview.inputBytes)} → ${formatBytes(preview.outputBytes)} · ${preview.width} × ${preview.height}", color = ToolAccent)
                ToolHint(if (preview.copied) "재압축 없이 그대로 복사됩니다." else if (!preview.targetMet) "목표 용량 초과 · 해상도 또는 촬영 정보 보존 영향" else "선택한 1장의 실제 결과입니다. 아직 저장하지 않았어요.")
            }
        }
        if (uris.isNotEmpty()) LazyRow(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("compress-photos"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(uris, key = { _, uri -> uri.toString() }) { index, uri ->
                PreviewPhoto(uri, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).border(if (selected == index) 2.dp else 0.dp, if (selected == index) ToolAccent else MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).clickable { selected = index }, true)
            }
        }
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        ToolDock(
            listOf("용량", "해상도", "설정"), tab, { tab = it }, compact = true,
            modifier = Modifier.weight(1f), fillRemaining = true,
        ) {
            when (tab) {
                0 -> {
                    Text("사진 한 장당 최대 용량")
                    Choices(listOf("500", "1024", "2048", "custom"), if (customLimit) "custom" else maxKb, {
                        when (it) {
                            "500" -> "500 KB"
                            "1024" -> "1 MB"
                            "2048" -> "2 MB"
                            else -> "직접 입력"
                        }
                    }, { if (it == "custom") customLimit = true else { maxKb = it; customLimit = false } })
                    if (customLimit) {
                        NumericInput("직접 입력 · KB", maxKb, maxKb.toLongOrNull() in 0L..102400L, { maxKb = it })
                        ToolHint("KB 단위 · 0은 제한 없음 · 최대 100 MB")
                    }
                    ToolHint("용량 우선은 필요하면 해상도도 줄입니다. ‘1장 테스트’로 먼저 확인하세요.")
                }
                1 -> {
                    Choices(ResizeMode.entries, resize, { when (it) { ResizeMode.NONE -> "크기 유지"; ResizeMode.PERCENT -> "비율"; ResizeMode.PIXELS -> "긴 변" } }, { resize = it })
                    when (resize) {
                        ResizeMode.NONE -> ToolHint("추가 축소 없음. 기기 안전 범위는 최대 4096px, 해상도 우선은 8192px입니다.")
                        ResizeMode.PERCENT -> {
                            CompactSlider("해상도", "${percent}%", percent.toFloat(), { percent = it.toInt() }, 1f..100f)
                            ToolHint("파일 용량의 비율이 아니라 사진 해상도의 비율입니다.")
                        }
                        ResizeMode.PIXELS -> {
                            Choices(listOf("1080", "1920", "2048", "4096"), longEdge, { "${it}px" }, { longEdge = it })
                            NumericInput("긴 변 · 1~8192px (확대 안 함)", longEdge, longEdge.toIntOrNull() in 1..8192, { longEdge = it })
                        }
                    }
                }
                2 -> {
                    Choices(ImageFormat.entries, format, { it.name }, { format = it })
                    Choices(ConflictPolicy.entries, policy, { if (it == ConflictPolicy.MAX_SIZE_FIRST) "용량 우선" else "해상도 우선" }, { policy = it })
                    Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("촬영 정보 보존"); ToolHint("날짜·위치 등 지원되는 EXIF 정보") }; Switch(metadata, { metadata = it }) }
                    ToolHint("원본을 수정하지 않습니다. Pictures / SB PHOTO TOOL / Compress에 저장됩니다.")
                }
            }
        }
        ToolFooter("${uris.size}장 압축", uris.isNotEmpty() && valid && !busy && !scanning, { onSubmit(spec(uris)) }, secondary = {
            OutlinedButton(enabled = currentUri != null && valid && !busy && !scanning, onClick = {
                testTask = scope.launch {
                    busy = true; error = null
                    try { result = MediaProcessor(context).previewCompression(spec(listOf(currentUri!!))); showOriginal = false }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { DiagnosticLog.recordException(context, "compress/test", failure); error = failure.message ?: "테스트에 실패했어요." }
                    finally { busy = false }
                }
            }, modifier = Modifier.height(50.dp)) { Text("1장 테스트") }
        })
    }
}
