package com.sb.phototool.data

import android.content.Context
import com.sb.phototool.media.MAX_COLLAGE_PHOTOS
import com.sb.phototool.media.NormalizedCell
import com.sb.phototool.model.BackgroundKind
import com.sb.phototool.model.BackgroundPattern
import com.sb.phototool.model.COLLAGE_AUTO_LAYOUT_ID
import com.sb.phototool.model.COLLAGE_DEFAULT_BORDER_PERCENT
import com.sb.phototool.model.COLLAGE_DEFAULT_COLOR
import com.sb.phototool.model.COLLAGE_DEFAULT_CORNER_PERCENT
import com.sb.phototool.model.COLLAGE_DEFAULT_GAP_PERCENT
import com.sb.phototool.model.COLLAGE_DEFAULT_MARGIN_PERCENT
import com.sb.phototool.model.CanvasRatio
import com.sb.phototool.model.CollageBackground
import com.sb.phototool.model.CollageLayoutChoice
import com.sb.phototool.model.CollageSettings
import org.json.JSONArray

/**
 * Keeps the canvas choices of the last collage in app-private preferences so
 * the next collage starts the same way.
 *
 * Only canvas settings live here. Photo URIs, photo transforms and bitmap data
 * never reach this file, and nothing leaves the device.
 */
internal class CollageSettingsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): CollageSettings = runCatching {
        CollageSettings(
            ratio = CanvasRatio.entries.firstOrNull { it.name == preferences.getString(KEY_RATIO, null) }
                ?: CanvasRatio.SQUARE,
            gapPercent = rememberedFloat(KEY_GAP, COLLAGE_DEFAULT_GAP_PERCENT, MAX_GAP),
            cornerPercent = rememberedFloat(KEY_CORNER, COLLAGE_DEFAULT_CORNER_PERCENT, MAX_CORNER),
            backgroundColor = preferences.getInt(KEY_BACKGROUND_COLOR, COLLAGE_DEFAULT_COLOR),
            outerMarginPercent = rememberedFloat(KEY_OUTER_MARGIN, COLLAGE_DEFAULT_MARGIN_PERCENT, MAX_MARGIN),
            borderWidthPercent = rememberedFloat(KEY_BORDER_WIDTH, COLLAGE_DEFAULT_BORDER_PERCENT, MAX_BORDER),
            borderColor = preferences.getInt(KEY_BORDER_COLOR, COLLAGE_DEFAULT_COLOR),
            background = loadBackground(),
            layouts = (1..MAX_COLLAGE_PHOTOS).mapNotNull { count -> loadLayout(count)?.let { count to it } }.toMap(),
        )
    }.getOrElse { CollageSettings() }

    fun save(settings: CollageSettings) {
        val editor = preferences.edit()
            .putString(KEY_RATIO, settings.ratio.name)
            .putFloat(KEY_GAP, settings.gapPercent)
            .putFloat(KEY_CORNER, settings.cornerPercent)
            .putInt(KEY_BACKGROUND_COLOR, settings.backgroundColor)
            .putFloat(KEY_OUTER_MARGIN, settings.outerMarginPercent)
            .putFloat(KEY_BORDER_WIDTH, settings.borderWidthPercent)
            .putInt(KEY_BORDER_COLOR, settings.borderColor)
            .putString(KEY_BACKGROUND_KIND, settings.background.kind.name)
            .putInt(KEY_BACKGROUND_SECONDARY_COLOR, settings.background.secondaryColor)
            .putFloat(KEY_BACKGROUND_ANGLE, settings.background.angle)
            .putString(KEY_BACKGROUND_PATTERN, settings.background.pattern.name)
        for (count in 1..MAX_COLLAGE_PHOTOS) {
            val choice = settings.layouts[count]
            if (choice == null) {
                editor.remove(layoutKey(count)).remove(cellsKey(count))
                continue
            }
            editor.putString(layoutKey(count), choice.layoutId.ifBlank { COLLAGE_AUTO_LAYOUT_ID })
            if (choice.customCells.isEmpty()) editor.remove(cellsKey(count))
            else editor.putString(cellsKey(count), encodeCells(choice.customCells))
        }
        editor.apply()
    }

    /** A stored value outside the slider range would show a setting the UI cannot produce. */
    private fun rememberedFloat(key: String, default: Float, maximum: Float): Float =
        preferences.getFloat(key, default).takeIf { it.isFinite() }?.coerceIn(0f, maximum) ?: default

    /** Photo and blur backgrounds belong to one collage's photos, so they are never restored. */
    private fun loadBackground(): CollageBackground {
        val fallback = CollageBackground()
        val kind = BackgroundKind.entries.firstOrNull { it.name == preferences.getString(KEY_BACKGROUND_KIND, null) }
            ?.takeUnless { it == BackgroundKind.PHOTO || it == BackgroundKind.BLUR }
            ?: BackgroundKind.SOLID
        val pattern = BackgroundPattern.entries
            .firstOrNull { it.name == preferences.getString(KEY_BACKGROUND_PATTERN, null) } ?: fallback.pattern
        val angle = preferences.getFloat(KEY_BACKGROUND_ANGLE, fallback.angle).takeIf { it.isFinite() }
            ?.coerceIn(0f, 360f) ?: fallback.angle
        return fallback.copy(
            kind = kind,
            secondaryColor = preferences.getInt(KEY_BACKGROUND_SECONDARY_COLOR, fallback.secondaryColor),
            angle = angle,
            pattern = pattern,
        )
    }

    private fun loadLayout(count: Int): CollageLayoutChoice? {
        val layoutId = preferences.getString(layoutKey(count), null)?.takeIf { it.isNotBlank() } ?: return null
        val cells = preferences.getString(cellsKey(count), null)?.let(::decodeCells).orEmpty()
        return CollageLayoutChoice(layoutId, cells)
    }

    private fun encodeCells(cells: List<NormalizedCell>) = JSONArray().apply {
        cells.forEach { cell -> put(JSONArray(listOf(cell.left, cell.top, cell.right, cell.bottom))) }
    }.toString()

    private fun decodeCells(raw: String): List<NormalizedCell> = runCatching {
        val array = JSONArray(raw)
        List(array.length()) { index ->
            val cell = array.getJSONArray(index)
            NormalizedCell(cell.getDouble(0).toFloat(), cell.getDouble(1).toFloat(),
                cell.getDouble(2).toFloat(), cell.getDouble(3).toFloat())
        }
    }.getOrDefault(emptyList())

    private fun layoutKey(count: Int) = "layout_$count"

    private fun cellsKey(count: Int) = "cells_$count"

    private companion object {
        const val PREFERENCES_NAME = "collage_settings"
        const val KEY_RATIO = "ratio"
        const val KEY_GAP = "gap"
        const val KEY_CORNER = "corner"
        const val KEY_BACKGROUND_COLOR = "background_color"
        const val KEY_OUTER_MARGIN = "outer_margin"
        const val KEY_BORDER_WIDTH = "border_width"
        const val KEY_BORDER_COLOR = "border_color"
        const val KEY_BACKGROUND_KIND = "background_kind"
        const val KEY_BACKGROUND_SECONDARY_COLOR = "background_secondary_color"
        const val KEY_BACKGROUND_ANGLE = "background_angle"
        const val KEY_BACKGROUND_PATTERN = "background_pattern"
        const val MAX_GAP = .08f
        const val MAX_CORNER = .12f
        const val MAX_MARGIN = .12f
        const val MAX_BORDER = .04f
    }
}
