#!/bin/bash
# Сверка с прототипом: одинаковые экраны из docs/design/Прототип.html и из приложения на эмуляторе — парами.
# Результат: out/compare/index.html. Нужны: запущенный эмулятор с установленной отладочной сборкой.
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$ROOT/out/compare; mkdir -p "$OUT/img"
export PATH=$PATH:/Users/em/Dev/.toolchains/android-sdk/platform-tools
PKG=ru.poneslas.vahter.dev
# Ширина как у телефона и прототипа: 1344 px / 3.5 = 384 dp (по умолчанию у эмулятора 448 dp — всё мельче)
adb shell wm density 560; trap 'adb shell wm density reset' EXIT; sleep 2
# копия прототипа с крючками: ?page=…&edit=…&brain=…, ?sheet=1, ?onb=N
rm -rf "$OUT/proto"; mkdir -p "$OUT/proto"; cp -R "$ROOT/docs/design/prototype" "$OUT/proto/"
python3 -I - "$ROOT/docs/design/Прототип.html" "$OUT/proto/index.html" <<'PY'
import sys,io
s=io.open(sys.argv[1],encoding='utf-8').read()
hook='''<script>
const Q=new URLSearchParams(location.search);
if(Q.get('page')) setTimeout(()=>{ S.screen='app'; S.onboarded=true; S.ov=null; S.page=Q.get('page'); if(Q.get('edit')) S.editMode=Q.get('edit'); if(Q.get('brain')) S.editBrain=Q.get('brain'); render() },300);
if(Q.get('sheet')) setTimeout(()=>openOverlay('key'),300);
if(Q.get('onb')) setTimeout(()=>{ S.screen='app'; S.onboarded=false; S.onbStep=+Q.get('onb'); render() },300);
</script>
</body>'''
io.open(sys.argv[2],'w',encoding='utf-8').write(s.replace('</body>',hook))
PY
specs=(); rows=()
while IFS="|" read -r name q app title <&3; do
  name=$(echo $name); [[ -z "$name" || "$name" == \#* ]] && continue
  q=$(echo $q); app=$(echo $app); title=$(echo $title)
  specs+=("$name=$q"); rows+=("$name|$title")
  # приложение
  adb shell input keyevent KEYCODE_HOME; sleep 0.5
  if [[ "$app" == sheet ]]; then adb shell input keyevent KEYCODE_ASSIST; sleep 2.5
  else adb shell am start -S -n $PKG/ru.poneslas.vahter.app.MainActivity --es page "$app" >/dev/null; sleep 2; fi
  adb exec-out screencap -p > "$OUT/img/$name.app.png"
  [[ "$app" == sheet ]] && adb shell input keyevent KEYCODE_BACK
done 3< "$(dirname "$0")/screens.txt"
PLAYWRIGHT_BROWSERS_PATH=/Users/em/Dev/.toolchains/playwright node "$(dirname "$0")/proto.mjs" "$OUT/proto/index.html" "$OUT/img" "${specs[@]}"
{
  echo '<!doctype html><meta charset="utf-8"><title>Сверка с прототипом</title><style>body{font:15px system-ui;margin:24px;background:#eee}h2{font-size:16px;margin:28px 0 8px}.p{display:flex;gap:24px;align-items:flex-start}.p figure{margin:0}.p img{width:380px;border-radius:24px;box-shadow:0 4px 16px rgba(0,0,0,.2)}figcaption{font-size:12px;color:#666;margin-top:4px}</style>'
  echo "<h1>Сверка с прототипом</h1><p>Слева — прототип, справа — приложение на эмуляторе. Снято: $(date '+%d.%m %H:%M').</p>"
  for r in "${rows[@]}"; do n=${r%%|*}; t=${r#*|}
    echo "<h2 id=\"$n\">$t <small style=\"color:#888\">· $n</small></h2><div class=\"p\"><figure><img src=\"img/$n.proto.png\"><figcaption>прототип</figcaption></figure><figure><img src=\"img/$n.app.png\"><figcaption>приложение</figcaption></figure></div>"
  done
} > "$OUT/index.html"
echo "$OUT/index.html"
