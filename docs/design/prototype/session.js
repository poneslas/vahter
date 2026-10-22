/* Лист ассистента и съёмка: открыть/закрыть, камера, загрузка фото (в Android — VoiceInteractionSession, CameraX в окне листа, загрузка корутиной в процессе, запасной путь WorkManager). */
/* ───────── лист ───────── */
function openOverlay(src){
  if (S.w.locked && S.settings.lockScope==='off' && !S.unlocking){ return lockGate(src) }
  token++; S.busy=false;
  if (S.screen==='cam2'&&S.cam){ S.screen=S.camRet.screen; S.cam=null }   // лист вызвали поверх камеры — камера закрывается, иначе она осталась бы поверх листа
  const isSide = src==='side'||src.startsWith('боков');
  const R=S.settings.returnToAssistant; if (isSide && S.mode!=='main' && R!=='never' && (R==='always' || S.idle>=30 || !curMode().enabled)){ const t=curMode().title; S.mode='main'; trace(R==='always'?`Боковая кнопка всегда открывает Ассистента (настройка). «${t}» — через вкладки или ярлык.`:`Без дела ${S.idle} мин — боковая кнопка снова открывает Ассистента. «${t}» остался во вкладках, лента сохранена.`,'p') }
  if (isSide && S.idle>=30 && S.feed.length>sheetFrom()){ S.sheetFrom={...(S.sheetFrom||{}),[S.mode]:S.feed.length}; trace(`Без дела ${S.idle} мин — лист открывается чистым. Прошлый разговор не стёрт: он в приложении, выше черты «Новый разговор»; из листа — «Прошлый разговор».`,'p') }
  S.idle=0;
  S.rec=null; const O={pop:true}; S.ov=O;
  if (curMode().primary==='photo' && curMode().accepts.photo){ render(); return openCamera(true) }
  const sub=s=>`<span style="font-size:14.5px;font-weight:400;color:var(--soft)">${s}</span>`;
  if (S.w.model!=='ready') return render();   // голоса нет — не слушаем; строка над лентой уже говорит «Голос не скачан · Скачать»
  if (S.w.mic==='ask'){ O.live=`<span class="st">Нужен доступ к микрофону</span><span style="font-size:14.5px;font-weight:400;color:var(--soft)">Спросит приложение — лист на секунду развернётся.</span><br><button class="recbtn" data-act="permmic" style="margin-top:10px;background:var(--ink);color:#fff">Разрешить</button>`; S.pendingSide = src==='side'; trace('Системное окно разрешения из листа на Samsung открывается ПОД листом (пробник, S24). Поэтому разрешение спрашивает приложение: лист разворачивается, окно поверх приложения, потом снова лист.','p'); return render() }
  if (S.w.mic==='denied'){ O.live=`<span class="st e">Нет доступа к микрофону</span>${sub('Пока можно писать. Чтобы говорить, разрешите микрофон в настройках.')}<br><button class="recbtn" data-act="sysmic" style="margin-top:10px;background:var(--ink);color:#fff">Открыть настройки</button>`; return render() }
  if (S.w.mic==='call'){ O.live=`<span class="st e">Микрофон занят звонком</span>${sub('Пока — пишите.')}`; return render() }
  if (!curMode().accepts.voice){ O.live=`<span class="st">Режим без голоса</span>${sub('Пишите или пришлите фото.')}`; return render() }
  if (isSide||src==='ярлык'){ render(); trace('Боковая кнопка (настройка по умолчанию): лист сразу пишет без рук — само нажатие и есть намерение. Отпускание боковой кнопки приложению недоступно, поэтому не «рация», а замочек: «Отправить» или «В поле».','p'); return startRec('locked',undefined,isSide?'side':true) }
  O.live=`<span class="st">Зажмите микрофон и говорите</span>`; O.liveQ=true; trace(`Лист открыт ${src==='side'?'боковой кнопкой':src} и ждёт: без нажатия микрофон не пишет.`,'p'); render();
}
function openCamera(first){
  if (S.w.perms.camera!=='ok'){ const msg=`<span class="st">Нужен доступ к камере</span><span style="font-size:14.5px;font-weight:400;color:var(--soft)">Спросит приложение — лист на секунду развернётся.</span><br><button class="recbtn" data-permfix="camera" style="margin-top:10px;background:var(--ink);color:#fff">Разрешить</button>`;
    if (!S.ov) S.ov={pop:false}; S.ov.live=msg; S.ov.liveQ=false; trace('Камера в листе тоже требует разрешения. Окно разрешения из листа на S24 уходит под лист — спрашиваем через приложение, потом камера в листе стартует без повторного вопроса.','p'); return render() }
  S.camRet={ov:S.ov, screen:S.screen}; S.cam={subj:S.camSubj||(S.mode==='translate'||S.mode==='m-4c1e'?'menu':'tea'),taken:false,first:!!first,tool:null,frozen:false,marks:[],crop:null,frames:[],editIdx:null}; S.nextKey = S.mode==='translate'?'spicy':'price'; S.screen='cam2';
  trace(first?`Режим «${curMode().title}» открывается сразу камерой (настройка режима). Спуск — снимок уходит сам; зажать микрофон — снимок уйдёт с вопросом.`:'Камера — CameraX прямо в листе: на эмуляторе видоискатель через ~320 мс после вызова (пробник). Запасной путь — своя активность, ~530 мс.','p'); render() }
function uploadPhoto(img, q, via){
  const modeId=S.mode, F=S.feed; const b={t:'me',x:q||'',via:via||'фото',img,up:{st:S.w.net==='none'?'wait':'up',pct:0,t0:Date.now()},mode:modeId,id:'u'+Date.now()};
  F.push(b); S.pendingUp=b; render(); runUpload(b); return b }
/** Подпись состояния вложения: у прямого API загрузка — это сам запрос, у Hermes — файлы на сервер. */
function upLabel(b){ const u=b.up, api=brainOf(modeById(b.mode)).kind==='api', n=b.img.kind==='album'?b.img.items.length:1;
  if (u.pct<8) return 'Готовлю'+(n>1?` ${n} кадра`:'')+'…';
  return (api?'Отправляю':'Загружаю')+` · ${u.pct} %` }
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
/** Лента кадров: «＋» оставляет текущий кадр и возвращает живой видоискатель; «Отправить» оставляет текущий и отправляет всё. */
const camMax = () => brainOf().caps.maxImages||10;
/** Пропорции снимка = пропорции видоискателя в момент съёмки: в листе он шире, чем на весь экран (400×820). */
const vfAspect=()=>{ const v=$('#scr .vf'); const r=v&&v.getBoundingClientRect(); return r&&r.height?r.width/r.height:400/820 };
function camCurrent(){ const c=S.cam; return {cls:c.subj,marks:c.marks.slice(),crop:c.crop,ar:vfAspect()} }
function camLive(){ Object.assign(S.cam,{frozen:false,tool:null,marks:[],crop:null,editIdx:null}) }
/** Положить текущий кадр в ленту (или сохранить правки кадра из ленты). Возвращает все кадры. */
function camCommit(forSend){ const c=S.cam;
  if (c.editIdx!=null) c.frames[c.editIdx]=camCurrent();
  else if (forSend || c.frozen || true) c.frames.push(camCurrent());
  return c.frames.slice() }
function camAdd(){ const c=S.cam; if (c.editIdx==null && c.frames.length>=camMax()-1){ trace(`Предел мозга — ${camMax()} картинок в одной реплике. «Отправить» добавит этот кадр и отправит всё.`,'e'); return }
  camCommit(false); camLive(); if (c.subj==='menu') { c.subj='menu2'; trace('В прототипе видоискатель «перевернул страницу» — следующий кадр будет второй страницей меню.','p') } render() }
function camOpenFrame(i){ const c=S.cam; if (c.frozen && c.editIdx==null) c.frames.push(camCurrent());
  const f=c.frames[i]; Object.assign(c,{frozen:true,tool:null,subj:f.cls,marks:f.marks.slice(),crop:f.crop,editIdx:i}); render() }
function camDelFrame(i){ const c=S.cam; c.frames.splice(i,1); if (c.editIdx===i) camLive(); else if (c.editIdx>i) c.editIdx--; render() }
/** Инструменты на видоискателе: выбор инструмента замораживает кадр; пометки и обрезка впекаются в снимок перед загрузкой. */
function camTool(t){ const c=S.cam; if(!c.frozen){ c.frozen=true; trace('Инструмент нажат — кадр зафиксирован, как спуск. Дальше рисуете поверх и «Отправить».','p') } c.tool = c.tool===t ? null : t; render() }
let draw=null;
function camPoint(e){ const r=$('#scr .cam2 .vf').getBoundingClientRect(); return [Math.max(0,Math.min(100,(e.clientX-r.left)/r.width*100)), Math.max(0,Math.min(100,(e.clientY-r.top)/r.height*100))] }
document.addEventListener('pointerdown',e=>{ if(!e.target.closest('[data-draw]')||!S.cam||!S.cam.tool) return; e.preventDefault(); const p=camPoint(e); const t=S.cam.tool;
  draw = t==='circle' ? {k:'circle',pts:[p]} : t==='arrow' ? {k:'arrow',a:p,b:p} : {k:'crop',a:p,b:p}; S.cam.draft=draw; render() });
document.addEventListener('pointermove',e=>{ if(!draw||!S.cam) return; const p=camPoint(e); if(draw.k==='circle') draw.pts.push(p); else draw.b=p; render() });
document.addEventListener('pointerup',()=>{ if(!draw||!S.cam) return; const d=draw; draw=null; S.cam.draft=null;
  if (d.k==='crop'){ const x=Math.min(d.a[0],d.b[0]), y=Math.min(d.a[1],d.b[1]), w=Math.abs(d.a[0]-d.b[0]), h=Math.abs(d.a[1]-d.b[1]); if(w>8&&h>8) S.cam.crop={x,y,w,h} }
  else if ((d.k==='circle'&&d.pts.length>3)||(d.k==='arrow'&&Math.hypot(d.a[0]-d.b[0],d.a[1]-d.b[1])>5)) S.cam.marks.push(d);
  render() });
async function shotGo(q){
  const c=S.cam; const cur={cls:c.subj,marks:c.marks.slice(),crop:c.crop,ar:vfAspect()}; const items=camCommit(true); const img=items.length>1?{kind:'album',items}:{kind:'photo',...items[0]}; S.screen=S.camRet.screen; S.ov=S.camRet.ov||S.ov; if(!S.ov && S.screen!=='app') S.ov={pop:false}; S.cam=null;
  uploadPhoto(img, q||'', (q?'голос · ':'')+(items.length>1?`альбом · ${items.length}`:'фото'));
  trace(q?'Снимок и вопрос: сразу обратно в разговор, фото грузится в пузыре.':'Спуск — сразу обратно в разговор. Фото грузится прямо в пузыре: пока грузится (не меньше 2 секунд), можно нажать «✕» или дописать вопрос — уйдёт вместе с фото.','p');
  if(!q) setTimeout(()=>$('#cin')?.focus(),50); }
/** Разрешения — только из приложения: системное окно поверх листа на S24 не показывается (уходит под лист). */
function permInApp(kind){ S.permRet={screen:S.screen==='app'?'home':S.screen, side:S.pendingSide}; S.ov=null; S.screen='app'; S.onboarded=true; S.page='chat';
  S.dialog = kind==='mic' ? {ic:'🎙',title:'Разрешить приложению «Вахтёр» записывать аудио?',opts:[['При использовании приложения','mic_ok'],['Только в этот раз','mic_ok'],['Не разрешать','mic_no','sec2']]}
    : {ic:'🔔',title:'Разрешить приложению «Вахтёр» отправлять уведомления?',opts:[['Разрешить','notif_ok'],['Не разрешать','notif_no','sec2']]};
  render() }
function permBack(){ const R=S.permRet; if(!R) return false; S.permRet=null; S.screen=R.screen; openOverlay(R.side?'side':'снова'); return true }
/** На заблокированном экране по умолчанию ничего не открывается: сначала разблокировка (тот же путь, что проверен на S24), микрофон до неё не включается. */
async function lockGate(src){ S.ov=null; S.unlocking=true; render(); trace('На заблокированном экране — «Не открывать» (по умолчанию): сначала разблокировка, микрофон не включается. Настройки → Действия на телефоне → Экран блокировки.','p');
  await wait(1100); S.unlocking=false; S.w.locked=false; S.screen='home'; openOverlay(src) }
function closeOverlay(){ token++; S.busy=false; S.ov=null; S.sideCue=false;
  if (S.firstClose){ S.firstClose=false; return showToast('Вахтёр на месте','Зажмите боковую кнопку, когда понадобится.',undefined,4000) }   // общий таймер уведомлений: новое не гаснет раньше срока
  render() }
/** Разрешение на уведомления спрашиваем, пока лист ещё виден: при первом вопросе мозгу, пока он думает. После закрытия окна спросить уже нечем. */
function maybeAskNotif(){ if (S.w.notif==='ok'||S.notifAsked||!S.ov) return; S.notifAsked=true; S.feed.push({t:'notifask'}); trace('Уведомления спрашиваем, пока лист виден: при закрытии листа окна уже нет, системный запрос показать нечем.','p') }
async function expand(msgId){
  if (!S.ov) return; const sh=$('#scr .sheet'); S.ov.fromH=sh?sh.offsetHeight:400; S.ov.full=true; S.keepScroll=false; render();
  trace('Лист доезжает до полного экрана, затем startAssistantActivity открывает тот же разговор в экране приложения. Пока лист растёт, сверху проявляется шапка приложения, свечение гаснет — к моменту подмены лист уже выглядит как экран приложения, подмены не видно.','p');
  await wait(380); S.fromSheet={screen:S.screen==='app'?'home':S.screen}; S.ov=null; S.screen='app'; S.onboarded=true; S.page='chat'; S.scrollToMsg=msgId||null; render();
}

