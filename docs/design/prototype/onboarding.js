/* Первый запуск — мастер: шаги, навигация, проба голоса (в Android — app/Onboarding.kt). Сцены шагов — intro.js, кнопка/лист/ключ — ui.js. */
const ONB=['welcome','voice','button','try','rules','quick','brain'];
// точки — своей строкой над кнопками: ширина «Подключить» и крупный шрифт телефона их не задевают
function navBar(skip,primary){ return `${onbDots()}<div class="nav">${skip?`<button class="tbtn" data-act="${skip[1]}">${skip[0]}</button>`:'<span style="width:60px"></span>'}${primary?`<button class="fbtn" data-act="${primary[1]}">${primary[0]}</button>`:'<span style="width:60px"></span>'}</div>` }
/** Точки шагов как в Instagram: на виду не больше пяти, окно едет за текущим шагом; крайняя точка мельче, если за ней есть ещё.
 *  Ширина постоянная, строка своя — над кнопками, строго по центру (в Android — StepDots). */
const DOTS_MAX=5;
function onbDots(){ const n=ONB.length, k=Math.min(n,DOTS_MAX), at=Math.max(0,Math.min(S.onbStep-2,n-k));
  return `<div class="dots">${Array.from({length:k},(_,j)=>{ const i=at+j, edge=(j===0&&i>0)||(j===k-1&&i<n-1);
    return `<i class="${i===S.onbStep?'on':edge?'sm':''}"></i>` }).join('')}</div>` }
function onb(){
  const st=ONB[S.onbStep];
  if (st==='try') return tryStep();
  if (st==='brain'){ const K=S.keyCheck, done=S.w.key==='ok'&&K&&K.stage==='done', run=K&&K.stage==='run';
    // Как остальные шаги: сцена — в растягиваемой области, заголовок, текст и поле ключа — внизу.
    return `<div class="layer appv">${statusBar(true)}<div class="onb wbrain"><div class="art">${introScene('brain')}</div>
    <h2>Подключите мозг</h2><p>Будильник, таймер, звонок — Вахтёр делает сам, даже без сети. Сложное — звонок наверх, мозгу. Начнём с DeepSeek — нужен только ключ.</p>${keyBody(true)}
    ${navBar(done?null:['Позже','onbnext'], done?['Открыть','onbnext']:run?['Подключаю…','noop']:['Подключить','keycheck'])}</div></div>` }
  let art='',h='',p='',extra='',card=null,stat=null,primary=['Далее','onbnext'],skip=['Позже','onbnext'];
  if (st==='welcome'){ art=introScene(0); h='Вахтёр. Всегда на месте'; p='Зажал кнопку — сказал — сделано. Как Алиса или Gemini, только быстрее, без рекламы и с мозгом, который выбираете вы.'; skip=null; primary=['Начать','onbnext'] }
  if (st==='voice'){ art=voiceArt(); h='Голос на телефоне'; p=`GigaAM от Сбера, ${mb(MODEL_TOTAL)} МБ один раз. Дальше голос не уходит с телефона и работает без сети.`;
    card = S.w.model!=='none' && S.w.mic!=='ok' ? li(I.mic,'Микрофон',S.w.mic==='denied'?'Запрещён — разрешите в настройках':'Нужен для голосового ввода',S.w.mic==='denied'?'sysmic':'askmic') : '';
    stat = S.w.model==='ready' ? '<span class="ok">✓ Голос скачан</span>'
      : S.w.model==='loading' ? `<div class="prog"><i style="width:${S.modelPct}%"></i></div><span>${S.w.net==='none'?'Ждёт сеть — продолжит с того же места':`Скачиваю голос — ${S.modelPct}\u00a0% · можно идти дальше`}</span>` : '';
    if (S.w.model==='none'||S.w.model==='broken') primary=['Скачать','dl']; else skip=null }
  // Обязательна только роль «Цифровой помощник». Боковая кнопка — строкой, только если с ней есть что делать: уже открывает
  // помощника (One UI 7+ по умолчанию) — строки нет; на ней Bixby или меню выключения — «Сейчас …»; незнакомый телефон —
  // после выбора помощника проба «зажмите кнопку». Отдельного шага проверки нет. Текст — одна мысль.
  if (st==='button'){ const role=S.w.role==='ours', known=S.w.profile!=='unknown', ok=btnDone();
    art=introScene(1); h='Своих — без очереди';
    p=!role?'Зажали кнопку — и сразу вопрос. Осталось выбрать Вахтёра помощником.'
      :known&&!S.sideOk?'Осталось переключить боковую кнопку на помощника.'
      :known?'Готово: зажмите боковую кнопку — откроется Вахтёр.'
      :S.sideProbe==='yes'?'Готово: Вахтёр открывается кнопкой.'
      :S.sideProbe==='no'?'Вахтёр — помощник. Открыть его можно так:'
      :'Вахтёр — помощник. Проверим кнопку:';
    const sideNow={bixby:'Сейчас Bixby',double:'Сейчас — двойное нажатие',power:'Сейчас меню выключения'}[S.w.side];
    const row2 = known ? (S.sideOk ? '' : li(I.btn,'Боковая кнопка',sideNow,'sysside',true))
      : !role || S.sideProbe==='yes' ? ''
      : S.sideProbe==='no' ? li(I.btn,'Вызов','«Домой» долгим нажатием или жест из угла','sysside')+(S.tileAdded?li(I.list,'Плитка в шторке','Добавлена',null,false,I.ok):li(I.list,'Плитка в шторке','Из быстрых настроек','addtile'))
      : `<div class="sprobe"><span>${I.btn}</span><p>Зажмите кнопку помощника. Открылся лист?</p><div class="ba"><button class="tbtn" data-act="sideno">Нет</button><button class="tbtn" data-act="sideyes">Да</button></div></div>`;
    card = li(I.spark,'Цифровой помощник',role?'Вахтёр':'Сейчас Gemini','sysrole',!role,role?I.ok:'')+row2;
    stat = S.w.model==='loading' ? `<span>Голос качается — ${S.modelPct}\u00a0%</span>` : '';
    if (ok) skip=null; else { primary=['Настроить','btnsetup']; skip=['Позже','btnlater'] } }
  // Режимы — два экрана: чем это лучше чата («как обычно» против просьбы заново) и как устроено (кнопка = написанная просьба). Настраивать нечего.
  if (st==='rules'){ art=introScene(2); h='Знает, как у вас заведено'; p='Чату каждый раз объясняешь всё заново. Своему режиму — один раз: правила он помнит и сразу открывает камеру.'; skip=null }
  if (st==='quick'){ art=introScene(3); h='Привычки знает наизусть'; p='Каждый режим — уже написанная просьба: нажали, и она сказана. Ассистент — как чат, только по кнопке.'; skip=null }
  // Сцена — во всём свободном месте, текст — у кнопок. Внутри шага, где по ходу что-то появляется (полоска, «Готово», микрофон,
  // строки кнопки), под текстом — резерв на самое высокое состояние: ничего не подпрыгивает (в Android — Onboarding.kt, Slot).
  // Шаблон мастера: сцена — свободное место; заголовок и текст (до двух строк); карточка действия — всегда прямо над точками,
  // место под одну строку занято заранее; под ней строка фона (загрузка голоса) — тоже заранее. Шаги без действия — без них.
  const act = card===null ? '' : `<div class="onbact">${card?`<div class="setcard">${card}</div>`:''}</div><div class="onbstat">${stat||''}</div>`;
  return `<div class="layer appv">${statusBar(true)}<div class="onb"><div class="art">${art}</div><h2>${h}</h2><p>${p}</p>${extra}${act}${navBar(skip,primary)}</div></div>`;
}
/** Первый запуск, шаг «Голос»: речь → модель на телефоне → текст; облако зачёркнуто. Движение — в intro.css (.scv). */
function voiceArt(){ return `<div class="sc scv"><div class="cloud"><span>☁</span><i></i></div><div class="lnk"></div>
  <div class="tel"><div class="mdl"><span class="chipi"></span><span><b>GigaAM</b><small>модель Сбера, на телефоне</small></span></div>
    <div class="say"><div class="deq">${vMark('v')}</div><div class="tx">Сколько варить гречку?</div></div></div>
  <div class="lock">🔒 чужих не пустит: голос остаётся здесь</div></div>` }
/** Первый запуск: проба голоса. Герой экрана — эквалайзер Вахтёра. Пока распознавание качается, его столбики и есть полоска
 *  загрузки: готовые шевелятся, остальные — точки, поперёк — шов («рот зашит», микрофон серый, «Пропустить»). Докачалось — шов
 *  лопается, столбики оживают, кнопка один раз пульсирует. Дальше фраза — крупно прямо на фоне, без поля и подложки; прошлые
 *  бледнеют выше. Внизу одна тихая строка жестов у кнопки — той же, что в листе. */
function tryStep(){
  const rec=S.rec&&S.rec.into==='try', shots=S.tryShots||[], loading=S.w.model==='loading', ready=S.w.model==='ready';
  const freed = ready && S.modelReadyAt && Date.now()-S.modelReadyAt<1600;   // только что докачалось — шов лопается
  const why = S.w.model!=='ready'&&!loading?'Голос не скачан — можно потом в настройках':S.w.mic==='denied'?'Микрофон запрещён — разрешите его в настройках':S.w.mic!=='ok'?'Нажмите на микрофон — Android спросит доступ':'';
  const sec = ms => (ms/1000).toFixed(1).replace('.',',');
  const last = shots[shots.length-1], old = shots.slice(0,-1).slice(-3);
  // Тот же знак, что в листе: слушает — живой от голоса; распознаёт — пунктирное кольцо; качается — кольцо-шкала, ядро спит.
  const hero = loading ? `<div class="tsun sleep"><i class="prog" style="--tp:${S.modelPct}"></i>${vMark()}</div><div class="tpct">${S.modelPct}${'\u00a0'}%</div>`
    : `<div class="tsun ${freed?'freed':''}" data-st="${rec?'rec':S.recog?'recog':'idle'}">${vMark()}</div><div class="tpct" style="visibility:hidden">0${'\u00a0'}%</div>`;   // строка процентов — в резерве, текст не прыгает
  const text = loading ? '<div class="tcap">Вахтёр учит русский…</div>'
    : rec ? '<div class="tcap">Слушаю…</div>' : S.recog ? '<div class="tcap">Распознаю на телефоне…</div>'
    : last ? `<div class="tbig">${esc(last.text)}</div><span class="tfast">⚡ Распознано на телефоне за ${sec(last.ms)}${'\u00a0'}с</span>`
    : `<div class="${why?'twhy':'tph'}">${why||'Скажите что-нибудь'}</div>`;
  const p = loading ? 'Вахтёр пока учит русский — подождите или попробуйте потом в листе.' : 'Зажмите микрофон и скажите что-нибудь — проверим, как слышно.';
  // Тот же шаблон, что у остальных шагов: сцена (знак, прошлые фразы) — в свободном месте сверху; распознанное — крупно под ней;
  // заголовок и текст — внизу над полем, на той же высоте, что на соседних шагах.
  return `<div class="layer appv">${statusBar(true)}<div class="onb tryonb">
    <div class="art tstage">${old.length&&!rec&&!S.recog&&!loading?`<div class="told">${old.map(x=>`<p>${esc(x.text)}</p>`).join('')}</div>`:''}
      <div class="thero">${hero}</div><div class="ttext">${text}</div></div>
    <h2>Попробуйте голосом</h2><p>${p}</p>
    ${composerHtml({into:'try', pulse:freed})}${navBar(null, loading?['Пропустить','onbnext']:['Далее','onbnext'])}</div></div>` }
