#!/usr/bin/env bash
# Build, install and make this app the assistant the remote voice button opens.
# Usage: ./install.sh <tv ip>    (network debugging on: Settings > Device preferences > Developer options)
set -euo pipefail
TV="${1:?usage: ./install.sh <tv ip>}"
PKG=com.tvvoice.assistant
adb connect "$TV:5555" >/dev/null
./gradlew assembleRelease -q
adb -s "$TV:5555" install -r app/build/outputs/apk/release/app-release.apk
# The overlay records only while it is on screen; the runtime grant covers that.
adb -s "$TV:5555" shell pm grant "$PKG" android.permission.RECORD_AUDIO
# Reminders open the panel over whatever is on screen: that needs "display over other apps".
adb -s "$TV:5555" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
# The voice button starts whichever app holds the assistant role (docs/FINDINGS.md).
adb -s "$TV:5555" shell cmd role add-role-holder android.app.role.ASSISTANT "$PKG" 0
echo "assistant role: $(adb -s "$TV:5555" shell cmd role get-role-holders android.app.role.ASSISTANT)"
echo "Done. Open the app tile to add an API key, then hold the voice button and speak."
