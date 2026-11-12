package com.sb.phototool.model

import org.json.JSONArray
import org.json.JSONObject
import com.sb.phototool.media.NormalizedCell

enum class JobType { COMPRESS, COLLAGE, SPLIT, GIF }

enum class ImageFormat(val extension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
}

enum class ResizeMode { NONE, PERCENT, PIXELS }

enum class ConflictPolicy { MAX_SIZE_FIRST, PRESERVE_RESOLUTION }

enum class CanvasRatio(val label: String, val ratio: Float?) {
    ORIGINAL("원본", null),
    SQUARE("1:1", 1f),
    PORTRAIT("4:5", 4f / 5f),
    STORY("9:16", 9f / 16f),
    LANDSCAPE("16:9", 16f / 9f),
    TWO_THREE("2:3", 2f / 3f),
    THREE_TWO("3:2", 3f / 2f),
    THREE_FOUR("3:4", 3f / 4f),
    FOUR_THREE("4:3", 4f / 3f),
    HALF("1:2", .5f),
    DOUBLE("2:1", 2f),
}

enum class ExportSizeMode { ORIGINAL, SOCIAL_1080, CUSTOM }

enum class CollageMode { LAYOUT, FREEFORM }

enum class SplitFitMode { FILL, FIT }

enum class GifOutputFormat { GIF, MP4 }

data class ExportSize(
    val mode: ExportSizeMode = ExportSizeMode.ORIGINAL,
    val customLongEdge: Int = 2048,
)

data class TileTransform(
    val uri: String,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val freeform: NormalizedCell? = null,
    val layer: Int = 0,
    val layoutTransform: PhotoTransformState? = null,
    val freeformTransform: PhotoTransformState? = null,
    val elementId: String = "",
    val frameRotation: Float = 0f,
    val shape: PhotoShape = PhotoShape.RECTANGLE,
    val matte: Float = 0f,
    val bottomMatte: Float = 0f,
    val frameColor: Int = -1,
    val locked: Boolean = false,
    val fitInside: Boolean = false,
    val adjustments: PhotoAdjustments = PhotoAdjustments(),
)

data class PhotoTransformState(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
)

sealed interface JobSpec {
    val id: String
    val type: JobType
}

data class CompressJob(
    override val id: String,
    val uris: List<String>,
    val outputFormat: ImageFormat,
    val resizeMode: ResizeMode,
    val percent: Int,
    val targetLongEdge: Int,
    val maxBytes: Long,
    val conflictPolicy: ConflictPolicy,
    val keepMetadata: Boolean,
) : JobSpec {
    override val type = JobType.COMPRESS
}

data class CollageJob(
    override val id: String,
    val tiles: List<TileTransform>,
    val ratio: CanvasRatio,
    val gapPercent: Float,
    val cornerPercent: Float,
    val backgroundColor: Int,
    val exportSize: ExportSize,
    val layoutId: String = "auto",
    val customCells: List<NormalizedCell> = emptyList(),
    val outerMarginPercent: Float = 0f,
    val borderWidthPercent: Float = 0f,
    val borderColor: Int = -1,
    val mode: CollageMode = CollageMode.LAYOUT,
    val freeformCanvasAspect: Float? = null,
    val background: CollageBackground = CollageBackground(),
    val texts: List<CollageText> = emptyList(),
    val decorations: List<CollageDecoration> = emptyList(),
    val templateId: String? = null,
    val placementLocked: Boolean = false,
    val snapping: Boolean = true,
) : JobSpec {
    override val type = JobType.COLLAGE
}

data class SplitJob(
    override val id: String,
    val uri: String,
    val columns: Int,
    val rows: Int,
    val fitMode: SplitFitMode,
    val backgroundColor: Int,
    val useBlurBackground: Boolean,
    val exportSize: ExportSize,
    val crop: NormalizedCell? = null,
) : JobSpec {
    override val type = JobType.SPLIT
}

data class GifJob(
    override val id: String,
    val uris: List<String>,
    val outputFormat: GifOutputFormat,
    val fps: Int,
    val targetLongEdge: Int,
    val loopCount: Int,
    val photoDelayMs: Int,
    val backgroundColor: Int,
) : JobSpec {
    override val type = JobType.GIF
}

object JobCodec {
    fun encode(job: JobSpec): String {
        val root = JSONObject().put("id", job.id).put("type", job.type.name)
        when (job) {
            is CompressJob -> root.apply {
                putUris(job.uris)
                put("format", job.outputFormat.name)
                put("resizeMode", job.resizeMode.name)
                put("percent", job.percent)
                put("targetLongEdge", job.targetLongEdge)
                put("maxBytes", job.maxBytes)
                put("conflictPolicy", job.conflictPolicy.name)
                put("keepMetadata", job.keepMetadata)
            }

            is CollageJob -> root.apply {
                put("design", CollageDesignCodec.encode(job))
                put("mode", job.mode.name)
                job.freeformCanvasAspect?.let { put("freeformCanvasAspect", it.toDouble()) }
                put("layoutId", job.layoutId)
                put("outerMarginPercent", job.outerMarginPercent.toDouble())
                put("borderWidthPercent", job.borderWidthPercent.toDouble())
                put("borderColor", job.borderColor)
                put("customCells", JSONArray().apply {
                    job.customCells.forEach { cell ->
                        put(JSONArray(listOf(cell.left, cell.top, cell.right, cell.bottom)))
                    }
                })
                put("ratio", job.ratio.name)
                put("gapPercent", job.gapPercent.toDouble())
                put("cornerPercent", job.cornerPercent.toDouble())
                put("backgroundColor", job.backgroundColor)
                putExportSize(job.exportSize)
                put("tiles", JSONArray().apply {
                    job.tiles.forEach { tile ->
                        put(
                            JSONObject()
                                .put("uri", tile.uri)
                                .put("zoom", tile.zoom.toDouble())
                                .put("panX", tile.panX.toDouble())
                                .put("panY", tile.panY.toDouble())
                                .put("quarterTurns", tile.quarterTurns)
                                .put("flipHorizontal", tile.flipHorizontal)
                                .put("flipVertical", tile.flipVertical)
                                .put("layer", tile.layer)
                                .put("elementId", tile.elementId)
                                .put("frameRotation", tile.frameRotation.toDouble())
                                .put("shape", tile.shape.name)
                                .put("matte", tile.matte.toDouble())
                                .put("bottomMatte", tile.bottomMatte.toDouble())
                                .put("frameColor", tile.frameColor)
                                .apply {
                                    tile.layoutTransform?.let { put("layoutTransform", it.toJson()) }
                                    tile.freeformTransform?.let { put("freeformTransform", it.toJson()) }
                                }
                                .apply { tile.freeform?.let { put("freeform", JSONArray(listOf(it.left, it.top, it.right, it.bottom))) } },
                        )
                    }
                })
            }

            is SplitJob -> root.apply {
                put("uri", job.uri)
                put("columns", job.columns)
                put("rows", job.rows)
                put("fitMode", job.fitMode.name)
                put("backgroundColor", job.backgroundColor)
                put("useBlurBackground", job.useBlurBackground)
                putExportSize(job.exportSize)
                job.crop?.let { put("crop", JSONArray(listOf(it.left, it.top, it.right, it.bottom))) }
            }

            is GifJob -> root.apply {
                putUris(job.uris)
                put("outputFormat", job.outputFormat.name)
                put("fps", job.fps)
                put("targetLongEdge", job.targetLongEdge)
                put("loopCount", job.loopCount)
                put("photoDelayMs", job.photoDelayMs)
                put("backgroundColor", job.backgroundColor)
            }
        }
        return root.toString()
    }

    fun decode(raw: String): JobSpec {
        val root = JSONObject(raw)
        val id = root.getString("id")
        return when (JobType.valueOf(root.getString("type"))) {
            JobType.COMPRESS -> CompressJob(
                id = id,
                uris = root.uris(),
                outputFormat = ImageFormat.valueOf(root.getString("format")),
                resizeMode = ResizeMode.valueOf(root.getString("resizeMode")),
                percent = root.getInt("percent"),
                targetLongEdge = root.getInt("targetLongEdge"),
                maxBytes = root.getLong("maxBytes"),
                conflictPolicy = ConflictPolicy.valueOf(root.getString("conflictPolicy")),
                keepMetadata = root.getBoolean("keepMetadata"),
            )

            JobType.COLLAGE -> CollageJob(
                id = id,
                tiles = root.getJSONArray("tiles").let { array ->
                    List(array.length()) { index ->
                        array.getJSONObject(index).let { tile ->
                            TileTransform(
                                uri = tile.getString("uri"),
                                zoom = tile.getDouble("zoom").toFloat(),
                                panX = tile.getDouble("panX").toFloat(),
                                panY = tile.getDouble("panY").toFloat(),
                                quarterTurns = tile.optInt("quarterTurns", 0),
                                flipHorizontal = tile.optBoolean("flipHorizontal", false),
                                flipVertical = tile.optBoolean("flipVertical", false),
                                freeform = tile.optJSONArray("freeform")?.let {
                                    NormalizedCell(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(),
                                        it.getDouble(2).toFloat(), it.getDouble(3).toFloat())
                                },
                                layer = tile.optInt("layer", 0),
                                elementId = tile.optString("elementId", ""),
                                frameRotation = tile.optDouble("frameRotation", 0.0).toFloat(),
                                shape = PhotoShape.entries.firstOrNull { it.name == tile.optString("shape") } ?: PhotoShape.RECTANGLE,
                                matte = tile.optDouble("matte", 0.0).toFloat(),
                                bottomMatte = tile.optDouble("bottomMatte", 0.0).toFloat(),
                                frameColor = tile.optInt("frameColor", -1),
                                layoutTransform = tile.optJSONObject("layoutTransform")?.photoTransform(),
                                freeformTransform = tile.optJSONObject("freeformTransform")?.photoTransform(),
                            )
                        }
                    }
                },
                ratio = CanvasRatio.valueOf(root.getString("ratio")),
                gapPercent = root.getDouble("gapPercent").toFloat(),
                cornerPercent = root.getDouble("cornerPercent").toFloat(),
                backgroundColor = root.getInt("backgroundColor"),
                exportSize = root.exportSize(),
                layoutId = root.optString("layoutId", "auto"),
                customCells = root.optJSONArray("customCells")?.let { cells ->
                    List(cells.length()) { index ->
                        val cell = cells.getJSONArray(index)
                        NormalizedCell(cell.getDouble(0).toFloat(), cell.getDouble(1).toFloat(),
                            cell.getDouble(2).toFloat(), cell.getDouble(3).toFloat())
                    }
                }.orEmpty(),
                outerMarginPercent = root.optDouble("outerMarginPercent", 0.0).toFloat(),
                borderWidthPercent = root.optDouble("borderWidthPercent", 0.0).toFloat(),
                borderColor = root.optInt("borderColor", -1),
                mode = CollageMode.valueOf(root.optString("mode", CollageMode.LAYOUT.name)),
                freeformCanvasAspect = if (root.has("freeformCanvasAspect")) root.getDouble("freeformCanvasAspect").toFloat() else null,
            ).let { CollageDesignCodec.decode(it, root.optJSONObject("design")) }

            JobType.SPLIT -> SplitJob(
                id = id,
                uri = root.getString("uri"),
                columns = root.getInt("columns"),
                rows = root.getInt("rows"),
                fitMode = SplitFitMode.valueOf(root.getString("fitMode")),
                backgroundColor = root.getInt("backgroundColor"),
                useBlurBackground = root.getBoolean("useBlurBackground"),
                exportSize = root.exportSize(),
                crop = root.optJSONArray("crop")?.let { NormalizedCell(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(), it.getDouble(3).toFloat()) },
            )

            JobType.GIF -> GifJob(
                id = id,
                uris = root.uris(),
                // Older queued photo GIF jobs do not have outputFormat yet.
                // Their obsolete video mode fields are intentionally ignored.
                outputFormat = GifOutputFormat.valueOf(root.optString("outputFormat", GifOutputFormat.GIF.name)),
                fps = root.optInt("fps", 12),
                targetLongEdge = root.getInt("targetLongEdge"),
                loopCount = root.getInt("loopCount"),
                photoDelayMs = root.getInt("photoDelayMs"),
                backgroundColor = root.getInt("backgroundColor"),
            )
        }
    }

    private fun PhotoTransformState.toJson() = JSONObject()
        .put("zoom", zoom.toDouble()).put("panX", panX.toDouble()).put("panY", panY.toDouble())
        .put("quarterTurns", quarterTurns).put("flipHorizontal", flipHorizontal).put("flipVertical", flipVertical)

    private fun JSONObject.photoTransform() = PhotoTransformState(
        optDouble("zoom", 1.0).toFloat(), optDouble("panX", 0.0).toFloat(), optDouble("panY", 0.0).toFloat(),
        optInt("quarterTurns", 0), optBoolean("flipHorizontal", false), optBoolean("flipVertical", false),
    )

    private fun JSONObject.putUris(uris: List<String>) {
        put("uris", JSONArray().apply { uris.forEach(::put) })
    }

    private fun JSONObject.uris(): List<String> {
        val array = getJSONArray("uris")
        return List(array.length()) { array.getString(it) }
    }

    private fun JSONObject.putExportSize(size: ExportSize) {
        put("exportSizeMode", size.mode.name)
        put("customLongEdge", size.customLongEdge)
    }

    private fun JSONObject.exportSize() = ExportSize(
        mode = ExportSizeMode.valueOf(getString("exportSizeMode")),
        customLongEdge = getInt("customLongEdge"),
    )
}
