package com.sb.phototool.ui.collage

import com.sb.phototool.model.CanvasRatio
import org.junit.Assert.*
import org.junit.Test

class CollageEditorStateTest {
    @Test
    fun continuousGesture_isOneUndoAndRedoStep() {
        val editor = CollageEditorState.create(listOf("one", "two"))
        val initial = editor.document
        repeat(50) { editor.drag(editor.document.copy(gapPercent = it / 1000f)) }
        editor.finishGesture()
        val changed = editor.document
        assertTrue(editor.canUndo)
        editor.undo()
        assertEquals(initial, editor.document)
        assertFalse(editor.canUndo)
        editor.redo()
        assertEquals(changed, editor.document)
        assertFalse(editor.canRedo)
    }

    @Test
    fun newEditAfterUndo_discardsRedoAndKeepsHistoryBounded() {
        val editor = CollageEditorState.create(listOf("one"))
        editor.update(editor.document.copy(ratio = CanvasRatio.STORY))
        editor.undo()
        editor.update(editor.document.copy(ratio = CanvasRatio.LANDSCAPE))
        assertFalse(editor.canRedo)
        repeat(60) { editor.update(editor.document.copy(backgroundColor = it)) }
        var undoCount = 0
        while (editor.canUndo) { editor.undo(); undoCount++ }
        assertEquals(30, undoCount)
    }
}
