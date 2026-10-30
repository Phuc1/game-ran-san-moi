#!/usr/bin/env bash
# Runs inside the emulator (reactivecircus/android-emulator-runner).
# 1) instrumented smoke tests on the debug build
# 2) installs the R8-minified RELEASE build and checks it launches without crashing
set -euo pipefail
PKG="com.aistudio.ransanmoi.gpxvzl"

on_exit() { adb logcat -d 2>/dev/null | tail -150 >> build.log || true; }
trap on_exit EXIT

./gradlew connectedDebugAndroidTest --stacktrace --console=plain 2>&1 | tee -a build.log
./gradlew assembleRelease --stacktrace --console=plain 2>&1 | tee -a build.log

APK="$(ls app/build/outputs/apk/release/*.apk | head -n1)"
echo "Installing $APK"
adb install -r "$APK"
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 10

if ! adb shell pidof "$PKG" > /dev/null; then
  echo "FAILED: release app is not running 10s after launch"; exit 1
fi
adb logcat -d > release-logcat.txt
if grep -q "FATAL EXCEPTION" release-logcat.txt; then
  echo "FAILED: FATAL EXCEPTION in release build"; grep -A15 "FATAL EXCEPTION" release-logcat.txt | head -40; exit 1
fi
echo "Release build launched and stayed alive on API $(adb shell getprop ro.build.version.sdk)"
