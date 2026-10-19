#!/usr/bin/env python3
"""
Перенос русских строк из Kotlin в res/values/strings.xml (Бэклог №15).
Строковый литерал с кириллицей → tr(R.string.ключ[, аргументы]); шаблоны $x и ${…} → %1$s.
Пропускает комментарии, сырые строки \"\"\"…\"\"\" (описания функций для мозга) и файлы из EXCLUDE.
Запуск: python3 -I tools/i18n/extract.py app/src/main [--dry]
"""
import io, os, re, sys

ROOT = sys.argv[1]
DRY = '--dry' in sys.argv
JAVA = os.path.join(ROOT, 'java')
STRINGS = os.path.join(ROOT, 'res', 'values', 'strings.xml')
EXCLUDE = ('/pisar/', 'actions/PhoneActions.kt')   # Писарь — уже на ресурсах; слова локальных команд — данные языка
CYR = re.compile('[А-Яа-яЁё]')
TR = dict(zip('абвгдеёжзийклмнопрстуфхцчшщъыьэюя', ['a','b','v','g','d','e','e','zh','z','i','y','k','l','m','n','o','p','r','s','t','u','f','kh','ts','ch','sh','shch','','y','','e','yu','ya']))

def translit(s):
    out = ''.join(TR.get(c, c) for c in s.lower())
    out = re.sub('[^a-z0-9]+', '_', out).strip('_')
    return '_'.join(out.split('_')[:5])[:40].strip('_') or 'text'

existing = io.open(STRINGS, encoding='utf-8').read()
used_keys = set(re.findall(r'name="([^"]+)"', existing))
by_text = {}          # (текст, есть ли аргументы) → ключ
new_entries = []      # (ключ, xml-значение, formatted_false)

def xml_value(text, has_args):
    v = text.replace('\\', '\\\\').replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    v = v.replace("'", "\\'").replace('"', '\\"').replace('\n', '\\n').replace('\t', '\\t')
    if v.startswith('@') or v.startswith('?'): v = '\\' + v
    # пробелы по краям aapt обрезает — сохраняем явно
    lead = len(v) - len(v.lstrip(' ')); trail = len(v) - len(v.rstrip(' '))
    if lead or trail: v = '\\u0020' * lead + v.strip(' ') + '\\u0020' * trail
    return v

def key_for(text, has_args, prefix):
    k = (text, has_args)
    if k in by_text: return by_text[k]
    base = prefix + '_' + translit(text)
    key = base; i = 2
    while key in used_keys: key = f'{base}_{i}'; i += 1
    used_keys.add(key); by_text[k] = key
    if has_args:
        # % в тексте — экранировать, %N$s — подстановки
        parts = re.split(r'(%\d+\$s)', text)
        val = ''.join(p if re.fullmatch(r'%\d+\$s', p) else xml_value(p, True).replace('%', '%%') for p in parts)
        new_entries.append((key, val, False))
    else:
        val = xml_value(text, False)
        new_entries.append((key, val, '%' in text))
    return key

KESC = {'n': '\n', 't': '\t', 'r': '\r', '"': '"', "'": "'", '\\': '\\', '$': '$', 'b': '\b'}

def parse_literal(src, i):
    """src[i] == '"'. Вернуть (конец, части), части: ('t', текст) | ('e', выражение)."""
    assert src[i] == '"'
    j = i + 1; parts = []; buf = []
    while True:
        c = src[j]
        if c == '\\':
            n = src[j + 1]
            if n == 'u': buf.append(chr(int(src[j + 2:j + 6], 16))); j += 6; continue
            buf.append(KESC.get(n, n)); j += 2; continue
        if c == '"':
            if buf: parts.append(('t', ''.join(buf)))
            return j + 1, parts
        if c == '$' and j + 1 < len(src) and (src[j + 1] == '{' or src[j + 1] == '_' or src[j + 1].isalpha()):
            if buf: parts.append(('t', ''.join(buf))); buf = []
            if src[j + 1] == '{':
                k = j + 2; depth = 1
                while depth:
                    ch = src[k]
                    if ch == '"':
                        k, _ = parse_literal(src, k); continue
                    if ch == '{': depth += 1
                    elif ch == '}': depth -= 1
                    k += 1
                parts.append(('e', src[j + 2:k - 1])); j = k; continue
            k = j + 1
            while k < len(src) and (src[k].isalnum() or src[k] == '_'): k += 1
            parts.append(('e', src[j + 1:k])); j = k; continue
        buf.append(c); j += 1

def transform(src, prefix):
    out = []; i = 0; n = len(src); changed = 0
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            e = src.find('\n', i); e = n if e < 0 else e
            out.append(src[i:e]); i = e; continue
        if src.startswith('/*', i):
            e = src.find('*/', i) + 2; out.append(src[i:e]); i = e; continue
        if src.startswith('"""', i):
            e = src.find('"""', i + 3) + 3
            while e < n and src[e] == '"': e += 1
            out.append(src[i:e]); i = e; continue
        if c == "'":
            e = i + 1
            while src[e] != "'": e += 2 if src[e] == '\\' else 1
            out.append(src[i:e + 1]); i = e + 1; continue
        if c == '"':
            e, parts = parse_literal(src, i)
            lit = src[i:e]
            text_only = ''.join(p[1] for p in parts if p[0] == 't')
            line_start = src.rfind('\n', 0, i) + 1
            line = src[line_start:src.find('\n', i) if src.find('\n', i) >= 0 else n]
            if CYR.search(text_only) and 'const val' not in line and not line.lstrip().startswith('@'):
                fmt = []; args = []
                for kind, val in parts:
                    if kind == 't': fmt.append(val)
                    else:
                        args.append(transform(val, prefix)[0]); fmt.append(f'%{len(args)}$s')
                key = key_for(''.join(fmt), bool(args), prefix)
                out.append(f'tr(R.string.{key}' + ''.join(', ' + a for a in args) + ')')
                changed += 1
            else:
                # внутри выражений шаблона тоже могут быть русские строки
                out.append(lit)
            i = e; continue
        out.append(c); i += 1
    return ''.join(out), changed

PREFIX = {'MainActivity': 'set', 'Pages': 'pg', 'Settings': 'kit', 'PhoneTools': 'act', 'Phone': 'ph', 'Registry': 'reg',
          'ApiBrain': 'brain', 'Brain': 'brain', 'Conversation': 'conv', 'Composer': 'cmp', 'ConversationUi': 'feed',
          'SheetScreen': 'sheet', 'Notifier': 'ntf', 'SideKey': 'side', 'App': 'app', 'PickerActivity': 'pick',
          'Model': 'mode', 'CameraScreen': 'cam', 'Launch': 'act', 'Assistant': 'sheet', 'Voice': 'voice', 'Attachments': 'cam'}

total = 0
for dp, _, fs in os.walk(JAVA):
    for f in fs:
        if not f.endswith('.kt'): continue
        p = os.path.join(dp, f)
        if any(x in p for x in EXCLUDE): continue
        src = io.open(p, encoding='utf-8').read()
        res, ch = transform(src, PREFIX.get(f[:-3], 'ui'))
        if not ch: continue
        total += ch
        pkg = re.search(r'^package ([\w.]+)', res, re.M).group(1)
        imports = []
        if pkg != 'ru.poneslas.tasya':
            if 'import ru.poneslas.tasya.R\n' not in res: imports.append('import ru.poneslas.tasya.R')
            if 'import ru.poneslas.tasya.tr\n' not in res: imports.append('import ru.poneslas.tasya.tr')
        if imports:
            m = re.search(r'^import .*$', res, re.M)
            pos = m.start() if m else re.search(r'^package .*$', res, re.M).end() + 1
            res = res[:pos] + '\n'.join(imports) + '\n' + res[pos:]
        print(f'{ch:4d}  {os.path.relpath(p, JAVA)}')
        if not DRY: io.open(p, 'w', encoding='utf-8').write(res)

FF = ' formatted="false"'
xml = ''.join('    <string name="%s"%s>%s</string>\n' % (k, FF if ff else '', v) for k, v, ff in new_entries)
print(f'строк заменено: {total}, ключей: {len(new_entries)}')
if not DRY and new_entries:
    io.open(STRINGS, 'w', encoding='utf-8').write(existing.replace('</resources>', '    <!-- Перенесено из кода (tools/i18n/extract.py) -->\n' + xml + '</resources>'))
