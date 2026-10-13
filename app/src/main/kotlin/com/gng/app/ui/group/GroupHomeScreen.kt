package com.gng.app.ui.group

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import com.gng.app.AppContainer
import com.gng.app.data.Group
import com.gng.app.data.GroupPermission
import com.gng.app.data.GroupRole
import com.gng.app.data.Message
import com.gng.app.data.Profile
import com.gng.app.ui.components.AvatarInfo
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.GroupAvatar
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.LocalSnackbar
import com.gng.app.ui.components.Loading
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.icons.GngIcons
import kotlinx.coroutines.launch

/** Everything the tabs share: the group and who's in it. */
data class GroupContext(
    val group: Group,
    val me: Profile,
    val members: Map<String, Profile>,
    /** user id to "admin" or "member". */
    val roles: Map<String, String> = emptyMap(),
) {
    val isAdmin: Boolean get() = roles[me.id] == GroupRole.ADMIN
    val canEditGroup: Boolean get() = isAdmin || group.editPermission == GroupPermission.MEMBERS
    val canSendMessages: Boolean get() = isAdmin || group.messagePermission == GroupPermission.EVERYONE
    fun isAdmin(userId: String): Boolean = roles[userId] == GroupRole.ADMIN

    fun nameOf(userId: String?): String = members[userId]?.displayName ?: "Someone"
    fun person(userId: String?): AvatarInfo =
        AvatarInfo(userId ?: "?", nameOf(userId), members[userId]?.avatarUrl)
}

class GroupHomeViewModel(private val c: AppContainer, private val groupId: String) : ViewModel() {
    var ctx by mutableStateOf<GroupContext?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var watching = false

    /** Reloads the gang whenever its row changes (photo, name, permissions), so everyone sees it live. */
    fun watch(me: Profile) {
        if (watching) return
        watching = true
        viewModelScope.launch {
            runCatching { c.groups.changes(groupId).collect { load(me).join() } }
        }
    }

    fun load(me: Profile) = viewModelScope.launch {
        runCatching {
            val group = c.groups.get(groupId)
            val rows = c.groups.members(groupId)
            GroupContext(group, me, rows.associate { it.userId to it.profiles }, rows.associate { it.userId to it.role })
        }.onSuccess { ctx = it; error = null }.onFailure { error = it.friendly() }
    }

    fun leave(onLeft: () -> Unit) = viewModelScope.launch {
        runCatching { c.groups.leave(groupId, c.auth.userId!!) }
            .onSuccess { onLeft() }
            .onFailure { error = it.friendly() }
    }
}

enum class GroupTab(val label: String, val icon: Int, val iconSelected: Int) {
    Chat("Chat", GngIcons.Forum, GngIcons.ForumFill),
    Plans("Plans", GngIcons.Event, GngIcons.EventFill),
    Map("Map", GngIcons.Map, GngIcons.MapFill),
    Squad("Squad", GngIcons.Groups, GngIcons.GroupsFill),
    Study("Study", GngIcons.School, GngIcons.SchoolFill),
}

@Composable
fun GroupHomeScreen(groupId: String, me: Profile, onBack: () -> Unit, onTimetable: () -> Unit, onSettings: () -> Unit) {
    val vm = gngViewModel(key = "group-$groupId") { GroupHomeViewModel(it, groupId) }
    LaunchedEffect(groupId, me) {
        vm.load(me)
        vm.watch(me)
    }
    // Coming back from group settings: pick up whatever changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load(me) }
    var tab by rememberSaveable { mutableIntStateOf(GroupTab.Chat.ordinal) }
    val ctx = vm.ctx

    GroupScaffold(
        ctx = ctx,
        error = vm.error,
        tab = GroupTab.entries[tab],
        onTab = { tab = it.ordinal },
        onBack = onBack,
        onTimetable = onTimetable,
        onSettings = onSettings,
        onLeave = { vm.leave(onBack) },
    ) { current ->
        if (ctx != null) {
            when (current) {
                GroupTab.Chat -> ChatTab(ctx, onOpenEvent = { m -> tab = tabForEvent(m).ordinal })
                GroupTab.Plans -> PlansTab(ctx)
                GroupTab.Map -> MapTab(ctx, onOpenSquad = { tab = GroupTab.Squad.ordinal })
                GroupTab.Squad -> SquadTab(ctx, onTimetable)
                GroupTab.Study -> StudyTab(ctx)
            }
        }
    }
}

private fun tabForEvent(m: Message): GroupTab = when (m.kind) {
    "plan" -> GroupTab.Plans
    else -> GroupTab.Study
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScaffold(
    ctx: GroupContext?,
    error: String?,
    tab: GroupTab,
    onTab: (GroupTab) -> Unit,
    onBack: () -> Unit,
    onTimetable: () -> Unit,
    onSettings: () -> Unit,
    onLeave: () -> Unit,
    content: @Composable (GroupTab) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (ctx != null) {
                        Row(
                            Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onSettings).padding(vertical = 4.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GroupAvatar(ctx.group.icon, ctx.group.photoUrl, size = 40.dp)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(ctx.group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text(
                                    "${ctx.members.size} members",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { GngIcon(GngIcons.ArrowBack, "Back") }
                },
                actions = {
                    if (ctx != null) {
                        IconButton(onClick = {
                            val text = "Join ${ctx.group.name} on gng. Code: ${ctx.group.inviteCode}"
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                                    "Invite the gang",
                                ),
                            )
                        }) { GngIcon(GngIcons.PersonAdd, "Invite") }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { GngIcon(GngIcons.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Group settings") },
                                leadingIcon = { GngIcon(GngIcons.Settings, null) },
                                onClick = { menu = false; onSettings() },
                            )
                            DropdownMenuItem(
                                text = { Text("My classes") },
                                leadingIcon = { GngIcon(GngIcons.CalendarMonth, null) },
                                onClick = { menu = false; onTimetable() },
                            )
                            DropdownMenuItem(
                                text = { Text("Leave gang") },
                                leadingIcon = { GngIcon(GngIcons.Logout, null) },
                                onClick = { menu = false; confirmLeave = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                GroupTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { onTab(t) },
                        icon = { GngIcon(if (tab == t) t.iconSelected else t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        CompositionLocalProvider(LocalSnackbar provides snackbar) {
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                when {
                    ctx == null && error != null ->
                        EmptyState(GngIcons.WifiOff, "Couldn't load the gang", error, Modifier.align(Alignment.Center))
                    ctx == null -> Loading()
                    else -> AnimatedContent(
                        targetState = tab,
                        transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
                        label = "tab",
                    ) { current -> content(current) }
                }
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            icon = { GngIcon(GngIcons.Logout, null) },
            title = { Text("Leave ${ctx?.group?.name ?: "this gang"}?") },
            text = { Text("You can rejoin later with the invite code.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onLeave() }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
}
