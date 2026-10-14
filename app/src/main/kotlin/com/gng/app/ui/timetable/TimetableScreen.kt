package com.gng.app.ui.timetable

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.data.IST
import com.gng.app.data.NewTimetableSlot
import com.gng.app.data.Profile
import com.gng.app.data.TimetableRepository
import com.gng.app.data.TimetableSlot
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.GngSheet
import com.gng.app.ui.components.GngTimePickerDialog
import com.gng.app.ui.components.InfoChip
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.icons.GngIcons
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

    fun dismissError() { error = null }

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

@Composable
fun TimetableScreen(me: Profile, onBack: () -> Unit) {
    val vm = gngViewModel { TimetableViewModel(it.timetable, me.id) }
    TimetableContent(vm.slots, vm.error, vm::dismissError, vm::add, vm::remove, onBack)
}

private val WEEKDAYS = DayOfWeek.entries.take(6) // DTU runs Mon to Sat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableContent(
    slots: List<TimetableSlot>,
    error: String?,
    onDismissError: () -> Unit,
    onAdd: (NewTimetableSlot) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
    initialDay: DayOfWeek = LocalDate.now(IST).dayOfWeek.let { if (it == DayOfWeek.SUNDAY) DayOfWeek.MONDAY else it },
) {
    val scheme = MaterialTheme.colorScheme
    var day by rememberSaveable { mutableStateOf(initialDay) }
    var adding by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val todays = slots.filter { it.day == day.value }.sortedBy { it.starts }

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
                title = { Text("My classes") },
                navigationIcon = { IconButton(onClick = onBack) { GngIcon(GngIcons.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { GngIcon(GngIcons.Add, null) },
                text = { Text("Add class") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text(
                "Your gang uses this to see when you're free and to find a lunch slot that works for everyone.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WEEKDAYS.forEach { d ->
                    val count = slots.count { it.day == d.value }
                    FilterChip(
                        selected = day == d, onClick = { day = d },
                        label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + if (count > 0) " · $count" else "") },
                    )
                }
            }
            if (todays.isEmpty()) {
                EmptyState(
                    GngIcons.EventBusy, "No classes on ${day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}",
                    "A free day? Tap Add class if that's wrong.",
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(todays, key = { it.id }) { s ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier.width(72.dp),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(s.startTime.hhmm(), style = MaterialTheme.typography.titleSmall)
                                Text(s.endTime.hhmm(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(s.subject, style = MaterialTheme.typography.titleMedium)
                                s.room?.let {
                                    Spacer(Modifier.height(6.dp))
                                    InfoChip(it, icon = GngIcons.LocationOn)
                                }
                            }
                            IconButton(onClick = { onRemove(s.id) }) {
                                GngIcon(GngIcons.Delete, "Remove class", tint = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }

    if (adding) AddClassSheet(day, onDismiss = { adding = false }) {
        onAdd(it)
        adding = false
    }
}

@Composable
fun AddClassSheet(day: DayOfWeek, onDismiss: () -> Unit, onAdd: (NewTimetableSlot) -> Unit) {
    var subject by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(LocalTime.of(9, 0)) }
    var end by remember { mutableStateOf(LocalTime.of(10, 0)) }
    var picking by remember { mutableStateOf<String?>(null) } // "start" | "end"

    GngSheet(title = "Add a ${day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} class", onDismiss = onDismiss) {
        OutlinedTextField(
            subject, { subject = it.take(40) }, label = { Text("Subject") }, placeholder = { Text("DSA") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            room, { room = it.take(20) }, label = { Text("Room (optional)") }, placeholder = { Text("SPS-12") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { picking = "start" }, modifier = Modifier.weight(1f)) {
                GngIcon(GngIcons.Schedule, null, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text("From ${start.hhmm()}")
            }
            OutlinedButton(onClick = { picking = "end" }, modifier = Modifier.weight(1f)) {
                GngIcon(GngIcons.Schedule, null, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text("To ${end.hhmm()}")
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            enabled = subject.isNotBlank() && end.isAfter(start),
            onClick = {
                onAdd(NewTimetableSlot(day.value, start.toString(), end.toString(), subject.trim(), room.trim().ifBlank { null }))
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Add class") }
    }

    when (picking) {
        "start" -> GngTimePickerDialog(start, onDismiss = { picking = null }) {
            start = it
            if (!end.isAfter(it)) end = it.plusHours(1)
            picking = null
        }
        "end" -> GngTimePickerDialog(end, onDismiss = { picking = null }) { end = it; picking = null }
    }
}
