package com.gng.app.ui.group

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.AppContainer
import com.gng.app.data.Exam
import com.gng.app.data.ExamKind
import com.gng.app.data.IST
import com.gng.app.data.Material
import com.gng.app.data.MaterialVote
import com.gng.app.data.NewExam
import com.gng.app.ui.components.Choice
import com.gng.app.ui.components.GngDatePickerDialog
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.GngSheet
import com.gng.app.ui.components.MessageEffect
import com.gng.app.ui.components.SectionHeader
import com.gng.app.ui.components.SegmentedChoice
import com.gng.app.ui.components.TonalIcon
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.icons.GngIcons
import com.gng.core.Exams
import com.gng.core.Urgency
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
        private set

    init {
        refresh()
    }

    fun dismissError() { error = null }

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
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Couldn't read that file")
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
    MessageEffect(vm.error, vm::dismissError)

    StudyContent(
        ctx = ctx, exams = vm.exams, materials = vm.materials, votes = vm.votes, filterExam = filterExam,
        onFilter = { filterExam = it }, onVote = vm::toggleVote, onOpen = { vm.open(context, it) },
        onAddExam = { addExam = true }, onAddMaterial = { addMaterial = true },
    )

    if (addExam) AddExamSheet(onDismiss = { addExam = false }) { subject, kind, date ->
        vm.addExam(subject, kind, date)
        addExam = false
    }
    if (addMaterial) AddMaterialSheet(
        exams = vm.exams,
        preselectedExam = filterExam,
        busy = vm.busy,
        onDismiss = { addMaterial = false },
        onLink = { examId, subject, title, url -> vm.addLink(examId, subject, title, url); addMaterial = false },
        onFile = { examId, subject, title, uri -> vm.addFile(context, uri, examId, subject, title); addMaterial = false },
    )
}

@Composable
fun StudyContent(
    ctx: GroupContext,
    exams: List<Exam>,
    materials: List<Material>,
    votes: List<MaterialVote>,
    filterExam: String?,
    onFilter: (String?) -> Unit,
    onVote: (Material) -> Unit,
    onOpen: (Material) -> Unit,
    onAddExam: () -> Unit,
    onAddMaterial: () -> Unit,
    today: LocalDate = LocalDate.now(IST),
) {
    val scheme = MaterialTheme.colorScheme
    val shown = materials
        .filter { filterExam == null || it.examId == filterExam }
        .sortedByDescending { m -> votes.count { it.materialId == m.id } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            SectionHeader("Exam radar") {
                FilledTonalButton(onClick = onAddExam) {
                    GngIcon(GngIcons.Add, null, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Exam")
                }
            }
            if (exams.isEmpty()) {
                Text(
                    "No exams on the radar. Suspicious.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(exams, key = { it.id }) { e ->
                        ExamCard(
                            e, today, selected = filterExam == e.id,
                            notes = materials.count { it.examId == e.id },
                            onClick = { onFilter(if (filterExam == e.id) null else e.id) },
                        )
                    }
                }
            }
            SectionHeader(if (filterExam == null) "The stack" else "The stack, filtered") {
                FilledTonalButton(onClick = onAddMaterial) {
                    GngIcon(GngIcons.Add, null, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Add")
                }
            }
            if (shown.isEmpty()) {
                Text(
                    "Nothing stacked yet. Notes, PYQs, slides, that one YouTube playlist: drop it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        items(shown, key = { it.id }) { m ->
            val count = votes.count { it.materialId == m.id }
            val mine = votes.any { it.materialId == m.id && it.userId == ctx.me.id }
            MaterialRow(m, ctx, count, mine, onOpen = { onOpen(m) }, onVote = { onVote(m) })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private data class UrgencyStyle(val container: Color, val content: Color, val accent: Color, val icon: Int)

@Composable
private fun urgencyStyle(u: Urgency): UrgencyStyle {
    val s = MaterialTheme.colorScheme
    return when (u) {
        Urgency.Today, Urgency.Critical -> UrgencyStyle(s.errorContainer, s.onErrorContainer, s.error, GngIcons.LocalFireDepartmentFill)
        Urgency.Soon -> UrgencyStyle(s.tertiaryContainer, s.onTertiaryContainer, s.tertiary, GngIcons.LocalFireDepartmentFill)
        Urgency.Upcoming -> UrgencyStyle(s.secondaryContainer, s.onSecondaryContainer, s.secondary, GngIcons.HourglassTop)
        Urgency.Relaxed -> UrgencyStyle(s.surfaceContainer, s.onSurface, s.onSurfaceVariant, GngIcons.HourglassTop)
        Urgency.Done -> UrgencyStyle(s.surfaceContainerLow, s.onSurfaceVariant, s.onSurfaceVariant, GngIcons.CheckCircleFill)
    }
}

@Composable
private fun ExamCard(e: Exam, today: LocalDate, selected: Boolean, notes: Int, onClick: () -> Unit) {
    val left = Exams.daysLeft(e.date, today)
    val style = urgencyStyle(Exams.urgency(left))
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = style.container, contentColor = style.content),
        shape = MaterialTheme.shapes.large,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.width(176.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GngIcon(style.icon, null, tint = style.accent, size = 20.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    Exams.countdownLabel(left).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelLarge,
                    color = style.accent,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(e.subject, style = MaterialTheme.typography.titleMedium, maxLines = 2, minLines = 2)
            Spacer(Modifier.height(6.dp))
            Text(
                "${ExamKind.of(e.kind).label} · ${e.date.format(EXAM_DATE)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GngIcon(GngIcons.AttachFile, null, size = 16.dp)
                Spacer(Modifier.width(4.dp))
                Text("$notes in the stack", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun MaterialRow(m: Material, ctx: GroupContext, votes: Int, mine: Boolean, onOpen: () -> Unit, onVote: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val icon = when {
        m.linkUrl != null -> GngIcons.Link
        m.storagePath?.endsWith(".pdf", ignoreCase = true) == true -> GngIcons.PictureAsPdf
        else -> GngIcons.Description
    }
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TonalIcon(icon, shape = RoundedCornerShape(12.dp), container = scheme.secondaryContainer)
            Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
                Text(m.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Text(
                    "${m.subject} · ${ctx.nameOf(m.uploadedBy)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            FilterChip(
                selected = mine,
                onClick = onVote,
                label = { Text("$votes") },
                leadingIcon = { GngIcon(if (mine) GngIcons.ThumbUpFill else GngIcons.ThumbUp, null, size = 18.dp) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddExamSheet(onDismiss: () -> Unit, onAdd: (String, ExamKind, LocalDate) -> Unit) {
    var subject by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ExamKind.Midterm) }
    var date by remember { mutableStateOf(LocalDate.now(IST).plusDays(7)) }
    var pickDate by remember { mutableStateOf(false) }
    GngSheet(title = "Add to the radar", onDismiss = onDismiss) {
        OutlinedTextField(
            subject, { subject = it.take(60) }, label = { Text("Subject") }, placeholder = { Text("Data Structures") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExamKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, label = { Text(k.label) }) }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { pickDate = true }) {
            GngIcon(GngIcons.CalendarMonth, null, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(date.format(EXAM_DATE))
        }
        Spacer(Modifier.height(24.dp))
        Button(
            enabled = subject.isNotBlank(),
            onClick = { onAdd(subject.trim(), kind, date) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Add exam") }
    }
    if (pickDate) GngDatePickerDialog(date, onDismiss = { pickDate = false }) { date = it; pickDate = false }
}

private enum class Source { Link, File }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddMaterialSheet(
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
    var source by remember { mutableStateOf(Source.Link) }
    var file by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { file = it }

    GngSheet(title = "Add to the stack", onDismiss = onDismiss) {
        if (exams.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(examId == null, { examId = null }, label = { Text("General") })
                exams.forEach { e ->
                    FilterChip(examId == e.id, { examId = e.id; if (subject.isBlank()) subject = e.subject }, label = { Text(e.subject) })
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        OutlinedTextField(subject, { subject = it.take(60) }, label = { Text("Subject") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            title, { title = it.take(100) }, label = { Text("Title") }, placeholder = { Text("Unit 3 handwritten notes") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        SegmentedChoice(
            choices = listOf(Choice(Source.Link, "Link", GngIcons.Link), Choice(Source.File, "File", GngIcons.UploadFile)),
            selected = source, onSelect = { source = it }, checkIcon = GngIcons.Check,
        )
        Spacer(Modifier.height(12.dp))
        if (source == Source.Link) {
            OutlinedTextField(
                url, { url = it.trim() }, label = { Text("Drive, YouTube or any link") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                GngIcon(if (file == null) GngIcons.AttachFile else GngIcons.CheckCircleFill, null, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(if (file == null) "Choose a file (up to 25 MB)" else "File ready")
            }
        }
        Spacer(Modifier.height(24.dp))
        val ready = subject.isNotBlank() && title.isNotBlank() && !busy &&
            if (source == Source.Link) url.startsWith("http") else file != null
        Button(
            enabled = ready,
            onClick = {
                val f = file
                if (source == Source.File && f != null) onFile(examId, subject.trim(), title.trim(), f)
                else onLink(examId, subject.trim(), title.trim(), url)
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Stack it") }
    }
}
