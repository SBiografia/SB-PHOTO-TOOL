package com.sb.phototool.media

import android.graphics.*
import android.net.Uri
import android.content.ContentUris
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sb.phototool.data.CollageLibrary
import com.sb.phototool.model.*
import com.sb.phototool.ui.collage.CollageEditorState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CollageEnhancementsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun source(): File = File(context.cacheDir,"enhancements-source.png").also { file ->
        val bitmap = Bitmap.createBitmap(240,120,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(120,40,20)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    private fun outputs() = buildSet {
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?",arrayOf("Pictures/SB PHOTO TOOL/Collage/"),null)?.use { c ->
            while(c.moveToNext()) add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,c.getLong(0)))
        }
    }

    @Test fun manualDraftCopiesOriginalBytesAndSurvivesOriginalAndEntryDeletion_templatesContainNoPhotos() {
        val file = source(); val bytes = file.readBytes(); val library = CollageLibrary(context)
        val created = mutableListOf<String>()
        try {
            val job = CollageEditorState.create(listOf(Uri.fromFile(file).toString())).document.copy(
                texts = listOf(CollageText("title",text="테스트",bold=true,letterSpacing=.1f)),
                tiles = listOf(TileTransform(Uri.fromFile(file).toString(),locked=true,fitInside=true,adjustments=PhotoAdjustments(filter=PhotoFilter.MONO))))
            val draft = library.save("보정 초안",job,false).also { created += it.id }
            val template = library.save("나의 템플릿",job,true).also { created += it.id }
            assertTrue(file.delete())
            val loaded = library.load(draft.id)
            assertArrayEquals(bytes,File(Uri.parse(loaded.tiles[0].uri).path!!).readBytes())
            assertEquals(job.tiles[0].adjustments,loaded.tiles[0].adjustments); assertTrue(loaded.tiles[0].locked)
            assertEquals(job.texts,loaded.texts)
            library.delete(draft.id)
            assertArrayEquals(bytes,File(Uri.parse(loaded.tiles[0].uri).path!!).readBytes())
            val blank = library.load(template.id)
            assertTrue(blank.tiles.all { it.uri.isBlank() && !it.locked }); assertTrue(blank.placementLocked)
            assertEquals(1,File(context.filesDir,"collage-library/${template.id}").listFiles()!!.size)
            val before = library.list()
            assertTrue(runCatching { library.save("읽기 실패",job,false) }.isFailure)
            assertEquals(before,library.list())
            assertFalse(File(context.filesDir,"collage-library").listFiles()!!.any { it.name.startsWith(".") })
        } finally { created.forEach(library::delete); file.delete() }
    }

    @Test fun fitAndMonochromeAreIdenticalInPreviewAndActualJpeg_andOriginalRemainsUntouched() = runBlocking {
        val file = source(); val bytes = file.readBytes(); val bitmap = BitmapFactory.decodeFile(file.path)
        val tile = TileTransform(Uri.fromFile(file).toString(),fitInside=true,adjustments=PhotoAdjustments(brightness=.15f,filter=PhotoFilter.MONO))
        val job = CollageEditorState.create(listOf(tile.uri)).document.copy(tiles=listOf(tile),gapPercent=0f,
            exportSize=ExportSize(ExportSizeMode.CUSTOM,480))
        val before = outputs(); var made = emptySet<Uri>()
        val expected = Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888)
        try {
            drawCollageComposition(Canvas(expected),job,480f,480f,{ bitmap },font={ CollageFonts.get(context,it) })
            val color = expected.getPixel(240,240)
            assertEquals(Color.red(color),Color.green(color)); assertEquals(Color.green(color),Color.blue(color))
            assertEquals(Color.WHITE,expected.getPixel(240,40))
            assertEquals(1,MediaProcessor(context).execute(job) { _,_,_ -> }.succeeded)
            made = outputs()-before; assertEquals(1,made.size)
            val actual = context.contentResolver.openInputStream(made.single())!!.use { BitmapFactory.decodeStream(it) }
            assertEquals(480,actual.width); assertEquals(480,actual.height)
            val actualColor = actual.getPixel(240,240)
            assertTrue(kotlin.math.abs(Color.red(color)-Color.red(actualColor)) <= 3)
            assertTrue(Color.red(actual.getPixel(240,40)) >= 250)
            assertArrayEquals(bytes,file.readBytes()); actual.recycle()
        } finally { made.forEach { context.contentResolver.delete(it,null,null) }; bitmap.recycle(); expected.recycle(); file.delete() }
    }

    @Test fun filterIntensityZeroIsIdentity_andEachFilterPreservesOpaqueAlpha() {
        val source = Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(120,40,20)) }
        val target = Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
        try {
            for(filter in PhotoFilter.entries) {
                val paint = Paint().apply { colorFilter = photoColorFilter(PhotoAdjustments(filter=filter,intensity=0f)) }
                Canvas(target).drawBitmap(source,0f,0f,paint)
                assertEquals(source.getPixel(0,0),target.getPixel(0,0))
                paint.colorFilter = photoColorFilter(PhotoAdjustments(filter=filter))
                Canvas(target).drawBitmap(source,0f,0f,paint)
                assertEquals(255,Color.alpha(target.getPixel(0,0)))
            }
        } finally { source.recycle(); target.recycle() }
    }
}
