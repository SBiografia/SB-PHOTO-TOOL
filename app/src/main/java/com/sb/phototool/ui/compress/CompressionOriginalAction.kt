package com.sb.phototool.ui.compress

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sb.phototool.data.DiagnosticLog
import com.sb.phototool.media.CompressionOriginalArchive
import com.sb.phototool.media.CompressionOriginalTargets
import kotlinx.coroutines.CancellationException

@Composable
internal fun CompressionOriginalAction(jobId: String, busy: Boolean, revision: Int, message: String?, onRequest: (String) -> Unit) {
    val context = LocalContext.current
    val archive = remember(context) { CompressionOriginalArchive(context.applicationContext) }
    var retry by remember(jobId) { mutableIntStateOf(0) }
    val state by produceState<Result<CompressionOriginalTargets>?>(null, jobId, revision, retry) {
        value = null
        value = try { Result.success(archive.targets(jobId)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            DiagnosticLog.recordException(context, "compress/archive_load", failure)
            Result.failure(failure)
        }
    }
    Column(Modifier.fillMaxWidth().testTag("compression-original-action"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            Build.VERSION.SDK_INT < 30 -> Text("원본 별도 보관 폴더 이동은 Android 11 이상에서 지원해요.", style = MaterialTheme.typography.bodySmall)
            state == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            state?.isFailure == true -> TextButton(onClick = { retry++ }, enabled = !busy) { Text("원본 목록을 읽지 못했어요 · 다시 시도") }
            else -> {
                val targets = state!!.getOrThrow()
                if (targets.mediaUris.isNotEmpty()) {
                    OutlinedButton(onClick = { onRequest(jobId) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("compression-archive-originals")) {
                        Text(if (busy) "원본 폴더 이동 중…" else "원본 ${targets.mediaUris.size}장 별도 보관 폴더로 이동")
                    }
                    Text("압축에 성공한 원본을 Pictures/SB PHOTO TOOL/compress_original로 모읍니다. 파일 수정 확인창에서 승인해 주세요.", style = MaterialTheme.typography.bodySmall)
                } else if (message == null) {
                    Text(if (targets.archivedOriginals.isNotEmpty()) "원본이 이미 compress_original 폴더에 있어요." else "별도 보관 폴더로 이동할 원본이 없어요.", style = MaterialTheme.typography.bodySmall)
                }
                if (targets.unavailableCount > 0) Text("${targets.unavailableCount}장은 접근할 수 없거나 폴더 이동을 지원하지 않아 제외했어요.", style = MaterialTheme.typography.bodySmall)
            }
        }
        message?.let { Text(it, Modifier.testTag("compression-archive-message"), style = MaterialTheme.typography.bodySmall) }
    }
}
