package com.gng.app.ui.group

import kotlinx.coroutines.async
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gng.app.container
import com.gng.app.call.CallPhase
import com.gng.app.ui.groups.OngoingCallBar
import androidx.compose.foundation.shape.CircleShape
import com.gng.app.ui.people.PhotoViewer
import com.gng.app.ui.people.PersonSheet
import com.gng.app.ui.people.LocalOpenPerson
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
import com.gng.app.data.parseInstant
import com.gng.app.call.CallActivity
import com.gng.app.call.callPermissions
import com.gng.app.crypto.IdentityKeys
import com.gng.app.ui.chat.ChatScreen
import com.gng.app.ui.components.Avatar
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.ContextCompat
import java.time.Duration
import java.time.Instant
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
import com.gng.app.ui.invite.InviteSheet
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

    /** A two-person direct message rather than a gang. */
    val isDm: Boolean get() = group.isDm
    /** In a DM, the other person. */
    val other: Profile? get() = if (isDm) members.values.firstOrNull { it.id != me.id } else null
    val title: String get() = if (isDm) other?.displayName ?: "Direct message" else group.name

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

    var mutedUntil by mutableStateOf<Instant?>(null)
        private set

    fun load(me: Profile) = viewModelScope.launch {
        // Open instantly from the cache, then refresh from the server.
        val cachedConversation = c.chat.cachedConversations()?.firstOrNull { it.id == groupId }
        if (ctx == null && cachedConversation != null) {
            c.chat.cachedMembers(groupId)?.let { rows ->
                ctx = GroupContext(cachedConversation.toGroup(), me, rows.associate { it.userId to it.profiles }, rows.associate { it.userId to it.role })
            }
        }
        cachedConversation?.let { mutedUntil = it.mutedUntil?.let(::parseInstant)?.takeIf { t -> t.isAfter(Instant.now()) } }
        runCatching {
            // Both at once: on a first open this is all that stands between you and the chat.
            val (group, rows) = kotlinx.coroutines.coroutineScope {
                val g = async { c.groups.get(groupId) }
                val m = async { c.groups.members(groupId) }
                g.await() to m.await()
            }
            c.chat.cacheMembers(groupId, rows)
            GroupContext(group, me, rows.associate { it.userId to it.profiles }, rows.associate { it.userId to it.role })
        }.onSuccess { ctx = it; error = null }.onFailure { if (ctx == null) error = it.friendly() }
    }

    fun leave(onLeft: () -> Unit) = viewModelScope.launch {
        runCatching { c.groups.leave(groupId, c.auth.userId!!) }
            .onSuccess {
                c.cache.clearGroup(groupId)
                onLeft()
            }
            .onFailure { error = it.friendly() }
    }

    fun mute(forHow: Duration?) = viewModelScope.launch {
        val until = forHow?.let { Instant.now().plus(it) }
        runCatching { c.chat.mute(groupId, c.auth.userId!!, until) }
            .onSuccess {
                mutedUntil = until
                runCatching { c.chat.conversations() }
            }
            .onFailure { error = it.friendly() }
    }

    fun call(video: Boolean) = viewModelScope.launch {
        val title = ctx?.title ?: return@launch
        runCatching { c.calls.start(groupId, title, video) }.onFailure { error = it.friendly() }
    }

    /** The code both people should see in a DM, if nobody's key was swapped. */
    suspend fun safetyCode(): String? {
        val other = ctx?.other ?: return null
        val theirs = runCatching { c.e2ee.publicKeyOf(other.id) }.getOrNull() ?: return null
        return IdentityKeys.safetyCode(c.e2ee.myPublicKey, theirs.publicKey)
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
fun GroupHomeScreen(
    groupId: String,
    me: Profile,
    onBack: () -> Unit,
    onTimetable: () -> Unit,
    onSettings: () -> Unit,
    onMessage: (userId: String) -> Unit,
    /** "voice" or "video": start a call as soon as the chat opens (Call back from a missed call). */
    startCall: String? = null,
) {
    val vm = gngViewModel(key = "group-$groupId") { GroupHomeViewModel(it, groupId) }
    LaunchedEffect(groupId, me) {
        vm.load(me)
        vm.watch(me)
    }
    // Coming back from group settings: pick up whatever changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load(me) }
    var tab by rememberSaveable { mutableIntStateOf(GroupTab.Chat.ordinal) }
    var safetyCode by remember { mutableStateOf<String?>(null) }
    var pendingCall by remember { mutableStateOf<Boolean?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ctx = vm.ctx

    fun startCall(video: Boolean) {
        vm.call(video)
        context.startActivity(CallActivity.intent(context, "", CallActivity.ACTION_SHOW))
    }
    val askCallPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val video = pendingCall ?: return@rememberLauncherForActivityResult
        pendingCall = null
        if (granted[android.Manifest.permission.RECORD_AUDIO] == true) startCall(video)
    }
    fun requestCall(video: Boolean) {
        val missing = callPermissions(video).filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startCall(video) else {
            pendingCall = video
            askCallPermissions.launch(missing.toTypedArray())
        }
    }

    // Tap anyone (in chat, Squad, the DM header) to see their profile.
    var personFor by remember { mutableStateOf<String?>(null) }
    CompositionLocalProvider(LocalOpenPerson provides { personFor = it }) {
    // Opened with "Call back": start the call once the chat has loaded.
    var autoCalled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(ctx != null, startCall) {
        if (ctx != null && startCall != null && !autoCalled) {
            autoCalled = true
            requestCall(startCall == "video")
        }
    }
    GroupScaffold(
        ctx = ctx,
        error = vm.error,
        tab = GroupTab.entries[tab],
        onTab = { tab = it.ordinal },
        onBack = onBack,
        onTimetable = onTimetable,
        onSettings = onSettings,
        onLeave = { vm.leave(onBack) },
        mutedUntil = vm.mutedUntil,
        onMute = vm::mute,
        onCall = ::requestCall,
        onVerify = {
            scope.launch { safetyCode = vm.safetyCode() ?: "They haven't opened the new version of gng yet, so there's no key to check." }
        },
    ) { current ->
        if (ctx != null) {
          Column {
            // You're on a call (maybe from another chat): one tap back to it.
            val ongoing by context.container.calls.state.collectAsStateWithLifecycle()
            ongoing?.takeIf { it.phase != CallPhase.Ended && it.phase != CallPhase.Ringing }?.let { call ->
                OngoingCallBar(call) { context.startActivity(CallActivity.intent(context, call.callId, CallActivity.ACTION_SHOW)) }
            }
            when (if (ctx.isDm) GroupTab.Chat else current) {
                GroupTab.Chat -> ChatScreen(ctx, onOpenEvent = { m -> tab = tabForEvent(m.kind).ordinal }, onClosed = onBack)
                GroupTab.Plans -> PlansTab(ctx)
                GroupTab.Map -> MapTab(ctx, onOpenSquad = { tab = GroupTab.Squad.ordinal }, onMessage = onMessage)
                GroupTab.Squad -> SquadTab(ctx, onTimetable, onMessage)
                GroupTab.Study -> StudyTab(ctx)
            }
          }
        }
    }
    }
    personFor?.let { id ->
        val p = ctx?.members?.get(id)
        if (p == null) {
            LaunchedEffect(id) { personFor = null }
        } else {
            PersonSheet(p, isMe = id == ctx.me.id, onDismiss = { personFor = null }, onMessage = { personFor = null; onMessage(it) })
        }
    }

    safetyCode?.let { code ->
        AlertDialog(
            onDismissRequest = { safetyCode = null },
            icon = { GngIcon(GngIcons.VerifiedUser, null) },
            title = { Text("Safety code") },
            text = {
                Column {
                    Text("Compare this with ${ctx?.other?.displayName ?: "them"}, in person or on a call. If it matches on both phones, nobody is listening in.")
                    Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = { TextButton(onClick = { safetyCode = null }) { Text("Done") } },
        )
    }
}

private fun tabForEvent(kind: String): GroupTab = when (kind) {
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
    mutedUntil: Instant? = null,
    onMute: (Duration?) -> Unit = {},
    onCall: ((video: Boolean) -> Unit)? = null,
    onVerify: () -> Unit = {},
    content: @Composable (GroupTab) -> Unit,
) {
    val dm = ctx?.isDm == true
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    if (inviting && ctx != null) InviteSheet(ctx.group, onDismiss = { inviting = false })

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (ctx != null) {
                        val openPerson = LocalOpenPerson.current
                        var viewPhoto by remember { mutableStateOf(false) }
                        if (viewPhoto) PhotoViewer(ctx.group.photoUrl, ctx.group.name, ctx.group.id, onDismiss = { viewPhoto = false })
                        Row(
                            Modifier.clip(MaterialTheme.shapes.medium)
                                // A gang's name opens its settings; a DM's opens the other person's profile.
                                .clickable { if (dm) ctx.other?.let { openPerson(it.id) } else onSettings() }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val other = ctx.other
                            if (dm) {
                                Avatar(ctx.title, other?.avatarUrl, size = 40.dp, colorKey = other?.id ?: ctx.group.id)
                            } else {
                                // Tap the gang's photo to see it big.
                                Box(Modifier.clip(CircleShape).clickable { viewPhoto = true }) {
                                    GroupAvatar(ctx.group.icon, ctx.group.photoUrl, size = 40.dp, colorKey = ctx.group.id)
                                }
                            }
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(ctx.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (mutedUntil != null) {
                                        GngIcon(GngIcons.NotificationsOff, "Muted", size = 14.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(
                                        if (dm) "End-to-end encrypted" else "${ctx.members.size} members",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { GngIcon(GngIcons.ArrowBack, "Back") }
                },
                actions = {
                    if (ctx != null && onCall != null) {
                        IconButton(onClick = { onCall(false) }) { GngIcon(GngIcons.Call, "Voice call") }
                        IconButton(onClick = { onCall(true) }) { GngIcon(GngIcons.Videocam, "Video call") }
                    }
                    if (ctx != null && !dm && onCall == null) {
                        IconButton(onClick = { inviting = true }) { GngIcon(GngIcons.QrCode, "Invite") }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { GngIcon(GngIcons.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (!dm) {
                                DropdownMenuItem(
                                    text = { Text("Group settings") },
                                    leadingIcon = { GngIcon(GngIcons.Settings, null) },
                                    onClick = { menu = false; onSettings() },
                                )
                                if (ctx != null && onCall != null) {
                                    DropdownMenuItem(
                                        text = { Text("Invite people") },
                                        leadingIcon = { GngIcon(GngIcons.PersonAdd, null) },
                                        onClick = {
                                            menu = false
                                            inviting = true
                                        },
                                    )
                                }
                            }
                            if (mutedUntil != null) {
                                DropdownMenuItem(
                                    text = { Text("Unmute") },
                                    leadingIcon = { GngIcon(GngIcons.Notifications, null) },
                                    onClick = { menu = false; onMute(null) },
                                )
                            } else {
                                listOf("Mute for 8 hours" to Duration.ofHours(8), "Mute for a week" to Duration.ofDays(7), "Mute always" to Duration.ofDays(3650))
                                    .forEach { (label, d) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            leadingIcon = { GngIcon(GngIcons.NotificationsOff, null) },
                                            onClick = { menu = false; onMute(d) },
                                        )
                                    }
                            }
                            if (dm) {
                                DropdownMenuItem(
                                    text = { Text("Verify encryption") },
                                    leadingIcon = { GngIcon(GngIcons.VerifiedUser, null) },
                                    onClick = { menu = false; onVerify() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("My classes") },
                                leadingIcon = { GngIcon(GngIcons.CalendarMonth, null) },
                                onClick = { menu = false; onTimetable() },
                            )
                            DropdownMenuItem(
                                text = { Text(if (dm) "Delete chat" else "Leave gang") },
                                leadingIcon = { GngIcon(if (dm) GngIcons.Delete else GngIcons.Logout, null) },
                                onClick = { menu = false; confirmLeave = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!dm) NavigationBar {
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
            title = { Text(if (dm) "Delete this chat?" else "Leave ${ctx?.group?.name ?: "this gang"}?") },
            text = {
                Text(
                    if (dm) "It disappears from your list. If either of you messages again it comes back, without the old messages."
                    else "You can rejoin later with the invite code. You won't be able to read messages from before you rejoin.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onLeave() }) { Text(if (dm) "Delete" else "Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
}
