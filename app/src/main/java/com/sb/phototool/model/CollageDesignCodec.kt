package com.sb.phototool.model

import com.sb.phototool.media.NormalizedCell
import org.json.JSONArray
import org.json.JSONObject

internal object CollageDesignCodec {
    private fun NormalizedCell.json() = JSONArray(listOf(left, top, right, bottom))
    private fun JSONObject.frame() = getJSONArray("frame").let { NormalizedCell(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(), it.getDouble(3).toFloat()) }
    private inline fun <reified T : Enum<T>> JSONObject.enum(key: String, default: T): T = enumValues<T>().firstOrNull { it.name == optString(key) } ?: default
    private fun JSONObject.number(key: String, default: Float) = optDouble(key, default.toDouble()).toFloat().takeIf { it.isFinite() } ?: default

    fun encode(job: CollageJob) = JSONObject().apply {
        put("version", 2)
        put("photoEdits", JSONArray().apply { job.tiles.forEach { p -> put(JSONObject().apply {
            put("locked", p.locked); put("fitInside", p.fitInside)
            put("brightness", p.adjustments.brightness.toDouble()); put("contrast", p.adjustments.contrast.toDouble())
            put("saturation", p.adjustments.saturation.toDouble()); put("filter", p.adjustments.filter.name); put("intensity", p.adjustments.intensity.toDouble())
        }) } })
        put("templateId", job.templateId ?: JSONObject.NULL)
        put("placementLocked", job.placementLocked)
        put("snapping", job.snapping)
        put("background", JSONObject().apply {
            put("kind", job.background.kind.name); put("secondaryColor", job.background.secondaryColor)
            put("angle", job.background.angle.toDouble()); put("pattern", job.background.pattern.name)
            put("uri", job.background.uri); put("blur", job.background.blur.toDouble())
        })
        put("texts", JSONArray().apply { job.texts.forEach { t -> put(JSONObject().apply {
            put("id", t.id); put("text", t.text); put("frame", t.frame.json()); put("rotation", t.rotation.toDouble())
            put("layer", t.layer); put("color", t.color); put("font", t.font.name); put("size", t.size.toDouble())
            put("alignment", t.alignment.name); put("outline", t.outline.toDouble()); put("outlineColor", t.outlineColor); put("shadow", t.shadow)
            put("bold", t.bold); put("italic", t.italic); put("underline", t.underline); put("strikeThrough", t.strikeThrough)
            put("letterSpacing", t.letterSpacing.toDouble()); put("lineSpacing", t.lineSpacing.toDouble())
        }) } })
        put("decorations", JSONArray().apply { job.decorations.forEach { d -> put(JSONObject().apply {
            put("id", d.id); put("kind", d.kind.name); put("frame", d.frame.json()); put("rotation", d.rotation.toDouble())
            put("layer", d.layer); put("color", d.color); put("opacity", d.opacity.toDouble())
        }) } })
    }

    fun decode(job: CollageJob, json: JSONObject?): CollageJob {
        if (json == null) return job
        val bg = json.optJSONObject("background") ?: JSONObject()
        val ts = json.optJSONArray("texts") ?: JSONArray()
        val ds = json.optJSONArray("decorations") ?: JSONArray()
        return job.copy(
            tiles = job.tiles.mapIndexed { i, p -> json.optJSONArray("photoEdits")?.optJSONObject(i)?.let { edit ->
                p.copy(locked = edit.optBoolean("locked", false), fitInside = edit.optBoolean("fitInside", false),
                    adjustments = PhotoAdjustments(edit.number("brightness", 0f).coerceIn(-1f, 1f), edit.number("contrast", 1f).coerceIn(.5f, 1.5f),
                        edit.number("saturation", 1f).coerceIn(0f, 2f), edit.enum("filter", PhotoFilter.NONE), edit.number("intensity", 1f).coerceIn(0f, 1f)))
            } ?: p },
            templateId = if (json.isNull("templateId")) null else json.optString("templateId").takeIf { it.isNotBlank() },
            placementLocked = json.optBoolean("placementLocked", false), snapping = json.optBoolean("snapping", true),
            background = CollageBackground(bg.enum("kind", BackgroundKind.SOLID), bg.optInt("secondaryColor", 0xFFE4D7C4.toInt()),
                bg.number("angle", 90f), bg.enum("pattern", BackgroundPattern.PAPER), bg.optString("uri", ""), bg.number("blur", .5f)),
            texts = List(ts.length()) { index -> ts.getJSONObject(index).let { t -> CollageText(
                t.getString("id"), t.optString("text", ""), t.frame(), t.number("rotation", 0f), t.optInt("layer", 100),
                t.optInt("color", 0xFF262626.toInt()), t.enum("font", CollageFont.SANS), t.number("size", .055f),
                t.enum("alignment", TextAlignment.CENTER), t.number("outline", 0f), t.optInt("outlineColor", -1), t.optBoolean("shadow", false),
                t.optBoolean("bold"), t.optBoolean("italic"), t.optBoolean("underline"), t.optBoolean("strikeThrough"),
                t.number("letterSpacing", 0f).coerceIn(-.05f, .3f), t.number("lineSpacing", 1f).coerceIn(.7f, 2f)) } },
            decorations = List(ds.length()) { index -> ds.getJSONObject(index).let { d -> CollageDecoration(
                d.getString("id"), d.enum("kind", DecorationKind.TAPE), d.frame(), d.number("rotation", 0f), d.optInt("layer", 50),
                d.optInt("color", 0xFFD5BB91.toInt()), d.number("opacity", 1f)) } },
        )
    }
}
