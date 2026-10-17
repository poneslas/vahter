#!/usr/bin/env python3
"""Тестовый сервер пробника на Mac (П9, П14).

  python3 probe/scripts/serve.py [--port 8765] [--rate-mbit 2]

  PUT|POST /upload?id=<имя>  — принимает тело, режет скорость до --rate-mbit (0 — без ограничения),
                               считает байты; ответ JSON с итогом.
  GET /status                — JSON: все загрузки (id, байты, ожидалось, готово, длительность).
  GET /apk/next.apk          — probe/serve/next.apk (кладёт scripts/bump.sh N --serve).
  GET /apk/next.sha256       — sha256 этого файла, hex.

Из эмулятора Mac виден как 10.0.2.2, с телефона — по адресу Mac в Wi-Fi
(впишите его в «Отчёт и настройки → Тестовый сервер»).
"""
import argparse
import hashlib
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

SERVE_DIR = Path(__file__).resolve().parent.parent / "serve"
uploads = {}
lock = threading.Lock()
args = None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):
        print(time.strftime("%H:%M:%S"), self.address_string(), fmt % a, flush=True)

    def send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False, indent=1).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/status":
            with lock:
                return self.send_json(200, list(uploads.values()))
        if path in ("/apk/next.apk", "/apk/next.sha256"):
            apk = SERVE_DIR / "next.apk"
            if not apk.exists():
                return self.send_json(404, {"error": "нет probe/serve/next.apk — scripts/bump.sh N --serve"})
            data = apk.read_bytes()
            if path.endswith(".sha256"):
                data = hashlib.sha256(data).hexdigest().encode()
                ctype = "text/plain"
            else:
                ctype = "application/vnd.android.package-archive"
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        self.send_json(404, {"error": "нет такого пути"})

    def do_PUT(self):
        self.receive()

    def do_POST(self):
        self.receive()

    def receive(self):
        u = urlparse(self.path)
        if u.path != "/upload":
            return self.send_json(404, {"error": "нет такого пути"})
        uid = parse_qs(u.query).get("id", [f"u{int(time.time() * 1000)}"])[0]
        expected = int(self.headers.get("Content-Length", "0"))
        rec = {"id": uid, "bytes": 0, "expected": expected, "done": False, "started": time.time(), "seconds": 0.0}
        with lock:
            uploads[uid] = rec
        rate = args.rate_mbit * 1_000_000 / 8  # байт/с
        chunk = 16 * 1024
        t0 = time.time()
        left = expected
        try:
            while left > 0:
                data = self.rfile.read(min(chunk, left))
                if not data:
                    break
                left -= len(data)
                with lock:
                    rec["bytes"] += len(data)
                    rec["seconds"] = round(time.time() - t0, 2)
                if rate > 0:
                    ahead = rec["bytes"] / rate - (time.time() - t0)
                    if ahead > 0:
                        time.sleep(ahead)
        except (ConnectionError, TimeoutError) as e:
            with lock:
                rec["error"] = repr(e)
            print("обрыв", uid, rec["bytes"], flush=True)
            return
        with lock:
            rec["done"] = rec["bytes"] == expected
            rec["seconds"] = round(time.time() - t0, 2)
        print("загрузка", uid, rec["bytes"], "из", expected, "за", rec["seconds"], "с", flush=True)
        self.send_json(200, rec)


def main():
    global args
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--rate-mbit", type=float, default=2.0, help="ограничение скорости приёма, Мбит/с; 0 — без")
    args = p.parse_args()
    SERVE_DIR.mkdir(exist_ok=True)
    print(f"serve.py: :{args.port}, приём {args.rate_mbit} Мбит/с, APK из {SERVE_DIR}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
