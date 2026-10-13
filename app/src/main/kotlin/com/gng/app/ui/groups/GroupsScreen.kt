package com.gng.app.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.data.Group
import com.gng.app.data.GroupRepository
import com.gng.app.data.Profile
import com.gng.app.ui.components.BouncyButton
import com.gng.app.ui.components.EmojiStrip
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.ErrorBanner
import com.gng.app.ui.components.FunCard
import com.gng.app.ui.components.GROUP_EMOJIS
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.core.InviteCodes
import kotlinx.coroutines.launch

class GroupsViewModel(private val repo: GroupRepository, private val userId: String) : ViewModel() {
    var groups by mutableStateOf<List<Group>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    fun refresh() = viewModelScope.launch {
        runCatching { repo.myGroups(userId) }
            .onSuccess { groups = it.sortedBy { g -> g.name.lowercase() }; error = null }
            .onFailure { error = it.friendly(); if (groups == null) groups = emptyList() }
    }

    fun create(name: String, emoji: String, onDone: (Group) -> Unit) = act(onDone) { repo.create(name, emoji) }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(me: Profile, onOpen: (Group) -> Unit, onSettings: () -> Unit) {
    val vm = gngViewModel { GroupsViewModel(it.groups, me.id) }
    LaunchedEffect(Unit) { vm.refresh() }
    var dialog by remember { mutableStateOf<String?>(null) } // "create" | "join"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("hey ${me.displayName} ${me.emoji}") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    BouncyButton("➕ New gang", { dialog = "create" }, Modifier.weight(1f))
                    BouncyButton(
                        "🔑 Join with code", { dialog = "join" }, Modifier.weight(1f),
                        container = MaterialTheme.colorScheme.secondary,
                        content = MaterialTheme.colorScheme.onSecondary,
                    )
                }
                ErrorBanner(vm.error)
            }
            val groups = vm.groups
            if (groups != null && groups.isEmpty()) {
                item { EmptyState("🦗", "No gangs yet", "Start one and drop the code in your WhatsApp group.") }
            }
            items(groups.orEmpty(), key = { it.id }) { g ->
                FunCard(Modifier.clickable { onOpen(g) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.emoji, fontSize = 36.sp)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(g.name, style = MaterialTheme.typography.titleLarge)
                            Text("code ${g.inviteCode}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        "create" -> CreateDialog(busy = vm.busy, error = vm.error, onDismiss = { dialog = null }) { name, emoji ->
            vm.create(name, emoji) { dialog = null; onOpen(it) }
        }
        "join" -> JoinDialog(busy = vm.busy, error = vm.error, onDismiss = { dialog = null }) { code ->
            vm.join(code) { dialog = null; onOpen(it) }
        }
    }
}

@Composable
private fun CreateDialog(busy: Boolean, error: String?, onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf(GROUP_EMOJIS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start a gang") },
        text = {
            Column {
                EmojiStrip(GROUP_EMOJIS, emoji) { emoji = it }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true,
                    placeholder = { Text("Hostel H4 Maggi Club") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorBanner(error, Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !busy, onClick = { onCreate(name.trim(), emoji) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Nah") } },
    )
}

@Composable
private fun JoinDialog(busy: Boolean, error: String?, onDismiss: () -> Unit, onJoin: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    val normalized = InviteCodes.normalize(code)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Got a code?") },
        text = {
            Column {
                OutlinedTextField(
                    code, { code = it.take(12) }, label = { Text("6-character code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                ErrorBanner(error, Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = normalized != null && !busy, onClick = { normalized?.let(onJoin) }) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
