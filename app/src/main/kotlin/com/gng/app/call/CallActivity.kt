package com.gng.app.call

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gng.app.container
import com.gng.app.data.ThemeMode
import com.gng.app.ui.theme.GngTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Full-screen call UI. Shows over the lock screen for incoming calls. */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            GngTheme(ThemeMode.Dark, dynamicColor = false) { CallScreen(container.calls, onClose = { finish() }) }
        }
        // Close once the call is gone.
        lifecycleScope.launch {
            container.calls.state.first { it != null }
            container.calls.state.first { it == null }
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        // Accept straight from the notification only if we already have the mic;
        // otherwise the ringing screen's Accept button asks for it first.
        val canTalk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (intent.action == ACTION_ACCEPT && canTalk) {
            lifecycleScope.launch { container.calls.accept() }
        }
    }

    companion object {
        const val ACTION_SHOW = "com.gng.app.call.SHOW"
        const val ACTION_ACCEPT = "com.gng.app.call.ACCEPT"
        private const val EXTRA_CALL = "call_id"

        fun intent(context: Context, callId: String, action: String) =
            Intent(context, CallActivity::class.java).setAction(action).putExtra(EXTRA_CALL, callId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

/** Keeps the microphone (and camera) alive while the call is in the background. */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val call = container.calls.state.value
        var type = 0
        if (Build.VERSION.SDK_INT >= 30) {
            fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
            if (granted(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (call?.video == true && granted(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID,
                container.notifier.ongoingCall(call?.title ?: "gng", call?.callId ?: ""),
                type,
            )
        }
        if (call == null) stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 4242

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
