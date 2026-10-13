package com.gng.app.ui.group

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gng.app.AppContainer
import com.gng.app.container
import com.gng.app.data.IST
import com.gng.app.data.NewZone
import com.gng.app.data.Presence
import com.gng.app.data.SharingMode
import com.gng.app.data.SharingState
import com.gng.app.data.parseInstant
import com.gng.app.location.CheckIn
import com.gng.app.ui.components.BouncyButton
import com.gng.app.ui.components.ChipRow
import com.gng.app.ui.components.EmojiAvatar
import com.gng.app.ui.components.EmojiConfetti
import com.gng.app.ui.components.EmojiStrip
import com.gng.app.ui.components.ErrorBanner
import com.gng.app.ui.components.FunCard
import com.gng.app.ui.components.SectionHeader
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.theme.LocalIsDark
import com.gng.app.ui.theme.friendColor
import com.gng.core.Birthday
import com.gng.core.Birthdays
import com.gng.core.ClassSlot
import com.gng.core.Geo
import com.gng.core.LatLng
import com.gng.core.Timetable
import com.gng.core.UpcomingBirthday
import com.gng.core.hhmm
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import com.gng.core.Presence as PresenceText

private val ZONE_EMOJIS = listOf("📍", "📚", "🍟", "☕", "🏠", "🏀", "🎭", "🧪", "💻", "🌳")

class SquadViewModel(private val c: AppContainer, private val ctx: GroupContext) : ViewModel() {
    var presence by mutableStateOf<Map<String, Presence>>(emptyMap())
        private set
    var classes by mutableStateOf<Map<String, List<ClassSlot>>>(emptyMap())
        private set
    var myLatLng by mutableStateOf<LatLng?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    val sharing = c.settings.sharing.stateIn(viewModelScope, SharingStarted.Eagerly, SharingState(SharingMode.Off, null))

    val birthdays: List<UpcomingBirthday> = Birthdays.upcoming(
        ctx.members.values.mapNotNull { p -> p.birthdayDate?.let { Birthday(p.id, p.displayName, it) } },
        LocalDate.now(IST),
    )

    init {
        viewModelScope.launch {
            runCatching {
                classes = c.timetable.forUsers(ctx.members.keys.toList()).groupBy({ it.userId }, { it.toCore() })
            }.onFailure { error = it.friendly() }
        }
        // Presence is a poll, not a stream: positions only change every few minutes anyway.
        viewModelScope.launch {
            while (true) {
                refreshPresence()
                delay(60_000)
            }
        }
    }

    private suspend fun refreshPresence() {
        runCatching { presence = c.locations.presence(ctx.group.id).associateBy { it.userId } }
            .onFailure { error = it.friendly() }
        if (sharing.value.effective() == SharingMode.Live) myLatLng = runCatching { c.checkIn.currentLatLng() }.getOrNull()
    }

    fun checkInNow() = viewModelScope.launch {
        message = when (val r = runCatching { c.checkIn.run() }.getOrElse { error = it.friendly(); return@launch }) {
            is CheckIn.Result.Shared -> "Checked in " + (r.zoneName?.let { "at $it ✅" } ?: "(not at a pinned spot)")
            CheckIn.Result.SharingOff -> "Sharing is off, nobody sees you 👻"
            CheckIn.Result.NoPermission -> "Location permission needed"
            CheckIn.Result.NoFix -> "Couldn't get a GPS fix, try again outside"
        }
        refreshPresence()
    }

    fun setMode(mode: SharingMode, liveFor: Duration? = null) = viewModelScope.launch {
        runCatching { c.checkIn.setMode(mode, liveFor) }.onFailure { error = it.friendly() }
        refreshPresence()
    }

    fun pinHere(name: String, emoji: String, radius: Int) = viewModelScope.launch {
        val here = c.checkIn.currentLatLng() ?: run { message = "Couldn't get your location"; return@launch }
        runCatching { c.locations.pinZone(NewZone(ctx.group.id, name, emoji, here.lat, here.lng, radius)) }
            .onSuccess { message = "$emoji $name pinned! Check-ins nearby will snap to it."; checkInNow() }
            .onFailure { error = it.friendly() }
    }
}

@Composable
fun SquadTab(ctx: GroupContext, onTimetable: () -> Unit) {
    val vm = gngViewModel(key = "squad-${ctx.group.id}") { SquadViewModel(it, ctx) }
    val sharing by vm.sharing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dark = LocalIsDark.current
    var pinDialog by remember { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf<Pair<SharingMode, Duration?>?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val ok = granted.values.any { it }
        pendingMode?.let { (mode, dur) -> if (ok) vm.setMode(mode, dur) else vm.message = "No permission, no check-ins 🤷" }
        pendingMode = null
    }
    fun choose(mode: SharingMode, dur: Duration? = null) {
        if (mode == SharingMode.Off || context.container.checkIn.hasPermission()) {
            vm.setMode(mode, dur)
        } else {
            pendingMode = mode to dur
            permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    val now = Instant.now()
    val nowIst = LocalDateTime.now(IST)
    val birthdayToday = vm.birthdays.firstOrNull { it.isToday }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                ErrorBanner(vm.error, Modifier.padding(top = 8.dp))
                birthdayToday?.let {
                    FunCard(color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(Birthdays.label(it) + " Go wish them 🥳", style = MaterialTheme.typography.titleMedium)
                    }
                }
                SectionHeader("Your spot")
                FunCard {
                    Column {
                        val effective = sharing.effective(now)
                        ChipRow {
                            FilterChip(effective == SharingMode.Off, { choose(SharingMode.Off) }, label = { Text("👻 Ghost") })
                            FilterChip(effective == SharingMode.Zone, { choose(SharingMode.Zone) }, label = { Text("🏫 Spot only") })
                            FilterChip(effective == SharingMode.Live, { choose(SharingMode.Live, Duration.ofHours(1)) }, label = { Text("📡 Live 1h") })
                        }
                        Text(
                            when (effective) {
                                SharingMode.Off -> "Invisible. Nobody sees where you are."
                                SharingMode.Zone -> "The gang sees which spot you're at (Library, Mic Mac…), never your exact location."
                                SharingMode.Live -> "Exact location shared until ${sharing.liveUntil?.atZone(IST)?.toLocalTime()?.hhmm()}, then back to spot only."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        if (effective != SharingMode.Off) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BouncyButton("📍 Check in", vm::checkInNow)
                                BouncyButton(
                                    "📌 Pin this spot", { pinDialog = true },
                                    container = MaterialTheme.colorScheme.tertiary,
                                    content = MaterialTheme.colorScheme.onTertiary,
                                )
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                TextButton(onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                                    )
                                }) { Text("Auto check-in when the app is closed: set location to \"Allow all the time\"") }
                            }
                        }
                        vm.message?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                    }
                }
                SectionHeader("The gang") {
                    TextButton(onClick = onTimetable) { Text("🗓️ my classes") }
                }
            }
            items(ctx.members.values.sortedBy { it.displayName.lowercase() }, key = { it.id }) { p ->
                val pr = vm.presence[p.id]
                val classStatus = vm.classes[p.id]?.let { Timetable.label(Timetable.status(it, nowIst)) }
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    EmojiAvatar(p.emoji, friendColor(p.id, dark))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(
                            p.displayName + if (p.id == ctx.me.id) " (you)" else "",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        p.statusText?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall) }
                        Text(
                            pr?.let { PresenceText.label(it.zoneName, it.zoneEmoji, parseInstant(it.updatedAt), now) } ?: "👻 not sharing",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        classStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                    if (pr?.lat != null && pr.lng != null && p.id != ctx.me.id) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("📡 live", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            vm.myLatLng?.let { me ->
                                Text(Geo.prettyDistance(Geo.distanceMeters(me, LatLng(pr.lat, pr.lng))), style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = {
                                val uri = Uri.parse("geo:${pr.lat},${pr.lng}?q=${pr.lat},${pr.lng}(${Uri.encode(p.displayName)})")
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            }) { Text("Find 🗺️") }
                        }
                    }
                }
            }
            item {
                SectionHeader("Birthdays coming up")
                if (vm.birthdays.isEmpty()) {
                    Text("No birthdays in the next 2 months (or nobody added theirs 👀)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                vm.birthdays.forEach { Text(Birthdays.label(it), modifier = Modifier.padding(vertical = 4.dp), fontSize = 16.sp) }
                Spacer(Modifier.height(24.dp))
            }
        }
        if (birthdayToday != null) EmojiConfetti()
    }

    if (pinDialog) PinDialog(onDismiss = { pinDialog = false }) { name, emoji, radius ->
        vm.pinHere(name, emoji, radius)
        pinDialog = false
    }
}

@Composable
private fun PinDialog(onDismiss: () -> Unit, onPin: (String, String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf(ZONE_EMOJIS.first()) }
    var radius by remember { mutableStateOf(60f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pin where you're standing") },
        text = {
            Column {
                Text("Stand at the spot (Library, Mic Mac, your hostel…) and name it. Everyone's check-ins nearby will show this name.")
                EmojiStrip(ZONE_EMOJIS, emoji) { emoji = it }
                OutlinedTextField(name, { name = it.take(30) }, label = { Text("Name") }, placeholder = { Text("Central Library") }, singleLine = true)
                Text("Size: ${radius.toInt()} m")
                Slider(value = radius, onValueChange = { radius = it }, valueRange = 20f..300f)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onPin(name.trim(), emoji, radius.toInt()) }) { Text("Pin it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
