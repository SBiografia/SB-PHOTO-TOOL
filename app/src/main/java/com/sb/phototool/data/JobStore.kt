package com.sb.phototool.data

import android.content.Context
import com.sb.phototool.model.JobCodec
import com.sb.phototool.model.JobSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persists only active work specifications. Files are deleted after terminal
 * completion, so this is not a user-visible job or photo history.
 */
class JobStore(context: Context) {
    private val directory = File(context.filesDir, "active-jobs").apply { mkdirs() }

    suspend fun save(job: JobSpec) = withContext(Dispatchers.IO) {
        File(directory, "${job.id}.json").writeText(JobCodec.encode(job), Charsets.UTF_8)
    }

    suspend fun load(id: String): JobSpec? = withContext(Dispatchers.IO) {
        val file = File(directory, "$id.json")
        if (file.exists()) JobCodec.decode(file.readText(Charsets.UTF_8)) else null
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        File(directory, "$id.json").delete()
    }
}

