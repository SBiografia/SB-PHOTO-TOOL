package com.sb.phototool.ui.collage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sb.phototool.model.*
import com.sb.phototool.EditorPreviewState
import com.sb.phototool.media.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CollageEditorTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val createdPhotoUris = mutableListOf<Uri>()

    @After
    fun removeGeneratedGalleryFixtures() {
        createdPhotoUris.forEach { context.contentResolver.delete(it, null, null) }
    }

    private fun photos(count: Int): List<Uri> = List(count) { index ->
        val width = if (index % 2 == 0) 900 else 1200
        val height = if (index % 2 == 0) 1200 else 800
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val shades = listOf(0xFFECCCBD, 0xFFB7CFD2, 0xFFCCD9AF, 0xFFE7C6CB, 0xFFB8C6DF)
        canvas.drawColor(shades[index % shades.size].toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(width * .7f, height * .24f, width * .13f, paint)
        paint.color = 0xFF506E62.toInt()
        canvas.drawOval(-width * .5f, height * .58f, width * 1.3f, height * 1.6f, paint)
        paint.color = 0xFF304E49.toInt()
        canvas.drawOval(width * .3f, height * .72f, width * 1.7f, height * 1.5f, paint)
        paint.color = Color.WHITE
        paint.textSize = width / 5f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("${index + 1}", width * .5f, height * .55f, paint)
        // Asymmetric edge markers reveal crop, reflection and rotation mistakes.
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, width * .08f, height.toFloat(), paint)
        paint.color = Color.BLUE
        canvas.drawRect(width * .92f, 0f, width.toFloat(), height.toFloat(), paint)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "collage-qa-$index.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SB PHOTO TOOL/TestFixtures")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        createdPhotoUris += uri
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        bitmap.recycle()
        uri
    }

    private fun waitForPhotos() {
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("저장") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null), "collage-qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun freeformStartsWithWholePhotos_andRestoresEdgesAfterUserCropping() {
        val uris = photos(3)
        var submitted: CollageJob? = null
        compose.setContent { CollageEditorScreen(uris, {}, {}, { submitted = it as CollageJob }) }
        waitForPhotos()
        compose.onNodeWithTag("collage-layouts").performScrollToKey("hero-left")
        compose.onNodeWithTag("collage-layout-hero-left").performClick()
        compose.onNodeWithTag("collage-mode-FREEFORM").performClick()
        fun draft(): CollageJob {
            compose.onNodeWithText("저장").performClick()
            compose.onNodeWithText("갤러리에 저장").performClick()
            compose.waitForIdle()
            return requireNotNull(submitted)
        }
        fun assertOriginalEdges(job: CollageJob) {
            val preview = compose.onNodeWithTag("freeform-canvas").captureToImage().asAndroidBitmap()
            val frame = collageTileBounds(job, preview.width.toFloat(), preview.height.toFloat())[0]
            val red = preview.getPixel((frame.left + frame.width * .035f).toInt(), (frame.top + frame.height * .5f).toInt())
            val blue = preview.getPixel((frame.left + frame.width * .965f).toInt(), (frame.top + frame.height * .5f).toInt())
            assertTrue("Original left edge must be visible", Color.red(red) > 200 && Color.blue(red) < 60)
            assertTrue("Original right edge must be visible", Color.blue(blue) > 200 && Color.red(blue) < 60)
        }
        val initial = draft()
        initial.tiles.forEachIndexed { i, tile ->
            val rect = collageTileBounds(initial, 1000f, 1000f)[i]
            assertEquals(if (i % 2 == 0) .75f else 1.5f, rect.width / rect.height, .001f)
            assertEquals(1f, tile.zoom, 0f)
        }
        assertOriginalEdges(initial)
        screenshot("11-freeform-original-photos")
        compose.onNodeWithTag("freeform-select-0").performClick()
        compose.onNodeWithText("자르기").performClick()
        compose.onNodeWithTag("freeform-crop-1:1").performClick()
        compose.onNodeWithContentDescription("확대").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        val cropped = draft()
        compose.onNodeWithTag("collage-mode-LAYOUT").performClick()
        compose.onNodeWithTag("collage-mode-FREEFORM").performClick()
        val returned = draft()
        assertEquals(cropped.tiles[0].freeform, returned.tiles[0].freeform)
        assertEquals(2f, returned.tiles[0].zoom, 0f)
        compose.onNodeWithTag("freeform-select-0").performClick()
        compose.onNodeWithText("원본 복원").performClick()
        val restored = draft()
        val frame = collageTileBounds(restored, 1000f, 1000f)[0]
        assertEquals(.75f, frame.width / frame.height, .001f)
        assertEquals(1f, restored.tiles[0].zoom, 0f)
        assertEquals(0f, restored.tiles[0].panX, 0f)
        assertOriginalEdges(restored)
        screenshot("12-freeform-original-restored")
    }

    @Test
    fun freeformGestures_changeFrameAndCropIndependentlyWithoutCanvasJump() {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val initial = CollageJob("gesture", listOf(TileTransform("fixture", freeform = NormalizedCell(.2f, .2f, .65f, .65f))),
            CanvasRatio.SQUARE, 0f, 0f, Color.WHITE, ExportSize(), mode = CollageMode.FREEFORM)
        val editor = CollageEditorState(initial)
        var selected by mutableIntStateOf(0)
        var crop by mutableStateOf(false)
        compose.setContent { FreeformCollageCanvas(editor, mapOf("fixture" to EditorPreviewState.Ready(bitmap)), selected,
            crop, { selected = it }, { selected = -1 }, Modifier.fillMaxSize()) }
        val canvasBefore = compose.onNodeWithTag("freeform-canvas").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("freeform-photo-0").performTouchInput { swipe(center, center + Offset(40f, 25f), 400) }
        compose.runOnIdle {
            val frame = freeformFrame(editor.document, 0)
            assertTrue("Drag must move frame horizontally", frame.left > .22f)
            assertTrue("Drag must move frame vertically", frame.top > .21f)
            assertEquals(.45f, frame.right - frame.left, .0001f)
        }
        assertEquals(canvasBefore, compose.onNodeWithTag("freeform-canvas").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("freeform-photo-0").performTouchInput {
            down(0, center - Offset(45f, 0f)); down(1, center + Offset(45f, 0f))
            repeat(10) { step ->
                moveTo(0, center - Offset(45f + step * 2, 0f), 16)
                moveTo(1, center + Offset(45f + step * 2, 0f), 16)
            }
            up(0); up(1)
        }
        var scaled = initial.tiles.single().freeform!!
        compose.runOnIdle {
            scaled = freeformFrame(editor.document, 0)
            assertTrue("Pinch must enlarge frame, not crop", scaled.right - scaled.left > .5f)
            assertEquals(1f, editor.document.tiles.single().zoom, 0f)
        }
        compose.onNodeWithTag("freeform-handle-scale").performTouchInput { swipe(center, center - Offset(45f, 45f), 400) }
        compose.runOnIdle {
            assertTrue("Handle must shrink frame", freeformFrame(editor.document, 0).right < scaled.right)
            crop = true
        }
        var beforeCrop = initial.tiles.single().freeform!!
        compose.runOnIdle { beforeCrop = freeformFrame(editor.document, 0) }
        compose.onNodeWithTag("freeform-handle-right").performTouchInput { swipe(center, center - Offset(30f, 0f), 400) }
        compose.runOnIdle {
            val frame = freeformFrame(editor.document, 0)
            assertTrue("Crop edge must shrink width", frame.right < beforeCrop.right)
            assertEquals(beforeCrop.left, frame.left, .0001f)
            beforeCrop = frame
        }
        compose.onNodeWithTag("freeform-photo-0").performTouchInput {
            down(0, center - Offset(40f, 0f)); down(1, center + Offset(40f, 0f))
            repeat(8) { step ->
                moveTo(0, center - Offset(40f + step * 3, 0f), 16)
                moveTo(1, center + Offset(40f + step * 3, 0f), 16)
            }
            up(0); up(1)
        }
        compose.runOnIdle {
            assertEquals(beforeCrop, freeformFrame(editor.document, 0))
            assertTrue("Crop pinch must enlarge content", editor.document.tiles.single().zoom > 1.2f)
            repeat(5) { editor.undo() }
            assertEquals(initial, editor.document)
        }
    }

    @Test
    fun freeformMoveResizeCropLayersAndRestoration_areIncludedInSave() {
        val uris = photos(5)
        var submitted: CollageJob? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CollageEditorScreen(uris, {}, {}, { submitted = it as CollageJob }) }
        waitForPhotos()
        compose.onNodeWithTag("collage-family-MOSAIC").performClick()
        compose.onNodeWithTag("collage-layouts").performScrollToKey("pinwheel-0")
        compose.onNodeWithTag("collage-layout-pinwheel-0").performClick()
        screenshot("08-expanded-layout")
        compose.onNodeWithTag("collage-mode-FREEFORM").performClick().assertIsSelected()
        compose.onNodeWithTag("freeform-select-0").performClick()
        compose.onNodeWithTag("freeform-photo-0").performTouchInput { swipe(center, center + Offset(45f, 20f), 400) }
        compose.onNodeWithTag("freeform-handle-scale").performTouchInput { swipe(center, center + Offset(35f, 40f), 400) }
        compose.onNodeWithTag("freeform-photo-0").performTouchInput {
            down(0, center - Offset(30f, 0f)); down(1, center + Offset(30f, 0f))
            repeat(8) { step ->
                moveTo(0, center - Offset(30f + step * 2, 0f), 16)
                moveTo(1, center + Offset(30f + step * 2, 0f), 16)
            }
            up(0); up(1)
        }
        repeat(4) { compose.onNodeWithText("앞으로").performClick() }
        compose.onNodeWithText("앞으로").assertIsNotEnabled()
        screenshot("09-freeform-layers")
        compose.onNodeWithText("자르기").performClick()
        compose.onNodeWithTag("freeform-crop-4:5").performClick()
        compose.onNodeWithContentDescription("확대").performSemanticsAction(SemanticsActions.SetProgress) { it(1.6f) }
        compose.onNodeWithTag("freeform-handle-right").performTouchInput { swipe(center, center - Offset(20f, 0f), 400) }
        compose.onNodeWithTag("freeform-photo-0").performTouchInput { swipe(center, center + Offset(14f, 10f), 400) }
        screenshot("10-freeform-crop")
        compose.onNodeWithText("완료").performClick()
        compose.onNodeWithContentDescription("실행 취소").performClick()
        compose.onNodeWithContentDescription("다시 실행").performClick()
        restoration.emulateSavedInstanceStateRestore()
        waitForPhotos()
        compose.onNodeWithTag("collage-mode-FREEFORM").assertIsSelected()
        compose.onNodeWithText("저장").performClick()
        compose.onNodeWithText("갤러리에 저장").performClick()
        compose.runOnIdle {
            val job = requireNotNull(submitted)
            assertEquals(CollageMode.FREEFORM, job.mode)
            assertEquals(uris.map(Uri::toString), job.tiles.map { it.uri })
            assertEquals(0, collagePaintOrder(job).last())
            assertEquals(1.6f, job.tiles[0].zoom, .01f)
            assertTrue(job.tiles.all { validFreeformFrame(it.freeform) })
            val original = collageLayouts(5).first { it.id == "pinwheel-0" }.cells[0]
            assertNotEquals(original, job.tiles[0].freeform)
        }
    }

    @Test
    fun layoutGesturesSwapUndoAndSave_workThroughTheEditor() {
        val uris = photos(5)
        var submitted: CollageJob? = null
        compose.setContent { CollageEditorScreen(uris, {}, {}, { submitted = it as CollageJob }) }
        waitForPhotos()
        screenshot("01-layouts")
        compose.onNodeWithTag("collage-layouts").performScrollToKey("hero-center")
        compose.onNodeWithTag("collage-layout-hero-center").performClick().assertIsSelected()
        compose.onNodeWithTag("collage-tab-RATIO").performClick()
        compose.onNodeWithContentDescription("9:16 비율").performClick()
        screenshot("02-story")

        compose.onNodeWithTag("collage-photo-0").performTouchInput { click() }
        compose.onNodeWithText("회전").assertIsDisplayed().performClick()
        compose.onNodeWithText("위치 교환").performClick()
        compose.onNodeWithTag("collage-photo-1").performTouchInput { click() }
        compose.onNodeWithText("사진 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("실행 취소").performClick()
        compose.onNodeWithContentDescription("다시 실행").performClick()

        compose.onNodeWithTag("collage-photo-1").performTouchInput {
            val left = center - Offset(width * .12f, 0f)
            val right = center + Offset(width * .12f, 0f)
            down(0, left); down(1, right)
            repeat(12) { step ->
                val span = width * (.12f + (step + 1) * .012f)
                moveTo(0, center - Offset(span, 0f), delayMillis = 16)
                moveTo(1, center + Offset(span, 0f), delayMillis = 16)
            }
            up(0); up(1)
        }
        compose.onNodeWithTag("collage-photo-1").performTouchInput { swipe(center, center + Offset(24f, 12f), 300) }
        compose.onNodeWithTag("collage-boundary-right").performTouchInput { swipe(center, center + Offset(42f, 0f), 300) }
        screenshot("03-photo-edit")
        compose.onNodeWithText("완료").performClick()
        compose.onNodeWithTag("collage-tab-MARGIN").performClick()
        compose.onNodeWithContentDescription("바깥 여백").performSemanticsAction(SemanticsActions.SetProgress) { it(.035f) }
        compose.onNodeWithContentDescription("사진 간격").performSemanticsAction(SemanticsActions.SetProgress) { it(.012f) }
        compose.onNodeWithContentDescription("모서리").performSemanticsAction(SemanticsActions.SetProgress) { it(.035f) }
        screenshot("04-spacing")
        compose.onNodeWithTag("collage-tab-BORDER").performClick()
        compose.onNodeWithContentDescription("배경 색상 6").performClick()
        compose.onNodeWithContentDescription("두께").performSemanticsAction(SemanticsActions.SetProgress) { it(.005f) }
        screenshot("05-border")
        compose.onNodeWithText("저장").performClick()
        compose.onNodeWithText("1080px").performClick()
        compose.onNodeWithText("갤러리에 저장").performClick()
        compose.runOnIdle {
            val job = requireNotNull(submitted)
            assertEquals(5, job.tiles.size)
            assertEquals("hero-center", job.layoutId)
            assertEquals(CanvasRatio.STORY, job.ratio)
            assertEquals(uris[0].toString(), job.tiles[1].uri)
            assertEquals(1, job.tiles[1].quarterTurns)
            assertTrue("Pinch must enlarge the photo", job.tiles[1].zoom > 1.2f)
            assertEquals(5, job.customCells.size)
            assertEquals(.035f, job.outerMarginPercent, .001f)
            assertEquals(ExportSizeMode.SOCIAL_1080, job.exportSize.mode)
        }
    }

    @Test
    fun draftRestoration_ninePhotoLimitAndDeletingToEmpty_remainUsable() {
        val uris = photos(9)
        var submitted: CollageJob? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CollageEditorScreen(uris, {}, {}, { submitted = it as CollageJob }) }
        waitForPhotos()
        compose.onNodeWithContentDescription("사진 추가").assertIsNotEnabled()
        compose.onNodeWithTag("collage-layouts").performScrollToKey("hero-center")
        compose.onNodeWithTag("collage-layout-hero-center").performClick()
        restoration.emulateSavedInstanceStateRestore()
        waitForPhotos()
        compose.onNodeWithTag("collage-layouts").performScrollToKey("hero-center")
        compose.onNodeWithTag("collage-layout-hero-center").assertIsSelected()
        screenshot("06-nine-photos")
        repeat(9) {
            compose.onNodeWithTag("collage-photo-0").performClick()
            compose.onNodeWithText("삭제").performScrollTo().performClick()
        }
        compose.onNodeWithText("사진 선택 · 최대 9장").assertIsDisplayed()
        compose.onNodeWithText("저장").assertIsNotEnabled()
        compose.onNodeWithContentDescription("실행 취소").performClick()
        waitForPhotos()
        compose.onNodeWithTag("collage-photo-0").assertExists()
        compose.onNodeWithText("저장").performClick()
        compose.onNodeWithText("갤러리에 저장").performClick()
        compose.runOnIdle { assertEquals(1, submitted?.tiles?.size) }
    }

    @Test
    fun missingPhoto_showsRetryAndDisablesSavingWithoutCrashing() {
        compose.setContent { CollageEditorScreen(listOf(Uri.fromFile(File(context.cacheDir, "does-not-exist.jpg"))), {}, {}, {}) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("사진을 읽지 못했어요").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("저장").assertIsNotEnabled()
        compose.onAllNodesWithText("다시 불러오기").onLast().performClick()
        compose.onNodeWithTag("collage-photo-0").performClick()
        compose.onNodeWithText("교체").assertIsDisplayed()
    }
}
