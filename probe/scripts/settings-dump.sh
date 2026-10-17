#!/usr/bin/env bash
# П18: что видит shell в системных настройках — для сверки с тем, что видит приложение.
#   scripts/settings-dump.sh before   # снять
#   (поменять настройку на телефоне)
#   scripts/settings-dump.sh after    # снять и показать разницу с before
set -euo pipefail
name="${1:-snap}"
dir="$(cd "$(dirname "$0")/.." && pwd)/reports/settings"
mkdir -p "$dir"
for t in system secure global; do
  adb shell settings list "$t" | sed "s/^/$t\//" 
done | sort > "$dir/$name.txt"
echo "снято: $dir/$name.txt ($(wc -l < "$dir/$name.txt") ключей)"
if [ "$name" = "after" ] && [ -f "$dir/before.txt" ]; then
  diff "$dir/before.txt" "$dir/after.txt" || true
fi
