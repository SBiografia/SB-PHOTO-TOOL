package com.sb.phototool.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.sb.phototool.model.CanvasRatio
import com.sb.phototool.model.CollageJob
import com.sb.phototool.model.CompressJob
import com.sb.phototool.model.ConflictPolicy
import com.sb.phototool.model.ExportSize
import com.sb.phototool.model.ExportSizeMode
import com.sb.phototool.model.GifJob
import com.sb.phototool.model.GifOutputFormat
import com.sb.phototool.model.ImageFormat
import com.sb.phototool.model.JobSpec
import com.sb.phototool.model.SplitFitMode
import com.sb.phototool.model.SplitJob
import com.sb.phototool.model.TileTransform
import com.squareup.gifencoder.GifEncoder
import com.squareup.gifencoder.ImageOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.nio.ByteBuffer
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class UserVisibleException(message: String) : Exception(message)

data class ProcessingSummary(
    val succeeded: Int,
    val failed: Int,
    val details: List<String>,
    val inputBytes: Long = 0,
    val outputBytes: Long = 0,
    val measuredFiles: Int = 0,
) {
    val userMessage: String
        get() = when {
            failed == 0 -> "${succeeded}개 파일을 저장했어요." + (details.firstOrNull()?.let { "\n$it" } ?: "")
            succeeded == 0 -> details.firstOrNull() ?: "저장하지 못했어요."
            else -> "${succeeded}개 저장, ${failed}개를 처리하지 못했어요."
        }
}

data class CompressionPreview(val inputBytes: Long, val outputBytes: Long, val width: Int, val height: Int, val bitmap: Bitmap, val copied: Boolean, val targetMet: Boolean)
private data class PreparedCompression(val file: File, val inputBytes: Long, val copied: Boolean, val targetMet: Boolean)

private data class EncodedFile(
    val file: File,
    val targetMet: Boolean,
    val wasResizedForTarget: Boolean,
)

private data class ImageBounds(val width: Int, val height: Int)

/**
 * All transformations run on URI streams and temporary app files. Only a
 * completed result is published to MediaStore, so originals never change.
 */
class MediaProcessor(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    suspend fun execute(
        job: JobSpec,
        onProgress: suspend (current: Int, total: Int, label: String) -> Unit,
    ): ProcessingSummary = withContext(Dispatchers.IO) {
        when (job) {
            is CompressJob -> compress(job, onProgress)
            is CollageJob -> createCollage(job, onProgress)
            is SplitJob -> splitImage(job, onProgress)
            is GifJob -> createGif(job, onProgress)
        }
    }

    private suspend fun compress(
        job: CompressJob,
        onProgress: suspend (Int, Int, String) -> Unit,
    ): ProcessingSummary {
        require(job.uris.isNotEmpty()) { "압축할 사진을 선택해 주세요." }
        val details = mutableListOf<String>()
        var succeeded = 0
        var failed = 0
        var inputBytes = 0L
        var outputBytes = 0L
        var measuredFiles = 0

        job.uris.forEachIndexed { index, rawUri ->
            currentCoroutineContext().ensureActive()
            onProgress(index, job.uris.size, "사진 ${index + 1}/${job.uris.size} 처리 중")
            try {
                val uri = Uri.parse(rawUri)
                val sourceName = displayName(uri)
                val prepared = prepareCompression(uri, job)
                try {
                    currentCoroutineContext().ensureActive()
                    publishTempFile(
                        temp = prepared.file,
                        displayName = outputName(sourceName, "compressed", job.outputFormat.extension),
                        mimeType = job.outputFormat.mimeType,
                        album = "Compress",
                    )
                    succeeded++
                    if (prepared.inputBytes >= 0) {
                        inputBytes += prepared.inputBytes
                        outputBytes += prepared.file.length()
                        measuredFiles++
                    }
                    if (!prepared.targetMet) details += "$sourceName: 해상도 또는 촬영 정보 보존으로 목표 용량을 넘겼어요."
                } finally {
                    prepared.file.delete()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failed++
                details += "${displayNameOrFallback(rawUri)}: ${error.userMessage()}"
            }
        }
        onProgress(job.uris.size, job.uris.size, "압축 완료")
        return ProcessingSummary(succeeded, failed, details.take(4), inputBytes, outputBytes, measuredFiles)
    }

    suspend fun previewCompression(job: CompressJob): CompressionPreview = withContext(Dispatchers.IO) {
        require(job.uris.isNotEmpty()) { "테스트할 사진을 선택해 주세요." }
        val prepared = prepareCompression(Uri.parse(job.uris.first()), job)
        try {
            currentCoroutineContext().ensureActive()
            val bounds = imageBounds(Uri.fromFile(prepared.file))
            // These bounded preview pixels are owned by Compose/GC, not the export bitmap pool.
            CompressionPreview(prepared.inputBytes, prepared.file.length(), bounds.width, bounds.height,
                decodeBitmap(Uri.fromFile(prepared.file), 768), prepared.copied, prepared.targetMet)
        } finally { prepared.file.delete() }
    }

    private fun prepareCompression(uri: Uri, job: CompressJob): PreparedCompression {
        val sourceSize = byteSize(uri)
        val direct = job.resizeMode == com.sb.phototool.model.ResizeMode.NONE && sourceFormat(uri) == job.outputFormat &&
            job.keepMetadata && (job.maxBytes <= 0 || sourceSize in 0..job.maxBytes)
        if (direct) {
            val file = File.createTempFile("compress_copy_", ".${job.outputFormat.extension}", context.cacheDir)
            try {
                resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                    ?: throw UserVisibleException("사진을 읽을 수 없어요.")
                return PreparedCompression(file, file.length(), true, true)
            } catch (error: Throwable) { file.delete(); throw error }
        }
        var bitmap = decodeBitmap(uri, if (job.conflictPolicy == ConflictPolicy.PRESERVE_RESOLUTION) 8192 else 4096)
        try {
            bitmap = resizeForCompression(bitmap, job)
            if (job.outputFormat == ImageFormat.JPEG && bitmap.hasAlpha()) {
                val flattened = flattenForJpeg(bitmap, Color.WHITE)
                if (flattened !== bitmap) { bitmap.recycleSafely(); bitmap = flattened }
            }
            val encoded = encodeBitmap(bitmap, job.outputFormat, job.maxBytes, job.conflictPolicy, "compress_")
            try {
                if (job.keepMetadata) copyExif(uri, encoded.file)
                return PreparedCompression(encoded.file, sourceSize, false, job.maxBytes <= 0 || encoded.file.length() <= job.maxBytes)
            } catch (error: Throwable) { encoded.file.delete(); throw error }
        } finally { bitmap.recycleSafely() }
    }

    private suspend fun createCollage(
        job: CollageJob,
        onProgress: suspend (Int, Int, String) -> Unit,
    ): ProcessingSummary {
        require(job.tiles.size in 1..MAX_COLLAGE_PHOTOS) { "콜라주는 사진 1~9장이 필요해요." }
        onProgress(0, 1, "콜라주 캔버스 준비 중")
        val firstBounds = imageBounds(Uri.parse(job.tiles.first().uri))
        val (width, height) = exportDimensions(job.exportSize, job.ratio, firstBounds)
        val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(canvasBitmap).drawColor(job.backgroundColor)
            val radius = min(width, height) * job.cornerPercent.coerceIn(0f, 0.25f)
            val border = min(width, height) * job.borderWidthPercent.coerceIn(0f, .02f)
            val destinations = collageTileBounds(job, width.toFloat(), height.toFloat())
            collagePaintOrder(job).forEachIndexed { progress, index ->
                val destination = destinations[index]
                val tile = job.tiles[index]
                currentCoroutineContext().ensureActive()
                onProgress(progress, job.tiles.size, "콜라주 사진 ${progress + 1}/${job.tiles.size} 배치 중")
                val bitmap = decodeBitmap(Uri.parse(tile.uri), maxSide = max(width, height))
                try {
                    drawCollagePhoto(
                        canvas = Canvas(canvasBitmap),
                        bitmap = bitmap,
                        destination = destination,
                        tile = tile,
                        cornerRadius = radius,
                        borderWidth = border,
                        borderColor = job.borderColor,
                    )
                } finally {
                    bitmap.recycleSafely()
                }
            }
            onProgress(1, 1, "콜라주 저장 중")
            val jpegBitmap = flattenForJpeg(canvasBitmap, job.backgroundColor)
            val encoded = try {
                encodeBitmap(
                    bitmap = jpegBitmap,
                    format = ImageFormat.JPEG,
                    maxBytes = 0,
                    conflictPolicy = ConflictPolicy.PRESERVE_RESOLUTION,
                    prefix = "collage_",
                )
            } finally {
                if (jpegBitmap !== canvasBitmap) jpegBitmap.recycleSafely()
            }
            try {
                publishTempFile(
                    temp = encoded.file,
                    displayName = "SBPhotoTool_collage_${System.currentTimeMillis()}.jpg",
                    mimeType = ImageFormat.JPEG.mimeType,
                    album = "Collage",
                )
            } finally {
                encoded.file.delete()
            }
            return ProcessingSummary(1, 0, emptyList())
        } finally {
            canvasBitmap.recycleSafely()
        }
    }

    private suspend fun splitImage(
        job: SplitJob,
        onProgress: suspend (Int, Int, String) -> Unit,
    ): ProcessingSummary {
        require(job.columns in 1..MAX_GRID_AXIS && job.rows in 1..MAX_GRID_AXIS) { "가로와 세로는 각각 1~10칸으로 설정해 주세요." }
        onProgress(0, job.columns * job.rows, "분할 캔버스 준비 중")
        val sourceUri = Uri.parse(job.uri)
        val sourceBounds = imageBounds(sourceUri)
        val baseRatio = job.columns.toFloat() / job.rows.toFloat()
        var (width, height) = exportDimensions(job.exportSize, CanvasRatio.ORIGINAL, sourceBounds, baseRatio)
        width = max(job.columns, width / job.columns * job.columns)
        height = max(job.rows, height / job.rows * job.rows)

        val source = decodeBitmap(sourceUri, maxSide = max(width, height))
        val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(canvasBitmap)
            if (job.useBlurBackground && job.fitMode == SplitFitMode.FIT) {
                val backdrop = blurredBackdrop(source, width, height)
                try {
                    canvas.drawBitmap(backdrop, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                } finally {
                    backdrop.recycleSafely()
                }
            } else {
                canvas.drawColor(job.backgroundColor)
            }
            if (job.crop != null && job.fitMode == SplitFitMode.FILL) {
                drawGridCrop(canvas, source, job.crop, width.toFloat(), height.toFloat())
            } else drawFitted(
                canvas = canvas,
                bitmap = source,
                destination = RectF(0f, 0f, width.toFloat(), height.toFloat()),
                fill = job.fitMode == SplitFitMode.FILL,
            )

            val baseName = displayName(sourceUri).substringBeforeLast('.', "photo")
            val splitId = System.currentTimeMillis()
            val cells = instagramPostOrder(job.columns, job.rows)
            var success = 0
            val failures = mutableListOf<String>()
            cells.forEachIndexed { index, (row, column) ->
                currentCoroutineContext().ensureActive()
                onProgress(index, cells.size, "분할 이미지 ${index + 1}/${cells.size} 저장 중")
                try {
                    val x = column * (width / job.columns)
                    val y = row * (height / job.rows)
                    val tile = Bitmap.createBitmap(canvasBitmap, x, y, width / job.columns, height / job.rows)
                    try {
                        val encoded = encodeBitmap(
                            bitmap = tile,
                            format = ImageFormat.JPEG,
                            maxBytes = 0,
                            conflictPolicy = ConflictPolicy.PRESERVE_RESOLUTION,
                            prefix = "split_",
                        )
                        try {
                            publishTempFile(
                                temp = encoded.file,
                                displayName = "${baseName}_${job.columns}x${job.rows}_${splitId}_${(index + 1).toString().padStart(2, '0')}.jpg",
                                mimeType = ImageFormat.JPEG.mimeType,
                                album = "Split",
                            )
                            success++
                        } finally {
                            encoded.file.delete()
                        }
                    } finally {
                        tile.recycleSafely()
                    }
                } catch (error: Exception) {
                    failures += error.userMessage()
                }
            }
            return ProcessingSummary(success, cells.size - success, failures.take(3))
        } finally {
            source.recycleSafely()
            canvasBitmap.recycleSafely()
        }
    }

    private suspend fun createGif(
        job: GifJob,
        onProgress: suspend (Int, Int, String) -> Unit,
    ): ProcessingSummary {
        require(job.uris.isNotEmpty()) { "애니메이션으로 만들 사진을 선택해 주세요." }
        return when (job.outputFormat) {
            GifOutputFormat.GIF -> createPhotoGif(job, onProgress)
            GifOutputFormat.MP4 -> createPhotoMp4(job, onProgress)
        }
    }

    private suspend fun createPhotoGif(
        job: GifJob,
        onProgress: suspend (Int, Int, String) -> Unit,
    ): ProcessingSummary {
        val firstBounds = imageBounds(Uri.parse(job.uris.first()))
        val (width, height) = gifDimensions(firstBounds, job.targetLongEdge)
        verifyGifSafety(width, height, job.uris.size)
        val temp = File.createTempFile("gif_photo_", ".gif", context.cacheDir)
        try {
            temp.outputStream().use { stream ->
                val encoder = GifEncoder(stream, width, height, job.loopCount.coerceAtLeast(0))
                try {
                    job.uris.forEachIndexed { index, rawUri ->
                        currentCoroutineContext().ensureActive()
                        onProgress(index, job.uris.size, "GIF 프레임 ${index + 1}/${job.uris.size} 생성 중")
                        val source = decodeBitmap(Uri.parse(rawUri), maxSide = max(width, height))
                        try {
                            val frame = fittedBitmap(source, width, height, job.backgroundColor, fill = false)
                            try {
                                encoder.addImage(
                                    rgbMatrix(frame),
                                    ImageOptions().apply {
                                        setDelay(job.photoDelayMs.coerceIn(20, 2000).toLong(), TimeUnit.MILLISECONDS)
                                    },
                                )
                            } finally {
                                frame.recycleSafely()
                            }
                        } finally {
                            source.recycleSafely()
                        }
                    }
                } finally {
                    encoder.finishEncoding()
                }
            }
            publishTempFile(
                temp = temp,
                displayName = "SBPhotoTool_gif_${System.currentTimeMillis()}.gif",
                mimeType = "image/gif",
                album = "GIF",
            )
            return ProcessingSummary(1, 0, emptyList())
        } finally {
            temp.delete()
        }
    }

    private suspend fun createPhotoMp4(
        job: GifJob,
        onProgress: suspend (Int, Int, String) -> Unit,
    ): ProcessingSummary {
        val fps = job.fps.coerceIn(1, 30)
        val framesPerPhoto = max(1, ((job.photoDelayMs.coerceIn(20, 2000) * fps + 500) / 1000))
        val totalFrames = job.uris.size.toLong() * framesPerPhoto
        val firstBounds = imageBounds(Uri.parse(job.uris.first()))
        val (requestedWidth, requestedHeight) = gifDimensions(firstBounds, job.targetLongEdge)
        val width = (requestedWidth / 2 * 2).coerceAtLeast(2)
        val height = (requestedHeight / 2 * 2).coerceAtLeast(2)
        verifyMp4Safety(width, height, totalFrames, fps)
        val temp = File.createTempFile("photo_animation_", ".mp4", context.cacheDir)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, (width.toLong() * height * fps * 0.12).toInt().coerceIn(400_000, 8_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var outputTrack = -1
            val bufferInfo = MediaCodec.BufferInfo()
            var submittedFrames = 0L
            fun drainEncoder(endOfStream: Boolean): Boolean {
                while (true) {
                    val outputIndex = codec!!.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "동영상 출력 형식이 여러 번 변경됐어요." }
                            outputTrack = muxer!!.addTrack(codec!!.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        outputIndex >= 0 -> {
                            val encoded = codec!!.getOutputBuffer(outputIndex)
                            val end = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufferInfo.size = 0
                            if (bufferInfo.size > 0) {
                                check(muxerStarted && outputTrack >= 0) { "동영상 인코더 출력을 시작하지 못했어요." }
                                val sample = encoded ?: throw UserVisibleException("동영상 프레임을 읽지 못했어요.")
                                sample.position(bufferInfo.offset)
                                sample.limit(bufferInfo.offset + bufferInfo.size)
                                muxer!!.writeSampleData(outputTrack, sample, bufferInfo)
                            }
                            codec!!.releaseOutputBuffer(outputIndex, false)
                            if (end) return true
                        }
                    }
                }
            }

            job.uris.forEachIndexed { index, rawUri ->
                currentCoroutineContext().ensureActive()
                onProgress(index, job.uris.size, "MP4 사진 ${index + 1}/${job.uris.size} 인코딩 중")
                val source = decodeBitmap(Uri.parse(rawUri), maxSide = max(width, height))
                val fitted = try { fittedBitmap(source, width, height, job.backgroundColor, fill = false) }
                finally { source.recycleSafely() }
                try {
                    val yuv = bitmapToYuv420(fitted)
                    repeat(framesPerPhoto) {
                        currentCoroutineContext().ensureActive()
                        var inputIndex = codec!!.dequeueInputBuffer(10_000)
                        while (inputIndex < 0) {
                            currentCoroutineContext().ensureActive()
                            drainEncoder(endOfStream = false)
                            inputIndex = codec!!.dequeueInputBuffer(10_000)
                        }
                        val inputImage = codec!!.getInputImage(inputIndex)
                            ?: throw UserVisibleException("이 기기에서 MP4 입력 프레임을 준비하지 못했어요.")
                        try {
                            writeYuv420(inputImage, yuv)
                        } finally {
                            inputImage.close()
                        }
                        val presentationTimeUs = submittedFrames * 1_000_000L / fps
                        codec!!.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
                        submittedFrames++
                        drainEncoder(endOfStream = false)
                    }
                } finally {
                    fitted.recycleSafely()
                }
            }
            currentCoroutineContext().ensureActive()
            var eosInput = codec!!.dequeueInputBuffer(10_000)
            while (eosInput < 0) {
                drainEncoder(endOfStream = false)
                eosInput = codec!!.dequeueInputBuffer(10_000)
            }
            codec!!.queueInputBuffer(eosInput, 0, 0, submittedFrames * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            while (!drainEncoder(endOfStream = true)) currentCoroutineContext().ensureActive()
            check(temp.length() > 0L) { "MP4 파일을 만들지 못했어요." }
            publishTempFile(
                temp = temp,
                displayName = "SBPhotoTool_animation_${System.currentTimeMillis()}.mp4",
                mimeType = "video/mp4",
                album = "Animation",
            )
            return ProcessingSummary(1, 0, emptyList())
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is UserVisibleException) throw error
            throw UserVisibleException("MP4를 만들지 못했어요. 저장 공간을 확인하거나 더 낮은 해상도로 다시 시도해 주세요.")
        } finally {
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            temp.delete()
        }
    }

    private data class Yuv420Frame(val y: ByteArray, val u: ByteArray, val v: ByteArray)

    private fun bitmapToYuv420(bitmap: Bitmap): Yuv420Frame {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val yPlane = ByteArray(width * height)
        val chromaSize = (width / 2) * (height / 2)
        val uPlane = ByteArray(chromaSize)
        val vPlane = ByteArray(chromaSize)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val color = pixels[row * width + column]
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                yPlane[row * width + column] = (16 + ((66 * red + 129 * green + 25 * blue) shr 8)).coerceIn(0, 255).toByte()
            }
        }
        for (row in 0 until height step 2) {
            for (column in 0 until width step 2) {
                var red = 0
                var green = 0
                var blue = 0
                repeat(2) { dy -> repeat(2) { dx ->
                    val color = pixels[(row + dy) * width + column + dx]
                    red += Color.red(color)
                    green += Color.green(color)
                    blue += Color.blue(color)
                } }
                red /= 4
                green /= 4
                blue /= 4
                val chromaIndex = (row / 2) * (width / 2) + column / 2
                uPlane[chromaIndex] = (128 + ((-38 * red - 74 * green + 112 * blue) shr 8)).coerceIn(0, 255).toByte()
                vPlane[chromaIndex] = (128 + ((112 * red - 94 * green - 18 * blue) shr 8)).coerceIn(0, 255).toByte()
            }
        }
        return Yuv420Frame(yPlane, uPlane, vPlane)
    }

    private fun writeYuv420(image: android.media.Image, frame: Yuv420Frame) {
        val width = image.width
        val height = image.height
        val planes = image.planes
        writePlane(planes[0].buffer, frame.y, width, height, planes[0].rowStride, planes[0].pixelStride)
        writePlane(planes[1].buffer, frame.u, width / 2, height / 2, planes[1].rowStride, planes[1].pixelStride)
        writePlane(planes[2].buffer, frame.v, width / 2, height / 2, planes[2].rowStride, planes[2].pixelStride)
    }

    private fun writePlane(buffer: ByteBuffer, source: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int) {
        val base = buffer.position()
        for (row in 0 until height) {
            val sourceOffset = row * width
            val targetOffset = base + row * rowStride
            for (column in 0 until width) {
                buffer.put(targetOffset + column * pixelStride, source[sourceOffset + column])
            }
        }
    }

    private fun resizeForCompression(bitmap: Bitmap, job: CompressJob): Bitmap = when (job.resizeMode) {
        com.sb.phototool.model.ResizeMode.NONE -> bitmap
        com.sb.phototool.model.ResizeMode.PERCENT -> {
            val factor = job.percent.coerceIn(1, 100) / 100f
            scaleBitmap(bitmap, max(1, (bitmap.width * factor).roundToInt()), max(1, (bitmap.height * factor).roundToInt()))
        }

        com.sb.phototool.model.ResizeMode.PIXELS -> {
            if (job.targetLongEdge <= 0 || max(bitmap.width, bitmap.height) <= job.targetLongEdge) bitmap else {
                val factor = job.targetLongEdge.toFloat() / max(bitmap.width, bitmap.height)
                scaleBitmap(bitmap, (bitmap.width * factor).roundToInt(), (bitmap.height * factor).roundToInt())
            }
        }
    }

    private fun encodeBitmap(
        bitmap: Bitmap,
        format: ImageFormat,
        maxBytes: Long,
        conflictPolicy: ConflictPolicy,
        prefix: String,
    ): EncodedFile {
        if (maxBytes <= 0L || conflictPolicy == ConflictPolicy.PRESERVE_RESOLUTION) {
            val file = encodeAtQuality(bitmap, format, DEFAULT_QUALITY, prefix)
            return EncodedFile(file, maxBytes <= 0L || file.length() <= maxBytes, false)
        }

        var working = bitmap
        var ownsWorking = false
        try {
            repeat(MAX_TARGET_RESIZE_STEPS) {
                val candidate = when (format) {
                    ImageFormat.JPEG, ImageFormat.WEBP -> bestQualityWithinLimit(working, format, maxBytes, prefix)
                    ImageFormat.PNG -> encodeAtQuality(working, format, DEFAULT_QUALITY, prefix)
                }
                if (candidate.length() <= maxBytes) return EncodedFile(candidate, true, ownsWorking)
                candidate.delete()

                if (min(working.width, working.height) <= MIN_OUTPUT_SIDE) {
                    throw UserVisibleException("선택한 형식으로 최대 용량을 맞출 수 없어요. 더 큰 용량 또는 JPEG/WebP를 선택해 주세요.")
                }
                val resized = scaleBitmap(
                    working,
                    max(MIN_OUTPUT_SIDE, (working.width * 0.84f).roundToInt()),
                    max(MIN_OUTPUT_SIDE, (working.height * 0.84f).roundToInt()),
                )
                if (ownsWorking) working.recycleSafely()
                working = resized
                ownsWorking = true
            }
            throw UserVisibleException("최대 용량을 맞출 수 없어요.")
        } finally {
            if (ownsWorking) working.recycleSafely()
        }
    }

    private fun bestQualityWithinLimit(bitmap: Bitmap, format: ImageFormat, maxBytes: Long, prefix: String): File {
        var low = MIN_QUALITY
        var high = MAX_QUALITY
        var best: File? = null
        while (low <= high) {
            val quality = (low + high) / 2
            val candidate = encodeAtQuality(bitmap, format, quality, prefix)
            if (candidate.length() <= maxBytes) {
                best?.delete()
                best = candidate
                low = quality + 1
            } else {
                candidate.delete()
                high = quality - 1
            }
        }
        return best ?: encodeAtQuality(bitmap, format, MIN_QUALITY, prefix)
    }

    private fun encodeAtQuality(bitmap: Bitmap, format: ImageFormat, quality: Int, prefix: String): File {
        val file = File.createTempFile(prefix, ".${format.extension}", context.cacheDir)
        file.outputStream().use { stream ->
            val compressFormat = when (format) {
                ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                ImageFormat.PNG -> Bitmap.CompressFormat.PNG
                ImageFormat.WEBP -> Bitmap.CompressFormat.WEBP
            }
            if (!bitmap.compress(compressFormat, quality, stream)) {
                throw UserVisibleException("이미지를 저장하지 못했어요.")
            }
        }
        return file
    }

    private fun copyExif(source: Uri, destination: File) {
        runCatching {
            val sourceExif = resolver.openInputStream(source)?.use(::ExifInterface) ?: return
            val destinationExif = ExifInterface(destination)
            EXIF_TAGS.forEach { tag ->
                sourceExif.getAttribute(tag)?.let { destinationExif.setAttribute(tag, it) }
            }
            destinationExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            destinationExif.saveAttributes()
        }
    }

    private fun publishTempFile(temp: File, displayName: String, mimeType: String, album: String): Uri {
        val isVideo = mimeType.startsWith("video/")
        val collection = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${if (isVideo) "Movies" else "Pictures"}/SB PHOTO TOOL/$album")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw UserVisibleException("결과 앨범을 만들 수 없어요.")
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(temp).use { input -> input.copyTo(output) }
            } ?: throw UserVisibleException("결과 파일을 열 수 없어요.")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun copySourceToMediaStore(source: Uri, displayName: String, mimeType: String, album: String): Uri {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SB PHOTO TOOL/$album")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw UserVisibleException("결과 앨범을 만들 수 없어요.")
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                resolver.openInputStream(source)?.use { input -> input.copyTo(output) }
                    ?: throw UserVisibleException("원본 파일을 읽을 수 없어요.")
            } ?: throw UserVisibleException("결과 파일을 열 수 없어요.")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun decodeBitmap(uri: Uri, maxSide: Int): Bitmap {
        val bounds = imageBounds(uri)
        val sample = calculateInSampleSize(bounds.width, bounds.height, maxSide)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw UserVisibleException("사진을 읽을 수 없어요.")
        val rotated = applyExifOrientation(raw, exifOrientation(uri))
        if (rotated !== raw) raw.recycleSafely()
        return rotated
    }

    internal fun sourceAspectRatio(uri: Uri): Float = imageBounds(uri).let { it.width.toFloat() / it.height }

    private fun imageBounds(uri: Uri): ImageBounds {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw UserVisibleException("지원하지 않는 이미지 형식이에요.")
        }
        return when (exifOrientation(uri)) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE
            -> ImageBounds(options.outHeight, options.outWidth)

            else -> ImageBounds(options.outWidth, options.outHeight)
        }
    }

    private fun exifOrientation(uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

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

    private fun exportDimensions(
        export: ExportSize,
        ratio: CanvasRatio,
        source: ImageBounds,
        overrideRatio: Float? = null,
    ): Pair<Int, Int> {
        val desiredRatio = overrideRatio ?: ratio.ratio ?: source.width.toFloat() / source.height
        val sourceLongEdge = max(source.width, source.height)
        val longEdge = when (export.mode) {
            ExportSizeMode.ORIGINAL -> min(sourceLongEdge, MAX_STATIC_LONG_EDGE)
            ExportSizeMode.SOCIAL_1080 -> 1080
            ExportSizeMode.CUSTOM -> export.customLongEdge.coerceIn(320, MAX_STATIC_LONG_EDGE)
        }
        return if (desiredRatio >= 1f) {
            longEdge to max(1, (longEdge / desiredRatio).roundToInt())
        } else {
            max(1, (longEdge * desiredRatio).roundToInt()) to longEdge
        }
    }

    private fun gifDimensions(source: ImageBounds, requestedLongEdge: Int): Pair<Int, Int> {
        val longEdge = requestedLongEdge.coerceAtLeast(MIN_GIF_LONG_EDGE)
        val ratio = source.width.toFloat() / source.height
        return if (ratio >= 1f) longEdge to max(1, (longEdge / ratio).roundToInt())
        else max(1, (longEdge * ratio).roundToInt()) to longEdge
    }

    private fun verifyGifSafety(width: Int, height: Int, frameCount: Int) {
        val pixels = width.toDouble() * height.toDouble()
        val frameBytes = pixels * 4.0
        val runtimeBudget = Runtime.getRuntime().maxMemory().toDouble() / 5.0
        val cacheFree = StatFs(context.cacheDir.absolutePath).availableBytes
        val estimatedOutput = pixels * frameCount.toDouble() / 5.0
        if (!frameBytes.isFinite() || frameBytes > runtimeBudget) {
            throw UserVisibleException("선택한 해상도는 이 기기에서 GIF로 처리하기에 너무 커요. 해상도를 낮춰 주세요.")
        }
        if (!estimatedOutput.isFinite() || estimatedOutput > cacheFree.toDouble() / 2.0) {
            throw UserVisibleException("GIF 예상 크기에 비해 저장 공간이 부족해요. 해상도·FPS·구간을 줄여 주세요.")
        }
    }

    private fun verifyMp4Safety(width: Int, height: Int, frameCount: Long, fps: Int) {
        if (frameCount <= 0L || frameCount > 10_000L) {
            throw UserVisibleException("사진 수 또는 표시 시간이 너무 길어요. 사진 수나 장당 간격을 줄여 주세요.")
        }
        val frameBytes = width.toDouble() * height.toDouble() * 4.0
        val runtimeBudget = Runtime.getRuntime().maxMemory().toDouble() / 5.0
        if (!frameBytes.isFinite() || frameBytes > runtimeBudget) {
            throw UserVisibleException("선택한 해상도는 이 기기에서 MP4로 처리하기에 너무 커요. 해상도를 낮춰 주세요.")
        }
        val bitrate = (width.toDouble() * height * fps * 0.12).coerceIn(400_000.0, 8_000_000.0)
        val estimatedOutput = bitrate * frameCount.toDouble() / fps / 8.0
        val cacheFree = StatFs(context.cacheDir.absolutePath).availableBytes.toDouble()
        if (!estimatedOutput.isFinite() || estimatedOutput > cacheFree / 2.0) {
            throw UserVisibleException("MP4 예상 크기에 비해 저장 공간이 부족해요. 해상도나 표시 시간을 줄여 주세요.")
        }
    }

    private fun drawFitted(canvas: Canvas, bitmap: Bitmap, destination: RectF, fill: Boolean) {
        val scale = if (fill) max(destination.width() / bitmap.width, destination.height() / bitmap.height)
        else min(destination.width() / bitmap.width, destination.height() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val target = RectF(
            destination.centerX() - width / 2f,
            destination.centerY() - height / 2f,
            destination.centerX() + width / 2f,
            destination.centerY() + height / 2f,
        )
        val save = canvas.save()
        canvas.clipRect(destination)
        canvas.drawBitmap(bitmap, null, target, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restoreToCount(save)
    }

    private fun fittedBitmap(source: Bitmap, width: Int, height: Int, background: Int, fill: Boolean): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(background)
        drawFitted(canvas, source, RectF(0f, 0f, width.toFloat(), height.toFloat()), fill)
        return result
    }

    private fun flattenedCopy(source: Bitmap, background: Int): Bitmap {
        val copy = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(copy).apply {
            drawColor(background)
            drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        return copy
    }

    private fun flattenForJpeg(source: Bitmap, background: Int): Bitmap = if (source.hasAlpha()) flattenedCopy(source, background) else source

    private fun blurredBackdrop(source: Bitmap, width: Int, height: Int): Bitmap {
        val miniWidth = max(24, width / 28)
        val miniHeight = max(24, height / 28)
        val cropped = fittedBitmap(source, miniWidth, miniHeight, Color.BLACK, fill = true)
        return try {
            val result = Bitmap.createScaledBitmap(cropped, width, height, true)
            Canvas(result).drawColor(Color.argb(46, 0, 0, 0))
            result
        } finally {
            cropped.recycleSafely()
        }
    }

    private fun scaleBitmap(source: Bitmap, width: Int, height: Int): Bitmap {
        if (source.width == width && source.height == height) return source
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source) source.recycleSafely()
        return scaled
    }

    private fun rgbMatrix(bitmap: Bitmap): Array<IntArray> {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        // GifEncoder expects rows first, then columns (the same order as getPixels).
        return Array(bitmap.height) { y ->
            IntArray(bitmap.width) { x -> pixels[y * bitmap.width + x] }
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        var currentWidth = width
        var currentHeight = height
        while (max(currentWidth, currentHeight) > maxSide && sample < 32) {
            sample *= 2
            currentWidth /= 2
            currentHeight /= 2
        }
        return sample
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return uri.path?.let { File(it).name } ?: "photo"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && column >= 0) return cursor.getString(column)
        }
        return "photo"
    }

    private fun displayNameOrFallback(rawUri: String): String = runCatching { displayName(Uri.parse(rawUri)) }.getOrDefault("사진")

    private fun byteSize(uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let { File(it).length() } ?: -1
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) return cursor.getLong(column)
        }
        return -1L
    }

    private fun sourceFormat(uri: Uri): ImageFormat? {
        return when (resolver.getType(uri)?.lowercase(Locale.US)) {
            "image/jpeg", "image/jpg" -> ImageFormat.JPEG
            "image/png" -> ImageFormat.PNG
            "image/webp" -> ImageFormat.WEBP
            else -> when (displayName(uri).substringAfterLast('.', "").lowercase(Locale.US)) {
                "jpg", "jpeg" -> ImageFormat.JPEG
                "png" -> ImageFormat.PNG
                "webp" -> ImageFormat.WEBP
                else -> null
            }
        }
    }

    private fun outputName(source: String, suffix: String, extension: String): String {
        val base = source.substringBeforeLast('.', source).replace(Regex("[^A-Za-z0-9가-힣._-]"), "_")
        return "${base}_${suffix}_${System.currentTimeMillis()}.$extension"
    }

    private fun Throwable.userMessage(): String = when (this) {
        is UserVisibleException -> message ?: "처리하지 못했어요."
        is SecurityException -> "사진 접근 권한이 만료됐어요. 다시 선택해 주세요."
        is OutOfMemoryError -> "사진이 너무 커서 처리하지 못했어요. 더 작은 해상도를 선택해 주세요."
        else -> "처리하지 못했어요."
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }

    private companion object {
        const val MAX_STATIC_LONG_EDGE = 4096
        const val MIN_GIF_LONG_EDGE = 160
        const val MIN_OUTPUT_SIDE = 64
        const val MAX_TARGET_RESIZE_STEPS = 12
        const val DEFAULT_QUALITY = 92
        const val MIN_QUALITY = 12
        const val MAX_QUALITY = 96

        val EXIF_TAGS = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_TIMESTAMP,
        )
    }
}
