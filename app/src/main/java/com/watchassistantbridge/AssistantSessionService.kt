package com.watchassistantbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Holds the process in the foreground for exactly one assistant session. A cancelled call leaves the
 * process cached, and Android's cached-app freezer then stops the main looper that runs the session
 * timeout, so the watch would stay in call-audio mode. Audio logic and state stay in [AudioAssistantBridge].
 */
class AssistantSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        Log.d(TAG, "session service foreground")
        return START_NOT_STICKY
    }

    // API 34 gives a short service 3 minutes, far above the 60 s maximum session; this is the safety net.
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "session service timed out, releasing audio")
        AudioAssistantBridge.releaseAudio(applicationContext)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Asistan oturumu", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Saat asistan oturumu açık")
            .setContentText("Ses kanalı oturum bitince otomatik kapanır.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "WatchBridge"
        private const val CHANNEL_ID = "assistant_session"
        private const val NOTIFICATION_ID = 1

        /** The session continues without the service if the system refuses a background foreground start. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AssistantSessionService::class.java))
            } catch (e: IllegalStateException) {
                // Includes ForegroundServiceStartNotAllowedException (API 31+).
                Log.w(TAG, "session service start refused, process may be frozen mid-session", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "session service start denied, process may be frozen mid-session", e)
            }
        }

        /** Harmless when the service is not running. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AssistantSessionService::class.java))
        }
    }
}
