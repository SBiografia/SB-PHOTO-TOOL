package com.sb.phototool.ui.gallery

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel

internal class GallerySelection : ViewModel() {
    val photos = mutableStateListOf<GalleryPhoto>()
    private var session: String? = null
    private var limit = 1

    fun beginSession(id: String, maxSelection: Int) {
        require(maxSelection > 0)
        if (session != id) photos.clear()
        session = id
        limit = maxSelection
    }

    fun toggle(photo: GalleryPhoto): Boolean = setSelected(photo, photos.none { it.uri == photo.uri })

    fun selectAll(candidates: List<GalleryPhoto>): Int {
        val knownUris = photos.mapTo(mutableSetOf()) { it.uri }
        val remaining = (limit - photos.size).coerceAtLeast(0)
        val added = candidates.asSequence().filter { knownUris.add(it.uri) }.take(remaining).toList()
        photos.addAll(added)
        return added.size
    }

    fun setSelected(photo: GalleryPhoto, select: Boolean): Boolean {
        val index = photos.indexOfFirst { it.uri == photo.uri }
        if (!select) {
            if (index >= 0) photos.removeAt(index)
            return true
        }
        if (index >= 0) return true
        if (limit == 1) photos.clear()
        if (photos.size >= limit) return false
        photos.add(photo)
        return true
    }
}
