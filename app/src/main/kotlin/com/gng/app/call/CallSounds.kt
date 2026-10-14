package com.gng.app.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The sounds and screen behaviour around a call: ringtone and vibration for incoming calls,
 * a ringback tone while you wait for someone to pick up, audio focus (so music pauses), and
 * turning the screen off when the phone is against your ear.
 */
class CallSounds(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null
    private var focus: AudioFocusRequest? = null
    private val proximity: PowerManager.WakeLock? by lazy {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "gng:call-proximity")
        } else {
            null
        }
    }
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    }

    /** Follows the call: call this whenever the call state changes. */
    fun update(call: CallUi?) {
        val phase = call?.phase
        if (phase == CallPhase.Ringing) startRinging() else stopRinging()
        val waiting = phase == CallPhase.Calling && call.participants.isEmpty()
        if (waiting) startRingback() else stopRingback()
        val inCall = phase == CallPhase.Calling || phase == CallPhase.Connecting || phase == CallPhase.Active
        if (inCall) requestFocus() else abandonFocus()
        // Screen off near your ear, but not on speaker or video.
        if (inCall && !call.video && !call.speakerOn) holdProximity() else releaseProximity()
    }

    private fun startRinging() {
        if (ringtone != null) return
        // Respect silent / vibrate mode.
        when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> {
                ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (Build.VERSION.SDK_INT >= 28) isLooping = true
                    runCatching { play() }
                }
                vibrate()
            }
            AudioManager.RINGER_MODE_VIBRATE -> vibrate()
        }
    }

    private fun vibrate() {
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 700), 0)) }
    }

    private fun stopRinging() {
        ringtone?.let { runCatching { it.stop() } }
        if (ringtone != null || audio.ringerMode != AudioManager.RINGER_MODE_SILENT) runCatching { vibrator.cancel() }
        ringtone = null
    }

    private fun startRingback() {
        if (ringback != null) return
        ringback = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) }.getOrNull()?.apply {
            startTone(ToneGenerator.TONE_SUP_RINGTONE) // repeats until stopped
        }
    }

    private fun stopRingback() {
        ringback?.let {
            runCatching { it.stopTone() }
            it.release()
        }
        ringback = null
    }

    private fun requestFocus() {
        if (focus != null || Build.VERSION.SDK_INT < 26) return
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .build()
            .also { audio.requestAudioFocus(it) }
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun holdProximity() {
        proximity?.takeIf { !it.isHeld }?.acquire(3 * 60 * 60 * 1000L)
    }

    private fun releaseProximity() {
        proximity?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
    }
}
