package com.sb.phototool.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Only the latest requested compression's successful originals, never source/output pairs. */
internal class CompressionOriginalStore(context: Context) {
    // Keep the existing record so completed compressions remain usable after an update.
    private val file = AtomicFile(File(context.filesDir, "compression-trash.json"))

    suspend fun begin(jobId: String) = withContext(Dispatchers.IO) {
        lock.withLock { write(jobId, emptyList()) }
    }

    suspend fun complete(jobId: String, originals: List<String>): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (readRecord()?.getString("jobId") != jobId) return@withLock false
            write(jobId, originals.distinct())
            true
        }
    }

    suspend fun load(jobId: String): List<String> = withContext(Dispatchers.IO) {
        lock.withLock { read(jobId) }
    }

    suspend fun removeConfirmed(jobId: String, originals: Set<String>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = read(jobId)
            if (current.isNotEmpty()) {
                val remaining = current.filterNot { it in originals }
                if (remaining.isEmpty()) file.delete() else write(jobId, remaining)
            }
        }
    }

    private fun read(jobId: String): List<String> {
        val record = readRecord() ?: return emptyList()
        if (record.getString("jobId") != jobId) return emptyList()
        val uris = record.getJSONArray("originals")
        return List(uris.length()) { uris.getString(it) }.distinct()
    }

    private fun readRecord(): JSONObject? = try {
        JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
    } catch (_: java.io.FileNotFoundException) { null }

    private fun write(jobId: String, originals: List<String>) {
        val bytes = JSONObject().put("jobId", jobId).put("originals", JSONArray(originals)).toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
    }

    private companion object { val lock = Mutex() }
}
