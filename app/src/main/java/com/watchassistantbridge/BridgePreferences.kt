package com.watchassistantbridge

import android.content.Context

class BridgePreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun read(): BridgeSettings {
        val defaults = BridgeSettings()
        return BridgeSettings(
            triggerNumber = prefs.getString(KEY_TRIGGER, defaults.triggerNumber) ?: defaults.triggerNumber,
            delayMs = prefs.getLong(KEY_DELAY, defaults.delayMs),
            forceScoAudio = prefs.getBoolean(KEY_FORCE_SCO, defaults.forceScoAudio),
            scoTimeoutSeconds = prefs.getInt(KEY_SCO_TIMEOUT, defaults.scoTimeoutSeconds),
            isServiceEnabled = prefs.getBoolean(KEY_ENABLED, defaults.isServiceEnabled),
        ).clamped()
    }

    /** Atomic read-modify-write; the lock is shared because the service and the UI hold separate instances. */
    fun update(transform: (BridgeSettings) -> BridgeSettings) {
        synchronized(lock) {
            val next = transform(read()).clamped()
            prefs.edit()
                .putString(KEY_TRIGGER, next.triggerNumber)
                .putLong(KEY_DELAY, next.delayMs)
                .putBoolean(KEY_FORCE_SCO, next.forceScoAudio)
                .putInt(KEY_SCO_TIMEOUT, next.scoTimeoutSeconds)
                .putBoolean(KEY_ENABLED, next.isServiceEnabled)
                .apply()
        }
    }

    private fun BridgeSettings.clamped() = copy(
        delayMs = delayMs.coerceIn(BridgeSettings.DELAY_RANGE),
        scoTimeoutSeconds = scoTimeoutSeconds.coerceIn(BridgeSettings.SCO_TIMEOUT_RANGE),
    )

    private companion object {
        val lock = Any()
        const val FILE_NAME = "bridge_settings"
        const val KEY_TRIGGER = "triggerNumber"
        const val KEY_DELAY = "delayMs"
        const val KEY_FORCE_SCO = "forceScoAudio"
        const val KEY_SCO_TIMEOUT = "scoTimeoutSeconds"
        const val KEY_ENABLED = "isServiceEnabled"
    }
}
