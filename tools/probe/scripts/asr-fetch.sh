#!/usr/bin/env bash
# П15. Качает sherpa-onnx AAR в app/libs (это же делает Gradle при первой сборке).
# С --models ещё модели и тестовые WAV в serve/models (~255 МБ) и пишет serve/models/manifest.json.
#
#   scripts/asr-fetch.sh            # только AAR
#   scripts/asr-fetch.sh --models   # AAR + модели + WAV
#
# Источники: sherpa-onnx v1.13.8 (Apache-2.0), GigaAM v3 e2e_ctc (MIT) и zipformer-small-ru-vosk —
# официальные сборки sherpa-onnx на Hugging Face (csukuangfj), silero_vad — релиз asr-models sherpa-onnx.
set -euo pipefail
cd "$(dirname "$0")/.."

HF=https://huggingface.co/csukuangfj
GIGA=$HF/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16/resolve/main
ZIP=$HF/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/resolve/main

# fetch URL DST [SHA256]: докачка в DST.part (curl -C -), проверка sha256, атомарное переименование.
fetch() {
  local url=$1 dst=$2 sha=${3:-}
  if [[ -f $dst ]]; then
    if [[ -z $sha || $(shasum -a 256 "$dst" | cut -d' ' -f1) == "$sha" ]]; then
      echo "есть: $dst"
      return
    fi
    echo "sha256 не совпал, качаю заново: $dst"
    rm -f "$dst"
  fi
  mkdir -p "$(dirname "$dst")"
  echo "качаю: $url"
  curl -fL# --retry 3 -C - -o "$dst.part" "$url"
  if [[ -n $sha ]]; then
    local got
    got=$(shasum -a 256 "$dst.part" | cut -d' ' -f1)
    if [[ $got != "$sha" ]]; then
      echo "ОШИБКА: sha256 $dst: $got, ждали $sha" >&2
      rm -f "$dst.part"
      exit 1
    fi
  fi
  mv "$dst.part" "$dst"
}

fetch https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar \
  app/libs/sherpa-onnx-1.13.8.aar 633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96

[[ ${1:-} == --models ]] || exit 0

M=serve/models
fetch "$GIGA/model.int8.onnx" $M/gigaam/model.int8.onnx d5fea8df94263c285e54b21e5774b707c707192d3bdbeffd7b1eb07fb6743b35
fetch "$GIGA/tokens.txt" $M/gigaam/tokens.txt
fetch "$GIGA/LICENSE" $M/gigaam/LICENSE
fetch "$ZIP/encoder.int8.onnx" $M/zipformer/encoder.int8.onnx e0db705e94ec35d803b1df4f40cda23d064e1142977c80ab288430b109777a9d
fetch "$ZIP/decoder.onnx" $M/zipformer/decoder.onnx 89b3088a9e20e1ef7f2e85ce1a3478afe6a9c4ac57369cabcc4beb8e95328ea0
fetch "$ZIP/joiner.int8.onnx" $M/zipformer/joiner.int8.onnx b55784b071ab7512eab4c7c44e4f5478284ef33c83562cc6a249b972515a31e5
fetch "$ZIP/tokens.txt" $M/zipformer/tokens.txt
fetch https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx $M/silero_vad.onnx

# Живая речь из репозиториев моделей (эталонного текста нет — WER по ним не считается).
fetch "$GIGA/test_wavs/example.wav" $M/wavs/gigaam-example.wav d8aaaa18a5098d7c6de0595ae7ac1e64cacd0d4022af3595213bdaf23be77e69
fetch "$ZIP/test_wavs/0.wav" $M/wavs/vosk-0.wav f3ac4f6e5b818ec89bdd884f60637daa32ef0ed19a11981b7e02e3e7799dfd79
fetch "$ZIP/test_wavs/1.wav" $M/wavs/vosk-1.wav d6e768803b3bc3afcb08326677f3bb872c9beeed29af40d3579bcc14e74484f8

# Синтетика голосом Milena (macOS say) от ~2 до ~13 с, с эталоном для WER. Это не голос S24 —
# записи с телефона положите в serve/models/wavs/ (16 кГц моно PCM16, рядом .txt с текстом).
synth() {
  local name=$1 text=$2 wav=$M/wavs/$1.wav
  [[ -f $wav ]] && { echo "есть: $wav"; return; }
  local tmp
  tmp=$(mktemp -t vahter-say).aiff
  say -v Milena -o "$tmp" "$text"
  afconvert -f WAVE -d LEI16@16000 -c 1 "$tmp" "$wav"
  rm -f "$tmp"
  printf '%s\n' "$text" > "$M/wavs/$name.txt"
  echo "синтетика: $wav"
}
if say -v '?' | grep -q '^Milena'; then
  synth synth-1 "Поставь таймер на пять минут."
  synth synth-2 "Какая погода будет завтра утром в Москве, нужен ли зонтик?"
  synth synth-3 "Напомни мне в четверг в шесть вечера позвонить маме и купить хлеба, молока и яблок по дороге домой."
  synth synth-4 "Сегодня я хочу приготовить ужин на четверых: суп из тыквы, запечённую курицу с овощами и простой салат. Составь список покупок и подскажи, сколько времени займёт всё вместе, если начать в семь вечера."
else
  echo "Голоса Milena нет — синтетические WAV пропущены (Системные настройки → Универсальный доступ → Устная речь)."
fi

# manifest.json: имя, размер, sha256 всех файлов, кроме служебных.
python3 - "$M" <<'EOF'
import hashlib, json, os, sys
root = sys.argv[1]
files = []
for d, _, names in os.walk(root):
    for n in sorted(names):
        p = os.path.join(d, n)
        rel = os.path.relpath(p, root)
        if rel in ("manifest.json", "settings.properties") or n.endswith(".part"):
            continue
        h = hashlib.sha256()
        with open(p, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        files.append({"name": rel, "size": os.path.getsize(p), "sha256": h.hexdigest()})
files.sort(key=lambda e: e["name"])
with open(os.path.join(root, "manifest.json"), "w") as f:
    json.dump({"files": files}, f, ensure_ascii=False, indent=1)
print(f"manifest.json: {len(files)} файлов, {sum(e['size'] for e in files) / 1e6:.0f} МБ")
EOF
