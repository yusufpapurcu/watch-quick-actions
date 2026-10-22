package com.watchassistantbridge

data class BridgeSettings(
    val triggerNumber: String = "0000",
    val delayMs: Long = 800,
    val forceScoAudio: Boolean = true,
    val scoTimeoutSeconds: Int = 30,
    val isServiceEnabled: Boolean = true,
) {
    companion object {
        val DELAY_RANGE = 200L..2500L
        const val DELAY_STEP = 100L
        val SCO_TIMEOUT_RANGE = 10..60
    }
}
