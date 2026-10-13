package com.gng.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gng.app.BuildConfig
import com.gng.app.container
import com.gng.app.data.Profile
import com.gng.app.data.SharingMode
import com.gng.app.data.ThemeMode
import com.gng.app.ui.components.Avatar
import com.gng.app.ui.components.Choice
import com.gng.app.ui.components.GngIcon
import com.gng.app.ui.components.SectionHeader
import com.gng.app.ui.components.SegmentedChoice
import com.gng.app.ui.icons.GngIcons
import com.gng.app.ui.theme.DynamicColorSupported
import com.gng.app.ui.update.UpdateActions
import com.gng.app.ui.update.actions
import com.gng.app.ui.update.UpdateCard
import com.gng.app.update.UpdateState
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(me: Profile, onEditProfile: () -> Unit, onTimetable: () -> Unit, onBack: () -> Unit) {
    val c = LocalContext.current.container
    val theme by c.settings.theme.collectAsStateWithLifecycle(ThemeMode.System)
    val dynamic by c.settings.dynamicColor.collectAsStateWithLifecycle(true)
    val scope = rememberCoroutineScope()

    val updates = c.updates
    val updateState by updates.state.collectAsStateWithLifecycle()
    SettingsContent(
        updateState = updateState, updateActions = updates.actions(),
        currentVersion = updates.currentVersion, updatesEnabled = updates.enabled,
        me = me, theme = theme, dynamicColor = dynamic,
        onTheme = { scope.launch { c.settings.setTheme(it) } },
        onDynamicColor = { scope.launch { c.settings.setDynamicColor(it) } },
        onEditProfile = onEditProfile, onTimetable = onTimetable, onBack = onBack,
        onLogout = {
            scope.launch {
                // Stop sharing before logging out so nobody sees a stale spot.
                runCatching { c.checkIn.setMode(SharingMode.Off) }
                c.auth.signOut()
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    me: Profile,
    theme: ThemeMode,
    dynamicColor: Boolean,
    onTheme: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onEditProfile: () -> Unit,
    onTimetable: () -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    updateState: UpdateState = UpdateState.Idle,
    updateActions: UpdateActions = UpdateActions(),
    currentVersion: String = BuildConfig.VERSION_NAME,
    updatesEnabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { GngIcon(GngIcons.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(me.displayName, me.avatarUrl, size = 64.dp, colorKey = me.id)
                    Column(Modifier.padding(horizontal = 16.dp).weight(1f)) {
                        Text(me.displayName, style = MaterialTheme.typography.titleLarge)
                        val line = listOfNotNull(me.branch, me.year?.let { "Year $it" }).joinToString(" · ")
                        if (line.isNotBlank()) {
                            Text(line, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                    FilledTonalButton(onClick = onEditProfile) { Text("Edit") }
                }
            }

            SectionHeader("Appearance")
            SegmentedChoice(
                choices = listOf(
                    Choice(ThemeMode.System, "Auto", GngIcons.BrightnessAuto),
                    Choice(ThemeMode.Light, "Light", GngIcons.LightMode),
                    Choice(ThemeMode.Dark, "Dark", GngIcons.DarkMode),
                ),
                selected = theme, onSelect = onTheme, checkIcon = GngIcons.Check,
            )
            Spacer(Modifier.height(8.dp))
            ListItem(
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                leadingContent = { GngIcon(GngIcons.Palette, null) },
                headlineContent = { Text("Dynamic colour") },
                supportingContent = {
                    Text(
                        if (DynamicColorSupported) "Match your wallpaper, like the rest of Android"
                        else "Needs Android 12 or newer",
                    )
                },
                trailingContent = {
                    Switch(
                        checked = dynamicColor && DynamicColorSupported,
                        onCheckedChange = onDynamicColor,
                        enabled = DynamicColorSupported,
                    )
                },
            )

            SectionHeader("You")
            ListItem(
                modifier = Modifier.clickable(onClick = onTimetable),
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                leadingContent = { GngIcon(GngIcons.CalendarMonth, null) },
                headlineContent = { Text("My classes") },
                supportingContent = { Text("Your gang uses this to find lunch slots that work for everyone") },
                trailingContent = { GngIcon(GngIcons.ChevronRight, null, tint = scheme.onSurfaceVariant) },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onLogout),
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                leadingContent = { GngIcon(GngIcons.Logout, null, tint = scheme.error) },
                headlineContent = { Text("Log out", color = scheme.error) },
            )
            SectionHeader("About")
            UpdateCard(updateState, updateActions)
            val busy = updateState is UpdateState.Checking || updateState is UpdateState.Downloading
            ListItem(
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                leadingContent = { GngIcon(GngIcons.Info, null) },
                headlineContent = { Text("gng $currentVersion") },
                supportingContent = {
                    Text(
                        when {
                            !updatesEnabled -> "Updates are switched off in test builds"
                            updateState is UpdateState.Checking -> "Checking for updates…"
                            updateState is UpdateState.UpToDate -> "You're on the latest version"
                            updateState is UpdateState.Available -> "Version ${updateState.info.version} is available"
                            else -> "Updates come from GitHub Releases"
                        },
                    )
                },
                trailingContent = {
                    if (updatesEnabled) TextButton(onClick = updateActions.onCheck, enabled = !busy) { Text("Check") }
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
