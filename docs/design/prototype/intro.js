/* Приветствие: девиз + 4 карточки. Сцены 1–3 (v3: гонка, правила режима, два телефона) — пока только здесь; приложение (ui/Intro.kt) — прошлая версия. Сцены — только разметка, движение в intro.css. */
const INTRO = [
  { k: 0, h: 'Зажал кнопку — сказал — сделано', p: 'Как Алиса или Gemini — только быстрее, без рекламы и с моделью, которую выбираете вы.' },
  { k: 1, h: 'Не чат, а кнопка', p: 'Не нужно открывать приложение и заводить новый чат. Нужный разговор уже ждёт под кнопкой.' },
  { k: 2, h: 'Настроил один раз', p: 'Язык перевода, вкусы, аллергия — режим помнит ваши правила. Дальше хватает одной кнопки.' },
  { k: 3, h: 'Сразу камера или голос', p: 'Режим сам решает, с чего начать: меню и вывески — камерой, вопросы — голосом. На одно нажатие меньше.' },
  { k: 4, h: 'Сказал — сделано', p: 'Будильник, таймер, календарь, звонок — мгновенно, прямо на телефоне, даже без интернета.' },
];
const WAVE = '<div class="wave">' + '<i></i>'.repeat(7) + '</div>';

function introScene(k) {
  if (k === 0) return `<div class="sc sc0"><span class="halo"></span><span class="halo b"></span><span class="logo"><i><b></b><b></b><b></b><b></b></i></span></div>`;
  if (k === 1) {
    const st = ['Разблокировать','Найти приложение','Открыть','Новый чат'];
    return `<div class="sc sc1">
      <div class="card usual"><div class="hd"><span class="apps"><i></i><i></i><i></i><i></i></span><b>ИИ-приложение</b><span class="clk"></span></div>
        <div class="chain">${st.map(s=>`<span>${s}</span><i>→</i>`).join('')}<span class="q">🎙 вопрос</span></div></div>
      <div class="card tasy"><div class="hd"><span class="pw">⏻</span><b>Тася</b><span class="clk"></span><span class="ok">✓</span></div>
        <div class="chain"><span class="k">⏻ Зажать кнопку</span><i>→</i><span class="q">🎙 вопрос</span></div>
        <div class="ai">Гречка — 15–20 минут.</div></div></div>`;
  }
  if (k === 2) return `<div class="sc sc2">
    <div class="mcard"><div class="mh"><span class="mi">🍽</span><b>Гастрогид</b><small>мой режим</small></div>
      <div class="rule"><i>1</i>Не ем острое и свинину</div>
      <div class="rule"><i>2</i>Аллергия на орехи</div>
      <div class="rule"><i>3</i>Бюджет до 1500 ₽, советуй 2–3 блюда</div>
      <div class="start">Начинать с: <b>📷 камеры</b></div>
      <div class="stamp">записано один раз</div></div>
    <div class="use"><div class="later">через неделю, в ресторане…</div>
      <div class="ms"><div class="g"></div><div class="h"><i></i><b>Гастрогид</b> · одно нажатие</div>
        <div class="thumb menu"></div>
        <div class="ai">Судак на гриле и морс — 1200 ₽.</div>
        <div class="chk"><span>✓ не остро</span><span>✓ без свинины</span><span>✓ без орехов</span><span>✓ до 1500 ₽</span></div></div></div></div>`;
  if (k === 3) {
    const home = '<div class="home">' + '<i></i>'.repeat(12) + '</div>';
    const ph = (m, sheet, cap) => `<div class="tel"><div class="ml">${m}</div><div class="dev"><span class="key"></span><div class="scr2">${home}${sheet}</div></div><div class="cap">${cap}</div></div>`;
    return `<div class="sc sc3">
      ${ph('Переводчик', `<div class="sht"><div class="g"></div>${WAVE}<small>говорите…</small></div>`, '🎙 сразу голос')}
      ${ph('Что это?', `<div class="sht cam"><div class="g"></div><div class="vf"><div class="shelf"></div><span class="jar a"></span><span class="jar b"></span></div><div class="shut"></div></div>`, '📷 сразу камера')}
      </div>`;
  }
  return `<div class="sc sc4"><div class="ms"><div class="g"></div><div class="h"><i></i><b>Ассистент</b> · на связи</div>${WAVE}
    <div class="live">будильник на 7 и таймер на минуту</div>
    <div class="me">Будильник на 7 и таймер на минуту</div>
    <div class="acts"><div class="act2"><span class="ic">⏰<span class="ok">✓</span></span><span><b>Будильник поставлен · 7:00</b><small>прозвенит через 9 ч</small></span></div>
      <div class="act2"><span class="ic">⏱<span class="ok">✓</span></span><span><b>Таймер запущен · 1:00</b><small>осталось 0:59</small></span></div></div>
    <span class="off">✈ без интернета — тоже работает</span></div></div>`;
}

function introHtml() {
  const i = S.introStep || 0, c = INTRO[i], last = i === INTRO.length - 1;
  return `<div class="layer appv">${statusBar(true)}<div class="intro"><div class="islide">
    <div class="stage">${introScene(c.k)}</div><h2>${c.h}</h2><p>${c.p}</p></div>
    <div class="nav" style="display:flex;justify-content:space-between;align-items:center;padding-top:14px">
      <button class="tbtn" data-act="introskip">${last ? '' : 'Пропустить'}</button>
      <div class="dots">${INTRO.map((_, j) => `<i class="${j === i ? 'on' : ''}"></i>`).join('')}</div>
      <button class="fbtn" data-act="${last ? 'introdone' : 'intronext'}">${last ? 'Настроить' : 'Далее'}</button></div></div></div>`;
}
