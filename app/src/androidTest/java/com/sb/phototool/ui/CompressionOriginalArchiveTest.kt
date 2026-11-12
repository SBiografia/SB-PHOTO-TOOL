package com.sb.phototool.ui

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sb.phototool.MainActivity
import com.sb.phototool.data.CompressionOriginalStore
import com.sb.phototool.media.CompressionOriginalArchive
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CompressionOriginalArchiveTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val prefix = "archive-qa-${System.nanoTime()}"
    private val album = "$prefix-originals"
    private val originals = mutableListOf<Uri>()
    private lateinit var good: Uri
    private lateinit var bad: Uri
    private lateinit var untouched: Uri
    private var foreign: Uri? = null
    private var foreignStaging: File? = null

    @Before fun fixtures() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        good = fixture("good", album)
        bad = fixture("bad", album, valid = false)
        untouched = fixture("untouched", album)
    }

    private fun fixture(name: String, folder: String, valid: Boolean = true): Uri {
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$prefix-$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$folder")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        originals += uri
        resolver.openOutputStream(uri)!!.use { output ->
            if (valid) {
                val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.GREEN)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                bitmap.recycle()
            } else output.write("not an image".toByteArray())
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun outputs(): List<Uri> = buildList {
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id"),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("Pictures/SB PHOTO TOOL/Compress/", "$prefix%"), null)?.use { cursor ->
            while (cursor.moveToNext()) add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)))
        }
    }

    private fun trashed(uri: Uri): Boolean {
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        return resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)!!.use {
            assertTrue("An original must still exist, including after trashing", it.moveToFirst())
            it.getInt(0) == 1
        }
    }

    private val archivePath = "Pictures/SB PHOTO TOOL/compress_original/"

    private fun path(uri: Uri): String = resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
        check(it.moveToFirst())
        it.getString(0)
    }

    private fun name(uri: Uri): String = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use {
        check(it.moveToFirst())
        it.getString(0)
    }

    private fun read(uri: Uri) = resolver.openInputStream(uri)!!.use { it.readBytes() }

    @After fun cleanup() {
        systemWriteButton(false)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        (originals + outputs()).forEach { uri -> resolver.delete(uri, null, null) }
        foreign?.let { shell("content delete --uri $it") }
        foreignStaging?.delete()
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun compress(folder: String, selected: List<Uri>?, count: Int) {
        compose.onNodeWithText("사진 압축", substring = false).performClick()
        compose.onAllNodesWithText("사진 선택").onFirst().performClick()
        compose.onNodeWithTag("gallery_albums").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText(folder).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithText(folder).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("gallery_photo_${ContentUris.parseId(selected?.first() ?: originals.last())}").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        if (selected == null) {
            compose.onNodeWithTag("gallery_select_all").performClick()
        } else selected.forEach { uri ->
            compose.onNodeWithTag("gallery_grid").performScrollToKey(uri.toString())
            compose.onNodeWithTag("gallery_photo_${ContentUris.parseId(uri)}").performClick()
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("확인 ($count)").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithTag("gallery_confirm").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("tool-save") and isEnabled() and hasText("${count}장 압축")).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithTag("tool-save").assertTextEquals("${count}장 압축").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("tool-save").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
        compose.waitUntil(90_000) { compose.onAllNodesWithTag("compression-archive-originals").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithTag("compression-archive-originals").performScrollTo().assertIsEnabled()
    }

    private fun currentJobId() = JSONObject(File(context.filesDir, "compression-trash.json").readText()).getString("jobId")

    private fun systemWriteButton(allow: Boolean): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        if (root.packageName?.toString() == context.packageName) return null
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo) {
            nodes += node
            repeat(node.childCount) { node.getChild(it)?.let(::collect) }
        }
        collect(root)
        if (nodes.none { node -> node.text?.toString()?.let { it.contains("modify", ignoreCase = true) || it.contains("edit", ignoreCase = true) } == true }) return null
        val ids = if (allow) listOf("/button1", "/permission_allow_button") else listOf("/button2", "/permission_deny_button")
        return nodes.firstOrNull { node -> node.isEnabled && node.isClickable && ids.any { node.viewIdResourceName?.endsWith(it) == true } }
            ?: nodes.firstOrNull { node ->
                val label = node.text?.toString()?.lowercase()
                node.isEnabled && node.isClickable && if (allow) label in listOf("allow", "modify", "edit")
                else label in listOf("don't allow", "don’t allow", "cancel", "deny")
            }
    }

    private fun answerWrite(allow: Boolean) {
        compose.waitUntil(15_000) { systemWriteButton(allow) != null }
        assertTrue(requireNotNull(systemWriteButton(allow)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun screenshot(name: String) {
        val folder = File(context.getExternalFilesDir(null), "compression-archive-qa").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun partialFailure_cancelRotationThenApprove_onlySuccessfulOriginalIsArchived() = runBlocking {
        val originalBytes = read(good)
        compress(album, listOf(good, bad), 2)
        compose.onNodeWithTag("compression-archive-originals").assertTextEquals("원본 1장 별도 보관 폴더로 이동")
        val jobId = currentJobId()
        assertEquals(listOf(good.toString()), CompressionOriginalStore(context).load(jobId))
        assertEquals(listOf(ContentUris.parseId(good)), CompressionOriginalArchive(context).targets(jobId).mediaUris.map(ContentUris::parseId))
        assertEquals(1, outputs().size)
        originals.forEach { assertFalse(trashed(it)) }
        screenshot("01-partial-success")
        compose.onNodeWithTag("compression-archive-originals").performClick()
        answerWrite(false)
        compose.waitUntil(15_000) { compose.onAllNodesWithText("원본 폴더 이동을 취소했어요.").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        originals.forEach { assertFalse(trashed(it)); assertEquals("Pictures/$album/", path(it)) }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("compression-archive-originals") and isEnabled()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithTag("compression-archive-originals").performScrollTo().performClick()
        answerWrite(true)
        compose.waitUntil(20_000) { compose.onAllNodesWithText("원본 1장을 compress_original 폴더로 이동했어요.").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        assertEquals(archivePath, path(good)); assertFalse(trashed(good))
        assertFalse(trashed(bad)); assertFalse(trashed(untouched))
        assertEquals("Pictures/$album/", path(bad)); assertEquals("Pictures/$album/", path(untouched))
        assertArrayEquals(originalBytes, read(good))
        outputs().forEach { assertFalse(trashed(it)); assertTrue(read(it).isNotEmpty()) }
        assertTrue(CompressionOriginalStore(context).load(jobId).isEmpty())
        compose.onNodeWithTag("compression-archive-originals").assertDoesNotExist()
        screenshot("02-original-archived")
    }

    @Test fun oneHundredOriginals_areMovedTogether_bytesAndUnselectedOriginalsRemain() = runBlocking {
        val bulkAlbum = "$prefix-bulk"
        val bulk = List(100) { fixture("bulk-$it", bulkAlbum) }
        val bytes = bulk.map(::read)
        compress(bulkAlbum, null, 100)
        val jobId = currentJobId()
        assertEquals(bulk.map(Uri::toString).toSet(), CompressionOriginalStore(context).load(jobId).toSet())
        compose.onNodeWithTag("compression-archive-originals").assertTextEquals("원본 100장 별도 보관 폴더로 이동")
        assertEquals(100, outputs().size)
        originals.forEach { assertFalse(trashed(it)) }
        compose.onNodeWithTag("compression-archive-originals").performClick()
        answerWrite(true)
        compose.waitUntil(30_000) { compose.onAllNodesWithText("원본 100장을 compress_original 폴더로 이동했어요.").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        bulk.forEach { assertEquals(archivePath, path(it)); assertFalse(trashed(it)) }
        assertFalse(trashed(good)); assertFalse(trashed(bad)); assertFalse(trashed(untouched))
        outputs().forEach { assertFalse(trashed(it)); assertTrue(read(it).isNotEmpty()) }
        assertTrue(CompressionOriginalStore(context).load(jobId).isEmpty())
        screenshot("03-hundred-originals-archived")
        bulk.forEachIndexed { index, uri ->
            assertFalse(trashed(uri)); assertArrayEquals(bytes[index], read(uri))
        }
    }

    @Test fun storeKeepsLatestBatch_onlyConfirmedUrisAreRemoved_andUnsupportedSourcesAreNotActionable() = runBlocking {
        val store = CompressionOriginalStore(context)
        store.begin("first")
        assertTrue(store.complete("first", listOf(good.toString(), good.toString(), bad.toString())))
        assertEquals(listOf(good.toString(), bad.toString()), CompressionOriginalStore(context).load("first"))
        store.removeConfirmed("first", setOf(good.toString()))
        assertEquals(listOf(bad.toString()), store.load("first"))
        store.begin("second")
        assertTrue(store.complete("second", listOf(untouched.toString(), "file:///unsupported.jpg", "content://unsupported-provider/123")))
        assertFalse(store.complete("first", listOf(good.toString())))
        store.removeConfirmed("first", setOf(bad.toString()))
        assertTrue(store.load("first").isEmpty())
        assertEquals(3, store.load("second").size)
        val targets = CompressionOriginalArchive(context).targets("second")
        assertEquals(listOf(ContentUris.parseId(untouched)), targets.mediaUris.map(ContentUris::parseId))
        assertEquals(2, targets.unavailableCount)
        originals.forEach { assertFalse(trashed(it)) }
        store.removeConfirmed("second", store.load("second").toSet())
        assertTrue(store.load("second").isEmpty())
        assertFalse(store.complete("first", listOf(good.toString())))
        assertFalse(store.complete("second", listOf(untouched.toString())))
    }

    @Test fun duplicateNames_preserveExistingFiles_andLegacyRecordRecognizesInterruptedMove() = runBlocking {
        val first = fixture("same", "$prefix-first")
        val second = fixture("same", "$prefix-second")
        val existing = fixture("same", "SB PHOTO TOOL/compress_original", valid = false)
        val firstBytes = read(first)
        val secondBytes = read(second)
        val existingBytes = read(existing)
        val store = CompressionOriginalStore(context)
        // This is the record format used by the previously delivered APK.
        File(context.filesDir, "compression-trash.json").writeText(
            JSONObject().put("jobId", "legacy").put("originals",
                org.json.JSONArray(listOf(first.toString(), second.toString()))).toString(),
        )
        val archive = CompressionOriginalArchive(context)
        val result = archive.move("legacy")
        assertEquals(2, result.movedCount)
        assertEquals(0, result.failedCount)
        assertEquals(3, listOf(first, second, existing).map(::name).distinct().size)
        assertArrayEquals(existingBytes, read(existing))
        assertArrayEquals(firstBytes, read(first))
        assertArrayEquals(secondBytes, read(second))
        listOf(first, second, existing).forEach { assertEquals(archivePath, path(it)); assertFalse(trashed(it)) }
        assertTrue(store.load("legacy").isEmpty())

        // Simulate interruption after the file moved but before metadata was removed.
        store.begin("interrupted")
        store.complete("interrupted", listOf(first.toString(), untouched.toString()))
        val targets = archive.targets("interrupted")
        assertEquals(setOf(first.toString()), targets.archivedOriginals)
        assertEquals(listOf(ContentUris.parseId(untouched)), targets.mediaUris.map(ContentUris::parseId))
        assertEquals(1, archive.move("interrupted").movedCount)
        assertTrue(store.load("interrupted").isEmpty())
        assertEquals(0, archive.move("interrupted").movedCount)
        assertArrayEquals(firstBytes, read(first))
    }

    @Test fun foreignOwnedPhoto_requiresConsent_andMovesAsVisibleOriginal() = runBlocking {
        val foreignAlbum = "$prefix-foreign"
        val filename = "$prefix-foreign.jpg"
        val staged = File(context.externalCacheDir, filename).apply { writeBytes(read(good)) }
        foreignStaging = staged
        val destination = "/sdcard/Pictures/$foreignAlbum/$filename"
        shell("mkdir -p /sdcard/Pictures/$foreignAlbum")
        shell("cp ${staged.absolutePath} $destination")
        shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$destination")
        var uri: Uri? = null
        compose.waitUntil(15_000) {
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf("Pictures/$foreignAlbum/", filename), null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    assertNotEquals(context.packageName, cursor.getString(1))
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                }
            }
            uri != null
        }
        val source = requireNotNull(uri)
        foreign = source
        val bytes = read(source)
        val store = CompressionOriginalStore(context)
        store.begin("partial-move")
        store.complete("partial-move", listOf(good.toString(), source.toString()))
        val partial = CompressionOriginalArchive(context).move("partial-move")
        assertEquals(1, partial.movedCount)
        assertEquals(1, partial.failedCount)
        assertEquals(listOf(source.toString()), store.load("partial-move"))
        assertEquals("Pictures/$foreignAlbum/", path(source))
        assertArrayEquals(bytes, read(source))
        compress(foreignAlbum, listOf(source), 1)
        val jobId = currentJobId()
        assertEquals(listOf(source.toString()), CompressionOriginalStore(context).load(jobId))
        assertFalse(trashed(source))
        compose.onNodeWithTag("compression-archive-originals").performClick()
        answerWrite(true)
        compose.waitUntil(20_000) { compose.onAllNodesWithText("원본 1장을 compress_original 폴더로 이동했어요.").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        assertEquals(archivePath, path(source)); assertFalse(trashed(source))
        assertEquals(1, outputs().size)
        assertArrayEquals(bytes, read(source))
        assertArrayEquals(bytes, read(source))
        outputs().forEach { assertFalse(trashed(it)) }
        screenshot("04-foreign-original-archived")
    }
}
