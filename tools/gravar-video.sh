#!/bin/zsh
# Records the launch videos from the headset, driven from the Mac with the Quest on the cable.
#
#   tools/gravar-video.sh largo      wide 16:9 picture, 1920x1080 at 60 frames a second (run once per session)
#   tools/gravar-video.sh simples    the headset's usual picture, only sharper (if "largo" looks wrong)
#   tools/gravar-video.sh iniciar    starts recording
#   tools/gravar-video.sh parar      stops recording
#   tools/gravar-video.sh baixar     copies the recordings to marketing/gravacoes/<today>/
#   tools/gravar-video.sh normal     puts the headset back as it was
#
# "largo" is the trailer preset of the Meta Quest Developer Hub (its 1080p60 option), sent by hand:
# the headset is told to render each eye as a 16:9 picture 90 degrees wide, so the recording is not a
# crop of the middle. While it is on, what the player sees inside the headset looks stretched; the
# recording does not. Reopen the app in the headset after switching modes. Everything here is undone
# by "normal" or by restarting the headset.
set -e
ROOT=${0:A:h:h}
adb get-state >/dev/null 2>&1 || { echo "Quest não encontrado. Conecte o cabo e aceite o aviso dentro do headset."; exit 1; }

set_all() {
  while (( $# )); do adb shell setprop "$1" "$2"; shift 2; done
  # The system service that reads these only does so when it starts.
  adb shell am force-stop com.oculus.systemdriver
}

case $1 in
  largo)
    set_all debug.oculus.refreshRate 60 debug.oculus.fullRateCapture 1 debug.oculus.swapInterval 2 \
      debug.oculus.capture.width 1920 debug.oculus.capture.height 1080 debug.oculus.capture.bitrate 20000000 \
      debug.oculus.eyeFovDown 30 debug.oculus.eyeFovUp 30 debug.oculus.eyeFovOutward 45 debug.oculus.eyeFovInward 45 \
      debug.oculus.textureWidth 1920 debug.oculus.textureHeight 1080 \
      debug.oculus.foveation.level 0 debug.oculus.adaclocks.force 0 debug.oculus.cpuLevel 5 debug.oculus.gpuLevel 5
    echo "Modo largo ligado. Feche e abra de novo o app no headset antes de gravar."
    ;;
  simples)
    set_all debug.oculus.fullRateCapture 1 debug.oculus.capture.width 1920 debug.oculus.capture.height 1080 \
      debug.oculus.capture.bitrate 20000000
    echo "Gravação em 1920x1080 a 60 quadros por segundo, com o enquadramento normal do headset."
    ;;
  normal)
    set_all debug.oculus.refreshRate "" debug.oculus.fullRateCapture "" debug.oculus.swapInterval "" \
      debug.oculus.capture.width "" debug.oculus.capture.height "" debug.oculus.capture.bitrate "" \
      debug.oculus.eyeFovDown "" debug.oculus.eyeFovUp "" debug.oculus.eyeFovOutward "" debug.oculus.eyeFovInward "" \
      debug.oculus.textureWidth "" debug.oculus.textureHeight "" \
      debug.oculus.foveation.level "" debug.oculus.adaclocks.force "" debug.oculus.cpuLevel "" debug.oculus.gpuLevel ""
    echo "Headset de volta ao padrão. Se algo ainda parecer estranho, reinicie o headset."
    ;;
  iniciar)
    adb shell setprop debug.oculus.command_line_media_capture VideoCapture
    adb shell am startservice -n com.oculus.metacam/.capture.CaptureService -a START_INTERNAL_CAPTURE_TO_DISK -e capture_entrypoint ODH >/dev/null
    echo "Gravando. Para parar: tools/gravar-video.sh parar"
    ;;
  parar)
    adb shell am startservice -n com.oculus.metacam/.capture.CaptureService -a STOP_INTERNAL_CAPTURE_TO_DISK >/dev/null
    echo "Gravação encerrada. Para copiar: tools/gravar-video.sh baixar"
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
    sed -n 2,10p $0
    ;;
esac
