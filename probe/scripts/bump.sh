#!/usr/bin/env bash
# Сборка пробника с versionCode = 1 + N (П1, П14).
#   probe/scripts/bump.sh N [norec] [--serve]
#   norec   — XML помощника без recognitionService (контрольный опыт П1: роль должна слететь)
#   --serve — не ставить через adb, а положить APK в probe/serve/next.apk для П14 (раздаёт scripts/serve.py)
# П1: bump.sh 1; bump.sh 2; bump.sh 3 norec; install.sh
# П14: bump.sh 4 --serve, в пробнике «Запустить» П14; потом bump.sh 5 --serve и ещё раз.
# Роль после установки скрипт НЕ возвращает — именно это и проверяем.
set -euo pipefail
cd "$(dirname "$0")/.."

N="${1:?укажите N: bump.sh N [norec] [--serve]}"
shift
NOREC=""
SERVE=0
for a in "$@"; do
  case "$a" in
    norec) NOREC="-PnoRecognizer" ;;
    --serve) SERVE=1 ;;
    *) echo "неизвестный аргумент: $a" >&2; exit 2 ;;
  esac
done

./gradlew :app:assembleDebug -q "-Pbump=$N" $NOREC
APK=app/build/outputs/apk/debug/app-debug.apk
if [[ $SERVE == 1 ]]; then
  mkdir -p serve
  cp "$APK" serve/next.apk
  echo "serve/next.apk: versionCode $((1 + N)), sha256 $(shasum -a 256 serve/next.apk | cut -d' ' -f1)"
else
  adb install -r "$APK"
  echo "установлено: versionCode $((1 + N)) ${NOREC:+(без recognitionService)}"
  echo "держатель роли: $(adb shell cmd role get-role-holders android.app.role.ASSISTANT | tr -d '\r')"
fi
