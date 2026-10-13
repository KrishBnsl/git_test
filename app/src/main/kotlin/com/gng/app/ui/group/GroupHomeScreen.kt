package com.gng.app.ui.group

import android.content.Intent
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.AppContainer
import com.gng.app.data.Group
import com.gng.app.data.Profile
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.Loading
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import kotlinx.coroutines.launch

/** Everything the tabs share: the group and who's in it. */
data class GroupContext(val group: Group, val me: Profile, val members: Map<String, Profile>) {
    fun nameOf(userId: String?) = members[userId]?.displayName ?: "someone"
    fun emojiOf(userId: String?) = members[userId]?.emoji ?: "👤"
}

class GroupHomeViewModel(private val c: AppContainer, private val groupId: String) : ViewModel() {
    var ctx by mutableStateOf<GroupContext?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun load(me: Profile) = viewModelScope.launch {
        runCatching {
            val group = c.groups.get(groupId)
            val members = c.groups.members(groupId).associate { it.userId to it.profiles }
            GroupContext(group, me, members)
        }.onSuccess { ctx = it; error = null }.onFailure { error = it.friendly() }
    }

    fun leave(onLeft: () -> Unit) = viewModelScope.launch {
        runCatching { c.groups.leave(groupId, c.auth.userId!!) }
            .onSuccess { onLeft() }
            .onFailure { error = it.friendly() }
    }
}

private enum class Tab(val emoji: String, val label: String) {
    Chat("💬", "Hangout"), Plans("🍛", "Plans"), Squad("📍", "Squad"), Study("📚", "Study")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupHomeScreen(groupId: String, me: Profile, onBack: () -> Unit, onTimetable: () -> Unit) {
    val vm = gngViewModel(key = "group-$groupId") { GroupHomeViewModel(it, groupId) }
    LaunchedEffect(groupId, me) { vm.load(me) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val ctx = vm.ctx

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ctx?.let { "${it.group.emoji} ${it.group.name}" } ?: "…") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (ctx != null) {
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
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("🗓️ My classes") }, onClick = { menu = false; onTimetable() })
                        DropdownMenuItem(text = { Text("👋 Leave gang") }, onClick = { menu = false; confirmLeave = true })
                    }
                },
            )
        },
        bottomBar = {
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
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ctx == null && vm.error != null -> EmptyState("📡", "Couldn't load the gang", vm.error!!)
                ctx == null -> Loading()
                else -> when (Tab.entries[tab]) {
                    Tab.Chat -> ChatTab(ctx)
                    Tab.Plans -> PlansTab(ctx)
                    Tab.Squad -> SquadTab(ctx, onTimetable)
                    Tab.Study -> StudyTab(ctx)
                }
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave ${ctx?.group?.name ?: "this gang"}?") },
            text = { Text("You can rejoin later with the invite code.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; vm.leave(onBack) }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
}
