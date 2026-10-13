package com.gng.app.ui.root

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gng.app.data.Profile
import com.gng.app.ui.group.GroupHomeScreen
import com.gng.app.ui.groups.GroupsScreen
import com.gng.app.ui.profile.ProfileScreen
import com.gng.app.ui.settings.SettingsScreen
import com.gng.app.ui.timetable.TimetableScreen

@Composable
fun MainNav(me: Profile, onProfileSaved: (Profile) -> Unit) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "groups") {
        composable("groups") {
            GroupsScreen(
                me = me,
                onOpen = { nav.navigate("group/${it.id}") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("group/{groupId}") { entry ->
            GroupHomeScreen(
                groupId = entry.arguments?.getString("groupId")!!,
                me = me,
                onBack = { nav.popBackStack() },
                onTimetable = { nav.navigate("timetable") },
            )
        }
        composable("timetable") { TimetableScreen(me = me, onBack = { nav.popBackStack() }) }
        composable("settings") {
            SettingsScreen(
                me = me,
                onEditProfile = { nav.navigate("profile") },
                onTimetable = { nav.navigate("timetable") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("profile") {
            ProfileScreen(userId = me.id, existing = me, onSaved = {
                onProfileSaved(it)
                nav.popBackStack()
            })
        }
    }
}
