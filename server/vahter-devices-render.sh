#!/bin/bash
# Из vahter-devices.txt (имя \t ключ \t дата) собрать матчер Caddy @device: по строке header на ключ — любой из них подходит.
# Пустой список — ничего не пускать.
set -euo pipefail
DIR=/opt/edge/edge-web/caddy
F="$DIR/vahter-devices.txt"; OUT="$DIR/vahter-devices.caddy"
umask 077
if [ -s "$F" ]; then
  { printf '@device {\n'; cut -f2 "$F" | while read -r t; do printf '\theader Authorization "Bearer %s"\n' "$t"; done; printf '}\n'; } > "$OUT"
else
  printf '@device expression false\n' > "$OUT"
fi
