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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.gng.app.data.ThemeMode
import com.gng.app.ui.root.RootScreen
import com.gng.app.ui.theme.GngTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var openGroup by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            val theme by container.settings.theme.collectAsStateWithLifecycle(ThemeMode.System)
            GngTheme(theme) { RootScreen(openGroup = openGroup, onOpened = { openGroup = null }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        intent.getStringExtra(EXTRA_OPEN_GROUP)?.let { openGroup = it }
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
                .fold({ if (it > 0) "Added $it to your gng stickers 🎉" else "Those are already in your stickers" }, { it.message ?: "Couldn't import that" })
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_OPEN_GROUP = "open_group"
    }
}
