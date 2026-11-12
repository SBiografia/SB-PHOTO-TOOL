package com.sb.phototool.ui.collage

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sb.phototool.EditorPreviewState
import com.sb.phototool.loadEditorPreview
import com.sb.phototool.media.*
import com.sb.phototool.model.*
import com.sb.phototool.ui.gallery.PhotoGalleryContent
import com.sb.phototool.ui.gallery.PhotoGalleryPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private enum class DesignSheet { BACKGROUND, TEXT, SHAPE, ALIGN, LAYERS, RATIO, DECORATION, LAYOUT, MARGIN, BORDER, PRECISION, ADJUST, ROTATE }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CollageDesignStudio(editor: CollageEditorState, onBack: () -> Unit, onTemplates: () -> Unit, onSubmit: (JobSpec) -> Unit, onLibrary: () -> Unit = {}, onPick: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job = editor.document
    val inlinePanelMaxHeight = (LocalConfiguration.current.screenHeightDp.dp * .4f).coerceAtMost(260.dp)
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var multi by rememberSaveable { mutableStateOf(false) }
    var gallery by rememberSaveable { mutableStateOf(false) }
    var slotId by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by rememberSaveable { mutableStateOf<DesignSheet?>(null) }
    var showSave by rememberSaveable { mutableStateOf(false) }
    var confirmTemplates by rememberSaveable { mutableStateOf(false) }
    var backgroundPicker by rememberSaveable { mutableStateOf(false) }
    var requestedBackgroundKind by rememberSaveable { mutableStateOf(BackgroundKind.PHOTO) }
    var pending by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var advance by rememberSaveable { mutableStateOf(true) }
    var batchPicker by rememberSaveable { mutableStateOf(false) }
    var swapSource by rememberSaveable { mutableStateOf<String?>(null) }
    fun clearSelection() {
        selected = emptyList(); multi = false; swapSource = null
    }
    fun closeSheet(commit: Boolean = false) {
        if (sheet == DesignSheet.ADJUST && !commit) editor.cancelGesture() else editor.finishGesture()
        sheet = null
    }
    // Old saved UI state can still name menus removed from the toolbar.
    LaunchedEffect(sheet) { if (sheet == DesignSheet.ALIGN || sheet == DesignSheet.LAYERS) closeSheet() }
    val bgUri = job.background.uri.takeIf { job.background.kind == BackgroundKind.PHOTO || job.background.kind == BackgroundKind.BLUR }
    val keys = (job.tiles.map { it.uri }.filter { it.isNotBlank() } + listOfNotNull(bgUri?.takeIf { it.isNotBlank() })).toSet()
    val budget = editorPreviewPixelBudget(keys.size + 1)
    val previews = remember(budget) { mutableStateMapOf<String, EditorPreviewState>() }
    var bgImage by remember { mutableStateOf<Bitmap?>(null) }
    var bgReady by remember { mutableStateOf(false) }
    val elements = collageDesignElements(job)
    val chosen = elements.singleOrNull { it.id in selected }
    val photoIndices = selectedCollagePhotoIndices(job, selected.toSet(), includeLocked = true)
    val photoIds = photoIndices.map { photoElementId(job.tiles[it], it) }.toSet()
    val selectedPhoto = photoIndices.firstOrNull()?.let { job.tiles[it] }
    val editablePhotos = photoIndices.map { job.tiles[it] }.filterNot { it.locked }
    val allLocked = selectedPhoto != null && editablePhotos.isEmpty()
    val allFitInside = editablePhotos.isNotEmpty() && editablePhotos.all { it.fitInside }
    fun toggleMultiSelection() {
        editor.finishGesture(); swapSource = null
        multi = !multi
        selected = if (multi) selected.filter { it in photoIds } else selected.take(1)
    }
    LaunchedEffect(sheet, selected, multi) { if (sheet == DesignSheet.ROTATE && photoIds.isEmpty() && !multi) closeSheet() }
    val aspect = job.ratio.ratio ?: job.freeformCanvasAspect ?: job.tiles.firstOrNull { it.uri.isNotBlank() }?.let {
        (previews[it.uri] as? EditorPreviewState.Ready)?.bitmap?.let { image -> image.width.toFloat() / image.height }
    } ?: 1f

    LaunchedEffect(keys, budget, retry) {
        previews.keys.toList().filter { it !in keys }.forEach(previews::remove)
        for (key in keys) {
            if (previews[key] is EditorPreviewState.Ready) continue
            previews[key] = EditorPreviewState.Loading
            val result = withContext(Dispatchers.IO) { loadEditorPreview(context, Uri.parse(key), "collage-design", HIGH_QUALITY_PREVIEW_MAX_EDGE, budget) }
            ensureActive(); previews[key] = result
        }
    }
    LaunchedEffect(bgUri, job.background.kind, job.background.blur, previews[bgUri]) {
        bgReady = false; bgImage = null
        if (bgUri == null) bgReady = true
        else (previews[bgUri] as? EditorPreviewState.Ready)?.bitmap?.let { source ->
            bgImage = if (job.background.kind == BackgroundKind.BLUR) withContext(Dispatchers.Default) { blurredCollageBackground(source, job.background.blur) } else source
            bgReady = true
        }
    }
    LaunchedEffect(elements.map { it.id }, job.tiles.map { it.uri }, multi) {
        selected = selected.filter { id -> elements.any { it.id == id && (!multi || it.kind == DesignElementKind.PHOTO && job.tiles[it.index].uri.isNotBlank()) } }
        if (slotId != null && elements.none { it.id == slotId }) gallery = false
    }
    val allReady = !pending && job.tiles.any { it.uri.isNotBlank() } && bgReady && keys.all { previews[it] is EditorPreviewState.Ready }
    val failed = keys.any { previews[it] == EditorPreviewState.Failed }

    fun adjustOriginalCanvas(next: CollageJob, newUri: String? = null, newAspect: Float? = null): CollageJob {
        if (next.ratio != CanvasRatio.ORIGINAL) return next
        val first = next.tiles.firstOrNull { it.uri.isNotBlank() } ?: return next
        val ratio = if (first.uri == newUri && newAspect != null) newAspect else
            (previews[first.uri] as? EditorPreviewState.Ready)?.bitmap?.let { it.width.toFloat() / it.height } ?: return next
        return (if (next.mode == CollageMode.LAYOUT) next else changeDesignCanvasAspect(next, aspect, ratio)).copy(freeformCanvasAspect = ratio)
    }

    fun select(id: String?) {
        editor.finishGesture()
        val from = editor.document.tiles.indices.firstOrNull { photoElementId(editor.document.tiles[it], it) == swapSource } ?: -1
        val to = editor.document.tiles.indices.firstOrNull { photoElementId(editor.document.tiles[it], it) == id }
        if (multi && id != null && (to == null || editor.document.tiles[to].uri.isBlank())) return
        if (from >= 0 && to != null && from != to) {
            editor.update(swapCollagePhotos(editor.document, from, to)); swapSource = null
            selected = listOf(id!!); return
        }
        selected = when {
            id == null -> emptyList()
            multi && id in selected -> selected - id
            multi -> selected + id
            to != null && selected.singleOrNull() == id -> emptyList()
            else -> listOf(id)
        }
        if (selected.isEmpty()) swapSource = null
    }
    fun chooseSlot(index: Int) {
        if (pending) return
        val tile = editor.document.tiles.getOrNull(index) ?: return
        val id = photoElementId(tile, index)
        if (tile.locked) { selected = listOf(id); notice = "잠금을 해제한 뒤 사진을 교체해 주세요"; return }
        selected = listOf(id); slotId = id; gallery = true; multi = false
    }
    fun picked(uri: Uri, asBackground: Boolean = false) {
        if (pending) return
        val targetId = slotId
        pending = true; notice = null
        scope.launch {
            try {
                val sourceAspect = withContext(Dispatchers.IO) { MediaProcessor(context).sourceAspectRatio(uri) }
                val current = editor.document
                if (asBackground) {
                    editor.update(current.copy(background = current.background.copy(kind = requestedBackgroundKind, uri = uri.toString())))
                    backgroundPicker = false
                } else {
                    val index = current.tiles.indexOfFirst { it.elementId == targetId }.takeIf { it >= 0 }
                        ?: current.tiles.indices.firstOrNull { photoElementId(current.tiles[it], it) == targetId }
                    if (index != null) {
                        val old = current.tiles[index]
                        if (old.locked) return@launch
                        var replacement = photoContent(old, TileTransform(uri.toString()))
                        if (current.templateId == null && current.mode == CollageMode.FREEFORM) {
                            replacement = restoreFreeformPhoto(replacement, freeformFrame(current, index), sourceAspect, aspect)
                        }
                        editor.update(adjustOriginalCanvas(current.copy(tiles = current.tiles.mapIndexed { i, p -> if (i == index) replacement else p }), uri.toString(), sourceAspect))
                        if (advance && old.uri.isBlank()) {
                            val next = nextEmptyCollageSlot(editor.document, index)
                            if (next != null) { slotId = photoElementId(editor.document.tiles[next], next); selected = listOf(slotId!!) }
                            else gallery = false
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                notice = "사진을 읽지 못했어요. 다른 사진을 선택해 주세요."
                if (asBackground) backgroundPicker = false
            }
            finally { pending = false }
        }
    }
    fun editPhotos(includeLocked: Boolean = false, action: (Int, TileTransform) -> TileTransform) {
        val current = editor.document
        editor.update(adjustOriginalCanvas(updateSelectedCollagePhotos(current, photoIds, includeLocked, action)))
    }
    fun editPhoto(action: (TileTransform) -> TileTransform) = editPhotos { _, photo -> action(photo) }
    fun addPhotoSlot(overlay: Boolean) {
        if (job.tiles.size >= MAX_COLLAGE_PHOTOS || job.placementLocked) return
        val current = if (overlay) detachCollageLayout(job, aspect) else job.copy(layoutId = "auto", customCells = emptyList())
        val id = "photo-" + UUID.randomUUID()
        val layer = (collageDesignElements(current).maxOfOrNull { it.layer } ?: 0) + 1
        editor.update(current.copy(tiles = current.tiles + TileTransform("", elementId = id, freeform = NormalizedCell(.2f, .2f, .8f, .8f), layer = layer)))
        selected = listOf(id); slotId = id; gallery = true
    }

    BackHandler { when { sheet != null -> closeSheet(); gallery -> gallery = false; showSave -> showSave = false; selected.isNotEmpty() || multi -> clearSelection(); else -> onBack() } }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().testTag("collage-design-studio")) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (sheet != null) closeSheet() else if (gallery) gallery = false else if (selected.isNotEmpty() || multi) clearSelection() else onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "콜라주 나가기") }
            Column(Modifier.weight(1f)) {
                Text(if (job.templateId != null) "템플릿 콜라주" else "콜라주", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(job.tiles.count { it.uri.isNotBlank() }.toString() + "장 · " + job.tiles.size + "/15칸", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { editor.undo(); clearSelection() }, enabled = editor.canUndo && !pending) { Icon(Icons.AutoMirrored.Outlined.Undo, "실행 취소") }
            IconButton(onClick = { editor.redo(); clearSelection() }, enabled = editor.canRedo && !pending) { Icon(Icons.AutoMirrored.Outlined.Redo, "다시 실행") }
            TextButton(onClick = { showSave = true }, enabled = allReady, modifier = Modifier.testTag("design-save")) { Text("저장", fontWeight = FontWeight.SemiBold) }
        }
        if (!gallery) Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (job.templateId != null) Text("사진 칸을 눌러 채워 보세요", Modifier.weight(1f), fontSize = 12.sp)
            else Row(Modifier.weight(1f)) {
                CollageMode.entries.forEach { mode -> TextButton(onClick = {
                    val aspects = previews.mapNotNull { (key, p) -> (p as? EditorPreviewState.Ready)?.bitmap?.let { key to it.width.toFloat() / it.height } }.toMap()
                    editor.update(changeCollageMode(job, mode, aspect, aspects)); clearSelection()
                }, modifier = Modifier.testTag("design-mode-" + mode.name).semantics { this.selected = job.mode == mode }) { Text(if (mode == CollageMode.FREEFORM) "자유형" else "레이아웃", color = if (job.mode == mode) EditorAccent else MaterialTheme.colorScheme.onSurfaceVariant) } }
            }
            Text("배치 편집", fontSize = 12.sp)
            Switch(!job.placementLocked, { editor.update(job.copy(placementLocked = !it)) }, modifier = Modifier.testTag("design-placement-edit"))
        }
        CollageDesignCanvas(editor, previews, bgImage, selected.toSet(), multi, false, gallery, ::select, ::chooseSlot, Modifier.weight(1f).fillMaxWidth())
        if (gallery) {
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(advance, { advance = it }); Text("다음 빈칸으로", fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { batchPicker = true }, enabled = !pending && job.tiles.any { it.uri.isBlank() && !it.locked }) { Text("빈칸 한번에 채우기") }
            }
            Text(if (pending) "사진 준비 중" else notice ?: "사진을 누르면 선택한 칸에 적용됩니다.",
                Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 11.sp,
                color = if (notice == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, maxLines = 1)
            PhotoGalleryContent(1, { gallery = false }, {}, embedded = true, onSinglePhoto = { picked(it) }, modifier = Modifier.weight(1f).fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(when { pending -> "사진 준비 중"; swapSource != null -> "교환할 다른 사진을 눌러 주세요"; notice != null -> notice!!; failed -> "사진을 읽지 못했어요";
                    multi -> "사진을 눌러 선택·해제하세요 · " + photoIds.size + "장 선택";
                    job.tiles.none { it.uri.isNotBlank() } -> "＋를 눌러 사진을 넣어 주세요"; job.placementLocked -> "배치 편집을 켜면 사진틀을 움직일 수 있어요";
                    else -> "드래그·두 손가락으로 이동·확대·회전" }, Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (failed) TextButton(onClick = { retry++ }) { Text("다시 불러오기") }
            }
            Surface(shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), color = MaterialTheme.colorScheme.surface) {
                if (sheet == DesignSheet.ROTATE && (selectedPhoto != null || multi)) {
                    Column(Modifier.fillMaxWidth().heightIn(max = inlinePanelMaxHeight).padding(horizontal = 16.dp).testTag("design-inline-panel")) {
                        Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("회전/반전", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            StudioSelectionActions(true, multi, ::toggleMultiSelection) { closeSheet() }
                        }
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                            if (editablePhotos.isNotEmpty()) PhotoRotationControls(editor, photoIds)
                            else Text(if (photoIds.isEmpty()) "편집할 사진을 선택해 주세요." else "잠금을 해제한 사진을 선택해 주세요.", fontSize = 12.sp)
                        }
                    }
                } else Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(when { photoIds.size > 1 -> photoIds.size.toString() + "장 선택"; selectedPhoto != null -> "사진 " + (photoIndices.first() + 1); multi -> "사진 선택"; chosen?.kind == DesignElementKind.TEXT -> "글자"; chosen?.kind == DesignElementKind.DECORATION -> job.decorations[chosen.index].kind.label; else -> "콜라주 도구" }, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        if (selected.isNotEmpty() || multi) StudioSelectionActions(selectedPhoto != null || multi, multi, ::toggleMultiSelection, ::clearSelection)
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (selectedPhoto != null) {
                            StudioButton(if (allLocked) "잠금 해제" else "잠금", if (allLocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock) {
                                editPhotos(includeLocked = true) { _, photo -> photo.copy(locked = !allLocked) }
                            }
                            StudioButton("사진 교체", Icons.Outlined.PhotoLibrary, enabled = !allLocked && photoIds.size == 1) { chooseSlot(photoIndices.first()) }
                            StudioButton("교환", Icons.Outlined.SwapHoriz, enabled = !allLocked && photoIds.size == 1 && job.tiles.size > 1) { swapSource = photoIds.singleOrNull() }
                            StudioButton("필터·보정", Icons.Outlined.Tune, enabled = editablePhotos.any { it.uri.isNotBlank() }) { editor.beginGesture(); sheet = DesignSheet.ADJUST }
                            StudioButton("정밀 조정", Icons.Outlined.ControlCamera, enabled = !allLocked) { sheet = DesignSheet.PRECISION }
                            StudioButton(if (allFitInside) "채우기" else "맞추기", if (allFitInside) Icons.Outlined.Crop else Icons.Outlined.FitScreen, enabled = !allLocked) { editPhoto { it.copy(fitInside = !allFitInside, zoom = 1f, panX = 0f, panY = 0f) } }
                            StudioButton("사진틀 모양", Icons.Outlined.Category, enabled = !allLocked) { sheet = DesignSheet.SHAPE }
                            StudioButton("원본 복원", Icons.Outlined.Restore, enabled = editablePhotos.any { it.uri.isNotBlank() }) { editPhotos { index, p ->
                                val base = p.copy(zoom = 1f, panX = 0f, panY = 0f)
                                val bitmap = (previews[p.uri] as? EditorPreviewState.Ready)?.bitmap
                                if (job.templateId == null && bitmap != null && job.mode == CollageMode.FREEFORM)
                                    restoreFreeformPhoto(base, p.freeform ?: freeformFrame(job, index), bitmap.width.toFloat() / bitmap.height, aspect) else base
                            } }
                            StudioButton("회전/반전", Icons.Outlined.Rotate90DegreesCw, enabled = !allLocked) { sheet = DesignSheet.ROTATE }
                            StudioButton("비우기", Icons.Outlined.ImageNotSupported, enabled = !allLocked) { editPhoto { photoContent(it, TileTransform("")) } }
                        } else if (chosen?.kind == DesignElementKind.TEXT) {
                            StudioButton("글자 수정", Icons.Outlined.Edit) { sheet = DesignSheet.TEXT }
                        }
                        if (selected.isNotEmpty()) {
                            StudioButton("앞으로", Icons.Outlined.FlipToFront, enabled = !job.placementLocked && chosen != null && selectedPhoto?.locked != true) { chosen?.let { editor.update(moveDesignLayer(detachCollageLayout(job, aspect), it.id, true)) } }
                            StudioButton("뒤로", Icons.Outlined.FlipToBack, enabled = !job.placementLocked && chosen != null && selectedPhoto?.locked != true) { chosen?.let { editor.update(moveDesignLayer(detachCollageLayout(job, aspect), it.id, false)) } }
                            StudioButton("삭제", Icons.Outlined.DeleteOutline, enabled = !job.placementLocked && (selectedPhoto == null || !allLocked)) { editor.update(adjustOriginalCanvas(removeDesignElements(job, selected.toSet()))); clearSelection() }
                        }
                    }
                    if (chosen != null && selectedPhoto == null && !job.placementLocked) CollageRotationSlider(chosen.id, chosen.rotation,
                        { editor.drag(updateDesignElement(editor.document, chosen.id, rotation = it)) }, editor::finishGesture)
                    if (selected.isEmpty() && !multi) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp).testTag("design-collage-tools"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        StudioButton("템플릿", Icons.AutoMirrored.Outlined.ViewQuilt) { confirmTemplates = true }
                        StudioButton("레이아웃", Icons.Outlined.GridView) { sheet = DesignSheet.LAYOUT }
                        StudioButton("비율", Icons.Outlined.AspectRatio) { sheet = DesignSheet.RATIO }
                        StudioButton("여백", Icons.Outlined.BorderOuter) { sheet = DesignSheet.MARGIN }
                        StudioButton("테두리", Icons.Outlined.BorderStyle) { sheet = DesignSheet.BORDER }
                        StudioButton("배경", Icons.Outlined.Wallpaper) { sheet = DesignSheet.BACKGROUND }
                        StudioButton("글자 추가", Icons.Outlined.TextFields) {
                            val id = "text-" + UUID.randomUUID(); val layer = (elements.maxOfOrNull { it.layer } ?: 0) + 1
                            editor.update(job.copy(texts = job.texts + CollageText(id, layer = layer)))
                            selected = listOf(id); sheet = DesignSheet.TEXT
                        }
                        StudioButton("장식", Icons.Outlined.AutoAwesome) { sheet = DesignSheet.DECORATION }
                        StudioButton("보관함", Icons.Outlined.FolderOpen, onClick = onLibrary)
                        if (job.mode == CollageMode.LAYOUT) StudioButton("칸 추가", Icons.Outlined.AddBox, enabled = job.tiles.size < MAX_COLLAGE_PHOTOS && !job.placementLocked) { addPhotoSlot(false) }
                        StudioButton("겹치는 사진 추가", Icons.Outlined.AddPhotoAlternate, enabled = job.tiles.size < MAX_COLLAGE_PHOTOS && !job.placementLocked) { addPhotoSlot(true) }
                        StudioButton("사진 다시 선택", Icons.Outlined.PhotoLibrary, onClick = onPick)
                    }
                }
            }
        }
    }
    if (confirmTemplates) AlertDialog(onDismissRequest = { confirmTemplates = false }, title = { Text("템플릿 바꾸기") },
        text = { Text("사진을 유지하고 새 디자인에 순서대로 배치합니다. 남는 사진은 자유 배치로 추가하며 실행 취소로 되돌릴 수 있어요.") },
        confirmButton = { TextButton(onClick = { confirmTemplates = false; onTemplates() }) { Text("템플릿 보기") } }, dismissButton = { TextButton(onClick = { confirmTemplates = false }) { Text("취소") } })
    if (sheet != null && sheet !in setOf(DesignSheet.ROTATE, DesignSheet.ALIGN, DesignSheet.LAYERS)) ModalBottomSheet(onDismissRequest = { closeSheet() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp).testTag("design-sheet-content"), verticalArrangement = Arrangement.spacedBy(if (sheet == DesignSheet.MARGIN) 0.dp else 8.dp)) {
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            when (sheet) {
                DesignSheet.MARGIN -> CollageMarginControls(editor)
                DesignSheet.BORDER -> CollageBorderControls(editor)
                DesignSheet.PRECISION -> PhotoPrecisionControls(editor, photoIds, aspect)
                DesignSheet.ADJUST -> PhotoAdjustmentControls(editor, photoIds, { closeSheet(true) }, { closeSheet() })
                DesignSheet.BACKGROUND -> BackgroundDesignControls(editor) { kind -> requestedBackgroundKind = kind; backgroundPicker = true }
                DesignSheet.TEXT -> selected.singleOrNull()?.let { TextDesignControls(editor, it) { sheet = null } }
                DesignSheet.SHAPE -> ShapeDesignControls(editor, selected.toSet())
                DesignSheet.ROTATE, DesignSheet.ALIGN, DesignSheet.LAYERS -> Unit
                DesignSheet.DECORATION -> {
                    DecorationDesignControls(editor) { id -> selected = listOf(id); sheet = null }
                    chosen?.takeIf { it.kind == DesignElementKind.DECORATION }?.let { e ->
                        val d = job.decorations[e.index]
                        DesignColorEditor("장식 색", d.color) { c -> editor.update(job.copy(decorations = job.decorations.map { if (it.id == d.id) it.copy(color = c) else it })) }
                        DesignSlider("장식 불투명도", d.opacity, 0f..1f, { v -> editor.drag(editor.document.copy(decorations = editor.document.decorations.map { if (it.id == d.id) it.copy(opacity = v) else it })) }, editor::finishGesture)
                    }
                }
                DesignSheet.RATIO -> {
                    Text("캔버스 비율", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    val first = job.tiles.firstOrNull { it.uri.isNotBlank() }?.let { (previews[it.uri] as? EditorPreviewState.Ready)?.bitmap }
                    val originalAspect = first?.let { it.width.toFloat() / it.height } ?: aspect
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CanvasRatio.entries.forEach { r -> CollageRatioOption(r.label, r.ratio ?: originalAspect,
                            selected = job.ratio == r, modifier = Modifier.testTag("design-ratio-" + r.name)) {
                            val nextAspect = r.ratio ?: originalAspect
                            editor.update((if (job.mode == CollageMode.LAYOUT) job.copy(freeformCanvasAspect = nextAspect) else changeDesignCanvasAspect(job, aspect, nextAspect)).copy(ratio = r))
                        } }
                    }
                }
                DesignSheet.LAYOUT -> {
                    Text("레이아웃", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text("배치 그림을 눌러 비교하세요. 사진·보정은 유지됩니다.")
                    Box(Modifier.fillMaxWidth().height(220.dp)) { LayoutPanel(job) { layout ->
                        val aspects = previews.mapNotNull { (key, p) -> (p as? EditorPreviewState.Ready)?.bitmap?.let { key to it.width.toFloat() / it.height } }.toMap()
                        editor.update(changeCollageMode(job, CollageMode.LAYOUT, aspect, aspects).copy(layoutId = layout.id, customCells = emptyList(), templateId = null, placementLocked = false))
                    } }
                }
                null -> Unit
            }
            if (sheet != DesignSheet.TEXT && sheet != DesignSheet.ADJUST) TextButton(onClick = { editor.finishGesture(); sheet = null }, modifier = Modifier.fillMaxWidth()) { Text("완료") }
        }
    }
    if (batchPicker) PhotoGalleryPicker(job.tiles.count { it.uri.isBlank() && !it.locked }.coerceAtLeast(1), { batchPicker = false }, { uris ->
        batchPicker = false; pending = true
        scope.launch {
            try {
                val firstAspect = withContext(Dispatchers.IO) { MediaProcessor(context).sourceAspectRatio(uris.first()) }
                editor.update(adjustOriginalCanvas(fillEmptyCollageSlots(editor.document, uris.map(Uri::toString)), uris.first().toString(), firstAspect))
                gallery = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "사진을 읽지 못했어요. 권한과 원본을 확인해 주세요." }
            finally { pending = false }
        }
    })
    if (backgroundPicker) PhotoGalleryPicker(1, { backgroundPicker = false }, { picked(it.single(), asBackground = true) })
    if (showSave) CollageDesignSaveSheet(editor, allReady, { showSave = false }, onSubmit)
}

@Composable
private fun StudioSelectionActions(showMulti: Boolean, multi: Boolean, onMulti: () -> Unit, onDone: () -> Unit) {
    if (showMulti) {
        FilterChip(selected = multi, onClick = onMulti, label = { Text("다중 선택", fontSize = 12.sp) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = EditorAccent.copy(alpha = .24f), selectedLabelColor = EditorAccent),
            modifier = Modifier.testTag("design-multi-select"))
        Spacer(Modifier.width(6.dp))
    }
    TextButton(onClick = onDone) { Text("완료") }
}

@Composable
private fun StudioButton(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        modifier = Modifier.widthIn(min = 64.dp).heightIn(min = 64.dp).testTag("design-tool-" + text)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, Modifier.size(24.dp))
            Text(text, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollageDesignSaveSheet(editor: CollageEditorState, ready: Boolean, onClose: () -> Unit, onSubmit: (JobSpec) -> Unit) {
    var mode by rememberSaveable { mutableStateOf(editor.document.exportSize.mode) }
    var edge by rememberSaveable { mutableStateOf(editor.document.exportSize.customLongEdge.toString()) }
    val valid = mode != ExportSizeMode.CUSTOM || (edge.toIntOrNull() ?: 0) in 320..4096
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("콜라주 저장", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("빈 사진 칸과 편집 표시는 저장되지 않습니다.", fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportSizeMode.entries.forEach { m -> FilterChip(mode == m, { mode = m }, label = { Text(when(m) { ExportSizeMode.ORIGINAL -> "원본 품질"; ExportSizeMode.SOCIAL_1080 -> "1080px"; ExportSizeMode.CUSTOM -> "직접 입력" }) }) }
            }
            if (mode == ExportSizeMode.CUSTOM) OutlinedTextField(edge, { edge = it.filter(Char::isDigit).take(5) }, label = { Text("긴 변 · 320~4096px") }, isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Text("새 JPEG 사진으로 저장합니다. 원본 품질은 긴 변 최대 4096px입니다.", fontSize = 12.sp)
            Button(onClick = {
                val job = editor.document.copy(id = UUID.randomUUID().toString(), exportSize = ExportSize(mode, edge.toIntOrNull() ?: 2048))
                editor.update(job.copy(id = "draft")); onClose(); onSubmit(job)
            }, enabled = ready && valid, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("갤러리에 저장") }
        }
    }
}
