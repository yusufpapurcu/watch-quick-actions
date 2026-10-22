package com.watchassistantbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.watchassistantbridge.AudioAssistantBridge
import com.watchassistantbridge.BridgePreferences
import com.watchassistantbridge.BridgeSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TEST_COUNTDOWN_SECONDS = 2

@Composable
fun Dashboard(preferences: BridgePreferences) {
    var settings by remember { mutableStateOf(preferences.read()) }
    val onChange: ((BridgeSettings) -> BridgeSettings) -> Unit = { transform ->
        preferences.update(transform)
        settings = preferences.read()
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Watch Asistan Köprüsü", style = MaterialTheme.typography.headlineMedium)
            PermissionSection()
            SettingsSection(settings, onChange)
            ControlSection()
        }
    }
}

@Composable
private fun ControlSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var countdown by remember { mutableIntStateOf(0) }

    SectionTitle("Test ve Kontrol")
    Button(
        onClick = {
            scope.launch {
                // Gives the user time to lock the screen before the assistant starts.
                for (second in TEST_COUNTDOWN_SECONDS downTo 1) {
                    countdown = second
                    delay(1000)
                }
                countdown = 0
                AudioAssistantBridge.startScoAndLaunchAssistant(context)
            }
        },
        enabled = countdown == 0,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (countdown > 0) "Başlıyor… $countdown" else "Şimdi Test Et (Saati Simüle Et)")
    }
    OutlinedButton(
        onClick = { AudioAssistantBridge.releaseAudio(context) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("SCO Ses Kanalını Sıfırla")
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
}
