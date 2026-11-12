package com.sb.phototool.ui.collage

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sb.phototool.media.*
import com.sb.phototool.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CollageDesignInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun source(): Uri {
        val images = templateDemoPhotos(); val file = File(context.cacheDir, "studio-gesture-fixture.png")
        file.outputStream().use { images[0].compress(Bitmap.CompressFormat.PNG, 100, it) }; images.forEach(Bitmap::recycle)
        return Uri.fromFile(file)
    }
    private fun ready() = compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("design-save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    private fun screenshot(name: String) {
        val directory = File(context.getExternalFilesDir(null), "template-qa").apply { mkdirs() }
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun doubleTapAndLongPressSwap_respectPhotoLocksAndKeepGridFrames() {
        val uri = source().toString()
        val editor = CollageEditorState.create(listOf(uri,uri)).also { state ->
            state.update(state.document.copy(tiles = state.document.tiles.mapIndexed { i,p -> p.copy(adjustments=PhotoAdjustments(brightness=i*.2f)) }))
        }
        compose.setContent { MaterialTheme { CollageDesignStudio(editor,{},{},{}) } }
        ready()
        compose.onNodeWithTag("design-element-photo-0").performTouchInput { doubleClick() }
        compose.runOnIdle { assertTrue(editor.document.tiles[0].fitInside) }
        val canvas = compose.onNodeWithTag("design-canvas").fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithTag("design-element-photo-0").fetchSemanticsNode().boundsInRoot.center-canvas.topLeft
        val end = compose.onNodeWithTag("design-element-photo-1").fetchSemanticsNode().boundsInRoot.center-canvas.topLeft
        compose.onNodeWithTag("design-canvas").performTouchInput { down(start); advanceEventTime(700); moveTo(end,300); up() }
        compose.runOnIdle {
            assertEquals(.2f,editor.document.tiles[0].adjustments.brightness,0f)
            assertTrue(editor.document.tiles[1].fitInside)
            assertEquals(CollageMode.LAYOUT,editor.document.mode)
            assertTrue(editor.document.customCells.isEmpty())
            editor.update(editor.document.copy(tiles=editor.document.tiles.map { it.copy(locked=true) }))
        }
        val before = editor.document
        compose.onNodeWithTag("design-element-photo-0").performTouchInput { doubleClick(); swipe(center,center+Offset(35f,30f),300) }
        compose.runOnIdle { assertEquals(before,editor.document) }
    }

    @Test fun manualLibraryDialog_savesReloadsAndDeletesDraft() {
        val uri = source().toString(); val editor = CollageEditorState.create(listOf(uri))
        val library = com.sb.phototool.data.CollageLibrary(context)
        val previous = library.list().map { it.id }.toSet()
        compose.setContent { MaterialTheme {
            var open by remember { mutableStateOf(false) }
            CollageDesignStudio(editor,{},{},{},onLibrary={ open=true })
            if(open) CollageLibraryDialog(editor.document,{ job,_ -> editor.update(job); open=false },{ open=false })
        } }
        try {
            ready(); compose.onNodeWithTag("design-tool-보관함").performScrollTo().performClick()
            compose.onNodeWithText("저장 이름").performTextInput("계측 테스트 초안")
            Espresso.closeSoftKeyboard()
            compose.onNodeWithText("초안 저장").performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("계측 테스트 초안 · 초안").fetchSemanticsNodes().isNotEmpty() }
            screenshot("manual-library")
            compose.onNodeWithText("계측 테스트 초안 · 초안").performScrollTo().performClick()
            ready()
            compose.runOnIdle { assertNotEquals(uri,editor.document.tiles[0].uri) }
            compose.onNodeWithTag("design-tool-보관함").performScrollTo().performClick()
            compose.onAllNodesWithText("삭제").onFirst().performScrollTo().performClick()
            compose.onAllNodesWithText("삭제").onLast().performClick()
            compose.waitUntil(15_000) { library.list().none { it.id !in previous } }
            compose.onNodeWithText("닫기").performClick(); ready()
        } finally { library.list().filter { it.id !in previous }.forEach { library.delete(it.id) } }
    }

    @Test fun lockedTemplateCropsPhoto_unlockAllowsIndependentMoveScaleRotate_andUndoRestoresEachGesture() {
        val uri = source()
        val base = collageTemplates.first().document
        val editor = CollageEditorState(base.copy(tiles = base.tiles.map { it.copy(uri = uri.toString(), zoom = 1.5f) }))
        compose.setContent { MaterialTheme { CollageDesignStudio(editor, {}, {}, {}) } }
        ready()
        val id = base.tiles.first().elementId
        val before = editor.document.tiles.first().freeform
        compose.onNodeWithTag("design-element-" + id).performTouchInput { swipe(center, center + Offset(0f, 45f), 350) }
        compose.runOnIdle { assertEquals(before, editor.document.tiles.first().freeform) }
        compose.onNodeWithTag("design-placement-edit").performClick()
        var baseline = editor.document
        compose.runOnIdle { baseline = editor.document }
        compose.onNodeWithTag("design-element-" + id).performTouchInput { swipe(center, center + Offset(35f, -30f), 350) }
        compose.onNodeWithTag("design-handle-scale").performTouchInput { swipe(center, center - Offset(35f, 30f), 350) }
        compose.onNodeWithTag("design-handle-rotate").performTouchInput { swipe(center, center + Offset(80f, 0f), 350) }
        compose.runOnIdle {
            assertNotEquals(before, editor.document.tiles.first().freeform)
            assertTrue(kotlin.math.abs(editor.document.tiles.first().frameRotation) > 5f)
        }
        screenshot("studio-frame-rotation")
        repeat(3) { compose.onNodeWithContentDescription("실행 취소").performClick() }
        compose.runOnIdle { assertEquals(baseline, editor.document) }
    }

    @Test fun koreanTextFontAndOriginalCanvasRatio_areIncludedInSubmittedDocument() {
        val uri = source(); val base = collageTemplates.first().document
        val editor = CollageEditorState(base.copy(tiles = base.tiles.map { it.copy(uri = uri.toString()) }, placementLocked = false))
        var submitted: CollageJob? = null
        compose.setContent { MaterialTheme { CollageDesignStudio(editor, {}, {}, { submitted = it as CollageJob }) } }
        ready()
        compose.onNodeWithText("글자 추가").performScrollTo().performClick()
        compose.onNodeWithTag("design-text-content").performTextReplacement("우리의 여행\n2026.10")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("손글씨").performScrollTo().performClick()
        compose.onNodeWithTag("design-text-apply").performScrollTo().performClick()
        compose.onNodeWithText("완료").performClick()
        compose.onNodeWithText("비율").performScrollTo().performClick()
        compose.onNodeWithText("원본").performClick()
        Espresso.pressBack()
        val canvas = compose.onNodeWithTag("design-canvas").fetchSemanticsNode().boundsInRoot
        assertEquals(.75f, canvas.width / canvas.height, .002f)
        compose.onNodeWithTag("design-save").performClick()
        compose.onNodeWithText("갤러리에 저장").performClick()
        compose.runOnIdle {
            val added = requireNotNull(submitted).texts.last()
            assertEquals("우리의 여행\n2026.10", added.text)
            assertEquals(CollageFont.HAND, added.font)
            assertFalse(requireNotNull(submitted).placementLocked)
            assertEquals(CanvasRatio.ORIGINAL, requireNotNull(submitted).ratio)
            assertEquals(.75f, requireNotNull(submitted).freeformCanvasAspect!!, .0001f)
        }
        screenshot("studio-korean-text")
    }

    @Test fun multiSelectAppliesOnlyNewEditsAndKeepsPhotoToolsVisible() {
        val uri = source().toString()
        val initial = CollageEditorState.create(listOf(uri, uri, uri)).document
        val before = initial.copy(tiles = initial.tiles.mapIndexed { i, photo -> photo.copy(
            adjustments = PhotoAdjustments(filter = listOf(PhotoFilter.WARM, PhotoFilter.MONO, PhotoFilter.COOL)[i], contrast = 1f + i * .2f)
        ) })
        val editor = CollageEditorState(before)
        compose.setContent { MaterialTheme { CollageDesignStudio(editor, {}, {}, {}) } }
        ready()
        compose.onNodeWithTag("design-tool-자석·정렬").assertDoesNotExist()
        compose.onNodeWithTag("design-tool-요소 선택").assertDoesNotExist()
        compose.onNodeWithTag("design-element-photo-0").performClick()
        val multiBounds = compose.onNodeWithTag("design-multi-select").fetchSemanticsNode().boundsInRoot
        val doneBounds = compose.onNodeWithText("완료").fetchSemanticsNode().boundsInRoot
        assertTrue(multiBounds.right <= doneBounds.left + 1f)
        compose.onNodeWithTag("design-multi-select").performClick().assertIsSelected()
        compose.onNodeWithTag("design-element-photo-1").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(before, editor.document) }
        compose.onNodeWithText("2장 선택").assertExists()
        compose.onNodeWithTag("design-tool-사진 교체").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("design-tool-필터·보정").performScrollTo().performClick()
        compose.onNodeWithContentDescription("밝기").performSemanticsAction(SemanticsActions.SetProgress) { it(.42f) }
        compose.onNodeWithTag("photo-adjust-apply").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(.42f, editor.document.tiles[0].adjustments.brightness, .001f)
            assertEquals(.42f, editor.document.tiles[1].adjustments.brightness, .001f)
            assertEquals(PhotoFilter.WARM, editor.document.tiles[0].adjustments.filter)
            assertEquals(PhotoFilter.MONO, editor.document.tiles[1].adjustments.filter)
            assertEquals(before.tiles[2], editor.document.tiles[2])
        }
        compose.onNodeWithTag("design-tool-사진틀 모양").performScrollTo().performClick()
        compose.onNodeWithTag("design-shape-HEART").performScrollTo().performClick()
        Espresso.pressBack()
        compose.runOnIdle { assertTrue(editor.document.tiles.take(2).all { it.shape == PhotoShape.HEART }) }
        compose.onNodeWithTag("design-multi-select").performClick().assertIsNotSelected()
        compose.onNodeWithTag("design-element-photo-0").assertIsSelected()
        compose.onNodeWithTag("design-element-photo-1").assertIsNotSelected()
    }

    @Test fun multiSelectCanExitAfterDeselectingItsLastPhotoWithoutChangingEdits() {
        val uri = source().toString()
        val editor = CollageEditorState.create(listOf(uri, uri))
        val before = editor.document
        compose.setContent { MaterialTheme { CollageDesignStudio(editor, {}, {}, {}) } }
        ready()
        compose.onNodeWithTag("design-element-photo-0").performClick()
        compose.onNodeWithTag("design-multi-select").performClick()
        compose.onNodeWithTag("design-element-photo-0").performClick().assertIsNotSelected()
        compose.onNodeWithTag("design-multi-select").assertIsSelected()
        compose.onNodeWithTag("design-collage-tools").assertDoesNotExist()
        compose.onNodeWithText("완료").performClick()
        compose.onNodeWithTag("design-collage-tools").assertExists()
        compose.runOnIdle { assertEquals(before, editor.document); assertFalse(editor.canUndo) }
    }

    private fun dragBoundary(vertical: Boolean) {
        val uri = source().toString()
        val initial = CollageEditorState.create(listOf(uri, uri)).document
        val cells = if (vertical) listOf(NormalizedCell(0f, 0f, .5f, 1f), NormalizedCell(.5f, 0f, 1f, 1f))
            else listOf(NormalizedCell(0f, 0f, 1f, .5f), NormalizedCell(0f, .5f, 1f, 1f))
        val before = initial.copy(customCells = cells, placementLocked = false)
        val editor = CollageEditorState(before)
        compose.setContent { MaterialTheme { CollageDesignStudio(editor, {}, {}, {}) } }
        ready()
        compose.onNodeWithTag("design-element-photo-0").performClick()
        compose.onNodeWithTag(if (vertical) "collage-boundary-right" else "collage-boundary-bottom").performTouchInput {
            swipe(center, center + if (vertical) Offset(42f, 0f) else Offset(0f, 42f), 300)
        }
        compose.runOnIdle {
            val changed = editor.document.customCells
            assertTrue(if (vertical) changed[0].right > .5f else changed[0].bottom > .5f)
            assertEquals(if (vertical) changed[0].right else changed[0].bottom,
                if (vertical) changed[1].left else changed[1].top, .0001f)
            assertEquals(before.tiles, editor.document.tiles)
        }
        compose.onNodeWithTag("design-element-photo-0").assertIsSelected()
        compose.onNodeWithContentDescription("실행 취소").performClick()
        compose.runOnIdle { assertEquals(before, editor.document); assertFalse(editor.canUndo) }
    }

    @Test fun draggingLayoutWidthHandleResizesTheBoundaryAndUndoesAsOneStep() = dragBoundary(true)

    @Test fun draggingLayoutHeightHandleResizesTheBoundaryAndUndoesAsOneStep() = dragBoundary(false)

    @Test fun selectedPhotoShowsOnlyPhotoToolsAndRotationPanelKeepsPreviewVisible() {
        val uri = source().toString()
        val editor = CollageEditorState.create(listOf(uri, uri))
        compose.setContent { MaterialTheme { CollageDesignStudio(editor, {}, {}, {}) } }
        ready()
        compose.onNodeWithTag("design-collage-tools").assertExists()
        compose.onNodeWithTag("design-element-photo-0").performClick()
        compose.onNodeWithTag("design-collage-tools").assertDoesNotExist()
        compose.onNodeWithContentDescription("회전").assertDoesNotExist()
        compose.onNodeWithTag("design-tool-회전/반전").performScrollTo().performClick()
        val preview = compose.onNodeWithTag("design-canvas").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("design-inline-panel").fetchSemanticsNode().boundsInRoot
        assertTrue(preview.height > 0f && preview.bottom <= panel.top + 1f)
        compose.onNodeWithTag("photo-rotate-quarter-turn").performClick()
        compose.onNodeWithContentDescription("회전").performSemanticsAction(SemanticsActions.SetProgress) { it(270f) }
        compose.onNodeWithTag("photo-flip-horizontal").performClick()
        compose.onNodeWithTag("photo-flip-vertical").performClick()
        compose.runOnIdle {
            val photo = editor.document.tiles.first()
            assertEquals(1, photo.quarterTurns)
            assertEquals(270f, collagePhotoRotationSliderValue(photo.frameRotation, photo.quarterTurns), 0f)
            assertTrue(photo.flipHorizontal && photo.flipVertical)
        }
        compose.onNodeWithText("완료").performClick()
        compose.onNodeWithTag("design-element-photo-0").performClick()
        compose.onNodeWithTag("design-collage-tools").assertExists()
    }
}
