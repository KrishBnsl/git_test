package com.gng.app.ui.group

import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.AppContainer
import com.gng.app.call.CallActivity
import com.gng.app.call.callPermissions
import com.gng.app.crypto.IdentityKeys
import com.gng.app.data.Group
import com.gng.app.data.Profile
import com.gng.app.data.parseInstant
import com.gng.app.ui.chat.ChatScreen
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.Loading
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** Everything the tabs share: the conversation and who's in it. */
data class GroupContext(val group: Group, val me: Profile, val members: Map<String, Profile>) {
    val isDm get() = group.isDm
    val other: Profile? get() = if (isDm) members.values.firstOrNull { it.id != me.id } else null
    val title: String get() = if (isDm) other?.let { "${it.emoji} ${it.displayName}" } ?: "DM" else "${group.emoji} ${group.name}"
    fun nameOf(userId: String?) = members[userId]?.displayName ?: "someone"
    fun emojiOf(userId: String?) = members[userId]?.emoji ?: "👤"
}

class GroupHomeViewModel(private val c: AppContainer, private val groupId: String) : ViewModel() {
    var ctx by mutableStateOf<GroupContext?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var mutedUntil by mutableStateOf<Instant?>(null)
        private set

    fun load(me: Profile) = viewModelScope.launch {
        // Open instantly from the cache, then refresh.
        val cachedConversation = c.chat.cachedConversations()?.firstOrNull { it.id == groupId }
        val cachedMembers = c.chat.cachedMembers(groupId)
        if (ctx == null && cachedConversation != null && cachedMembers != null) {
            ctx = GroupContext(cachedConversation.toGroup(), me, cachedMembers.associate { it.userId to it.profiles })
        }
        mutedUntil = cachedConversation?.mutedUntil?.let(::parseInstant)?.takeIf { it.isAfter(Instant.now()) }
        runCatching {
            val group = c.groups.get(groupId)
            val members = c.groups.members(groupId)
            c.chat.cacheMembers(groupId, members)
            GroupContext(group, me, members.associate { it.userId to it.profiles })
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

    fun call(video: Boolean, title: String) = viewModelScope.launch {
        runCatching { c.calls.start(groupId, title, video) }.onFailure { error = it.friendly() }
    }

    /** The code both of you should see in a DM, if nobody's key was swapped. */
    suspend fun safetyCode(): String? {
        val other = ctx?.other ?: return null
        val theirs = runCatching { c.e2ee.publicKeyOf(other.id) }.getOrNull() ?: return null
        return IdentityKeys.safetyCode(c.e2ee.myPublicKey, theirs.publicKey)
    }
}

private enum class Tab(val emoji: String, val label: String) {
    Chat("💬", "Hangout"), Plans("🍛", "Plans"), Squad("📍", "Squad"), Study("📚", "Study")
}

private val FOREVER: Duration = Duration.ofDays(3650)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupHomeScreen(groupId: String, me: Profile, onBack: () -> Unit, onTimetable: () -> Unit) {
    val vm = gngViewModel(key = "group-$groupId") { GroupHomeViewModel(it, groupId) }
    LaunchedEffect(groupId, me) { vm.load(me) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var safetyCode by remember { mutableStateOf<String?>(null) }
    var pendingCall by remember { mutableStateOf<Boolean?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ctx = vm.ctx

    fun startCall(video: Boolean) {
        val title = ctx?.title ?: return
        vm.call(video, title)
        context.startActivity(CallActivity.intent(context, "", CallActivity.ACTION_SHOW))
    }
    val askCallPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val video = pendingCall ?: return@rememberLauncherForActivityResult
        pendingCall = null
        if (granted.values.all { it }) startCall(video) else vm.error = "Calls need the microphone" + if (video) " and camera" else ""
    }
    fun requestCall(video: Boolean) {
        val missing = callPermissions(video).filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startCall(video) else {
            pendingCall = video
            askCallPermissions.launch(missing.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ctx?.title ?: "…", maxLines = 1)
                        if (vm.mutedUntil != null) Text("🔕 muted", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (ctx != null) {
                        IconButton(onClick = { requestCall(video = false) }) { Text("📞", fontSize = 20.sp) }
                        IconButton(onClick = { requestCall(video = true) }) { Text("📹", fontSize = 20.sp) }
                        if (!ctx.isDm) {
                            IconButton(onClick = {
                                val text = "Join ${ctx.group.emoji} ${ctx.group.name} on gng! Code: ${ctx.group.inviteCode}"
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                                        "Invite the gang",
                                    ),
                                )
                            }) { Icon(Icons.Filled.Share, "Invite") }
                        }
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (vm.mutedUntil != null) {
                            DropdownMenuItem(text = { Text("🔔 Unmute") }, onClick = { menu = false; vm.mute(null) })
                        } else {
                            DropdownMenuItem(text = { Text("🔕 Mute for 8 hours") }, onClick = { menu = false; vm.mute(Duration.ofHours(8)) })
                            DropdownMenuItem(text = { Text("🔕 Mute for a week") }, onClick = { menu = false; vm.mute(Duration.ofDays(7)) })
                            DropdownMenuItem(text = { Text("🔕 Mute always") }, onClick = { menu = false; vm.mute(FOREVER) })
                        }
                        if (ctx?.isDm == true) {
                            DropdownMenuItem(text = { Text("🔐 Verify encryption") }, onClick = {
                                menu = false
                                scope.launch { safetyCode = vm.safetyCode() ?: "They haven't opened the new app yet, so there's no key to check." }
                            })
                        }
                        DropdownMenuItem(text = { Text("🗓️ My classes") }, onClick = { menu = false; onTimetable() })
                        DropdownMenuItem(
                            text = { Text(if (ctx?.isDm == true) "🗑️ Delete chat" else "👋 Leave gang") },
                            onClick = { menu = false; confirmLeave = true },
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (ctx != null && !ctx.isDm) {
                NavigationBar {
                    Tab.entries.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(t.emoji, fontSize = 22.sp) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ctx == null && vm.error != null -> EmptyState("📡", "Couldn't load this chat", vm.error!!)
                ctx == null -> Loading()
                ctx.isDm -> ChatScreen(ctx)
                else -> when (Tab.entries[tab]) {
                    Tab.Chat -> ChatScreen(ctx)
                    Tab.Plans -> PlansTab(ctx)
                    Tab.Squad -> SquadTab(ctx, onTimetable)
                    Tab.Study -> StudyTab(ctx)
                }
            }
        }
    }

    if (confirmLeave) {
        val dm = ctx?.isDm == true
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (dm) "Delete this chat?" else "Leave ${ctx?.group?.name ?: "this gang"}?") },
            text = {
                Text(
                    if (dm) "It disappears from your list. If either of you messages again it comes back, without the old messages."
                    else "You can rejoin later with the invite code. You won't be able to read messages from before you rejoin.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmLeave = false; vm.leave(onBack) }) { Text(if (dm) "Delete" else "Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }

    safetyCode?.let { code ->
        AlertDialog(
            onDismissRequest = { safetyCode = null },
            title = { Text("🔐 Safety code") },
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


