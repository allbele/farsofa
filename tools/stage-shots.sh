#!/bin/sh
# Takes the store screenshots with nobody wearing the headset: keeps it awake, stages each demo scene
# from the launch command, and saves the raw captures to store/screenshots/raw/ (not committed).
# Stand the headset upright on a flat surface first.
cd "$(dirname "$0")/.."
mkdir -p store/screenshots/raw
shot() {
  name=$1; shift
  adb wait-for-device
  adb shell "am broadcast -a com.oculus.vrpowermanager.prox_close >/dev/null; input keyevent KEYCODE_WAKEUP; am force-stop app.farsofa.vr; sleep 1; am start -n app.farsofa.vr/.ui.MainActivity --ez demo true $* >/dev/null; sleep 9"
  adb exec-out screencap -p > "store/screenshots/raw/$name.png"
  echo "$name $(stat -f %z "store/screenshots/raw/$name.png") bytes"
}
shot 1-dark-flat "--ef width 4.0 --ef distance 3.0"
shot 2-dark-curved "--ef width 6.4 --ef distance 4.0 --ez curved true"
shot 3-controls "--ef width 3.2 --ef distance 2.6 --ez controls true"
shot 4-frame "--ef width 3.4 --ef distance 2.8 --ez frame true"
shot 5-room "--ef width 3.6 --ef distance 3.0 --ez passthrough true"
shot 6-room-curved "--ef width 5.5 --ef distance 3.6 --ez passthrough true --ez curved true"
shot 7-pin "--ef width 3.2 --ef distance 2.6 --ez pin true"
adb shell "am force-stop app.farsofa.vr; am broadcast -a com.oculus.vrpowermanager.automation_disable >/dev/null"
