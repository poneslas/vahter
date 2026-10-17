/* Экраны и компоненты (в Android — Compose-экраны). Только рисуют состояние, логики нет. */
/* ───────── общие куски ───────── */
function statusBar(dark){
  const wifi='<svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor"><path d="M12 21 23.6 7A17.6 17.6 0 0 0 12 3 17.6 17.6 0 0 0 .4 7z"/></svg>';
  const cell='<svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M2 22h20V2z"/></svg>';
  const plane='<svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor"><path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z"/></svg>';
  const bat='<svg viewBox="0 0 26 13" width="24" height="12"><rect x=".7" y=".7" width="22" height="11.6" rx="3" fill="none" stroke="currentColor" stroke-opacity=".45" stroke-width="1.2"/><rect x="2.4" y="2.4" width="15.2" height="8.2" rx="1.6" fill="currentColor"/><rect x="23.8" y="4.3" width="1.8" height="4.4" rx=".9" fill="currentColor" fill-opacity=".45"/></svg>';
  const net = S.w.net==='none' ? plane : (S.w.net==='wifi' ? wifi : '') + cell;
  return `<div class="sb ${dark?'d':'l'}"><span>9:41</span><span class="sbi">${net}<span>82%</span>${bat}</span></div><div class="punch"></div>` }
function oursIcon(st=''){ return `<span class="ico ours" style="${st}"><i><b style="height:8px"></b><b style="height:16px"></b><b style="height:22px"></b><b style="height:12px"></b></i></span>` }
const brainOk = () => S.w.server==='paired' && S.w.net!=='none';
function linkState(){
  if (S.w.server==='none') return ['loc','только команды телефона'];
  if (S.w.net==='none') return ['off','нет связи · команды телефона работают'];
  if (S.w.server==='down') return ['off','сервер не отвечает'];
  return ['','на связи'];
}
function issue(){
  if (S.w.mic==='denied') return {k:'e',t:'Нет доступа к микрофону. Пока можно писать.',a:[['Разрешить','askmic']]};
  if (S.w.model==='broken') return {k:'e',t:'Файл распознавания повреждён.',a:[['Скачать заново','dl']]};
  if (S.w.model==='loading') return {k:'p',t:S.w.net==='none'?`Распознавание: ${S.modelPct} % — ждёт сеть`:`Распознавание качается: ${S.modelPct} % из ${mb(MODEL_TOTAL)} МБ`,prog:S.modelPct};
  if (S.w.model==='none') return {k:'p',t:`Чтобы говорить голосом, скачайте распознавание — ${mb(MODEL_TOTAL)} МБ, один раз.`,a:[['Не сейчас','dismiss'],['Скачать','dl']]};
  if (S.w.role!=='ours'||!S.sideOk) return {k:'p',t:S.w.role!=='ours'?`Боковая кнопка открывает ${S.w.role==='bixby'?'Bixby':'Gemini'}.`:'Боковая кнопка ещё не вызывает помощника.',a:[['Не сейчас','dismiss'],['Настроить',S.w.role!=='ours'?'sysrole':'sysside']]};
  if (S.w.server==='none') return {k:'p',t:'Сервер не подключён — работают только команды телефона.',a:[['Не сейчас','dismiss'],['Подключить','connect']]};
  return null;
}
function bannerHtml(){ const b=issue(); if(!b||S.dismissed===b.t) return ''; return `<div class="banner ${b.k==='e'?'e':''}"><span class="bi">${b.k==='e'?I.warn:I.info}</span><p>${b.t}</p>${b.prog!=null?`<div class="prog"><i style="width:${b.prog}%"></i></div>`:''}${b.a?`<div class="ba">${b.a.map(x=>`<button class="tbtn" data-act="${x[1]}">${x[0]}</button>`).join('')}</div>`:''}</div>` }

/* ───────── лента ───────── */
function feedHtml(inSheet){
  if (!S.feed.length && S.mode!=='main') return `<div class="feed"><div class="empty" style="padding:10px 4px">${S.mode==='translate'?'Скажите или напишите — переведу. Можно сфотографировать надпись или меню.':S.mode==='m-4c1e'?'Сфотографируйте меню — выберу под ваши вкусы.':S.mode.startsWith('m-')?'Свой режим: пришлите то, что он ждёт.':'Сфотографируйте товар, вывеску или меню — или сделайте снимок экрана. Коротко объясню, что это.'}</div></div>`;
  if (!S.feed.length) return inSheet ? '<div class="feed"></div>' : `<div class="feed"><div class="empty">Зажмите боковую кнопку в любом приложении — или напишите здесь.<div class="chips" style="margin-top:12px">${['Таймер на пять минут','Что у меня завтра?','Как настроить бэкап сервера?'].map(x=>`<button class="chip" data-saytext="${x}">${x}</button>`).join('')}</div></div></div>`;
  return `<div class="feed">${S.feed.map(it=>itemHtml(it,inSheet)).join('')}</div>`;
}
function itemHtml(it,inSheet){
  if (it.t==='me' && it.img && it.up && it.up.st!=='sent'){ const u=it.up;
    const st = u.st==='wait'?'Ждёт сети — отправлю сам':u.st==='fail'?'Не загрузилось':`Загружаю${u.pct?` · ${u.pct} %`:'…'}`;
    return `<div class="meimg up"><div class="thw"><i class="th ${it.img.cls}"></i>${u.st==='up'?`<i class="upbar" style="width:${u.pct}%"></i>`:''}<button class="upx" data-act="upcancel" data-id="${it.id}" aria-label="Отменить отправку">${I.x}</button></div>
      <small class="upst ${u.st==='fail'?'err':''}">${st}${u.st==='fail'?` · <button class="lnk" data-act="upretry" data-id="${it.id}">Повторить</button>`:''}</small>
      ${it.x?`<div class="me" style="max-width:100%">${esc(it.x)}<small>уйдёт вместе с фото</small></div>`:`<small class="uphint">Можно дописать вопрос — уйдёт вместе с фото</small>`}</div>` }
  if (it.t==='me' && it.img && it.up && it.up.st==='cancel') return '';
  if (it.t==='me' && it.img) return `<div class="meimg"><i class="th ${it.img.cls}"></i>${it.x?`<div class="me" style="max-width:100%">${esc(it.x)}<small>${it.via}</small></div>`:`<small style="font-size:10.5px;color:var(--faint)">${it.via}</small>`}</div>`;
  if (it.t==='me') return `<div class="me">${esc(it.x)}<small>${it.via||'голос'}</small></div>`;
  if (it.t==='ai' && it.speak) return `<div class="ai"><div class="md">${it.x}</div></div><button class="speak" data-act="speaksoon" aria-disabled="true">🔊 Озвучить · скоро</button>`;
  if (it.t==='ai'){
    const clip = it.long && inSheet;
    return `<div class="ai ${it.interim?'interim':''}" data-msg="${it.id||''}"><div class="md ${clip?'clip':''}">${it.x}</div></div>${clip&&it.done?`<button class="more" data-act="expand" data-msg="${it.id}">Развернуть сообщение</button>`:''}${it.done&&it.long&&!clip?`<div class="ansact"><button>Копировать</button><button>Поделиться</button><button>Выделить текст</button></div>`:''}`;
  }
  if (it.t==='sys') return `<div class="sysl">${it.x}</div>`;
  if (it.t==='err') return `<div class="errmsg">${it.x}</div>`;
  if (it.t==='att') return `<div class="msgatt">${attHtml(it.a)}${it.cap?`<div class="cap">${it.cap}</div>`:''}</div>`;
  if (it.t==='ph') return `<div class="ph">${it.x}</div>`;
  if (it.t==='steps') return stepsHtml(it);
  if (it.t==='q') return `<div class="queued">${it.x}<span>${it.s}</span></div>`;
  if (it.t==='chips') return `<div class="chips">${it.c.map(c=>`<button class="chip" data-chip="${c}">${c}</button>`).join('')}</div>`;
  if (it.t==='act') return `<div class="act ${it.undone?'undone':''}"><span class="ai2">${it.i}</span><span class="tx"><b>${it.x}</b><span>${it.s}</span></span>${it.btn&&!it.undone?`<button class="ln" data-undo="${it.id}" data-kind="${it.kind}">${it.btn}</button>`:''}</div>`;
  if (it.t==='card') return `<div class="card"><div class="who ${it.who}">${it.head}</div><div class="bd"><div class="tt">${it.title}</div>${it.kv?`<dl class="kv">${it.kv.map(k=>`<dt>${k[0]}</dt><dd>${k[1]}</dd>`).join('')}</dl>`:''}${it.text?`<p style="margin:6px 0 2px;font-size:13px;color:var(--soft)">${it.text}</p>`:''}${it.code?`<pre>${it.code}</pre>`:''}${it.tmr?`<div class="tmr">${it.tmr}</div>`:''}</div><div class="btns">${it.btns.map(b=>`<button class="cb ${b[2]||''}" data-cardbtn="${b[1]}">${b[0]}</button>`).join('')}</div></div>`;
  return '';
}
function stepsHtml(it){
  const n=it.items.length; const pd=it.plan?it.plan.items.filter(p=>p.st==='completed').length:0;
  const head = it.run ? `<span class="shim">${it.cur}</span>` : `${n} ${n===1?'шаг':n<5?'шага':'шагов'} · ${it.sec} с${it.plan?` · план ${pd} из ${it.plan.items.length}`:''}`;
  return `<div class="steps ${it.open?'open':''}"><button data-act="togglesteps" data-id="${it.id}">${head}${I.chev}</button>
  ${it.plan&&(it.run||it.open)?`<div class="plan"><div class="ph2"><span>План Hermes</span><span>ревизия ${it.plan.rev}</span></div>${it.plan.items.map(p=>`<div class="pi ${p.st} ${it.run?'now':''}"><span class="b">${p.st==='completed'?'✓':''}</span><span>${p.c}</span></div>`).join('')}</div>`:''}
  <div class="sl">${it.items.map(s=>`<div class="sitem ${s.phone?'phone':''}"><div><span class="si">${s.i}</span><span>${s.t}</span><span style="margin-left:auto;color:var(--faint);font-size:11px">${s.ms||''}</span></div>${s.d?`<div class="sd">${s.d}</div>`:''}</div>`).join('')}</div></div>`;
}
function attHtml(a){
  return `<div class="att">${a.map(x=>{
    if (x.k==='imgs'){ const n=x.n||4; const show=Math.min(n,4); return `<div class="imgs" style="grid-template-columns:repeat(${show===3?3:2},1fr)">${Array.from({length:show},(_,i)=>`<i>${i===3&&n>4?`<em class="more4">+${n-4}</em>`:''}</i>`).join('')}</div>` }
    if (x.k==='chart') return `<div class="chart"><div style="font-size:12px;color:var(--soft);margin-bottom:4px">${x.title}</div><svg viewBox="0 0 300 100" preserveAspectRatio="none"><polyline fill="none" stroke="#2F4BE8" stroke-width="2.5" points="0,80 40,72 80,76 120,56 160,58 200,38 240,42 300,18"/><polyline fill="none" stroke="#E8A317" stroke-width="2.5" stroke-dasharray="6 4" points="0,86 60,82 120,78 180,74 240,70 300,66"/></svg><div class="lg"><span><i style="background:#2F4BE8"></i>занято сейчас</span><span><i style="background:#E8A317"></i>с бэкапами, прогноз</span></div></div>`;
    const col={PDF:'#C2412D',XLSX:'#2E7D4F',ZIP:'#4A5670'}[x.type]||'#4A5670';
    return `<div class="file"><span class="fi" style="background:${col}">${x.type}</span><span class="ft"><b>${x.name}</b><span>${x.meta}</span></span><button class="fa" data-act="openfile" data-name="${x.name}">${x.btn||'Открыть'}</button></div>`;
  }).join('')}</div>`;
}

/* ───────── экраны ───────── */
function home(){
  const apps=[['📞','#2F9E5B','Телефон'],['💬','#3B82F6','Сообщения'],['✈️','#2AABEE','Telegram','tg'],['📷','#555','Камера'],['⏰','#1F2937','Часы'],['📅','#E05D44','Календарь'],['⚙️','#6B7280','Настройки'],['ours','','Тася','app']];
  return `<div class="layer home">${statusBar(false)}<div class="clk">9:41</div><div class="dt">пятница, 17 октября</div>
  <div class="grid">${apps.map(a=>`<button class="app" data-open="${a[3]||''}">${a[0]==='ours'?oursIcon():`<span class="ico" style="background:${a[1]}">${a[0]}</span>`}${a[2]}</button>`).join('')}</div>
  <div class="dock"><span class="ico" style="background:#2F9E5B">📞</span><span class="ico" style="background:#3B82F6">💬</span><span class="ico" style="background:#F59E0B">🌐</span><span class="ico" style="background:#555">📷</span></div></div>`;
}
function lock(){ return `<div class="layer lock">${statusBar(false)}<div class="clk">9:41</div><div class="dt">пятница, 17 октября</div><button class="unl" data-act="unlock">Разблокировать отпечатком</button></div>` }

const voiceOk = () => S.w.model==='ready' && S.w.mic==='ok';
function recTime(){ const s=S.rec?Math.floor((Date.now()-S.rec.t0)/1000):0; return `0:${String(s).padStart(2,'0')}` }
function liveHtml(){
  if (S.rec){ const ph=PHR[S.rec.key]; const n=S.rec.n; const fx=Math.max(0,n-2);
    const lbl = S.rec.into==='append' ? (S.rec.mode==='hold'?'Дописываю в поле · отпустите — допишется':'Дописываю в поле · «В поле» или «Отправить» всё') : S.rec.mode==='hold' ? 'Запись · отпустите — отправится' : 'Запись без рук · «Отправить» или «В поле»';
    return `<div class="live"><span class="st p">${lbl}</span>${n?ph.w.slice(0,n).map((w,j)=>`<span class="${j>=fx?'dr':''}">${w}</span>`).join(' '):'<span class="dr">…</span>'}</div>` }
  if (S.ov&&S.ov.live) return `<div class="live ${S.ov.liveQ?'q':''}">${S.ov.live}</div>`;
  return '';
}
function modesBar(){ return `<div class="modes" role="tablist" aria-label="Режимы">${MODES.map(m=>`<button class="mchip" role="tab" data-mode="${m.id}" aria-pressed="${S.mode===m.id}"><span class="mi">${m.icon}</span>${m.title}</button>`).join('')}<button class="mchip add" data-act="modes" aria-label="Настроить режимы">+</button></div>` }
function attDraftHtml(){ const a=S.draftImg; if(!a) return ''; return `<div class="attdraft"><i class="th ${a.cls}"></i><span>${a.kind==='screen'?'Снимок экрана':'Фото'} · можно добавить вопрос голосом или текстом</span><button class="iconbtn" data-act="dropimg" aria-label="Убрать">${I.x}</button></div>` }
function modeTabs(){ const am=activeModes(); return `<div class="mtabs" data-swipe role="tablist" aria-label="Режимы">${am.map(m=>`<button role="tab" data-pickmode="${m.id}" aria-pressed="${S.mode===m.id}">${m.title}</button>`).join('')}<button class="madd" data-act="addmode" aria-label="Новый режим">+</button></div>` }
function modePill(){ const am=activeModes(); if(am.length<2) return ''; const m=curMode(); return `<button class="mpill" data-act="modepick" aria-label="Режим: ${m.title}. Нажмите — список, свайп по строке — следующий">${m.icon}</button>` }
function modePicker(){ if(!S.modePick) return ''; return `<div class="mpicker" role="menu">${activeModes().map(m=>`<button data-pickmode="${m.id}" aria-pressed="${S.mode===m.id}"><span class="mi2">${m.icon}</span><span><b>${m.title}</b><small>${m.id==='main'?'обычный разговор':(S.camFirst[m.id]?'сразу камера':'голос, текст'+(m.photo?', фото':''))}</small></span></button>`).join('')}<button data-act="modes"><span class="mi2">⚙</span><span><b>Настроить режимы</b></span></button></div>` }
function composerHtml(){
  const ph=S.w.server==='paired'?'Написать Hermes':'Команда телефону';
  const mic=`<button class="micbtn ${voiceOk()?'':'off'} ${S.rec&&S.rec.mode==='hold'?'big':''}" data-mic aria-label="Зажмите, чтобы говорить; короткое нажатие — запись без рук">${I.mic}</button>`;
  if (S.rec && S.rec.mode==='hold'){ const g=S.rec.g||{x:0,y:0}; const fresh=Date.now()-S.rec.t0<250;
    return `<div class="cwrap"><div class="lockcue ${fresh?'in':''} ${g.y>22?'near':''}" style="--ly:${Math.min(g.y,45)}px">${I.lock||'🔒'}<span class="arr">⌃</span></div><div class="composer rec"><div class="fld"><span class="rdot"></span><span class="tm">${recTime()}</span><div class="eq sm" data-mode="speak"></div><span class="slide" style="opacity:${Math.max(.25,1-g.x/70)}">‹ влево — отмена</span></div><div class="micwrap" style="transform:translate(${-Math.min(g.x,70)}px,${-Math.min(g.y,45)}px)">${mic}</div></div>${modeTabs()}</div>` }
  if (S.rec) return `<div class="cwrap"><div class="composer rec"><button class="iconbtn" data-act="reccancel" aria-label="Отменить запись">${I.x}</button><div class="fld"><span class="rdot"></span><span class="tm">${recTime()}</span><div class="eq sm" data-mode="speak"></div></div><button class="recbtn" data-act="recfield">В поле</button><button class="sendbtn" data-act="recsend" aria-label="Отправить">${I.send}</button></div>${modeTabs()}</div>`;
  const m=curMode(); const photoMain=m.photo&&S.camFirst[m.id], vOn=S.voiceOn[m.id]!==false;
  const tools=(photoMain?(vOn?`<button class="intool" data-mic aria-label="Зажмите и спросите голосом">${I.mic}</button>`:''):(m.photo?`<button class="intool" data-act="camera" aria-label="Сфотографировать">${I.cam}</button>`:''))+(m.screen?`<button class="intool" data-act="screenshot" aria-label="Снимок экрана под листом">${I.shot}</button>`:'');
  const big = photoMain ? `<button class="micbtn" data-act="camera" aria-label="Камера">${I.cam}</button>` : vOn ? mic : '';
  const canSend = S.draft || S.draftImg;
  return `<div class="cwrap">${modePicker()}${attDraftHtml()}<div class="composer"><div class="fld" style="${tools?'padding-left:4px':''}">${tools}<input id="cin" value="${esc(S.draft)}" placeholder="${S.draft?'':S.pendingUp&&S.pendingUp.mode===S.mode&&S.pendingUp.up.st!=='sent'?'Вопрос к фото — уйдёт вместе с ним':(m.id==='main'?ph:m.id==='translate'?'Что перевести?':'Вопрос к фото')}" autocomplete="off">${canSend?`<button class="sendbtn" data-act="csend" aria-label="Отправить">${I.send}</button>`:''}</div>${big}</div>${modeTabs()}</div>`;
}
function chat(){
  const [d,t]=linkState();
  return `<div class="layer appv">${statusBar(true)}
  <div class="topbar">${S.fromSheet?`<button class="iconbtn" data-act="collapse" aria-label="Свернуть в лист">${I.down}</button>`:''}<div class="t"><b>${curMode().title}</b><small><span class="ldot ${d}"></span>Hermes · ${t}</small></div>
    <button class="iconbtn" data-act="settings" aria-label="Настройки">${I.gear}</button></div>
  ${bannerHtml()}
  <div class="scroll" id="scroll">${feedHtml(false)}</div>
  ${S.ov?'':liveHtml()}${S.ov?'':composerHtml()}</div>`;
}
function li(icon,title,sum,act,warn,trail){ return `<button class="li ${warn?'warn':''}" ${act?`data-act="${act}"`:''}><span class="lic">${warn?I.warn:icon}</span><span class="lt"><b>${title}</b>${sum?`<small>${sum}</small>`:''}</span>${trail||''}</button>` }
function settings(){
  const w=S.w;
  return `<div class="layer appv">${statusBar(true)}
  <div class="topbar"><button class="iconbtn" data-act="back" aria-label="Назад">${I.back}</button><div class="t"><b>Настройки</b></div></div>
  <div class="scroll"><div class="list">
    <div class="lh">Вызов</div>
    ${li(I.spark,'Цифровой помощник', w.role==='ours'?'Тася':`Сейчас ${w.role==='bixby'?'Bixby':'Gemini'}`,'sysrole',w.role!=='ours')}
    ${li(I.btn,'Боковая кнопка', S.sideOk?'Зажатие вызывает помощника':'Зажатие открывает меню выключения','sysside',!S.sideOk)}
    ${li(I.hist,'Возвращаться к Ассистенту', {'30':'Через 30 минут без дела','never':'Никогда — боковая кнопка открывает последний режим','now':'Сразу — боковая кнопка всегда открывает Ассистента'}[S.stick],'stick')}
    <div class="lh">Режимы</div>
    ${allModes().map(m=>`<div class="lirow">${li(m.icon,m.title,m.sum,'editmode:'+m.id)}${m.id==='main'||m.custom?'':`<button class="swbtn" data-toggle="modeOn:${m.id}" role="switch" aria-checked="${!!S.modeOn[m.id]}" aria-label="${m.title}"><span class="sw ${S.modeOn[m.id]?'':'off'}"></span></button>`}</div>`).join('')}
    ${li(I.plus,'Новый режим','Название, промпт, что принимает','addmode')}
    <div class="lh">Голос</div>
    ${li(I.wave,'Распознавание речи', w.model==='ready'?`На телефоне · ${mb(MODEL_TOTAL)} МБ`:w.model==='loading'?`Качается: ${S.modelPct} %`:w.model==='broken'?'Файл повреждён':'Не скачано · '+mb(MODEL_TOTAL)+' МБ','dl',w.model==='none'||w.model==='broken')}
    ${li(I.mic,'Микрофон', w.mic==='ok'?'Только пока открыт лист':w.mic==='denied'?'Запрещён':'Спросим, когда понадобится','askmic',w.mic==='denied')}
    <div class="lh">Сервер</div>
    ${li(I.srv,'Подключение', w.server==='paired'?'hermes.example.ru':w.server==='down'?'hermes.example.ru — не отвечает':'Не подключено','connect',w.server!=='paired')}
    ${li(I.bell,'Уведомления', w.notif==='ok'?'Когда Hermes ответил, а лист закрыт':'Выключены','asknotif',false,`<span class="sw ${w.notif==='ok'?'':'off'}"></span>`)}
    <div class="lh">Действия</div>
    ${li(I.shield,'Что можно телефону и Hermes','16 действий')}
    ${li(I.list,'Журнал действий','Сегодня — 3')}
    <div class="divider"></div>
    ${li(I.info,'О программе', S.upd.state==='available'?`Доступна версия ${S.upd.next}`:`Версия ${S.upd.ver}`,'about',false, S.upd.state==='available'?'<span class="ldot loc"></span>':'')}
  </div></div></div>`;
}
function modePage(){ const m=allModes().find(x=>x.id===S.editMode); if(m.custom){ S.camFirst[m.id]??=false; S.sendOnShot[m.id]??=false }
  const chip=(on,t)=>`<span class="mchip" aria-pressed="${on}" style="cursor:default">${t}</span>`;
  return `<div class="layer appv">${statusBar(true)}<div class="topbar"><button class="iconbtn" data-act="settings" aria-label="Назад">${I.back}</button><div class="t"><b style="display:flex;align-items:center;gap:8px">${m.icon}${m.title}</b><small>${m.custom?'идентификатор '+m.id+' — не меняется':'отдельный разговор в Hermes'}</small></div></div>
  <div class="scroll"><div style="padding:0 18px 20px;display:flex;flex-direction:column;gap:12px">
    <div><div class="lh" style="padding:4px 0">Что делать</div>${m.id==='main'?'<p style="margin:0;color:var(--soft);font-size:13.5px">Основной режим: без своего промпта, все действия телефона, сюда приходят ответы по расписанию.</p>':`<textarea class="fieldarea" data-prompt="${m.id}" rows="1">${esc(m.prompt)}</textarea>
      <div class="dict">${S.rec&&S.rec.into==='prompt'
        ? (S.rec.mode==='locked'
            ? `<button class="iconbtn" data-act="reccancel" aria-label="Отменить">${I.x}</button><span class="dlive"><span class="rdot"></span>${esc(recText())||'Слушаю…'}</span><button class="recbtn" data-act="recsend" style="background:var(--ink);color:#fff">Готово</button>`
            : `<button class="dmic on" data-mic data-into="prompt" aria-label="Говорите">${I.mic}</button><span class="dlive"><span class="rdot"></span>${esc(recText())||'Слушаю…'}</span>`)
        : `<button class="dmic" data-mic data-into="prompt" aria-label="Надиктовать">${I.mic}</button><span class="dhint">Зажмите и надиктуйте — допишется в конец. Можно поправить руками.</span>`}</div>`}</div>
    <div><div class="lh" style="padding:4px 0">Принимает</div><div class="chips">${m.id==='main'?chip(true,I.mic+'Голос'):`<button class="mchip" data-toggle="voiceOn:${m.id}" aria-pressed="${S.voiceOn[m.id]!==false}">${I.mic}Голос</button>`}${chip(true,I.kbd+'Текст')}${chip(m.photo,I.cam+'Фото')}${chip(m.screen,I.shot+'Снимок экрана')}</div></div>
    ${m.photo?`<div><div class="lh" style="padding:4px 0">Главное</div><div class="seg2">${[[false,I.mic+'Голос'],[true,I.cam+'Фото']].map(([v,t])=>`<button data-primary="${m.id}:${v}" aria-pressed="${!!S.camFirst[m.id]===v}">${t}</button>`).join('')}</div><p style="margin:6px 0 0;font-size:12.5px;color:var(--soft)">${S.camFirst[m.id]?'Большая кнопка — камера. Открывается сразу видоискателем; спросить голосом — микрофон в поле или рядом со спуском.':'Большая кнопка — микрофон. С боковой кнопки сразу слушает.'}</p></div>
    `:''}
    <div class="setcard">${li(I.shield,'Действия телефона',m.tools?'Все — таймер, календарь, будильник…':'Выключены — режиму не нужны, ответы быстрее','',false,`<span class="sw ${m.tools?'':'off'}"></span>`)}${li(I.wave,'Язык речи','Русский (GigaAM). Другие языки — позже','',false,'')}${li(I.wave,'Озвучка ответа','Позже: язык и голос для каждого режима','',false,'<span class="sw off"></span>')}</div>
    <p style="margin:0;font-size:12.5px;color:var(--soft)">В Hermes это отдельная сессия: <code>tasya:dm:&lt;телефон&gt;:${m.id==='main'?'':m.id}</code>. Промпт подмешивается в каждый ход — сброс сессии его не теряет.</p>
  </div></div></div>` }
function about(){
  const u=S.upd;
  return `<div class="layer appv">${statusBar(true)}<div class="topbar"><button class="iconbtn" data-act="settings" aria-label="Назад">${I.back}</button><div class="t"><b>О программе</b></div></div>
  <div class="scroll"><div style="padding:0 16px">
    <div style="text-align:center;padding:10px 0 14px">${oursIcon('width:64px;height:64px;border-radius:20px;margin:0 auto')}<div style="font-family:var(--display);font-size:24px;margin-top:10px">${u.ver}</div><div style="color:var(--soft);font-size:13px">стабильная версия</div></div>
    ${u.state==='available'?`<div class="card"><div class="who p">Есть версия ${u.next}</div><div class="bd"><ul style="margin:0;padding-left:18px"><li>Уведомление, когда Hermes ответил</li><li>Таблицы и код в ответах</li><li>Лист открывается быстрее</li></ul></div><div class="btns"><button class="cb pri" data-act="upd">Обновить</button><button class="cb gh">Позже</button></div></div>`:''}
    ${u.state==='dl'?`<div class="card"><div class="who p">Скачиваю ${u.next}</div><div class="bd"><div class="prog"><i style="width:${u.pct}%"></i></div><div style="font-size:12px;color:var(--soft);margin:6px 0">${u.pct} %</div></div><div class="btns"><button class="cb" data-act="updcancel">Отменить</button></div></div>`:''}
    ${u.state==='checking'?'<div class="note">Проверяю…</div>':''}${u.state==='latest'?'<div class="note ok">Это последняя версия</div>':''}${u.state==='fail'?'<div class="note bad">Не удалось проверить: нет связи</div>':''}
  </div>
  <div class="list">
    ${li(I.dl,'Проверить обновления','Сами проверяем при запуске и раз в 6 часов','check')}
    ${li(I.wave,'Модели распознавания', S.w.model==='ready'?'GigaAM v3 · потоковая · определение речи':'Не скачаны','dl')}
  </div></div></div>`;
}

/* первый запуск: 5 шагов, Material-навигация внизу */
const ONB=['welcome','button','voice','server','done'];
function navBar(skip,primary){ return `<div class="nav">${skip?`<button class="tbtn" data-act="${skip[1]}">${skip[0]}</button>`:'<span style="width:60px"></span>'}<div class="dots">${ONB.map((_,i)=>`<i class="${i===S.onbStep?'on':''}"></i>`).join('')}</div>${primary?`<button class="fbtn" data-act="${primary[1]}">${primary[0]}</button>`:'<span style="width:60px"></span>'}</div>` }
function onb(){
  const st=ONB[S.onbStep];
  if (st==='server') return `<div class="layer appv">${statusBar(true)}<div class="onb" style="padding-top:48px"><div style="flex:1;overflow:auto">${connectBody(true)}</div>${navBar(S.pair?.stage==='done'?null:['Позже','onbnext'], S.pair?.stage==='done'?['Далее','onbnext']:null)}</div></div>`;
  let art='',h='',p='',extra='',primary=['Далее','onbnext'],skip=['Позже','onbnext'];
  if (st==='welcome'){ art=oursIcon('width:96px;height:96px;border-radius:30px'); h='Зажал кнопку — сказал — готово'; p='Таймеры, будильники, фонарик — телефон делает сам, даже без интернета. Остальное отвечает ваш агент на сервере.'; skip=null; primary=['Начать','onbnext'] }
  if (st==='button'){ const ok=S.w.role==='ours'&&S.sideOk; art='<div style="font-size:64px">⏻</div>'; h='Боковая кнопка'; p='Android разрешает выбрать помощника только в настройках. Я открою нужные экраны.';
    extra=`<div class="setcard">${li(I.spark,'Цифровой помощник',S.w.role==='ours'?'Тася':'Сейчас '+(S.w.role==='bixby'?'Bixby':'Gemini'),'sysrole',false,S.w.role==='ours'?I.ok:'')}${li(I.btn,'Боковая кнопка → помощник',S.sideOk?'Готово':'Зажатие и удержание','sysside',false,S.sideOk?I.ok:'')}</div>`; if(ok) skip=null }
  if (st==='voice'){ art='<div style="font-size:64px;letter-spacing:-4px">▂▅▇▅▂</div>'; h='Голос на телефоне'; p=`Один раз скачать ${mb(MODEL_TOTAL)} МБ. Голос не уходит с телефона и работает без интернета.`;
    if (S.w.model==='ready') extra='<div class="note ok">Готово</div>';
    else if (S.w.model==='loading') extra=`<div class="prog"><i style="width:${S.modelPct}%"></i></div><p style="font-size:13px;margin-top:8px">${S.w.net==='none'?'Ждёт сеть — продолжит с того же места.':`${S.modelPct} % — можно идти дальше, докачается само.`}</p>`;
    if (S.w.model==='none'||S.w.model==='broken') primary=['Скачать','dl']; else skip=null }
  if (st==='done'){ art='<div style="font-size:64px">✓</div>'; h='Готово'; p = S.w.role==='ours'&&S.sideOk ? 'Зажмите боковую кнопку в любом приложении и скажите «Таймер на минуту».' : 'Лист можно открыть отсюда. Боковую кнопку настроите потом — шестерёнка, «Вызов».'; primary=['Открыть','onbdone']; skip=null }
  return `<div class="layer appv">${statusBar(true)}<div class="onb"><div class="art">${art}</div><h2>${h}</h2><p>${p}</p>${extra}${navBar(skip,primary)}</div></div>`;
}
function connectPage(){ return `<div class="layer appv">${statusBar(true)}<div class="topbar"><button class="iconbtn" data-act="settings" aria-label="Назад">${I.back}</button><div class="t"><b>Подключение</b></div></div><div class="scroll"><div style="padding:0 18px 20px">${connectBody(false)}</div></div></div>` }
const CHECKS=[['addr','Адрес'],['net','Соединение'],['tls','Сертификат'],['plugin','Плагин на сервере'],['proto','Версия'],['key','Ключ этого телефона'],['admit','Допуск']];
function connectBody(onb){
  const P=S.pair;
  if (!P) return `<div class="h2">Ваш сервер</div><p style="color:var(--soft);margin:0 0 12px">Hermes отвечает на всё, что сложнее команд телефона.</p>
    <div class="setcard">${li(I.chat,'Ссылка из Telegram','Напишите боту /tasya_pair','viatg')}${li(I.qr,'QR с экрана компьютера','Та же команда на сервере','viaqr')}${li(I.pen,'Ввести адрес','Владелец подтвердит код','viaaddr')}</div>`;
  if (P.via==='addr'&&P.stage==='input') return `<div class="h2">Адрес сервера</div>
    <input class="field ${P.err?'err':''}" id="addr" value="${esc(P.addr||'hermes.example.ru')}" autocomplete="off" spellcheck="false">
    <p style="font-size:12.5px;color:${P.err?'var(--danger)':'var(--soft)'}">${P.err||'Например, hermes.example.ru'}</p>
    <div style="display:flex;justify-content:flex-end;gap:6px"><button class="tbtn" data-act="pairreset">Назад</button><button class="fbtn" data-act="addrgo">Подключиться</button></div>`;
  const ck=P.checks.map(c=>`<div class="ck ${c.s}"><span class="s">${c.s==='ok'?'✓':c.s==='bad'?'!':''}</span><span>${c.t}${c.d?`<small>${c.d}</small>`:''}</span></div>`).join('');
  let tail='';
  if (P.stage==='code') tail=`<div class="card"><div class="who h">Ждёт подтверждения</div><div class="bd"><div class="big">${P.code}</div><p style="text-align:center;font-size:12.5px;color:var(--soft);margin:0 0 6px">Код живёт час. На сервере:</p><p style="text-align:center;margin:0"><code>hermes pairing approve tasya ${P.code.replace('-','')}</code></p></div><div class="btns" style="justify-content:center"><button class="cb pri" data-act="copycmd">Скопировать команду</button><button class="cb gh" data-act="pairreset">Отмена</button></div></div>`;
  if (P.stage==='fail') tail=`<div class="note bad">${P.err}</div><div style="display:flex;justify-content:flex-end;gap:6px;margin-top:10px"><button class="tbtn" data-act="pairreset">Другой способ</button><button class="fbtn" data-act="${P.retry}">${P.retryLabel}</button></div>`;
  if (P.stage==='done') tail=`<div class="note ok">Подключено. На сервере этот телефон виден как «S24 Ultra».</div>${onb?'':'<div style="display:flex;justify-content:flex-end;margin-top:10px"><button class="fbtn" data-act="tochat">К разговору</button></div>'}`;
  return `<div class="h2">${P.stage==='done'?'Подключено':'Подключаюсь'}</div><div class="checks">${ck}</div>${tail}`;
}
function sysRole(){ const o=[['gemini','Gemini'],['bixby','Bixby'],['ours','Тася']]; return `<div class="layer sys">${statusBar(true)}<div class="sbar"><button class="iconbtn" data-act="sysback">${I.back}</button>Цифровой помощник</div><p style="margin:0 22px 6px;font-size:13px;color:#666">Настройки → Приложения → Выбор приложений по умолчанию</p><div class="grp">${o.map(x=>`<button class="it" data-role="${x[0]}"><span class="rad ${S.w.role===x[0]?'on':''}"></span>${x[1]}</button>`).join('')}</div></div>` }
function sysMic(){ const o=(v,t)=>`<button class="it" data-micperm="${v}"><span class="rad ${S.w.mic===v?'on':''}"></span>${t}</button>`;
  return `<div class="layer sys">${statusBar(true)}<div class="sbar"><button class="iconbtn" data-act="sysback">${I.back}</button>Микрофон</div><p style="margin:0 22px 6px;font-size:13px;color:#666">Приложения → Тася → Разрешения → Микрофон</p><div class="grp">${o('ok','Разрешить только во время использования приложения')}${o('ask','Спрашивать каждый раз')}${o('denied','Запретить')}</div></div>` }
function sysSide(){ return `<div class="layer sys">${statusBar(true)}<div class="sbar"><button class="iconbtn" data-act="sysback">${I.back}</button>Боковая кнопка</div><p style="margin:0 22px 6px;font-size:13px;color:#666">Настройки → Дополнительные функции → Боковая кнопка</p><p style="margin:14px 24px 4px;font-size:13px;color:#3E6FD8;font-weight:600">Нажатие и удержание</p><div class="grp"><button class="it" data-side="1"><span class="rad ${S.sideOk?'on':''}"></span>Цифровой помощник</button><button class="it" data-side="0"><span class="rad ${S.sideOk?'':'on'}"></span>Меню выключения</button></div></div>` }
function tg(){ return `<div class="layer tg">${statusBar(false)}<div class="top"><button class="iconbtn" data-act="home" style="color:#fff">${I.back}</button><span class="av">H</span><span><b>Hermes</b><br><small style="opacity:.8">бот</small></span></div><div class="msgs" id="msgs">${S.tg.map(m=>`<div class="bub ${m.me?'me':''}">${m.html}</div>`).join('')}</div><div class="compose"><span class="in">${S.tg.length?'Сообщение':'/tasya_pair'}</span><button class="cb pri" data-act="tgsend" ${S.tg.length?'disabled':''}>➤</button></div></div>` }
function cam(){ return `<div class="layer cam">${statusBar(false)}<div class="frame"><div class="scanline"></div></div><p style="position:absolute;top:450px;width:100%;text-align:center;font-size:14px">Наведите на QR с экрана компьютера</p></div>` }
function qrSvg(){ let s=''; for(let i=0;i<144;i++){ const r=Math.floor(i/12),c=i%12; const f=(r<3&&c<3)||(r<3&&c>8)||(r>8&&c<3); s+=`<i style="background:${f||((r*7+c*13+r*c)%3===0)?'#111':'#fff'}"></i>` } return `<div class="qr">${s}</div>` }

function cam2(){ const c=S.cam; const m=curMode(); const qrec=S.rec&&S.screen==='cam2';
  return `<div class="layer cam2">${statusBar(false)}
  <div class="modeName">${m.icon} ${m.title}</div>
  <div class="vf" style="top:96px"><i class="th ${c.subj}"></i></div>
  ${qrec?`<div class="q">${PHR[S.rec.key].w.slice(0,S.rec.n).join(' ')||'…'}</div>`:''}
  ${false?`<div class="sending"><span>Отправляю${c.q?' с вопросом':''}…</span><div class="bar"><i></i></div><button class="recbtn" data-act="shotcancel" style="background:rgba(255,255,255,.18);color:#fff">Отмена</button></div>`
   : c.taken?`<div class="after"><button data-act="retake">Переснять</button><button data-act="addq">Добавить вопрос</button><button class="pri" data-act="sendshot">Отправить</button></div>`
   : `<button class="cx" data-act="camcancel">${c.first?'В лист':'Отмена'}</button><button class="shut" data-act="shoot" aria-label="Снять и отправить"></button><button class="mic2 ${qrec?'on':''}" data-mic aria-label="Зажмите и спросите — снимок уйдёт с вопросом">${I.mic}</button>`}</div>` }
function shortcutsPop(){ if(!S.shortcuts) return ''; return `<div class="sc-pop"><small>Тася</small>${activeModes().map(m=>`<button data-shortcut="${m.id}"><span style="width:22px;display:grid;place-items:center;color:#555">${m.icon}</span>${m.title}</button>`).join('')}</div>` }
function overlay(){
  const O=S.ov; if(!O) return ''; const [d,t]=linkState();
  return `<div class="ov"><div class="dim" data-act="dim" style="${O.full?'opacity:0':''}"></div><div class="sheet ${O.full?'full':''} ${O.pop?'pop':''}">
    <button class="grab" data-act="expand" aria-label="Развернуть"></button>
    <div class="hdr"><span class="ldot ${d}"></span><span><b style="color:var(--ink);font-weight:600">${curMode().title}</b> · ${t}</span><span class="sp"></span>
      <button class="iconbtn" data-act="expand" aria-label="Развернуть в приложение">${I.expand}</button>
      <button class="iconbtn" data-act="gear" aria-label="Настройки">${I.gear}</button>
      <button class="iconbtn" data-act="close" aria-label="Закрыть">${I.x}</button></div>
    <div class="scroll" id="oscroll">${feedHtml(true)}</div>
    ${liveHtml()}${composerHtml()}
  </div></div>`;
}
function dialog(){ const D=S.dialog; if(!D) return ''; return `<div class="scrim"><div class="dlg"><div class="di">${D.ic||''}</div><h3>${D.title}</h3>${D.text?`<p>${D.text}</p>`:''}${D.opts.map((o,i)=>`<button class="opt ${o[2]||''}" data-dlg="${i}">${o[0]}</button>`).join('')}</div></div>` }
function toast(){ const T=S.toast; if(!T) return ''; return `<button class="toast" data-act="toast">${oursIcon()}<span><b>${T.title}</b><span>${T.text}</span></span></button>` }

function grow(t){ t.style.height='auto'; t.style.height=t.scrollHeight+'px' }
function render(){
  let base='';
  if (S.screen==='lock') base=lock();
  else if (S.screen==='home') base=home();
  else if (S.screen==='app') base = !S.onboarded ? onb() : S.page==='settings'?settings():S.page==='mode'?modePage():S.page==='about'?about():S.page==='connect'?connectPage():chat();
  else if (S.screen==='tg') base=tg(); else if (S.screen==='cam') base=cam(); else if (S.screen==='cam2') base=cam2();
  else if (S.screen==='sysrole') base=sysRole(); else if (S.screen==='sysside') base=sysSide(); else if (S.screen==='sysmic') base=sysMic();
  const prev={}; ['#scroll','#oscroll'].forEach(s=>{ const el=$(s); if(el) prev[s]=el.scrollTop });
  const ae=document.activeElement, aeId=ae&&ae.id, aePos=ae&&ae.selectionStart;
  $('#scr').innerHTML = base + (S.screen==='cam2'?'':overlay()) + (S.screen==='home'?shortcutsPop():'') + dialog() + toast();
  document.querySelectorAll('#scr .fieldarea').forEach(grow);
  if (typeof tourCheck==='function') tourCheck();
  document.querySelectorAll('#scr .mtabs').forEach(t=>{ const b=t.querySelector('[aria-pressed=true]'); if(b) t.scrollLeft=b.offsetLeft-(t.clientWidth-b.offsetWidth)/2 });
  if (aeId==='cin'){ const el=$('#cin'); if(el){ el.focus(); try{ el.setSelectionRange(aePos,aePos) }catch{} } }
  if (S.ov) S.ov.pop=false;
  eqs=[...document.querySelectorAll('#scr .eq')].map(e=>new Eq(e));
  ['#scroll','#oscroll','#msgs'].forEach(s=>{ const el=$(s); if(!el) return; if(S.keepScroll && prev[s]!=null) el.scrollTop=prev[s]; else el.scrollTop=el.scrollHeight });
  S.keepScroll=false;
  if (S.scrollToMsg){ const id=S.scrollToMsg; S.scrollToMsg=null; requestAnimationFrame(()=>{ const el=$('#scroll'); const tgt=el&&el.querySelector(`.ai[data-msg="${id}"]`); if(tgt){ const k=el.getBoundingClientRect().height/el.offsetHeight||1; el.scrollTop += (tgt.getBoundingClientRect().top - el.getBoundingClientRect().top)/k - 8 } }) }
  renderWorld();
}

