package com.gng.app.ui.root

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gng.app.AppContainer
import com.gng.app.data.Profile
import com.gng.app.data.Supabase
import com.gng.app.ui.auth.AuthScreen
import com.gng.app.ui.components.BouncyButton
import com.gng.app.ui.components.EmptyState
import com.gng.app.ui.components.Loading
import com.gng.app.ui.gngViewModel
import com.gng.app.ui.profile.ProfileScreen
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface RootState {
    data object Loading : RootState
    data object SignedOut : RootState
    data object Offline : RootState
    data class NeedsProfile(val userId: String) : RootState
    data class Ready(val me: Profile) : RootState
}

class RootViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<RootState>(RootState.Loading)
    val state: StateFlow<RootState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            c.auth.sessionStatus.collectLatest { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val uid = status.session.user?.id ?: return@collectLatest
                        val current = _state.value
                        // Token refreshes re-emit Authenticated; don't reload if nothing changed.
                        if (current is RootState.Ready && current.me.id == uid) return@collectLatest
                        loadProfile(uid)
                    }
                    is SessionStatus.NotAuthenticated -> {
                        session?.cancel()
                        _state.value = RootState.SignedOut
                    }
                    is SessionStatus.RefreshFailure ->
                        if (_state.value !is RootState.Ready) _state.value = RootState.Offline
                    else -> Unit // still loading the stored session
                }
            }
        }
    }

    private var session: Job? = null

    private suspend fun loadProfile(uid: String) {
        _state.value = RootState.Loading
        _state.value = runCatching { c.profiles.get(uid) }.fold(
            onSuccess = { if (it == null) RootState.NeedsProfile(uid) else RootState.Ready(it) },
            onFailure = { RootState.Offline },
        )
        if (_state.value is RootState.Ready) startSession(uid)
    }

    /** Things that run while you're signed in: encryption key, push token, incoming calls. */
    private fun startSession(uid: String) {
        session?.cancel()
        session = viewModelScope.launch {
            launch { runCatching { c.e2ee.publishIdentity(uid) } }
            launch { c.settings.push.collectLatest { c.push.sync(it) } }
            launch {
                while (true) {
                    runCatching {
                        c.chat.callChanges().collect { call ->
                            if (call.endedAt != null) c.calls.remoteEnded(call.id) else c.inbox.onCall(call)
                        }
                    }
                    delay(5_000)
                }
            }
        }
    }

    fun retry() {
        val uid = c.auth.userId ?: return
        viewModelScope.launch { loadProfile(uid) }
    }

    fun profileSaved(p: Profile) {
        val first = _state.value !is RootState.Ready
        _state.value = RootState.Ready(p)
        if (first) startSession(p.id)
    }
}

@Composable
fun RootScreen(openGroup: String? = null, onOpened: () -> Unit = {}) {
    if (!Supabase.isConfigured) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                "🔧", "Supabase isn't set up yet",
                "Add SUPABASE_URL and SUPABASE_ANON_KEY to local.properties and rebuild. See docs/SETUP.md.",
            )
        }
        return
    }
    val vm = gngViewModel { RootViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        RootState.Loading -> Loading()
        RootState.SignedOut -> AuthScreen()
        RootState.Offline -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState("📡", "Can't reach the gang", "Check your internet and try again.")
                BouncyButton("Retry", vm::retry)
            }
        }
        is RootState.NeedsProfile -> ProfileScreen(userId = s.userId, existing = null, onSaved = vm::profileSaved)
        is RootState.Ready -> MainNav(me = s.me, onProfileSaved = vm::profileSaved, openGroup = openGroup, onOpened = onOpened)
    }
}
