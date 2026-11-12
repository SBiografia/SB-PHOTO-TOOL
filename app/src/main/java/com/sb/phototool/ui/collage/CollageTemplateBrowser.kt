package com.sb.phototool.ui.collage

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sb.phototool.media.*
import com.sb.phototool.model.*

@Composable
internal fun CollageTemplateBrowser(onBack: () -> Unit, onChoose: (CollageTemplate) -> Unit) {
    var category by rememberSaveable { mutableStateOf<TemplateCategory?>(null) }
    var count by rememberSaveable { mutableStateOf<Int?>(null) }
    var ratio by rememberSaveable { mutableStateOf<CanvasRatio?>(null) }
    val matches = collageTemplates.filter { (category == null || it.category == category) && (count == null || it.photoCount == count) && (ratio == null || it.document.ratio == ratio) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().testTag("template-browser")) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "템플릿 목록 닫기") }
            Text("템플릿", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("60가지 디자인", Modifier.padding(end = 16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(category == null, { category = null }, label = { Text("전체") }) }
            items(TemplateCategory.entries) { c -> FilterChip(category == c, { category = c }, label = { Text(c.label) }, modifier = Modifier.testTag("template-category-" + c.name)) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(count == null, { count = null }, label = { Text("사진 수 전체") })
            (1..15).forEach { n -> FilterChip(count == n, { count = n }, label = { Text(n.toString() + "칸") }) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(ratio == null, { ratio = null }, label = { Text("비율 전체") })
            listOf(CanvasRatio.SQUARE, CanvasRatio.PORTRAIT, CanvasRatio.STORY).forEach { r -> FilterChip(ratio == r, { ratio = r }, label = { Text(r.label) }) }
        }
        if (matches.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("이 조건에 맞는 템플릿이 없어요")
                TextButton(onClick = { category = null; count = null; ratio = null }) { Text("전체 보기") }
            }
        } else LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f).testTag("template-grid"),
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(matches, key = { it.id }) { t ->
                Column(Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
                    .clickable { onChoose(t) }.testTag("template-" + t.id)
                    .semantics { contentDescription = t.name + ", 사진 " + t.photoCount + "칸, " + t.document.ratio.label }) {
                    TemplateThumbnail(t, Modifier.fillMaxWidth().aspectRatio(t.document.ratio.ratio ?: 1f))
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(t.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.photoCount.toString() + "칸 · " + t.document.ratio.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TemplateThumbnail(template: CollageTemplate, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val photos = remember { templateDemoPhotos() }
    val demo = remember(template) { template.document.copy(tiles = template.document.tiles.mapIndexed { i, tile -> tile.copy(uri = "demo-" + i % photos.size) }) }
    Canvas(modifier) {
        drawIntoCanvas { c -> drawCollageComposition(c.nativeCanvas, demo, size.width, size.height,
            { key -> photos[key.removePrefix("demo-").toInt() % photos.size] }, font = { CollageFonts.get(context, it) }) }
    }
}

/** Original, generated landscape drawings are used only for catalog previews and QA. */
internal fun templateDemoPhotos(): List<Bitmap> = List(4) { index ->
    val palettes = listOf(intArrayOf(0xFFAFC8C5.toInt(), 0xFF405F59.toInt(), 0xFFB69B78.toInt()),
        intArrayOf(0xFFDBB7A0.toInt(), 0xFF775B6C.toInt(), 0xFFB08A70.toInt()),
        intArrayOf(0xFFA8C1D2.toInt(), 0xFF426372.toInt(), 0xFFE0C5A0.toInt()),
        intArrayOf(0xFFE8D7B8.toInt(), 0xFF65775E.toInt(), 0xFFB1A479.toInt()))
    val colors = palettes[index]
    Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).also { image ->
        val c = AndroidCanvas(image); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, 320f, colors[0], 0xFFF5EDDA.toInt(), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, 240f, 320f, p)
        p.shader = null; p.color = 0xFFF9E9C2.toInt(); c.drawCircle(160f - index * 17, 68f, 24f, p)
        p.color = colors[1]; c.drawPath(Path().apply { moveTo(0f, 205f); lineTo(85f + index * 9, 105f); lineTo(160f, 205f); lineTo(215f, 155f); lineTo(240f, 190f); lineTo(240f, 320f); lineTo(0f, 320f); close() }, p)
        p.color = colors[2]; c.drawPath(Path().apply { moveTo(0f, 260f); cubicTo(65f, 205f, 130f, 285f, 240f, 225f); lineTo(240f, 320f); lineTo(0f, 320f); close() }, p)
        p.color = 0x77FFFFFF; p.strokeWidth = 1.2f; (0..10).forEach { c.drawLine(0f, 282f + it * 3, 240f, 279f + it * 3, p) }
    }
}
