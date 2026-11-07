#!/usr/bin/env bash
# Feeds heart rate to a Wear OS 4+ emulator's Health Services, about one reading per second,
# through the content provider Android Studio's Wear Health Services panel uses (its adb command
# strings, found in Studio's plugin). The extended-controls heart rate and the Wear OS 3
# whs.synthetic broadcasts don't reach Health Services on these images. An exercise must already
# be running, i.e. a watch session started from the phone.
#
# Usage: docker/whs-pulse.sh [start_bpm=80] [seconds=120] [end_bpm=start_bpm] [serial=emulator-5554]
#   Steady:     docker/whs-pulse.sh 80 600
#   Descending: docker/whs-pulse.sh 90 600 65     (straight line from 90 to 65 over 10 minutes)
# Clear the override afterwards: adb -s emulator-5554 shell content delete --uri <uri below>
set -euo pipefail
start="${1:-80}" seconds="${2:-120}" end_bpm="${3:-${1:-80}}" serial="${4:-emulator-5554}"
uri=content://com.google.android.wearable.healthservices.dev.synthetic/synthetic_config

# The provider emits a reading only when the value changes, so every other reading adds 1 BPM
# (inside the phone's 1 BPM steadiness tolerance). Each adb round trip takes about a second,
# which sets the pace without a sleep.
t0=$SECONDS i=0
while (( SECONDS - t0 < seconds )); do
    elapsed=$(( SECONDS - t0 ))
    base=$(( start + (end_bpm - start) * elapsed / seconds ))
    v=$(( base + i++ % 2 ))
    adb -s "$serial" shell content update --uri "$uri" --bind "HEART_RATE_BPM:f:$v"
    printf '\r%4ds  %3d bpm ' "$elapsed" "$v"
done
echo
echo "Stopped feeding at $v bpm; readings stop now. Clear the override with:"
echo "  adb -s $serial shell content delete --uri $uri"
