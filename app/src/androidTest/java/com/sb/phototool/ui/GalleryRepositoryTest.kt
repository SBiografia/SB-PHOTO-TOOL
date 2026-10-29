package com.sb.phototool.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sb.phototool.ui.gallery.GalleryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryRepositoryTest {
    @Test fun paginationAlbumAndFavoritesFilters_doNotDuplicateOrDropPhotos() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val inserted = mutableListOf<Uri>()
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val album = "GalleryPageQA-${System.nanoTime()}"
        try {
            repeat(123) { index ->
                val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "page-$index.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$album")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }))
                inserted += uri
                resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                    if (Build.VERSION.SDK_INT >= 30) put(MediaStore.Images.Media.IS_FAVORITE, if (index < 2) 1 else 0)
                }, null, null)
            }
            val repository = GalleryRepository(context)
            val bucket = repository.albums().single { it.name == album }
            assertEquals(123, bucket.count)
            val first = repository.page(bucket.id, false, null)
            assertEquals(120, first.photos.size)
            assertTrue(first.hasMore)
            val second = repository.page(bucket.id, false, first.photos.last())
            assertEquals(3, second.photos.size)
            assertFalse(second.hasMore)
            assertEquals(inserted.map(Uri::toString).toSet(), (first.photos + second.photos).map { it.uri }.toSet())
            if (Build.VERSION.SDK_INT >= 30) assertEquals(2, repository.page(bucket.id, true, null).photos.size)
            val removed = first.photos.first()
            resolver.delete(Uri.parse(removed.uri), null, null)
            inserted.remove(Uri.parse(removed.uri))
            assertEquals(setOf(removed.uri), repository.unavailable(first.photos.take(2)))
        } finally {
            bitmap.recycle()
            inserted.forEach { resolver.delete(it, null, null) }
        }
    }
}
