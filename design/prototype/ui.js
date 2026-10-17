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
const brainOk = () => brainReady() && S.w.net!=='none' && !(brainOf().kind==='hermes'&&S.w.server==='down');
function linkState(){
  const B=brainOf();
  if (!brainReady(B)) return ['loc', B.kind==='api'?'нет ключа · только команды телефона':'сервер не подключён'];
  if (S.w.net==='none') return ['off','нет связи · команды телефона работают'];
  if (B.kind==='hermes'&&S.w.server==='down') return ['off','сервер не отвечает'];
  return ['','на связи'];
}
function issue(){
  if (S.w.mic==='denied') return {k:'e',t:'Нет доступа к микрофону. Пока можно писать.',a:[['Разрешить','askmic']]};
  if (S.w.model==='broken') return {k:'e',t:'Файл распознавания повреждён.',a:[['Скачать заново','dl']]};
  if (S.w.model==='loading') return {k:'p',t:S.w.net==='none'?`Распознавание: ${S.modelPct} % — ждёт сеть`:`Распознавание качается: ${S.modelPct} % из ${mb(MODEL_TOTAL)} МБ`,prog:S.modelPct};
  if (S.w.model==='none') return {k:'p',t:`Чтобы говорить голосом, скачайте распознавание — ${mb(MODEL_TOTAL)} МБ, один раз.`,a:[['Не сейчас','dismiss'],['Скачать','dl']]};
  if (S.w.role!=='ours'||!S.sideOk) return {k:'p',t:S.w.role!=='ours'?`Боковая кнопка открывает ${S.w.role==='bixby'?'Bixby':'Gemini'}.`:'Боковая кнопка ещё не вызывает помощника.',a:[['Не сейчас','dismiss'],['Настроить',S.w.role!=='ours'?'sysrole':'sysside']]};
  if (!brainReady()) return {k:'p',t:brainOf().kind==='api'?'Мозг не подключён — работают только команды телефона.':'Hermes не подключён — работают только команды телефона.',a:[['Не сейчас','dismiss'],['Подключить',brainOf().kind==='api'?'brainsetup':'connect']]};
  return null;
}
function bannerHtml(){ const b=issue(); if(!b||S.dismissed===b.t) return ''; return `<div class="banner ${b.k==='e'?'e':''}"><span class="bi">${b.k==='e'?I.warn:I.info}</span><p>${b.t}</p>${b.prog!=null?`<div class="prog"><i style="width:${b.prog}%"></i></div>`:''}${b.a?`<div class="ba">${b.a.map(x=>`<button class="tbtn" data-act="${x[1]}">${x[0]}</button>`).join('')}</div>`:''}</div>` }

/* ───────── лента ───────── */
function feedHtml(inSheet){
  if (!S.feed.length && curMode().hint && (inSheet || S.mode!=='main')) return `<div class="feed"><div class="empty" style="padding:10px 4px">${esc(curMode().hint)}</div></div>`;
  if (!S.feed.length) return inSheet ? '<div class="feed"></div>' : `<div class="feed"><div class="empty">Зажмите боковую кнопку в любом приложении — или напишите здесь.<div class="chips" style="margin-top:12px">${['Таймер на пять минут','Что у меня завтра?','Как настроить бэкап сервера?'].map(x=>`<button class="chip" data-saytext="${x}">${x}</button>`).join('')}</div></div></div>`;
  return `<div class="feed">${S.feed.map(it=>itemHtml(it,inSheet)).join('')}</div>`;
}
function itemHtml(it,inSheet){
  if (it.t==='me' && it.img && it.up && it.up.st!=='sent'){ const u=it.up;
    const st = u.st==='wait'?'Ждёт сети — отправлю сам':u.st==='fail'?'Не загрузилось':u.pct>=100&&u.hold?'Загружено · ждёт ваш вопрос':u.pct>=100?`Загружено · отправлю через ${Math.max(1,Math.ceil((2000-(Date.now()-u.t0))/1000))} с`:`Загружаю${u.pct?` · ${u.pct} %`:'…'}`;
    if (it.img.kind==='file') return `<div class="meimg up"><div class="fcard">${I.doc}<span><b>${esc(it.img.name)}</b><small>${it.img.size}</small></span><button class="upx" data-act="upcancel" data-id="${it.id}" aria-label="Отменить отправку">${I.x}</button>${u.st==='up'?`<i class="upbar" style="width:${u.pct}%"></i>`:''}</div><small class="upst ${u.st==='fail'?'err':''}">${st}${u.st==='fail'?` · <button class="lnk" data-act="upretry" data-id="${it.id}">Повторить</button>`:''}</small>${it.x?`<div class="me" style="max-width:100%">${esc(it.x)}<small>уйдёт вместе с файлом</small></div>`:`<small class="uphint">Можно дописать вопрос — уйдёт вместе с файлом</small>`}</div>`;
    return `<div class="meimg up"><div class="thw"><i class="th ${it.img.cls}"></i>${u.st==='up'?`<i class="upbar" style="width:${u.pct}%"></i>`:''}<button class="upx" data-act="upcancel" data-id="${it.id}" aria-label="Отменить отправку">${I.x}</button></div>
      <small class="upst ${u.st==='fail'?'err':''}">${st}${u.st==='fail'?` · <button class="lnk" data-act="upretry" data-id="${it.id}">Повторить</button>`:''}</small>
      ${it.x?`<div class="me" style="max-width:100%">${esc(it.x)}<small>уйдёт вместе с фото</small></div>`:`<small class="uphint">Можно дописать вопрос — уйдёт вместе с фото</small>`}</div>` }
  if (it.t==='me' && it.img && it.up && it.up.st==='cancel') return '';
  if (it.t==='me' && it.img && it.img.kind==='file') return `<div class="meimg"><div class="fcard">${I.doc}<span><b>${esc(it.img.name)}</b><small>${it.img.size}</small></span></div>${it.x?`<div class="me" style="max-width:100%">${esc(it.x)}<small>${it.via}</small></div>`:`<small style="font-size:10.5px;color:var(--faint)">${it.via}</small>`}</div>`;
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
  ${it.plan&&(it.run||it.open)?`<div class="plan"><div class="ph2"><span>План</span><span>ревизия ${it.plan.rev}</span></div>${it.plan.items.map(p=>`<div class="pi ${p.st} ${it.run?'now':''}"><span class="b">${p.st==='completed'?'✓':''}</span><span>${p.c}</span></div>`).join('')}</div>`:''}
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
  if (S.rec){ const ph=PHR[S.rec.key]||{w:[]}; const n=S.rec.n; const fx=Math.max(0,n-2);
    const lbl = S.rec.mode==='hold' ? 'Запись · отпустите — отправится' : 'Запись без рук · кнопка справа — отправить';
    return `<div class="live"><span class="st p">${lbl}</span>${n?ph.w.slice(0,n).map((w,j)=>`<span class="${j>=fx?'dr':''}">${w}</span>`).join(' '):'<span class="dr">Слушаю…</span>'}</div>` }
  if (S.ov&&S.ov.live) return `<div class="live ${S.ov.liveQ?'q':''}">${S.ov.live}</div>`;
  return '';
}
function modeTabs(){ const am=activeModes(); return `<div class="mtabs" data-swipe role="tablist" aria-label="Режимы">${am.map(m=>`<button role="tab" data-pickmode="${m.id}" aria-pressed="${S.mode===m.id}">${m.title}</button>`).join('')}<button class="madd" data-act="addmode" aria-label="Новый режим">+</button></div>` }
function attachMenu(){ if(!S.attachMenu) return ''; const m=curMode(), A=m.accepts, photoMain=m.primary==='photo';
  const it=(act,ic,t,s)=>`<button data-act="${act}"><span class="mi2">${ic}</span><span><b>${t}</b><small>${s}</small></span></button>`;
  return `<div class="mpicker" role="menu">${A.photo&&!photoMain?it('camera',I.cam,'Камера','снять и отправить'):''}${A.photo?it('gallery',I.image,'Галерея','системный выбор фото'):''}${A.file?it('file',I.doc,'Файл','PDF, таблица, документ'):''}</div>` }
function composerHtml(){
  const ph=brainReady()?curMode().placeholder:'Команда телефону';
  const mic=`<button class="micbtn ${voiceOk()?'':'off'} ${S.rec&&S.rec.mode==='hold'?'big':''}" data-mic aria-label="Зажмите, чтобы говорить; короткое нажатие — запись без рук">${I.mic}</button>`;
  if (S.rec && S.rec.mode==='hold'){ const g=S.rec.g||{x:0,y:0}; const fresh=Date.now()-S.rec.t0<250;
    return `<div class="cwrap"><div class="lockcue ${fresh?'in':''} ${g.y>22?'near':''}" style="--ly:${Math.min(g.y,45)}px">${I.lock||'🔒'}<span class="arr">⌃</span></div><div class="composer rec"><div class="fld"><span class="rdot"></span><span class="tm">${recTime()}</span><div class="eq sm" data-mode="speak"></div><span class="slide" style="opacity:${Math.max(.25,1-g.x/70)}">‹ влево — отмена</span></div><div class="micwrap" style="transform:translate(${-Math.min(g.x,70)}px,${-Math.min(g.y,45)}px)">${mic}</div></div>${modeTabs()}</div>` }
  // запись без рук, как в Писаре: круг микрофона стал «Отправить»; дёрнуть его влево — отмена
  if (S.rec){ const gx=S.rec.lg||0; return `<div class="cwrap"><div class="composer rec"><div class="fld"><span class="rdot"></span><span class="tm">${recTime()}</span><div class="eq sm" data-mode="speak"></div><button class="slide lnkc" data-act="reccancel" style="opacity:${Math.max(.25,1-gx/70)}">‹ влево — отмена</button></div><div class="micwrap" style="transform:translateX(${-Math.min(gx,70)}px)"><button class="micbtn sendmode" data-recsend aria-label="Отправить; потяните влево — отменить">${I.send}</button></div></div>${modeTabs()}</div>` }
  const m=curMode(), A=m.accepts, photoMain=m.primary==='photo'&&A.photo;
  // слева в поле — только скрепка; справа в поле — отправка (есть текст) или микрофон (если большая кнопка — камера)
  const canAttach = A.file || A.photo;
  const tools = canAttach?`<button class="intool" data-act="attach" aria-label="Прикрепить" aria-expanded="${S.attachMenu}">${I.clip}</button>`:'';
  // есть текст — большая кнопка становится «Отправить» (как в Telegram); в поле справа — только микрофон фото-режима
  const right = S.draft ? '' : (photoMain&&A.voice ? `<button class="intool" data-mic aria-label="Зажмите и спросите голосом">${I.mic}</button>` : '');
  const big = S.draft ? `<button class="micbtn sendmode" data-act="csend" aria-label="Отправить">${I.send}</button>` : photoMain ? `<button class="micbtn" data-act="camera" aria-label="Камера">${I.cam}</button>` : A.voice ? mic : '';
  const up = S.pendingUp&&S.pendingUp.mode===S.mode&&S.pendingUp.up.st!=='sent';
  const place = up ? (S.pendingUp.img.kind==='file'?'Вопрос к файлу':'Вопрос к фото') : m.id==='main' ? ph : m.placeholder;
  return `<div class="cwrap">${attachMenu()}<div class="composer"><div class="fld" style="${tools?'padding-left:4px':''}">${tools}<input id="cin" value="${esc(S.draft)}" placeholder="${S.draft?'':esc(place)}" autocomplete="off">${right}</div>${big}</div>${modeTabs()}</div>`;
}
function chat(){
  const [d,t]=linkState();
  return `<div class="layer appv">${statusBar(true)}
  <div class="topbar">${S.fromSheet?`<button class="iconbtn" data-act="collapse" aria-label="Свернуть в лист">${I.down}</button>`:''}<div class="t"><b>${curMode().title}</b><small><span class="ldot ${d}"></span>${brainOf().title} · ${t}</small></div>
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
    ${li(I.hist,'Возвращаться к Ассистенту', RETURN_OPTS.find(o=>o[0]===S.settings.returnToAssistant)[1],'returnpick')}
    <div class="lh">Режимы</div>
    ${S.modes.map(m=>`<div class="lirow">${li(I[m.icon],esc(m.title),modeSummary(m),'editmode:'+m.id)}${m.id==='main'?'':`<button class="swbtn" data-menable="${m.id}" role="switch" aria-checked="${m.enabled}" aria-label="${esc(m.title)}"><span class="sw ${m.enabled?'':'off'}"></span></button>`}</div>`).join('')}
    ${li(I.plus,'Новый режим','Название, промпт, что принимает','addmode')}
    <div class="lh">Голос</div>
    ${li(I.wave,'Распознавание речи', w.model==='ready'?`На телефоне · ${mb(MODEL_TOTAL)} МБ`:w.model==='loading'?`Качается: ${S.modelPct} %`:w.model==='broken'?'Файл повреждён':'Не скачано · '+mb(MODEL_TOTAL)+' МБ','dl',w.model==='none'||w.model==='broken')}
    ${li(I.mic,'Микрофон', w.mic==='ok'?'Только пока открыт лист':w.mic==='denied'?'Запрещён':'Спросим, когда понадобится','askmic',w.mic==='denied')}
    <div class="lh">Мозги</div>
    ${S.brains.map(b=>li(b.kind==='api'?I.spark:I.srv, b.title+(b.v2?' · версия 2':''), b.kind==='api'?(S.w.key==='ok'?`${b.model} · ключ есть`:S.w.key==='bad'?'Ключ не принимается':'Нет ключа'):(S.w.server==='paired'?'hermes.example.ru · подключён':'Свой сервер: память, поиск, Telegram'), b.kind==='api'?'brainpage:'+b.id:'connect', b.kind==='api'&&S.w.key!=='ok')).join('')}
    ${li(I.plus,'Добавить мозг','Любой OpenAI-совместимый API: адрес, модель, ключ','addbrain')}
    <div class="lh">Уведомления</div>
    ${li(I.bell,'Уведомления', w.notif==='ok'?'Когда пришёл ответ, а лист закрыт':'Выключены','asknotif',false,`<span class="sw ${w.notif==='ok'?'':'off'}"></span>`)}
    <div class="lh">Действия</div>
    ${li(I.shield,'Разрешения',`Разрешено ${Object.values(S.policy).filter(p=>p==='auto').length}, спрашивать ${Object.values(S.policy).filter(p=>p==='ask').length}, не разрешено ${Object.values(S.policy).filter(p=>p==='off').length}`,'tools')}
    ${li(I.list,'Журнал действий',`Сегодня — ${S.journal.filter(j=>j.day==='Сегодня').length}`,'journal')}
    <div class="divider"></div>
    ${li(I.info,'О программе', S.upd.state==='available'?`Доступна версия ${S.upd.next}`:`Версия ${S.upd.ver}`,'about',false, S.upd.state==='available'?'<span class="ldot loc"></span>':'')}
  </div></div></div>`;
}
function modePage(){ const m=modeById(S.editMode); if(!m){ S.page='settings'; return settings() } const A=m.accepts, main=m.id==='main';
  const tog=(k,t,ic,lock)=>lock?`<span class="mchip" aria-pressed="true" style="cursor:default">${ic}${t}</span>`:`<button class="mchip" data-maccept="${k}" aria-pressed="${A[k]}">${ic}${t}</button>`;
  const field=(k,label,ph)=>`<label class="fl"><span>${label}</span><input class="fin" data-mfield="${k}" value="${esc(m[k])}" placeholder="${ph}"></label>`;
  const sw=(on,k,t,s,ic)=>`<div class="lirow">${li(ic,t,s)}<button class="swbtn" data-mbool="${k}" role="switch" aria-checked="${on}"><span class="sw ${on?'':'off'}"></span></button></div>`;
  return `<div class="layer appv">${statusBar(true)}<div class="topbar"><button class="iconbtn" data-act="settings" aria-label="Назад">${I.back}</button><div class="t"><b style="display:flex;align-items:center;gap:8px">${I[m.icon]}${esc(m.title)}</b><small>${m.builtin?'встроенный режим':'свой режим · id '+m.id+', не меняется'}</small></div></div>
  <div class="scroll"><div class="mform">
    ${main?'':`<div class="mrow">${field('title','Название','Как назвать вкладку')}</div>
    <div><div class="lh">Значок</div><div class="icons">${MODE_ICONS.map(k=>`<button class="icb" data-micon="${k}" aria-pressed="${m.icon===k}" aria-label="${k}">${I[k]}</button>`).join('')}</div></div>`}
    <div><div class="lh">Что делать</div>${main?'<p class="mnote">Основной режим: без своего промпта, все действия телефона, сюда приходят ответы по расписанию.</p>':`<textarea class="fieldarea ${S.rec&&S.rec.into==='prompt'?'dictating':''}" data-prompt="${m.id}" rows="1" ${S.rec&&S.rec.into==='prompt'?'readonly':''}>${esc(S.rec&&S.rec.into==='prompt'&&recText()?(m.prompt.trim()+' '+recText()).trim():m.prompt)}</textarea>
      <div class="dict">${S.rec&&S.rec.into==='prompt'
        ? (S.rec.mode==='locked'
            ? `<button class="iconbtn" data-act="reccancel" aria-label="Отменить">${I.x}</button><span class="dlive"><span class="rdot"></span>Пишу в поле · ${recTime()}</span><button class="recbtn" data-act="recsend" style="background:var(--ink);color:#fff">Готово</button>`
            : `<button class="dmic on" data-mic data-into="prompt" aria-label="Говорите">${I.mic}</button><span class="dlive"><span class="rdot"></span>Пишу в поле · отпустите — останется</span>`)
        : `<button class="dmic" data-mic data-into="prompt" aria-label="Надиктовать">${I.mic}</button><span class="dhint">Зажмите и надиктуйте — допишется в конец. Можно поправить руками.</span>`}</div>`}</div>
    ${main?'':`${field('hint','Подсказка в пустом разговоре','Что сюда присылать')}${field('placeholder','Подсказка в поле ввода','Вопрос к фото')}`}
    <div><div class="lh">Принимает</div><div class="chips">${tog('voice','Голос',I.mic,main)}${tog('text','Текст',I.kbd,true)}${tog('photo','Фото',I.cam)}${tog('file','Файл',I.doc)}</div></div>
    ${A.photo&&!main?`<div><div class="lh">Главное</div><div class="seg2">${[['voice',I.mic+'Голос'],['photo',I.cam+'Фото']].map(([v,t])=>`<button data-mprimary="${v}" aria-pressed="${m.primary===v}">${t}</button>`).join('')}</div><p class="mnote">${m.primary==='photo'?'Большая кнопка — камера. С боковой кнопки и ярлыка — сразу видоискатель. Спросить голосом — микрофон в поле или рядом со спуском.':'Большая кнопка — микрофон. С боковой кнопки и ярлыка — сразу слушает.'}</p></div>`:''}
    <div class="setcard">${main?li(I.shield,'Действия телефона','Все — таймер, календарь, будильник…'):sw(m.tools,'tools','Действия телефона',m.tools?'Включены — таймер, календарь, будильник…':'Выключены — меньше инструментов, ответ быстрее',I.shield)}${li(I.wave,'Язык речи','Русский (GigaAM). Другие языки — позже')}${li(I.wave,'Озвучка ответа','Позже: язык и голос для режима','',false,'<span class="sw off" aria-disabled="true" style="opacity:.4"></span>')}</div>
    <div class="setcard">${li(brainOf(m).kind==='api'?I.spark:I.srv,'Мозг',brainOf(m).title+(brainOf(m).v2?' · версия 2':'')+(m.accepts.photo?' · нужен со зрением':''),'brainpick')}${li(I.hist,'Начать заново',(S.feeds[m.id]||[]).length?`Стереть разговор режима — ${(S.feeds[m.id]||[]).length} сообщ.`:'Разговор пуст','restart')}</div>
    <p class="mnote">${brainOf(m).kind==='api'?'История режима хранится на телефоне и целиком уходит мозгу с каждой репликой. Смена мозга начинает разговор заново.':`В Hermes это отдельная сессия: <code>tasya:dm:&lt;телефон&gt;${main?'':':'+m.id}</code>. Промпт подмешивается в каждый ход.`}</p>
    ${m.builtin?'':`<button class="danger" data-act="delmode">${I.del}Удалить режим</button>`}
  </div></div></div>` }
function keyBody(onb){ const K=S.keyCheck, b=brainById('deepseek');
  const ck=K?K.checks.map(c=>`<div class="ck ${c.s}"><span class="s">${c.s==='ok'?'✓':c.s==='bad'?'!':''}</span><span>${c.t}${c.d?`<small>${c.d}</small>`:''}</span></div>`).join(''):'';
  return `<div class="h2">${onb?'Мозг':'Ключ DeepSeek'}</div><p style="color:var(--soft);margin:0 0 12px">${onb?'Отвечать будет DeepSeek — нужен ключ API с platform.deepseek.com. Ключ хранится в защищённом хранилище телефона.':'Хранится в защищённом хранилище телефона (Keystore).'}</p>
    <input class="field" id="apikey" value="${S.w.key==='ok'||K?'sk-••••••••••••••••3f9a':''}" placeholder="sk-…" autocomplete="off" spellcheck="false">
    <div style="display:flex;justify-content:flex-end;gap:6px;margin:8px 0 12px"><button class="tbtn" data-act="keypaste">Вставить</button><button class="fbtn" data-act="keycheck">Проверить</button></div>
    ${K?`<div class="checks">${ck}</div>`:''}${K&&K.stage==='bad'?`<div class="note bad">${K.err}</div>`:''}${S.w.key==='ok'&&K&&K.stage==='done'?'<div class="note ok">Готово. Режимы отвечают через DeepSeek.</div>':''}
    ${onb?'<p style="font-size:12.5px;color:var(--soft)">Свой сервер Hermes — с памятью, поиском и Telegram — подключается в версии 2.</p>':''}` }
function brainPage(){ const b=brainById(S.editBrain); const users=S.modes.filter(m=>m.brain===b.id).map(m=>m.title);
  return `<div class="layer appv">${statusBar(true)}<div class="topbar"><button class="iconbtn" data-act="settings" aria-label="Назад">${I.back}</button><div class="t"><b>${b.title}</b><small>OpenAI-совместимый API</small></div></div>
  <div class="scroll"><div class="mform">
    <div class="setcard">${li(I.srv,'Адрес',b.url)}${li(I.spark,'Модель',b.model)}</div>
    ${keyBody(false)}
    <div><div class="lh">Умеет</div><div class="chips">${[['vision','Фото'],['tools','Действия телефона'],['memory','Память'],['server','Сервер, поиск']].map(([k,t])=>`<span class="mchip" aria-pressed="${b.caps[k]}" style="cursor:default">${t}</span>`).join('')}</div></div>
    <p class="mnote">Режимы на этом мозге: ${users.join(', ')||'нет'}. История каждого хранится на телефоне и целиком уходит с каждой репликой; одинаковое начало разговора провайдер берёт из кэша — дешевле и быстрее.</p>
  </div></div></div>` }
function toolsPage(){
  return `<div class="layer appv">${statusBar(true)}<div class="topbar"><button class="iconbtn" data-act="settings" aria-label="Назад">${I.back}</button><div class="t"><b>Разрешения</b><small>что Тася делает на телефоне</small></div></div>
  <div class="scroll"><div class="list">${TOOL_GROUPS.map(g=>`<div class="lh">${g}</div>${TOOLS.filter(x=>x.group===g).map(x=>`<button class="li prow" data-tool="${x.id}"><span class="lt"><b>${x.title}</b></span><span class="pword ${S.policy[x.id]}">${POLICY_WORD[S.policy[x.id]]}</span></button>`).join('')}`).join('')}
    <div class="lh">Заблокированный телефон</div>${li(I.lock,'Без разблокировки', S.settings.lockScope==='safe'?'Будильник, таймер, фонарик':'Ничего — всё после разблокировки','lockscope')}
    <p class="mnote" style="padding:4px 22px 20px">«Разрешено» — Тася делает сразу и показывает в разговоре, что сделала. «Спрашивать» — сначала покажет и подождёт «Да». Сообщения Тася только готовит — отправляете вы.</p></div></div></div>` }
function journalPage(){ const days=[...new Set(S.journal.map(j=>j.day))];
  return `<div class="layer appv">${statusBar(true)}<div class="topbar"><button class="iconbtn" data-act="settings" aria-label="Назад">${I.back}</button><div class="t"><b>Журнал действий</b><small>только на телефоне</small></div></div>
  <div class="scroll"><div class="list">${days.map(d=>`<div class="lh">${d}</div>${S.journal.filter(j=>j.day===d).map(j=>`<button class="jrow" data-jid="${j.id}" aria-expanded="${S.jopen===j.id}"><span class="jt">${j.at}</span><span class="jm"><b>${esc(j.title)}</b><small>${j.who}${j.status?' · '+j.status:''}</small></span></button>${S.jopen===j.id?`<div class="jd"><div><span>Фраза</span>«${esc(j.phrase)}»</div><div><span>Действие</span><code>${j.tool}</code> ${esc(j.params)}</div><div><span>Подтверждение</span>${j.confirm}</div><div><span>Экран</span>${j.lock?'заблокирован':'открыт'}</div>${j.undo?`<button class="tbtn" data-act="jundo" data-id="${j.id}">Отменить</button>`:''}</div>`:''}`).join('')}`).join('')}</div></div></div>` }
function about(){
  const u=S.upd;
  return `<div class="layer appv">${statusBar(true)}<div class="topbar"><button class="iconbtn" data-act="settings" aria-label="Назад">${I.back}</button><div class="t"><b>О программе</b></div></div>
  <div class="scroll"><div style="padding:0 16px">
    <div style="text-align:center;padding:10px 0 14px">${oursIcon('width:64px;height:64px;border-radius:20px;margin:0 auto')}<div style="font-family:var(--display);font-size:24px;margin-top:10px">${u.ver}</div><div style="color:var(--soft);font-size:13px">стабильная версия</div></div>
    ${u.state==='available'?`<div class="card"><div class="who p">Есть версия ${u.next}</div><div class="bd"><ul style="margin:0;padding-left:18px"><li>Уведомление, когда Hermes ответил</li><li>Таблицы и код в ответах</li><li>Лист открывается быстрее</li></ul></div><div class="btns"><button class="cb pri" data-act="upd">Обновить</button><button class="cb gh">Позже</button></div></div>`:''}
    ${u.state==='dl'?`<div class="card"><div class="who p">Скачиваю ${u.next}</div><div class="bd"><div class="prog"><i style="width:${u.pct}%"></i></div><div style="font-size:12px;color:var(--soft);margin:6px 0">${u.pct} %</div></div><div class="btns"><button class="cb" data-act="updcancel">Отменить</button></div></div>`:''}
    ${u.state==='checking'?'<div class="mnote">Проверяю…</div>':''}${u.state==='latest'?'<div class="note ok">Это последняя версия</div>':''}${u.state==='fail'?'<div class="note bad">Не удалось проверить: нет связи</div>':''}
  </div>
  <div class="list">
    ${li(I.dl,'Проверить обновления','Сами проверяем при запуске и раз в 6 часов','check')}
    ${li(I.wave,'Модели распознавания', S.w.model==='ready'?'GigaAM v3 · потоковая · определение речи':'Не скачаны','dl')}
  </div></div></div>`;
}

/* первый запуск: 5 шагов, Material-навигация внизу */
const ONB=['welcome','button','voice','brain','done'];
function navBar(skip,primary){ return `<div class="nav">${skip?`<button class="tbtn" data-act="${skip[1]}">${skip[0]}</button>`:'<span style="width:60px"></span>'}<div class="dots">${ONB.map((_,i)=>`<i class="${i===S.onbStep?'on':''}"></i>`).join('')}</div>${primary?`<button class="fbtn" data-act="${primary[1]}">${primary[0]}</button>`:'<span style="width:60px"></span>'}</div>` }
function onb(){
  const st=ONB[S.onbStep];
  if (st==='brain') return `<div class="layer appv">${statusBar(true)}<div class="onb" style="padding-top:48px"><div style="flex:1;overflow:auto">${keyBody(true)}</div>${navBar(S.w.key==='ok'?null:['Позже','onbnext'], S.w.key==='ok'?['Далее','onbnext']:null)}</div></div>`;
  let art='',h='',p='',extra='',primary=['Далее','onbnext'],skip=['Позже','onbnext'];
  if (st==='welcome'){ art=oursIcon('width:96px;height:96px;border-radius:30px'); h='Зажал кнопку — сказал — готово'; p='Таймеры, будильники, фонарик — телефон делает сам, даже без интернета. Остальное отвечает модель, которую выберете вы.'; skip=null; primary=['Начать','onbnext'] }
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
function sysPick(){ const k=S.picker; const items=k==='gallery'?[['menu','Меню · сегодня 19:42'],['tea','Чай · сегодня 18:10']]:[['Договор аренды.pdf','248 КБ · Загрузки'],['Счёт октябрь.xlsx','36 КБ · Документы'],['Паспорт объекта.pdf','1,2 МБ · Загрузки']];
  return `<div class="layer sys">${statusBar(true)}<div class="sbar"><button class="iconbtn" data-act="pickback">${I.back}</button>${k==='gallery'?'Выберите фото':'Недавние файлы'}</div>
  ${k==='gallery'?`<div class="pgrid">${items.map(([c,t])=>`<button data-pickitem="${c}" aria-label="${t}"><i class="th ${c}"></i></button>`).join('')}</div>`:`<div class="grp">${items.map(([n,s])=>`<button class="it" data-pickitem="${n}|${s.split(' · ')[0]}">${I.doc}<span><b style="font-weight:500">${n}</b><br><small style="color:#777">${s}</small></span></button>`).join('')}</div>`}
  <p style="margin:12px 22px;font-size:12.5px;color:#777">Системный ${k==='gallery'?'выбор фото (Photo Picker)':'выбор файла (ACTION_OPEN_DOCUMENT)'} — разрешения на все файлы приложению не нужны.</p></div>` }
function sysSide(){ return `<div class="layer sys">${statusBar(true)}<div class="sbar"><button class="iconbtn" data-act="sysback">${I.back}</button>Боковая кнопка</div><p style="margin:0 22px 6px;font-size:13px;color:#666">Настройки → Дополнительные функции → Боковая кнопка</p><p style="margin:14px 24px 4px;font-size:13px;color:#3E6FD8;font-weight:600">Нажатие и удержание</p><div class="grp"><button class="it" data-side="1"><span class="rad ${S.sideOk?'on':''}"></span>Цифровой помощник</button><button class="it" data-side="0"><span class="rad ${S.sideOk?'':'on'}"></span>Меню выключения</button></div></div>` }
function tg(){ return `<div class="layer tg">${statusBar(false)}<div class="top"><button class="iconbtn" data-act="home" style="color:#fff">${I.back}</button><span class="av">H</span><span><b>Hermes</b><br><small style="opacity:.8">бот</small></span></div><div class="msgs" id="msgs">${S.tg.map(m=>`<div class="bub ${m.me?'me':''}">${m.html}</div>`).join('')}</div><div class="compose"><span class="in">${S.tg.length?'Сообщение':'/tasya_pair'}</span><button class="cb pri" data-act="tgsend" ${S.tg.length?'disabled':''}>➤</button></div></div>` }
function cam(){ return `<div class="layer cam">${statusBar(false)}<div class="frame"><div class="scanline"></div></div><p style="position:absolute;top:450px;width:100%;text-align:center;font-size:14px">Наведите на QR с экрана компьютера</p></div>` }
function qrSvg(){ let s=''; for(let i=0;i<144;i++){ const r=Math.floor(i/12),c=i%12; const f=(r<3&&c<3)||(r<3&&c>8)||(r>8&&c<3); s+=`<i style="background:${f||((r*7+c*13+r*c)%3===0)?'#111':'#fff'}"></i>` } return `<div class="qr">${s}</div>` }

function cam2(){ const c=S.cam; const m=curMode(); const qrec=S.rec&&S.screen==='cam2';
  return `<div class="layer cam2">${statusBar(false)}
  <div class="modeName">${I[m.icon]} ${esc(m.title)}</div>
  <div class="vf" style="top:96px"><i class="th ${c.subj}"></i></div>
  ${qrec?`<div class="q">${recText()||'Слушаю…'}</div>`:''}
  ${false?`<div class="sending"><span>Отправляю${c.q?' с вопросом':''}…</span><div class="bar"><i></i></div><button class="recbtn" data-act="shotcancel" style="background:rgba(255,255,255,.18);color:#fff">Отмена</button></div>`
   : c.taken?`<div class="after"><button data-act="retake">Переснять</button><button data-act="addq">Добавить вопрос</button><button class="pri" data-act="sendshot">Отправить</button></div>`
   : `<button class="cx" data-act="camcancel">${c.first?'В лист':'Отмена'}</button><button class="shut" data-act="shoot" aria-label="Снять и отправить"></button><button class="mic2 ${qrec?'on':''}" data-mic aria-label="Зажмите и спросите — снимок уйдёт с вопросом">${I.mic}</button>`}</div>` }
function shortcutsPop(){ if(!S.shortcuts) return ''; return `<div class="sc-pop"><small>Тася</small>${activeModes().map(m=>`<button data-shortcut="${m.id}"><span style="width:22px;display:grid;place-items:center;color:#555">${I[m.icon]}</span>${m.title}</button>`).join('')}</div>` }
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
function dialog(){ const D=S.dialog; if(!D) return '';
  if (D.radio) return `<div class="scrim"><div class="dlg rdlg"><h3>${D.title}</h3>${D.radio.map(([v,t])=>`<button class="ropt" data-radio="${v}"><span class="rad ${D.cur===v?'on':''}"></span>${t}</button>`).join('')}<div class="dfoot"><button class="tbtn" data-dlgclose>Отмена</button></div></div></div>`;
  return `<div class="scrim"><div class="dlg">${D.ic?`<div class="di">${D.ic}</div>`:''}<h3>${D.title}</h3>${D.text?`<p>${D.text}</p>`:''}${D.opts.map((o,i)=>`<button class="opt ${o[2]||''}" data-dlg="${i}">${o[0]}</button>`).join('')}</div></div>` }
function toast(){ const T=S.toast; if(!T) return ''; return `<button class="toast" data-act="toast">${oursIcon()}<span><b>${T.title}</b><span>${T.text}</span></span></button>` }

function grow(t){ t.style.height='auto'; t.style.height=t.scrollHeight+'px' }
function render(){
  let base='';
  if (S.screen==='lock') base=lock();
  else if (S.screen==='home') base=home();
  else if (S.screen==='app') base = !S.onboarded ? onb() : S.page==='settings'?settings():S.page==='mode'?modePage():S.page==='brain'?brainPage():S.page==='tools'?toolsPage():S.page==='journal'?journalPage():S.page==='about'?about():S.page==='connect'?connectPage():chat();
  else if (S.screen==='tg') base=tg(); else if (S.screen==='cam') base=cam(); else if (S.screen==='cam2') base=cam2();
  else if (S.screen==='sysrole') base=sysRole(); else if (S.screen==='sysside') base=sysSide(); else if (S.screen==='sysmic') base=sysMic(); else if (S.screen==='syspick') base=sysPick();
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

