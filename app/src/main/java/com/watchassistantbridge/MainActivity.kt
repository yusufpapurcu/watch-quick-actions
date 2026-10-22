package com.watchassistantbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.watchassistantbridge.ui.Dashboard
import com.watchassistantbridge.ui.WatchBridgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = BridgePreferences(this)
        setContent {
            WatchBridgeTheme {
                Dashboard(preferences)
            }
        }
    }
}
