#!/usr/bin/env python3
"""Манифест обновления для Вахтёра (app/.../update/Updates.kt), как в Docsy.

Использование:
    python3 tools/release/update_json.py out/vahter-0.10.0.apk 0.10.0 38 "Что нового — по строке" > update.json

Потом (с подтверждения): релиз v0.10.0 в github.com/poneslas/Vahter с файлом vahter-0.10.0.apk и коммит update.json в main.
Приложение принимает ссылку на APK только из Releases этого репозитория и сверяет sha256.
"""
import hashlib, json, os, sys

apk, version, code, notes = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4] if len(sys.argv) > 4 else ''
h = hashlib.sha256(open(apk, 'rb').read()).hexdigest()
name = f'vahter-{version}.apk'
print(json.dumps({
    'version': version,
    'versionCode': code,
    'apk': {'url': f'https://github.com/poneslas/Vahter/releases/download/v{version}/{name}', 'sha256': h, 'size': os.path.getsize(apk)},
    'notes': {'ru': notes},
}, ensure_ascii=False, indent=2))
