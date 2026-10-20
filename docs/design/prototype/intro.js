/* Первый запуск — один мастер: каждый экран сначала объясняет, потом сразу настраивает (ui.js, ONB). Здесь — только сцены; движение в intro.css. */
const WAVE = '<div class="wave">' + '<i></i>'.repeat(7) + '</div>';

function introScene(k) {
  if (k === 0) return `<div class="sc sc0"><span class="halo"></span><span class="halo b"></span><span class="logo"><i><b></b><b></b><b></b><b></b></i></span><span class="post"><i></i>Вахтёр · на месте</span></div>`;
  if (k === 1) {
    const st = ['Разблокировать','Найти приложение','Открыть','Новый чат'];
    return `<div class="sc sc1">
      <div class="card usual"><div class="hd"><span class="apps"><i></i><i></i><i></i><i></i></span><b>ИИ-приложение</b><span class="clk"></span></div>
        <div class="chain">${st.map((s,j)=>`<span class="tk"><em>№${j+1}</em>${s}</span><i>→</i>`).join('')}<span class="q">🎙 вопрос</span></div></div>
      <div class="card vah"><div class="hd"><span class="pw">⏻</span><b>Вахтёр</b><small class="ql free">без очереди</small><span class="clk"></span><span class="ok">✓</span></div>
        <div class="chain"><span class="k">⏻ Зажать</span><i>→</i><span class="q dk"><span class="deq">${'<b></b>'.repeat(9)}</span><span class="tx">Сколько варить гречку?</span></span></div>
        <div class="ai">15–20 минут, воды вдвое больше.</div></div></div>`;
  }
  if (k === 2) return `<div class="sc sc2">
    <div class="mcard"><div class="mh"><span class="mi">🍽</span><b>Гастрогид</b><small>мой режим</small></div>
      <div class="rule"><i>1</i>Не ем острое и свинину</div>
      <div class="rule"><i>2</i>Аллергия на орехи</div>
      <div class="rule"><i>3</i>Бюджет до 1500 ₽, советуй 2–3 блюда</div>
      <div class="start">Начинать с: <b>📷 камеры</b></div>
      <div class="stamp">всё запомнил</div></div>
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
  if (k === 'brain') return `<div class="sc sc5">
    <div class="ups"><div class="up mz"><span class="ui">🧠</span><span><b>Мозг Вахтёра</b><small class="who"><i>DeepSeek</i><i>OpenAI</i><i>Hermes</i></small></span><span class="cl">☁</span></div></div>
    <div class="wire"><i class="pk"></i></div>
    <div class="ms"><div class="g"></div><div class="h"><i></i><b>Ассистент</b> · на связи</div>
      <div class="pa"><div class="act2"><span class="ic">⏰<span class="ok">✓</span></span><span><b>Будильник поставлен · 7:00</b><small>сам, на телефоне</small></span></div>
        <div class="act2"><span class="ic">⏱<span class="ok">✓</span></span><span><b>Таймер запущен · 1:00</b><small>осталось 0:59</small></span></div>
        <span class="off">✈ нет сети — всё равно сам</span></div>
      <div class="pb"><div class="me">Спланируй выходные в Казани</div><div class="ca"><div class="call">☎ Звоню наверх…</div>
        <div class="ai">Суббота — Кремль и Баумана, воскресенье — Свияжск на теплоходе.</div></div></div></div></div>`;
}

