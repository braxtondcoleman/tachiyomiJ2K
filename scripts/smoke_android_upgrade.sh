#!/usr/bin/env bash
# Runs only against an empty disposable Android emulator; never uninstall a user's app.
set -euo pipefail
baseline="${1:?baseline APK required}"
upgrade="${2:?updated APK required}"
package="eu.kanade.tachiyomi.customj2k.debug"
[[ -s "$baseline" && -s "$upgrade" ]] || { echo "Missing upgrade fixture APKs" >&2; exit 1; }

adb wait-for-device
adb install -r "$baseline"
adb shell pm path "$package" | grep -F 'package:'
adb shell run-as "$package" mkdir -p files
adb shell run-as "$package" touch files/j2k_custom_upgrade_preserved
# run-as reports the package's actual Linux UID and is more stable than
# parsing dumpsys package output, whose labels vary across Android versions.
uid_before="$(adb shell run-as "$package" id -u | tr -d '\r\n')"
[[ "$uid_before" =~ ^[0-9]+$ ]] || { echo "Cannot read baseline Android UID" >&2; exit 1; }

adb install -r "$upgrade"
adb shell pm path "$package" | grep -F 'package:'
adb shell run-as "$package" test -f files/j2k_custom_upgrade_preserved || {
  echo "App private files lost on upgrade" >&2
  exit 1
}
uid_after="$(adb shell run-as "$package" id -u | tr -d '\r\n')"
[[ "$uid_after" =~ ^[0-9]+$ && "$uid_before" == "$uid_after" ]] || { echo "Android UID changed; may have reinstalled!" >&2; exit 1; }
echo "PASS: Signed upgrade retained the package UID and private data file."
