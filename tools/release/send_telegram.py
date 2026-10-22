#!/usr/bin/env python3
"""Отправка сборки Вахтёра через Telegram-бота сборок («Сельский телеграф») владельцу.

    python3 tools/release/send_telegram.py out/vahter-0.11.0.apk notes.md

notes.md — что поменялось и на что смотреть при тестировании (Markdown-вольно, уходит как текст). Подпись к файлу у Telegram —
до 1024 символов: короткие заметки уходят подписью, длинные — отдельным сообщением перед файлом.
Токен бота и id чата — вне репозитория: /Users/em/Dev/.toolchains/keys/telegram-builds.env (TG_BOT_TOKEN, TG_CHAT_ID).
Лимит файла у ботов — 50 МБ: выпускная сборка (assembleRelease) укладывается, отладочная — нет.
"""
import json, os, subprocess, sys

ENV = '/Users/em/Dev/.toolchains/keys/telegram-builds.env'
LIMIT = 50 * 1024 * 1024


def env():
    out = {}
    for line in open(ENV, encoding='utf-8'):
        if '=' in line:
            k, v = line.strip().split('=', 1)
            out[k] = v
    return out['TG_BOT_TOKEN'], out['TG_CHAT_ID']


def call(token, method, *args):
    r = subprocess.run(['curl', '-s', '-m', '600', f'https://api.telegram.org/bot{token}/{method}', *args], capture_output=True, text=True)
    try:
        d = json.loads(r.stdout)
    except ValueError:
        sys.exit(f'Telegram: не ответил ({r.stderr.strip()[:200]})')
    if not d.get('ok'):
        sys.exit(f"Telegram: {d.get('description')}")
    return d


def main():
    apk, notes_path = sys.argv[1], sys.argv[2]
    size = os.path.getsize(apk)
    if size > LIMIT:
        sys.exit(f'{apk}: {size / 1048576:.1f} МБ — больше лимита ботов 50 МБ (нужна выпускная сборка)')
    notes = open(notes_path, encoding='utf-8').read().strip()
    token, chat = env()
    if len(notes) > 1000:
        for i in range(0, len(notes), 4000):
            call(token, 'sendMessage', '-d', f'chat_id={chat}', '--data-urlencode', f'text={notes[i:i + 4000]}')
        caption = os.path.basename(apk)
    else:
        caption = notes
    call(token, 'sendDocument', '-F', f'chat_id={chat}', '-F', f'document=@{apk}', '-F', f'caption={caption}')
    print(f'Отправлено: {os.path.basename(apk)} ({size / 1048576:.1f} МБ)')


if __name__ == '__main__':
    main()
