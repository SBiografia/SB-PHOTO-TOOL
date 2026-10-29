package com.sb.phototool.media

import kotlin.math.abs

const val MAX_COLLAGE_PHOTOS = 9

data class NormalizedCell(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * The same automatic layouts are used by the on-screen preview and the
 * exported collage, so the arrangement never changes after saving.
 */
fun collageCells(count: Int): List<NormalizedCell> = when (count) {
    1 -> listOf(NormalizedCell(0f, 0f, 1f, 1f))
    2 -> listOf(NormalizedCell(0f, 0f, .5f, 1f), NormalizedCell(.5f, 0f, 1f, 1f))
    3 -> listOf(
        NormalizedCell(0f, 0f, .5f, 1f),
        NormalizedCell(.5f, 0f, 1f, .5f),
        NormalizedCell(.5f, .5f, 1f, 1f),
    )

    4 -> regularCells(2, 2)
    5 -> rowsOfCells(2, 3)
    6 -> regularCells(3, 2)
    7 -> rowsOfCells(3, 4)
    8 -> regularCells(4, 2)
    MAX_COLLAGE_PHOTOS -> regularCells(3, 3)
    else -> emptyList()
}

private fun regularCells(columns: Int, rows: Int): List<NormalizedCell> = buildList {
    repeat(rows) { row ->
        repeat(columns) { column ->
            add(
                NormalizedCell(
                    left = column.toFloat() / columns,
                    top = row.toFloat() / rows,
                    right = (column + 1f) / columns,
                    bottom = (row + 1f) / rows,
                ),
            )
        }
    }
}

private fun rowsOfCells(firstRow: Int, secondRow: Int): List<NormalizedCell> = buildList {
    repeat(firstRow) { column ->
        add(NormalizedCell(column.toFloat() / firstRow, 0f, (column + 1f) / firstRow, .5f))
    }
    repeat(secondRow) { column ->
        add(NormalizedCell(column.toFloat() / secondRow, .5f, (column + 1f) / secondRow, 1f))
    }
}

enum class CollageLayoutFamily(val label: String) { BASIC("기본"), FEATURE("강조"), MOSAIC("모자이크") }

data class CollageLayout(val id: String, val label: String, val cells: List<NormalizedCell>,
    val family: CollageLayoutFamily = CollageLayoutFamily.BASIC)

private val layoutCatalog by lazy { (1..MAX_COLLAGE_PHOTOS).associateWith(::buildCollageLayouts) }

fun collageLayouts(count: Int): List<CollageLayout> = layoutCatalog[count].orEmpty()

private fun buildCollageLayouts(count: Int): List<CollageLayout> {
    if (count !in 1..MAX_COLLAGE_PHOTOS) return emptyList()
    val candidates = mutableListOf(CollageLayout("auto", "기본", collageCells(count)))
    fun add(id: String, label: String, cells: List<NormalizedCell>, family: CollageLayoutFamily = CollageLayoutFamily.BASIC) {
        if (candidates.none { it.cells == cells }) candidates += CollageLayout(id, label, cells, family)
    }
    if (count == 1) return candidates
    fun bands(sizes: List<Int>): List<NormalizedCell> = sizes.flatMapIndexed { row, columns ->
        List(columns) { column ->
            NormalizedCell(column.toFloat() / columns, row.toFloat() / sizes.size,
                (column + 1f) / columns, (row + 1f) / sizes.size)
        }
    }
    fun transpose(cells: List<NormalizedCell>) = cells.map { NormalizedCell(it.top, it.left, it.bottom, it.right) }
    val arrangements = buildList {
        add(listOf(count))
        add(List(count) { 1 })
        for (first in 1 until count) {
            if (first <= 5 && count - first <= 5) add(listOf(first, count - first))
        }
        if (count >= 5) {
            val side = (count - 1) / 2
            add(listOf(side, count - side * 2, side))
        }
        if (count % 3 == 0) add(List(3) { count / 3 })
    }
    arrangements.forEachIndexed { index, sizes ->
        add("rows-$index", "가로 ${index + 1}", bands(sizes))
        add("cols-$index", "세로 ${index + 1}", transpose(bands(sizes)))
    }
    if (count >= 2) {
        val sideCount = count - 1
        val hero = listOf(NormalizedCell(0f, 0f, .62f, 1f)) + List(sideCount) { row ->
            NormalizedCell(.62f, row.toFloat() / sideCount, 1f, (row + 1f) / sideCount)
        }
        add("hero-left", "큰 사진 왼쪽", hero, CollageLayoutFamily.FEATURE)
        add("hero-top", "큰 사진 위", transpose(hero), CollageLayoutFamily.FEATURE)
        if (count % 2 == 1) {
            val perSide = count / 2
            val center = listOf(NormalizedCell(1f / 3f, 0f, 2f / 3f, 1f)) +
                List(perSide) { row -> NormalizedCell(0f, row.toFloat() / perSide, 1f / 3f, (row + 1f) / perSide) } +
                List(perSide) { row -> NormalizedCell(2f / 3f, row.toFloat() / perSide, 1f, (row + 1f) / perSide) }
            add("hero-center", "큰 사진 가운데", center, CollageLayoutFamily.FEATURE)
        }
    }
    // Keep original IDs and geometry stable for already queued jobs and restored drafts.
    fun signature(cells: List<NormalizedCell>) = cells.map { cell ->
        listOf(cell.left, cell.top, cell.right, cell.bottom).map { kotlin.math.round(it * 10000).toInt() }.joinToString(",")
    }.sorted().joinToString(";")
    val seen = candidates.map { signature(it.cells) }.toMutableSet()
    fun variants(id: String, label: String, cells: List<NormalizedCell>, family: CollageLayoutFamily) {
        var rotated = cells
        repeat(4) { turn ->
            if (validCollageCells(rotated, count) && seen.add(signature(rotated))) {
                candidates += CollageLayout("$id-$turn", "$label ${turn + 1}", rotated, family)
            }
            rotated = rotated.map { NormalizedCell(1 - it.bottom, it.left, 1 - it.top, it.right) }
        }
    }
    fun divide(rect: NormalizedCell, pieces: Int, vertical: Boolean): List<NormalizedCell> = List(pieces) { i ->
        if (vertical) rect.copy(left = rect.left + (rect.right - rect.left) * i / pieces,
            right = rect.left + (rect.right - rect.left) * (i + 1) / pieces)
        else rect.copy(top = rect.top + (rect.bottom - rect.top) * i / pieces,
            bottom = rect.top + (rect.bottom - rect.top) * (i + 1) / pieces)
    }
    variants("feature-wide", "와이드 강조", listOf(NormalizedCell(0f, 0f, .7f, 1f)) +
        divide(NormalizedCell(.7f, 0f, 1f, 1f), count - 1, false), CollageLayoutFamily.FEATURE)
    if (count >= 3) {
        val upper = count / 2
        val weighted = divide(NormalizedCell(0f, 0f, 1f, .36f), upper, true) +
            divide(NormalizedCell(0f, .36f, 1f, 1f), count - upper, true)
        variants("editorial", "매거진", weighted, CollageLayoutFamily.MOSAIC)
        val right = (count - 1) / 2
        variants("corner", "모서리 강조", listOf(NormalizedCell(0f, 0f, .64f, .64f)) +
            divide(NormalizedCell(.64f, 0f, 1f, .64f), right, false) +
            divide(NormalizedCell(0f, .64f, 1f, 1f), count - 1 - right, true), CollageLayoutFamily.FEATURE)
    }
    if (count >= 4) {
        // Independent row heights create staggered T-junctions rather than a uniform grid.
        val leftCount = count / 2
        val rightCount = count - leftCount
        fun staggeredColumn(left: Float, right: Float, pieces: Int, firstHeight: Float): List<NormalizedCell> =
            listOf(NormalizedCell(left, 0f, right, firstHeight)) +
                divide(NormalizedCell(left, firstHeight, right, 1f), pieces - 1, false)
        variants("staggered", "엇갈린 모자이크",
            staggeredColumn(0f, .42f, leftCount, .38f) + staggeredColumn(.42f, 1f, rightCount, .62f), CollageLayoutFamily.MOSAIC)
        val side = count - 1
        val rows = side / 2
        val sideGrid = (0 until rows).flatMap { row ->
            divide(NormalizedCell(.56f, row.toFloat() / rows, 1f, (row + 1f) / rows),
                if (row == rows - 1) side - (rows - 1) * 2 else 2, true)
        }
        variants("gallery", "큰 사진과 갤러리", listOf(NormalizedCell(0f, 0f, .56f, 1f)) + sideGrid, CollageLayoutFamily.FEATURE)
    }
    if (count >= 5) {
        val spiral = mutableListOf(
            NormalizedCell(.28f, .28f, .72f, .72f),
            NormalizedCell(0f, 0f, .72f, .28f),
            NormalizedCell(.72f, 0f, 1f, .72f),
            NormalizedCell(.28f, .72f, 1f, 1f),
            NormalizedCell(0f, .28f, .28f, 1f),
        )
        repeat(count - 5) { i ->
            val slot = 1 + i * 2
            val cell = spiral.removeAt(slot)
            spiral.addAll(slot, divide(cell, 2, cell.right - cell.left > cell.bottom - cell.top))
        }
        variants("pinwheel", "바람개비", spiral, CollageLayoutFamily.MOSAIC)
        val surround = listOf(NormalizedCell(.25f, .25f, .75f, .75f)) +
            divide(NormalizedCell(0f, 0f, 1f, .25f), 1 + (count - 5 + 3) / 4, true) +
            divide(NormalizedCell(0f, .75f, 1f, 1f), 1 + (count - 5 + 2) / 4, true) +
            divide(NormalizedCell(0f, .25f, .25f, .75f), 1 + (count - 5 + 1) / 4, false) +
            divide(NormalizedCell(.75f, .25f, 1f, .75f), 1 + (count - 5) / 4, false)
        variants("surround", "중앙 프레임", surround, CollageLayoutFamily.FEATURE)
    }
    return candidates
}

fun resolvedCollageCells(count: Int, layoutId: String, customCells: List<NormalizedCell>): List<NormalizedCell> {
    if (validCollageCells(customCells, count)) return customCells
    return collageLayouts(count).firstOrNull { it.id == layoutId }?.cells ?: collageCells(count)
}

fun validCollageCells(cells: List<NormalizedCell>, count: Int): Boolean {
    if (count !in 1..MAX_COLLAGE_PHOTOS || cells.size != count) return false
    if (cells.any { cell ->
        !listOf(cell.left, cell.top, cell.right, cell.bottom).all(Float::isFinite) ||
            cell.left < 0f || cell.top < 0f || cell.right > 1f || cell.bottom > 1f ||
            cell.right - cell.left < .025f || cell.bottom - cell.top < .025f
    }) return false
    val area = cells.sumOf { ((it.right - it.left) * (it.bottom - it.top)).toDouble() }
    if (abs(area - 1.0) > .0001) return false
    return cells.indices.all { a ->
        (a + 1 until cells.size).all { b ->
            val x = minOf(cells[a].right, cells[b].right) - maxOf(cells[a].left, cells[b].left)
            val y = minOf(cells[a].bottom, cells[b].bottom) - maxOf(cells[a].top, cells[b].top)
            x <= .00001f || y <= .00001f
        }
    }
}

/** Moves a shared grid line and all touching cells, preserving a full partition. */
fun moveCollageBoundary(cells: List<NormalizedCell>, vertical: Boolean, coordinate: Float, delta: Float): List<NormalizedCell> {
    fun same(value: Float) = abs(value - coordinate) < .0001f
    if (coordinate <= 0f || coordinate >= 1f) return cells
    val before = cells.filter { same(if (vertical) it.right else it.bottom) }
    val after = cells.filter { same(if (vertical) it.left else it.top) }
    if (before.isEmpty() || after.isEmpty()) return cells
    val lower = before.maxOf { (if (vertical) it.left else it.top) + .08f }
    val upper = after.minOf { (if (vertical) it.right else it.bottom) - .08f }
    if (lower >= upper) return cells
    val moved = (coordinate + delta).coerceIn(lower, upper)
    val result = cells.map { cell ->
        if (vertical) cell.copy(left = if (same(cell.left)) moved else cell.left, right = if (same(cell.right)) moved else cell.right)
        else cell.copy(top = if (same(cell.top)) moved else cell.top, bottom = if (same(cell.bottom)) moved else cell.bottom)
    }
    return if (validCollageCells(result, cells.size)) result else cells
}
