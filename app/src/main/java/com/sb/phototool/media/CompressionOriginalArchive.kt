package com.sb.phototool.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.sb.phototool.data.CompressionOriginalStore
import com.sb.phototool.data.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val COMPRESSION_ORIGINAL_PATH = "Pictures/SB PHOTO TOOL/compress_original/"

internal data class CompressionOriginalTargets(
    val originalsByMediaUri: Map<Uri, Set<String>> = emptyMap(),
    val archivedOriginals: Set<String> = emptySet(),
    val unavailableCount: Int = 0,
) {
    val mediaUris: List<Uri> get() = originalsByMediaUri.keys.toList()
}

internal data class CompressionOriginalMoveResult(
    val movedCount: Int,
    val failedCount: Int,
    val unavailableCount: Int,
)

internal class CompressionOriginalArchive(private val context: Context) {
    private val resolver = context.contentResolver
    private val store = CompressionOriginalStore(context)

    suspend fun targets(jobId: String): CompressionOriginalTargets = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 30) return@withContext CompressionOriginalTargets()
        val media = linkedMapOf<Uri, MutableSet<String>>()
        val archived = mutableSetOf<String>()
        var unavailable = 0
        for (original in store.load(jobId)) {
            currentCoroutineContext().ensureActive()
            try {
                val uri = mediaUri(original)
                val state = uri?.let(::readState)
                when {
                    state == null || !state.visible -> unavailable++
                    else -> {
                        readableSize(state.uri)
                        if (state.path == COMPRESSION_ORIGINAL_PATH) archived += original
                        else media.getOrPut(state.uri) { linkedSetOf() }.add(original)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DiagnosticLog.recordException(context, "compress/archive_target", failure)
                unavailable++
            }
        }
        CompressionOriginalTargets(media, archived, unavailable)
    }

    suspend fun move(jobId: String): CompressionOriginalMoveResult = withContext(Dispatchers.IO) {
        moveLock.withLock {
            val before = targets(jobId)
            val confirmedOriginals = before.archivedOriginals.toMutableSet()
            var moved = 0
            var failed = 0
            for ((uri, originals) in before.originalsByMediaUri) {
                currentCoroutineContext().ensureActive()
                try {
                    val current = readState(uri)
                    if (current == null || !current.visible) {
                        failed++
                        continue
                    }
                    val originalSize = readableSize(current.uri)
                    if (current.path != COMPRESSION_ORIGINAL_PATH) {
                        // MediaProvider moves the existing file and chooses a unique name if needed.
                        // Keeping this on the same volume preserves the original bytes and EXIF.
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, COMPRESSION_ORIGINAL_PATH)
                        }
                        check(resolver.update(current.uri, values, null, null) == 1)
                    }
                    val confirmed = readState(current.uri)
                    check(confirmed?.visible == true && confirmed.path == COMPRESSION_ORIGINAL_PATH)
                    val movedSize = readableSize(current.uri)
                    check(originalSize < 0 || movedSize == originalSize)
                    confirmedOriginals.addAll(originals)
                    moved++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    DiagnosticLog.recordException(context, "compress/archive_move", failure)
                    failed++
                }
            }
            // A retry recognizes files moved before an interruption. Failed items remain pending.
            store.removeConfirmed(jobId, confirmedOriginals)
            DiagnosticLog.record(context, "compress/archive_result",
                "moved=$moved; failed=$failed; unavailable=${before.unavailableCount}")
            CompressionOriginalMoveResult(moved, failed, before.unavailableCount)
        }
    }

    private fun mediaUri(original: String): Uri? {
        val source = Uri.parse(original)
        val uri = when {
            source.scheme == "content" && source.authority == MediaStore.AUTHORITY -> source
            DocumentsContract.isDocumentUri(context, source) -> MediaStore.getMediaUri(context, source)
            else -> null
        } ?: return null
        // Picker/cloud URIs cannot be used as writable MediaStore item URIs.
        val segments = uri.pathSegments
        val imageItem = segments.size == 4 && segments[1] == "images" && segments[2] == "media"
        val fileItem = segments.size == 3 && segments[1] == "file"
        return uri.takeIf { it.authority == MediaStore.AUTHORITY &&
            (imageItem || fileItem) && it.lastPathSegment?.toLongOrNull() != null }
    }

    private fun readState(uri: Uri): OriginalState? {
        if (Build.VERSION.SDK_INT < 30) return null
        val query = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.MIME_TYPE)
        return resolver.query(uri, projection, query, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val volume = cursor.getString(1) ?: return@use null
            if (volume == MediaStore.VOLUME_INTERNAL || cursor.getString(5)?.startsWith("image/") != true) return@use null
            OriginalState(
                ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), cursor.getLong(0)),
                cursor.getString(2).orEmpty().trimEnd('/') + "/",
                cursor.getInt(3) == 0 && cursor.getInt(4) == 0,
            )
        }
    }

    private fun readableSize(uri: Uri): Long = resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        ?: error("원본 파일을 읽을 수 없어요.")

    private data class OriginalState(val uri: Uri, val path: String, val visible: Boolean)
    private companion object { val moveLock = Mutex() }
}
