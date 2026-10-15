package com.gng.app.ui.settings

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.gng.app.update.AutoUpdate
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.gng.app.ui.theme.Accent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import com.gng.app.ui.update.UpdateActions
import com.gng.app.ui.update.actions
import com.gng.app.ui.update.UpdateCard
import com.gng.app.update.UpdateState
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(me: Profile, onEditProfile: () -> Unit, onTimetable: () -> Unit, onBack: () -> Unit) {
    val c = LocalContext.current.container
    val theme by c.settings.theme.collectAsStateWithLifecycle(ThemeMode.System)
    val accentName by c.settings.accent.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()

    val updates = c.updates
    val updateState by updates.state.collectAsStateWithLifecycle()
    val push by c.settings.push.collectAsStateWithLifecycle(true)
    val previews by c.settings.previews.collectAsStateWithLifecycle(true)
    val receipts by c.settings.readReceipts.collectAsStateWithLifecycle(true)
    val autoUpdate by c.settings.autoUpdate.collectAsStateWithLifecycle(true)
    val context = LocalContext.current
    // Re-checked whenever you come back from the system settings page.
    var restrictions by remember { mutableStateOf(DeliveryRestrictions.of(context)) }
    LifecycleResumeEffect(Unit) {
        restrictions = DeliveryRestrictions.of(context)
        onPauseOrDispose {}
    }
    SettingsContent(
        privacy = PrivacySettings(
            pushAvailable = c.push.available, push = push, previews = previews, readReceipts = receipts,
            batteryRestricted = restrictions.battery, fullScreenBlocked = restrictions.fullScreen,
            onFixBattery = { DeliveryRestrictions.fixBattery(context) },
            onFixFullScreen = { DeliveryRestrictions.fixFullScreen(context) },
            onPush = { scope.launch { c.settings.setPush(it) } },
            onPreviews = { scope.launch { c.settings.setPreviews(it) } },
            onReadReceipts = { scope.launch { c.settings.setReadReceipts(it) } },
        ),
        updateState = updateState, updateActions = updates.actions(),
        currentVersion = updates.currentVersion, updatesEnabled = updates.enabled,
        autoUpdate = autoUpdate,
        onAutoUpdate = {
            scope.launch { c.settings.setAutoUpdate(it) }
            AutoUpdate.schedule(context, it)
        },
        me = me, theme = theme, accent = Accent.from(accentName),
        onTheme = { scope.launch { c.settings.setTheme(it) } },
        onAccent = { scope.launch { c.settings.setAccent(it.name) } },
        onEditProfile = onEditProfile, onTimetable = onTimetable, onBack = onBack,
        onLogout = {
            scope.launch {
                // Stop sharing before logging out so nobody sees a stale spot,
                // stop pushes to this phone, and forget the decrypted message cache.
                runCatching { c.checkIn.setMode(SharingMode.Off) }
                runCatching { c.push.unregister() }
                c.cache.wipe()
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
    accent: Accent = Accent.Default,
    onTheme: (ThemeMode) -> Unit,
    onAccent: (Accent) -> Unit = {},
    onEditProfile: () -> Unit,
    onTimetable: () -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    updateState: UpdateState = UpdateState.Idle,
    updateActions: UpdateActions = UpdateActions(),
    currentVersion: String = BuildConfig.VERSION_NAME,
    updatesEnabled: Boolean = true,
    autoUpdate: Boolean = true,
    onAutoUpdate: (Boolean) -> Unit = {},
    privacy: PrivacySettings = PrivacySettings(),
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
                        me.handle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.primary) }
                        val line = listOfNotNull(me.college, me.branch, me.year?.let { "Year $it" }).joinToString(" · ")
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
            Text("Theme colour", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
            AccentPicker(accent, onAccent)

            SectionHeader("Notifications & privacy")
            val clear = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
            ListItem(
                colors = clear,
                leadingContent = { GngIcon(GngIcons.Notifications, null) },
                headlineContent = { Text("Push notifications") },
                supportingContent = {
                    Text(
                        if (privacy.pushAvailable) "Messages and calls, even when gng is closed"
                        else "Not set up in this build (needs Firebase, see docs/SETUP.md)",
                    )
                },
                trailingContent = {
                    Switch(checked = privacy.push && privacy.pushAvailable, onCheckedChange = privacy.onPush, enabled = privacy.pushAvailable)
                },
            )
            if (privacy.push && privacy.pushAvailable && privacy.batteryRestricted) {
                ListItem(
                    colors = clear,
                    modifier = Modifier.clickable(onClick = privacy.onFixBattery),
                    leadingContent = { GngIcon(GngIcons.Bolt, null, tint = scheme.error) },
                    headlineContent = { Text("Get notifications on time") },
                    supportingContent = {
                        Text("Your phone is saving battery on gng, which can hold back messages and calls. Tap and allow gng to run in the background.")
                    },
                    trailingContent = { GngIcon(GngIcons.ChevronRight, null) },
                )
            }
            if (privacy.push && privacy.pushAvailable && privacy.fullScreenBlocked) {
                ListItem(
                    colors = clear,
                    modifier = Modifier.clickable(onClick = privacy.onFixFullScreen),
                    leadingContent = { GngIcon(GngIcons.Call, null, tint = scheme.error) },
                    headlineContent = { Text("Show incoming calls full screen") },
                    supportingContent = { Text("Right now calls only show as a small banner, and won't light up a locked phone. Tap to allow.") },
                    trailingContent = { GngIcon(GngIcons.ChevronRight, null) },
                )
            }
            ListItem(
                colors = clear,
                leadingContent = { GngIcon(GngIcons.Visibility, null) },
                headlineContent = { Text("Message previews") },
                supportingContent = { Text("Show who said what in notifications") },
                trailingContent = { Switch(checked = privacy.previews, onCheckedChange = privacy.onPreviews) },
            )
            ListItem(
                colors = clear,
                leadingContent = { GngIcon(GngIcons.DoneAll, null) },
                headlineContent = { Text("Send read receipts") },
                supportingContent = { Text("When off, people see Delivered but not Read") },
                trailingContent = { Switch(checked = privacy.readReceipts, onCheckedChange = privacy.onReadReceipts) },
            )
            ListItem(
                colors = clear,
                leadingContent = { GngIcon(GngIcons.Encrypted, null, tint = scheme.primary) },
                headlineContent = { Text("End-to-end encrypted") },
                supportingContent = {
                    Text("Messages, photos, files, stickers and reactions can only be read by the people in the chat. Calls are encrypted phone to phone.")
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
            if (updatesEnabled) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    leadingContent = { GngIcon(GngIcons.SystemUpdate, null) },
                    headlineContent = { Text("Update automatically") },
                    supportingContent = { Text("New versions install in the background when you're not using gng") },
                    trailingContent = { Switch(checked = autoUpdate, onCheckedChange = onAutoUpdate) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The notification and privacy switches; defaults are what a fresh install has. */
data class PrivacySettings(
    val pushAvailable: Boolean = true,
    val push: Boolean = true,
    val previews: Boolean = true,
    val readReceipts: Boolean = true,
    /** The phone's battery saver may hold back pushes. */
    val batteryRestricted: Boolean = false,
    /** Android 14+: incoming calls can't take over the screen. */
    val fullScreenBlocked: Boolean = false,
    val onFixBattery: () -> Unit = {},
    val onFixFullScreen: () -> Unit = {},
    val onPush: (Boolean) -> Unit = {},
    val onPreviews: (Boolean) -> Unit = {},
    val onReadReceipts: (Boolean) -> Unit = {},
)

/** Phone settings that make pushes and calls arrive late or quietly. */
data class DeliveryRestrictions(val battery: Boolean, val fullScreen: Boolean) {
    companion object {
        fun of(context: Context): DeliveryRestrictions {
            val power = context.getSystemService(PowerManager::class.java)
            val battery = !power.isIgnoringBatteryOptimizations(context.packageName)
            val fullScreen = Build.VERSION.SDK_INT >= 34 &&
                !context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            return DeliveryRestrictions(battery, fullScreen)
        }

        @SuppressLint("BatteryLife")
        fun fixBattery(context: Context) {
            val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(ask) }
                .recoverCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }

        fun fixFullScreen(context: Context) {
            if (Build.VERSION.SDK_INT < 34) return
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
            }
        }
    }
}

/** A row of colour swatches; "Wallpaper" (Material You) only where Android supports it. */
@Composable
private fun AccentPicker(selected: Accent, onSelect: (Accent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val choices = Accent.entries.filter { it != Accent.Wallpaper || DynamicColorSupported }
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        choices.forEach { a ->
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable { onSelect(a) }.padding(4.dp),
            ) {
                val ring = if (a == selected) scheme.onSurface else androidx.compose.ui.graphics.Color.Transparent
                androidx.compose.foundation.layout.Box(
                    Modifier.size(44.dp).border(2.dp, ring, androidx.compose.foundation.shape.CircleShape).padding(4.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(
                            a.seed?.let { androidx.compose.ui.graphics.SolidColor(it) }
                                ?: androidx.compose.ui.graphics.Brush.sweepGradient(
                                    listOf(androidx.compose.ui.graphics.Color(0xFFE8506E), androidx.compose.ui.graphics.Color(0xFFF0B429),
                                        androidx.compose.ui.graphics.Color(0xFF00A884), androidx.compose.ui.graphics.Color(0xFF2F8CF0),
                                        androidx.compose.ui.graphics.Color(0xFF7C5CFA), androidx.compose.ui.graphics.Color(0xFFE8506E)),
                                ),
                        ),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) { if (a == selected) GngIcon(GngIcons.Check, null, size = 20.dp, tint = androidx.compose.ui.graphics.Color.White) }
                Text(a.label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
