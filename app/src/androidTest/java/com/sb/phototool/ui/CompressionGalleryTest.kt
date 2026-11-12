package com.sb.phototool.ui

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sb.phototool.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CompressionGalleryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val photos = mutableListOf<Uri>()
    private val album = "CompressionSelectionQA-${System.nanoTime()}"

    @Before fun fixtures() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            repeat(123) { index ->
                val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "compression-$index.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$album")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }))
                photos += uri
                context.contentResolver.openOutputStream(uri)!!.use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    it.write(ByteArray((index % 5) * 64))
                }
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                    if (Build.VERSION.SDK_INT >= 30) put(MediaStore.Images.Media.IS_FAVORITE, if (index < 2) 1 else 0)
                }, null, null)
            }
        } finally { bitmap.recycle() }
    }

    @After fun cleanup() { photos.forEach { context.contentResolver.delete(it, null, null) } }

    private fun openCompressionAlbum() {
        compose.onNodeWithText("사진 압축").performClick()
        compose.onAllNodesWithText("사진 선택").onFirst().performClick()
        compose.onNodeWithTag("gallery_albums").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText(album).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(album).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("gallery_photo_${ContentUris.parseId(photos.last())}").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun chooseSort(name: String) {
        compose.onNodeWithTag("gallery_sort").performClick()
        compose.onNodeWithTag("gallery_sort_$name").performClick()
    }

    private fun choose(index: Int) {
        compose.onNodeWithTag("gallery_grid").performScrollToKey(photos[index].toString())
        compose.onNodeWithTag("gallery_photo_${ContentUris.parseId(photos[index])}").performClick()
    }

    private fun screenshot(name: String) {
        val folder = File(context.getExternalFilesDir(null), "compression-qa").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose.onNodeWithTag("photo_gallery").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun sizeSortAndSelectAllLimit_surviveRotationAndConfirm() {
        openCompressionAlbum()
        chooseSort("SIZE_DESC")
        val largest = "gallery_photo_${ContentUris.parseId(photos[119])}"
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(largest).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("gallery_select_all").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("확인 (100)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(largest).assertContentDescriptionEquals("compression-119.jpg, 1번째 선택")
        // This small photo falls just beyond the first 100, despite being newer than many large photos.
        choose(110)
        compose.onNodeWithTag("gallery_photo_${ContentUris.parseId(photos[110])}").assertIsNotSelected()
        compose.onNodeWithTag("gallery_message").assertTextContains("최대 100장", substring = true)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("확인 (100)").assertExists()
        compose.onNodeWithTag("gallery_sort").assertTextContains("용량 큰 순")
        compose.onNodeWithTag("gallery_clear_all").assertIsEnabled().assertIsDisplayed()
        screenshot("clear-all-size-sort-100")
        compose.onNodeWithTag("gallery_confirm").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("100장 선택 · 1번째 사진").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tool-save").assertIsEnabled()
    }

    @Test fun selectAllRespectsFavoritesAndAlbum_andClearAllowsRecentSelection() {
        openCompressionAlbum()
        compose.onNodeWithTag("gallery_clear_all").assertIsNotEnabled().assertIsDisplayed()
        choose(122)
        compose.onNodeWithTag("gallery_clear_all").assertIsEnabled()
        choose(122)
        compose.onNodeWithTag("gallery_clear_all").assertIsNotEnabled()
        choose(122)
        compose.onNodeWithText("즐겨찾기", substring = false).performClick()
        chooseSort("SIZE_DESC")
        compose.onNodeWithTag("gallery_select_all").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("확인 (3)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("gallery_photo_${ContentUris.parseId(photos[1])}").assertContentDescriptionEquals("compression-1.jpg, 2번째 선택")
        compose.onNodeWithTag("gallery_clear_all").assertIsEnabled().performClick()
        compose.onNodeWithTag("gallery_clear_all").assertIsNotEnabled()
        compose.onNodeWithTag("gallery_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("gallery_selection_strip").assertDoesNotExist()
        compose.onNodeWithText("사진", substring = false).performClick()
        chooseSort("RECENT")
        compose.onNodeWithTag("gallery_select_all").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("확인 (100)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("gallery_photo_${ContentUris.parseId(photos.last())}").assertContentDescriptionEquals("compression-122.jpg, 1번째 선택")
        compose.onNodeWithTag("gallery_select_all").performClick()
        compose.onNodeWithText("확인 (100)").assertExists()
        compose.onNodeWithTag("gallery_clear_all").assertIsEnabled().performClick()
        compose.onNodeWithText("확인 (0)").assertExists()
        compose.onNodeWithTag("gallery_clear_all").assertIsNotEnabled()
        choose(122)
        compose.onNodeWithTag("gallery_clear_all").assertIsEnabled()
        screenshot("clear-all-single")
        compose.onNodeWithTag("gallery_clear_all").performClick()
        compose.onNodeWithTag("gallery_clear_all").assertIsNotEnabled()
        compose.onNodeWithTag("gallery_confirm").assertIsNotEnabled()
        screenshot("clear-all-empty")
    }
}
