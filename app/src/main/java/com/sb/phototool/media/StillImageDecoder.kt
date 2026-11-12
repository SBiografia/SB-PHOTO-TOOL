package com.sb.phototool.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max

/** Decodes the still-image portion of common images and Motion Photos. */
internal fun decodeStillBitmap(context: Context, uri: Uri, maxEdge: Int, maxPixels: Long): Bitmap {
    require(maxEdge > 0 && maxPixels > 0)
    val resolver = context.contentResolver
    var imageDecoderFailure: Exception? = null
    try {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (width, height) = previewDimensions(info.size.width, info.size.height, maxEdge, maxPixels)
            decoder.setTargetSize(width, height)
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (error: Exception) {
        imageDecoderFailure = error
    }

    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "지원하지 않는 이미지 형식이에요." }
        val sampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge, maxPixels)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("사진을 읽을 수 없어요.")
        val oriented = applyExifOrientation(raw, exifOrientation(context, uri))
        if (oriented !== raw) raw.recycle()
        return oriented
    } catch (fallback: Exception) {
        imageDecoderFailure?.let(fallback::addSuppressed)
        throw fallback
    }
}

/** Returns orientation-corrected dimensions without materializing the full source bitmap. */
internal fun readStillImageBounds(context: Context, uri: Uri): Size {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        return orientedSize(bounds.outWidth, bounds.outHeight, exifOrientation(context, uri))
    }

    var encodedWidth = 0
    var encodedHeight = 0
    val tiny = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        encodedWidth = info.size.width
        encodedHeight = info.size.height
        decoder.setTargetSize(1, 1)
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    tiny.recycle()
    require(encodedWidth > 0 && encodedHeight > 0) { "지원하지 않는 이미지 형식이에요." }
    return orientedSize(encodedWidth, encodedHeight, exifOrientation(context, uri))
}

private fun exifOrientation(context: Context, uri: Uri): Int = runCatching {
    context.contentResolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

private fun orientedSize(width: Int, height: Int, orientation: Int): Size = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90,
    ExifInterface.ORIENTATION_ROTATE_270,
    ExifInterface.ORIENTATION_TRANSPOSE,
    ExifInterface.ORIENTATION_TRANSVERSE -> Size(height, width)
    else -> Size(width, height)
}

private fun sampleSize(width: Int, height: Int, maxEdge: Int, maxPixels: Long): Int {
    var sample = 1
    while (true) {
        val sampledWidth = max(1, width / sample)
        val sampledHeight = max(1, height / sample)
        if (max(sampledWidth, sampledHeight) <= maxEdge && sampledWidth.toLong() * sampledHeight <= maxPixels) break
        if (sample >= (1 shl 30)) break
        sample *= 2
    }
    return sample
}

private fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return source
    }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}
