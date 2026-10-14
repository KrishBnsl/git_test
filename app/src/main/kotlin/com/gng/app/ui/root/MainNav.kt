package com.gng.app.ui.root

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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
fun MainNav(me: Profile, onProfileSaved: (Profile) -> Unit, openGroup: String? = null, onOpened: () -> Unit = {}) {
    val nav = rememberNavController()
    val context = LocalContext.current
    // Tapped a notification: jump into that chat.
    LaunchedEffect(openGroup) {
        if (openGroup != null) {
            nav.navigate("group/$openGroup") { launchSingleTop = true }
            onOpened()
        }
    }
    // Ask once for notification permission (Android 13+), so pushes can show.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
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
