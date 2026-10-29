package com.sb.phototool.ui.collage

import android.app.Activity
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sb.phototool.ui.editor.CompactSlider
import com.sb.phototool.ui.gallery.PhotoGalleryPicker
import androidx.core.view.WindowCompat
import com.sb.phototool.EditorPreviewState
import com.sb.phototool.data.DiagnosticLog
import com.sb.phototool.loadEditorPreview
import com.sb.phototool.media.*
import com.sb.phototool.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private enum class EditorTab(val title: String, val icon: ImageVector) {
    RATIO("비율", Icons.Outlined.AspectRatio),
    LAYOUT("레이아웃", Icons.Outlined.GridOn),
    MARGIN("여백", Icons.Outlined.SpaceDashboard),
    BORDER("테두리", Icons.Outlined.BorderColor),
}

internal val EditorAccent = Color(0xFF80A7FF)
private val EditorScheme = darkColorScheme(
    primary = EditorAccent, onPrimary = Color(0xFF091B3E),
    background = Color(0xFF101113), surface = Color(0xFF1C1D21),
    surfaceContainer = Color(0xFF24262B), surfaceContainerLow = Color(0xFF18191D),
    onSurface = Color(0xFFF3F3F6), onSurfaceVariant = Color(0xFFA9ABB4),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollageEditorScreen(uris: List<Uri>, onBack: () -> Unit, onPick: () -> Unit, onSubmit: (JobSpec) -> Unit, selectionRevision: Int = 0) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
    }
    DisposableEffect(activity, view) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = controller?.isAppearanceLightStatusBars ?: false
        val lightNavigation = controller?.isAppearanceLightNavigationBars ?: false
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = lightStatus
            controller?.isAppearanceLightNavigationBars = lightNavigation
        }
    }
    val inputs = uris.map(Uri::toString)
    val editor = rememberSaveable(inputs, selectionRevision, saver = CollageEditorState.StateSaver) { CollageEditorState.create(inputs) }
    val document = editor.document
    var tab by rememberSaveable { mutableStateOf(EditorTab.LAYOUT) }
    var selectedIndex by rememberSaveable { mutableIntStateOf(-1) }
    var swapSource by rememberSaveable { mutableIntStateOf(-1) }
    var pickerSlot by rememberSaveable { mutableIntStateOf(-1) }
    var showPhotoPicker by rememberSaveable { mutableStateOf(false) }
    var showSave by rememberSaveable { mutableStateOf(false) }
    var cropping by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingPhoto by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val photoKeys = document.tiles.map { it.uri }.toSet()
    val freeform = document.mode == CollageMode.FREEFORM
    val previewBudget = editorPreviewPixelBudget(photoKeys.size)
    val previews = remember(previewBudget) { mutableStateMapOf<String, EditorPreviewState>() }

    LaunchedEffect(inputs, selectionRevision) { selectedIndex = -1; swapSource = -1 }
    LaunchedEffect(document.tiles.size) {
        if (selectedIndex !in document.tiles.indices) selectedIndex = -1
        if (swapSource !in document.tiles.indices) swapSource = -1
    }
    LaunchedEffect(selectedIndex, document.mode) { if (selectedIndex < 0 || !freeform) cropping = false }
    LaunchedEffect(photoKeys, previewBudget, retry) {
        previews.keys.toList().filter { it !in photoKeys }.forEach(previews::remove)
        for (uri in photoKeys) {
            if (previews[uri] is EditorPreviewState.Ready) continue
            previews[uri] = EditorPreviewState.Loading
            val loaded = withContext(Dispatchers.IO) { loadEditorPreview(context, Uri.parse(uri), "collage", HIGH_QUALITY_PREVIEW_MAX_EDGE, previewBudget) }
            ensureActive()
            previews[uri] = loaded
        }
    }

    fun editTile(transform: (TileTransform) -> TileTransform) {
        val slot = selectedIndex
        if (slot !in editor.document.tiles.indices) return
        editor.update(editor.document.copy(tiles = editor.document.tiles.mapIndexed { i, tile -> if (i == slot) transform(tile) else tile }))
    }
    fun canvasAspect(job: CollageJob = editor.document): Float = job.ratio.ratio ?:
        (previews[job.tiles.firstOrNull()?.uri] as? EditorPreviewState.Ready)?.bitmap?.let { it.width.toFloat() / it.height }
            ?: job.freeformCanvasAspect ?: 1f

    fun restorePhoto(tile: TileTransform, resetRotation: Boolean = false): TileTransform {
        val bitmap = (previews[tile.uri] as? EditorPreviewState.Ready)?.bitmap ?: return tile
        val current = if (resetRotation) tile.copy(quarterTurns = 0, flipHorizontal = false, flipVertical = false) else tile
        return restoreFreeformPhoto(current, tile.freeform ?: NormalizedCell(.2f, .2f, .8f, .8f),
            bitmap.width.toFloat() / bitmap.height, canvasAspect())
    }
    fun applyPickedPhoto(uri: Uri) {
        val slot = pickerSlot
        val replacedUri = editor.document.tiles.getOrNull(slot)?.uri
        pickerSlot = -1
        showPhotoPicker = false
        pendingPhoto = true
        notice = null
        scope.launch {
            try {
                // Read dimensions only; do not allocate another full preview just to place a photo.
                val aspect = withContext(Dispatchers.IO) { MediaProcessor(context).sourceAspectRatio(uri) }
                val current = editor.document
                val isFreeform = current.mode == CollageMode.FREEFORM
                if (replacedUri != null && current.tiles.getOrNull(slot)?.uri == replacedUri) {
                    val newCanvas = if (slot == 0 && current.ratio == CanvasRatio.ORIGINAL) aspect else canvasAspect(current)
                    val adjusted = changeFreeformCanvasAspect(current, canvasAspect(current), newCanvas)
                    val old = adjusted.tiles[slot]
                    val replacement = TileTransform(uri.toString(), freeform = old.freeform, layer = old.layer)
                    val prepared = if (isFreeform) restoreFreeformPhoto(replacement, freeformFrame(adjusted, slot), aspect, newCanvas) else replacement
                    editor.update(adjusted.copy(tiles = adjusted.tiles.mapIndexed { i, tile -> if (i == slot) prepared else tile }))
                    selectedIndex = slot
                    DiagnosticLog.record(context, "collage/editor", "photo_replaced; slot=$slot")
                } else if (replacedUri == null && current.tiles.size < MAX_COLLAGE_PHOTOS) {
                    val added = TileTransform(uri.toString(), freeform = if (isFreeform)
                        freeformAspect(NormalizedCell(.2f, .2f, .8f, .8f), aspect, canvasAspect(current)) else null,
                        layer = (current.tiles.maxOfOrNull { it.layer } ?: -1) + 1)
                    editor.update(current.copy(tiles = current.tiles + added, layoutId = "auto", customCells = emptyList()))
                    selectedIndex = if (isFreeform) current.tiles.size else -1
                    tab = EditorTab.LAYOUT
                    DiagnosticLog.record(context, "collage/editor", "photo_added; count=${editor.document.tiles.size}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = "사진을 읽지 못했어요. 다른 사진을 선택해 주세요"
            } finally { pendingPhoto = false }
        }
    }
    fun selectPhoto(index: Int) {
        if (index != selectedIndex) cropping = false
        if (swapSource in editor.document.tiles.indices && index != swapSource) {
            val tiles = editor.document.tiles.toMutableList()
            val source = tiles[swapSource]
            tiles[swapSource] = tiles[index]
            tiles[index] = source
            editor.update(editor.document.copy(tiles = tiles))
            swapSource = -1
            selectedIndex = index
            notice = "사진 위치를 바꿨어요"
        } else {
            selectedIndex = index
        }
    }
    BackHandler {
        when {
            showSave -> showSave = false
            cropping -> cropping = false
            selectedIndex >= 0 -> { selectedIndex = -1; swapSource = -1 }
            else -> onBack()
        }
    }
    val allReady = !pendingPhoto && document.tiles.isNotEmpty() && photoKeys.all { previews[it] is EditorPreviewState.Ready }
    val failed = photoKeys.any { previews[it] == EditorPreviewState.Failed }

    MaterialTheme(colorScheme = EditorScheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                val compact = maxHeight < 540.dp
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "콜라주 나가기") }
                        Column(Modifier.weight(1f)) {
                            Text("콜라주", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text("${document.tiles.size} / ${MAX_COLLAGE_PHOTOS}장", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { editor.undo(); selectedIndex = -1; swapSource = -1 }, enabled = editor.canUndo) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, "실행 취소")
                        }
                        IconButton(onClick = { editor.redo(); selectedIndex = -1; swapSource = -1 }, enabled = editor.canRedo) {
                            Icon(Icons.AutoMirrored.Outlined.Redo, "다시 실행")
                        }
                        TextButton(onClick = { selectedIndex = -1; swapSource = -1; showSave = true }, enabled = allReady) {
                            Text("저장", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (document.tiles.isEmpty()) {
                        Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Dashboard, null, Modifier.size(72.dp), tint = EditorAccent)
                            Spacer(Modifier.height(24.dp))
                            Text("사진을 한 장의 이야기로", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            Text("사진을 고른 뒤, 배치와 구도를 직접 맞춰 보세요.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(32.dp))
                            Button(onClick = onPick, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                                Icon(Icons.Outlined.AddPhotoAlternate, null)
                                Spacer(Modifier.width(8.dp)); Text("사진 선택 · 최대 9장")
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            CollageMode.entries.forEach { mode ->
                                val active = mode == document.mode
                                TextButton(onClick = {
                                    val aspects = previews.mapNotNull { (uri, preview) ->
                                        (preview as? EditorPreviewState.Ready)?.bitmap?.let { uri to it.width.toFloat() / it.height }
                                    }.toMap()
                                    editor.update(changeCollageMode(editor.document, mode, canvasAspect(), aspects))
                                    selectedIndex = -1; swapSource = -1; cropping = false
                                    notice = null; tab = EditorTab.LAYOUT
                                }, enabled = mode == CollageMode.LAYOUT || allReady,
                                    modifier = Modifier.width(108.dp).fillMaxHeight().testTag("collage-mode-${mode.name}")
                                    .semantics { selected = active },
                                    colors = ButtonDefaults.textButtonColors(contentColor = if (active) EditorAccent else MaterialTheme.colorScheme.onSurfaceVariant)) {
                                    Icon(if (mode == CollageMode.FREEFORM) Icons.Outlined.OpenWith else Icons.Outlined.GridOn, null, Modifier.size(17.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (mode == CollageMode.FREEFORM) "자유형" else "레이아웃")
                                }
                            }
                        }
                        if (freeform) FreeformCollageCanvas(
                            editor, previews, selectedIndex, cropping, ::selectPhoto,
                            { selectedIndex = -1; cropping = false }, Modifier.weight(1f).fillMaxWidth(),
                        ) else CollageCanvas(
                            editor = editor, previews = previews, selectedIndex = selectedIndex, swapMode = swapSource >= 0,
                            onSelect = ::selectPhoto, onDeselect = { selectedIndex = -1; swapSource = -1 },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth().height(if (compact) 32.dp else 40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    swapSource >= 0 -> "위치를 바꿀 다른 사진을 눌러 주세요"
                                    pendingPhoto -> "사진 준비 중"
                                    notice != null -> notice!!
                                    failed -> "사진을 읽지 못했어요"
                                    !allReady -> "사진 불러오는 중 · ${previews.values.count { it is EditorPreviewState.Ready }}/${photoKeys.size}"
                                    freeform && cropping -> "안쪽 드래그·확대 · 가장자리로 자르기"
                                    freeform -> "드래그로 이동 · 두 손가락이나 손잡이로 크기 조절"
                                    selectedIndex >= 0 -> "드래그·두 손가락 확대 · 테두리 손잡이로 칸 조절"
                                    else -> notice ?: "사진을 눌러 구도를 조절하세요"
                                },
                                Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (failed) TextButton(onClick = { retry++ }) { Text("다시 불러오기", fontSize = 12.sp) }
                            else {
                                TextButton(onClick = onPick, modifier = Modifier.height(36.dp)) { Text("다시 선택", fontSize = 12.sp) }
                                IconButton(
                                    onClick = { pickerSlot = -1; showPhotoPicker = true },
                                    enabled = !pendingPhoto && document.tiles.size < MAX_COLLAGE_PHOTOS,
                                ) { Icon(Icons.Outlined.AddPhotoAlternate, "사진 추가", Modifier.size(20.dp)) }
                            }
                        }
                        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
                            // A fixed dock height prevents the canvas from moving under an active finger.
                            Column(Modifier.fillMaxWidth().height(if (compact) 148.dp else 192.dp)) {
                                if (selectedIndex in document.tiles.indices) {
                                    Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("사진 ${selectedIndex + 1}" + if (cropping) " · 자르기" else "", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                        TextButton(onClick = { if (cropping) cropping = false else { selectedIndex = -1; swapSource = -1 } }) { Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Text("완료") }
                                    }
                                    if (freeform && cropping) {
                                        val tile = document.tiles[selectedIndex]
                                        val bitmap = (previews[tile.uri] as? EditorPreviewState.Ready)?.bitmap
                                        val originalAspect = bitmap?.let { if (tile.quarterTurns.mod(2) == 1) it.height.toFloat() / it.width else it.width.toFloat() / it.height } ?: 1f
                                        val first = (previews[document.tiles.first().uri] as? EditorPreviewState.Ready)?.bitmap
                                        val canvasAspect = document.ratio.ratio ?: first?.let { it.width.toFloat() / it.height } ?: 1f
                                        Column(Modifier.padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
                                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                                                listOf("원본" to originalAspect, "1:1" to 1f, "4:5" to .8f, "3:2" to 1.5f, "9:16" to 9f / 16).forEach { (label, aspect) ->
                                                    TextButton(onClick = { editTile { if (label == "원본") restorePhoto(it) else it.copy(freeform = freeformAspect(freeformFrame(document, selectedIndex), aspect, canvasAspect), zoom = 1f, panX = 0f, panY = 0f) } },
                                                        modifier = Modifier.testTag("freeform-crop-$label")) { Text(label) }
                                                }
                                            }
                                            CompactSlider("확대", "${(tile.zoom * 100).toInt()}%", tile.zoom, { value ->
                                                editor.drag(editor.document.copy(tiles = editor.document.tiles.mapIndexed { i, item -> if (i == selectedIndex) item.copy(zoom = value) else item }))
                                            }, 1f..3f, rowHeight = 44.dp, onValueChangeFinished = editor::finishGesture)
                                        }
                                    } else Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (freeform) {
                                            PhotoAction("자르기", Icons.Outlined.Crop) { cropping = true }
                                            PhotoAction("원본 복원", Icons.Outlined.AspectRatio, enabled = previews[document.tiles[selectedIndex].uri] is EditorPreviewState.Ready) { editTile { restorePhoto(it) } }
                                            val order = collagePaintOrder(document)
                                            PhotoAction("앞으로", Icons.Outlined.FlipToFront, enabled = order.last() != selectedIndex) { editor.update(moveFreeformLayer(document, selectedIndex, true)) }
                                            PhotoAction("뒤로", Icons.Outlined.FlipToBack, enabled = order.first() != selectedIndex) { editor.update(moveFreeformLayer(document, selectedIndex, false)) }
                                        }
                                        PhotoAction("교체", Icons.Outlined.PhotoLibrary) { pickerSlot = selectedIndex; showPhotoPicker = true }
                                        if (!freeform) PhotoAction("위치 교환", Icons.Outlined.SwapHoriz, enabled = document.tiles.size > 1, active = swapSource >= 0) { swapSource = if (swapSource < 0) selectedIndex else -1 }
                                        PhotoAction("회전", Icons.AutoMirrored.Outlined.RotateRight) { editTile {
                                            val rotated = it.copy(quarterTurns = (it.quarterTurns + 1).mod(4), zoom = 1f, panX = 0f, panY = 0f)
                                            if (freeform) restorePhoto(rotated) else rotated
                                        } }
                                        PhotoAction("좌우 반전", Icons.Outlined.Flip) { editTile { it.copy(flipHorizontal = !it.flipHorizontal) } }
                                        PhotoAction("초기화", Icons.Outlined.RestartAlt) { editTile {
                                            if (freeform) restorePhoto(it, resetRotation = true) else TileTransform(it.uri, freeform = it.freeform, layer = it.layer)
                                        } }
                                        PhotoAction("삭제", Icons.Outlined.DeleteOutline) {
                                            val remaining = document.tiles.filterIndexed { i, _ -> i != selectedIndex }
                                            val newCanvas = document.ratio.ratio ?: (previews[remaining.firstOrNull()?.uri] as? EditorPreviewState.Ready)?.bitmap?.let { it.width.toFloat() / it.height } ?: canvasAspect()
                                            val next = document.copy(tiles = remaining, layoutId = "auto", customCells = emptyList())
                                            editor.update(changeFreeformCanvasAspect(next, canvasAspect(), newCanvas))
                                            selectedIndex = -1; swapSource = -1
                                        }
                                    }
                                    if (freeform && !cropping) FreeformPhotoStrip(document, previews, selectedIndex, ::selectPhoto)
                                } else {
                                    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp)) {
                                        EditorTab.entries.forEach { item ->
                                            val title = if (freeform && item == EditorTab.LAYOUT) "사진" else if (freeform && item == EditorTab.MARGIN) "모서리" else item.title
                                            Row(
                                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                                                    .clickable(role = Role.Tab) { tab = item; notice = null }
                                                    .semantics { selected = tab == item; contentDescription = "$title 탭" }
                                                    .testTag("collage-tab-${item.name}"),
                                                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(item.icon, null, Modifier.size(17.dp), tint = if (tab == item) EditorAccent else MaterialTheme.colorScheme.onSurfaceVariant)
                                                Spacer(Modifier.width(4.dp))
                                                Text(title,
                                                    fontSize = 11.sp, maxLines = 1, fontWeight = FontWeight.Medium, color = if (tab == item) EditorAccent else MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = Color.White.copy(alpha = .06f))
                                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                                        when (tab) {
                                            EditorTab.RATIO -> RatioPanel(document.ratio) { ratio ->
                                                val first = (previews[document.tiles.first().uri] as? EditorPreviewState.Ready)?.bitmap
                                                val nextAspect = ratio.ratio ?: first?.let { it.width.toFloat() / it.height } ?: 1f
                                                editor.update(changeFreeformCanvasAspect(document, canvasAspect(), nextAspect).copy(ratio = ratio))
                                            }
                                            EditorTab.LAYOUT -> if (freeform) Column {
                                                Text("겹쳐 가려진 사진도 아래에서 선택할 수 있어요", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                FreeformPhotoStrip(document, previews, selectedIndex, ::selectPhoto)
                                            } else LayoutPanel(document) { layout -> editor.update(document.copy(layoutId = layout.id, customCells = emptyList())) }
                                            EditorTab.MARGIN -> Column(Modifier.verticalScroll(rememberScrollState())) {
                                                if (!freeform) {
                                                    EditorSlider("바깥 여백", document.outerMarginPercent, .12f, { editor.drag(editor.document.copy(outerMarginPercent = it)) }, editor::finishGesture)
                                                    EditorSlider("사진 간격", document.gapPercent, .08f, { editor.drag(editor.document.copy(gapPercent = it)) }, editor::finishGesture)
                                                }
                                                EditorSlider("모서리", document.cornerPercent, .12f, { editor.drag(editor.document.copy(cornerPercent = it)) }, editor::finishGesture)
                                            }
                                            EditorTab.BORDER -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                ColorPalette("배경", document.backgroundColor) { editor.update(document.copy(backgroundColor = it)) }
                                                ColorPalette("테두리", document.borderColor) { editor.update(document.copy(borderColor = it)) }
                                                EditorSlider("두께", document.borderWidthPercent, .02f, { editor.drag(editor.document.copy(borderWidthPercent = it)) }, editor::finishGesture)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showPhotoPicker) {
            PhotoGalleryPicker(1, onDismiss = { showPhotoPicker = false; pickerSlot = -1 }, onConfirm = { applyPickedPhoto(it.single()) })
        }
        if (showSave) {
            ModalBottomSheet(onDismissRequest = { showSave = false }, containerColor = MaterialTheme.colorScheme.surface) {
                var customEdge by remember { mutableStateOf(document.exportSize.customLongEdge.toString()) }
                var mode by remember { mutableStateOf(document.exportSize.mode) }
                val valid = mode != ExportSizeMode.CUSTOM || (customEdge.toIntOrNull() ?: 0) in 320..4096
                Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("콜라주 저장", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text("현재 배치 그대로 새 사진을 저장합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        ExportSizeMode.entries.forEach { choice ->
                            FilterChip(selected = mode == choice, onClick = { mode = choice }, label = { Text(when(choice) {
                                ExportSizeMode.ORIGINAL -> "원본 품질"
                                ExportSizeMode.SOCIAL_1080 -> "1080px"
                                ExportSizeMode.CUSTOM -> "직접 입력"
                            }) })
                        }
                    }
                    if (mode == ExportSizeMode.CUSTOM) OutlinedTextField(
                        value = customEdge, onValueChange = { customEdge = it.filter(Char::isDigit).take(5) }, singleLine = true,
                        label = { Text("긴 변 · 320~4096px") }, isError = !valid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("원본 품질은 긴 변 최대 4096px로 저장됩니다.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = {
                            val job = editor.document.copy(id = UUID.randomUUID().toString(), exportSize = ExportSize(mode, customEdge.toIntOrNull() ?: 2048))
                            editor.update(job.copy(id = "draft"))
                            DiagnosticLog.record(context, "collage/editor", "save; count=${job.tiles.size}; layout=${job.layoutId}; ratio=${job.ratio}")
                            showSave = false
                            onSubmit(job)
                        }, enabled = allReady && valid, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
                    ) { Text("갤러리에 저장", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

@Composable
private fun PhotoAction(label: String, icon: ImageVector, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.width(64.dp).height(56.dp).clip(RoundedCornerShape(12.dp))
            .background(if (active) EditorAccent.copy(alpha = .16f) else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val color = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f) else if (active) EditorAccent else MaterialTheme.colorScheme.onSurface
        Icon(icon, null, Modifier.size(20.dp), tint = color)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = color, maxLines = 1)
    }
}

@Composable
private fun FreeformPhotoStrip(document: CollageJob, previews: Map<String, EditorPreviewState>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(document.tiles.size) { index ->
            val tile = document.tiles[index]
            val bitmap = (previews[tile.uri] as? EditorPreviewState.Ready)?.bitmap
            Box(Modifier.width(48.dp).fillMaxHeight().padding(vertical = 2.dp).clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF34363D)).border(if (index == selectedIndex) 2.dp else 1.dp,
                    if (index == selectedIndex) EditorAccent else Color(0xFF555861), RoundedCornerShape(6.dp))
                .clickable { onSelect(index) }.testTag("freeform-select-$index")
                .semantics { contentDescription = "사진 ${index + 1} 선택"; selected = index == selectedIndex }) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().padding(2.dp), contentScale = ContentScale.Crop)
                Text("${index + 1}", Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .6f)).padding(horizontal = 4.dp),
                    color = Color.White, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun LayoutPanel(document: CollageJob, onSelect: (CollageLayout) -> Unit) {
    val layouts = remember(document.tiles.size) { collageLayouts(document.tiles.size) }
    var family by rememberSaveable(document.tiles.size) { mutableStateOf<CollageLayoutFamily?>(null) }
    val filtered = layouts.filter { family == null || it.family == family }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.height(40.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf<CollageLayoutFamily?>(null) + CollageLayoutFamily.entries).forEach { category ->
                val count = layouts.count { category == null || it.family == category }
                if (count > 0) Text(
                    "${category?.label ?: "전체"} $count",
                    Modifier.fillMaxHeight().clip(RoundedCornerShape(8.dp))
                        .background(if (family == category) EditorAccent.copy(alpha = .12f) else Color.Transparent)
                        .clickable(role = Role.Tab) { family = category }
                        .semantics { selected = family == category }
                        .testTag("collage-family-${category?.name ?: "ALL"}").padding(horizontal = 10.dp, vertical = 10.dp),
                    fontSize = 11.sp, color = if (family == category) EditorAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            LazyHorizontalGrid(rows = GridCells.Fixed(if (maxHeight >= 100.dp) 2 else 1),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().testTag("collage-layouts")) {
                items(filtered, key = { it.id }) { layout ->
                    val active = document.layoutId == layout.id
                    val color = if (active) EditorAccent else Color(0xFF888C96)
                    Canvas(
                        Modifier.width(66.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                            .background(if (active) EditorAccent.copy(alpha = .12f) else Color.Transparent)
                            .clickable(role = Role.Button) { onSelect(layout) }
                            .semantics { contentDescription = "${layout.label} 레이아웃"; selected = active }
                            .testTag("collage-layout-${layout.id}").padding(5.dp),
                    ) {
                        val edge = size.minDimension
                        val origin = Offset((size.width - edge) / 2, (size.height - edge) / 2)
                        layout.cells.forEach { cell ->
                            drawRect(color, origin + Offset(cell.left * edge + 1.5f, cell.top * edge + 1.5f),
                                Size((cell.right - cell.left) * edge - 3f, (cell.bottom - cell.top) * edge - 3f), style = Stroke(1.5.dp.toPx()))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RatioPanel(selected: CanvasRatio, onSelect: (CanvasRatio) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        items(CanvasRatio.entries.toList()) { ratio ->
            val active = selected == ratio
            Column(
                Modifier.width(64.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onSelect(ratio) }
                    .semantics { this.selected = active; contentDescription = "${ratio.label} 비율" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                val aspect = ratio.ratio ?: .75f
                val width = if (aspect >= 1f) 44.dp else 58.dp * aspect
                val height = if (aspect >= 1f) 44.dp / aspect else 58.dp
                Box(Modifier.height(66.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width, height).clip(RoundedCornerShape(5.dp)).background(if (active) EditorAccent else Color(0xFF454850)))
                }
                Spacer(Modifier.height(6.dp))
                Text(ratio.label, color = if (active) EditorAccent else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EditorSlider(label: String, value: Float, maximum: Float, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    CompactSlider(label, "${(value / maximum * 100).toInt()}%", value, onChange, 0f..maximum, rowHeight = 44.dp, onValueChangeFinished = onFinished)
}

@Composable
private fun ColorPalette(label: String, selected: Int, onSelect: (Int) -> Unit) {
    val colors = listOf(0xFFFFFFFF, 0xFFF2F2F7, 0xFFB0B3BB, 0xFF464950, 0xFF000000, 0xFFD7E5FA, 0xFFFFD9DC, 0xFFF1E6CF, 0xFFD8E7DB).map(Long::toInt)
    Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(54.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            colors.forEachIndexed { index, color ->
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { onSelect(color) }
                    .semantics { contentDescription = "$label 색상 ${index + 1}"; this.selected = color == selected }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color(color)).border(if (color == selected) 3.dp else 1.dp, if (color == selected) EditorAccent else Color(0xFF676A73), CircleShape))
                }
            }
        }
    }
}
