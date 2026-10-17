#!/usr/bin/env bash
# Установка пробника БЕЗ выдачи разрешений — для П20 (разрешения из листа).
#   probe/scripts/install-nogrant.sh [--run]
# Как install.sh, но без -g; уже выданные разрешения забираем (pm revoke перезапускает процесс).
set -euo pipefail
cd "$(dirname "$0")/.."
PKG=ru.poneslas.tasya.probe

./gradlew :app:assembleDebug -q
adb install -r app/build/outputs/apk/debug/app-debug.apk
for p in RECORD_AUDIO CAMERA POST_NOTIFICATIONS READ_CALENDAR WRITE_CALENDAR \
         ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION READ_CONTACTS READ_CALL_LOG ACCESS_LOCAL_NETWORK; do
  adb shell pm revoke "$PKG" "android.permission.$p" 2>/dev/null || true
  # Сбросить «больше не спрашивать», чтобы окно разрешения снова показывалось.
  adb shell pm clear-permission-flags "$PKG" "android.permission.$p" user-set user-fixed 2>/dev/null || true
done
adb shell cmd role add-role-holder android.app.role.ASSISTANT "$PKG"
echo "держатель роли: $(adb shell cmd role get-role-holders android.app.role.ASSISTANT | tr -d '\r')"
if [[ "${1:-}" == "--run" ]]; then
  adb shell am start -n "$PKG/.MainActivity" >/dev/null
fi
