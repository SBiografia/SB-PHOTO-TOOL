package com.sb.phototool.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sb.phototool.model.*
import com.sb.phototool.ui.split.SplitEditorScreen
import com.sb.phototool.ui.compress.CompressEditorScreen
import com.sb.phototool.ui.gif.GifEditorScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ToolEditorsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(index: Int): Uri {
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(listOf(0xFFD7C6B4, 0xFFBBCEC5, 0xFFB8C9D5)[index % 3].toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(430f, 180f, 70f, paint)
        paint.color = 0xFF698B77.toInt(); canvas.drawOval(-180f, 450f, 850f, 1300f, paint)
        paint.color = 0xFF34574E.toInt(); canvas.drawOval(200f, 570f, 1000f, 1200f, paint)
        paint.color = Color.WHITE; paint.textSize = 110f; canvas.drawText("${index + 1}", 260f, 410f, paint)
        val file = File(context.cacheDir, "tools-ui-$index.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
        return Uri.fromFile(file)
    }
    private fun waitSave() = compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag("tool-save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    private fun screenshot(name: String) {
        val directory = File(context.getExternalFilesDir(null), "tools-qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun missingSplitPhoto_showsRetryWithoutEnablingSave() {
        compose.setContent { SplitEditorScreen(Uri.parse("file:///missing-grid-image.jpg"), {}, {}, {}) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("사진을 열지 못했어요 · 다시 시도").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tool-save").assertIsNotEnabled()
        compose.onNodeWithText("사진을 열지 못했어요 · 다시 시도").performClick()
        compose.onNodeWithTag("tool-save").assertIsNotEnabled()
    }

    @Test fun gifMissingPhoto_canBeRemovedWithoutCrashing() {
        compose.setContent {
            var sources by remember { mutableStateOf(listOf(Uri.parse("file:///missing-gif-image.jpg"))) }
            GifEditorScreen(sources, {}, {}, { sources = it }, {})
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("1장을 열지 못했어요. 제외하거나 다시 선택하세요.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tool-save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("애니메이션에서 사진 제외").performClick()
        compose.onNodeWithText("사진 여러 장으로 GIF 또는 MP4 만들기").assertExists()
        compose.onNodeWithTag("tool-save").assertIsNotEnabled()
    }

    @Test fun split_customGridCropAndRestoration_matchSavedJob() {
        val source = fixture(0)
        var submitted: SplitJob? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { SplitEditorScreen(source, {}, {}, { submitted = it as SplitJob }) }
        waitSave()
        compose.onNodeWithText("직접 설정").performClick()
        compose.onNodeWithText("가로(열) · 1~10").performTextReplacement("4")
        compose.onNodeWithText("세로(행) · 1~10").performTextReplacement("1")
        compose.onNodeWithText("적용").performClick()
        var lastBounds = compose.onNodeWithTag("grid-canvas").fetchSemanticsNode().boundsInRoot
        var stableSince = android.os.SystemClock.elapsedRealtime()
        compose.waitUntil(5_000) {
            val bounds = compose.onNodeWithTag("grid-canvas").fetchSemanticsNode().boundsInRoot
            if (bounds != lastBounds) { lastBounds = bounds; stableSince = android.os.SystemClock.elapsedRealtime() }
            android.os.SystemClock.elapsedRealtime() - stableSince > 500
        }
        compose.onNodeWithTag("crop-bottom").performTouchInput { swipe(center, center + Offset(0f, -75f), 400) }
        compose.onNodeWithTag("tool-save").performClick()
        assertTrue("Handle must resize: ${submitted!!.crop}", submitted!!.crop!!.right - submitted!!.crop!!.left < .95f)
        val resizedWidth = submitted!!.crop!!.right - submitted!!.crop!!.left
        compose.onNodeWithTag("grid-canvas").performTouchInput { swipe(Offset(width * .1f, height * .7f), Offset(width * .1f, height * .85f), 400) }
        screenshot("01-grid-crop")
        restoration.emulateSavedInstanceStateRestore()
        waitSave()
        compose.onNodeWithTag("tool-save").performClick()
        assertEquals(4, submitted!!.columns); assertEquals(1, submitted!!.rows)
        val crop = submitted!!.crop!!
        assertTrue("Restored resize: $crop", crop.right - crop.left < .95f)
        assertEquals(resizedWidth, crop.right - crop.left, .002f)
        assertTrue("Moved crop: $crop", crop.top > .4f)
    }

    @Test fun compression_measuresSampleAndValidatesCustomResolution() {
        val uris = listOf(fixture(0), fixture(1))
        var submitted: CompressJob? = null
        compose.setContent { CompressEditorScreen(uris, null, {}, {}, {}, { _, _ -> }, {}, { submitted = it as CompressJob }) }
        compose.onNodeWithText("해상도", substring = false).performClick()
        compose.onNodeWithText("크기 유지").performClick()
        compose.onNodeWithText("용량", substring = false).performClick()
        compose.onNodeWithText("제한 없음").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("1장 테스트").performClick()
        compose.waitUntil(25_000) { compose.onAllNodesWithText("재압축 없이 그대로 복사됩니다.").fetchSemanticsNodes().isNotEmpty() }
        screenshot("02-compress-result")
        compose.onNodeWithTag("tool-save").performClick()
        assertEquals(ResizeMode.NONE, submitted!!.resizeMode); assertEquals(0L, submitted!!.maxBytes)
        compose.onNodeWithText("해상도", substring = false).performClick()
        compose.onNodeWithText("긴 변", substring = false).performClick()
        compose.onNodeWithText("긴 변 · 1~8192px (확대 안 함)").performTextReplacement("")
        compose.onNodeWithTag("tool-save").assertIsNotEnabled()
    }

    @Test fun gif_reorderRemovePlayAndSpeed_areExported() {
        val inputs = listOf(fixture(0), fixture(1), fixture(2))
        var submitted: GifJob? = null
        compose.setContent {
            var photos by remember { mutableStateOf(inputs) }
            GifEditorScreen(photos, {}, {}, { photos = it }, { submitted = it as GifJob })
        }
        waitSave()
        compose.onNodeWithContentDescription("사진 뒤로 이동").performClick()
        compose.onNodeWithContentDescription("애니메이션에서 사진 제외").performClick()
        waitSave()
        compose.onNodeWithText("속도", substring = false).performClick()
        compose.onNodeWithText("0.50초", substring = false).performClick()
        compose.onNodeWithContentDescription("미리보기 재생").performClick()
        compose.onNodeWithContentDescription("미리보기 일시정지").performClick()
        screenshot("03-gif-photo")
        val before = compose.onNodeWithTag("gif-preview").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithContentDescription("도구 패널 접기").performClick()
        val after = compose.onNodeWithTag("gif-preview").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Collapsing controls must enlarge preview", after > before)
        screenshot("05-gif-large-preview")
        compose.onNodeWithContentDescription("도구 패널 펼치기").performClick()
        compose.onNodeWithTag("tool-save").performClick()
        assertEquals(listOf(inputs[1].toString(), inputs[2].toString()), submitted!!.uris)
        assertEquals(500, submitted!!.photoDelayMs)
    }

}
