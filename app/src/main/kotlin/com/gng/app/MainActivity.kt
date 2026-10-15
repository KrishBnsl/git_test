package com.gng.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.gng.app.data.ThemeMode
import com.gng.app.ui.root.RootScreen
import com.gng.app.ui.theme.GngTheme
import com.gng.core.InviteCodes
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var openGroup by mutableStateOf<String?>(null)
    /** From an invite link or QR code (gng://join/CODE or the https link). Waits through sign-in if needed. */
    private var inviteCode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container.updates.checkIfDue()
        handle(intent)
        setContent {
            val theme by container.settings.theme.collectAsStateWithLifecycle(ThemeMode.System)
            val dynamic by container.settings.dynamicColor.collectAsStateWithLifecycle(true)
            GngTheme(theme, dynamic) {
                RootScreen(
                    openGroup = openGroup, onOpened = { openGroup = null },
                    inviteCode = inviteCode, onInviteHandled = { inviteCode = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        intent.getStringExtra(EXTRA_OPEN_GROUP)?.let { openGroup = it }
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null && (data.scheme == "gng" || data.host == INVITE_HOST)) {
            InviteCodes.fromScan(data.toString())?.let { inviteCode = it }
            return
        }
        // Images or sticker packs shared to gng go into your sticker library.
        val shared: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (shared.isEmpty()) return
        lifecycleScope.launch {
            val msg = runCatching { container.stickers.importFiles(shared) }
                .fold({ it.describe() + " to your gng stickers" }, { it.message ?: "Couldn't import that" })
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_OPEN_GROUP = "open_group"
        private const val INVITE_HOST = "krishbnsl.github.io"
    }
}
