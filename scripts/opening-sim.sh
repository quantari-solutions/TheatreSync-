#!/usr/bin/env bash
# Simulates the owner opening TheatreSync on a phone. Owner's rule: the opening plays only on a fresh start,
# never when returning to the app while it runs in the background.
# (emulator, installed app, no test framework) and checks
# the Quantari opening: shown, and for its full length (~6.6 s), in each situation. Run by CI after the
# release APK is installed. Uses the app's own log lines "Opening: shown" / "Opening: finished after N ms".
set -u
PKG=com.theatresync.app
FAIL=0
tap_icon() { adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }
log_lines() { adb logcat -d -s TheatreSync:I | grep "Opening:" ; }
expect() { # $1 = name, $2 = yes|no
  sleep 9
  local got; got=$(log_lines)
  local shown=no; echo "$got" | grep -q "Opening: shown" && shown=yes
  local ms; ms=$(echo "$got" | grep -o "finished after [0-9]*" | grep -o "[0-9]*" | tail -1)
  if [ "$2" = yes ]; then
    if [ "$shown" = yes ] && [ -n "$ms" ] && [ "$ms" -ge 6000 ]; then echo "PASS  $1: opening shown for ${ms} ms"
    else echo "FAIL  $1: expected the opening for ~6.6 s, got shown=$shown finished=${ms:-never} ms"; FAIL=1; fi
  else
    if [ "$shown" = no ]; then echo "PASS  $1: no opening (as wanted)"
    else echo "FAIL  $1: opening shown but shouldn't be"; FAIL=1; fi
  fi
  adb logcat -c
}
run_suite() { # $1 = label
  adb shell am force-stop $PKG; adb logcat -c
  tap_icon;                                   expect "$1 · first open from the icon" yes
  adb shell input keyevent HOME; sleep 36; tap_icon;              expect "$1 · back from the home screen after 36 s (still running: no intro)" no
  adb shell input keyevent HOME; sleep 5;  tap_icon;              expect "$1 · quick switch, 5 s" no
  adb shell input keyevent KEYCODE_APP_SWITCH; sleep 2; adb shell input keyevent HOME; sleep 36
  adb shell am start -n $PKG/.MainActivity >/dev/null;            expect "$1 · back after 36 s from recent apps (still running: no intro)" no
  adb shell input keyevent KEYCODE_SLEEP; sleep 40; adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; sleep 1
  expect "$1 · phone went idle with the app open, then woke" no
  adb shell input keyevent HOME; sleep 2; adb shell am kill $PKG >/dev/null 2>&1; sleep 34
  tap_icon;                                                       expect "$1 · Android closed it in the background, reopened (no intro)" no
  adb shell am force-stop $PKG; sleep 2; tap_icon;                expect "$1 · closed / swiped away, then opened again (fresh start)" yes
}
echo "== Animations OFF (like battery saver / Remove animations) =="
adb shell settings put global window_animation_scale 0; adb shell settings put global transition_animation_scale 0; adb shell settings put global animator_duration_scale 0
run_suite "animations off"
echo "== Animations ON (phone default) =="
adb shell settings put global window_animation_scale 1; adb shell settings put global transition_animation_scale 1; adb shell settings put global animator_duration_scale 1
run_suite "animations on"
adb shell settings put global window_animation_scale 0; adb shell settings put global transition_animation_scale 0; adb shell settings put global animator_duration_scale 0
[ $FAIL = 0 ] && echo "Opening simulation passed" || { echo "Opening simulation FAILED"; exit 1; }
