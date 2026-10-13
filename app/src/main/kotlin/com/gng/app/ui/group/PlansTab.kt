package com.gng.app.ui.group

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.gng.app.data.IST
import com.gng.app.data.LunchLog
import com.gng.app.data.NewPlan
import com.gng.app.data.Plan
import com.gng.app.data.PlanKind
import com.gng.app.data.Rsvp
import com.gng.app.data.TimetableSlot
import com.gng.app.ui.components.BouncyButton
import com.gng.app.ui.components.EmojiStrip
import com.gng.app.ui.components.ErrorBanner
import com.gng.app.ui.components.FunCard
import com.gng.app.ui.components.SectionHeader
import com.gng.app.ui.components.pickTime
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.core.ClassSlot
import com.gng.core.TimeWindow
import com.gng.core.Timetable
import com.gng.core.hhmm
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// Edit these to match your campus haunts.
private val PLACES = listOf("Mic Mac", "Nescafe", "Hostel mess", "Main canteen", "Rohini", "Outside")
private val FOOD_EMOJIS = listOf("🍛", "🍜", "🥪", "🍕", "🍔", "🥟", "🍱", "🥗", "🍳", "☕")

fun TimetableSlot.toCore() = ClassSlot(DayOfWeek.of(day), startTime, endTime, subject, room)

class PlansViewModel(private val c: AppContainer, private val ctx: GroupContext) : ViewModel() {
    private val groupId = ctx.group.id
    var plans by mutableStateOf<List<Plan>>(emptyList())
        private set
    var rsvps by mutableStateOf<List<Rsvp>>(emptyList())
        private set
    var lunch by mutableStateOf<List<LunchLog>>(emptyList())
        private set
    var lunchWindows by mutableStateOf<List<TimeWindow>?>(null)
        private set
    var error by mutableStateOf<String?>(null)

    init {
        refresh()
        viewModelScope.launch { c.plans.changes(groupId).collect { refreshPlans() } }
    }

    fun refresh() = viewModelScope.launch {
        refreshPlans()
        runCatching {
            lunch = c.plans.lunchToday().filter { it.userId in ctx.members }
            val slots = c.timetable.forUsers(ctx.members.keys.toList()).map { it.toCore() }
            // Only meaningful once people have added their classes.
            lunchWindows = if (slots.isEmpty()) null else Timetable.commonFreeWindows(
                people = listOf(slots),
                day = LocalDate.now(IST).dayOfWeek,
                from = LocalTime.of(11, 30),
                to = LocalTime.of(16, 0),
                minMinutes = 30,
            )
        }.onFailure { error = it.friendly() }
    }

    private suspend fun refreshPlans() {
        runCatching {
            plans = c.plans.upcoming(groupId)
            rsvps = c.plans.rsvps(groupId)
        }.onFailure { error = it.friendly() }
    }

    fun create(plan: NewPlan, onDone: () -> Unit) = viewModelScope.launch {
        runCatching { c.plans.create(plan) }
            .onSuccess { onDone(); refreshPlans() }
            .onFailure { error = it.friendly() }
    }

    fun rsvp(plan: Plan, answer: String) = viewModelScope.launch {
        runCatching { c.plans.rsvp(plan, answer) }.onSuccess { refreshPlans() }.onFailure { error = it.friendly() }
    }

    fun delete(plan: Plan) = viewModelScope.launch {
        runCatching { c.plans.delete(plan.id) }.onSuccess { refreshPlans() }.onFailure { error = it.friendly() }
    }

    fun logLunch(dish: String, place: String?, emoji: String) = viewModelScope.launch {
        runCatching { c.plans.logLunch(dish, place, emoji) }.onSuccess { refresh() }.onFailure { error = it.friendly() }
    }
}

@Composable
fun PlansTab(ctx: GroupContext) {
    val vm = gngViewModel(key = "plans-${ctx.group.id}") { PlansViewModel(it, ctx) }
    var newPlan by remember { mutableStateOf<PlanKind?>(null) }
    var logLunch by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            ErrorBanner(vm.error, Modifier.padding(top = 8.dp))
            SectionHeader("Who's down?")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanKind.entries.forEach { k ->
                    BouncyButton(
                        "${k.emoji} ${k.label}", { newPlan = k },
                        container = if (k == PlanKind.Lunch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                        content = if (k == PlanKind.Lunch) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            vm.lunchWindows?.let { windows ->
                FunCard(color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Column {
                        Text("🕐 When is everyone free for lunch today?", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (windows.isEmpty()) "Nobody's free at the same time today 😭"
                            else windows.joinToString("  ·  ") { "${it.start.hhmm()}–${it.end.hhmm()}" },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            SectionHeader("Upcoming")
            if (vm.plans.isEmpty()) {
                Text("No plans yet. Be the one who makes it happen ✨", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(vm.plans, key = { it.id }) { p ->
            PlanCard(p, vm.rsvps.filter { it.planId == p.id }, ctx, onRsvp = { vm.rsvp(p, it) }, onDelete = { vm.delete(p) })
        }
        item {
            SectionHeader("Today's lunch board") {
                TextButton(onClick = { logLunch = true }) { Text("+ what I ate") }
            }
            if (vm.lunch.isEmpty()) {
                Text("Nobody's posted lunch yet. Hungry or mysterious?", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vm.lunch.forEach { l ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(l.emoji, fontSize = 28.sp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("${ctx.emojiOf(l.userId)} ${ctx.nameOf(l.userId)}", style = MaterialTheme.typography.labelLarge)
                        Text(l.dish + (l.place?.let { " @ $it" } ?: ""))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    newPlan?.let { kind ->
        NewPlanDialog(kind, ctx.group.id, onDismiss = { newPlan = null }) { plan -> vm.create(plan) { newPlan = null } }
    }
    if (logLunch) {
        LunchDialog(onDismiss = { logLunch = false }) { dish, place, emoji ->
            vm.logLunch(dish, place, emoji)
            logLunch = false
        }
    }
}

private val DAY_TIME = DateTimeFormatter.ofPattern("EEE h:mm a")

private fun whenLabel(p: Plan): String {
    val start = p.startsInstant.atZone(IST)
    val today = LocalDate.now(IST)
    return when (start.toLocalDate()) {
        today -> "today " + start.toLocalTime().hhmm()
        today.plusDays(1) -> "tomorrow " + start.toLocalTime().hhmm()
        else -> start.format(DAY_TIME)
    }
}

@Composable
private fun PlanCard(p: Plan, rsvps: List<Rsvp>, ctx: GroupContext, onRsvp: (String) -> Unit, onDelete: () -> Unit) {
    val mine = rsvps.firstOrNull { it.userId == ctx.me.id }?.answer
    FunCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.planKind.emoji, fontSize = 32.sp)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(whenLabel(p), p.place?.let { "📍 $it" }).joinToString("  "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("by ${ctx.nameOf(p.createdBy)}", style = MaterialTheme.typography.labelSmall)
                }
                if (p.createdBy == ctx.me.id) TextButton(onClick = onDelete) { Text("🗑️") }
            }
            p.note?.let { Text(it, modifier = Modifier.padding(top = 6.dp)) }
            val inPeople = rsvps.filter { it.answer == "in" }
            if (inPeople.isNotEmpty()) {
                Text(
                    "In: " + inPeople.joinToString(" ") { ctx.emojiOf(it.userId) },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                listOf("in" to "🙋 I'm in", "maybe" to "🤔 maybe", "out" to "🙅 out").forEach { (a, label) ->
                    val count = rsvps.count { it.answer == a }
                    FilterChip(
                        selected = mine == a,
                        onClick = { onRsvp(a) },
                        label = { Text(if (count > 0) "$label $count" else label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NewPlanDialog(kind: PlanKind, groupId: String, onDismiss: () -> Unit, onCreate: (NewPlan) -> Unit) {
    val context = LocalContext.current
    val now = ZonedDateTime.now(IST)
    var title by remember {
        mutableStateOf(
            when (kind) {
                PlanKind.Lunch -> "Lunch together?"
                PlanKind.Snacks -> "Evening snacks"
                PlanKind.Outing -> ""
                PlanKind.Study -> "Group study"
                PlanKind.Other -> ""
            },
        )
    }
    var place by remember { mutableStateOf(if (kind == PlanKind.Lunch) PLACES.first() else "") }
    var tomorrow by remember { mutableStateOf(false) }
    var time by remember {
        mutableStateOf(
            when (kind) {
                PlanKind.Lunch -> LocalTime.of(13, 0)
                PlanKind.Snacks -> LocalTime.of(17, 30)
                else -> now.toLocalTime().plusHours(1).withMinute(0).withSecond(0).withNano(0)
            },
        )
    }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${kind.emoji} ${kind.label}") },
        text = {
            Column {
                OutlinedTextField(title, { title = it.take(80) }, label = { Text("What's the plan") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PLACES.forEach { AssistChip(onClick = { place = it }, label = { Text(it) }) }
                }
                OutlinedTextField(place, { place = it.take(80) }, label = { Text("Where") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !tomorrow, onClick = { tomorrow = false }, label = { Text("Today") })
                    FilterChip(selected = tomorrow, onClick = { tomorrow = true }, label = { Text("Tomorrow") })
                    OutlinedButton(onClick = { pickTime(context, time) { time = it } }) { Text("⏰ ${time.hhmm()}") }
                }
                OutlinedTextField(note, { note = it.take(280) }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                val day = LocalDate.now(IST).plusDays(if (tomorrow) 1 else 0)
                val startsAt = ZonedDateTime.of(day, time, IST).toInstant()
                onCreate(
                    NewPlan(groupId, kind.db, title.trim(), place.trim().ifBlank { null }, startsAt.toString(), note.trim().ifBlank { null }),
                )
            }) { Text("Send it 🚀") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LunchDialog(onDismiss: () -> Unit, onSave: (String, String?, String) -> Unit) {
    var dish by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf(FOOD_EMOJIS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's on the plate?") },
        text = {
            Column {
                EmojiStrip(FOOD_EMOJIS, emoji) { emoji = it }
                OutlinedTextField(dish, { dish = it.take(60) }, label = { Text("Dish") }, placeholder = { Text("rajma chawal") }, singleLine = true)
                OutlinedTextField(place, { place = it.take(60) }, label = { Text("Where (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = dish.isNotBlank(), onClick = { onSave(dish.trim(), place.trim().ifBlank { null }, emoji) }) { Text("Post") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
