#!/bin/bash
# Один раз на сервере Hermes (от root): сайт Вахтёра в Caddy за SNI-роутером (Архитектура 9.8).
#   1. DNS: A-запись <имя> → адрес сервера (у регистратора; здесь не делается).
#   2. scp -r server root@сервер:/root/vahter-server && ssh root@сервер 'bash /root/vahter-server/install.sh vahter.example.ru'
# Что меняет: /opt/edge/edge-router/nginx.conf (одна строка в map), /opt/edge/edge-web/{Caddyfile,compose.yml,vahter.env,caddy/},
# /usr/local/bin/vahter-*.sh, папка /root/.hermes/vahter-out (файлы от Hermes для телефона). Hermes, его конфиг и ufw не трогает.
# Повторный запуск безопасен.
set -euo pipefail
HOST=${1:?имя сайта, например vahter.example.ru}
SRC=$(cd "$(dirname "$0")" && pwd)
WEB=/opt/edge/edge-web
KEY=$(grep ^API_SERVER_KEY= /root/.hermes/.env | cut -d= -f2-)
[ -n "$KEY" ] || { echo "В /root/.hermes/.env нет API_SERVER_KEY" >&2; exit 1; }
getent hosts "$HOST" >/dev/null || echo "Внимание: $HOST пока не резолвится — сертификат не выдадут, пока нет DNS-записи."

# Ключ API и имя — только для Caddy, файл 0600
umask 077
printf 'API_SERVER_KEY=%s\nVAHTER_HOST=%s\n' "$KEY" "$HOST" > "$WEB/vahter.env"
mkdir -p "$WEB/caddy" "$WEB/data"
# Исходящая папка Hermes для телефона: владелец — пользователь hermes в контейнере (uid 10000), иначе агент писать не сможет
mkdir -p /root/.hermes/vahter-out && chown 10000:10000 /root/.hermes/vahter-out
touch "$WEB/caddy/vahter-devices.txt"
umask 022
cp "$SRC/Caddyfile" "$WEB/caddy/Caddyfile"
cp "$SRC/connect.html" "$WEB/caddy/connect.html"
install -m 755 "$SRC/vahter-invite.sh" "$SRC/vahter-revoke.sh" "$SRC/vahter-devices-render.sh" /usr/local/bin/
/usr/local/bin/vahter-devices-render.sh

# Caddy: папка конфигов вместо одного файла, постоянные данные (сертификаты), ключ через env
[ -f "$WEB/compose.yml.bak" ] || cp "$WEB/compose.yml" "$WEB/compose.yml.bak"
cat > "$WEB/compose.yml" <<YML
services:
  edge-web:
    image: caddy:2
    container_name: edge-web
    restart: always
    network_mode: host
    env_file:
      - $WEB/vahter.env
    volumes:
      - $WEB/caddy:/etc/caddy:ro
      - $WEB/data:/data
      - /root/.hermes/vahter-out:/srv/vahter/files:ro
      - /etc/letsencrypt:/etc/letsencrypt:ro
YML

# SNI-роутер: имя Вахтёра → Caddy; остальное как было (прокси)
NG=/opt/edge/edge-router/nginx.conf
if ! grep -q "$HOST" "$NG"; then
  cp "$NG" "$NG.bak"
  # Файл примонтирован в контейнер по inode: писать на место (cat >), не через sed -i, иначе контейнер видит старый файл
  sed "s|^\(\s*default\s\+127.0.0.1:8444;.*\)$|        $HOST   127.0.0.1:9443;   # Vahter -> Caddy\n\1|" "$NG.bak" > "$NG.new"
  cat "$NG.new" > "$NG" && rm "$NG.new"
  docker exec edge-router nginx -t && docker exec edge-router nginx -s reload
  docker exec edge-router sh -c "nginx -T 2>/dev/null | grep -q $HOST" || docker restart edge-router
fi

(cd "$WEB" && docker compose up -d --force-recreate)
sleep 3
docker exec edge-web caddy validate --config /etc/caddy/Caddyfile >/dev/null && echo "Caddy: конфиг принят."
echo "Готово. Приглашение: vahter-invite.sh \"S24 Ultra\". Проверка сертификата: curl -sI https://$HOST/vahter/connect | head -1"
