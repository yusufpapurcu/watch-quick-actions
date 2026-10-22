package com.watchassistantbridge

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast

/** Main-thread only: all mutable state below is touched from the main looper. */
object AudioAssistantBridge {
    private const val TAG = "WatchBridge"
    private const val WAKE_LOCK_TIMEOUT_MS = 10_000L
    private const val SCO_FALLBACK_MS = 1200L
    private const val KEEP_ALIVE_SAMPLE_RATE = 8000

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRelease: Runnable? = null
    private var scoWait: ScoWait? = null
    private var keepAlive: AudioTrack? = null

    @Suppress("DEPRECATION")
    fun wakeUpScreen(context: Context) {
        try {
            val pm = context.getSystemService(PowerManager::class.java)
            val lock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "WatchBridge:wake",
            )
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.d(TAG, "screen wake lock acquired for ${WAKE_LOCK_TIMEOUT_MS}ms")
        } catch (e: Exception) {
            Log.w(TAG, "screen wake failed", e)
        }
    }

    fun startScoAndLaunchAssistant(context: Context) {
        val app = context.applicationContext
        cancelPending()
        val settings = BridgePreferences(app).read()
        val audio = app.getSystemService(AudioManager::class.java)
        Log.d(TAG, "session start: forceSco=${settings.forceScoAudio} timeout=${settings.scoTimeoutSeconds}s")

        AssistantSessionService.start(app)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val release = Runnable {
            Log.d(TAG, "session timeout reached")
            releaseAudio(app)
        }
        pendingRelease = release
        handler.postDelayed(release, settings.scoTimeoutSeconds * 1000L)

        if (!settings.forceScoAudio) {
            Log.d(TAG, "SCO skipped (forceScoAudio off)")
            launchAssistant(app)
            return
        }
        startKeepAlive()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                routeToScoDevice(audio)
                launchAssistant(app)
            } else {
                startLegacySco(app, audio)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SCO routing denied (BLUETOOTH_CONNECT missing?), launching assistant anyway", e)
            scoWait?.cancel()
            launchAssistant(app)
        }
    }

    /** Idempotent: safe with no session and safe to call twice. */
    @Suppress("DEPRECATION")
    fun releaseAudio(context: Context) {
        cancelPending()
        stopKeepAlive()
        AssistantSessionService.stop(context.applicationContext)
        val audio = context.applicationContext.getSystemService(AudioManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audio.clearCommunicationDevice()
            } else {
                audio.stopBluetoothSco()
                audio.isBluetoothScoOn = false
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SCO release denied", e)
        } finally {
            audio.mode = AudioManager.MODE_NORMAL
            Log.d(TAG, "audio released, mode reset to NORMAL")
        }
    }

    private fun cancelPending() {
        pendingRelease?.let { handler.removeCallbacks(it) }
        pendingRelease = null
        scoWait?.cancel()
    }

    // AudioService resets MODE_IN_COMMUNICATION (and drops our SCO route) about 6 s after the mode
    // owner stops playing or recording. The assistant records under its own uid, so a looping
    // silent voice-call track keeps this app the active mode owner until the session ends.
    private fun startKeepAlive() {
        if (keepAlive != null) return
        try {
            val samples = KEEP_ALIVE_SAMPLE_RATE
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(KEEP_ALIVE_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples * 2)
                .build()
            track.write(ShortArray(samples), 0, samples)
            track.setLoopPoints(0, samples, -1)
            track.play()
            keepAlive = track
            Log.d(TAG, "silent keep-alive track playing")
        } catch (e: Exception) {
            Log.w(TAG, "keep-alive track failed; audio mode may reset early", e)
        }
    }

    private fun stopKeepAlive() {
        val track = keepAlive ?: return
        keepAlive = null
        try {
            track.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "keep-alive stop failed", e)
        }
        track.release()
    }

    private fun routeToScoDevice(audio: AudioManager) {
        Log.d(TAG, "SCO path: setCommunicationDevice (API ${Build.VERSION.SDK_INT})")
        val device = audio.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (device == null) {
            Log.w(TAG, "no Bluetooth SCO communication device available")
            return
        }
        val ok = audio.setCommunicationDevice(device)
        Log.d(TAG, "SCO device found, setCommunicationDevice=$ok")
    }

    @Suppress("DEPRECATION")
    private fun startLegacySco(app: Context, audio: AudioManager) {
        Log.d(TAG, "SCO path: startBluetoothSco (API ${Build.VERSION.SDK_INT})")
        val wait = ScoWait(app) { launchAssistant(app) }
        scoWait = wait
        wait.begin()
        audio.startBluetoothSco()
        audio.isBluetoothScoOn = true
    }

    private fun launchAssistant(app: Context) {
        val intent = Intent(Intent.ACTION_VOICE_COMMAND)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            app.startActivity(intent)
            Log.d(TAG, "assistant launched")
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no assistant app found", e)
            Toast.makeText(app, "Sesli asistan uygulaması bulunamadı.", Toast.LENGTH_LONG).show()
        }
    }

    // On API 29-30 the assistant must start only after the SCO link is up, or it grabs the phone mic.
    // Launches at most once: on CONNECTED or after SCO_FALLBACK_MS, whichever comes first.
    private class ScoWait(private val app: Context, private val onReady: () -> Unit) : BroadcastReceiver() {
        private var finished = false
        private val timeout = Runnable {
            Log.d(TAG, "SCO connect wait timed out, using fallback")
            finish(launch = true)
        }

        fun begin() {
            handler.postDelayed(timeout, SCO_FALLBACK_MS)
            app.registerReceiver(this, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        }

        fun cancel() = finish(launch = false)

        override fun onReceive(context: Context, intent: Intent) {
            // The broadcast is sticky; the replayed state is from before startBluetoothSco().
            if (isInitialStickyBroadcast) return
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                Log.d(TAG, "SCO connected")
                finish(launch = true)
            }
        }

        private fun finish(launch: Boolean) {
            if (finished) return
            finished = true
            handler.removeCallbacks(timeout)
            app.unregisterReceiver(this)
            if (scoWait === this) scoWait = null
            if (launch) onReady()
        }
    }
}
