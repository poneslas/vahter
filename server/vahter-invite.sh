#!/bin/bash
# Приглашение для телефона (Архитектура 9.8): новый ключ телефона → /opt/edge/edge-web/caddy/vahter-devices.caddy → перезагрузка
# Caddy → ссылка и QR. Запускать на сервере от root:  vahter-invite.sh "S24 Ultra"
# Ключи телефонов лежат только в vahter-devices.txt (0600); ключ API Hermes сюда не попадает.
set -euo pipefail
NAME=${1:?имя телефона, например: vahter-invite.sh "S24 Ultra"}
DIR=/opt/edge/edge-web/caddy
HOST=$(grep -s ^VAHTER_HOST= /opt/edge/edge-web/vahter.env | cut -d= -f2-)
[ -n "$HOST" ] || { echo "Нет VAHTER_HOST в /opt/edge/edge-web/vahter.env — сначала install.sh" >&2; exit 1; }
TOKEN=$(head -c 32 /dev/urandom | base64 | tr -d '/+=' | head -c 43)
umask 077
printf '%s\t%s\t%s\n' "$NAME" "$TOKEN" "$(date -u +%FT%TZ)" >> "$DIR/vahter-devices.txt"
"$(dirname "$0")/vahter-devices-render.sh"
docker exec edge-web caddy reload --config /etc/caddy/Caddyfile >/dev/null 2>&1 || { echo "Caddy не принял конфиг — ключ не активен. Проверьте: docker exec edge-web caddy validate --config /etc/caddy/Caddyfile" >&2; exit 1; }
LINK="https://$HOST/vahter/connect#$TOKEN"
echo
echo "Телефон «$NAME» добавлен. Ссылка (одна на устройство, не публикуйте):"
echo "  $LINK"
echo
command -v qrencode >/dev/null && qrencode -t ANSIUTF8 "$LINK" || echo "(qrencode не установлен — QR не показан: apt install qrencode)"
echo "Отозвать: vahter-revoke.sh \"$NAME\""
