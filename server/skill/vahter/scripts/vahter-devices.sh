#!/bin/bash
# Ключи телефонов Вахтёра: invite <имя> | list | revoke <имя>. Пишет только в $HERMES_HOME/vahter/:
# devices.txt (имя \t ключ \t дата, 0600) и devices.caddy (матчер @device для Caddy). Перезагрузку Caddy делает
# хост (systemd path-юнит на devices.caddy). Работает и из контейнера Hermes (HERMES_HOME=/opt/data), и с хоста.
set -euo pipefail
HOME_DIR=${HERMES_HOME:-/root/.hermes}
DIR="$HOME_DIR/vahter"
F="$DIR/devices.txt"; OUT="$DIR/devices.caddy"
cmd=${1:-}; name=${2:-}
umask 077
mkdir -p "$DIR"; touch "$F"

render() {
  if [ -s "$F" ]; then
    { printf '@device {\n'; cut -f2 "$F" | while read -r t; do printf '\theader Authorization "Bearer %s"\n' "$t"; done; printf '}\n'; } > "$OUT.new"
  else
    printf '@device expression false\n' > "$OUT.new"
  fi
  cat "$OUT.new" > "$OUT"; rm -f "$OUT.new"   # на место: файл примонтирован в Caddy
}

case "$cmd" in
  invite)
    [ -n "$name" ] || { echo "Нужно имя телефона: invite \"S24 Ultra\"" >&2; exit 1; }
    HOST=$(cat "$DIR/host" 2>/dev/null || true)
    [ -n "$HOST" ] || { echo "Нет $DIR/host — сначала install.sh на хосте" >&2; exit 1; }
    TOKEN=$(head -c 32 /dev/urandom | base64 | tr -d '/+=' | head -c 43)
    grep -vF "$name	" "$F" > "$F.new" || true
    printf '%s\t%s\t%s\n' "$name" "$TOKEN" "$(date -u +%FT%TZ)" >> "$F.new"
    cat "$F.new" > "$F"; rm -f "$F.new"
    render
    echo "Телефон «$name» добавлен. Ссылка-приглашение (это ключ телефона, не публикуйте):"
    echo "https://$HOST/vahter/connect#$TOKEN"
    ;;
  list)
    if [ -s "$F" ]; then echo "Телефоны:"; cut -f1,3 "$F" | sed 's/\t/ · /; s/^/  /'; else echo "Телефонов нет."; fi
    ;;
  revoke)
    [ -n "$name" ] || { echo "Нужно имя телефона: revoke \"S24 Ultra\"" >&2; exit 1; }
    grep -qF "$name	" "$F" || { echo "Нет телефона «$name»" >&2; exit 1; }
    grep -vF "$name	" "$F" > "$F.new" || true; cat "$F.new" > "$F"; rm -f "$F.new"
    render
    echo "Телефон «$name» отозван: его ключ больше не принимается."
    ;;
  render) render ;;
  *) echo "invite <имя> | list | revoke <имя>" >&2; exit 1 ;;
esac
