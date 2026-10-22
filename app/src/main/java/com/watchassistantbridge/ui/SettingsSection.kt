package com.watchassistantbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.watchassistantbridge.BridgeSettings

@Composable
fun SettingsSection(settings: BridgeSettings, onChange: ((BridgeSettings) -> BridgeSettings) -> Unit) {
    SectionTitle("Ayarlar")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SwitchRow("Köprü Servisini Etkinleştir", settings.isServiceEnabled) { enabled ->
                onChange { it.copy(isServiceEnabled = enabled) }
            }

            OutlinedTextField(
                value = settings.triggerNumber,
                onValueChange = { number -> onChange { it.copy(triggerNumber = number) } },
                label = { Text("Tetikleyici Numara") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )

            SliderSetting(
                label = "HFP Kapanma Gecikmesi (ms)",
                value = settings.delayMs.toFloat(),
                range = BridgeSettings.DELAY_RANGE.first.toFloat()..BridgeSettings.DELAY_RANGE.last.toFloat(),
                steps = ((BridgeSettings.DELAY_RANGE.last - BridgeSettings.DELAY_RANGE.first) / BridgeSettings.DELAY_STEP - 1).toInt(),
                unit = "ms",
                hint = "Saat aramayı iptal ettikten sonra kendi HFP çağrı durumunu kapatmak için biraz zamana ihtiyaç duyar. " +
                    "Ses kanalı bu bitmeden açılırsa Bluetooth HFP yarış durumu oluşur ve kanal kurulamaz ya da hemen kopar. " +
                    "Çok kısa değer kanalın kurulmasını bozar, çok uzun değer asistanı geciktirir.",
                onCommit = { v -> onChange { it.copy(delayMs = v.toLong()) } },
            )

            SwitchRow("Bluetooth SCO Mikrofonunu Zorla", settings.forceScoAudio) { force ->
                onChange { it.copy(forceScoAudio = force) }
            }

            SliderSetting(
                label = "SCO Kanalı Otomatik Kapanma Süresi (sn)",
                value = settings.scoTimeoutSeconds.toFloat(),
                range = BridgeSettings.SCO_TIMEOUT_RANGE.first.toFloat()..BridgeSettings.SCO_TIMEOUT_RANGE.last.toFloat(),
                steps = BridgeSettings.SCO_TIMEOUT_RANGE.last - BridgeSettings.SCO_TIMEOUT_RANGE.first - 1,
                unit = "sn",
                hint = null,
                onCommit = { v -> onChange { it.copy(scoTimeoutSeconds = v.toInt()) } },
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Shows the dragged value live and persists only when the drag ends. */
@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    unit: String,
    hint: String?,
    onCommit: (Float) -> Unit,
) {
    var dragged by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$label: ${dragged.toInt()} $unit", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = dragged,
            onValueChange = { dragged = it },
            onValueChangeFinished = { onCommit(dragged) },
            valueRange = range,
            steps = steps,
        )
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
    }
}
