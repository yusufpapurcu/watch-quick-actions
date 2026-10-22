#!/usr/bin/env bash
# End-to-end check of the call bridge on a running emulator or device.
# Usage: scripts/verify-emulator.sh [path/to/app-debug.apk]
set -u

PKG=com.watchassistantbridge
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
ADB=${ADB:-"${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/platform-tools/adb"}
TRIGGER=${TRIGGER:-0000}
NORMAL_NUMBER=${NORMAL_NUMBER:-5551234}
DELAY_WAIT=${DELAY_WAIT:-4}

pass=0
fail=0
check() {
  if [ "$2" = "0" ]; then echo "PASS  $1"; pass=$((pass + 1)); else echo "FAIL  $1"; fail=$((fail + 1)); fi
}
sh() { "$ADB" shell "$@" | tr -d '\r'; }

has_live_call() { sh dumpsys telecom | grep -qE 'state=(DIALING|CONNECTING|ACTIVE|NEW|SELECT_PHONE_ACCOUNT)'; }
# Google's VOICE_COMMAND handler is a trampoline into a headless headset-query service,
# so the assistant never stays in front; assert on the system's own launch records instead.
assistant_started() {
  local log; log=$("$ADB" logcat -d | tr -d '
')
  echo "$log" | grep -q "START u0 {act=android.intent.action.VOICE_COMMAND.*result code=0" &&
    echo "$log" | grep -q "HeadsetQueryCommitService"
}
wakefulness() { sh dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*' ; }
end_calls() { "$ADB" shell input keyevent KEYCODE_ENDCALL; sleep 1; }
audio_mode() { sh dumpsys audio | grep 'setMode(' | tail -1 | grep -o 'setMode([A-Z_]*) from package=[a-z.]*' ; }
home() { "$ADB" shell input keyevent KEYCODE_HOME; sleep 1; }

"$ADB" install -r -g "$APK" >/dev/null || { echo "install failed"; exit 1; }
sh cmd role add-role-holder android.app.role.CALL_REDIRECTION "$PKG" 0
sh appops set "$PKG" SYSTEM_ALERT_WINDOW allow
sh dumpsys deviceidle whitelist +"$PKG" >/dev/null
sh cmd role get-role-holders android.app.role.CALL_REDIRECTION | grep -q "$PKG"
check "app holds CALL_REDIRECTION role" $?

"$ADB" shell am force-stop com.google.android.googlequicksearchbox
end_calls; home

echo "--- trigger number $TRIGGER, screen unlocked"
"$ADB" logcat -c
sh am start -a android.intent.action.CALL -d "tel:$TRIGGER" >/dev/null
sleep "$DELAY_WAIT"
! has_live_call
check "trigger call never reaches telecom as a live call" $?
assistant_started
check "assistant voice query started (VOICE_COMMAND -> HeadsetQueryCommitService)" $?
"$ADB" logcat -d -s WatchBridge:* | sed 's/^/    /'

echo "--- trigger number with formatting (00-00), screen off"
"$ADB" shell am force-stop com.google.android.googlequicksearchbox
home
"$ADB" shell input keyevent KEYCODE_SLEEP; sleep 2
w=$(wakefulness); echo "$w" | grep -q Asleep
check "screen is off before trigger: $w" $?
"$ADB" logcat -c
sh am start -a android.intent.action.CALL -d "tel:00-00" >/dev/null
sleep "$DELAY_WAIT"
w=$(wakefulness); echo "$w" | grep -q Awake
check "trigger wakes the screen: $w" $?
! has_live_call
check "formatted trigger call is cancelled" $?
assistant_started
check "assistant voice query started from screen-off" $?
"$ADB" logcat -d -s WatchBridge:* | sed 's/^/    /'
"$ADB" shell input keyevent KEYCODE_WAKEUP; "$ADB" shell wm dismiss-keyguard; sleep 1

echo "--- normal number $NORMAL_NUMBER passes through"
end_calls; home
"$ADB" logcat -c
sh am start -a android.intent.action.CALL -d "tel:$NORMAL_NUMBER" >/dev/null
sleep 4
has_live_call
check "normal call is placed" $?
end_calls

echo "--- bridge disabled: trigger number passes through"
write_prefs() {
  local b64; b64=$(printf '%s' "$1" | base64 -w0)
  "$ADB" shell "run-as $PKG sh -c 'mkdir -p shared_prefs && echo $b64 | base64 -d > shared_prefs/bridge_settings.xml'"
  "$ADB" shell am force-stop "$PKG"
}
write_prefs "<map><boolean name='isServiceEnabled' value='false' /></map>"
end_calls; home
"$ADB" logcat -c
sh am start -a android.intent.action.CALL -d "tel:$TRIGGER" >/dev/null
sleep 4
has_live_call
check "disabled bridge lets the trigger call through" $?
end_calls

echo "--- custom trigger number 1234"
write_prefs "<map><string name='triggerNumber'>1234</string></map>"
sh am start -a android.intent.action.CALL -d "tel:12-34" >/dev/null
sleep 4
! has_live_call
check "custom trigger 12-34 is cancelled" $?
end_calls
sh am start -a android.intent.action.CALL -d "tel:$TRIGGER" >/dev/null
sleep 4
has_live_call
check "old trigger $TRIGGER now passes through" $?
end_calls
echo "--- audio auto-release after scoTimeoutSeconds=10"
write_prefs "<map><int name='scoTimeoutSeconds' value='10' /></map>"
sh am start -a android.intent.action.CALL -d "tel:$TRIGGER" >/dev/null
sleep 3
m=$(audio_mode); echo "$m" | grep -q MODE_IN_COMMUNICATION
check "session holds $m" $?
sleep 10
m=$(audio_mode); echo "$m" | grep -q "MODE_NORMAL) from package=$PKG"
check "timeout released audio: $m" $?
"$ADB" shell "run-as $PKG rm -f shared_prefs/bridge_settings.xml"
"$ADB" shell am force-stop "$PKG"
echo
echo "passed=$pass failed=$fail"
[ "$fail" = "0" ]
