package com.sb.phototool.data

import android.content.Context
import android.net.Uri
import com.sb.phototool.media.collageTemplateWithoutPhotos
import com.sb.phototool.model.CollageJob
import com.sb.phototool.model.JobCodec
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SavedCollage(val id: String, val name: String, val template: Boolean, val savedAt: Long)

/** Only explicit saves create entries. Draft photos are independent private copies. */
class CollageLibrary(private val context: Context) {
    private val root = File(context.filesDir, "collage-library").apply { mkdirs() }
    private fun directory(id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}")))
        return File(root, id).also { require(it.canonicalFile.parentFile == root.canonicalFile) }
    }
    fun list(): List<SavedCollage> = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.mapNotNull { folder ->
        runCatching { val j = JSONObject(File(folder, "entry.json").readText()); SavedCollage(folder.name, j.getString("name"), j.getBoolean("template"), j.getLong("savedAt")) }.getOrNull()
    }.sortedByDescending { it.savedAt }

    fun save(name: String, job: CollageJob, template: Boolean): SavedCollage {
        require(name.isNotBlank() && name.length <= 60)
        require(list().size < 30) { "보관함은 30개까지 저장할 수 있어요. 사용하지 않는 항목을 삭제해 주세요." }
        val entry = SavedCollage(UUID.randomUUID().toString(), name.trim(), template, System.currentTimeMillis())
        val destination = directory(entry.id)
        val staging = File(root, ".${entry.id}").apply { check(mkdir()) }
        try {
            val copies = mutableMapOf<String, String>()
            fun copyPhoto(uri: String): String {
                if (uri.isBlank()) return uri
                return copies.getOrPut(uri) {
                    val filename = "photo-${copies.size}.image"
                    context.contentResolver.openInputStream(Uri.parse(uri)).use { input ->
                        requireNotNull(input) { "사진을 읽을 수 없어요. 사진 권한과 원본을 확인해 주세요." }
                        File(staging, filename).outputStream().use { output -> input.copyTo(output, 131072) }
                    }
                    Uri.fromFile(File(destination, filename)).toString()
                }
            }
            val document = if (template) collageTemplateWithoutPhotos(job) else job.copy(
                id = "draft", tiles = job.tiles.map { it.copy(uri = copyPhoto(it.uri)) },
                background = job.background.copy(uri = if (job.background.kind in listOf(com.sb.phototool.model.BackgroundKind.PHOTO, com.sb.phototool.model.BackgroundKind.BLUR)) copyPhoto(job.background.uri) else ""),
            )
            File(staging, "entry.json").writeText(JSONObject().put("name", entry.name).put("template", template)
                .put("savedAt", entry.savedAt).put("document", JobCodec.encode(document)).toString())
            check(staging.renameTo(destination)) { "보관함 저장을 마치지 못했어요." }
            return entry
        } finally { if (staging.exists()) staging.deleteRecursively() }
    }
    fun load(id: String): CollageJob {
        val job = JobCodec.decode(JSONObject(File(directory(id), "entry.json").readText()).getString("document")) as CollageJob
        val cache = File(context.cacheDir, "collage-open-${UUID.randomUUID()}").apply { check(mkdir()) }
        val copies = mutableMapOf<String, String>()
        fun retain(uri: String): String = if (uri.isBlank()) uri else copies.getOrPut(uri) {
            val source = File(requireNotNull(Uri.parse(uri).path))
            require(source.canonicalFile.parentFile == directory(id).canonicalFile)
            Uri.fromFile(source.copyTo(File(cache, "photo-${copies.size}.image"))).toString()
        }
        return try { job.copy(tiles = job.tiles.map { it.copy(uri = retain(it.uri)) }, background = job.background.copy(uri = retain(job.background.uri))) }
        catch (failure: Exception) { cache.deleteRecursively(); throw failure }
    }
    fun delete(id: String) { val file = directory(id); check(!file.exists() || file.deleteRecursively()) { "보관함 항목을 삭제하지 못했어요." } }
}
