package com.sb.phototool.ui.collage

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sb.phototool.media.applyCollageTemplate
import com.sb.phototool.model.JobSpec

internal val EditorAccent = Color(0xFF80A7FF)

@Composable
fun CollageEditorScreen(uris: List<Uri>, onBack: () -> Unit, onPick: () -> Unit, onSubmit: (JobSpec) -> Unit, selectionRevision: Int = 0) {
    val inputs = uris.map(Uri::toString)
    val editor = rememberSaveable(inputs, selectionRevision, saver = CollageEditorState.StateSaver) { CollageEditorState.create(inputs) }
    var templates by rememberSaveable { mutableStateOf(false) }
    var library by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = templates || library) { if (library) library = false else templates = false }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            templates -> CollageTemplateBrowser({ templates = false }) { template ->
                editor.update(applyCollageTemplate(editor.document, template.document)); templates = false
            }
            editor.document.tiles.isNotEmpty() || editor.document.texts.isNotEmpty() || editor.document.decorations.isNotEmpty() || editor.canUndo || editor.canRedo ->
                CollageDesignStudio(editor, onBack, { templates = true }, onSubmit, onLibrary = { library = true }, onPick = onPick)
            else -> Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth()) { TextButton(onClick = onBack) { Text("콜라주 나가기") } }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("사진을 한 장의 이야기로", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(16.dp))
                    Text("사진을 먼저 고르거나 마음에 드는 템플릿부터 시작하세요.")
                    Spacer(Modifier.height(32.dp))
                    Button(onClick = onPick, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("사진 선택 · 최대 15장") }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { templates = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("템플릿 · 60가지 디자인") }
                    TextButton(onClick = { library = true }, modifier = Modifier.fillMaxWidth()) { Text("내 콜라주 보관함") }
                }
            }
        }
    }
    if (library) CollageLibraryDialog(editor.document, { loaded, template ->
        editor.update(if (template) applyCollageTemplate(editor.document, loaded) else loaded.copy(id = "draft")); library = false
    }, { library = false })
}
