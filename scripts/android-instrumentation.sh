#!/usr/bin/env bash
set -eu

cd "$(dirname "$0")/../native/android"
result=0
./gradlew --no-daemon -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true connectedDebugAndroidTest || result=$?
if (( result != 0 )); then
  device_files=/sdcard/Android/data/com.sandrox.osservio.ui.test/files
  reports=osservio-ui/build/reports/navigation-pixels
  mkdir -p "$reports"
  for name in navigation-reused.png navigation-fresh.png navigation-unsettled.png; do
    if adb shell test -f "$device_files/$name"; then
      adb pull "$device_files/$name" "$reports/" || echo "Could not retrieve $name" >&2
    fi
  done
fi
exit "$result"
