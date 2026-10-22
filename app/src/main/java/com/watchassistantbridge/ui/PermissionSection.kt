package com.watchassistantbridge.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

sealed interface Fix {
    class Launch(val intent: Intent) : Fix
    class Request(val permission: String) : Fix
}

class PermissionCheck(
    val title: String,
    val description: String,
    val isGranted: (Context) -> Boolean,
    val fix: (Context) -> Fix,
)

private fun packageUri(context: Context) = Uri.parse("package:${context.packageName}")

private val permissionChecks = listOf(
    PermissionCheck(
        title = "Varsayılan Arama Yönlendirme Uygulaması",
        description = "Saatten gelen çağrıyı yakalamak için gerekli.",
        isGranted = { it.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION) },
        fix = {
            Fix.Launch(it.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_CALL_REDIRECTION))
        },
    ),
    PermissionCheck(
        title = "Diğer Uygulamaların Üzerinde Göster",
        description = "Asistanı arka plandaki bir servisten açabilmek için gerekli (Android 10+).",
        isGranted = { Settings.canDrawOverlays(it) },
        fix = { Fix.Launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(it))) },
    ),
    PermissionCheck(
        title = "Bluetooth Bağlantı İzni",
        description = "Saatin ses kanalına bağlanmak için gerekli (Android 12+).",
        isGranted = {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(it, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        },
        fix = { Fix.Request(Manifest.permission.BLUETOOTH_CONNECT) },
    ),
    PermissionCheck(
        title = "Pil Optimizasyonu Muafiyeti",
        description = "Uygulamanın kilitli ekranda arka planda öldürülmesini önler.",
        isGranted = { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) },
        fix = { Fix.Launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(it))) },
    ),
)

@Composable
fun PermissionSection() {
    val context = LocalContext.current
    // Bumped to re-run every isGranted check; returning from any system screen resumes the activity.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    SectionTitle("İzinler")
    permissionChecks.forEach { check ->
        val granted = remember(refresh) { check.isGranted(context) }
        PermissionCard(check, granted) {
            when (val fix = check.fix(context)) {
                is Fix.Request -> permissionLauncher.launch(fix.permission)
                is Fix.Launch -> try {
                    activityLauncher.launch(fix.intent)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "Bu ayar ekranı açılamadı.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(check: PermissionCheck, granted: Boolean, onFix: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) colors.secondaryContainer else colors.errorContainer,
        ),
    ) {
        Column(
            Modifier
                .then(if (granted) Modifier else Modifier.clickable(onClick = onFix))
                .padding(16.dp),
        ) {
            Text(check.title, style = MaterialTheme.typography.titleMedium)
            Text(check.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (granted) "Aktif" else "Eksik — düzeltmek için dokunun",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
