/* Приветствие: девиз + 4 карточки. Сцены — только разметка, движение в intro.css (те же фазы — в приложении, ui/Intro.kt). */
const INTRO = [
  { k: 0, h: 'Зажал кнопку — сказал — сделано', p: 'Как Алиса или Gemini — только быстрее, без рекламы и с моделью, которую выбираете вы.' },
  { k: 1, h: 'Не чат, а кнопка', p: 'Не нужно открывать приложение и заводить новый чат. Нужный разговор уже ждёт под кнопкой.' },
  { k: 2, h: 'Объяснили один раз', p: 'Переводчик, гид по меню, справка о товаре — задача уже внутри. Говорите только суть.' },
  { k: 3, h: 'Навёл камеру — узнал, что это', p: 'Где нужен глаз, сразу открывается камера: вывеска, меню, товар.' },
  { k: 4, h: 'Сказал — сделано', p: 'Будильник, таймер, календарь, звонок — без лишних шагов. Работает мгновенно, даже без интернета.' },
];
const WAVE = '<div class="wave">' + '<i></i>'.repeat(7) + '</div>';

function introScene(k) {
  if (k === 0) return `<div class="sc sc0"><span class="halo"></span><span class="halo b"></span><span class="logo"><i><b></b><b></b><b></b><b></b></i></span></div>`;
  if (k === 1) return `<div class="sc sc1"><div class="phone"><span class="sidekey"></span></div>
    <div class="steps2"><span>Разблокировать</span><span>Найти приложение</span><span>Новый чат</span><span>Объяснить задачу</span><span>Напечатать вопрос</span></div>
    <div class="ms"><div class="g"></div><div class="h"><i></i><b>Ассистент</b> · на связи</div>${WAVE}
      <div class="me">Сколько варить гречку?</div><div class="ai">15–20 минут на слабом огне, 1 : 2 с водой.</div></div></div>`;
  if (k === 2) return `<div class="sc sc2"><div class="ms"><div class="g"></div><div class="h"><i></i><b>Режимы</b> · задача уже внутри</div>
    <div>
      <div class="pane"><span class="task">★ переводи на китайский</span><div class="me">Где здесь туалет?</div><div class="ai">洗手间在哪里？<br><small style="color:var(--soft)">Xǐshǒujiān zài nǎlǐ?</small></div></div>
      <div class="pane"><span class="task">★ не остро, без свинины</span><div class="thumb menu"></div><div class="ai">Судак на гриле и морс — под ваши вкусы.</div></div>
      <div class="pane"><span class="task">★ что это и нормальная ли цена</span><div class="thumb tea"></div><div class="ai">Пуэр, 15 лет выдержки. Цена обычная.</div></div>
    </div>
    <div class="mtabs2"><span>Переводчик</span><span>Гастрогид</span><span>Что это?</span></div>
    </div></div>`;
  if (k === 3) return `<div class="sc sc3"><div class="ms"><div class="g"></div><div class="h"><i></i><b>Что это?</b> · на связи</div>
    <div class="vf"><div class="shelf"></div><span class="jar a"></span><span class="jar b"></span><span class="jar c"></span>
      <svg class="ring" viewBox="0 0 70 96"><path d="M35 4 C60 4 66 30 64 52 C62 80 50 92 34 92 C14 92 5 76 6 50 C7 24 14 6 38 6"/></svg><div class="flash"></div></div>
    <div class="shut">↑</div>
    <div class="res"><div class="thumb2"></div><div class="ai">Пуэр, 15 лет выдержки — хороший. Цена обычная, брать можно.</div></div>
    </div></div>`;
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
