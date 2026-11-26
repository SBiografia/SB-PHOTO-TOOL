package com.sb.phototool.model

import com.sb.phototool.media.NormalizedCell
import com.sb.phototool.media.collageLayouts
import com.sb.phototool.media.validCollageCells

/** Layout id of the automatic grid that every photo count always has. */
const val COLLAGE_AUTO_LAYOUT_ID = "auto"
const val COLLAGE_DEFAULT_GAP_PERCENT = .006f
const val COLLAGE_DEFAULT_CORNER_PERCENT = 0f
const val COLLAGE_DEFAULT_MARGIN_PERCENT = 0f
const val COLLAGE_DEFAULT_BORDER_PERCENT = 0f
const val COLLAGE_DEFAULT_COLOR = -1

/** A stored layout choice: the selected preset plus any hand-moved grid lines. */
data class CollageLayoutChoice(
    val layoutId: String = COLLAGE_AUTO_LAYOUT_ID,
    val customCells: List<NormalizedCell> = emptyList(),
)

/**
 * Canvas settings that carry over from one collage to the next.
 *
 * Photo zoom, pan, rotation, crop, shape and filters are deliberately absent:
 * they belong to the photos of one job and would not fit the photos of the
 * next one. A photo background is left out for the same reason.
 */
data class CollageSettings(
    val ratio: CanvasRatio = CanvasRatio.SQUARE,
    val gapPercent: Float = COLLAGE_DEFAULT_GAP_PERCENT,
    val cornerPercent: Float = COLLAGE_DEFAULT_CORNER_PERCENT,
    val backgroundColor: Int = COLLAGE_DEFAULT_COLOR,
    val outerMarginPercent: Float = COLLAGE_DEFAULT_MARGIN_PERCENT,
    val borderWidthPercent: Float = COLLAGE_DEFAULT_BORDER_PERCENT,
    val borderColor: Int = COLLAGE_DEFAULT_COLOR,
    val background: CollageBackground = CollageBackground(),
    val layouts: Map<Int, CollageLayoutChoice> = emptyMap(),
) {
    /**
     * The layout a collage of [photoCount] photos starts with. Layout ids exist
     * per photo count, so a stored choice is only used when it is still a valid
     * layout for this count; otherwise the automatic layout keeps the collage
     * usable instead of leaving a broken grid.
     */
    fun layoutChoice(photoCount: Int): CollageLayoutChoice {
        val stored = layouts[photoCount] ?: return CollageLayoutChoice()
        val cells = stored.customCells.takeIf { validCollageCells(it, photoCount) }.orEmpty()
        val layoutId = stored.layoutId.takeIf { id -> collageLayouts(photoCount).any { it.id == id } }
            ?: COLLAGE_AUTO_LAYOUT_ID
        return CollageLayoutChoice(layoutId, cells)
    }

    /**
     * The settings to remember after editing [job].
     *
     * A template defines its own ratio, spacing and background, so a template
     * job records nothing: browsing templates never replaces the choices a
     * plain collage made. Freeform jobs keep the last grid layout, because their
     * frames belong to their own photos.
     */
    fun rememberFrom(job: CollageJob): CollageSettings {
        if (job.tiles.isEmpty() || job.templateId != null) return this
        val next = copy(
            ratio = job.ratio,
            gapPercent = job.gapPercent,
            cornerPercent = job.cornerPercent,
            backgroundColor = job.backgroundColor,
            outerMarginPercent = job.outerMarginPercent,
            borderWidthPercent = job.borderWidthPercent,
            borderColor = job.borderColor,
            background = job.background.takeUnless { it.kind == BackgroundKind.PHOTO || it.kind == BackgroundKind.BLUR }
                ?: this.background,
        )
        if (job.mode != CollageMode.LAYOUT) return next
        return next.copy(layouts = next.layouts + (job.tiles.size to CollageLayoutChoice(job.layoutId, job.customCells)))
    }
}
