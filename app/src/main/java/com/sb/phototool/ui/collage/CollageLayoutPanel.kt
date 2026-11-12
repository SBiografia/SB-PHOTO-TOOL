package com.sb.phototool.ui.collage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sb.phototool.media.CollageLayout
import com.sb.phototool.media.collageLayouts
import com.sb.phototool.model.CollageJob

@Composable
internal fun LayoutPanel(document: CollageJob, onSelect: (CollageLayout) -> Unit) {
    val layouts = remember(document.tiles.size) { collageLayouts(document.tiles.size) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyHorizontalGrid(rows = GridCells.Fixed(if (maxHeight >= 100.dp) 2 else 1),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().testTag("collage-layouts")) {
            items(layouts, key = { it.id }) { layout ->
                val active = document.layoutId == layout.id
                val color = if (active) EditorAccent else Color(0xFF888C96)
                Canvas(
                    Modifier.width(66.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                        .background(if (active) EditorAccent.copy(alpha = .12f) else Color.Transparent)
                        .clickable(role = Role.Button) { onSelect(layout) }
                        .semantics { contentDescription = "${layout.label} 레이아웃"; selected = active }
                        .testTag("collage-layout-${layout.id}").padding(5.dp),
                ) {
                    val edge = size.minDimension
                    val origin = Offset((size.width - edge) / 2, (size.height - edge) / 2)
                    layout.cells.forEach { cell ->
                        drawRect(color, origin + Offset(cell.left * edge + 1.5f, cell.top * edge + 1.5f),
                            Size((cell.right - cell.left) * edge - 3f, (cell.bottom - cell.top) * edge - 3f), style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
        }
    }
}
