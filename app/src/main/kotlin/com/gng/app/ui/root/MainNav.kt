package com.gng.app.ui.root

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.gng.app.data.Profile
import com.gng.app.ui.dm.DmChatScreen
import com.gng.app.ui.dm.DmInboxScreen
import com.gng.app.ui.dm.DmInboxViewModel
import com.gng.app.ui.gngActivityViewModel
import com.gng.app.ui.group.GroupHomeScreen
import com.gng.app.ui.group.GroupSettingsScreen
import com.gng.app.ui.groups.GroupsScreen
import com.gng.app.ui.profile.ProfileScreen
import com.gng.app.ui.settings.SettingsScreen
import com.gng.app.ui.timetable.TimetableScreen

private const val DURATION = 300

@Composable
fun MainNav(me: Profile, onProfileSaved: (Profile) -> Unit) {
    val nav = rememberNavController()
    // Lives as long as the activity, so the unread badge on the home screen is always current.
    val dmInbox = gngActivityViewModel(key = "dm-inbox-${me.id}") { DmInboxViewModel(it, me.id) }
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
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { dmInbox.refresh() }
            GroupsScreen(
                me = me,
                unreadDms = dmInbox.unread,
                onOpen = { nav.navigate("group/${it.id}") },
                onSettings = { nav.navigate("settings") },
                onMessages = { nav.navigate("dms") },
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
        composable("dms") {
            DmInboxScreen(me = me, vm = dmInbox, onBack = { nav.popBackStack() }, onOpen = { nav.navigate("dm/$it") })
        }
        composable("dm/{userId}") { entry ->
            DmChatScreen(me = me, otherId = entry.arguments?.getString("userId")!!, onBack = { nav.popBackStack() })
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
