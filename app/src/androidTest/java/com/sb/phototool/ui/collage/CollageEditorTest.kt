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

    private fun tool(label: String) { compose.onNodeWithTag("design-tool-" + label).performScrollTo().performClick() }

    @Test fun unifiedGridLayoutRatioSwapAndMargins_areIncludedInSave() {
        val uris = photos(5); var submitted: CollageJob? = null
        compose.setContent { CollageEditorScreen(uris, {}, {}, { submitted = it as CollageJob }) }
        waitForPhotos()
        tool("레이아웃")
        compose.onNodeWithTag("collage-layouts").performScrollToKey("hero-center")
        compose.onNodeWithTag("collage-layout-hero-center").performClick().assertIsSelected()
        androidx.test.espresso.Espresso.pressBack()
        tool("비율"); compose.onNodeWithText("9:16").performClick(); androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("design-mode-LAYOUT").assertIsSelected()
        compose.onNodeWithTag("design-element-photo-0").performClick()
        tool("회전/반전"); compose.onNodeWithTag("photo-rotate-quarter-turn").performClick()
        compose.onNodeWithText("완료").performClick(); tool("교환")
        compose.onNodeWithTag("design-element-photo-1").performClick()
        compose.onNodeWithContentDescription("실행 취소").performClick()
        compose.onNodeWithContentDescription("다시 실행").performClick()
        tool("여백")
        compose.onNodeWithContentDescription("바깥 여백").performSemanticsAction(SemanticsActions.SetProgress) { it(.035f) }
        compose.onNodeWithContentDescription("사진 사이 간격").performSemanticsAction(SemanticsActions.SetProgress) { it(.012f) }
        androidx.test.espresso.Espresso.pressBack()
        screenshot("unified-grid")
        compose.onNodeWithText("저장").performClick(); compose.onNodeWithText("갤러리에 저장").performClick()
        compose.runOnIdle {
            val job = requireNotNull(submitted)
            assertEquals(CollageMode.LAYOUT,job.mode); assertEquals("hero-center",job.layoutId)
            assertEquals(CanvasRatio.STORY,job.ratio); assertEquals(uris[0].toString(),job.tiles[1].uri)
            assertEquals(1,job.tiles[1].quarterTurns); assertEquals(.035f,job.outerMarginPercent,.001f)
        }
    }

    @Test fun filterApplyCancelAndRotation_restoreTheAdjustmentTransaction() {
        val uris = photos(2); var submitted: CollageJob? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CollageEditorScreen(uris, {}, {}, { submitted = it as CollageJob }) }
        waitForPhotos(); compose.onNodeWithTag("design-element-photo-0").performClick(); tool("필터·보정")
        compose.onNodeWithTag("photo-filter-MONO").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("취소").performScrollTo().performClick()
        compose.onNodeWithText("저장").performClick(); compose.onNodeWithText("갤러리에 저장").performClick()
        compose.runOnIdle { assertEquals(PhotoAdjustments(),requireNotNull(submitted).tiles[0].adjustments) }
        tool("필터·보정"); compose.onNodeWithTag("photo-filter-WARM").performScrollTo().performClick()
        compose.onNodeWithContentDescription("밝기").performSemanticsAction(SemanticsActions.SetProgress) { it(.2f) }
        compose.onNodeWithTag("photo-adjust-apply").performScrollTo().performClick()
        screenshot("photo-filter-applied")
        compose.onNodeWithText("저장").performClick(); compose.onNodeWithText("갤러리에 저장").performClick()
        compose.runOnIdle { assertEquals(PhotoFilter.WARM,requireNotNull(submitted).tiles[0].adjustments.filter) }
        compose.onNodeWithContentDescription("실행 취소").performClick()
        compose.onNodeWithText("저장").performClick(); compose.onNodeWithText("갤러리에 저장").performClick()
        compose.runOnIdle { assertEquals(PhotoAdjustments(),requireNotNull(submitted).tiles[0].adjustments) }
    }

    @Test fun freeformCropPrecisionLockAndStateRestoration_areIncludedInSave() {
        val uris = photos(3); var submitted: CollageJob? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CollageEditorScreen(uris, {}, {}, { submitted = it as CollageJob }) }
        waitForPhotos(); compose.onNodeWithTag("design-mode-FREEFORM").performClick().assertIsSelected()
        compose.onNodeWithTag("design-element-photo-0").performClick()
        tool("정밀 조정"); compose.onNodeWithTag("photo-crop-1:1").performScrollTo().performClick()
        compose.onNodeWithContentDescription("사진 확대").performSemanticsAction(SemanticsActions.SetProgress) { it(1.6f) }
        androidx.test.espresso.Espresso.pressBack()
        tool("잠금"); compose.onNodeWithTag("design-tool-사진 교체").performScrollTo().assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore(); waitForPhotos()
        screenshot("freeform-locked")
        compose.onNodeWithText("저장").performClick(); compose.onNodeWithText("갤러리에 저장").performClick()
        compose.runOnIdle {
            val job = requireNotNull(submitted); assertEquals(CollageMode.FREEFORM,job.mode)
            assertTrue(job.tiles[0].locked); assertEquals(1.6f,job.tiles[0].zoom,.01f)
            val f = freeformFrame(job,0); assertEquals(f.right-f.left,f.bottom-f.top,.001f)
        }
    }

    @Test fun deletingAllPhotos_keepsUndoAndTheFifteenPhotoLimit() {
        val uris = photos(15); var submitted: CollageJob? = null
        compose.setContent { CollageEditorScreen(uris, {}, {}, { submitted = it as CollageJob }) }
        waitForPhotos(); compose.onNodeWithTag("design-tool-칸 추가").performScrollTo().assertIsNotEnabled()
        repeat(15) {
            compose.onNodeWithTag("design-element-photo-" + it).performClick(); tool("삭제")
        }
        compose.onNodeWithTag("design-save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("실행 취소").performClick(); waitForPhotos()
        compose.onNodeWithText("저장").performClick(); compose.onNodeWithText("갤러리에 저장").performClick()
        compose.runOnIdle { assertEquals(1,requireNotNull(submitted).tiles.size) }
    }

    @Test fun missingPhoto_allowsRetryAndReplacementWithoutSaving() {
        compose.setContent { CollageEditorScreen(listOf(Uri.fromFile(File(context.cacheDir,"does-not-exist.jpg"))),{},{},{}) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("사진을 읽지 못했어요").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("design-save").assertIsNotEnabled()
        compose.onNodeWithText("다시 불러오기").performClick()
        compose.onNodeWithTag("design-element-photo-0").performClick()
        compose.onNodeWithTag("design-tool-사진 교체").performScrollTo().assertIsEnabled()
    }
}
