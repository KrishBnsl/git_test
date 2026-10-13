package com.gng.app.ui.group

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gng.app.AppContainer
import com.gng.app.data.GroupPermission
import com.gng.app.data.GroupRole
import com.gng.app.data.Profile
import com.gng.app.ui.components.Avatar
import com.gng.app.ui.components.Choice
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.GngSheet
import com.gng.app.ui.components.GroupAvatar
import com.gng.app.ui.components.IconPicker
import com.gng.app.ui.components.InfoChip
import com.gng.app.ui.components.Loading
import com.gng.app.ui.components.SectionHeader
import com.gng.app.ui.components.SegmentedChoice
import com.gng.app.ui.friendly
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.icons.GngIcons
import com.gng.app.ui.icons.GroupIcons
import com.gng.app.util.AvatarImage
import kotlinx.coroutines.launch

class GroupSettingsViewModel(private val c: AppContainer, private val groupId: String, private val me: Profile) : ViewModel() {
    var ctx by mutableStateOf<GroupContext?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun dismissError() { error = null }

    fun load() = viewModelScope.launch {
        runCatching {
            val group = c.groups.get(groupId)
            val rows = c.groups.members(groupId)
            GroupContext(group, me, rows.associate { it.userId to it.profiles }, rows.associate { it.userId to it.role })
        }.onSuccess { ctx = it }.onFailure { error = it.friendly() }
    }

    private fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            runCatching { block() }.onFailure { error = it.friendly() }
            busy = false
            load()
        }
    }

    fun changePhoto(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        act {
            val url = c.groups.uploadPhoto(groupId, AvatarImage.squareJpeg(appContext, uri))
            c.groups.updateInfo(groupId, photoUrl = url)
        }
    }

    fun removePhoto() = act { c.groups.updateInfo(groupId, clearPhoto = true) }

    fun saveInfo(name: String, icon: String) = act { c.groups.updateInfo(groupId, name = name, icon = icon) }

    fun setEditPermission(value: String) = act { c.groups.updatePermissions(groupId, edit = value) }

    fun setMessagePermission(value: String) = act { c.groups.updatePermissions(groupId, message = value) }

    fun setRole(userId: String, role: String) = act { c.groups.setRole(groupId, userId, role) }

    fun leave(onLeft: () -> Unit) {
        viewModelScope.launch {
            runCatching { c.groups.leave(groupId, me.id) }
                .onSuccess { onLeft() }
                .onFailure { error = it.friendly() }
        }
    }
}

@Composable
fun GroupSettingsScreen(groupId: String, me: Profile, onBack: () -> Unit, onLeft: () -> Unit) {
    val vm = gngViewModel(key = "group-settings-$groupId") { GroupSettingsViewModel(it, groupId, me) }
    val context = LocalContext.current
    GroupSettingsContent(
        ctx = vm.ctx, busy = vm.busy, error = vm.error, onDismissError = vm::dismissError, onBack = onBack,
        onPhoto = { vm.changePhoto(context, it) }, onRemovePhoto = vm::removePhoto,
        onSaveInfo = vm::saveInfo,
        onEditPermission = vm::setEditPermission, onMessagePermission = vm::setMessagePermission,
        onRole = vm::setRole,
        onLeave = { vm.leave(onLeft) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsContent(
    ctx: GroupContext?,
    busy: Boolean,
    error: String?,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    onPhoto: (Uri) -> Unit,
    onRemovePhoto: () -> Unit,
    onSaveInfo: (name: String, icon: String) -> Unit,
    onEditPermission: (String) -> Unit,
    onMessagePermission: (String) -> Unit,
    onRole: (userId: String, role: String) -> Unit,
    onLeave: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editInfo by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPhoto(uri)
    }
    LaunchedEffect(error) {
        if (error != null) {
            snackbar.showSnackbar(error)
            onDismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Group settings") },
                navigationIcon = { IconButton(onClick = onBack) { GngIcon(GngIcons.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (ctx == null) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (error != null) EmptyState(GngIcons.WifiOff, "Couldn't load the gang", error, Modifier.align(Alignment.Center))
                else Loading()
            }
            return@Scaffold
        }
        val group = ctx.group
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                // Header
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.then(
                            if (ctx.canEditGroup) Modifier.clickable {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            } else Modifier,
                        ),
                    ) {
                        GroupAvatar(group.icon, group.photoUrl, size = 112.dp, contentDescription = group.name)
                        if (ctx.canEditGroup) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(40.dp)
                                    .background(scheme.surface, CircleShape)
                                    .padding(3.dp)
                                    .background(scheme.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { GngIcon(GngIcons.AddAPhoto, "Change gang photo", tint = scheme.onPrimary, size = 20.dp) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(group.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text(
                        "${ctx.members.size} members",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    if (ctx.canEditGroup) {
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { editInfo = true }) {
                                GngIcon(GngIcons.Edit, null, size = 18.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Name and icon")
                            }
                            if (group.photoUrl != null) {
                                OutlinedButton(onClick = onRemovePhoto) { Text("Remove photo") }
                            }
                        }
                    }
                }

                // Invite
                SectionHeader("Invite")
                Card(
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Invite code", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                            Text(group.inviteCode, style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 3.sp))
                        }
                        IconButton(onClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("gng code", group.inviteCode))
                            scope.launch { snackbar.showSnackbar("Code copied") }
                        }) { GngIcon(GngIcons.ContentCopy, "Copy code") }
                        IconButton(onClick = {
                            val text = "Join ${group.name} on gng. Code: ${group.inviteCode}"
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                                    "Invite the gang",
                                ),
                            )
                        }) { GngIcon(GngIcons.Share, "Share invite") }
                    }
                }

                // Permissions
                SectionHeader("Permissions")
                Card(
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        PermissionRow(
                            title = "Edit gang info",
                            description = "Who can change the photo, name and icon",
                            value = group.editPermission,
                            choices = listOf(
                                Choice(GroupPermission.MEMBERS, "Members"),
                                Choice(GroupPermission.ADMINS, "Admins only"),
                            ),
                            enabled = ctx.isAdmin && !busy,
                            onSelect = onEditPermission,
                        )
                        PermissionRow(
                            title = "Send messages",
                            description = "Who can post in the chat",
                            value = group.messagePermission,
                            choices = listOf(
                                Choice(GroupPermission.EVERYONE, "Everyone"),
                                Choice(GroupPermission.ADMINS, "Admins only"),
                            ),
                            enabled = ctx.isAdmin && !busy,
                            onSelect = onMessagePermission,
                        )
                        if (!ctx.isAdmin) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GngIcon(GngIcons.Lock, null, tint = scheme.onSurfaceVariant, size = 18.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Only admins can change these.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // Members
                SectionHeader("Members")
                val members = ctx.members.values.sortedWith(
                    compareByDescending<Profile> { ctx.isAdmin(it.id) }.thenBy { it.displayName.lowercase() },
                )
                members.forEach { p ->
                    MemberRow(
                        profile = p, isMe = p.id == ctx.me.id, isAdmin = ctx.isAdmin(p.id), canManage = ctx.isAdmin && !busy,
                        onRole = { onRole(p.id, it) },
                    )
                }

                Spacer(Modifier.height(16.dp))
                ListItem(
                    modifier = Modifier.clickable { confirmLeave = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { GngIcon(GngIcons.Logout, null, tint = scheme.error) },
                    headlineContent = { Text("Leave gang", color = scheme.error) },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (editInfo && ctx != null) EditGroupSheet(ctx.group.name, ctx.group.icon, onDismiss = { editInfo = false }) { name, icon ->
        onSaveInfo(name, icon)
        editInfo = false
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

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    value: String,
    choices: List<Choice<String>>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        SegmentedChoice(choices, value, onSelect, checkIcon = GngIcons.Check, enabled = enabled)
    }
}

@Composable
private fun MemberRow(profile: Profile, isMe: Boolean, isAdmin: Boolean, canManage: Boolean, onRole: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(profile.displayName, profile.avatarUrl, size = 44.dp, colorKey = profile.id)
        Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
            Text(profile.displayName + if (isMe) " (you)" else "", style = MaterialTheme.typography.titleSmall)
            val line = listOfNotNull(profile.branch, profile.year?.let { "Year $it" }).joinToString(" · ")
            if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        if (isAdmin) {
            InfoChip(
                "Admin", icon = GngIcons.AdminPanelSettings,
                container = scheme.primaryContainer, content = scheme.onPrimaryContainer,
            )
        }
        if (canManage) {
            Box {
                IconButton(onClick = { menu = true }) { GngIcon(GngIcons.MoreVert, "Manage ${profile.displayName}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (isAdmin) {
                        DropdownMenuItem(
                            text = { Text("Remove as admin") },
                            leadingIcon = { GngIcon(GngIcons.Person, null) },
                            onClick = { menu = false; onRole(GroupRole.MEMBER) },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Make admin") },
                            leadingIcon = { GngIcon(GngIcons.ShieldPerson, null) },
                            onClick = { menu = false; onRole(GroupRole.ADMIN) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EditGroupSheet(name: String, icon: String, onDismiss: () -> Unit, onSave: (name: String, icon: String) -> Unit) {
    var newName by remember { mutableStateOf(name) }
    var newIcon by remember { mutableStateOf(GroupIcons.resolve(icon).key) }
    GngSheet(title = "Name and icon", onDismiss = onDismiss) {
        OutlinedTextField(
            newName, { newName = it.take(40) }, label = { Text("Gang name") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Shown when the gang has no photo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        IconPicker(GroupIcons, newIcon) { newIcon = it }
        Spacer(Modifier.height(24.dp))
        Button(
            enabled = newName.isNotBlank(),
            onClick = { onSave(newName.trim(), newIcon) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Save") }
    }
}
