#!/usr/bin/env bash
# Automated standby scenarios on a connected phone: charging, then charger cut off, then battery-only.
# Charging is simulated with `dumpsys battery` and always reset at the end, so the real cable state comes back.
#
# Preconditions: Standby Clock installed and started, phone lying sideways (landscape) and LOCKED,
# screen on. Usage: ANDROID_SERIAL=<serial> scripts/standby-scenarios.sh
set -u
PKG=com.standby.clock
pass=0; fail=0

focus()     { adb shell dumpsys window | grep -m1 mCurrentFocus | grep -q "$PKG/$PKG.ClockActivity"; }
keep_on()   { local fl; fl=$(adb shell dumpsys window windows | grep -A30 "$PKG/$PKG.ClockActivity" | grep -m1 -oE "fl=[0-9a-f]+" | cut -d= -f2)
              [ -n "$fl" ] && (( 0x$fl & 0x80 )); }
awake()     { adb shell dumpsys power | grep -q "mWakefulness=Awake"; }
zen()       { adb shell settings get global zen_mode | tr -d '\r'; }
check()     { if eval "$2"; then echo "  PASS  $1"; pass=$((pass+1)); else echo "  FAIL  $1"; fail=$((fail+1)); fi; }
cleanup()   { adb shell dumpsys battery reset; adb shell input keyevent KEYCODE_WAKEUP; }
trap cleanup EXIT

echo "1. Charging, locked, sideways"
adb shell dumpsys battery reset; adb shell dumpsys battery set ac 1; sleep 1
adb shell input keyevent KEYCODE_SLEEP; sleep 4          # power button: should come straight back with the clock
check "clock shows after power button" focus
check "screen kept on while charging" keep_on
check "Do Not Disturb on while clock shows" '[ "$(zen)" != "0" ]'

echo "2. Charger cut off while the clock shows"
adb shell dumpsys battery unplug; sleep 2
check "clock stays up" focus
check "keep-screen-on released" '! keep_on'

echo "3. Battery only: power button"
adb shell input keyevent KEYCODE_SLEEP; sleep 4
check "screen stays off (no wake on battery)" '! awake'
check "Do Not Disturb restored when clock hidden" '[ "$(zen)" = "0" ]'

echo "4. Battery only: wake while locked, then 5 s idle on the lock screen"
adb shell input keyevent KEYCODE_WAKEUP; sleep 8
check "clock shows on wake" focus
check "screen not kept on" '! keep_on'

echo "5. Charger plugged back in"
adb shell dumpsys battery set ac 1; sleep 2
check "clock still up" focus
check "screen kept on again" keep_on

echo
echo "passed $pass, failed $fail"
[ "$fail" -eq 0 ]
