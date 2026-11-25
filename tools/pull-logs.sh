#!/bin/sh
# Copies the test log kept on the headset into test-logs/ (ignored by git) and restarts the recording.
# The recording runs on the headset itself, so it survives the USB cable dropping out.
set -e
cd "$(dirname "$0")/.."
mkdir -p test-logs
out="test-logs/$(date +%Y-%m-%d_%H-%M-%S).log"
adb wait-for-device
adb shell 'cat /data/local/tmp/farsofa.log.2 /data/local/tmp/farsofa.log.1 /data/local/tmp/farsofa.log 2>/dev/null' > "$out"
adb shell 'for pid in $(pgrep -f "logcat -f /data/local/tmp/farsofa.log"); do kill $pid; done; sleep 1; rm -f /data/local/tmp/farsofa.log*'
adb shell 'setsid sh -c "logcat -f /data/local/tmp/farsofa.log -r 16384 -n 2 -v time Chiaki:V Farsofa:V FarsofaXr:V FarsofaInput:V FarsofaDiag:V FarsofaPad:V FarsofaSmooth:V FarsofaPsn:V AndroidRuntime:E AAudio:W AudioTrack:W *:S" </dev/null >/dev/null 2>&1 &'
echo "$out ($(wc -l < "$out") lines)"
