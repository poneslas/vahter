#!/usr/bin/env node
// Проверка прототипа без браузера и зависимостей (node ≥ 20): node tools/proto-check.mjs
// Порядок скриптов и стилей — из docs/design/Прототип.html. Ошибки → код выхода 1, предупреждения — только печать.
//  (а) синтаксис каждого файла; (б) все скрипты одной склейкой — повторные let/const/class между файлами (общая область видимости);
//  (в) повторные function/let/const верхнего уровня между файлами, глобальные имена поверх свойств window;
//  (г) CSS: парность скобок, повторные @keyframes; (д) у каждого data-act="x" есть case 'x' (или разбор по префиксу) в actions.js.
import { readFileSync } from 'node:fs';
import { dirname, join, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const DESIGN = join(ROOT, 'docs/design');
const HTML = join(DESIGN, 'Прототип.html');
const errors = [], warns = [];
const err = m => errors.push(m), warn = m => warns.push(m);
const rel = f => relative(ROOT, f);

const html = readFileSync(HTML, 'utf8');
const local = u => !/^[a-z]+:|^\/\//i.test(u);
const scripts = [...html.matchAll(/<script\b[^>]*\bsrc="([^"]+)"/g)].map(m => m[1]).filter(local).map(u => join(DESIGN, u));
const styles = [...html.matchAll(/<link\b[^>]*rel="stylesheet"[^>]*>/g)].map(m => (m[0].match(/href="([^"]+)"/) || [])[1]).filter(u => u && local(u)).map(u => join(DESIGN, u));
const src = Object.fromEntries([...scripts, ...styles].map(f => [f, readFileSync(f, 'utf8')]));
const inline = [...html.matchAll(/<script\b(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1]);

/* (а) синтаксис по файлам */
for (const f of scripts) { try { new vm.Script(src[f], { filename: rel(f) }) } catch (e) { err(`${rel(f)}: ${e.message}`) } }

/* (б) одна склейка в порядке загрузки: повторное объявление let/const/class в разных файлах — SyntaxError, как в браузере */
{ let joined = '', starts = [];
  for (const f of scripts) { starts.push([joined.split('\n').length, f]); joined += src[f] + '\n;\n' }
  try { new vm.Script(joined, { filename: 'склейка' }) } catch (e) {
    const ln = +((e.stack || '').match(/склейка:(\d+)/) || [])[1] || 0; const at = starts.filter(s => s[0] <= ln).pop();
    err(`склейка скриптов: ${e.message}${at ? ` (${rel(at[1])}:${ln - at[0] + 1})` : ''}`) } }

/* (в) имена верхнего уровня: строки с нулевым отступом. Скобки считаем грубо (строки и шаблоны пропускаем), имена — после let/const/var и запятых глубины 0. */
function topNames(code) {
  const out = [];
  code.split('\n').forEach((line, i) => {
    if (!/^(?:async\s+function|function|let|const|var|class)\b/.test(line)) return;
    let d = 0, q = null, decl = false, expectName = false;
    for (let j = 0; j < line.length; j++) {
      const c = line[j];
      if (q) { if (c === '\\') j++; else if (c === q) q = null; continue }
      if (c === '/' && line[j + 1] === '/') break;
      if (c === '"' || c === "'" || c === '`') { q = c; continue }
      if ('([{'.includes(c)) { d++; continue }
      if (')]}'.includes(c)) { d--; continue }
      if (d !== 0) continue;
      if (c === ';') { decl = false; expectName = false; continue }
      if (c === ',' && decl) { expectName = true; continue }
      if (!/[A-Za-z_$]/.test(c) || /[\w$]/.test(line[j - 1] || '')) continue;
      const w = line.slice(j).match(/^[\w$]+/)[0];
      if (expectName) { out.push([w, i + 1, decl]); expectName = false }
      else if (w === 'function' || w === 'class') { const m = line.slice(j).match(/^(?:function\s*\*?|class)\s+([\w$]+)/); if (m) out.push([m[1], i + 1, w]) }
      else if (w === 'let' || w === 'const' || w === 'var') { decl = w; expectName = true }
      j += w.length - 1;
    }
  });
  return out;
}
const WINDOW = ['event','name','status','top','parent','open','close','stop','print','focus','blur','scroll','scrollTo','scrollBy','history','screen','find','self','length','origin','frames','opener','closed','location','navigator','alert','confirm','prompt','external','menubar','toolbar','crypto','performance','caches','frameElement','visualViewport','customElements','postMessage','moveTo','resizeTo','getSelection','matchMedia','fetch','localStorage','sessionStorage','indexedDB','onload','onerror','onclick','oninput','onresize','document','window','globalThis','innerWidth','innerHeight','outerWidth','devicePixelRatio','scrollX','scrollY','clientInformation','styleMedia','speechSynthesis','statusbar','personalbar','locationbar','scrollbars','defaultStatus','captureEvents','releaseEvents','queueMicrotask','structuredClone','reportError','requestIdleCallback'];
const seen = new Map();
for (const f of scripts) for (const [n, ln, kind] of topNames(src[f])) {
  const at = `${rel(f)}:${ln}`;
  if (WINDOW.includes(n)) warn(`${at}: глобальное «${n}» перекрывает window.${n}`);
  const prev = seen.get(n);
  if (prev && prev.at !== at) err(`${at}: «${n}» уже объявлено в ${prev.at} (${prev.kind} → ${kind})`);
  else if (!prev) seen.set(n, { at, kind });
}

/* (г) CSS */
const kfAll = new Map();
for (const f of styles) {
  const css = src[f].replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ' ')).replace(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/g, m => m.replace(/[{}]/g, ' '));
  let d = 0, ln = 1;
  for (const c of css) { if (c === '\n') ln++; else if (c === '{') d++; else if (c === '}' && --d < 0) { err(`${rel(f)}:${ln}: лишняя «}»`); d = 0 } }
  if (d) err(`${rel(f)}: не закрыто «{»: ${d}`);
  for (const m of css.matchAll(/@keyframes\s+([\w-]+)/g)) {
    const at = `${rel(f)}:${css.slice(0, m.index).split('\n').length}`, prev = kfAll.get(m[1]);
    if (prev) err(`${at}: @keyframes ${m[1]} уже есть в ${prev} — действует последний`); else kfAll.set(m[1], at) }
}

/* (д) data-act → обработчик */
const ACT = scripts.find(f => /actions\.js$/.test(f));
const actSrc = ACT ? src[ACT] : '';
const cases = new Set([...actSrc.matchAll(/case\s+'([^']+)'/g)].map(m => m[1]));
const prefixes = [...actSrc.matchAll(/a\.startsWith\('([^']+)'\)/g)].map(m => m[1]);
const dynamic = new Set([...actSrc.matchAll(/case\s+'([^']+)':[^;]*?a\.slice\(/g)].map(m => m[1]));   // re-*: обработчик сам режет префикс
const handled = v => cases.has(v) || prefixes.some(p => v.startsWith(p));
const emitted = new Map();
const emit = (v, at) => { if (v && !emitted.has(v)) emitted.set(v, at) };
const code = [...scripts.map(f => [rel(f), src[f]]), [rel(HTML), html], ...inline.map(s => [rel(HTML) + ' (встроенный)', s])];
for (const [name, s] of code) {
  for (const m of s.matchAll(/data-act=(?:"([^"]*)"|\\?'([^']*)'|([\w:-]+))/g)) {
    const v = m[1] ?? m[2] ?? m[3]; const at = `${name}:${s.slice(0, m.index).split('\n').length}`;
    if (!v.includes('${')) { emit(v, at); continue }
    const pre = v.slice(0, v.indexOf('${'));
    if (pre) { emit(pre, at); continue }
    for (const l of v.matchAll(/'([\w:-]+)'/g)) emit(l[1], at);   // ${c.frozen?'camretake':'camcancel'} — оба варианта
  }
}
for (const [v, at] of emitted) {
  if (!handled(v) && ![...dynamic].some(d => d.startsWith(v))) err(`${at}: data-act="${v}" — нет case '${v}' в ${rel(ACT)}`);
}
// Значения, которые собираются склейкой и в data-act="${…}" не видны: префикс → где собирается (проверяем, что склейка ещё есть).
const BUILT = [['re-', "services.js, pairFail: retry = 're-'+S.pair.via"]];
for (const [p, where] of BUILT) if (!code.some(([, s]) => s.includes(`'${p}'+`))) warn(`склейка '${p}'+… больше не найдена (${where}) — убрать из BUILT в tools/proto-check.mjs`);
for (const c of cases) {   // необработанное наоборот: case, который ничто не шлёт и о котором нет ни одной строки в коде
  if (emitted.has(c) || BUILT.some(([p]) => c.startsWith(p))) continue;
  const lit = new RegExp(`['"\`]${c.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}['"\`]`, 'g');
  const n = code.reduce((k, [, s]) => k + (s.match(lit) || []).length, 0);
  if (n <= 1) warn(`${rel(ACT)}: case '${c}' — data-act с таким значением нигде не выводится`);
}

for (const w of warns) console.log('предупреждение: ' + w);
for (const e of errors) console.log('ошибка: ' + e);
console.log(`${scripts.length} скриптов, ${styles.length} стилей, data-act: ${emitted.size}, case: ${cases.size} — ${errors.length ? `ошибок: ${errors.length}` : 'ошибок нет'}${warns.length ? `, предупреждений: ${warns.length}` : ''}`);
process.exit(errors.length ? 1 : 0);
