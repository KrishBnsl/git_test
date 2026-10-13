package com.gng.app.ui.dm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import com.gng.app.AppContainer
import com.gng.app.data.DmInboxRow
import com.gng.app.data.IST
import com.gng.app.data.Profile
import com.gng.app.ui.components.Avatar
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.GngSheet
import com.gng.app.ui.components.Loading
import com.gng.app.ui.friendly
import com.gng.app.ui.icons.GngIcons
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The inbox and the unread badge. Lives as long as the activity so the badge is always current. */
class DmInboxViewModel(private val c: AppContainer, private val myId: String) : ViewModel() {
    var rows by mutableStateOf<List<DmInboxRow>?>(null)
        private set
    var people by mutableStateOf<Map<String, Profile>>(emptyMap())
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val unread: Int get() = rows.orEmpty().sumOf { it.unread }

    init {
        refresh()
        viewModelScope.launch { runCatching { c.dms.anyIncoming().collect { refresh() } } }
    }

    fun dismissError() { error = null }

    fun refresh() = viewModelScope.launch {
        runCatching {
            val inbox = c.dms.inbox()
            people = c.profiles.getMany(inbox.map { it.otherId }.distinct()).associateBy { it.id }
            rows = inbox
        }.onFailure {
            error = it.friendly()
            if (rows == null) rows = emptyList()
        }
    }

    /** Everyone you could message: the people in your gangs. */
    suspend fun candidates(): List<Profile> =
        runCatching { c.profiles.gangMates(myId).sortedBy { it.displayName.lowercase() } }.getOrDefault(emptyList())
}

private val TIME = DateTimeFormatter.ofPattern("h:mm a")
private val WEEKDAY = DateTimeFormatter.ofPattern("EEE")
private val DAY = DateTimeFormatter.ofPattern("d MMM")

/** "12:05 pm" today, "Yesterday", "Mon" within a week, otherwise "3 Oct". */
fun inboxTime(at: Instant, today: LocalDate = LocalDate.now(IST)): String {
    val date = at.atZone(IST)
    val day = date.toLocalDate()
    return when {
        day == today -> date.format(TIME).lowercase()
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> date.format(WEEKDAY)
        else -> date.format(DAY)
    }
}

@Composable
fun DmInboxScreen(me: Profile, vm: DmInboxViewModel, onBack: () -> Unit, onOpen: (otherId: String) -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    var picker by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<Profile>>(emptyList()) }
    LaunchedEffect(picker) { if (picker) candidates = vm.candidates() }

    DmInboxContent(
        me = me, rows = vm.rows, people = vm.people, error = vm.error, onDismissError = vm::dismissError,
        onBack = onBack, onOpen = onOpen, onNew = { picker = true },
    )
    if (picker) NewMessageSheet(candidates, onDismiss = { picker = false }) { picker = false; onOpen(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmInboxContent(
    me: Profile,
    rows: List<DmInboxRow>?,
    people: Map<String, Profile>,
    error: String?,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        if (error != null) {
            snackbar.showSnackbar(error)
            onDismissError()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Messages") },
                navigationIcon = { IconButton(onClick = onBack) { GngIcon(GngIcons.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { GngIcon(GngIcons.AddComment, null) },
                text = { Text("New message") },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                rows == null -> Loading()
                rows.isEmpty() -> EmptyState(
                    GngIcons.ChatBubbleFill, "No messages yet",
                    "Start a one-to-one chat with anyone in your gangs.",
                    Modifier.align(Alignment.Center),
                    action = { Button(onClick = onNew) { Text("New message") } },
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                    items(rows, key = { it.threadId }) { row ->
                        InboxRow(me, row, people[row.otherId], onClick = { onOpen(row.otherId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxRow(me: Profile, row: DmInboxRow, other: Profile?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val unread = row.unread > 0
    val name = other?.displayName ?: "Former gang-mate"
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name, other?.avatarUrl, size = 52.dp, colorKey = row.otherId)
        Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread) FontWeight.ExtraBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val preview = when {
                row.lastBody == null -> "Say hi"
                row.lastSender == me.id -> "You: ${row.lastBody}"
                else -> row.lastBody
            }
            Text(
                preview,
                style = MaterialTheme.typography.bodyMedium,
                color = if (unread) scheme.onSurface else scheme.onSurfaceVariant,
                fontWeight = if (unread) FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                inboxTime(row.lastInstant),
                style = MaterialTheme.typography.labelSmall,
                color = if (unread) scheme.primary else scheme.onSurfaceVariant,
            )
            if (unread) Badge { Text(if (row.unread > 99) "99+" else row.unread.toString()) }
            else Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun NewMessageSheet(people: List<Profile>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = people.filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) }
    GngSheet(title = "New message", onDismiss = onDismiss) {
        OutlinedTextField(
            query, { query = it }, label = { Text("Search your gangs") },
            leadingIcon = { GngIcon(GngIcons.Search, null) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            Text(
                if (people.isEmpty()) "Join a gang to message its members." else "Nobody called that.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
        shown.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(p.id) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(p.displayName, p.avatarUrl, size = 44.dp, colorKey = p.id)
                Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
                    Text(p.displayName, style = MaterialTheme.typography.titleSmall)
                    val line = listOfNotNull(p.branch, p.year?.let { "Year $it" }).joinToString(" · ")
                    if (line.isNotBlank()) {
                        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                GngIcon(GngIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
