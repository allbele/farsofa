#!/bin/zsh
# Prepares the headset for recording the marketing videos, and fetches what was recorded.
#
#   tools/gravar-video.sh preparar   with the Quest on the cable: full HD, 60 frames a second, high bitrate
#   tools/gravar-video.sh baixar     copies the recordings from the headset to marketing/gravacoes/
#   tools/gravar-video.sh normal     puts the headset's recording settings back
#
# The settings are lost when the headset restarts, so "preparar" is run once per recording session.
# The wider picture ("Cinematic") is chosen in the Meta Quest Developer Hub, under Device > Cast/Record
# settings; any app that is open in the headset has to be reopened after changing it.
set -e
ROOT=${0:A:h:h}
case $1 in
  preparar)
    adb get-state >/dev/null
    adb shell setprop debug.oculus.capture.width 1920
    adb shell setprop debug.oculus.capture.height 1080
    adb shell setprop debug.oculus.capture.bitrate 20000000
    adb shell setprop debug.oculus.fullRateCapture 1
    echo "Pronto: 1920x1080, 60 quadros por segundo, 20 Mbit/s."
    echo "Grave pelo botão Meta > Câmera > Gravar vídeo, ou pelo Developer Hub."
    ;;
  normal)
    adb shell setprop debug.oculus.capture.width 1024
    adb shell setprop debug.oculus.capture.height 1024
    adb shell setprop debug.oculus.capture.bitrate 5000000
    adb shell setprop debug.oculus.fullRateCapture 0
    echo "Gravação do headset de volta ao padrão."
    ;;
  baixar)
    DEST=$ROOT/marketing/gravacoes/$(date +%Y-%m-%d)
    mkdir -p $DEST
    adb shell ls /sdcard/Oculus/VideoShots/ | tr -d '\r' | while read -r file; do
      [[ -z $file || -e $DEST/$file ]] && continue
      adb pull "/sdcard/Oculus/VideoShots/$file" "$DEST/$file" >/dev/null && echo "baixado: $file"
    done
    echo "Em $DEST"
    ;;
  *)
    sed -n 2,11p $0
    ;;
esac
