package com.gng.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.gng.app.container
import com.gng.app.data.Profile
import com.gng.app.data.SharingMode
import com.gng.app.data.ThemeMode
import com.gng.app.ui.components.ChipRow
import com.gng.app.ui.components.EmojiAvatar
import com.gng.app.ui.components.FunCard
import com.gng.app.ui.components.SectionHeader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(me: Profile, onEditProfile: () -> Unit, onTimetable: () -> Unit, onBack: () -> Unit) {
    val c = LocalContext.current.container
    val theme by c.settings.theme.collectAsStateWithLifecycle(ThemeMode.System)
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            FunCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmojiAvatar(me.emoji, MaterialTheme.colorScheme.primaryContainer, 56.dp)
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Text(me.displayName, style = MaterialTheme.typography.titleLarge)
                        Text(listOfNotNull(me.branch, me.year?.let { "Year $it" }).joinToString(" · "))
                    }
                    OutlinedButton(onClick = onEditProfile) { Text("Edit") }
                }
            }
            SectionHeader("Theme")
            ChipRow {
                FilterChip(theme == ThemeMode.System, { scope.launch { c.settings.setTheme(ThemeMode.System) } }, label = { Text("📱 System") })
                FilterChip(theme == ThemeMode.Light, { scope.launch { c.settings.setTheme(ThemeMode.Light) } }, label = { Text("☀️ Playful") })
                FilterChip(theme == ThemeMode.Dark, { scope.launch { c.settings.setTheme(ThemeMode.Dark) } }, label = { Text("🌚 Neon") })
            }
            SectionHeader("Notifications & privacy")
            val push by c.settings.push.collectAsStateWithLifecycle(true)
            val previews by c.settings.previews.collectAsStateWithLifecycle(true)
            val receipts by c.settings.readReceipts.collectAsStateWithLifecycle(true)
            SettingSwitch(
                "🔔 Push notifications",
                if (c.push.available) "Messages and calls, even when gng is closed" else "Not set up in this build (needs Firebase, see docs/SETUP.md)",
                push && c.push.available,
                enabled = c.push.available,
            ) { scope.launch { c.settings.setPush(it) } }
            SettingSwitch("👀 Message previews", "Show who said what in notifications", previews) { scope.launch { c.settings.setPreviews(it) } }
            SettingSwitch("✔️ Send read receipts", "When off, people see Delivered but not Read", receipts) { scope.launch { c.settings.setReadReceipts(it) } }
            Text(
                "🔒 Messages, photos, files, stickers and reactions are end-to-end encrypted. Calls are peer-to-peer encrypted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            SectionHeader("Stuff")
            OutlinedButton(onClick = onTimetable, modifier = Modifier.fillMaxWidth()) { Text("🗓️ My classes") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        // Stop sharing before logging out so nobody sees a stale spot,
                        // and stop pushes to this phone.
                        runCatching { c.checkIn.setMode(SharingMode.Off) }
                        runCatching { c.push.unregister() }
                        c.cache.wipe()
                        c.auth.signOut()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("👋 Log out") }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
