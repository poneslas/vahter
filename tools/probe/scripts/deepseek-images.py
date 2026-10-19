#!/usr/bin/env python3
"""Пределы DeepSeek по картинкам в одной реплике (альбом из нескольких кадров).

  python3 probe/scripts/deepseek-images.py [--model deepseek-chat] [--quick]

Ключ: переменная DEEPSEEK_API_KEY или файл ~/.config/tasya/deepseek_key (одна строка).
Тратит токены: ~10–20 запросов. --quick — только 1 и 5 картинок, без проверки размера.

Что меряет:
  1) одна реплика user: текст + 1 / 5 / 10 картинок image_url (base64 JPEG, длинная сторона 1600, качество 80):
     принят ли, код и текст ошибки, время до первого токена, prompt_tokens и сколько на картинку;
  2) тот же альбом в истории второго запроса: prompt_cache_hit_tokens (кэш префикса);
  3) одна картинка растущего размера (≈0,3 → 1 → 3 → 6 МБ): где отказ — maxImageBytes.
Итог — таблица в консоль и probe/reports/deepseek-images.json.
Картинки берутся из обоев macOS и пережимаются sips — своих файлов не нужно.
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

API = "https://api.deepseek.com/chat/completions"
ROOT = Path(__file__).resolve().parent.parent


def api_key():
    k = os.environ.get("DEEPSEEK_API_KEY", "").strip()
    if not k:
        f = Path.home() / ".config/tasya/deepseek_key"
        if f.exists():
            k = f.read_text().strip()
    if not k:
        sys.exit("Нет ключа: DEEPSEEK_API_KEY или ~/.config/tasya/deepseek_key")
    return k


def source_images():
    dirs = [Path("/System/Library/Desktop Pictures"), Path("/Library/Desktop Pictures")]
    out = []
    for d in dirs:
        if d.exists():
            out += sorted(p for p in d.rglob("*") if p.suffix.lower() in (".heic", ".jpg", ".jpeg", ".png"))
    if not out:
        sys.exit("Не нашёл исходных картинок в Desktop Pictures")
    return out


def make_jpeg(src, side, quality, tmp):
    dst = Path(tmp) / f"{src.stem}_{side}_{quality}.jpg"
    subprocess.run(
        ["sips", "-s", "format", "jpeg", "-s", "formatOptions", str(quality), "-Z", str(side), str(src), "--out", str(dst)],
        check=True, capture_output=True,
    )
    return dst.read_bytes()


def part(jpeg):
    return {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(jpeg).decode()}}


def call(key, model, messages):
    """Потоковый запрос: время до первого токена, usage, ответ или ошибка."""
    body = json.dumps({
        "model": model, "messages": messages, "stream": True, "max_tokens": 60,
        "stream_options": {"include_usage": True},
    }).encode()
    req = urllib.request.Request(API, body, {"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    t0 = time.time()
    ttft, text, usage = None, "", None
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            for raw in r:
                line = raw.decode().strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                o = json.loads(data)
                if o.get("usage"):
                    usage = o["usage"]
                for ch in o.get("choices", []):
                    d = ch.get("delta", {}).get("content") or ""
                    if d and ttft is None:
                        ttft = time.time() - t0
                    text += d
        return {"ok": True, "ttft_s": ttft, "total_s": time.time() - t0, "usage": usage, "answer": text[:200],
                "request_mb": round(len(body) / 1e6, 2)}
    except urllib.error.HTTPError as e:
        return {"ok": False, "code": e.code, "error": e.read().decode(errors="replace")[:500],
                "total_s": time.time() - t0, "request_mb": round(len(body) / 1e6, 2)}
    except Exception as e:  # сеть, таймаут
        return {"ok": False, "code": None, "error": f"{type(e).__name__}: {e}", "total_s": time.time() - t0,
                "request_mb": round(len(body) / 1e6, 2)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="deepseek-chat")
    ap.add_argument("--quick", action="store_true")
    a = ap.parse_args()
    key = api_key()
    srcs = source_images()
    report = {"model": a.model, "album": [], "cache": None, "size": []}

    with tempfile.TemporaryDirectory() as tmp:
        frames = [make_jpeg(srcs[i % len(srcs)], 1600, 80, tmp) for i in range(10)]
        print(f"кадры: {len(frames)}, размер {min(map(len, frames)) // 1000}–{max(map(len, frames)) // 1000} КБ")
        ask = "Сколько здесь картинок? Ответь одним числом."

        base_tokens = None
        r0 = call(key, a.model, [{"role": "user", "content": ask}])
        if r0["ok"] and r0["usage"]:
            base_tokens = r0["usage"].get("prompt_tokens")
        for n in ([1, 5] if a.quick else [1, 5, 10]):
            msgs = [{"role": "user", "content": [{"type": "text", "text": ask}] + [part(f) for f in frames[:n]]}]
            r = call(key, a.model, msgs)
            r["images"] = n
            if r["ok"] and r["usage"] and base_tokens is not None:
                r["tokens_per_image"] = round((r["usage"]["prompt_tokens"] - base_tokens) / n)
            report["album"].append(r)
            print(f"{n:>2} картинок:", "принят" if r["ok"] else f"ОТКАЗ {r.get('code')}: {r.get('error', '')[:160]}",
                  f"ttft={r.get('ttft_s') and round(r['ttft_s'], 2)} с, usage={r.get('usage')}, на картинку={r.get('tokens_per_image')}")

        # Кэш префикса: тот же альбом в истории, новый вопрос.
        ok = [r for r in report["album"] if r["ok"]]
        if ok:
            n = ok[-1]["images"]
            album = {"role": "user", "content": [{"type": "text", "text": ask}] + [part(f) for f in frames[:n]]}
            msgs = [album, {"role": "assistant", "content": ok[-1]["answer"] or "?"},
                    {"role": "user", "content": "А какого цвета первая?"}]
            r = call(key, a.model, msgs)
            r["images"] = n
            report["cache"] = r
            print(f"повтор альбома из {n} в истории:", "принят" if r["ok"] else f"ОТКАЗ {r.get('code')}",
                  f"usage={r.get('usage')}")

        # Предел размера одной картинки.
        if not a.quick:
            for side, q in [(1600, 80), (3000, 92), (5000, 95), (8000, 100)]:
                jpeg = make_jpeg(srcs[0], side, q, tmp)
                r = call(key, a.model, [{"role": "user", "content": [{"type": "text", "text": ask}, part(jpeg)]}])
                r["image_kb"] = len(jpeg) // 1000
                report["size"].append(r)
                print(f"одна картинка {r['image_kb']} КБ ({side}px):", "принят" if r["ok"] else f"ОТКАЗ {r.get('code')}: {r.get('error', '')[:160]}")
                if not r["ok"]:
                    break

    accepted = [r["images"] for r in report["album"] if r["ok"]]
    sizes = [r["image_kb"] for r in report["size"] if r["ok"]]
    report["maxImages_tested_ok"] = max(accepted) if accepted else 0
    report["maxImageKB_tested_ok"] = max(sizes) if sizes else None
    out = ROOT / "reports" / "deepseek-images.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=1))
    print(f"\nитог: картинок в реплике принято до {report['maxImages_tested_ok']}, одна картинка до {report['maxImageKB_tested_ok']} КБ; {out}")


if __name__ == "__main__":
    main()
