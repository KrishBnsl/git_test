package com.gng.app.ui.root

import com.gng.app.ui.invite.JoinGangDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
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
import com.gng.app.container
import com.gng.app.ui.components.Loading
import com.gng.app.ui.friendly
import com.gng.app.ui.group.GroupHomeScreen
import com.gng.app.ui.group.GroupSettingsScreen
import com.gng.app.ui.groups.GroupsScreen
import com.gng.app.ui.profile.ProfileScreen
import com.gng.app.ui.settings.SettingsScreen
import com.gng.app.ui.timetable.TimetableScreen

private const val DURATION = 300

@Composable
fun MainNav(
    me: Profile,
    onProfileSaved: (Profile) -> Unit,
    openGroup: String? = null,
    onOpened: () -> Unit = {},
    inviteCode: String? = null,
    onInviteHandled: () -> Unit = {},
) {
    val nav = rememberNavController()
    val context = LocalContext.current
    // Joining from a QR code, an invite link, or a typed code: show the gang, then join.
    var joinCode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(inviteCode) {
        if (inviteCode != null) {
            joinCode = inviteCode
            onInviteHandled()
        }
    }
    joinCode?.let { code ->
        JoinGangDialog(
            code,
            onDismiss = { joinCode = null },
            onJoined = { g -> joinCode = null; nav.navigate("group/${g.id}") { launchSingleTop = true } },
            onOpenExisting = { id -> joinCode = null; nav.navigate("group/$id") { launchSingleTop = true } },
        )
    }
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
    NavHost(
        nav,
        startDestination = "groups",
        // Forward navigation fades in with a short slide; going back mirrors it.
        enterTransition = { fadeIn(tween(DURATION)) + slideInHorizontally(tween(DURATION)) { it / 14 } },
        exitTransition = { fadeOut(tween(DURATION / 2)) },
        popEnterTransition = { fadeIn(tween(DURATION)) },
        popExitTransition = { fadeOut(tween(DURATION / 2)) + slideOutHorizontally(tween(DURATION)) { it / 14 } },
    ) {
        composable("groups") {
            GroupsScreen(
                me = me,
                onOpen = { nav.navigate("group/${it.id}") },
                onSettings = { nav.navigate("settings") },
                onJoinCode = { joinCode = it },
            )
        }
        composable("group/{groupId}") { entry ->
            GroupHomeScreen(
                groupId = entry.arguments?.getString("groupId")!!,
                me = me,
                onBack = { nav.popBackStack() },
                onTimetable = { nav.navigate("timetable") },
                onSettings = { nav.navigate("group/${entry.arguments?.getString("groupId")}/settings") },
                onMessage = { nav.navigate("dm/$it") },
            )
        }
        composable("group/{groupId}/settings") { entry ->
            GroupSettingsScreen(
                groupId = entry.arguments?.getString("groupId")!!,
                me = me,
                onBack = { nav.popBackStack() },
                // Leaving removes the gang from your list, so go all the way back to it.
                onLeft = { nav.popBackStack("groups", inclusive = false) },
                onMessage = { nav.navigate("dm/$it") },
            )
        }
        // "Message" on someone's profile: find or create the DM, then show it like any other chat.
        composable("dm/{userId}") { entry ->
            OpenDm(
                userId = entry.arguments?.getString("userId")!!,
                onOpened = { dmId ->
                    nav.navigate("group/$dmId") { popUpTo("dm/{userId}") { inclusive = true } }
                },
                onBack = { nav.popBackStack() },
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
            ProfileScreen(
                userId = me.id,
                existing = me,
                onSaved = {
                    onProfileSaved(it)
                    nav.popBackStack()
                },
                onBack = { nav.popBackStack() },
            )
        }
    }
}

@Composable
private fun OpenDm(userId: String, onOpened: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(userId) {
        runCatching { context.container.chat.openDm(userId) }
            .onSuccess { onOpened(it.id) }
            .onFailure {
                Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show()
                onBack()
            }
    }
    Loading()
}
