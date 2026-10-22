#!/usr/bin/env python3
"""Приёмник файлов от телефона для Hermes (Архитектура 9.0, «Файлы для Hermes»).

API Hermes документы не принимает (api_server.py: части file/input_file отклоняются), поэтому телефон кладёт файл сюда,
а в реплике пишет путь — Hermes открывает его своими инструментами. Слушает только 127.0.0.1; снаружи — через Caddy
(`/vahter/upload/*`) с той же проверкой ключа телефона, что и весь остальной API.

PUT /vahter/upload/<имя>  тело — файл  →  200 {"path": "/opt/data/vahter-in/<id>-<имя>"} (путь внутри контейнера Hermes)
Лимит 20 МБ; файлы старше 7 дней удаляются при каждой загрузке. Владелец — пользователь Hermes (10000), чтобы агент мог читать.
"""
import json, os, re, time, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote

ROOT = os.environ.get('VAHTER_IN', '/root/.hermes/vahter-in')      # на хосте
SEEN_AS = os.environ.get('VAHTER_IN_SEEN_AS', '/opt/data/vahter-in')  # то же место внутри контейнера Hermes
LIMIT = 20 * 1024 * 1024
KEEP = 7 * 24 * 3600
UID = int(os.environ.get('HERMES_UID', '10000'))


def clean(name: str) -> str:
    name = os.path.basename(unquote(name)).strip().replace('\x00', '')
    name = re.sub(r'[^\w.\- ()\[\]а-яА-ЯёЁ]', '_', name)[:120]
    return name or 'file'


def sweep():
    now = time.time()
    for f in os.listdir(ROOT):
        p = os.path.join(ROOT, f)
        try:
            if now - os.path.getmtime(p) > KEEP:
                os.remove(p)
        except OSError:
            pass


class H(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_PUT(self):
        if not self.path.startswith('/vahter/upload/'):
            return self._send(404, {'error': 'not_found'})
        size = int(self.headers.get('Content-Length') or 0)
        if size <= 0:
            return self._send(400, {'error': 'empty'})
        if size > LIMIT:
            return self._send(413, {'error': 'too_large', 'limit': LIMIT})
        name = f'{uuid.uuid4().hex[:8]}-{clean(self.path[len("/vahter/upload/"):])}'
        os.makedirs(ROOT, exist_ok=True)
        tmp = os.path.join(ROOT, '.' + name + '.part')
        left = size
        with open(tmp, 'wb') as f:
            while left > 0:
                chunk = self.rfile.read(min(left, 64 * 1024))
                if not chunk:
                    break
                f.write(chunk)
                left -= len(chunk)
        if left:
            os.remove(tmp)
            return self._send(400, {'error': 'short_body'})
        final = os.path.join(ROOT, name)
        os.replace(tmp, final)
        os.chmod(final, 0o644)
        try:
            os.chown(final, UID, UID)
        except OSError:
            pass
        sweep()
        self._send(200, {'path': f'{SEEN_AS}/{name}', 'size': size})

    def log_message(self, fmt, *args):  # без имён файлов в журнале
        pass


if __name__ == '__main__':
    os.makedirs(ROOT, exist_ok=True)
    ThreadingHTTPServer(('127.0.0.1', int(os.environ.get('PORT', '8650'))), H).serve_forever()
