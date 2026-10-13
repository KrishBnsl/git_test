package com.gng.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

enum class ThemeMode { System, Light, Dark }

/** How much of your whereabouts the gang sees. */
enum class SharingMode(val db: String) { Off("off"), Zone("zone"), Live("live") }

data class SharingState(val mode: SharingMode, val liveUntil: Instant?) {
    /** Live sharing quietly falls back to zone-only once its timer runs out. */
    fun effective(now: Instant = Instant.now()): SharingMode =
        if (mode == SharingMode.Live && (liveUntil == null || !liveUntil.isAfter(now))) SharingMode.Zone else mode
}

private val Context.dataStore by preferencesDataStore("gng_settings")

class SettingsStore(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")
    private val lastUpdateCheckKey = longPreferencesKey("last_update_check")
    private val skippedVersionKey = stringPreferencesKey("skipped_update_version")
    private val sharingKey = stringPreferencesKey("sharing")
    private val liveUntilKey = longPreferencesKey("live_until")

    val theme: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System
    }

    /** Use wallpaper colours on Android 12+. On by default; the brand palette is the alternative. */
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[dynamicColorKey] ?: true }

    val sharing: Flow<SharingState> = context.dataStore.data.map { prefs ->
        SharingState(
            mode = prefs[sharingKey]?.let { runCatching { SharingMode.valueOf(it) }.getOrNull() } ?: SharingMode.Off,
            liveUntil = prefs[liveUntilKey]?.let(Instant::ofEpochMilli),
        )
    }

    suspend fun currentSharing(): SharingState = sharing.first()

    suspend fun setTheme(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun lastUpdateCheck(): Long = context.dataStore.data.first()[lastUpdateCheckKey] ?: 0L

    suspend fun setLastUpdateCheck(epochMillis: Long) {
        context.dataStore.edit { it[lastUpdateCheckKey] = epochMillis }
    }

    /** A version the person chose "Skip" on, so we don't nag about it again. */
    suspend fun skippedUpdateVersion(): String? = context.dataStore.data.first()[skippedVersionKey]

    suspend fun skipUpdateVersion(version: String) {
        context.dataStore.edit { it[skippedVersionKey] = version }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[dynamicColorKey] = enabled }
    }

    suspend fun setSharing(mode: SharingMode, liveUntil: Instant? = null) {
        context.dataStore.edit {
            it[sharingKey] = mode.name
            if (liveUntil == null) it.remove(liveUntilKey) else it[liveUntilKey] = liveUntil.toEpochMilli()
        }
    }
}
