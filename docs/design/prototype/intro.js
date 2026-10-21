/* Сцены мастера первого запуска (в Android — ui/Intro.kt). Только разметка; движение — intro.css; шаги и навигация — onboarding.js. */

function introScene(k) {
  if (k === 0) return `<div class="sc sc0"><span class="halo"></span><span class="halo b"></span><span class="logo"><i><b></b><b></b><b></b><b></b></i></span><span class="post"><i></i>Вахтёр · на месте</span></div>`;
  if (k === 1) {
    const st = ['Разблокировать','Найти приложение','Открыть','Новый чат'];
    return `<div class="sc sc1">
      <div class="rcard usual"><div class="hd"><span class="apps"><i></i><i></i><i></i><i></i></span><b>ИИ-приложение</b><span class="clk"></span></div>
        <div class="chain">${st.map((s,j)=>`<span class="tk"><em>№${j+1}</em>${s}</span><i>→</i>`).join('')}<span class="q">🎙 вопрос</span></div></div>
      <div class="rcard vah"><div class="hd"><span class="pw">⏻</span><b>Вахтёр</b><small class="ql free">без очереди</small><span class="clk"></span><span class="ok">✓</span></div>
        <div class="chain"><span class="k">⏻ Зажать</span><i>→</i><span class="q dk"><span class="deq">${'<b></b>'.repeat(9)}</span><span class="tx">Сколько варить гречку?</span></span></div>
        <div class="ai">15–20 минут, воды вдвое больше.</div></div></div>`;
  }
  // «Знает, как у вас заведено»: одна ситуация (ресторан, фото меню) — чату всё объясняешь заново, своему режиму — «как обычно».
  // Под колонками — секундомеры крупно: сколько ушло на одно и то же.
  if (k === 2) { const sw = '<svg class="swatch" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="12" cy="13.5" r="7.5"/><path d="M12 13.5V9.5M10 3h4M12 3v2.5M18.5 7l1.3-1.3"/></svg>';
    return `<div class="sc sc6">
    <div class="colw"><div class="col chat"><div class="hd"><span class="ci">💬</span><span class="tt"><b>Чат</b><small>новый</small></span></div>
      <div class="ask"><div class="tw">${askWords()}</div><span class="car"></span></div>
      <div class="cnt"><span class="w"></span></div></div>
      <div class="timer tchat">${sw}<span class="t"></span></div></div>
    <div class="colw"><div class="col vah"><div class="hd"><span class="ci">🍽</span><span class="tt"><b>Гастрогид</b><small>мой режим</small></span></div>
      <div class="snap"><span class="thumb"><i class="mcrd"><b>МЕНЮ</b></i></span><span class="flash"></span></div><div class="scap">📷 отправил фото меню</div>
      <div class="ans">Возьмите судака на гриле и морс — 1200${'\u00a0'}₽. Не остро и без орехов — как вы любите.</div>
      <div class="cnt"><span>⏻ 1 нажатие</span></div></div>
      <div class="timer tvah">${sw}<span class="t"></span></div></div></div>`; }
  // «Привычки знает наизусть»: режимы — кнопки пульта; у каждой на обороте уже написанная просьба. Нажал — она сказана.
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

/** «Чат»: просьба печатается по словам; счётчик считает только видимые слова («₽», «📎» — не слова), как SceneChat в Intro.kt. */
const ASK='Здравствуйте! Я в ресторане. Не ем острое и свинину, аллергия на орехи, бюджет до 1500\u00a0₽. Посоветуйте 2–3 блюда. Вот меню 📎';
function askWords(){ const tk=ASK.split(' '), isW=x=>/[0-9A-Za-zА-Яа-яЁё]/.test(x), n=tk.filter(isW).length, at=i=>(3+75*(i+1)/n).toFixed(2);
  // слово №i появляется на своей доле 3–78 % цикла (как счётчик: steps(n)); знак без букв — вместе с предыдущим словом. Кадры — свои на слово.
  if (!document.getElementById('askkf')) document.head.insertAdjacentHTML('beforeend', `<style id="askkf">${Array.from({length:n},(_,i)=>`@keyframes tw${i}{0%,${at(i)}%{opacity:0}${(+at(i)+.01).toFixed(2)}%,96%{opacity:1}100%{opacity:0}}`).join('')}</style>`);
  let w=-1; return tk.map(x=>{ if(isW(x)) w++; return `<span style="animation-name:tw${Math.max(w,0)}">${x}</span>` }).join(' ') }
