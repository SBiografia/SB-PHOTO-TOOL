package com.sb.phototool.ui.gallery

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sb.phototool.data.DiagnosticLog
import com.sb.phototool.ui.editor.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoGalleryPicker(maxSelection: Int, onDismiss: () -> Unit, onConfirm: (List<Uri>) -> Unit,
                               compressionTools: Boolean = false) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        PhotoGalleryContent(maxSelection, onDismiss, onConfirm, compressionTools = compressionTools)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoGalleryContent(maxSelection: Int, onDismiss: () -> Unit, onConfirm: (List<Uri>) -> Unit,
    modifier: Modifier = Modifier, embedded: Boolean = false, onSinglePhoto: ((Uri) -> Unit)? = null,
    compressionTools: Boolean = false) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val repository = remember(context) { GalleryRepository(context.applicationContext) }
    val selection: GallerySelection = viewModel(key = if (embedded) "template-photo-gallery-selection" else "photo-gallery-selection")
    val session = rememberSaveable { UUID.randomUUID().toString() }
    LaunchedEffect(session, maxSelection) { selection.beginSession(session, maxSelection) }
    val selectedPhotos = selection.photos.toList()
    var access by remember { mutableStateOf(photoAccess(context)) }
    var revision by remember { mutableIntStateOf(0) }
    var permissionAttempted by rememberSaveable { mutableStateOf(false) }
    var albumId by rememberSaveable { mutableStateOf<String?>(null) }
    var albumName by rememberSaveable { mutableStateOf("모든 사진") }
    var favorites by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(GallerySort.RECENT) }
    var sortMenu by remember { mutableStateOf(false) }
    var albumSheet by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val permissions = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        access = photoAccess(context)
        repository.clearThumbnails()
        revision++
    }
    DisposableEffect(lifecycle, repository) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                access = photoAccess(context)
                repository.clearThumbnails()
                revision++
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); repository.clearThumbnails() }
    }
    LaunchedEffect(access) {
        if (access == PhotoAccess.NONE) {
            selection.photos.clear()
            if (!permissionAttempted) {
                permissionAttempted = true
                permissions.launch(photoPermissions())
            }
        }
    }
    val albumResult by produceState<Result<List<GalleryAlbum>>?>(null, access, revision) {
        value = null
        if (access != PhotoAccess.NONE) {
            value = try { Result.success(repository.albums()) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                DiagnosticLog.recordException(context, "gallery/albums", error)
                Result.failure(error)
            }
        }
    }
    fun close() { selection.photos.clear(); onDismiss() }
    fun select(photo: GalleryPhoto, selected: Boolean? = null) {
        if (busy) return
        if (onSinglePhoto != null) {
            busy = true
            scope.launch {
                try {
                    if (repository.unavailable(listOf(photo)).isEmpty()) {
                        message = null
                        onSinglePhoto(Uri.parse(photo.uri))
                    } else message = "사진을 읽지 못했어요. 다른 사진을 선택해 주세요."
                } finally { busy = false }
            }
            return
        }
        val accepted = if (selected == null) selection.toggle(photo) else selection.setSelected(photo, selected)
        message = if (!accepted) "최대 ${maxSelection}장까지 선택할 수 있어요. 선택한 사진을 해제해 주세요." else null
    }
    fun confirm() {
        if (busy || selection.photos.isEmpty()) return
        busy = true
        scope.launch {
            try {
                val selected = selection.photos.toList()
                val unavailable = repository.unavailable(selected)
                if (unavailable.isNotEmpty()) {
                    selection.photos.removeAll { it.uri in unavailable }
                    message = "읽을 수 없는 사진 ${unavailable.size}장을 제외했어요. 선택을 확인해 주세요."
                    revision++
                } else {
                    DiagnosticLog.record(context, "gallery/confirm", "selected=${selected.size}; limit=$maxSelection")
                    onConfirm(selected.map { Uri.parse(it.uri) })
                    selection.photos.clear()
                }
            } finally { busy = false }
        }
    }

    fun selectAll() {
        if (busy) return
        if (selection.photos.size >= maxSelection) {
            message = "최대 ${maxSelection}장이 선택되어 있어요. 다른 사진을 고르려면 선택을 해제해 주세요."
            return
        }
        busy = true
        scope.launch {
            try {
                val page = repository.page(albumId, favorites, null, sort, pageSize = maxSelection)
                val added = selection.selectAll(page.photos)
                message = when {
                    page.photos.isEmpty() -> "현재 앨범·필터에 선택할 사진이 없어요."
                    added == 0 -> "현재 앨범·필터의 사진이 모두 선택되어 있어요."
                    page.hasMore || selection.photos.size == maxSelection ->
                        "${sort.label}으로 ${added}장을 추가했어요. 한 번에 최대 ${maxSelection}장까지 선택할 수 있어요."
                    else -> "현재 앨범·필터의 사진 ${added}장을 추가했어요."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                DiagnosticLog.recordException(context, "gallery/select_all", failure)
                message = "사진을 읽지 못했어요. 전체 선택을 다시 눌러 주세요."
            } finally { busy = false }
        }
    }

    val dark = if (embedded) MaterialTheme.colorScheme.background.luminance() < .5f else isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF80A7FF), surface = Color(0xFF1C1C1E), background = Color(0xFF1C1C1E),
        secondaryContainer = Color(0xFF253757), onSecondaryContainer = Color(0xFFDBE6FF),
    ) else lightColorScheme(
        primary = Color(0xFF2368E8), surface = Color.White, background = Color.White,
        surfaceContainerLow = Color(0xFFF5F5F7), surfaceContainerHighest = Color(0xFFE5E5EA),
        secondaryContainer = Color(0xFFE8F0FF), onSecondaryContainer = Color(0xFF153460),
    )
    val viewport = visibleWindowSize()
    Box(modifier) {
        MaterialTheme(colorScheme = scheme) {
            Surface((if (embedded) Modifier.fillMaxSize() else Modifier.size(viewport)).testTag("photo_gallery")) {
                Column(Modifier.fillMaxSize().then(if (embedded) Modifier else Modifier.safeDrawingPadding())) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!embedded) IconButton(onClick = ::close, enabled = !busy) { Icon(Icons.Outlined.Close, "사진 선택 취소") }
                        TextButton(onClick = { albumSheet = true }, enabled = access != PhotoAccess.NONE && !busy, modifier = Modifier.weight(1f).testTag("gallery_albums")) {
                            Text(albumName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Icon(Icons.Outlined.ExpandMore, null)
                        }
                        TextButton(onClick = { if (embedded) close() else confirm() }, enabled = (embedded || selection.photos.isNotEmpty()) && !busy, modifier = Modifier.testTag("gallery_confirm")) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else if (embedded) Icon(Icons.Outlined.Check, "사진 선택 완료")
                            else Text("확인 (${selection.photos.size})", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (access == PhotoAccess.NONE) {
                        Column(Modifier.weight(1f).fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(56.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("앨범에서 사진을 고르려면 사진 접근 권한이 필요해요.")
                            Text("사진은 기기 안에서만 읽고 원본은 수정하지 않아요.", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { permissions.launch(photoPermissions()) }) { Text("사진 접근 허용") }
                            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("앱 설정 열기") }
                        }
                    } else {
                        if (access == PhotoAccess.LIMITED) {
                            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("허용한 사진만 표시 중", Modifier.weight(1f), fontSize = 12.sp)
                                TextButton(onClick = { permissions.launch(photoPermissions()) }, enabled = !busy) { Text("사진 더 허용", fontSize = 12.sp) }
                            }
                        }
                        if (Build.VERSION.SDK_INT >= 30 && !embedded) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(!favorites, { favorites = false }, label = { Text("사진") }, enabled = !busy)
                                FilterChip(favorites, { favorites = true }, label = { Text("즐겨찾기") }, enabled = !busy)
                            }
                        }
                        if (compressionTools) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = ::selectAll, enabled = !busy, modifier = Modifier.testTag("gallery_select_all")) { Text("전체 선택") }
                                TextButton(onClick = { selection.photos.clear(); message = null },
                                    enabled = selectedPhotos.isNotEmpty() && !busy, modifier = Modifier.testTag("gallery_clear_all")) { Text("전체 해제") }
                                Spacer(Modifier.weight(1f))
                                Box {
                                    TextButton(onClick = { sortMenu = true }, enabled = !busy, modifier = Modifier.testTag("gallery_sort")) {
                                        Text(sort.label)
                                        Icon(Icons.Outlined.ExpandMore, "정렬 변경")
                                    }
                                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                        GallerySort.entries.forEach { option ->
                                            DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                                sort = option; sortMenu = false; message = null
                                            }, modifier = Modifier.testTag("gallery_sort_${option.name}"),
                                                trailingIcon = { if (sort == option) Icon(Icons.Outlined.Check, "선택한 정렬") })
                                        }
                                    }
                                }
                            }
                        }
                        if (!embedded) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(when {
                                compressionTools -> "최대 ${maxSelection}장 · 길게 눌러 드래그 선택"
                                maxSelection == 1 -> "사진 1장 선택"
                                else -> "길게 누르고 드래그해 여러 장 선택"
                            }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!compressionTools) Text("최근 저장순", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (message != null) Text(message!!, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("gallery_message"), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                        val expectedPhotoCount = if (favorites) null else albumResult?.getOrNull()?.let { albums ->
                            if (albumId == null) albums.sumOf { it.count } else albums.firstOrNull { it.id == albumId }?.count
                        }
                        key(albumId, favorites, access, revision, sort) {
                            GalleryGrid(repository, albumId, favorites, expectedPhotoCount, selection, !embedded && maxSelection > 1 && !busy, !busy, ::select, Modifier.weight(1f),
                                columns = if (embedded) 4 else 3, showDates = !embedded && sort == GallerySort.RECENT, showSelection = !embedded, sort = sort)
                        }
                        if (selection.photos.isNotEmpty()) {
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (maxSelection == Int.MAX_VALUE) "${selection.photos.size}장 선택" else "${selection.photos.size} / ${maxSelection}장", Modifier.weight(1f), fontSize = 12.sp)
                                TextButton(onClick = { selection.photos.clear(); message = null }, enabled = !busy) { Text("선택 해제", fontSize = 12.sp) }
                            }
                            LazyRow(Modifier.fillMaxWidth().height(68.dp).padding(bottom = 8.dp).testTag("gallery_selection_strip"), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(selectedPhotos, key = { it.uri }) { photo ->
                                    Box(Modifier.size(60.dp).clickable(enabled = !busy) { select(photo, false) }.semantics { contentDescription = "${photo.name} 선택 해제" }) {
                                        GalleryThumbnail(repository, photo)
                                        SelectionBadge(selectedPhotos.indexOf(photo) + 1, Modifier.align(Alignment.TopEnd))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (albumSheet) {
                ModalBottomSheet(onDismissRequest = { albumSheet = false }) {
                    Text("앨범", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        item {
                            AlbumRow("모든 사진", albumResult?.getOrNull()?.sumOf { it.count }, albumId == null) { albumId = null; albumName = "모든 사진"; albumSheet = false }
                        }
                        items(albumResult?.getOrNull().orEmpty(), key = { it.id }) { album ->
                            AlbumRow(album.name, album.count, albumId == album.id) { albumId = album.id; albumName = album.name; albumSheet = false }
                        }
                        if (albumResult == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        if (albumResult?.isFailure == true) item { TextButton(onClick = { revision++ }) { Text("앨범을 읽지 못했어요 · 다시 시도") } }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun visibleWindowSize(): DpSize {
    val view = LocalView.current
    val density = LocalDensity.current
    fun readSize(): IntSize {
        val frame = Rect()
        view.getWindowVisibleDisplayFrame(frame)
        return IntSize(frame.width().coerceAtLeast(1), frame.height().coerceAtLeast(1))
    }
    var size by remember(view) { mutableStateOf(readSize()) }
    DisposableEffect(view) {
        // Compose 1.7's full-width Dialog measures against screenHeightDp, including system bars
        // on newer Android targets. Bound it to the host's visible frame to keep the footer visible.
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener { size = readSize() }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return with(density) { DpSize(size.width.toDp(), size.height.toDp()) }
}

@Composable
private fun AlbumRow(name: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (selected) Icon(Icons.Outlined.Check, "선택한 앨범", Modifier.padding(start = 12.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

private sealed interface GalleryEntry {
    data class Day(val label: String) : GalleryEntry
    data class Photo(val photo: GalleryPhoto) : GalleryEntry
}

@Composable
private fun GalleryGrid(repository: GalleryRepository, albumId: String?, favorites: Boolean, expectedPhotoCount: Int?, selection: GallerySelection, allowDrag: Boolean, enabled: Boolean, select: (GalleryPhoto, Boolean?) -> Unit, modifier: Modifier, columns: Int = 3, showDates: Boolean = true, showSelection: Boolean = true, sort: GallerySort = GallerySort.RECENT) {
    val context = LocalContext.current
    val photos = remember { mutableStateListOf<GalleryPhoto>() }
    var request by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var lastRequestedPhotoCount by remember { mutableIntStateOf(-1) }
    val currentExpectedPhotoCount by rememberUpdatedState(expectedPhotoCount)
    LaunchedEffect(request) {
        loading = true; error = false
        try {
            val page = repository.page(albumId, favorites, photos.lastOrNull(), sort)
            val knownUris = photos.mapTo(mutableSetOf()) { it.uri }
            photos.addAll(page.photos.filter { knownUris.add(it.uri) })
            hasMore = page.hasMore || (currentExpectedPhotoCount?.let { photos.size < it } == true)
            DiagnosticLog.record(context, "gallery/page", "received=${page.photos.size}; loaded=${photos.size}; expected=${currentExpectedPhotoCount ?: -1}; hasMore=$hasMore")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            DiagnosticLog.recordException(context, "gallery/page", failure)
            error = true
        } finally { loading = false }
    }
    LaunchedEffect(expectedPhotoCount, loading) {
        if (!loading && !favorites && expectedPhotoCount != null && photos.size < expectedPhotoCount) hasMore = true
    }
    val entries = remember(photos.toList(), showDates) {
        val format = DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E)", Locale.KOREAN).withZone(ZoneId.systemDefault())
        buildList<GalleryEntry> {
            var previous = ""
            for (photo in photos) {
                val day = format.format(Instant.ofEpochSecond(photo.addedSeconds))
                if (day != previous && showDates) { add(GalleryEntry.Day(day)); previous = day }
                add(GalleryEntry.Photo(photo))
            }
        }
    }
    val grid = rememberLazyGridState()
    val currentEntries by rememberUpdatedState(entries)
    LaunchedEffect(grid) {
        snapshotFlow {
            val lastVisible = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearEnd = lastVisible >= (currentEntries.lastIndex - 24).coerceAtLeast(0)
            nearEnd && !loading && !error && hasMore
        }.collect { shouldLoad ->
            if (shouldLoad && photos.size != lastRequestedPhotoCount) {
                lastRequestedPhotoCount = photos.size
                request++
            }
        }
    }
    val currentSelect by rememberUpdatedState(select)
    var dragPosition by remember { mutableStateOf<Offset?>(null) }
    val visited = remember { mutableSetOf<String>() }
    var dragSelect by remember { mutableStateOf(true) }
    var lastIndex by remember { mutableIntStateOf(-1) }
    fun hit(position: Offset): Int? = grid.layoutInfo.visibleItemsInfo.firstOrNull {
        position.x >= it.offset.x && position.x < it.offset.x + it.size.width && position.y >= it.offset.y && position.y < it.offset.y + it.size.height
    }?.index
    fun applyDrag(position: Offset) {
        val index = hit(position) ?: return
        val range = if (lastIndex < 0) index..index else minOf(lastIndex, index)..maxOf(lastIndex, index)
        for (i in range) {
            val photo = (currentEntries.getOrNull(i) as? GalleryEntry.Photo)?.photo ?: continue
            if (visited.add(photo.uri)) currentSelect(photo, dragSelect)
        }
        lastIndex = index
    }
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(dragPosition != null) {
        while (dragPosition != null) {
            val point = dragPosition ?: break
            val bottom = grid.layoutInfo.viewportEndOffset
            val speed = when {
                point.y < edge -> -((edge - point.y) / edge).coerceIn(0f, 1f) * 24f
                point.y > bottom - edge -> ((point.y - bottom + edge) / edge).coerceIn(0f, 1f) * 24f
                else -> 0f
            }
            if (speed != 0f) { grid.scrollBy(speed); applyDrag(point) }
            delay(16)
        }
    }
    Box(modifier.fillMaxWidth()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns), state = grid,
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize().testTag("gallery_grid").pointerInput(allowDrag) {
                if (allowDrag) detectDragGesturesAfterLongPress(
                    onDragStart = { point ->
                        visited.clear(); lastIndex = -1
                        val photo = (hit(point)?.let { currentEntries.getOrNull(it) } as? GalleryEntry.Photo)?.photo
                        dragSelect = photo == null || selection.photos.none { it.uri == photo.uri }
                        dragPosition = point; applyDrag(point)
                    },
                    onDrag = { change, _ -> change.consume(); dragPosition = change.position; applyDrag(change.position) },
                    onDragEnd = { dragPosition = null }, onDragCancel = { dragPosition = null },
                )
            },
        ) {
            itemsIndexed(entries, key = { _, entry -> when (entry) { is GalleryEntry.Day -> "day-${entry.label}"; is GalleryEntry.Photo -> entry.photo.uri } }, span = { _, entry -> GridItemSpan(if (entry is GalleryEntry.Day) 3 else 1) }) { _, entry ->
                when (entry) {
                    is GalleryEntry.Day -> Text(entry.label, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    is GalleryEntry.Photo -> {
                        val order = selection.photos.indexOfFirst { it.uri == entry.photo.uri } + 1
                        Box(Modifier.aspectRatio(1f).testTag("gallery_photo_${entry.photo.id}").semantics {
                            selected = order > 0
                            contentDescription = "${entry.photo.name}, ${if (order > 0) "${order}번째 선택" else "선택 안 됨"}"
                        }.clickable(enabled = enabled, role = if (showSelection) Role.Checkbox else Role.Button) { select(entry.photo, null) }) {
                            GalleryThumbnail(repository, entry.photo)
                            if (sort == GallerySort.SIZE_DESC) Text(formatBytes(entry.photo.sizeBytes),
                                Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .65f)).padding(horizontal = 6.dp, vertical = 2.dp),
                                color = Color.White, fontSize = 11.sp)
                            if (order > 0) Box(Modifier.matchParentSize().border(3.dp, MaterialTheme.colorScheme.primary))
                            if (showSelection) SelectionBadge(order, Modifier.align(Alignment.TopEnd).padding(6.dp))
                        }
                    }
                }
            }
            if (loading) item(span = { GridItemSpan(3) }) { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) } }
            if (error) item(span = { GridItemSpan(3) }) { TextButton(onClick = { request++ }, modifier = Modifier.fillMaxWidth()) { Text("사진을 읽지 못했어요 · 다시 시도") } }
            if (hasMore && !loading && !error) item(span = { GridItemSpan(3) }) { TextButton(onClick = { request++ }, modifier = Modifier.fillMaxWidth()) { Text("사진 더 불러오기") } }
        }
        if (photos.isEmpty() && !loading && !error) Text(if (favorites) "즐겨찾기 사진이 없어요" else "이 앨범에 표시할 사진이 없어요", Modifier.align(Alignment.Center))
    }
}

@Composable
private fun GalleryThumbnail(repository: GalleryRepository, photo: GalleryPhoto) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, repository, photo.uri) { value = repository.thumbnail(photo) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val loaded = bitmap
        if (loaded != null) Image(loaded.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Outlined.PhotoLibrary, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SelectionBadge(order: Int, modifier: Modifier) {
    Box(modifier.size(26.dp).background(if (order > 0) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = .22f), CircleShape).border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
        if (order > 0) Text("$order", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
