#!/bin/bash
# Снимает скриншоты демо-сцен в эмуляторе
set -x
mkdir -p shots
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put system accelerometer_rotation 0
for d in zombies climb inside night body; do
  adb shell am force-stop com.c0bectb11.zomboid
  adb shell am start -n com.c0bectb11.zomboid/.MainActivity --es demo $d
  sleep 6
  adb exec-out screencap -p > shots/$d.png
done
adb shell am force-stop com.c0bectb11.zomboid
adb shell am start -n com.c0bectb11.zomboid/.MainActivity
sleep 4
adb exec-out screencap -p > shots/menu.png
adb logcat -d -s zomboid:* AndroidRuntime:E > shots/log.txt
ls -la shots
