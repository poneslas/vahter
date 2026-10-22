#!/bin/bash
# Один раз на сервере Hermes (от root): сайт Вахтёра в Caddy за SNI-роутером и навык vahter (Архитектура 9.0, 9.8).
#   1. DNS: A-запись <имя> → адрес сервера (у регистратора; здесь не делается).
#   2. scp -r server root@сервер:/root/vahter-server && ssh root@сервер 'bash /root/vahter-server/host/install.sh vahter.example.ru'
# Пути и имена контейнеров этого сервера — в site.env рядом (не в git; образец — site.env.example).
# Что меняет: $SNI_CONF (одна строка в map), $WEB/{compose.yml,vahter.env,caddy/},
# /root/.hermes/vahter (ключи телефонов, владелец hermes), /root/.hermes/vahter-out (файлы от Hermes для телефона),
# /root/.hermes/skills/vahter (навык), юниты systemd vahter-caddy-reload.{path,service}. Hermes, его конфиг и ufw не трогает.
# Повторный запуск безопасен. Правило: файлы, примонтированные в контейнеры, писать на место (cat >), не sed -i.
set -euo pipefail
HOST=${1:?имя сайта, например vahter.example.ru}
SRC=$(cd "$(dirname "$0")" && pwd)          # …/server/host
SKILL=$(cd "$SRC/../skill/vahter" && pwd)
[ -f "$SRC/site.env" ] && . "$SRC/site.env"
WEB=${WEB:?путь к папке Caddy — в site.env}
SNI_CONF=${SNI_CONF:?конфиг SNI-роутера (nginx stream) — в site.env}
WEB_CONTAINER=${WEB_CONTAINER:?имя контейнера Caddy — в site.env}
SNI_CONTAINER=${SNI_CONTAINER:?имя контейнера SNI-роутера — в site.env}
PROXY_HOST=${PROXY_HOST:?прежний сайт сервера (блок в Caddyfile) — в site.env}
HH=/root/.hermes
HU=10000                                      # пользователь hermes в контейнере
KEY=$(grep ^API_SERVER_KEY= $HH/.env | cut -d= -f2-)
[ -n "$KEY" ] || { echo "В $HH/.env нет API_SERVER_KEY" >&2; exit 1; }
getent hosts "$HOST" >/dev/null || echo "Внимание: $HOST пока не резолвится — сертификат не выдадут, пока нет DNS-записи."

# Ключ API и имя — только для Caddy, файл 0600
umask 077
printf 'API_SERVER_KEY=%s\nVAHTER_HOST=%s\nPROXY_HOST=%s\n' "$KEY" "$HOST" "$PROXY_HOST" > "$WEB/vahter.env"
umask 022
mkdir -p "$WEB/caddy" "$WEB/data" "$HH/vahter" "$HH/vahter-out" "$HH/skills"
cp "$SRC/Caddyfile" "$WEB/caddy/Caddyfile"
cp "$SRC/connect.html" "$WEB/caddy/connect.html"
rm -f "$WEB/caddy/vahter-devices.caddy"

# Ключи телефонов — в доме Hermes, чтобы навык мог их вести; старый список (если был) переносится
printf '%s\n' "$HOST" > "$HH/vahter/host"
if [ -s "$WEB/caddy/vahter-devices.txt" ] && [ ! -s "$HH/vahter/devices.txt" ]; then
  cat "$WEB/caddy/vahter-devices.txt" > "$HH/vahter/devices.txt" && rm -f "$WEB/caddy/vahter-devices.txt"
fi
touch "$HH/vahter/devices.txt"
HERMES_HOME=$HH bash "$SKILL/scripts/vahter-devices.sh" render
chmod 700 "$HH/vahter"; chmod 600 "$HH/vahter/devices.txt" "$HH/vahter/devices.caddy"; chmod 644 "$HH/vahter/host"
chown -R $HU:$HU "$HH/vahter" "$HH/vahter-out"

# Навык: копия в доме Hermes (из GitHub — hermes skills install <репозиторий>/server/skill/vahter, когда опубликуем)
rm -rf "$HH/skills/vahter"; cp -R "$SKILL" "$HH/skills/vahter"; chown -R $HU:$HU "$HH/skills/vahter"
rm -f /usr/local/bin/vahter-invite.sh /usr/local/bin/vahter-revoke.sh /usr/local/bin/vahter-devices-render.sh

# Перезагрузка Caddy при смене ключей — systemd path-юнит (Hermes из контейнера Caddy перезагрузить не может)
cp "$SRC/vahter-caddy-reload.path" /etc/systemd/system/
sed "s|@WEB_CONTAINER@|$WEB_CONTAINER|" "$SRC/vahter-caddy-reload.service" > /etc/systemd/system/vahter-caddy-reload.service
systemctl daemon-reload && systemctl enable --now vahter-caddy-reload.path >/dev/null

# Caddy: папка конфигов, постоянные данные (сертификаты), ключ через env, ключи телефонов и файлы Hermes только для чтения
[ -f "$WEB/compose.yml.bak" ] || cp "$WEB/compose.yml" "$WEB/compose.yml.bak"
cat > "$WEB/compose.yml" <<YML
services:
  $WEB_CONTAINER:
    image: caddy:2
    container_name: $WEB_CONTAINER
    restart: always
    network_mode: host
    env_file:
      - $WEB/vahter.env
    volumes:
      - $WEB/caddy:/etc/caddy:ro
      - $HH/vahter:/etc/vahter:ro
      - $WEB/data:/data
      - $HH/vahter-out:/srv/vahter/files:ro
      - /etc/letsencrypt:/etc/letsencrypt:ro
YML

# SNI-роутер: имя Вахтёра → Caddy; остальное как было
NG=$SNI_CONF
if ! grep -q "$HOST" "$NG"; then
  cp "$NG" "$NG.bak"
  sed "s|^\(\s*default\s\+127.0.0.1:8444;.*\)$|        $HOST   127.0.0.1:9443;   # Vahter -> Caddy\n\1|" "$NG.bak" > "$NG.new"
  cat "$NG.new" > "$NG" && rm "$NG.new"
  docker exec "$SNI_CONTAINER" nginx -t && docker exec "$SNI_CONTAINER" nginx -s reload
  docker exec "$SNI_CONTAINER" sh -c "nginx -T 2>/dev/null | grep -q $HOST" || docker restart "$SNI_CONTAINER"
fi

(cd "$WEB" && docker compose up -d --force-recreate) 2>&1 | grep -v "^$" | tail -2
sleep 3
docker exec "$WEB_CONTAINER" caddy validate --config /etc/caddy/Caddyfile >/dev/null 2>&1 && echo "Caddy: конфиг принят."
echo "Готово. Приглашение — через Hermes («сделай приглашение для Вахтёра для <имя>») или с хоста:"
echo "  HERMES_HOME=$HH bash $HH/skills/vahter/scripts/vahter-devices.sh invite \"S24 Ultra\""
echo "Проверка: curl -sI https://$HOST/vahter/connect | head -1"
