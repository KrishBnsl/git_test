package com.gng.app.debug

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
import com.gng.app.ui.auth.AuthContent
import com.gng.app.ui.group.AddExamSheet
import com.gng.app.ui.group.AddMaterialSheet
import com.gng.app.ui.group.ChatContent
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
        setContent {
            GngTheme(if (dark) ThemeMode.Dark else ThemeMode.Light, dynamic) {
                Surface(Modifier.fillMaxSize()) { Screen(screen) }
            }
        }
    }
}

private val noop = {}

@Composable
private fun MapScreen(selected: String?) {
    val s = SampleData
    val pins = com.gng.core.MapRules.pins(s.presence.values.map { it.toMapSource() }, SampleData.now)
    MapContent(s.ctx, pins, selected, true, {}, { null }, {}, { _, _ -> })
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
        "groups" -> GroupsContent(s.me, s.groups, false, null, {}, {}, {}, {}, {}, {})
        "groups-empty" -> GroupsContent(s.me, emptyList(), false, null, {}, {}, {}, {}, {}, {})
        "chat" -> Group(GroupTab.Chat) { ChatContent(s.ctx, s.messages, s.reactions, {}, { _, _ -> }, {}) }
        "chat-empty" -> Group(GroupTab.Chat) { ChatContent(s.ctx, emptyList(), emptyList(), {}, { _, _ -> }, {}) }
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
                ), {}, {}, {}, {}, { _, _ -> }, SampleData.now,
            )
        }
        "study" -> Group(GroupTab.Study) {
            StudyContent(s.ctx, s.exams, s.materials, s.votes, null, {}, {}, {}, {}, {})
        }
        "timetable" -> TimetableContent(s.myClasses, null, {}, {}, {}, {}, DayOfWeek.of(java.time.LocalDate.now().dayOfWeek.value))
        "settings" -> SettingsContent(s.me, ThemeMode.System, true, {}, {}, {}, {}, {}, {})
        "map" -> Group(GroupTab.Map) { MapScreen(selected = null) }
        "map-selected" -> Group(GroupTab.Map) { MapScreen(selected = "u-bhavya") }
        "map-empty" -> Group(GroupTab.Map) {
            MapContent(s.ctx, emptyList(), null, true, {}, { null }, {}, { _, _ -> })
        }
        "chat-locked" -> Group(GroupTab.Chat) { ChatContent(s.memberCtx, s.messages, s.reactions, {}, { _, _ -> }, {}) }
        "group-settings" -> GroupSettingsContent(s.ctx, false, null, {}, {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {})
        "group-settings-member" -> GroupSettingsContent(s.memberCtx, false, null, {}, {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {})
        "sheet-group-edit" -> EditGroupSheet("Hostel H4 Maggi Club", "restaurant", {}, { _, _ -> })
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
