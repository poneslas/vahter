#!/usr/bin/env bash
# Забрать отчёт пробника с телефона или эмулятора.
#   probe/scripts/pull-report.sh [папка]   (по умолчанию probe/reports/<дата-время>/)
set -euo pipefail
cd "$(dirname "$0")/.."
PKG=ru.poneslas.vahter.probe
OUT="${1:-reports/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
for f in report.json report.txt opens.jsonl replaced.jsonl; do
  if ! adb exec-out run-as "$PKG" cat "files/$f" > "$OUT/$f" 2>/dev/null || [[ ! -s "$OUT/$f" ]]; then
    rm -f "$OUT/$f"
  fi
done
ls -la "$OUT"
