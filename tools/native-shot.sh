#!/bin/sh
# Takes one screenshot with the headset's own capture service (straight lines, level, 1440 x 1440)
# and saves it as store/screenshots/raw/NAME.jpg.
cd "$(dirname "$0")/.."
mkdir -p store/screenshots/raw
adb wait-for-device
before=$(adb shell 'ls -t /sdcard/Oculus/Screenshots | head -1' | tr -d '\r')
adb shell 'am startservice -n com.oculus.metacam/.capture.CaptureService -a TAKE_SCREENSHOT >/dev/null 2>&1'
for i in 1 2 3 4 5 6; do
  sleep 1
  latest=$(adb shell 'ls -t /sdcard/Oculus/Screenshots | head -1' | tr -d '\r')
  [ "$latest" != "$before" ] && break
done
adb pull "/sdcard/Oculus/Screenshots/$latest" "store/screenshots/raw/$1.jpg" >/dev/null && echo "$1 <- $latest"
