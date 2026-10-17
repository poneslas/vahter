#!/usr/bin/env bash
# Серия открытий листа без человека (П2/П3): N раз KEYCODE_ASSIST → ждём «FRAME» в logcat → BACK.
#   probe/scripts/series.sh [N=20] [пауза_с=2]
# `adb shell cmd voiceinteraction show` не годится: без root — SecurityException
# (нужно ACCESS_VOICE_INTERACTION_SERVICE). KEYCODE_ASSIST идёт тем же путём, что жест помощника
# (флаг SOURCE_ASSIST_GESTURE). Времена — из строк FRAME пробника (от onPrepareToShowSession),
# цену самой боковой кнопки даёт только scripts/trace.sh.
set -euo pipefail
N="${1:-20}"
PAUSE="${2:-2}"

frames() { adb logcat -d -s TasyaProbe:I | grep "FRAME" || true; }

adb logcat -c
for i in $(seq 1 "$N"); do
  adb shell input keyevent KEYCODE_ASSIST
  got=0
  for _ in $(seq 1 30); do
    sleep 0.1
    got=$(frames | wc -l | tr -d ' ')
    [[ "$got" -ge "$i" ]] && break
  done
  [[ "$got" -ge "$i" ]] || echo "#$i: FRAME не пришёл за 3 с" >&2
  sleep 0.5
  adb shell input keyevent KEYCODE_BACK
  sleep "$PAUSE"
done

frames | sed 's/.*FRAME //'
frames | sed -n 's/.*prepare→кадр \([0-9]*\) мс.*/\1/p' | sort -n | awk '
  { a[NR] = $1 }
  END {
    if (!NR) { print "ни одного FRAME"; exit }
    p = int(NR * 0.9); if (p < 1) p = 1
    printf "n=%d медиана %d мс, p90 %d мс, мин %d, макс %d\n", NR, a[int((NR + 1) / 2)], a[p], a[1], a[NR]
  }'
