#!/usr/bin/env bash
# Какой экран сейчас наверху телефона — чтобы найти экраны Samsung, у которых нет публичного интента (П18, П19).
#   probe/scripts/top-activity.sh              # просто напечатать pkg/.Class
#   probe/scripts/top-activity.sh side_key     # и записать в пробник: files/components.properties
# Ключи, которые читает П19: side_key (боковая кнопка), battery (батарея пробника), auto_blocker.
set -euo pipefail
PKG=ru.poneslas.vahter.probe

line="$(adb shell dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity' | head -1 | tr -d '\r')"
comp="$(printf '%s\n' "$line" | grep -oE '[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+' | head -1 || true)"
if [[ -z "$comp" ]]; then
  echo "верхняя активность не найдена: $line" >&2
  exit 1
fi
echo "$comp"

if [[ -n "${1:-}" ]]; then
  adb shell "run-as $PKG sh -c 'echo $1=$comp >> files/components.properties'"
  echo "записано в пробник: $1=$comp"
fi
