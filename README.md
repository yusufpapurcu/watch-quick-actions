# WatchAssistantBridge

WatchAssistantBridge lets a Redmi Watch 6 Active start Google Assistant or Gemini on the phone by dialing a dummy number. The app cancels that call before it reaches the network, wakes the screen, routes audio to the watch over Bluetooth SCO, and opens the assistant. [docs/product-spec.md](docs/product-spec.md) describes the full flow.

## Build the APK

The build needs JDK 17. Android Studio ships one in its `jbr` folder.

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug testDebugUnitTest
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Set up the phone

1. Install the APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
2. Open **Watch Asistan Köprüsü**.
3. Tap each red card under **İzinler** until all four read **Aktif**. The cards cover the call redirection role, display over other apps, Bluetooth connect, and the battery optimization exemption.
4. Under **Ayarlar**, keep the trigger number at `0000` or set your own.

To check audio routing without the watch dialing, tap **Şimdi Test Et (Saati Simüle Et)**. You have two seconds to lock the screen before the assistant opens. If the watch stays in call-audio mode, tap **SCO Ses Kanalını Sıfırla**.

## Set up the watch

1. On the phone, save a contact whose number is the trigger number (`0000` by default).
2. In Mi Fitness, add that contact to the watch's favorite contacts.
3. With the watch connected to the phone over Bluetooth, tap the contact on the watch.

The phone should wake and the assistant should start listening through the watch.

## Verify on an emulator

`scripts/verify-emulator.sh` installs the APK on a running emulator, grants the permissions through `adb`, and checks the bridge end to end. It asserts on system state, not on the app's own logs:

- The trigger number, including formatted as `00-00`, never becomes a live call.
- The system accepts the `VOICE_COMMAND` launch, with the screen on and from sleep.
- The trigger wakes a sleeping screen.
- Other numbers still place a call.
- The **Köprü Servisini Etkinleştir** switch and a custom trigger number take effect.
- The app holds `MODE_IN_COMMUNICATION` for the whole session and resets it itself when the timeout ends.

Start an emulator, then run:

```bash
bash scripts/verify-emulator.sh
```

In Git Bash, pass the APK as a Windows path (`C:/...`), or as a path relative to the repository.

## Known limits

- The emulator has no Bluetooth, so `setCommunicationDevice` finds no SCO device there. Audio routing to the watch is verified only on real hardware.
- The API 29 and 30 path (`startBluetoothSco`) compiles but has not run on a device or emulator.
- Google's `VOICE_COMMAND` handler opens a hands-free voice query without a screen. Whether it uses the watch microphone is up to the assistant.

## Behavior beyond the spec

Two Android behaviors broke the spec's 30 second audio session on API 34 and 36. The app works around both:

- AudioService resets `MODE_IN_COMMUNICATION` about 6 seconds after the app that set it stops playing or recording. The app plays a silent voice-call track for the length of the session.
- Once telecom unbinds the redirection service, the cached-app freezer freezes the process after 10 seconds, so the timeout that releases the audio never runs. The session runs inside `AssistantSessionService`, a short foreground service, which shows a notification while the session is open.
