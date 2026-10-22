# WatchAssistantBridge — Product Specification

## Purpose

WatchAssistantBridge is an Android app that lets a closed-OS smartwatch (Xiaomi Redmi Watch 6 Active) trigger the phone's default voice assistant (Google Assistant / Gemini) and hold a conversation with it, even when the phone is locked and in the user's pocket.

The watch has no app platform, but it supports two things the bridge relies on:

- **Bluetooth HFP calls** — the watch can place calls through the phone and acts as a headset (microphone + speaker).
- **Favorite Contacts** — the watch can dial a stored number with one tap.

The bridge turns a call to a dummy number into an assistant session, routed through the watch's audio.

---

## How It Works

1. **Trigger.** The user dials a dummy trigger number (default `0000`, configurable in the app) from the watch's Favorite Contacts.
2. **Interception.** Android routes the outgoing call request to the app's `CallRedirectionService` (`WatchCallInterceptorService`).
3. **Match.** The service normalizes the dialed number (strips whitespace, dashes, other formatting, and country-code prefixes where applicable) and compares it to the trigger number.
4. **If it matches** (and the service is enabled):
   1. **Cancel the call** with `cancelCall()`, so it never reaches the cellular network.
   2. **Wake the screen** by acquiring a `WakeLock` (`SCREEN_BRIGHT_WAKE_LOCK or ACQUIRE_CAUSES_WAKEUP`) for 10 seconds.
   3. **Wait for HFP teardown.** Pause for a configurable delay (default `800 ms`) so the watch can cleanly end its outgoing HFP call state. Skipping this causes a Bluetooth HFP race condition.
   4. **Open the audio channel.** Establish a Bluetooth HFP/SCO audio channel to the watch:
      - Set `AudioManager.MODE_IN_COMMUNICATION`.
      - API 31+: `setCommunicationDevice()` with the Bluetooth SCO device.
      - API 29–30: fall back to `startBluetoothSco()`.
   5. **Launch the assistant** with `Intent(Intent.ACTION_VOICE_COMMAND)` and flags `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP`.
   6. **Auto-cleanup.** After a configurable timeout (default `30 s`), reset `AudioManager.mode` to `MODE_NORMAL` and release the SCO channel (`clearCommunicationDevice()` / `stopBluetoothSco()`), so the watch does not stay stuck in call-audio mode after the interaction ends.
5. **If it does not match** (or the service is disabled), the call proceeds normally via `placeCallUnmodified()`.

---

## Technical Stack

| Item | Value |
|---|---|
| Language | Kotlin |
| Min SDK | 29 (Android 10) |
| Target SDK | 34 (Android 14) |
| UI | Jetpack Compose, Material 3, dark-mode friendly. The original spec calls for the UI copy to be in Turkish. |
| Persistence | `SharedPreferences`, wrapped in a helper class `BridgePreferences` |

### Stored Settings

| Key | Type | Default | Range |
|---|---|---|---|
| `triggerNumber` | `String` | `"0000"` | — |
| `delayMs` | `Long` | `800` | `200..2500` |
| `forceScoAudio` | `Boolean` | `true` | — |
| `scoTimeoutSeconds` | `Int` | `30` | `10..60` |
| `isServiceEnabled` | `Boolean` | `true` | — |

---

## Components

### `AndroidManifest.xml`

Permissions:

- `android.permission.MODIFY_AUDIO_SETTINGS`
- `android.permission.BLUETOOTH`
- `android.permission.BLUETOOTH_CONNECT`
- `android.permission.WAKE_LOCK`
- `android.permission.SYSTEM_ALERT_WINDOW`
- `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- `android.permission.FOREGROUND_SERVICE`

Components:

- `WatchCallInterceptorService` declared with `android:permission="android.permission.BIND_CALL_REDIRECTION_SERVICE"` and an intent filter for `android.telecom.CallRedirectionService`.
- `MainActivity` declared with `android:showWhenLocked="true"` and `android:turnScreenOn="true"`.

### `BridgePreferences`

A thread-safe `SharedPreferences` wrapper for the settings listed above.

### `AudioAssistantBridge`

A singleton/helper responsible for audio routing and launching the assistant.

- **`wakeUpScreen(context)`** — safely wakes the screen using `PowerManager`.
- **`startScoAndLaunchAssistant(context)`**:
  - Sets `AudioManager.mode = MODE_IN_COMMUNICATION`.
  - If `forceScoAudio` is enabled:
    - API 31+ (`Build.VERSION_CODES.S`): finds a `AudioDeviceInfo.TYPE_BLUETOOTH_SCO` device in `audioManager.availableCommunicationDevices` and calls `setCommunicationDevice()`.
    - API 29–30: registers a one-shot `BroadcastReceiver` for `AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED` (with a 1200 ms fallback timeout), then calls `startBluetoothSco()` and sets `isBluetoothScoOn = true`.
  - Fires `Intent(Intent.ACTION_VOICE_COMMAND)` inside `try/catch`; shows a Toast if no assistant app is found.
  - Schedules `releaseAudio(context)` on `Handler(Looper.getMainLooper())` after `scoTimeoutSeconds`.
- **`releaseAudio(context)`** — calls `clearCommunicationDevice()`, `stopBluetoothSco()`, and resets `audioManager.mode = MODE_NORMAL`.

### `WatchCallInterceptorService`

Extends `CallRedirectionService`.

- Reads settings from `BridgePreferences`.
- Normalizes `handle.schemeSpecificPart` (removes spaces, dashes, and country-code prefixes where applicable).
- If `isServiceEnabled` is true and the number matches `triggerNumber`:
  - Calls `cancelCall()`.
  - Calls `AudioAssistantBridge.wakeUpScreen(applicationContext)`.
  - Posts `AudioAssistantBridge.startScoAndLaunchAssistant(applicationContext)` on the main looper after `delayMs`.
- Otherwise calls `placeCallUnmodified()`.

### `MainActivity` (Compose dashboard)

A single-screen dashboard with three sections.

#### 1. Permission Status Cards

Interactive cards that show **Active** or **Missing — tap to fix**, each with a one-tap launcher:

- **Default Call Redirection Role** — requests `RoleManager.ROLE_CALL_REDIRECTION` via `ActivityResultContracts.StartActivityForResult`.
- **Display Over Other Apps** (`SYSTEM_ALERT_WINDOW`) — needed to launch the assistant from a background service on Android 10+. Opens `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`.
- **Bluetooth Connect Permission** (`BLUETOOTH_CONNECT`) — runtime permission request on Android 12+.
- **Battery Optimization Exemption** — checks `PowerManager.isIgnoringBatteryOptimizations` and opens `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

#### 2. Settings

- **Master switch:** "Enable Bridge Service" (`isServiceEnabled`).
- **Text field:** "Trigger Number" (default `0000`).
- **Slider:** "HFP Teardown Delay (ms)" — 200–2500 ms, step 100 ms, default 800 ms. Includes an explanation of the Bluetooth HFP race condition.
- **Switch:** "Force Bluetooth SCO Microphone" (`forceScoAudio`).
- **Slider:** "SCO Channel Auto-Close Timeout (s)" — 10–60 s, default 30 s.

#### 3. Test and Control Buttons

- **"Test Now (Simulate Watch)"** — after a 2-second countdown (giving the user time to lock the screen), calls `AudioAssistantBridge.startScoAndLaunchAssistant()` directly to test watch audio routing.
- **"Reset SCO Audio Channel"** — calls `AudioAssistantBridge.releaseAudio()` manually, in case the watch stays stuck in call mode during testing.

### `build.gradle.kts` (app module)

Dependencies configured for Jetpack Compose and Material 3.
