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
  // «Своих знает в лицо»: одна ситуация (ресторан, фото меню) — чату всё объясняешь заново, своему режиму — «как обычно».
  if (k === 2) return `<div class="sc sc6">
    <div class="col chat"><div class="hd"><span class="ci">💬</span><b>Чат</b><small>новый</small></div>
      <div class="bub"><div class="tw">Здравствуйте! Я в ресторане. Не ем острое и свинину, аллергия на орехи, бюджет до 1500 ₽. Посоветуйте 2–3 блюда. Вот меню 📎</div><span class="car"></span></div>
      <div class="cnt"><span class="w"></span><span class="t"></span></div></div>
    <div class="col vah"><div class="hd"><span class="ci">🍽</span><b>Гастрогид</b><small>мой режим</small></div>
      <div class="snap"><span class="thumb"><i class="card"><b>МЕНЮ</b></i></span><span class="fl"></span></div><div class="scap">📷 отправил фото меню</div>
      <div class="ans">Судак на гриле и морс — 1200 ₽.</div>
      <div class="chk"><span>✓ не остро</span><span>✓ без орехов</span><span>✓ до 1500 ₽</span></div>
      <div class="cnt"><span>⏻ 1 нажатие</span><span class="t"></span></div></div></div>`;
  // «Знает, кто зачем пришёл»: режимы — кнопки пульта; у каждой на обороте уже написанная просьба. Нажал — она сказана.
  if (k === 3) {
    const K = [['💬','Ассистент','как чат, по кнопке','Просто спросите — как в чате. Будильник, звонок, вопрос.'],
               ['🌐','Переводчик','🎙 сразу голос','Переводи на английский, ответ — вслух. Начинай с голоса.'],
               ['🔍','Что это?','📷 сразу камера','Скажи, что в кадре и зачем это. Начинай с камеры.'],
               ['🍽','Гастрогид','свой режим','Не ем острое, орехи нельзя, до 1500 ₽. Начинай с камеры.']];
    return `<div class="sc sc7">${K.map(([i,t,tag,back],j)=>`<div class="key k${j}"><div class="fc fr"><span class="ki">${i}</span><b>${t}</b><small>${tag}</small></div><div class="fc bk"><small>на обороте</small><p>${back}</p></div></div>`).join('')}
      <div class="plus">+ свой режим — сколько угодно</div></div>`;
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


/* Свайп по шагам мастера: вправо — назад, влево — вперёд, но только если на экране уже можно «Далее».
   На «Попробуйте голосом» не работает: у кнопки микрофона жест «влево — отмена». В приложении «Назад» системы — тоже шаг назад. */
let onbSw = null;
document.addEventListener('pointerdown', e => {
  onbSw = null;
  if (S.screen !== 'app' || S.onboarded || ONB[S.onbStep] === 'try') return;
  if (!e.target.closest('#scr .onb') || e.target.closest('button,input,a,[data-act],[data-role],[data-side]')) return;
  onbSw = { x: e.clientX, y: e.clientY };
});
document.addEventListener('pointerup', e => {
  if (!onbSw) return; const dx = e.clientX - onbSw.x, dy = e.clientY - onbSw.y; onbSw = null;
  if (Math.abs(dx) < 60 || Math.abs(dy) > Math.abs(dx)) return;
  if (dx > 0) return onbBack();
  if ($('#scr .onb .nav .fbtn[data-act="onbnext"]')) { trace('Свайп влево — дальше (на этом шаге уже можно «Далее»).', 'p'); onbNext() }
});
function onbBack() {
  if (S.onbStep <= 0) return;
  S.onbStep--; if (ONB[S.onbStep] === 'try' && (S.w.model === 'none' || S.w.model === 'broken')) S.onbStep--;
  S.keyCheck = null; trace('Свайп вправо — шаг назад. В приложении так же работает системный «Назад».', 'p'); render();
}
