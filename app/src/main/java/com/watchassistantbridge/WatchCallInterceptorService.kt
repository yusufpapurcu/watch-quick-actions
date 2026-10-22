package com.watchassistantbridge

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import android.util.Log

class WatchCallInterceptorService : CallRedirectionService() {
    override fun onPlaceCall(
        handle: Uri,
        initialPhoneAccount: PhoneAccountHandle,
        allowInteractiveResponse: Boolean,
    ) {
        Log.d(TAG, "onPlaceCall: outgoing call intercepted")
        // A bug here must never block a real call, so any failure falls back to placing it.
        val settings = try {
            BridgePreferences(applicationContext).read().takeIf { shouldIntercept(it, handle) }
        } catch (e: Exception) {
            Log.e(TAG, "interception check failed, placing call unmodified", e)
            null
        }
        if (settings == null) {
            Log.d(TAG, "call not intercepted, placing unmodified")
            placeCallUnmodified()
            return
        }

        Log.d(TAG, "trigger number matched, cancelling call (assistant in ${settings.delayMs}ms)")
        cancelCall()
        try {
            AudioAssistantBridge.wakeUpScreen(applicationContext)
            Handler(Looper.getMainLooper()).postDelayed(
                { AudioAssistantBridge.startScoAndLaunchAssistant(applicationContext) },
                settings.delayMs,
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to schedule assistant after cancelling call", e)
        }
    }

    private fun shouldIntercept(settings: BridgeSettings, handle: Uri): Boolean =
        settings.isServiceEnabled &&
            TriggerNumber.matches(handle.schemeSpecificPart.orEmpty(), settings.triggerNumber)

    private companion object {
        const val TAG = "WatchBridge"
    }
}
