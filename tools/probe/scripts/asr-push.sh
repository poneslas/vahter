#!/usr/bin/env bash
# П15. Кладёт serve/models на телефон в files/models пробника: adb push во временную папку,
# затем run-as копирует в данные приложения (работает только с отладочной сборкой).
#
#   scripts/asr-push.sh               # модели и WAV
#   scripts/asr-push.sh --minutes 2   # плюс длительность долгого прогона (по умолчанию 10)
#
# Запасной путь без adb: пробник сам качает Config.server/models/manifest.json и файлы по нему.
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=ru.poneslas.tasya.probe
M=serve/models
TMP=/data/local/tmp/tasya-models

[[ -f $M/manifest.json ]] || { echo "Нет $M/manifest.json — сначала scripts/asr-fetch.sh --models" >&2; exit 1; }

if [[ ${1:-} == --minutes ]]; then
  echo "long_run_minutes=${2:?укажите минуты}" > $M/settings.properties
fi

adb shell rm -rf $TMP
adb push $M $TMP
# [?] run-as читает /data/local/tmp на большинстве прошивок; если Samsung запретит — грузите с сервера.
adb shell run-as $PKG sh -c "'rm -rf files/models && mkdir -p files/models && cp -r $TMP/. files/models/'"
adb shell rm -rf $TMP
adb shell run-as $PKG du -sh files/models
echo "Готово. Запустите П15 в пробнике."
