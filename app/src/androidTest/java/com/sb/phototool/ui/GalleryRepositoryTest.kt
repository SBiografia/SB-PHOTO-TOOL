package com.sb.phototool.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sb.phototool.ui.gallery.GalleryRepository
import com.sb.phototool.ui.gallery.GalleryPhoto
import com.sb.phototool.ui.gallery.GallerySort
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
                resolver.openOutputStream(uri)!!.use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    it.write(ByteArray((index % 5) * 64))
                }
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
            val expectedBySize = (first.photos + second.photos).sortedWith(compareByDescending<GalleryPhoto> { it.sizeBytes }.thenByDescending { it.id })
            assertTrue(expectedBySize.first().sizeBytes > expectedBySize.last().sizeBytes)
            val largestFirst = repository.page(bucket.id, false, null, GallerySort.SIZE_DESC)
            val largestSecond = repository.page(bucket.id, false, largestFirst.photos.last(), GallerySort.SIZE_DESC)
            assertEquals(expectedBySize, largestFirst.photos + largestSecond.photos)
            assertTrue(largestFirst.hasMore)
            assertFalse(largestSecond.hasMore)
            val bulk = repository.page(bucket.id, false, null, GallerySort.SIZE_DESC, pageSize = 100)
            assertEquals(expectedBySize.take(100), bulk.photos)
            assertTrue(bulk.hasMore)
            if (Build.VERSION.SDK_INT >= 30) assertEquals(2, repository.page(bucket.id, true, null).photos.size)
            if (Build.VERSION.SDK_INT >= 30) {
                val favoriteSizes = repository.page(bucket.id, true, null, GallerySort.SIZE_DESC)
                assertEquals(2, favoriteSizes.photos.size)
                assertTrue(favoriteSizes.photos[0].sizeBytes > favoriteSizes.photos[1].sizeBytes)
            }
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
