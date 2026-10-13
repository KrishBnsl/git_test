package com.gng.app.ui.group

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.gng.app.AppContainer
import com.gng.app.data.Presence
import com.gng.app.data.SharingMode
import com.gng.app.data.parseInstant
import com.gng.app.ui.friendly
import com.gng.core.LatLng
import com.gng.core.MapSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where everyone in a gang is. Shared by the Map and Squad tabs so they never poll twice. */
class PresenceViewModel(private val c: AppContainer, private val groupId: String) : ViewModel() {
    var presence by mutableStateOf<Map<String, Presence>>(emptyMap())
        private set
    var loaded by mutableStateOf(false)
        private set
    var myLatLng by mutableStateOf<LatLng?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun dismissError() { error = null }

    suspend fun refresh() {
        runCatching { presence = c.locations.presence(groupId).associateBy { it.userId } }
            .onSuccess { loaded = true }
            .onFailure { error = it.friendly() }
        if (c.settings.currentSharing().effective() == SharingMode.Live) {
            myLatLng = runCatching { c.checkIn.currentLatLng() }.getOrNull()
        }
    }

    fun refreshNow() = viewModelScope.launch { refresh() }
}

/** Refreshes presence now and every [intervalMs], but only while the screen is visible. */
@Composable
fun PresencePolling(vm: PresenceViewModel, intervalMs: Long = 30_000) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refresh()
                delay(intervalMs)
            }
        }
    }
}

fun Presence.toMapSource() = MapSource(
    userId = userId,
    live = if (lat != null && lng != null) LatLng(lat, lng) else null,
    spot = if (zoneLat != null && zoneLng != null) LatLng(zoneLat, zoneLng) else null,
    spotName = zoneName,
    updatedAt = parseInstant(updatedAt),
)
