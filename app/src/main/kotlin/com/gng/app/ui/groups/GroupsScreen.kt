package com.gng.app.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.data.Group
import com.gng.app.data.GroupRepository
import com.gng.app.data.Profile
import com.gng.app.ui.components.Avatar
import com.gng.app.ui.components.Choice
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.GroupAvatar
import com.gng.app.ui.components.GngSheet
import com.gng.app.ui.components.IconPicker
import com.gng.app.ui.components.InfoChip
import com.gng.app.ui.components.Loading
import com.gng.app.ui.components.SegmentedChoice
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.icons.GngIcons
import com.gng.app.ui.icons.GroupIcons
import com.gng.core.InviteCodes
import kotlinx.coroutines.launch

class GroupsViewModel(private val repo: GroupRepository) : ViewModel() {
    var groups by mutableStateOf<List<Group>?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    fun dismissError() { error = null }

    fun refresh() = viewModelScope.launch {
        refreshing = true
        runCatching { repo.myGroups() }
            .onSuccess { groups = it.sortedBy { g -> g.name.lowercase() }; error = null }
            .onFailure { error = it.friendly(); if (groups == null) groups = emptyList() }
        refreshing = false
    }

    fun create(name: String, icon: String, onDone: (Group) -> Unit) = act(onDone) { repo.create(name, icon) }

    fun join(code: String, onDone: (Group) -> Unit) = act(onDone) { repo.join(code) }

    private fun act(onDone: (Group) -> Unit, block: suspend () -> Group) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { refresh(); onDone(it) }
                .onFailure { error = it.friendly() }
            busy = false
        }
    }
}

@Composable
fun GroupsScreen(me: Profile, unreadDms: Int, onOpen: (Group) -> Unit, onSettings: () -> Unit, onMessages: () -> Unit) {
    val vm = gngViewModel { GroupsViewModel(it.groups) }
    LaunchedEffect(Unit) { vm.refresh() }
    var sheet by remember { mutableStateOf<GangSheetMode?>(null) }

    GroupsContent(
        me = me, unreadDms = unreadDms, groups = vm.groups, refreshing = vm.refreshing, error = vm.error, onDismissError = vm::dismissError,
        onRefresh = { vm.refresh() }, onOpen = onOpen, onSettings = onSettings, onMessages = onMessages,
        onStart = { sheet = GangSheetMode.Start }, onJoin = { sheet = GangSheetMode.Join },
    )

    sheet?.let { mode ->
        NewGangSheet(
            initial = mode, busy = vm.busy, error = vm.error,
            onDismiss = { sheet = null; vm.dismissError() },
            onCreate = { name, icon -> vm.create(name, icon) { sheet = null; onOpen(it) } },
            onJoin = { code -> vm.join(code) { sheet = null; onOpen(it) } },
        )
    }
}

enum class GangSheetMode { Start, Join }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsContent(
    me: Profile,
    unreadDms: Int,
    groups: List<Group>?,
    refreshing: Boolean,
    error: String?,
    onDismissError: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (Group) -> Unit,
    onSettings: () -> Unit,
    onMessages: () -> Unit,
    onStart: () -> Unit,
    onJoin: () -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    // Sheet errors show inside the sheet; list errors show as a snackbar.
    LaunchedEffect(error, groups) {
        if (error != null && groups != null && groups.isEmpty()) {
            snackbar.showSnackbar(error)
            onDismissError()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = { Text("Hey, ${me.displayName.substringBefore(' ')}") },
                actions = {
                    IconButton(onClick = onMessages) {
                        BadgedBox(badge = { if (unreadDms > 0) Badge { Text(if (unreadDms > 99) "99+" else unreadDms.toString()) } }) {
                            GngIcon(GngIcons.ChatBubble, "Messages")
                        }
                    }
                    IconButton(onClick = onSettings) { Avatar(me.displayName, me.avatarUrl, size = 36.dp, colorKey = me.id) }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            if (!groups.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onStart,
                    icon = { GngIcon(GngIcons.Add, null) },
                    text = { Text("New gang") },
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.padding(padding)) {
            when {
                groups == null -> Loading()
                groups.isEmpty() -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    EmptyState(
                        GngIcons.Groups, "No gangs yet",
                        "Start one and share the code in your WhatsApp group, or join a friend's.",
                        action = {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = onStart) { Text("Start a gang") }
                                OutlinedButton(onClick = onJoin) { Text("Join with code") }
                            }
                        },
                    )
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(groups, key = { it.id }) { g -> GangCard(g, onClick = { onOpen(g) }) }
                    item {
                        OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            GngIcon(GngIcons.Key, null, size = 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Join with a code")
                        }
                        Spacer(Modifier.height(96.dp)) // room for the FAB
                    }
                }
            }
        }
    }
}

@Composable
private fun GangCard(g: Group, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GroupAvatar(g.icon, g.photoUrl, size = 56.dp)
            Column(Modifier.padding(horizontal = 16.dp).weight(1f)) {
                Text(g.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoChip("${g.memberCount} ${if (g.memberCount == 1) "member" else "members"}", icon = GngIcons.PersonFill)
                    InfoChip(g.inviteCode, icon = GngIcons.Key)
                }
            }
            GngIcon(GngIcons.ChevronRight, null, tint = scheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NewGangSheet(
    initial: GangSheetMode,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (name: String, icon: String) -> Unit,
    onJoin: (code: String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var mode by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(GroupIcons.default.key) }
    var code by remember { mutableStateOf("") }
    val normalized = InviteCodes.normalize(code)

    GngSheet(title = if (mode == GangSheetMode.Start) "Start a gang" else "Join a gang", onDismiss = onDismiss) {
        SegmentedChoice(
            choices = listOf(
                Choice(GangSheetMode.Start, "Start new", GngIcons.Add),
                Choice(GangSheetMode.Join, "Join with code", GngIcons.Key),
            ),
            selected = mode, onSelect = { mode = it }, checkIcon = GngIcons.Check,
        )
        Spacer(Modifier.height(20.dp))
        if (mode == GangSheetMode.Start) {
            IconPicker(GroupIcons, icon) { icon = it }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                name, { name = it.take(40) }, label = { Text("Name") }, placeholder = { Text("Hostel H4 Maggi Club") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                code, { code = it.take(12) }, label = { Text("6-character code") },
                leadingIcon = { GngIcon(GngIcons.Key, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (error != null) {
            Text(error, color = scheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        }
        Spacer(Modifier.height(24.dp))
        Button(
            enabled = !busy && if (mode == GangSheetMode.Start) name.isNotBlank() else normalized != null,
            onClick = { if (mode == GangSheetMode.Start) onCreate(name.trim(), icon) else normalized?.let(onJoin) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (mode == GangSheetMode.Start) "Create gang" else "Join gang") }
    }
}
