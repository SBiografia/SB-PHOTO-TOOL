package com.sb.phototool.ui.collage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.sb.phototool.data.CollageLibrary
import com.sb.phototool.data.SavedCollage
import com.sb.phototool.model.CollageJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CollageLibraryDialog(job: CollageJob, onLoad: (CollageJob, Boolean) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val library = remember(context) { CollageLibrary(context) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<SavedCollage>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<SavedCollage?>(null) }
    suspend fun refresh() { entries = withContext(Dispatchers.IO) { library.list() } }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch { try { action(); refresh() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "처리하지 못했어요. 저장 공간과 사진 권한을 확인해 주세요." } finally { busy = false } }
    }
    LaunchedEffect(Unit) { refresh() }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("내 콜라주 보관함") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("직접 저장한 초안·템플릿만 이 기기에 보관합니다. 초안 사진은 원본과 별도로 복사하고, 템플릿에는 사진을 보관하지 않습니다.")
            OutlinedTextField(name, { name = it.take(60) }, label = { Text("저장 이름") }, enabled = !busy, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { run { withContext(Dispatchers.IO) { library.save(name, job, false) }; name = "" } }, enabled = !busy && name.isNotBlank() && job.tiles.any { it.uri.isNotBlank() } && entries.size < 30) { Text("초안 저장") }
                TextButton(onClick = { run { withContext(Dispatchers.IO) { library.save(name, job, true) }; name = "" } }, enabled = !busy && name.isNotBlank() && job.tiles.isNotEmpty() && entries.size < 30) { Text("템플릿 저장") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("저장한 항목 ${entries.size}/30개")
            if (entries.isEmpty()) Text("저장한 항목이 없어요")
            entries.forEach { entry ->
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { run { val loaded = withContext(Dispatchers.IO) { library.load(entry.id) }; onLoad(loaded, entry.template) } }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(entry.name + if (entry.template) " · 템플릿" else " · 초안")
                    }
                    TextButton(onClick = { deleting = entry }, enabled = !busy) { Text("삭제") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose, enabled = !busy) { Text("닫기") } })
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("보관함 항목 삭제") },
        text = { Text("${entry.name}을 삭제할까요? 원본과 현재 편집 중인 사진은 유지됩니다.") },
        confirmButton = { TextButton(onClick = { deleting = null; run { withContext(Dispatchers.IO) { library.delete(entry.id) } } }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } }) }
}
