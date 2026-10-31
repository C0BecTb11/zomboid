#!/bin/bash
# Проверка APK в эмуляторе: запуск, меню, новая игра, ходьба, журналы
mkdir -p shots
adb install -r apk/zomboid.apk
adb shell settings put system accelerometer_rotation 0
adb logcat -c
adb shell am start -W -n com.c0bectb11.zomboid/com.godot.game.GodotApp | tee shots/start.txt
sleep 20
adb shell dumpsys activity activities | grep -E "topResumedActivity" | tee shots/top.txt
adb exec-out screencap -p > shots/android_menu.png
adb shell wm size | tee -a shots/top.txt
adb shell input tap 1170 366
sleep 25
adb exec-out screencap -p > shots/android_game.png
adb shell input swipe 400 800 620 700 2000
sleep 2
adb exec-out screencap -p > shots/android_walk.png
adb logcat -d -s godot:* AndroidRuntime:E > shots/android_log.txt
adb logcat -d -b crash > shots/crash.txt
adb logcat -d '*:E' | tail -200 > shots/errors.txt
true
