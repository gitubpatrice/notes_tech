#!/bin/sh
# Restarts the emulator's Android framework once, before the instrumented tests, so that every app gets
# the heap the emulator asked for.
#
# Why (2026-11-17): on the API 29 image, the app heap size (`dalvik.vm.heapsize`) is set by the
# `on boot` action of `init.ranchu.rc` (device/generic/goldfish, android10-release, lines 36-37), while
# zygote, the process every app is forked from, is started earlier, at `zygote-start`. When zygote reads
# the property before `on boot` has set it, it falls back to ART's default, 16 MB
# (`AndroidRuntime.cpp:770`). That is a race, so it fails at random: on 2026-11-10 (main) and 2026-11-17
# (PR #66) the test app ran with "Clamp target GC heap from 22MB to 16MB", Argon2 (64 MiB) threw
# OutOfMemoryError in series, and the instrumentation process died after 247 of 495 cases.
#
# Restarting the framework once boot has completed is deterministic: the property is set by then, and
# the new zygote reads it. Checked on a local API 29 emulator (pixel_2, 4 GB, as here), 2026-11-17:
# - negative control: zygote restarted with 16 MB, then the property put back → SauvegardeChiffreeTest
#   fails 9 out of 9 with OutOfMemoryError and the same "Clamp target GC heap … to 16MB" as on CI;
# - this script, in this workflow's order (script, PIN, install) on a wiped emulator →
#   SauvegardeChiffreeTest and NoteExporteurTest: OK (15 tests). The framework is back within 10 s.
#
# Called from the emulator step of `.github/workflows/android.yml`, where the emulator is the only
# device. Locally, pick the device first: `ANDROID_SERIAL=emulator-5554 sh tools/prepare-ci-emulator.sh`.
set -eu

prop() { adb shell getprop "$1" | tr -d '\r'; }

heap=$(prop dalvik.vm.heapsize)
if [ -z "$heap" ]; then
    echo "::error::dalvik.vm.heapsize is still empty after boot: a framework restart would not help"
    exit 1
fi
echo "dalvik.vm.heapsize = $heap; restarting the framework so that zygote reads it"

# `stop` and `start` need a root shell; the shell goes back to its usual user at the end.
adb root
adb wait-for-device
adb shell stop
adb shell setprop sys.boot_completed 0
adb shell start

waited=0
until [ "$(prop sys.boot_completed)" = 1 ]; do
    if [ "$waited" -ge 300 ]; then
        echo "::error::the framework did not come back within 300 s of its restart"
        exit 1
    fi
    sleep 2
    waited=$((waited + 2))
done
echo "framework back after ${waited} s"

# ⚠️ Run this BEFORE a lock screen PIN is set. Once a PIN exists, a framework restart leaves the user
# locked (RUNNING_LOCKED) until someone types it: the credential-encrypted storage stays closed, and
# every test that opens SharedPreferences fails. Measured on 2026-11-17 by running it after the PIN.
waited=0
until adb shell dumpsys user | grep -q "State: RUNNING_UNLOCKED"; do
    if [ "$waited" -ge 60 ]; then
        echo "::error::the user is still locked after the restart: was a lock screen PIN set before this script?"
        exit 1
    fi
    sleep 2
    waited=$((waited + 2))
done
echo "user unlocked"

adb unroot
adb wait-for-device
