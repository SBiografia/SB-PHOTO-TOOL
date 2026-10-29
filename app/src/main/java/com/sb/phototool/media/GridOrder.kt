package com.sb.phototool.media

/**
 * Instagram profiles fill from the bottom-right after the first upload.
 * This returns the tile coordinates in the upload order shown to the user.
 */
fun instagramPostOrder(columns: Int, rows: Int): List<Pair<Int, Int>> = buildList {
    for (row in rows - 1 downTo 0) {
        for (column in columns - 1 downTo 0) {
            add(row to column)
        }
    }
}

