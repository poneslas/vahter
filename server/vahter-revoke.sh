#!/bin/bash
# Отозвать ключ телефона по имени: vahter-revoke.sh "S24 Ultra". Без имени — список.
set -euo pipefail
DIR=/opt/edge/edge-web/caddy
F="$DIR/vahter-devices.txt"
if [ $# -eq 0 ]; then echo "Телефоны:"; cut -f1,3 "$F" 2>/dev/null | sed 's/^/  /'; exit 0; fi
grep -qF "$1	" "$F" || { echo "Нет телефона «$1»" >&2; exit 1; }
grep -vF "$1	" "$F" > "$F.new" || true; mv "$F.new" "$F"; chmod 600 "$F"
"$(dirname "$0")/vahter-devices-render.sh"
docker exec edge-web caddy reload --config /etc/caddy/Caddyfile >/dev/null 2>&1 || { echo "Caddy не принял конфиг — ключ не активен. Проверьте: docker exec edge-web caddy validate --config /etc/caddy/Caddyfile" >&2; exit 1; }
echo "Телефон «$1» отозван: его ключ больше не принимается."
