package com.gng.app.ui.group

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.AppContainer
import com.gng.app.data.Exam
import com.gng.app.data.ExamKind
import com.gng.app.data.IST
import com.gng.app.data.Material
import com.gng.app.data.MaterialVote
import com.gng.app.data.NewExam
import com.gng.app.ui.components.BouncyButton
import com.gng.app.ui.components.ChipRow
import com.gng.app.ui.components.ErrorBanner
import com.gng.app.ui.components.FunCard
import com.gng.app.ui.components.SectionHeader
import com.gng.app.ui.components.pickDate
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.core.Exams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val MAX_UPLOAD_BYTES = 25 * 1024 * 1024
private val EXAM_DATE = DateTimeFormatter.ofPattern("EEE d MMM")

class StudyViewModel(private val c: AppContainer, private val ctx: GroupContext) : ViewModel() {
    private val groupId = ctx.group.id
    var exams by mutableStateOf<List<Exam>>(emptyList())
        private set
    var materials by mutableStateOf<List<Material>>(emptyList())
        private set
    var votes by mutableStateOf<List<MaterialVote>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        runCatching {
            exams = c.study.exams(groupId)
            materials = c.study.materials(groupId)
            votes = c.study.votes(groupId)
        }.onFailure { error = it.friendly() }
    }

    fun addExam(subject: String, kind: ExamKind, date: LocalDate) = launchBusy {
        c.study.addExam(NewExam(groupId, subject, kind.db, date.toString()))
    }

    fun addLink(examId: String?, subject: String, title: String, url: String) = launchBusy {
        c.study.addLink(groupId, examId, subject, title, url)
    }

    fun addFile(context: Context, uri: Uri, examId: String?, subject: String, title: String) = launchBusy {
        val (name, bytes) = withContext(Dispatchers.IO) { readFile(context, uri) }
        require(bytes.size <= MAX_UPLOAD_BYTES) { "That file's over 25 MB, share a Drive link instead" }
        c.study.uploadFile(groupId, examId, subject, title, name, bytes)
    }

    fun toggleVote(m: Material) = viewModelScope.launch {
        val voted = votes.any { it.materialId == m.id && it.userId == ctx.me.id }
        runCatching { c.study.toggleVote(m, ctx.me.id, voted) }.onFailure { error = it.friendly() }
        refresh()
    }

    fun open(context: Context, m: Material) = viewModelScope.launch {
        runCatching { c.study.downloadUrl(m) }
            .onSuccess { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
            .onFailure { error = it.friendly() }
    }

    private fun launchBusy(block: suspend () -> Unit) = viewModelScope.launch {
        busy = true
        error = null
        runCatching { block() }.onFailure { error = it.friendly() }
        busy = false
        refresh()
    }

    private fun readFile(context: Context, uri: Uri): Pair<String, ByteArray> {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "file"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Couldn't read that file")
        return name to bytes
    }
}

@Composable
fun StudyTab(ctx: GroupContext) {
    val vm = gngViewModel(key = "study-${ctx.group.id}") { StudyViewModel(it, ctx) }
    val context = LocalContext.current
    var addExam by remember { mutableStateOf(false) }
    var addMaterial by remember { mutableStateOf(false) }
    var filterExam by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now(IST)

    val shown = vm.materials
        .filter { filterExam == null || it.examId == filterExam }
        .sortedByDescending { m -> vm.votes.count { it.materialId == m.id } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            ErrorBanner(vm.error, Modifier.padding(top = 8.dp))
            SectionHeader("Exam radar") { TextButton(onClick = { addExam = true }) { Text("+ exam") } }
            if (vm.exams.isEmpty()) {
                Text("No exams on the radar. Suspicious. 🤨", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vm.exams.forEach { e ->
                val left = Exams.daysLeft(e.date, today)
                val selected = filterExam == e.id
                FunCard(
                    Modifier.clickable { filterExam = if (selected) null else e.id },
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Exams.panicMeter(left), fontSize = 22.sp)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text("${ExamKind.of(e.kind).label}: ${e.subject}", style = MaterialTheme.typography.titleMedium)
                            Text("${e.date.format(EXAM_DATE)} · ${Exams.countdownLabel(left)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val count = vm.materials.count { it.examId == e.id }
                        Text("📎 $count")
                    }
                }
            }
            SectionHeader(if (filterExam == null) "The stack" else "The stack (filtered)") {
                TextButton(onClick = { addMaterial = true }) { Text("+ add") }
            }
            if (shown.isEmpty()) {
                Text("Nothing stacked yet. Notes, PYQs, slides, that one YouTube playlist: drop it here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(shown, key = { it.id }) { m ->
            val count = vm.votes.count { it.materialId == m.id }
            val mine = vm.votes.any { it.materialId == m.id && it.userId == ctx.me.id }
            FunCard(Modifier.clickable { vm.open(context, m) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (m.storagePath != null) "📄" else "🔗", fontSize = 26.sp)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(m.title, style = MaterialTheme.typography.titleMedium)
                        Text("${m.subject} · by ${ctx.emojiOf(m.uploadedBy)} ${ctx.nameOf(m.uploadedBy)}", style = MaterialTheme.typography.bodySmall)
                    }
                    FilterChip(selected = mine, onClick = { vm.toggleVote(m) }, label = { Text("🙏 $count") })
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (addExam) ExamDialog(onDismiss = { addExam = false }) { subject, kind, date ->
        vm.addExam(subject, kind, date)
        addExam = false
    }
    if (addMaterial) MaterialDialog(
        exams = vm.exams,
        preselectedExam = filterExam,
        busy = vm.busy,
        onDismiss = { addMaterial = false },
        onLink = { examId, subject, title, url -> vm.addLink(examId, subject, title, url); addMaterial = false },
        onFile = { examId, subject, title, uri -> vm.addFile(context, uri, examId, subject, title); addMaterial = false },
    )
}

@Composable
private fun ExamDialog(onDismiss: () -> Unit, onAdd: (String, ExamKind, LocalDate) -> Unit) {
    val context = LocalContext.current
    var subject by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ExamKind.Midterm) }
    var date by remember { mutableStateOf(LocalDate.now(IST).plusDays(7)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⏰ Add to the radar") },
        text = {
            Column {
                OutlinedTextField(subject, { subject = it.take(60) }, label = { Text("Subject") }, placeholder = { Text("Data Structures") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExamKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, label = { Text(k.label) }) }
                }
                OutlinedButton(onClick = { pickDate(context, date) { date = it } }) { Text("📅 ${date.format(EXAM_DATE)}") }
            }
        },
        confirmButton = { TextButton(enabled = subject.isNotBlank(), onClick = { onAdd(subject.trim(), kind, date) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MaterialDialog(
    exams: List<Exam>,
    preselectedExam: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onLink: (String?, String, String, String) -> Unit,
    onFile: (String?, String, String, Uri) -> Unit,
) {
    var examId by remember { mutableStateOf(preselectedExam) }
    var subject by remember { mutableStateOf(exams.firstOrNull { it.id == preselectedExam }?.subject ?: "") }
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { file = it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📎 Stack something") },
        text = {
            Column {
                if (exams.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(examId == null, { examId = null }, label = { Text("General") })
                        exams.forEach { e ->
                            FilterChip(examId == e.id, { examId = e.id; if (subject.isBlank()) subject = e.subject }, label = { Text(e.subject) })
                        }
                    }
                }
                OutlinedTextField(subject, { subject = it.take(60) }, label = { Text("Subject") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(title, { title = it.take(100) }, label = { Text("Title") }, placeholder = { Text("Unit 3 handwritten notes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url, { url = it.trim(); file = null }, label = { Text("Link (Drive, YouTube…)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("or", modifier = Modifier.padding(vertical = 4.dp))
                ChipRow {
                    BouncyButton(if (file == null) "📄 Pick a file" else "📄 File picked ✓", { picker.launch(arrayOf("*/*")) })
                }
            }
        },
        confirmButton = {
            val ready = subject.isNotBlank() && title.isNotBlank() && (file != null || url.startsWith("http")) && !busy
            TextButton(enabled = ready, onClick = {
                val f = file
                if (f != null) onFile(examId, subject.trim(), title.trim(), f) else onLink(examId, subject.trim(), title.trim(), url)
            }) { Text("Stack it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
