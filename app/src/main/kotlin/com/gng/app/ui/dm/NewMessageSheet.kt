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
import com.gng.app.data.IST
import com.gng.app.data.PersonCard
import com.gng.app.data.Profile
import com.gng.app.ui.components.Avatar
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.GngSheet
import com.gng.app.ui.components.Loading
import com.gng.app.ui.friendly
import com.gng.app.ui.icons.GngIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter


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

/**
 * Start a DM: your gang-mates, plus anyone on gng found by @username or name. People you don't
 * share a gang with get a message request they can accept or decline.
 */
@Composable
fun NewMessageSheet(
    people: List<Profile>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    search: suspend (String) -> List<PersonCard> = { emptyList() },
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().removePrefix("@")
    val shown = people.filter {
        q.isBlank() || it.displayName.contains(q, ignoreCase = true) || it.username?.startsWith(q, ignoreCase = true) == true
    }
    var found by remember { mutableStateOf<List<PersonCard>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        found = emptyList()
        if (q.length < 2) return@LaunchedEffect
        searching = true
        delay(300)
        found = runCatching { search(q) }.getOrDefault(emptyList())
        searching = false
    }
    val known = shown.map { it.id }.toSet()
    val others = found.filter { it.id !in known }

    GngSheet(title = "New message", onDismiss = onDismiss) {
        OutlinedTextField(
            query, { query = it }, label = { Text("Search by name or @username") },
            leadingIcon = { GngIcon(GngIcons.Search, null) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (shown.isNotEmpty()) SheetLabel("Your gangs")
        shown.forEach { p ->
            PersonRow(p.id, p.displayName, p.avatarUrl, listOfNotNull(p.handle, p.college ?: p.branch).joinToString(" · "), request = false) {
                onPick(p.id)
            }
        }
        if (others.isNotEmpty()) SheetLabel("Everyone on gng")
        others.forEach { p ->
            PersonRow(p.id, p.displayName, p.avatarUrl, listOfNotNull(p.username?.let { "@$it" }, p.college).joinToString(" · "), request = !p.sharesGang) {
                onPick(p.id)
            }
        }
        if (shown.isEmpty() && others.isEmpty()) {
            Text(
                when {
                    searching -> "Searching…"
                    q.length < 2 -> "Type a name or @username to find anyone on gng."
                    else -> "Nobody called that."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PersonRow(id: String, name: String, photo: String?, line: String, request: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name, photo, size = 44.dp, colorKey = id)
        Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            if (line.isNotBlank()) {
                Text(
                    line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (request) {
            Text("Sends a request", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        } else {
            GngIcon(GngIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
