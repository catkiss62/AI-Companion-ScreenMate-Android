#!/usr/bin/env bash
# emulator-runner executes each script line in a fresh shell; keep status in this process.
set -u
test_status=0
gradle --no-daemon :app:connectedDebugAndroidTest || test_status=$?
mkdir -p app/build/reports/device-ui
adb pull /sdcard/Pictures/ScreenMateQA/. app/build/reports/device-ui/ || true
exit "$test_status"
