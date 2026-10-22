package com.watchassistantbridge

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
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

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRelease: Runnable? = null
    private var scoWait: ScoWait? = null

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
