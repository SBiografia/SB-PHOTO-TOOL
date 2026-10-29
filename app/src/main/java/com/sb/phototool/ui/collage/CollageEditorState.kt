package com.sb.phototool.ui.collage

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import com.sb.phototool.model.CanvasRatio
import com.sb.phototool.model.CollageJob
import com.sb.phototool.model.ExportSize
import com.sb.phototool.model.JobCodec
import com.sb.phototool.model.TileTransform

@Stable
internal class CollageEditorState(initial: CollageJob) {
    var document by mutableStateOf(initial)
        private set
    private val past = mutableStateListOf<CollageJob>()
    private val future = mutableStateListOf<CollageJob>()
    private var gestureStart: CollageJob? = null
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    fun update(next: CollageJob) {
        finishGesture()
        if (document == next) return
        rememberPrevious(document)
        document = next
    }

    fun beginGesture() {
        if (gestureStart == null) gestureStart = document
    }

    fun drag(next: CollageJob) {
        beginGesture()
        document = next
    }

    fun finishGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        if (start != document) rememberPrevious(start)
    }

    fun undo() {
        finishGesture()
        if (past.isEmpty()) return
        future.add(document)
        document = past.removeAt(past.lastIndex)
    }

    fun redo() {
        finishGesture()
        if (future.isEmpty()) return
        past.add(document)
        document = future.removeAt(future.lastIndex)
    }

    private fun rememberPrevious(previous: CollageJob) {
        past.add(previous)
        if (past.size > 30) past.removeAt(0)
        future.clear()
    }

    companion object {
        val StateSaver = Saver<CollageEditorState, String>(
            save = { JobCodec.encode(it.document) },
            restore = { CollageEditorState(JobCodec.decode(it) as CollageJob) },
        )

        fun create(uris: List<String>) = CollageEditorState(CollageJob(
            id = "draft", tiles = uris.map(::TileTransform), ratio = CanvasRatio.SQUARE,
            gapPercent = .006f, cornerPercent = 0f, backgroundColor = -1, exportSize = ExportSize(),
        ))
    }
}
