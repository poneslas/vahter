#!/usr/bin/env bash
# Сборка и установка пробника, выдача роли помощника.
#   probe/scripts/install.sh [--run]
#   --run — после установки открыть пробник.
# Перед этим на телефоне: Auto Blocker выключен, «Отладка по USB» включена.
set -euo pipefail
cd "$(dirname "$0")/.."
PKG=ru.poneslas.vahter.probe

./gradlew :app:assembleDebug -q
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
# -g выдаёт всё; локальную сеть забираем — П13 меряет и без неё.
adb shell pm revoke "$PKG" android.permission.ACCESS_LOCAL_NETWORK 2>/dev/null || true
adb shell cmd role add-role-holder android.app.role.ASSISTANT "$PKG"
# Тестовый сервер (scripts/serve.py) виден приложению как 127.0.0.1:8765 — без разрешения на локальную сеть.
adb reverse tcp:8765 tcp:8765 >/dev/null
echo "держатель роли: $(adb shell cmd role get-role-holders android.app.role.ASSISTANT | tr -d '\r')"
if [[ "${1:-}" == "--run" ]]; then
  adb shell am start -n "$PKG/.MainActivity" >/dev/null
fi
