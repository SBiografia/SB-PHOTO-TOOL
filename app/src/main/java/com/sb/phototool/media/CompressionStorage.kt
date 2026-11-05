package com.sb.phototool.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resume

internal data class CompressionSource(
    val file: File,
    val name: String,
    val metadata: ImageMetadata,
    val modified: FileTime?,
    val dateTaken: Long?,
    val warnings: List<String>,
)

internal data class CompressionPublication(val uri: Uri, val warnings: List<String>)

/** Only this publisher applies source timestamps; collage/split/GIF keep their own policies. */
internal class CompressionStorage(private val context: Context) {
    private val resolver = context.contentResolver

    fun stage(source: Uri, name: String, keepMetadata: Boolean): CompressionSource {
        val warnings = mutableListOf<String>()
        val media = if (source.authority == MediaStore.AUTHORITY) source else if (source.scheme == "content") {
            try { MediaStore.getMediaUri(context, source) } catch (_: Exception) { null }
        } else null
        val readable = if (keepMetadata && media != null) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                throw UserVisibleException("GPS를 포함한 원본 촬영 정보를 읽으려면 위치 메타데이터 권한을 허용해 주세요.")
            }
            MediaStore.setRequireOriginal(media)
        } else {
            if (keepMetadata && source.scheme != "file") {
                throw UserVisibleException("이 공급자는 원본 메타데이터 접근을 확인할 수 없어요. 사진 선택에서 원본을 다시 선택해 주세요.")
            }
            source
        }
        val sourceDate = media?.let {
            try { queryLong(it, MediaStore.MediaColumns.DATE_TAKEN)?.takeIf { value -> value > 0 } }
            catch (_: Exception) { warnings += "원본 미디어 DB 촬영일을 읽지 못했어요."; null }
        }
        val file = File.createTempFile("compression_source_", ".tmp", context.cacheDir)
        try {
            var modified: FileTime? = null
            resolver.openFileDescriptor(readable, "r")?.use { descriptor ->
                try {
                    val stat = Os.fstat(descriptor.fileDescriptor)
                    if (OsConstants.S_ISREG(stat.st_mode)) modified = FileTime.from(Instant.ofEpochSecond(stat.st_mtim.tv_sec, stat.st_mtim.tv_nsec))
                } catch (_: Exception) { /* A provider may expose a pipe rather than a real file. */ }
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            } ?: throw UserVisibleException("원본 사진을 열 수 없어요.")
            val metadata = metadataOperation { ImageMetadata.read(file) }
            if (modified == null) warnings += "원본의 실제 파일 수정일을 확인하지 못해 수정일을 보존할 수 없어요."
            val taken = if (keepMetadata) sourceDate ?: metadataOperation { metadata.captureTimeMillis() } else null
            if (keepMetadata && taken == null && metadata.originalDate != null) {
                warnings += "EXIF 촬영일의 시간대가 없거나 유효하지 않아 미디어 DB 촬영일을 확정할 수 없어요."
            }
            return CompressionSource(file, name, metadata, modified, taken, warnings)
        } catch (failure: Throwable) { file.delete(); throw failure }
    }

    suspend fun publish(file: File, source: CompressionSource, keepMetadata: Boolean): CompressionPublication {
        val warnings = source.warnings.toMutableList()
        val mime = when (source.metadata.format) { "jpeg" -> "image/jpeg"; "png" -> "image/png"; else -> "image/webp" }
        validateName(source.name, source.metadata.format)
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        fun insert(path: String): Uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (keepMetadata) source.dateTaken?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
        }) ?: throw UserVisibleException("압축본 저장 위치를 만들지 못했어요.")

        var uri = insert("Pictures/SB PHOTO TOOL/Compress/")
        try {
            // Pending rows can keep the requested display name while the allocated path is already uniquified.
            fun matchesName(): Boolean = queryString(uri, MediaStore.MediaColumns.DISPLAY_NAME) == source.name &&
                queryString(uri, MediaStore.MediaColumns.DATA)?.let {
                    File(it).name.replace(Regex("^\\.pending-\\d+-"), "") == source.name
                } == true
            fun targetExists(): Boolean {
                val path = queryString(uri, MediaStore.MediaColumns.DATA) ?: return true
                if (File(File(path).parentFile, source.name).exists()) return true
                val relative = queryString(uri, MediaStore.MediaColumns.RELATIVE_PATH) ?: return true
                return resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns._ID} != ?",
                    arrayOf(relative, source.name, android.content.ContentUris.parseId(uri).toString()), null)?.use { it.moveToFirst() } ?: true
            }
            if (!matchesName() || targetExists()) {
                check(resolver.delete(uri, null, null) == 1)
                uri = insert("Pictures/SB PHOTO TOOL/Compress/${UUID.randomUUID()}/")
            }
            check(matchesName()) { "원본 파일명 그대로 저장할 수 없어요." }
            resolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw UserVisibleException("압축본을 기록하지 못했어요.")
            val hash = file.inputStream().use(::digest)
            check(resolver.openInputStream(uri)?.use(::digest)?.contentEquals(hash) == true) { "최종 파일 내용 검증에 실패했어요." }
            val modified = source.modified
            if (modified != null) setModified(uri, modified)
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                if (keepMetadata) source.dateTaken?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
            }, null, null) == 1) { "압축본 공개를 완료하지 못했어요." }

            val path = queryString(uri, MediaStore.MediaColumns.DATA)
            val scanned = if (path != null) scan(path, mime) else false
            if (!scanned) warnings += "미디어 정보 갱신 완료를 확인하지 못했어요. 갤러리 촬영일을 확인해 주세요."
            if (modified != null && modifiedTime(uri) != modified) {
                if (!setModified(uri, modified) || !scan(path, mime) || modifiedTime(uri) != modified) {
                    warnings += "미디어 갱신 후 실제 파일 수정일이 원본과 달라졌어요."
                }
            }
            if (modified != null && modifiedTime(uri) == modified) {
                if (queryLong(uri, MediaStore.MediaColumns.DATE_MODIFIED) != modified.toMillis() / 1000) warnings += "실제 수정일은 보존했지만 미디어 DB 수정일 갱신을 확인하지 못했어요."
            }
            if (keepMetadata && source.dateTaken != null && queryLong(uri, MediaStore.MediaColumns.DATE_TAKEN) != source.dateTaken) {
                // Some providers permit app-owned date updates; never claim success without reading back.
                try { resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, source.dateTaken) }, null, null) }
                catch (_: Exception) { }
                if (queryLong(uri, MediaStore.MediaColumns.DATE_TAKEN) != source.dateTaken) warnings += "Android 미디어 DB 촬영일을 원본과 같게 유지하지 못했어요."
            }
            check(queryString(uri, MediaStore.MediaColumns.DISPLAY_NAME) == source.name) { "저장 중 파일명이 변경됐어요: ${queryString(uri, MediaStore.MediaColumns.DISPLAY_NAME)} (원본 ${source.name})" }
            check(resolver.openInputStream(uri)?.use(::digest)?.contentEquals(hash) == true) { "미디어 갱신 후 파일 내용이 변경됐어요." }
            return CompressionPublication(uri, warnings.distinct())
        } catch (failure: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            throw UserVisibleException("압축본 저장·메타데이터 검증 실패: ${failure.message ?: "원인을 확인하지 못했어요."}")
        }
    }

    private suspend fun scan(path: String?, mime: String): Boolean {
        if (path == null) return false
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { continuation ->
                MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mime)) { _, result ->
                    if (continuation.isActive) continuation.resume(result != null)
                }
            }
        } ?: false
    }

    private fun modifiedTime(uri: Uri): FileTime? = try {
        resolver.openFileDescriptor(uri, "r")?.use {
            val stat = Os.fstat(it.fileDescriptor)
            FileTime.from(Instant.ofEpochSecond(stat.st_mtim.tv_sec, stat.st_mtim.tv_nsec))
        }
    } catch (_: Exception) { null }

    private fun setModified(uri: Uri, time: FileTime): Boolean {
        try {
            resolver.openFileDescriptor(uri, "rw")?.use {
                Files.setLastModifiedTime(Paths.get("/proc/self/fd/${it.fd}"), time)
            }
            if (modifiedTime(uri) == time) return true
        } catch (_: Exception) { }
        try {
            val path = queryString(uri, MediaStore.MediaColumns.DATA) ?: return false
            Files.setLastModifiedTime(Paths.get(path), time)
            return modifiedTime(uri) == time
        } catch (_: Exception) { return false }
    }

    private fun queryLong(uri: Uri, column: String): Long? = resolver.query(uri, arrayOf(column), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
    }

    private fun queryString(uri: Uri, column: String): String? = resolver.query(uri, arrayOf(column), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
    }

    companion object {
        fun validateName(name: String, format: String) {
            val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            check(name.isNotBlank() && name.none { it in "/\\\u0000" } && when (format) {
                "jpeg" -> extension in setOf("jpg", "jpeg")
                "png" -> extension == "png"
                "webp" -> extension == "webp"
                else -> false
            }) { "원본 파일 확장자와 실제 형식이 달라 이름을 그대로 사용할 수 없어요." }
        }

        fun <T> metadataOperation(block: () -> T): T = try { block() }
        catch (failure: Exception) { throw UserVisibleException("메타데이터 보존 실패: ${failure.message ?: "정보를 읽거나 기록하지 못했어요."}") }

        private fun digest(input: java.io.InputStream): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            return digest.digest()
        }
    }
}
