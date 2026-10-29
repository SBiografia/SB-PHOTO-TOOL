package com.sb.phototool

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CollageNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val photos = mutableListOf<Uri>()
    private val album = "GalleryNavigationQA"

    @Before fun fixtures() {
        val permission = if (Build.VERSION.SDK_INT >= 33) "READ_MEDIA_IMAGES" else "READ_EXTERNAL_STORAGE"
        assertEquals("Grant photo access before starting instrumentation (pm grant).", PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, "android.permission.$permission"))
        repeat(10) { index ->
            val bitmap = Bitmap.createBitmap(1800, 1200, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(60 + index * 16, 110 + index * 7, 150))
            val paint = Paint().apply { color = Color.WHITE; textSize = 240f }
            canvas.drawText("${index + 1}", 700f, 640f, paint)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "gallery-qa-$index.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$album")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            photos += uri
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
    }

    @After fun cleanup() { photos.forEach { context.contentResolver.delete(it, null, null) } }

    private fun openAlbum() {
        compose.onNodeWithTag("gallery_albums").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(album).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(album).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("gallery_photo_${ContentUris.parseId(photos.last())}").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun choose(uri: Uri) {
        compose.onNodeWithTag("gallery_grid").performScrollToKey(uri.toString())
        compose.onNodeWithTag("gallery_photo_${ContentUris.parseId(uri)}").performClick()
    }
    private fun ready() = compose.waitUntil(20_000) { compose.onAllNodes(hasText("저장") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    private fun screenshot(name: String, gallery: Boolean = false) {
        val folder = File(context.getExternalFilesDir(null), "gallery-qa").apply { mkdirs() }
        val node = if (gallery) compose.onNodeWithTag("photo_gallery") else compose.onRoot()
        File(folder, "$name.png").outputStream().use { node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun ninePhotoLimit_cancelReselection_rotationAndHomeReset() {
        compose.onNodeWithText("콜라주").performClick()
        compose.onNodeWithText("사진 선택 · 최대 9장").performClick()
        openAlbum()
        photos.take(9).forEach(::choose)
        choose(photos.last())
        compose.onNodeWithText("확인 (9)").assertExists()
        compose.onNodeWithTag("gallery_message").assertTextContains("최대 9장", substring = true)
        compose.onNodeWithTag("gallery_photo_${ContentUris.parseId(photos.last())}").assertIsNotSelected()
        screenshot("01-gallery-nine", true)
        compose.onNodeWithTag("gallery_confirm").performClick()
        ready()
        compose.onNodeWithText("9 / 9장").assertExists()
        screenshot("02-high-quality-collage")
        compose.onNodeWithText("다시 선택").performClick()
        openAlbum(); choose(photos.last())
        compose.onNodeWithContentDescription("사진 선택 취소").performClick()
        compose.onNodeWithText("9 / 9장").assertExists()
        compose.activityRule.scenario.recreate()
        ready()
        compose.onNodeWithText("9 / 9장").assertExists()
        compose.onNodeWithContentDescription("콜라주 나가기").performClick()
        compose.onNodeWithText("콜라주").performClick()
        compose.onNodeWithText("사진 선택 · 최대 9장").assertIsDisplayed()
        compose.onNodeWithText("저장").assertIsNotEnabled()
    }

    @Test fun dragSelection_survivesPickerRotation_andTileAddReplaceUseOwnGallery() {
        compose.onNodeWithText("콜라주").performClick()
        compose.onNodeWithText("사진 선택 · 최대 9장").performClick()
        openAlbum()
        val first = compose.onNodeWithTag("gallery_photo_${ContentUris.parseId(photos.last())}").fetchSemanticsNode().boundsInRoot.center
        val third = compose.onNodeWithTag("gallery_photo_${ContentUris.parseId(photos[7])}").fetchSemanticsNode().boundsInRoot.center
        val origin = compose.onNodeWithTag("gallery_grid").fetchSemanticsNode().boundsInRoot.topLeft
        compose.onNodeWithTag("gallery_grid").performTouchInput {
            down(first - origin); advanceEventTime(700)
            moveTo(third - origin, delayMillis = 300); up()
        }
        compose.onNodeWithText("확인 (3)").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("확인 (3)").assertExists()
        compose.onNodeWithTag("gallery_confirm").performClick()
        ready()
        compose.onNodeWithTag("collage-photo-0").performClick()
        compose.onNodeWithText("교체").performClick()
        openAlbum(); choose(photos.first())
        compose.onNodeWithTag("gallery_confirm").performClick()
        ready()
        compose.onNodeWithText("3 / 9장").assertExists()
        compose.onNodeWithText("완료").performClick()
        compose.onNodeWithContentDescription("사진 추가").performClick()
        openAlbum(); choose(photos[1])
        compose.onNodeWithTag("gallery_confirm").performClick()
        ready()
        compose.onNodeWithText("4 / 9장").assertExists()
    }

    @Test fun splitUsesSingleSelection_andResetOnHome() {
        compose.onNodeWithText("사진 분할").performClick()
        compose.onAllNodesWithText("사진 선택").onFirst().performClick()
        openAlbum(); choose(photos[0]); choose(photos[1])
        compose.onNodeWithText("확인 (1)").assertExists()
        compose.onNodeWithTag("gallery_confirm").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("tool-save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        screenshot("03-high-quality-grid")
        compose.onNodeWithContentDescription("홈으로").performClick()
        compose.onNodeWithText("사진 분할").performClick()
        compose.onNodeWithTag("tool-save").assertIsNotEnabled()
    }

    @Test fun compressionAndGifUseSharedGallery_andCancelKeepsEmptySelection() {
        compose.onNodeWithText("사진 압축").performClick()
        compose.onAllNodesWithText("사진 선택").onFirst().performClick()
        compose.onNodeWithTag("gallery_confirm").assertIsNotEnabled()
        compose.onNodeWithContentDescription("사진 선택 취소").performClick()
        compose.onNodeWithTag("tool-save").assertIsNotEnabled()
        compose.onAllNodesWithText("사진 선택").onFirst().performClick()
        openAlbum(); choose(photos[0]); choose(photos[1])
        compose.onNodeWithTag("gallery_confirm").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("tool-save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("홈으로").performClick()
        compose.onNodeWithText("GIF · MP4 만들기").performClick()
        compose.onAllNodesWithText("사진 선택").onFirst().performClick()
        openAlbum(); choose(photos[2]); choose(photos[3])
        compose.onNodeWithTag("gallery_confirm").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("tool-save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("홈으로").performClick()
        compose.onNodeWithText("GIF · MP4 만들기").performClick()
        compose.onNodeWithTag("tool-save").assertIsNotEnabled()
    }
}
