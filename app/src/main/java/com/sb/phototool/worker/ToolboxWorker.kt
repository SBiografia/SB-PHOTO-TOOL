package com.sb.phototool.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sb.phototool.R
import com.sb.phototool.ToolboxApplication
import com.sb.phototool.data.JobStore
import com.sb.phototool.data.CompressionOriginalStore
import com.sb.phototool.data.DiagnosticLog
import com.sb.phototool.media.MediaProcessor
import com.sb.phototool.model.CompressJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ToolboxWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo("작업 준비 중", 0, 1)

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val store = JobStore(applicationContext)
        val job = store.load(jobId) ?: return Result.failure(workDataOf(KEY_MESSAGE to "작업 정보를 찾을 수 없어요."))
        return try {
            DiagnosticLog.record(applicationContext, "worker/${job.type}", "started")
            setForeground(createForegroundInfo("작업 준비 중", 0, 1))
            val summary = MediaProcessor(applicationContext).execute(job) { current, total, label ->
                val safeTotal = total.coerceAtLeast(1)
                setProgress(workDataOf(KEY_CURRENT to current, KEY_TOTAL to safeTotal, KEY_LABEL to label))
                setForeground(createForegroundInfo(label, current, safeTotal))
            }
            var cleanupSaved = false
            if (job is CompressJob) {
                try {
                    cleanupSaved = CompressionOriginalStore(applicationContext).complete(jobId, summary.compressedOriginalUris)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { DiagnosticLog.recordException(applicationContext, "compress/archive_save", failure) }
            }
            store.remove(jobId)
            DiagnosticLog.record(applicationContext, "worker/${job.type}", "completed; saved=${summary.succeeded}; failed=${summary.failed}")
            Result.success(
                workDataOf(
                    KEY_MESSAGE to summary.userMessage,
                    KEY_SUCCEEDED to summary.succeeded,
                    KEY_FAILED to summary.failed,
                    KEY_INPUT_BYTES to summary.inputBytes,
                    KEY_OUTPUT_BYTES to summary.outputBytes,
                    KEY_MEASURED_FILES to summary.measuredFiles,
                    KEY_COMPRESSION_JOB_ID to if (cleanupSaved) jobId else null,
                    KEY_COMPRESSION_CLEANUP_UNAVAILABLE to (job is CompressJob && !cleanupSaved),
                ),
            )
        } catch (cancelled: CancellationException) {
            DiagnosticLog.record(applicationContext, "worker/${job.type}", "cancelled")
            withContext(NonCancellable) {
                store.remove(jobId)
            }
            throw cancelled
        } catch (error: Exception) {
            DiagnosticLog.recordException(applicationContext, "worker/${job.type}", error)
            store.remove(jobId)
            Result.failure(workDataOf(KEY_MESSAGE to (error.message ?: "작업하지 못했어요.")))
        }
    }

    private fun createForegroundInfo(label: String, current: Int, total: Int): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, ToolboxApplication.WORK_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(applicationContext.getString(R.string.work_notification_title))
            .setContentText(label)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, current.coerceIn(0, total), false)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_LABEL = "label"
        const val KEY_MESSAGE = "message"
        const val KEY_SUCCEEDED = "succeeded"
        const val KEY_FAILED = "failed"
        const val KEY_INPUT_BYTES = "input_bytes"
        const val KEY_OUTPUT_BYTES = "output_bytes"
        const val KEY_MEASURED_FILES = "measured_files"
        const val KEY_COMPRESSION_JOB_ID = "compression_job_id"
        const val KEY_COMPRESSION_CLEANUP_UNAVAILABLE = "compression_cleanup_unavailable"
        private const val NOTIFICATION_ID = 2109
    }
}
