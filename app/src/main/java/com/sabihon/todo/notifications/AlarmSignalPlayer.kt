package com.sabihon.todo.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Audible, repeating alarm signals for Alarm-style reminders while the alarm surface is visible.
 *
 * Notification-channel sound remains enabled as a system fallback for devices which suppress
 * full-screen intents. This player covers the foreground dialog and the lock-screen activity,
 * both of which otherwise only showed UI and did not make a sustained alarm sound.
 */
class AlarmSignalPlayer(context: Context) {

    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        startTone()
        startVibration()
    }

    @Synchronized
    fun stop() {
        started = false
        player?.let { active ->
            runCatching { if (active.isPlaying) active.stop() }
            runCatching { active.release() }
        }
        player = null
        vibrator()?.let { runCatching { it.cancel() } }
    }

    private fun startTone() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        val candidate = runCatching { MediaPlayer() }.getOrNull() ?: return
        try {
            candidate.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            candidate.isLooping = true
            candidate.setDataSource(appContext, uri)
            candidate.setOnPreparedListener { prepared ->
                synchronized(this) {
                    if (started && player === prepared) {
                        runCatching { prepared.start() }
                    } else {
                        runCatching { prepared.release() }
                    }
                }
            }
            candidate.setOnErrorListener { failed, _, _ ->
                synchronized(this) {
                    if (player === failed) player = null
                    runCatching { failed.release() }
                }
                true
            }
            player = candidate
            candidate.prepareAsync()
        } catch (_: Exception) {
            runCatching { candidate.release() }
            if (player === candidate) player = null
        }
    }

    private fun startVibration() {
        val vibrator = vibrator() ?: return
        if (!runCatching { vibrator.hasVibrator() }.getOrDefault(false)) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VIBRATION_PATTERN, 0)
            }
        }
    }

    private fun vibrator(): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    private companion object {
        val VIBRATION_PATTERN = longArrayOf(0, 700, 350, 700, 350, 1000)
    }
}
