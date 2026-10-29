package com.sb.phototool.ui.editor

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

// Folder selections may contain thousands of URIs: keep them out of the saved-state Bundle.
class ToolSelectionState : ViewModel() {
    val compressionPhotos = mutableStateListOf<Uri>()
    val compressionFolder = mutableStateOf<Uri?>(null)
}
