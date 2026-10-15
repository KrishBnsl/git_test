package com.gng.app.debug

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gng.app.data.PlanKind
import com.gng.app.data.SharingMode
import com.gng.app.data.SharingState
import com.gng.app.data.ThemeMode
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gng.app.container
import com.gng.app.ui.update.UpdateActions
import com.gng.app.ui.update.UpdateCard
import com.gng.app.ui.update.actions
import com.gng.app.update.UpdateInfo
import com.gng.app.update.UpdateState
import java.io.File
import com.gng.app.ui.auth.AuthContent
import com.gng.app.ui.dm.NewMessageSheet
import com.gng.app.ui.chat.ChatActions
import com.gng.app.ui.chat.ChatContent
import com.gng.app.ui.chat.ChatUiState
import com.gng.app.ui.chat.MediaAccess
import com.gng.app.ui.chat.Receipt
import com.gng.app.data.MediaRef
import com.gng.app.data.ChatMessage
import com.gng.app.ui.group.AddExamSheet
import com.gng.app.ui.group.AddMaterialSheet
import com.gng.app.ui.group.EditGroupSheet
import com.gng.app.ui.group.GroupScaffold
import com.gng.app.ui.group.GroupSettingsContent
import com.gng.app.ui.group.MapContent
import com.gng.app.ui.group.toMapSource
import com.gng.app.ui.group.GroupTab
import com.gng.app.ui.group.LunchSheet
import com.gng.app.ui.group.NewPlanSheet
import com.gng.app.ui.group.PinSpotSheet
import com.gng.app.ui.group.PlansContent
import com.gng.app.ui.group.SquadContent
import com.gng.app.ui.group.StudyContent
import com.gng.app.ui.groups.GangSheetMode
import com.gng.app.ui.groups.GroupsContent
import com.gng.app.ui.groups.NewGangSheet
import com.gng.app.ui.profile.ProfileForm
import com.gng.app.ui.settings.SettingsContent
import com.gng.app.ui.theme.GngTheme
import com.gng.app.ui.timetable.AddClassSheet
import com.gng.app.ui.timetable.TimetableContent
import com.gng.core.LatLng
import com.gng.core.Timetable
import java.time.DayOfWeek

class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val screen = intent.getStringExtra("screen") ?: "chat"
        val dark = intent.getBooleanExtra("dark", false)
        val dynamic = intent.getBooleanExtra("dynamic", false)
        val accent = com.gng.app.ui.theme.Accent.from(intent.getStringExtra("accent"))
        setContent {
            GngTheme(if (dark) ThemeMode.Dark else ThemeMode.Light, if (dynamic) com.gng.app.ui.theme.Accent.Wallpaper else accent) {
                Surface(Modifier.fillMaxSize()) { Screen(screen) }
            }
        }
    }
}

private val noop = {}

private val sampleUpdate = UpdateInfo(
    version = "0.3.0",
    notes = listOf("Live map with everyone's avatars", "Group settings and admin permissions", "Direct messages", "Updates inside the app", "Fixes and polish"),
    apkUrl = "https://github.com/KrishBnsl/gng/releases/download/v0.3.0/gng-0.3.0.apk",
    sizeBytes = 12_900_000,
)

@Composable
private fun UpdateDemo(state: UpdateState) {
    Column(Modifier.systemBarsPadding().padding(16.dp)) { UpdateCard(state, UpdateActions()) }
}

/** Drives the real UpdateManager, for testing the updater against a local server. */
@Composable
private fun UpdateLive() {
    val updates = LocalContext.current.container.updates
    val state by updates.state.collectAsStateWithLifecycle()
    Column(Modifier.systemBarsPadding().padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        Text("Updater test", style = MaterialTheme.typography.headlineSmall)
        Text("Installed: ${updates.currentVersion}   updates enabled: ${updates.enabled}")
        Text("State: ${state::class.simpleName}")
        Button(onClick = { updates.check() }) { Text("Check now") }
        UpdateCard(state, updates.actions())
    }
}

@Composable
private fun MapScreen(selected: String?) {
    val s = SampleData
    val pins = com.gng.core.MapRules.pins(s.presence.values.map { it.toMapSource() }, SampleData.now)
    MapContent(s.ctx, pins, selected, true, {}, { null }, {}, { _, _ -> })
}

/** The chat with sample messages; your latest message shows as read, like iMessage. */
@Composable
private fun Chat(ctx: com.gng.app.ui.group.GroupContext, messages: List<ChatMessage>, reactions: Map<Long, Map<String, String>>) {
    val noMedia = object : MediaAccess {
        override fun cached(ref: MediaRef) = null
        override suspend fun load(ref: MediaRef): File = error("no media in the gallery")
    }
    ChatContent(
        ctx, ChatUiState(messages, reactions, reachedStart = true), noMedia, stickers = null,
        actions = ChatActions(receiptFor = { Receipt(if (ctx.isDm) "Seen 12:38 pm" else "Seen by Diya, Esha +2") }),
        today = SampleData.now.atZone(com.gng.app.data.IST).toLocalDate(),
    )
}

@Composable
private fun Group(tab: GroupTab, content: @Composable () -> Unit) {
    GroupScaffold(SampleData.ctx, null, tab, {}, {}, {}, {}, {}) { content() }
}

@Composable
private fun Screen(name: String) {
    val s = SampleData
    when (name) {
        "auth-signup" -> AuthContent(true, {}, false, null, null, {_, _ -> })
        "auth-login" -> AuthContent(false, {}, false, "Wrong email or password", null, {_, _ -> }, "krish@dtu.ac.in")
        "profile-new" -> ProfileForm(null, false, null, {}, {})
        "profile-edit" -> ProfileForm(s.me, false, null, {}, {}, onBack = {})
        "groups" -> GroupsContent(s.me, s.conversations, false, null, {}, {}, {}, {}, {}, {}, {})
        "groups-empty" -> GroupsContent(s.me, emptyList(), false, null, {}, {}, {}, {}, {}, {}, {})
        "chat" -> Group(GroupTab.Chat) { Chat(s.ctx, s.messages, s.reactions) }
        "chat-empty" -> Group(GroupTab.Chat) { Chat(s.ctx, emptyList(), emptyMap()) }
        "plans" -> Group(GroupTab.Plans) {
            PlansContent(s.ctx, s.plans, s.rsvps, s.lunch, Timetable.commonFreeWindows(
                listOf(s.classes.values.flatten()), java.time.LocalDate.now().dayOfWeek,
                java.time.LocalTime.of(11, 30), java.time.LocalTime.of(16, 0),
            ), {}, { _, _ -> }, {}, {})
        }
        "squad" -> Group(GroupTab.Squad) {
            SquadContent(
                s.ctx, s.presence, s.classes, SharingState(SharingMode.Zone, null), LatLng(28.7500, 77.1170),
                com.gng.core.Birthdays.upcoming(
                    s.ctx.members.values.mapNotNull { p -> p.birthdayDate?.let { com.gng.core.Birthday(p.id, p.displayName, it) } },
                    java.time.LocalDate.now(),
                ), {}, {}, {}, {}, { _, _ -> }, {}, SampleData.now,
            )
        }
        "study" -> Group(GroupTab.Study) {
            StudyContent(s.ctx, s.exams, s.materials, s.votes, null, {}, {}, {}, {}, {})
        }
        "timetable" -> TimetableContent(s.myClasses, null, {}, {}, {}, {}, DayOfWeek.of(java.time.LocalDate.now().dayOfWeek.value))
        "icon" -> IconSheet()
        "settings" -> SettingsContent(
            s.me, ThemeMode.System, com.gng.app.ui.theme.Accent.Blue, {}, {}, {}, {}, {}, {},
            updateState = UpdateState.Available(sampleUpdate), currentVersion = "0.2.1",
        )
        "map" -> Group(GroupTab.Map) { MapScreen(selected = null) }
        "map-selected" -> Group(GroupTab.Map) { MapScreen(selected = "u-bhavya") }
        "map-empty" -> Group(GroupTab.Map) {
            MapContent(s.ctx, emptyList(), null, true, {}, { null }, {}, { _, _ -> })
        }
        "chat-locked" -> Group(GroupTab.Chat) { Chat(s.memberCtx, s.messages, s.reactions) }
        "group-settings" -> GroupSettingsContent(s.ctx, false, null, {}, {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {})
        "group-settings-member" -> GroupSettingsContent(s.memberCtx, false, null, {}, {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {})
        "sheet-group-edit" -> EditGroupSheet("Hostel H4 Maggi Club", "restaurant", {}, { _, _ -> })
        "dm-chat" -> GroupScaffold(s.dmCtx, null, GroupTab.Chat, {}, {}, {}, {}, {}, onCall = {}) { Chat(s.dmCtx, s.dmWithAarav, emptyMap()) }
        "sheet-new-message" -> NewMessageSheet(s.people.filter { it.id != s.me.id }, {}, {})
        "update-available" -> UpdateDemo(UpdateState.Available(sampleUpdate))
        "update-downloading" -> UpdateDemo(UpdateState.Downloading(sampleUpdate, 0.42f))
        "update-permission" -> UpdateDemo(UpdateState.NeedsInstallPermission(sampleUpdate, File("x.apk")))
        "update-failed" -> UpdateDemo(UpdateState.Failed("This update is signed with a different key than your installed app. Uninstall gng and install the new version once.", sampleUpdate))
        "update-live" -> UpdateLive()
        "sheet-plan" -> NewPlanSheet(PlanKind.Lunch, "g1", {}, {})
        "sheet-lunch" -> LunchSheet({}, { _, _, _ -> })
        "sheet-gang" -> NewGangSheet(GangSheetMode.Start, false, null, {}, { _, _ -> }, {})
        "sheet-join" -> NewGangSheet(GangSheetMode.Join, false, null, {}, { _, _ -> }, {})
        "sheet-pin" -> PinSpotSheet({}, { _, _, _ -> })
        "sheet-exam" -> AddExamSheet({}, { _, _, _ -> })
        "sheet-material" -> AddMaterialSheet(s.exams, null, false, {}, { _, _, _, _ -> }, { _, _, _, _ -> })
        "sheet-class" -> AddClassSheet(DayOfWeek.MONDAY, {}, {})
        else -> Text("Unknown screen: $name")
    }
}

/** The launcher icon (as Android draws it), the themed-icon version and the status bar glyph, big. */
@Composable
private fun IconSheet() {
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp),
    ) {
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp)) {
            listOf(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color(0xFF111418)).forEach { bg ->
                androidx.compose.foundation.layout.Box(
                    Modifier.size(150.dp).androidxBackground(bg),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    androidx.compose.ui.viewinterop.AndroidView({ ctx ->
                        android.widget.ImageView(ctx).apply { setImageResource(com.gng.app.R.mipmap.ic_launcher) }
                    }, Modifier.size(120.dp))
                }
            }
        }
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp)) {
            // Themed icon (Android 13+ draws the monochrome layer on a tinted circle).
            androidx.compose.foundation.layout.Box(
                Modifier.size(120.dp).androidxBackground(androidx.compose.ui.graphics.Color(0xFFD6E3FF), circle = true),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(com.gng.app.R.drawable.ic_launcher_monochrome), null,
                    Modifier.size(120.dp), tint = androidx.compose.ui.graphics.Color(0xFF1B3A73),
                )
            }
            androidx.compose.foundation.layout.Box(
                Modifier.size(120.dp).androidxBackground(androidx.compose.ui.graphics.Color(0xFF111418)),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(com.gng.app.R.drawable.ic_notification), null,
                    Modifier.size(72.dp), tint = androidx.compose.ui.graphics.Color.White,
                )
            }
        }
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            listOf(48, 36, 24).forEach { dp ->
                androidx.compose.ui.viewinterop.AndroidView({ ctx ->
                    android.widget.ImageView(ctx).apply { setImageResource(com.gng.app.R.mipmap.ic_launcher) }
                }, Modifier.size(dp.dp))
            }
        }
    }
}

private fun Modifier.androidxBackground(color: androidx.compose.ui.graphics.Color, circle: Boolean = false) =
    this.then(Modifier.background(color, if (circle) androidx.compose.foundation.shape.CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(24.dp)))
