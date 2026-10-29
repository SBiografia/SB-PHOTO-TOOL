package com.sb.phototool.ui.editor

import android.app.Activity
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.sb.phototool.EditorPreviewState
import com.sb.phototool.loadEditorPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

val ToolAccent = Color(0xFF80A7FF)
private val ToolScheme = darkColorScheme(
    primary = ToolAccent, onPrimary = Color(0xFF091B3E),
    secondary = ToolAccent, secondaryContainer = Color(0xFF253757), onSecondaryContainer = Color(0xFFB9CEFF),
    surfaceVariant = Color(0xFF383B43),
    background = Color(0xFF101113), surface = Color(0xFF1C1D21),
    surfaceContainer = Color(0xFF24262B), surfaceContainerLow = Color(0xFF18191D),
    onSurface = Color(0xFFF3F3F6), onSurfaceVariant = Color(0xFFA9ABB4),
)

@Composable
fun ToolEditor(
    title: String,
    onBack: () -> Unit,
    onPick: () -> Unit,
    pickLabel: String,
    headerAction: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull() }
    DisposableEffect(activity, view) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val status = controller?.isAppearanceLightStatusBars ?: false
        val nav = controller?.isAppearanceLightNavigationBars ?: false
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose { controller?.isAppearanceLightStatusBars = status; controller?.isAppearanceLightNavigationBars = nav }
    }
    BackHandler(onBack = onBack)
    MaterialTheme(colorScheme = ToolScheme) {
        Surface(Modifier.fillMaxSize(), color = ToolScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "홈으로") }
                    Text(title, Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    if (headerAction != null) headerAction.invoke(this)
                    else TextButton(onClick = onPick) { Text(pickLabel) }
                }
                content()
            }
        }
    }
}

@Composable
fun ToolDock(
    tabs: List<String>,
    selected: Int,
    onTab: (Int) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    fillRemaining: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val short = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 700
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var collapsed by rememberSaveable { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                tabs.forEachIndexed { index, title ->
                    TextButton(onClick = { collapsed = false; onTab(index) }, modifier = Modifier.weight(1f).height(48.dp).semantics { this.selected = selected == index }) {
                        Text(title, color = if (selected == index) ToolAccent else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                IconButton(onClick = { collapsed = !collapsed }) { Icon(if (collapsed) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (collapsed) "도구 패널 펼치기" else "도구 패널 접기") }
            }
            HorizontalDivider(color = Color.White.copy(alpha = .06f))
            if (!collapsed) Column(
                (if (fillRemaining) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(when {
                    keyboardOpen -> if (compact) 104.dp else 110.dp
                    compact && short -> 96.dp
                    compact -> 116.dp
                    short -> 112.dp
                    else -> 200.dp
                }))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 6.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
                content = content,
            )
        }
    }
}

@Composable
fun ToolFooter(label: String, enabled: Boolean, onSave: () -> Unit, secondary: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        secondary?.invoke(this)
        Button(onClick = onSave, enabled = enabled, modifier = Modifier.weight(1f).height(50.dp).testTag("tool-save"), shape = RoundedCornerShape(14.dp)) { Text(label, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
fun ToolHint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun CompactSlider(
    label: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 68.dp,
    rowHeight: Dp = 48.dp,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(labelWidth).padding(end = 4.dp), fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier.weight(1f).graphicsLayer { scaleY = .7f }.semantics { contentDescription = label },
        )
        Text(valueLabel, Modifier.widthIn(min = 36.dp).padding(start = 4.dp), textAlign = TextAlign.End, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun <T> Choices(values: List<T>, selected: T, label: (T) -> String, onSelected: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value -> FilterChip(selected = value == selected, onClick = { onSelected(value) }, label = { Text(label(value)) }) }
    }
}

@Composable
fun NumericInput(label: String, value: String, valid: Boolean, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, { onValue(it.filter(Char::isDigit).take(9)) }, label = { Text(label) }, isError = !valid, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier.fillMaxWidth())
}

@Composable
internal fun rememberPhoto(uri: Uri?, surface: String, retry: Int = 0, maxEdge: Int = 768, maxPixels: Long = maxEdge.toLong() * maxEdge): EditorPreviewState {
    val context = LocalContext.current
    return produceState<EditorPreviewState>(EditorPreviewState.Loading, uri, retry, maxEdge, maxPixels) {
        value = EditorPreviewState.Loading
        if (uri != null) value = withContext(Dispatchers.IO) { loadEditorPreview(context, uri, surface, maxEdge, maxPixels) }
    }.value
}

@Composable
internal fun PreviewPhoto(uri: Uri, modifier: Modifier = Modifier, crop: Boolean = false) {
    val state = rememberPhoto(uri, "thumbnail", maxEdge = if (crop) 192 else 768)
    Box(modifier, contentAlignment = Alignment.Center) {
        when (state) {
            is EditorPreviewState.Ready -> Image(state.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
            EditorPreviewState.Loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            EditorPreviewState.Failed -> Text("!", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun EmptyEditor(text: String, onPick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(46.dp), tint = ToolAccent)
            Text(text)
            Button(onClick = onPick) { Text("선택하기") }
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "크기 확인 불가"
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
