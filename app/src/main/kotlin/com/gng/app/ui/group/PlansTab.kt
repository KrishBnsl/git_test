package com.gng.app.ui.group

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.gng.app.ui.components.Avatar
import com.gng.app.ui.components.AvatarInfo
import com.gng.app.ui.components.AvatarStack
import com.gng.app.ui.components.Choice
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.GngSheet
import com.gng.app.ui.components.GngTimePickerDialog
import com.gng.app.ui.components.IconPicker
import com.gng.app.ui.components.InfoChip
import com.gng.app.ui.components.MessageEffect
import com.gng.app.ui.components.SectionHeader
import com.gng.app.ui.components.SegmentedChoice
import com.gng.app.ui.components.TonalIcon
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.icons.FoodIcons
import com.gng.app.ui.icons.GngIcons
import com.gng.app.ui.icons.iconRes
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
        private set

    init {
        refresh()
        viewModelScope.launch { c.plans.changes(groupId).collect { refreshPlans() } }
    }

    fun dismissError() { error = null }

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

    fun logLunch(dish: String, place: String?, icon: String) = viewModelScope.launch {
        runCatching { c.plans.logLunch(dish, place, icon) }.onSuccess { refresh() }.onFailure { error = it.friendly() }
    }
}

@Composable
fun PlansTab(ctx: GroupContext) {
    val vm = gngViewModel(key = "plans-${ctx.group.id}") { PlansViewModel(it, ctx) }
    var newPlan by remember { mutableStateOf<PlanKind?>(null) }
    var logLunch by remember { mutableStateOf(false) }
    MessageEffect(vm.error, vm::dismissError)

    PlansContent(
        ctx = ctx, plans = vm.plans, rsvps = vm.rsvps, lunch = vm.lunch, lunchWindows = vm.lunchWindows,
        onNewPlan = { newPlan = it }, onRsvp = vm::rsvp, onDelete = vm::delete, onLogLunch = { logLunch = true },
    )

    newPlan?.let { kind ->
        NewPlanSheet(kind, ctx.group.id, onDismiss = { newPlan = null }) { plan -> vm.create(plan) { newPlan = null } }
    }
    if (logLunch) {
        LunchSheet(onDismiss = { logLunch = false }) { dish, place, icon ->
            vm.logLunch(dish, place, icon)
            logLunch = false
        }
    }
}

@Composable
fun PlansContent(
    ctx: GroupContext,
    plans: List<Plan>,
    rsvps: List<Rsvp>,
    lunch: List<LunchLog>,
    lunchWindows: List<TimeWindow>?,
    onNewPlan: (PlanKind) -> Unit,
    onRsvp: (Plan, String) -> Unit,
    onDelete: (Plan) -> Unit,
    onLogLunch: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            SectionHeader("Start something")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PlanKind.entries.forEach { k -> QuickTile(k, onClick = { onNewPlan(k) }) }
            }
            if (lunchWindows != null) {
                Spacer(Modifier.height(16.dp))
                LunchWindowsCard(lunchWindows)
            }
            SectionHeader("Coming up")
            if (plans.isEmpty()) {
                Text(
                    "No plans yet. Be the one who makes it happen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(plans, key = { it.id }) { p ->
            PlanCard(
                p, rsvps.filter { it.planId == p.id }, ctx,
                onRsvp = { onRsvp(p, it) }, onDelete = { onDelete(p) },
            )
        }
        item {
            SectionHeader("Today's lunch board") {
                FilledTonalButton(onClick = onLogLunch) {
                    GngIcon(GngIcons.Add, null, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("What I ate")
                }
            }
            if (lunch.isEmpty()) {
                Text(
                    "Nobody's posted lunch yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(lunch, key = { it.userId }) { l ->
            val person = ctx.person(l.userId)
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(person.name, person.photoUrl, size = 40.dp, colorKey = person.key)
                Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
                    Text(person.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        l.dish + (l.place?.let { " at $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TonalIcon(FoodIcons.resolve(l.icon).icon, size = 40.dp)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun QuickTile(kind: PlanKind, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val primary = kind == PlanKind.Lunch
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (primary) scheme.primaryContainer else scheme.secondaryContainer,
        contentColor = if (primary) scheme.onPrimaryContainer else scheme.onSecondaryContainer,
    ) {
        Column(
            Modifier.width(88.dp).padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GngIcon(kind.iconRes(), null, size = 28.dp)
            Text(kind.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun LunchWindowsCard(windows: List<TimeWindow>) {
    val scheme = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TonalIcon(GngIcons.Schedule, container = scheme.tertiary, content = scheme.onTertiary)
            Column(Modifier.padding(start = 14.dp)) {
                Text("Free for lunch today", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (windows.isEmpty()) "Nobody's free at the same time today."
                    else windows.joinToString("   ") { "${it.start.hhmm()} to ${it.end.hhmm()}" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private val DAY_TIME = DateTimeFormatter.ofPattern("EEE h:mm a")

private fun whenLabel(p: Plan): String {
    val start = p.startsInstant.atZone(IST)
    val today = LocalDate.now(IST)
    return when (start.toLocalDate()) {
        today -> "Today, " + start.toLocalTime().hhmm()
        today.plusDays(1) -> "Tomorrow, " + start.toLocalTime().hhmm()
        else -> start.format(DAY_TIME)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanCard(p: Plan, rsvps: List<Rsvp>, ctx: GroupContext, onRsvp: (String) -> Unit, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val mine = rsvps.firstOrNull { it.userId == ctx.me.id }?.answer
    val going = rsvps.filter { it.answer == "in" }.map { ctx.person(it.userId) }
    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TonalIcon(
                    p.planKind.iconRes(),
                    container = if (p.planKind == PlanKind.Lunch) scheme.primaryContainer else scheme.secondaryContainer,
                    content = if (p.planKind == PlanKind.Lunch) scheme.onPrimaryContainer else scheme.onSecondaryContainer,
                )
                Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "by ${ctx.nameOf(p.createdBy)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (p.createdBy == ctx.me.id) {
                    IconButton(onClick = onDelete) {
                        GngIcon(GngIcons.Delete, "Delete plan", tint = scheme.onSurfaceVariant)
                    }
                }
            }
            FlowRow(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoChip(whenLabel(p), icon = GngIcons.Schedule)
                p.place?.let { InfoChip(it, icon = GngIcons.LocationOn) }
            }
            p.note?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (going.isNotEmpty()) {
                    AvatarStack(going, ringColor = scheme.surfaceContainer)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    if (going.isEmpty()) "Nobody yet" else "${going.size} going",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
            SegmentedChoice(
                choices = listOf(
                    Choice("in", "I'm in"),
                    Choice("maybe", "Maybe"),
                    Choice("out", "Can't"),
                ),
                selected = mine ?: "",
                onSelect = onRsvp,
                checkIcon = GngIcons.Check,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewPlanSheet(kind: PlanKind, groupId: String, onDismiss: () -> Unit, onCreate: (NewPlan) -> Unit) {
    val now = ZonedDateTime.now(IST)
    var title by remember {
        mutableStateOf(
            when (kind) {
                PlanKind.Lunch -> "Lunch together?"
                PlanKind.Snacks -> "Evening snacks"
                PlanKind.Study -> "Group study"
                PlanKind.Outing, PlanKind.Other -> ""
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
    var pickTime by remember { mutableStateOf(false) }

    GngSheet(title = "New ${kind.label.lowercase()} plan", onDismiss = onDismiss) {
        OutlinedTextField(
            title, { title = it.take(80) }, label = { Text("What's the plan?") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            place, { place = it.take(80) }, label = { Text("Where") },
            leadingIcon = { GngIcon(GngIcons.LocationOn, null) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PLACES.forEach { SuggestionChip(onClick = { place = it }, label = { Text(it) }) }
        }
        Spacer(Modifier.height(20.dp))
        Text("When", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SegmentedChoice(
                choices = listOf(Choice(false, "Today"), Choice(true, "Tomorrow")),
                selected = tomorrow, onSelect = { tomorrow = it },
                checkIcon = GngIcons.Check, modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { pickTime = true }) {
                GngIcon(GngIcons.Schedule, null, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(time.hhmm())
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            note, { note = it.take(280) }, label = { Text("Note (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            enabled = title.isNotBlank(),
            onClick = {
                val day = LocalDate.now(IST).plusDays(if (tomorrow) 1 else 0)
                val startsAt = ZonedDateTime.of(day, time, IST).toInstant()
                onCreate(NewPlan(groupId, kind.db, title.trim(), place.trim().ifBlank { null }, startsAt.toString(), note.trim().ifBlank { null }))
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Send it to the gang") }
    }
    if (pickTime) GngTimePickerDialog(time, onDismiss = { pickTime = false }) { time = it; pickTime = false }
}

@Composable
fun LunchSheet(onDismiss: () -> Unit, onSave: (dish: String, place: String?, icon: String) -> Unit) {
    var dish by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(FoodIcons.default.key) }
    GngSheet(title = "What's on the plate?", onDismiss = onDismiss) {
        IconPicker(FoodIcons, icon) { icon = it }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            dish, { dish = it.take(60) }, label = { Text("Dish") }, placeholder = { Text("Rajma chawal") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            place, { place = it.take(60) }, label = { Text("Where (optional)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            enabled = dish.isNotBlank(),
            onClick = { onSave(dish.trim(), place.trim().ifBlank { null }, icon) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Post to the board") }
    }
}
