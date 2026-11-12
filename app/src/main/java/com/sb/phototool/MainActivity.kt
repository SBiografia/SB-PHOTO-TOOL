package com.sb.phototool

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.sb.phototool.data.DiagnosticLog
import com.sb.phototool.data.JobStore
import com.sb.phototool.data.CompressionOriginalStore
import com.sb.phototool.media.MAX_COLLAGE_PHOTOS
import com.sb.phototool.media.decodeStillBitmap
import com.sb.phototool.media.CompressionOriginalArchive
import com.sb.phototool.model.JobSpec
import com.sb.phototool.model.JobCodec
import com.sb.phototool.model.CompressJob
import com.sb.phototool.worker.ToolboxWorker
import com.sb.phototool.ui.collage.CollageEditorScreen
import com.sb.phototool.ui.compress.CompressEditorScreen
import com.sb.phototool.ui.compress.MAX_COMPRESSION_PHOTOS
import com.sb.phototool.ui.compress.CompressionOriginalAction
import com.sb.phototool.ui.split.SplitEditorScreen
import com.sb.phototool.ui.gif.GifEditorScreen
import com.sb.phototool.ui.gallery.PhotoGalleryPicker
import com.sb.phototool.ui.editor.ToolSelectionState
import com.sb.phototool.ui.editor.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private enum class Screen { HOME, COLLAGE, COMPRESS, SPLIT, GIF, SETTINGS }
private enum class PickerTarget { COLLAGE, COMPRESS, SPLIT, GIF_PHOTOS }
internal sealed interface EditorPreviewState {
    data object Loading : EditorPreviewState
    data class Ready(val bitmap: Bitmap) : EditorPreviewState
    data object Failed : EditorPreviewState
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ToolboxApp() }
    }
}

@Composable
private fun ToolboxApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val workManager = remember { WorkManager.getInstance(context) }
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val editorStateHolder = rememberSaveableStateHolder()
    var galleryTarget by rememberSaveable { mutableStateOf<PickerTarget?>(null) }
    var pendingJobJson by rememberSaveable { mutableStateOf<String?>(null) }
    var activeWorkId by rememberSaveable { mutableStateOf<UUID?>(null) }
    var activeJobId by rememberSaveable { mutableStateOf<String?>(null) }
    var transientMessage by remember { mutableStateOf<String?>(null) }
    val originalArchive = remember(context) { CompressionOriginalArchive(context.applicationContext) }
    var pendingArchiveJobId by rememberSaveable { mutableStateOf<String?>(null) }
    var preparingArchive by remember { mutableStateOf(false) }
    var archiveRevision by rememberSaveable { mutableIntStateOf(0) }
    var archiveMessageJobId by rememberSaveable { mutableStateOf<String?>(null) }
    var archiveMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val archiveLauncher = rememberLauncherForActivityResult(StartIntentSenderForResult()) { result ->
        val jobId = pendingArchiveJobId ?: return@rememberLauncherForActivityResult
        pendingArchiveJobId = null
        archiveMessageJobId = jobId
        if (result.resultCode != Activity.RESULT_OK) {
            archiveMessage = "원본 폴더 이동을 취소했어요."
            archiveRevision++
        } else {
            preparingArchive = true
            scope.launch {
                try {
                    val moved = originalArchive.move(jobId)
                    archiveMessage = buildString {
                        append("원본 ${moved.movedCount}장을 compress_original 폴더로 이동했어요.")
                        if (moved.failedCount > 0) append(" ${moved.failedCount}장은 이동을 완료하지 못했어요. 다시 시도해 주세요.")
                        if (moved.unavailableCount > 0) append(" ${moved.unavailableCount}장은 접근할 수 없거나 이동을 지원하지 않아 제외했어요.")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    DiagnosticLog.recordException(context, "compress/archive_result", failure)
                    archiveMessage = "원본 폴더 이동을 완료하지 못했어요. 이동한 사진은 compress_original 폴더에서 확인하고, 남은 원본은 다시 시도해 주세요."
                } finally { preparingArchive = false; archiveRevision++ }
            }
        }
    }
    fun requestOriginalArchive(jobId: String) {
        if (Build.VERSION.SDK_INT < 30 || preparingArchive || pendingArchiveJobId != null) return
        preparingArchive = true
        archiveMessageJobId = jobId
        archiveMessage = null
        scope.launch {
            try {
                val targets = originalArchive.targets(jobId)
                if (targets.mediaUris.isEmpty()) {
                    archiveMessage = "별도 보관 폴더로 이동할 원본이 없어요. 원본 상태와 사진 권한을 확인해 주세요."
                } else {
                    val request = MediaStore.createWriteRequest(context.contentResolver, targets.mediaUris)
                    pendingArchiveJobId = jobId
                    archiveLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                pendingArchiveJobId = null
                DiagnosticLog.recordException(context, "compress/archive_request", failure)
                archiveMessage = "원본 이동을 위한 파일 수정 확인창을 열지 못했어요."
            } finally { preparingArchive = false; archiveRevision++ }
        }
    }

    var collageUris by rememberSaveable { mutableStateOf<List<Uri>>(emptyList()) }
    var collageSelectionRevision by rememberSaveable { mutableIntStateOf(0) }
    val selections: ToolSelectionState = androidx.lifecycle.viewmodel.compose.viewModel()
    val compressUris = selections.compressionPhotos
    var compressFolder by selections.compressionFolder
    var splitUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var gifPhotoUris by rememberSaveable { mutableStateOf<List<Uri>>(emptyList()) }

    fun returnToHome() {
        if (screen == Screen.COLLAGE) {
            // The collage draft saver includes selected photo URIs, so discard it with the selection.
            editorStateHolder.removeState("collage")
            collageSelectionRevision++
        }
        collageUris = emptyList()
        compressUris.clear()
        compressFolder = null
        splitUri = null
        gifPhotoUris = emptyList()
        screen = Screen.HOME
    }

    val folderPicker = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure { DiagnosticLog.recordException(context, "picker/folder_permission", it) }
            compressUris.clear()
            compressFolder = uri
        }
    }

    suspend fun enqueue(job: JobSpec) {
        JobStore(context).save(job)
        if (job is CompressJob) {
            try { CompressionOriginalStore(context).begin(job.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { DiagnosticLog.recordException(context, "compress/archive_begin", failure) }
        }
        val request = OneTimeWorkRequestBuilder<ToolboxWorker>()
            .setInputData(workDataOf(ToolboxWorker.KEY_JOB_ID to job.id))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork("photo-tool-${job.id}", ExistingWorkPolicy.KEEP, request)
        activeWorkId = request.id
        activeJobId = job.id
        transientMessage = "백그라운드 처리를 시작했어요."
    }

    val notificationPermission = rememberLauncherForActivityResult(RequestPermission()) {
        val job = pendingJobJson?.let(JobCodec::decode) ?: return@rememberLauncherForActivityResult
        pendingJobJson = null
        scope.launch { enqueue(job) }
    }

    fun submit(job: JobSpec) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingJobJson = JobCodec.encode(job)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            scope.launch { enqueue(job) }
        }
    }

    val workInfo by produceState<WorkInfo?>(initialValue = null, key1 = activeWorkId) {
        val id = activeWorkId ?: return@produceState
        workManager.getWorkInfoByIdFlow(id).collectLatest { value = it }
    }

    ToolboxTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    onOpen = { screen = it },
                    onOpenSettings = { screen = Screen.SETTINGS },
                    workInfo = workInfo,
                    onCancel = {
                        activeWorkId?.let(workManager::cancelWorkById)
                        activeJobId?.let { jobId ->
                            scope.launch { JobStore(context).remove(jobId) }
                        }
                        transientMessage = "작업을 취소했어요."
                    },
                    message = transientMessage,
                    onDismissMessage = { transientMessage = null },
                    compressionCleanup = { jobId -> CompressionOriginalAction(
                        jobId, preparingArchive || pendingArchiveJobId != null, archiveRevision,
                        if (archiveMessageJobId == jobId) archiveMessage else null, ::requestOriginalArchive,
                    ) },
                )

                Screen.COLLAGE -> editorStateHolder.SaveableStateProvider("collage") {
                    CollageEditorScreen(
                        uris = collageUris,
                        selectionRevision = collageSelectionRevision,
                        onBack = ::returnToHome,
                        onPick = { galleryTarget = PickerTarget.COLLAGE },
                        onSubmit = { job -> submit(job); returnToHome() },
                    )
                }

                Screen.COMPRESS -> editorStateHolder.SaveableStateProvider("compress") { CompressEditorScreen(
                    uris = compressUris.toList(),
                    folder = compressFolder,
                    onBack = ::returnToHome,
                    onPick = {
                        galleryTarget = PickerTarget.COMPRESS
                    },
                    onPickFolder = { folderPicker.launch(null) },
                    onResolveFolder = { folder, includeChildren ->
                        val results = collectImagesInTree(context, folder, includeChildren)
                        if (results.isEmpty()) throw IllegalStateException("선택한 폴더에서 지원되는 사진을 찾지 못했어요.")
                        compressFolder = null
                        compressUris.clear()
                        compressUris.addAll(results.distinct())
                    },
                    onRemove = { compressUris.remove(it) },
                    onSubmit = { submit(it); returnToHome() },
                ) }

                Screen.SPLIT -> editorStateHolder.SaveableStateProvider("split") { SplitEditorScreen(
                    uri = splitUri,
                    onBack = ::returnToHome,
                    onPick = { galleryTarget = PickerTarget.SPLIT },
                    onSubmit = { submit(it); returnToHome() },
                ) }

                Screen.GIF -> editorStateHolder.SaveableStateProvider("gif") { GifEditorScreen(
                    photoUris = gifPhotoUris,
                    onBack = ::returnToHome,
                    onPickPhotos = {
                        galleryTarget = PickerTarget.GIF_PHOTOS
                    },
                    onPhotosChanged = { gifPhotoUris = it },
                    onSubmit = { submit(it); returnToHome() },
                ) }

                Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME })
            }
            galleryTarget?.let { target ->
                PhotoGalleryPicker(
                    maxSelection = when (target) {
                        PickerTarget.COLLAGE -> MAX_COLLAGE_PHOTOS
                        PickerTarget.SPLIT -> 1
                        PickerTarget.GIF_PHOTOS -> 100
                        PickerTarget.COMPRESS -> MAX_COMPRESSION_PHOTOS
                    },
                    onDismiss = { galleryTarget = null },
                    compressionTools = target == PickerTarget.COMPRESS,
                    onConfirm = { uris ->
                        when (target) {
                            PickerTarget.COLLAGE -> { collageUris = uris; collageSelectionRevision++ }
                            PickerTarget.COMPRESS -> { compressFolder = null; compressUris.clear(); compressUris.addAll(uris) }
                            PickerTarget.SPLIT -> splitUri = uris.single()
                            PickerTarget.GIF_PHOTOS -> gifPhotoUris = uris
                        }
                        galleryTarget = null
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    onOpen: (Screen) -> Unit,
    onOpenSettings: () -> Unit,
    workInfo: WorkInfo?,
    onCancel: () -> Unit,
    message: String?,
    onDismissMessage: () -> Unit,
    compressionCleanup: @Composable (String) -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("SB PHOTO TOOL", fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.8).sp)
                Text("가볍고, 기기 안에서만 처리하는 사진 도구", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "설정", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ToolCard("콜라주", "최대 15장 · 템플릿", Icons.Outlined.GridOn, Modifier.weight(1f)) { onOpen(Screen.COLLAGE) }
            ToolCard("사진 압축", "여러 장 · 폴더", Icons.Outlined.Compress, Modifier.weight(1f)) { onOpen(Screen.COMPRESS) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ToolCard("사진 분할", "인스타그램 격자", Icons.Outlined.Splitscreen, Modifier.weight(1f)) { onOpen(Screen.SPLIT) }
            ToolCard("GIF · MP4 만들기", "사진 애니메이션", Icons.Outlined.AutoAwesomeMotion, Modifier.weight(1f)) { onOpen(Screen.GIF) }
        }
        WorkStatusCard(workInfo, onCancel, compressionCleanup)
        if (message != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    IconButton(onClick = onDismissMessage) { Text("닫기") }
                }
            }
        }
        Text(
            "압축 후 원본을 compress_original 폴더로 모아 직접 관리할 수 있습니다. 사진은 서버로 전송되지 않습니다.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ToolCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.heightIn(min = 154.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.size(46.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkStatusCard(workInfo: WorkInfo?, onCancel: () -> Unit, compressionCleanup: @Composable (String) -> Unit) {
    if (workInfo == null) return
    val running = workInfo.state == WorkInfo.State.RUNNING || workInfo.state == WorkInfo.State.ENQUEUED
    val current = workInfo.progress.getInt(ToolboxWorker.KEY_CURRENT, 0)
    val total = workInfo.progress.getInt(ToolboxWorker.KEY_TOTAL, 1).coerceAtLeast(1)
    val label = if (running) workInfo.progress.getString(ToolboxWorker.KEY_LABEL) else workInfo.outputData.getString(ToolboxWorker.KEY_MESSAGE)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SaveAlt, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(if (running) "작업 진행 중" else "최근 작업", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (running) OutlinedButton(onClick = onCancel) { Text("취소") }
            }
            if (!label.isNullOrBlank()) Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            val measured = workInfo.outputData.getInt(ToolboxWorker.KEY_MEASURED_FILES, 0)
            if (!running && measured > 0) {
                Text("${measured}장 합계: ${formatBytes(workInfo.outputData.getLong(ToolboxWorker.KEY_INPUT_BYTES, 0))} → ${formatBytes(workInfo.outputData.getLong(ToolboxWorker.KEY_OUTPUT_BYTES, 0))}", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
            if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                workInfo.outputData.getString(ToolboxWorker.KEY_COMPRESSION_JOB_ID)?.takeIf {
                    workInfo.outputData.getInt(ToolboxWorker.KEY_SUCCEEDED, 0) > 0
                }?.let { compressionCleanup(it) }
                if (workInfo.outputData.getBoolean(ToolboxWorker.KEY_COMPRESSION_CLEANUP_UNAVAILABLE, false)) {
                    Text("압축본은 저장했지만 원본 목록을 저장하지 못해 별도 보관 폴더 이동을 제공할 수 없어요.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (running) {
                LinearProgressIndicator(
                    progress = { (current.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}


internal fun loadEditorPreview(context: Context, uri: Uri, surface: String, maxEdge: Int = EDITOR_PREVIEW_MAX_EDGE, maxPixels: Long = maxEdge.toLong() * maxEdge): EditorPreviewState {
    DiagnosticLog.record(context, "preview/$surface", "decode_started")
    return try {
        val bitmap = try {
            decodeEditorPreviewWithImageDecoder(context, uri, maxEdge, maxPixels)
        } catch (memoryError: OutOfMemoryError) {
            DiagnosticLog.recordException(context, "preview/$surface/memory_fallback", memoryError)
            decodeEditorPreviewWithImageDecoder(context, uri, (maxEdge / 2).coerceAtLeast(1), (maxPixels / 4).coerceAtLeast(1))
        }
        DiagnosticLog.record(context, "preview/$surface", "decode_ready=${bitmap.width}x${bitmap.height}")
        EditorPreviewState.Ready(bitmap)
    } catch (error: Exception) {
        DiagnosticLog.recordException(context, "preview/$surface", error)
        EditorPreviewState.Failed
    } catch (error: OutOfMemoryError) {
        DiagnosticLog.recordException(context, "preview/$surface/memory", error)
        EditorPreviewState.Failed
    }
}

private fun decodeEditorPreviewWithImageDecoder(context: Context, uri: Uri, maxEdge: Int, maxPixels: Long): Bitmap =
    decodeStillBitmap(context, uri, maxEdge, maxPixels)

private const val EDITOR_PREVIEW_MAX_EDGE = 768

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var diagnosticText by remember { mutableStateOf(DiagnosticLog.snapshot(context)) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun refreshDiagnostics() {
        diagnosticText = DiagnosticLog.snapshot(context)
    }

    ToolPage("설정", "앱 상태와 오류 진단", onBack) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape(20.dp),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Outlined.BugReport, null, tint = MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("진단 로그", fontWeight = FontWeight.SemiBold)
                    Text(
                        "사진 선택, 미리보기 생성, 앱 종료 오류를 이 기기에만 기록합니다. Android 전체 로그캣은 일반 앱 권한으로 읽을 수 없습니다.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionTitle("오류 분석용 로그")
        Text(
            "오류가 다시 난 뒤 앱을 열어 ‘로그 공유’를 누르면 분석에 필요한 텍스트를 바로 전달할 수 있습니다.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    refreshDiagnostics()
                    statusMessage = "최신 로그로 갱신했어요."
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.Refresh, null)
                Spacer(Modifier.width(6.dp))
                Text("새로고침")
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TITLE, "SB PHOTO TOOL 진단 로그")
                                putExtra(Intent.EXTRA_TEXT, diagnosticText)
                            },
                            "진단 로그 공유",
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.Share, null)
                Spacer(Modifier.width(6.dp))
                Text("로그 공유")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("SB PHOTO TOOL 진단 로그", diagnosticText))
                    statusMessage = "로그를 클립보드에 복사했어요."
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.ContentCopy, null)
                Spacer(Modifier.width(6.dp))
                Text("로그 복사")
            }
            OutlinedButton(
                onClick = {
                    DiagnosticLog.clear(context)
                    refreshDiagnostics()
                    statusMessage = "진단 로그를 지웠어요."
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("로그 지우기")
            }
        }
        statusMessage?.let { message ->
            Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "최근 ${DiagnosticLog.entryCount(context)}개 기록",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
            ) {
                Text(
                    diagnosticText,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolPage(title: String, subtitle: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Text("‹", fontSize = 38.sp, lineHeight = 30.sp) }
            Column {
                Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
}

private suspend fun collectImagesInTree(context: Context, treeUri: Uri, includeChildren: Boolean): List<Uri> = withContext(Dispatchers.IO) {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
    val results = mutableListOf<Uri>()
    val scanContext = currentCoroutineContext()
    fun visit(folder: DocumentFile) {
        folder.listFiles().forEach { file ->
            scanContext.ensureActive()
            when {
                file.isDirectory && includeChildren -> visit(file)
                file.isFile && (file.type?.startsWith("image/") == true || file.name?.matches(Regex(".*\\.(jpe?g|png|webp|heic)$", RegexOption.IGNORE_CASE)) == true) -> {
                    check(results.size < MAX_COMPRESSION_PHOTOS) {
                        "한 번에 최대 ${MAX_COMPRESSION_PHOTOS}장까지 압축할 수 있어요. 사진 선택에서 나누어 선택해 주세요."
                    }
                    results += file.uri
                }
            }
        }
    }
    visit(root)
    results
}

@Composable
private fun ToolboxTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF8AB4FF),
        background = androidx.compose.ui.graphics.Color(0xFF000000),
        surface = androidx.compose.ui.graphics.Color(0xFF151518),
        surfaceContainer = androidx.compose.ui.graphics.Color(0xFF202025),
        surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF18181D),
    ) else lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF0A84FF),
        background = androidx.compose.ui.graphics.Color(0xFFF7F7FA),
        surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
        surfaceContainer = androidx.compose.ui.graphics.Color(0xFFEFEFF4),
        surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
