package com.sb.phototool.ui.gallery

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import com.sb.phototool.data.DiagnosticLog
import com.sb.phototool.media.decodeStillBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal enum class PhotoAccess { NONE, LIMITED, FULL }

internal fun photoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

internal fun photoAccess(context: Context): PhotoAccess {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return when {
        granted(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccess.FULL
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.LIMITED
        else -> PhotoAccess.NONE
    }
}

internal enum class GallerySort(val label: String) {
    RECENT("최근 저장순"), SIZE_DESC("용량 큰 순")
}

internal data class GalleryPhoto(val id: Long, val uri: String, val name: String, val addedSeconds: Long, val sizeBytes: Long = 0)
internal data class GalleryAlbum(val id: String, val name: String, val count: Int)
internal data class GalleryPage(val photos: List<GalleryPhoto>, val hasMore: Boolean)

internal class GalleryRepository(private val context: Context) {
    private val resolver = context.contentResolver
    private val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val decoders = Semaphore(2)
    private val thumbnails = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).coerceAtMost(16L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun clearThumbnails() = thumbnails.evictAll()

    private fun visibleSelection(): String = "${MediaStore.Images.Media.SIZE} > 0 AND ${MediaStore.Images.Media.IS_PENDING} = 0" +
        if (Build.VERSION.SDK_INT >= 30) " AND ${MediaStore.Images.Media.IS_TRASHED} = 0" else ""

    suspend fun albums(): List<GalleryAlbum> = withContext(Dispatchers.IO) {
        val counts = linkedMapOf<String, GalleryAlbum>()
        resolver.query(collection, arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME), visibleSelection(), null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val id = cursor.getString(0) ?: continue
                val previous = counts[id]
                counts[id] = GalleryAlbum(id, cursor.getString(1) ?: "이름 없는 앨범", (previous?.count ?: 0) + 1)
            }
        }
        counts.values.sortedBy { it.name.lowercase() }
    }

    suspend fun page(albumId: String?, favorites: Boolean, after: GalleryPhoto?, sort: GallerySort = GallerySort.RECENT,
                     pageSize: Int = PAGE_SIZE): GalleryPage = withContext(Dispatchers.IO) {
        require(pageSize in 1..PAGE_SIZE)
        val predicates = mutableListOf(visibleSelection())
        val args = mutableListOf<String>()
        if (albumId != null) {
            predicates += "${MediaStore.Images.Media.BUCKET_ID} = ?"
            args += albumId
        }
        if (favorites && Build.VERSION.SDK_INT >= 30) predicates += "${MediaStore.Images.Media.IS_FAVORITE} = 1"
        val sortColumn = if (sort == GallerySort.SIZE_DESC) MediaStore.Images.Media.SIZE else MediaStore.Images.Media.DATE_ADDED
        if (after != null) {
            val lastValue = if (sort == GallerySort.SIZE_DESC) after.sizeBytes else after.addedSeconds
            predicates += "($sortColumn < ? OR ($sortColumn = ? AND ${MediaStore.Images.Media._ID} < ?))"
            args += listOf(lastValue.toString(), lastValue.toString(), after.id.toString())
        }
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, predicates.joinToString(" AND "))
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "$sortColumn DESC, ${MediaStore.Images.Media._ID} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, pageSize + 1)
        }
        val photos = mutableListOf<GalleryPhoto>()
        resolver.query(collection, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.SIZE), query, null)?.use { cursor ->
            while (photos.size <= pageSize && cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val id = cursor.getLong(0)
                photos += GalleryPhoto(id, ContentUris.withAppendedId(collection, id).toString(), cursor.getString(1) ?: "사진", cursor.getLong(2), cursor.getLong(3))
            }
        } ?: error("사진 목록을 읽지 못했어요. 다시 시도해 주세요.")
        GalleryPage(photos.take(pageSize), photos.size > pageSize)
    }

    suspend fun thumbnail(photo: GalleryPhoto): Bitmap? {
        thumbnails.get(photo.uri)?.let { return it }
        return decoders.withPermit {
            withContext(Dispatchers.IO) {
                thumbnails.get(photo.uri) ?: try {
                    val uri = Uri.parse(photo.uri)
                    val bitmap = try {
                        resolver.loadThumbnail(uri, Size(384, 384), null)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        decodeStillBitmap(context, uri, 384, 384L * 384)
                    }
                    thumbnails.put(photo.uri, bitmap)
                    bitmap
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } catch (_: OutOfMemoryError) {
                    thumbnails.evictAll()
                    null
                }
            }
        }
    }

    suspend fun unavailable(photos: List<GalleryPhoto>): Set<String> = withContext(Dispatchers.IO) {
        photos.filter { photo ->
            currentCoroutineContext().ensureActive()
            try {
                resolver.openFileDescriptor(Uri.parse(photo.uri), "r")?.use { false } ?: true
            } catch (error: Exception) {
                DiagnosticLog.recordException(context, "gallery/read", error)
                true
            }
        }.mapTo(mutableSetOf()) { it.uri }
    }

    private companion object { const val PAGE_SIZE = 120 }
}
