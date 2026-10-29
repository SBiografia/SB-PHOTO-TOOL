package com.sb.phototool.media

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sb.phototool.EditorPreviewState
import com.sb.phototool.loadEditorPreview
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EditorPreviewQualityTest {
    @Test fun fullSourceDimensionsAndExifRotationAreUsedForBothEditors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "high-resolution-preview-qa.jpg")
        try {
            val source = Bitmap.createBitmap(4096, 2048, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.CYAN)
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            source.recycle()
            val pixelBudget = 8_388_608L
            for (surface in listOf("collage", "split")) {
                val preview = loadEditorPreview(context, Uri.fromFile(file), surface, HIGH_QUALITY_PREVIEW_MAX_EDGE, pixelBudget) as EditorPreviewState.Ready
                assertEquals(4096, preview.bitmap.width)
                assertEquals(2048, preview.bitmap.height)
                preview.bitmap.recycle() // Test-owned bitmap, never attached to a Compose canvas.
            }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val rotated = loadEditorPreview(context, Uri.fromFile(file), "collage", HIGH_QUALITY_PREVIEW_MAX_EDGE, pixelBudget) as EditorPreviewState.Ready
            assertEquals(2048, rotated.bitmap.width)
            assertEquals(4096, rotated.bitmap.height)
            rotated.bitmap.recycle()
        } finally { file.delete() }
    }
}
