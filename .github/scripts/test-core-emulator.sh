#!/usr/bin/env bash
set -euo pipefail
serial=emulator-5554
adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$serial" shell appops set com.hfzy.easytier ACTIVATE_VPN allow
probe() {
  local output
  output=$(adb -s "$serial" shell am instrument -w "$@" com.hfzy.easytier.test/com.hfzy.easytier.ProbeInstrumentation)
  printf '%s\n' "$output"
  grep -q 'result=PASS' <<< "$output"
}
probe -e ciCore true -e expectedCommit "$EXPECTED_COMMIT"
probe -e mtuDefaults true
probe -e vpnLifecycle true
probe -e manualStartSetup true
adb -s "$serial" shell am force-stop com.hfzy.easytier
adb -s "$serial" shell am start -n com.hfzy.easytier/.MainActivity
probe -e manualStartStopped true
probe -e recreatedConnectIntent true
for attempt in 1 2 3; do
  probe -e exitUpdate true -e exitEmulator true -e exitDns 1.1.1.1
done
