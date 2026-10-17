/* Лист ассистента и съёмка: открыть/закрыть, камера, загрузка фото (в Android — VoiceInteractionSession, CameraX, UploadWorker). */
/* ───────── лист ───────── */
function openOverlay(src){
  token++; S.busy=false;
  const isSide = src==='side'||src.startsWith('боков');
  const R=S.settings.returnToAssistant; if (isSide && S.mode!=='main' && R!=='never' && (R==='always' || S.idle>=30 || !curMode().enabled)){ const t=curMode().title; S.mode='main'; trace(R==='always'?`Боковая кнопка всегда открывает Ассистента (настройка). «${t}» — через вкладки или ярлык.`:`Без дела ${S.idle} мин — боковая кнопка снова открывает Ассистента. «${t}» остался во вкладках, лента сохранена.`,'p') }
  S.idle=0;
  S.rec=null; const O={pop:true}; S.ov=O;
  if (curMode().primary==='photo' && curMode().accepts.photo){ render(); return openCamera(true) }
  const sub=s=>`<span style="font-size:14.5px;font-weight:400;color:var(--soft)">${s}</span>`;
  if (S.w.model!=='ready'){ O.live=`<span class="st p">${S.w.model==='loading'?`Распознавание качается: ${S.modelPct} %`:'Голос ещё не готов'}</span>${sub('Пока — пишите. Команды и вопросы работают.')}`; return render() }
  if (S.w.mic==='ask'){ S.dialog={ic:'🎙',title:'Разрешить приложению «Тася» записывать аудио?',opts:[['При использовании приложения','mic_ok'],['Только в этот раз','mic_ok'],['Не разрешать','mic_no','sec2']]}; S.pendingSide = src==='side'; trace('Микрофон спрашиваем в момент, когда он нужен, — не на экране настройки.','p'); return render() }
  if (S.w.mic==='denied'){ O.live=`<span class="st e">Нет доступа к микрофону</span>${sub('Пока можно писать. Чтобы говорить, разрешите микрофон в настройках.')}<br><button class="recbtn" data-act="sysmic" style="margin-top:10px;background:var(--ink);color:#fff">Открыть настройки</button>`; return render() }
  if (S.w.mic==='call'){ O.live=`<span class="st e">Микрофон занят звонком</span>${sub('Пока — пишите.')}`; return render() }
  if (!curMode().accepts.voice){ O.live=`<span class="st">Режим без голоса</span>${sub('Пишите или пришлите фото.')}`; return render() }
  if (isSide||src==='ярлык'){ render(); trace('Боковая кнопка (настройка по умолчанию): лист сразу пишет без рук — само нажатие и есть намерение. Отпускание боковой кнопки приложению недоступно, поэтому не «рация», а замочек: «Отправить» или «В поле».','p'); return startRec('locked',undefined,true) }
  O.live=`<span class="st">Зажмите микрофон и говорите</span>`; O.liveQ=true; trace(`Лист открыт ${src==='side'?'боковой кнопкой':src} и ждёт: без нажатия микрофон не пишет.`,'p'); render();
}
function openCamera(first){
  S.camRet={ov:S.ov, screen:S.screen}; S.cam={subj:S.camSubj||(S.mode==='translate'||S.mode==='m-4c1e'?'menu':'tea'),taken:false,first:!!first}; S.nextKey = S.mode==='translate'?'spicy':'price'; S.screen='cam2';
  trace(first?`Режим «${curMode().title}» открывается сразу камерой (настройка режима). Спуск — снимок уходит сам; зажать микрофон — снимок уйдёт с вопросом.`:'Камера: системная (ACTION_IMAGE_CAPTURE через startAssistantActivity) или своя на CameraX — решит спайк С13.','p'); render() }
function uploadPhoto(img, q, via){
  const modeId=S.mode, F=S.feed; const b={t:'me',x:q||'',via:via||'фото',img,up:{st:S.w.net==='none'?'wait':'up',pct:0,t0:Date.now()},mode:modeId,id:'u'+Date.now()};
  F.push(b); S.pendingUp=b; render(); runUpload(b); return b }
async function runUpload(b){
  const speed=S.upSpeed||'fast', dur=speed==='slow'?7000:1400, MIN=2000; b.up.st=S.w.net==='none'?'wait':'up'; b.up.pct=0; b.up.t0=Date.now(); render();
  while(true){ await wait(200); if(b.up.st==='cancel') return;
    if(S.w.net==='none'){ if(b.up.st!=='wait'){ b.up.st='wait'; render() } continue }
    if(b.up.st==='wait'){ b.up.st='up'; b.up.t0=Date.now() }
    if(b.up.st!=='up') return;
    b.up.pct=Math.min(100,Math.round((Date.now()-b.up.t0)/dur*100));
    if(speed==='fail' && b.up.pct>=60){ b.up.st='fail'; S.keepScroll=true; render(); trace('Загрузка оборвалась. Фото остаётся в разговоре: «Повторить» догрузит его, «✕» уберёт. Добавленный вопрос не потеряется.','e'); return }
    const composing = (S.drafts[b.mode]||'').trim() || (S.rec && S.mode===b.mode);
    if(b.up.pct>=100 && Date.now()-b.up.t0>=MIN && composing){ if(!b.up.hold){ b.up.hold=true; trace('В поле начат вопрос — вложение ждёт его и уйдёт вместе с ним.','p') } S.keepScroll=true; render(); continue }
    if(b.up.pct>=100 && Date.now()-b.up.t0>=MIN){ b.up.st='sent'; if(S.pendingUp===b) S.pendingUp=null; S.keepScroll=true; render(); return deliverMode(b.x,b.via,b.img,b) }
    S.keepScroll=true; render() } }
async function shotGo(q){
  const c=S.cam; const img={kind:'photo',cls:c.subj}; S.screen=S.camRet.screen; S.ov=S.camRet.ov||S.ov; if(!S.ov && S.screen!=='app') S.ov={pop:false}; S.cam=null;
  uploadPhoto(img, q||'', q?'голос · с фото':'фото');
  trace(q?'Снимок и вопрос: сразу обратно в разговор, фото грузится в пузыре.':'Спуск — сразу обратно в разговор. Фото грузится прямо в пузыре: пока грузится (не меньше 2 секунд), можно нажать «✕» или дописать вопрос — уйдёт вместе с фото.','p');
  if(!q) setTimeout(()=>$('#cin')?.focus(),50); }
function closeOverlay(){ token++; S.busy=false; S.ov=null; render() }
/** Разрешение на уведомления спрашиваем, пока лист ещё виден: при первом вопросе мозгу, пока он думает. После закрытия окна спросить уже нечем. */
function maybeAskNotif(){ if (S.w.notif==='ok'||S.notifAsked||!S.ov) return; S.notifAsked=true; S.feed.push({t:'notifask'}); trace('Уведомления спрашиваем, пока лист виден: при закрытии листа окна уже нет, системный запрос показать нечем.','p') }
async function expand(msgId){
  if (!S.ov) return; S.ov.full=true; S.keepScroll=true; render();
  trace('Лист доезжает до полного экрана, затем startAssistantActivity открывает тот же разговор в экране приложения — без анимации перехода; лист закрывается, когда приложение нарисовало первый кадр. Так разворачивается Gemini.','p');
  await wait(380); S.fromSheet={screen:S.screen==='app'?'home':S.screen}; S.ov=null; S.screen='app'; S.onboarded=true; S.page='chat'; S.scrollToMsg=msgId||null; render();
}

