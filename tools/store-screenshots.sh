#!/bin/sh
# Takes store screenshots from the headset: opens the app's demo screen (our own picture, so no one
# else's game appears), then captures one image each time you press Enter while wearing the headset.
# Each capture is the left eye's view cropped to 16:9 and saved at 2560 x 1440 in store/screenshots/.
set -e
cd "$(dirname "$0")/.."
mkdir -p store/screenshots
adb wait-for-device
adb shell am force-stop app.farsofa.vr
adb shell am start -n app.farsofa.vr/.ui.MainActivity --ez demo true >/dev/null
echo "Put the headset on. Frame the shot, then press Enter here (or type q and Enter to finish)."
n=$(ls store/screenshots 2>/dev/null | wc -l | tr -d ' ')
while read -r answer; do
  [ "$answer" = "q" ] && break
  n=$((n + 1))
  raw="store/screenshots/raw-$n.png"
  out="store/screenshots/shot-$n.png"
  adb exec-out screencap -p > "$raw"
  width=$(sips -g pixelWidth "$raw" | awk '/pixelWidth/ {print $2}')
  height=$(sips -g pixelHeight "$raw" | awk '/pixelHeight/ {print $2}')
  eye=$((width / 2))
  crop=$((eye * 9 / 16))
  sips -c "$crop" "$eye" --cropOffset $(((height - crop) / 2)) 0 "$raw" --out "$out" >/dev/null
  sips -z 1440 2560 "$out" >/dev/null
  rm -f "$raw"
  echo "saved $out"
done
