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
  let art='',h='',p='',extra='',primary=['Далее','onbnext'],skip=['Позже','onbnext'];
  if (st==='welcome'){ art=introScene(0); h='Вахтёр. Всегда на месте'; p='Зажал кнопку — сказал — сделано. Как Алиса или Gemini, только быстрее, без рекламы и с мозгом, который выбираете вы.'; skip=null; primary=['Начать','onbnext'] }
  if (st==='voice'){ art=voiceArt(); h='Голос на телефоне'; p=`Речь распознаёт GigaAM — открытая модель Сбера. Скачать один раз ${mb(MODEL_TOTAL)} МБ, дальше голос не уходит с телефона и работает без интернета.`;
    if (S.w.model==='ready') extra='<div class="note ok">Готово</div>';
    else if (S.w.model==='loading') extra=`<div class="prog"><i style="width:${S.modelPct}%"></i></div><p style="font-size:13px;margin-top:8px">${S.w.net==='none'?'Ждёт сеть — продолжит с того же места.':`${S.modelPct} % — можно идти дальше, докачается само.`}</p>`;
    if (S.w.model==='none'||S.w.model==='broken') primary=['Скачать','dl']; else skip=null;
    if (S.w.model!=='none' && S.w.mic!=='ok') extra += `<div class="setcard" style="margin-top:12px">${li(I.mic,'Микрофон',S.w.mic==='denied'?'Запрещён — разрешите в настройках':'Нужен для голосового ввода',S.w.mic==='denied'?'sysmic':'askmic')}</div>` }
  // Сцена — гонка «приложение против кнопки», под ней — что настроить. Единственное действие на любом Android — «Цифровой помощник»
  // (роль есть везде с Android 10). Кнопка Samsung «Цифровой помощник» открывает того, кто выбран помощником: если удержание
  // уже на нём (по умолчанию в One UI 7+), выбрали Вахтёра — кнопка заработала сама, вторая строка сразу с ✓. Иначе — «Настроить».
  // Незнакомый телефон: что на кнопке, не прочитать — проба «зажмите кнопку», не вышло — способы вызова и плитка в шторке.
  if (st==='button'){ const role=S.w.role==='ours', known=S.w.profile!=='unknown', ok=btnDone(), sideReady=known&&S.sideOk;
    art=introScene(1); h='Своих — без очереди';
    p=ok?(known?'Готово: зажатие боковой кнопки откроет Вахтёра. Можно проверить прямо сейчас.':S.sideProbe==='yes'?'Готово: Вахтёр открывается кнопкой.':'Готово: Вахтёр — помощник телефона.')
      :sideReady?'Зажали кнопку — и сразу вопрос, без приложений и новых чатов. Кнопка уже открывает помощника — осталось выбрать помощником Вахтёра.'
      :known?'Зажали кнопку — и сразу вопрос, без приложений и новых чатов. Включается в настройках телефона, «Настроить» откроет их по очереди.'
      :'Вызвали помощника — и сразу вопрос, без приложений и новых чатов. Помощника выбирают в настройках телефона, «Настроить» откроет нужный экран.';
    const sideSum={bixby:'сейчас Bixby',double:'помощник — на двойном нажатии',power:'сейчас меню выключения'}[S.w.side];
    const row2 = known
      ? (S.sideOk ? li(I.btn,'Боковая кнопка','Зажатие — цифровой помощник · настраивать не нужно','sysside',false,I.ok) : li(I.btn,'Боковая кнопка','Не настроено · '+sideSum,'sysside',true))
      : !role ? li(I.btn,'Вызов','Кнопка или жест — зависит от телефона')
      : S.sideProbe==='yes' ? li(I.btn,'Вызов','Работает — открылся лист',null,false,I.ok)
      : S.sideProbe==='no' ? li(I.btn,'Вызов','Долгое нажатие «Домой» или жест из угла','sysside')+(S.tileAdded?li(I.list,'Плитка в шторке','Добавлена',null,false,I.ok):li(I.list,'Плитка в шторке','Вахтёр — из быстрых настроек','addtile'))
      : `<div class="sprobe"><span>${I.btn}</span><p>Зажмите кнопку, на которой у вас помощник. Открылся лист?</p><div class="ba"><button class="tbtn" data-act="sideno">Нет</button><button class="tbtn" data-act="sideyes">Да</button></div></div>`;
    extra=`<div class="setcard">${li(I.spark,'Цифровой помощник',role?'Вахтёр':'Не настроено · сейчас Gemini','sysrole',!role,role?I.ok:'')}${row2}</div>${S.w.model==='loading'?`<p class="bgdl">Распознавание качается — ${S.modelPct} %.${ok?'':' Пока настройте кнопку.'}</p>`:''}`;
    if (ok) skip=null; else { primary=['Настроить','btnsetup']; skip=['Позже','btnlater'] } }
  // Режимы — два экрана: чем это лучше чата («как обычно» против просьбы заново) и как устроено (кнопка = написанная просьба). Настраивать нечего.
  if (st==='rules'){ art=introScene(2); h='Знает, как у вас заведено'; p='Чату каждый раз объясняешь всё заново. Своему режиму — один раз: правила он помнит и сразу открывает камеру.'; skip=null }
  if (st==='quick'){ art=introScene(3); h='Привычки знает наизусть'; p='Каждый режим — уже написанная просьба: нажали, и она сказана. Ассистент — как чат, только по кнопке.'; skip=null }
  // Сцена — постоянной высоты, заголовок и текст на одном месте на всех таких шагах; что появляется по ходу (полоска, «Готово»,
  // микрофон, настройки кнопки) — в зарезервированное место под текстом: ничего не подпрыгивает (в Android — Onboarding.kt, Art/Slot).
  return `<div class="layer appv">${statusBar(true)}<div class="onb fixed"><div class="art">${art}</div><h2>${h}</h2><p>${p}</p><div class="ex">${extra}</div>${navBar(skip,primary)}</div></div>`;
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
    : last ? `<div class="tbig" data-k="${shots.length}">${esc(last.text)}</div><span class="tfast">⚡ Распознано на телефоне за ${sec(last.ms)}${'\u00a0'}с</span>`
    : `<div class="${why?'twhy':'tph'}">${why||'Скажите что-нибудь'}</div>`;
  const p = loading ? 'Вахтёр пока учит русский — подождите или попробуйте потом в листе.' : 'Зажмите микрофон и скажите что-нибудь — проверим, как слышно.';
  return `<div class="layer appv">${statusBar(true)}<div class="onb tryonb"><h2>Попробуйте голосом</h2><p>${p}</p>
    <div class="tstage">${old.length&&!rec&&!S.recog&&!loading?`<div class="told">${old.map(x=>`<p>${esc(x.text)}</p>`).join('')}</div>`:''}
      <div class="thero">${hero}</div><div class="ttext">${text}</div></div>
    ${composerHtml({into:'try', pulse:freed})}${navBar(null, loading?['Пропустить','onbnext']:['Далее','onbnext'])}</div></div>` }
