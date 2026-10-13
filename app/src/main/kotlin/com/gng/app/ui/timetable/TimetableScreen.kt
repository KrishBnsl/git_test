package com.gng.app.ui.timetable

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.data.IST
import com.gng.app.data.NewTimetableSlot
import com.gng.app.data.Profile
import com.gng.app.data.TimetableRepository
import com.gng.app.data.TimetableSlot
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.ErrorBanner
import com.gng.app.ui.components.FunCard
import com.gng.app.ui.components.pickTime
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.core.hhmm
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

class TimetableViewModel(private val repo: TimetableRepository, private val myId: String) : ViewModel() {
    var slots by mutableStateOf<List<TimetableSlot>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    private fun refresh() = viewModelScope.launch {
        runCatching { slots = repo.forUsers(listOf(myId)) }.onFailure { error = it.friendly() }
    }

    fun add(slot: NewTimetableSlot) = viewModelScope.launch {
        runCatching { repo.add(slot) }.onSuccess { refresh() }.onFailure { error = it.friendly() }
    }

    fun remove(id: String) = viewModelScope.launch {
        runCatching { repo.remove(id) }.onSuccess { refresh() }.onFailure { error = it.friendly() }
    }
}

private val WEEKDAYS = DayOfWeek.entries.take(6) // DTU runs Mon–Sat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(me: Profile, onBack: () -> Unit) {
    val vm = gngViewModel { TimetableViewModel(it.timetable, me.id) }
    var day by rememberSaveable {
        mutableStateOf(LocalDate.now(IST).dayOfWeek.let { if (it == DayOfWeek.SUNDAY) DayOfWeek.MONDAY else it })
    }
    var adding by remember { mutableStateOf(false) }
    val todays = vm.slots.filter { it.day == day.value }.sortedBy { it.starts }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🗓️ My classes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add class") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text(
                "Your gangs use this to see when you're free and to find a lunch slot that works for everyone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WEEKDAYS.forEach { d ->
                    FilterChip(day == d, { day = d }, label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)) })
                }
            }
            ErrorBanner(vm.error)
            if (todays.isEmpty()) EmptyState("🛌", "No classes", "Free day? Or tap + to add one.")
            LazyColumn {
                items(todays, key = { it.id }) { s ->
                    FunCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.subject, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${s.startTime.hhmm()}–${s.endTime.hhmm()}" + (s.room?.let { " · $it" } ?: ""),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { vm.remove(s.id) }) { Text("🗑️") }
                        }
                    }
                }
            }
        }
    }

    if (adding) AddClassDialog(day, onDismiss = { adding = false }) {
        vm.add(it)
        adding = false
    }
}

@Composable
private fun AddClassDialog(day: DayOfWeek, onDismiss: () -> Unit, onAdd: (NewTimetableSlot) -> Unit) {
    val context = LocalContext.current
    var subject by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(LocalTime.of(9, 0)) }
    var end by remember { mutableStateOf(LocalTime.of(10, 0)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a ${day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} class") },
        text = {
            Column {
                OutlinedTextField(subject, { subject = it.take(40) }, label = { Text("Subject") }, placeholder = { Text("DSA") }, singleLine = true)
                OutlinedTextField(room, { room = it.take(20) }, label = { Text("Room (optional)") }, placeholder = { Text("SPS-12") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { pickTime(context, start) { start = it; if (!end.isAfter(it)) end = it.plusHours(1) } }) { Text("from ${start.hhmm()}") }
                    OutlinedButton(onClick = { pickTime(context, end) { end = it } }) { Text("to ${end.hhmm()}") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = subject.isNotBlank() && end.isAfter(start), onClick = {
                onAdd(NewTimetableSlot(day.value, start.toString(), end.toString(), subject.trim(), room.trim().ifBlank { null }))
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
