package com.gng.app.ui.group

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import com.gng.app.ui.people.LocalOpenPerson
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.gng.app.ui.components.Choice
import com.gng.app.ui.components.Confetti
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.GngSheet
import com.gng.app.ui.components.IconPicker
import com.gng.app.ui.components.MessageEffect
import com.gng.app.ui.components.SectionHeader
import com.gng.app.ui.components.SegmentedChoice
import com.gng.app.ui.components.StatusAvatar
import com.gng.app.ui.components.TonalIcon
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.icons.GngIcons
import com.gng.app.ui.icons.ZoneIcons
import com.gng.core.Birthday
import com.gng.core.Birthdays
import com.gng.core.ClassSlot
import com.gng.core.ClassStatus
import com.gng.core.Geo
import com.gng.core.LatLng
import com.gng.core.Timetable
import com.gng.core.UpcomingBirthday
import com.gng.core.hhmm
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.gng.core.Presence as PresenceLogic

private val BIRTHDAY_DATE = DateTimeFormatter.ofPattern("d MMM")

class SquadViewModel(private val c: AppContainer, private val ctx: GroupContext) : ViewModel() {
    var classes by mutableStateOf<Map<String, List<ClassSlot>>>(emptyMap())
        private set

    /** Bumped after anything that changes where the person appears, so the shared presence reloads. */
    var changeTick by mutableIntStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

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
    }

    fun dismissMessage() { message = null }
    fun dismissError() { error = null }

    fun checkInNow() = viewModelScope.launch {
        message = when (val r = runCatching { c.checkIn.run() }.getOrElse { error = it.friendly(); return@launch }) {
            is CheckIn.Result.Shared -> r.zoneName?.let { "Checked in at $it" } ?: "Checked in, but not at a pinned spot"
            CheckIn.Result.SharingOff -> "Sharing is off, nobody can see you"
            CheckIn.Result.NoPermission -> "Location permission needed"
            CheckIn.Result.NoFix -> "Couldn't get a GPS fix, try again outside"
        }
        changeTick++
    }

    fun setMode(mode: SharingMode, liveFor: Duration? = null) = viewModelScope.launch {
        runCatching { c.checkIn.setMode(mode, liveFor) }.onFailure { error = it.friendly() }
        changeTick++
    }

    fun denyPermission() { message = "Without location permission there are no check-ins" }

    fun pinHere(name: String, icon: String, radius: Int) = viewModelScope.launch {
        val here = c.checkIn.currentLatLng() ?: run { message = "Couldn't get your location"; return@launch }
        runCatching { c.locations.pinZone(NewZone(ctx.group.id, name, icon, here.lat, here.lng, radius)) }
            .onSuccess { message = "$name pinned. Check-ins nearby will snap to it."; checkInNow() }
            .onFailure { error = it.friendly() }
    }
}

@Composable
fun SquadTab(ctx: GroupContext, onTimetable: () -> Unit, onMessage: (String) -> Unit) {
    val vm = gngViewModel(key = "squad-${ctx.group.id}") { SquadViewModel(it, ctx) }
    val sharing by vm.sharing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pinSheet by remember { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf<Pair<SharingMode, Duration?>?>(null) }
    val pv = gngViewModel(key = "presence-${ctx.group.id}") { PresenceViewModel(it, ctx.group.id) }
    PresencePolling(pv)
    LaunchedEffect(vm.changeTick) { if (vm.changeTick > 0) pv.refresh() }
    MessageEffect(vm.error, vm::dismissError)
    MessageEffect(vm.message, vm::dismissMessage)
    MessageEffect(pv.error, pv::dismissError)

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val ok = granted.values.any { it }
        pendingMode?.let { (mode, dur) -> if (ok) vm.setMode(mode, dur) else vm.denyPermission() }
        pendingMode = null
    }
    fun choose(mode: SharingMode) {
        val dur = if (mode == SharingMode.Live) Duration.ofHours(1) else null
        if (mode == SharingMode.Off || context.container.checkIn.hasPermission()) {
            vm.setMode(mode, dur)
        } else {
            pendingMode = mode to dur
            permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    SquadContent(
        ctx = ctx, presence = pv.presence, classes = vm.classes, sharing = sharing, myLatLng = pv.myLatLng,
        birthdays = vm.birthdays,
        onMode = ::choose, onCheckIn = vm::checkInNow, onPin = { pinSheet = true },
        onLocationSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        },
        onMessage = onMessage,
        onFind = { p, who ->
            val uri = Uri.parse("geo:${p.lat},${p.lng}?q=${p.lat},${p.lng}(${Uri.encode(who)})")
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        },
    )

    if (pinSheet) PinSpotSheet(onDismiss = { pinSheet = false }) { name, icon, radius ->
        vm.pinHere(name, icon, radius)
        pinSheet = false
    }
}

@Composable
fun SquadContent(
    ctx: GroupContext,
    presence: Map<String, Presence>,
    classes: Map<String, List<ClassSlot>>,
    sharing: SharingState,
    myLatLng: LatLng?,
    birthdays: List<UpcomingBirthday>,
    onMode: (SharingMode) -> Unit,
    onCheckIn: () -> Unit,
    onPin: () -> Unit,
    onLocationSettings: () -> Unit,
    onFind: (Presence, String) -> Unit,
    onMessage: (String) -> Unit = {},
    now: Instant = Instant.now(),
) {
    val scheme = MaterialTheme.colorScheme
    val nowIst = LocalDateTime.ofInstant(now, IST)
    val birthdayToday = birthdays.firstOrNull { it.isToday }
    val effective = sharing.effective(now)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                if (birthdayToday != null) {
                    Spacer(Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            TonalIcon(GngIcons.CakeFill, container = scheme.primary, content = scheme.onPrimary)
                            Column(Modifier.padding(start = 14.dp)) {
                                Text(
                                    "${birthdayToday.birthday.name} turns ${birthdayToday.turning} today",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text("Go wish them.", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                SectionHeader("Your location")
                Card(
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        SegmentedChoice(
                            choices = listOf(
                                Choice(SharingMode.Off, "Ghost", GngIcons.VisibilityOff),
                                Choice(SharingMode.Zone, "Spot", GngIcons.LocationOn),
                                Choice(SharingMode.Live, "Live", GngIcons.Sensors),
                            ),
                            selected = effective, onSelect = onMode, checkIcon = GngIcons.Check,
                        )
                        Text(
                            when (effective) {
                                SharingMode.Off -> "Invisible. Nobody can see where you are."
                                SharingMode.Zone -> "The gang sees which spot you're at, like the Library or Mic Mac. Never your exact location."
                                SharingMode.Live -> "Exact location shared until ${sharing.liveUntil?.atZone(IST)?.toLocalTime()?.hhmm()}, then back to spot only."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        if (effective != SharingMode.Off) {
                            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FilledTonalButton(onClick = onCheckIn) {
                                    GngIcon(GngIcons.MyLocation, null, size = 18.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Check in")
                                }
                                OutlinedButton(onClick = onPin) {
                                    GngIcon(GngIcons.PushPin, null, size = 18.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Pin this spot")
                                }
                            }
                            TextButton(onClick = onLocationSettings, modifier = Modifier.padding(top = 4.dp)) {
                                Text("Check in while closed: allow location all the time")
                            }
                        }
                    }
                }
                SectionHeader("The gang")
            }
            items(ctx.members.values.sortedBy { it.displayName.lowercase() }, key = { it.id }) { p ->
                MemberRow(
                    name = p.displayName, photoUrl = p.avatarUrl, id = p.id, status = p.statusText,
                    isMe = p.id == ctx.me.id,
                    presence = presence[p.id],
                    classStatus = classes[p.id]?.let { Timetable.status(it, nowIst) },
                    myLatLng = myLatLng, now = now, onFind = onFind, onMessage = { onMessage(p.id) },
                )
            }
            item {
                SectionHeader("Birthdays")
                if (birthdays.isEmpty()) {
                    Text(
                        "No birthdays in the next two months, or nobody's added theirs yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                birthdays.forEach { b ->
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TonalIcon(
                            if (b.isToday) GngIcons.CakeFill else GngIcons.Cake,
                            container = if (b.isToday) scheme.primaryContainer else scheme.secondaryContainer,
                            content = if (b.isToday) scheme.onPrimaryContainer else scheme.onSecondaryContainer,
                        )
                        Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
                            Text(b.birthday.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${b.next.format(BIRTHDAY_DATE)} · turns ${b.turning}",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            Birthdays.whenLabel(b),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (b.isToday) scheme.primary else scheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        if (birthdayToday != null) Confetti()
    }
}

@Composable
private fun MemberRow(
    name: String, photoUrl: String?, id: String, status: String?, isMe: Boolean,
    presence: Presence?, classStatus: ClassStatus?, myLatLng: LatLng?, now: Instant,
    onFind: (Presence, String) -> Unit,
    onMessage: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val info = presence?.let { PresenceLogic.describe(it.zoneName, parseInstant(it.updatedAt), now) }
    val openPerson = LocalOpenPerson.current
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { openPerson(id) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusAvatar(name, photoUrl, online = info != null && !info.stale, colorKey = id)
        Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
            Text(name + if (isMe) " (you)" else "", style = MaterialTheme.typography.titleMedium)
            if (!status.isNullOrBlank()) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            if (info != null) {
                val spot = info.spotName ?: "somewhere on the move"
                StatusLine(
                    GngIcons.LocationOn,
                    (if (info.stale) "Last seen at $spot" else "At $spot") + " · ${info.ago}",
                )
            } else {
                StatusLine(GngIcons.VisibilityOff, "Not sharing")
            }
            when (classStatus) {
                is ClassStatus.InClass ->
                    StatusLine(GngIcons.School, "In ${classStatus.slot.subject} until ${classStatus.slot.end.hhmm()}", scheme.primary)
                is ClassStatus.Free ->
                    StatusLine(GngIcons.CheckCircleFill, "Free until ${classStatus.nextClass.start.hhmm()}", scheme.tertiary)
                ClassStatus.DoneForToday ->
                    StatusLine(GngIcons.DoneAll, "Done for today", scheme.tertiary)
                null -> Unit
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            if (presence?.lat != null && presence.lng != null && !isMe) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(onClick = { onFind(presence, name) }) { GngIcon(GngIcons.NearMe, "Find $name") }
                    myLatLng?.let {
                        Text(
                            Geo.prettyDistance(Geo.distanceMeters(it, LatLng(presence.lat, presence.lng))),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!isMe) {
                IconButton(onClick = onMessage) { GngIcon(GngIcons.ChatBubble, "Message $name", tint = scheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun StatusLine(icon: Int, text: String, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        GngIcon(icon, null, tint = tint, size = 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

@Composable
fun PinSpotSheet(onDismiss: () -> Unit, onPin: (name: String, icon: String, radius: Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(ZoneIcons.default.key) }
    var radius by remember { mutableStateOf(60f) }
    GngSheet(title = "Pin where you're standing", onDismiss = onDismiss) {
        Text(
            "Stand at the spot (Library, Mic Mac, your hostel) and name it. Everyone's check-ins nearby will show this name.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        IconPicker(ZoneIcons, icon) { icon = it }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            name, { name = it.take(30) }, label = { Text("Name") }, placeholder = { Text("Central Library") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text("Size: ${radius.toInt()} m", style = MaterialTheme.typography.labelLarge)
        Slider(value = radius, onValueChange = { radius = it }, valueRange = 20f..300f)
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = name.isNotBlank(),
            onClick = { onPin(name.trim(), icon, radius.toInt()) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Pin it") }
    }
}
