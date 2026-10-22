/* Экраны и компоненты (в Android — Compose-экраны). Только рисуют состояние, логики нет. */
/* ───────── общие куски ───────── */
function statusBar(dark){
  const wifi='<svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor"><path d="M12 21 23.6 7A17.6 17.6 0 0 0 12 3 17.6 17.6 0 0 0 .4 7z"/></svg>';
  const cell='<svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M2 22h20V2z"/></svg>';
  const plane='<svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor"><path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z"/></svg>';
  const bat='<svg viewBox="0 0 26 13" width="24" height="12"><rect x=".7" y=".7" width="22" height="11.6" rx="3" fill="none" stroke="currentColor" stroke-opacity=".45" stroke-width="1.2"/><rect x="2.4" y="2.4" width="15.2" height="8.2" rx="1.6" fill="currentColor"/><rect x="23.8" y="4.3" width="1.8" height="4.4" rx=".9" fill="currentColor" fill-opacity=".45"/></svg>';
  const net = S.w.net==='none' ? plane : (S.w.net==='wifi' ? wifi : '') + cell;
  return `<div class="sb ${dark?'d':'l'}"><span>9:41</span><span class="sbi">${net}<span>82%</span>${bat}</span></div><div class="punch"></div>` }
/** Значок Вахтёра — тёмная плитка, в ней знак: янтарное ядро и два кольца («лампа на вахте»), без движения. */
function oursIcon(st=''){ return `<span class="ico ours" style="${st}">${vMark('ico')}</span>` }
const brainOk = () => brainReady() && S.w.net!=='none' && !(brainOf().kind==='hermes'&&S.w.server==='down');
function linkState(){
  const B=brainOf();
  if (!brainReady(B)) return ['loc', B.kind==='api'?'нет ключа':'сервер не подключён'];
  if (S.w.net==='none') return ['off','нет связи · команды телефона работают'];
  if (B.kind==='hermes'&&S.w.server==='down') return ['off','сервер не отвечает'];
  return ['','на связи'];
}
function issue(){
  if (S.w.mic==='denied') return {k:'e',t:'Нет доступа к микрофону. Пока можно писать.',a:[['Разрешить','askmic']]};
  if (S.w.model==='broken') return {k:'e',t:'Файл распознавания повреждён.',a:[['Скачать заново','dl']]};
  if (S.w.model==='loading') return {k:'p',t:S.w.net==='none'?`Распознавание: ${S.modelPct} % — ждёт сеть`:`Распознавание качается: ${S.modelPct} % из ${mb(MODEL_TOTAL)} МБ`,prog:S.modelPct};
  if (S.w.model==='none') return {k:'p',t:`Чтобы говорить голосом, скачайте распознавание — ${mb(MODEL_TOTAL)} МБ, один раз.`,a:[['Не сейчас','dismiss'],['Скачать','dl']]};
  if (S.w.role!=='ours') return {k:'p',t:'Цифровым помощником выбран Gemini — боковая кнопка откроет его.',a:[['Не сейчас','dismiss'],['Настроить','sysrole']]};
  if (S.w.profile==='unknown' && S.w.role==='ours' && S.sideProbe!=='yes') return S.sideProbe==='no'
      ? {k:'p',t:'Выберите «Вахтёр» цифровым помощником для боковой кнопки: Настройки → кнопки или жесты → помощник. Пункты зависят от телефона.',a:[['Сообщить разработчику','sidereport'],['Открыть настройки','sysside']]}
      : {k:'p',t:'Зажмите боковую кнопку. Открылось окно ассистента?',a:[['Нет, другое','sideno'],['Да','sideyes']]};
  if (S.w.side==='double') return {k:'p',t:'Ассистент открывается двойным нажатием боковой кнопки. Для удержания: Боковая кнопка → Нажатие и удержание → Цифровой помощник.',a:[['Не сейчас','dismiss'],['Настроить','sysside']]};
  if (S.w.side!=='assistant') return {k:'p',t:`Боковая кнопка открывает ${S.w.side==='bixby'?'Bixby':'меню выключения'}.`,a:[['Не сейчас','dismiss'],['Настроить','sysside']]};
  if (!brainReady()) return {k:'p',t:brainOf().kind==='api'?'Мозг не подключён — работают только команды телефона.':'Hermes не подключён — работают только команды телефона.',a:[['Не сейчас','dismiss'],['Подключить',brainOf().kind==='api'?'brainsetup':'connect']]};
  return null;
}
function bannerHtml(){ const b=issue(); if(!b||S.dismissed===b.t) return ''; return `<div class="banner ${b.k==='e'?'e':''}"><span class="bi">${b.k==='e'?I.warn:I.info}</span><p>${b.t}</p>${b.prog!=null?`<div class="prog"><i style="width:${b.prog}%"></i></div>`:''}${b.a?`<div class="ba">${b.a.map(x=>`<button class="tbtn" data-act="${x[1]}">${x[0]}</button>`).join('')}</div>`:''}</div>` }

/* ───────── лента ───────── */
/* Промпт режима — полоска над лентой: что режим помнит; карандаш → страница режима. У Ассистента промпта нет — полоски нет. */
function promptHtml(){
  const m=curMode(); if(!m.prompt.trim()) return '';
  return `<button class="mprompt" data-act="editmode:${m.id}"><span>${esc(m.prompt)}</span>${I.edit}</button>`;
}
/* Новый лист: после 30 мин без дела боковая кнопка открывает чистый лист. Старое не стирается — оно в приложении, выше черты «Новый разговор». */
const sheetFrom = () => (S.sheetFrom&&S.sheetFrom[S.mode])||0;
const sheetItems = () => S.feed.slice(sheetFrom());
function feedHtml(inSheet){
  const items = inSheet ? sheetItems() : S.feed;
  if (!items.length && inSheet) return sheetEmpty();
  if (!S.feed.length && curMode().hint && S.mode!=='main') return `<div class="feed"><div class="empty" style="padding:10px 4px">${esc(curMode().hint)}</div></div>`;
  if (!S.feed.length) return `<div class="feed"><div class="empty">В любом приложении зажмите боковую кнопку или проведите от нижнего угла к центру — или напишите здесь.<div class="chips" style="margin-top:12px">${['Таймер на пять минут','Что у меня завтра?','Как настроить бэкап сервера?'].map(x=>`<button class="chip" data-saytext="${x}">${x}</button>`).join('')}</div></div></div>`;
  const f=sheetFrom(), sep=!inSheet&&f>0&&f<S.feed.length;
  return `<div class="feed">${items.map((it,i)=>(sep&&i===f?`<div class="newconv"><span>Новый разговор · из листа</span></div>`:'')+itemHtml(it,inSheet)).join('')}</div>`;
}
/** Карточка действия по её описанию (model.js, ACT_CARDS): значок, итог, подробность, одна кнопка-значок. */
function actHtml(it){ const C=ACT_CARDS[it.i]||{}; const title=C.title?C.title(it):it.x;
  const sub=C.sub?C.sub(it):(it.s||'').replace(/^на телефоне( · )?/,'').replace(/^[^·]*→ телефон( · )?/,'');
  let btn='';
  if (!it.undone && C.btn==='open') btn=`<button class="actb" data-actbtn="${it.id}" aria-label="${C.label}">${I.openext}</button>`;
  if (!it.undone && C.btn==='stop' && !it.stopped) btn=`<button class="actb" data-actbtn="${it.id}" aria-label="${C.label}">${I.stop}</button>`;
  if (!it.undone && C.btn==='toggle') btn=`<button class="swbtn actsw" data-actbtn="${it.id}" role="switch" aria-checked="${it.on!==false}" aria-label="${C.label}"><span class="sw ${it.on===false?'off':''}"></span></button>`;
  return `<div class="act ${it.undone?'undone':''}" data-aid="${it.id}"><span class="ai2">${I[C.icon]||it.i}</span><span class="tx"><b>${title}</b>${sub?`<span class="asub">${sub}</span>`:''}</span>${btn}</div>` }
function itemHtml(it,inSheet){
  if (it.t==='notifask') return `<div class="nask">${I.bell}<span>Сообщить, если закроете лист до ответа?</span><button class="tbtn" data-act="asknotif">Разрешить</button><button class="iconbtn" data-act="notifno" aria-label="Не нужно">${I.x}</button></div>`;
  if (it.t==='me' && it.img && it.up && it.up.st!=='sent'){ const u=it.up;
    const st = u.st==='wait'?'Ждёт сети — отправлю сам':u.st==='fail'?'Не загрузилось':u.pct>=100&&u.hold?'Загружено · ждёт ваш вопрос':u.pct>=100?`Загружено · отправлю через ${Math.max(1,Math.ceil((2000-(Date.now()-u.t0))/1000))} с`:upLabel(it);
    if (it.img.kind==='file') return `<div class="meimg up"><div class="fcard">${I.doc}<span><b>${esc(it.img.name)}</b><small>${it.img.size}</small></span><button class="upx" data-act="upcancel" data-id="${it.id}" aria-label="Отменить отправку">${I.x}</button>${u.st==='up'?`<i class="upbar" style="width:${u.pct}%"></i>`:''}</div><small class="upst ${u.st==='fail'?'err':''}">${st}${u.st==='fail'?` · <button class="lnk" data-act="upretry" data-id="${it.id}">Повторить</button>`:''}</small>${it.x?`<div class="me" style="max-width:100%">${esc(it.x)}<small>уйдёт вместе с файлом</small></div>`:`<small class="uphint">Можно дописать вопрос — уйдёт вместе с файлом</small>`}</div>`;
    return `<div class="meimg up"><div class="thw" style="${thumbStyle(it.img)}">${thumbHtml(it.img)}${u.st==='up'?`<i class="upbar" style="width:${u.pct}%"></i>`:''}<button class="upx" data-act="upcancel" data-id="${it.id}" aria-label="Отменить отправку">${I.x}</button></div>
      <small class="upst ${u.st==='fail'?'err':''}">${st}${u.st==='fail'?` · <button class="lnk" data-act="upretry" data-id="${it.id}">Повторить</button>`:''}</small>
      ${it.x?`<div class="me" style="max-width:100%">${esc(it.x)}<small>уйдёт вместе с фото</small></div>`:`<small class="uphint">Можно дописать вопрос — уйдёт вместе с фото</small>`}</div>` }
  if (it.t==='me' && it.img && it.up && it.up.st==='cancel') return '';
  if (it.t==='me' && it.img && it.img.kind==='file') return `<div class="meimg"><div class="fcard">${I.doc}<span><b>${esc(it.img.name)}</b><small>${it.img.size}</small></span></div>${it.x?`<div class="me" style="max-width:100%">${esc(it.x)}<small>${it.via}</small></div>`:`<small style="font-size:10.5px;color:var(--faint)">${it.via}</small>`}</div>`;
  if (it.t==='me' && it.img) return `<div class="meimg"><div class="thw" style="${thumbStyle(it.img)}">${thumbHtml(it.img)}</div>${it.x?`<div class="me" style="max-width:100%">${esc(it.x)}<small>${it.via}</small></div>`:`<small style="font-size:10.5px;color:var(--faint)">${it.via}</small>`}</div>`;
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
  if (it.t==='act') return actHtml(it);
  if (it.t==='card') return `<div class="card"><div class="who ${it.who}">${it.head}</div><div class="bd"><div class="tt">${it.title}</div>${it.kv?`<dl class="kv">${it.kv.map(k=>`<dt>${k[0]}</dt><dd>${k[1]}</dd>`).join('')}</dl>`:''}${it.text?`<p style="margin:6px 0 2px;font-size:13px;color:var(--soft)">${it.text}</p>`:''}${it.code?`<pre>${it.code}</pre>`:''}${it.tmr?`<div class="tmr">${it.tmr}</div>`:''}</div><div class="btns">${it.btns.map(b=>`<button class="cb ${b[2]||''}" data-cardbtn="${b[1]}">${b[0]}</button>`).join('')}</div></div>`;
  return '';
}
function stepsHtml(it){
  const n=it.items.length; const pd=it.plan?it.plan.items.filter(p=>p.st==='completed').length:0;
  const head = it.run ? `<span class="shim">${it.cur}</span>` : `${n} ${n===1?'шаг':n<5?'шага':'шагов'} · ${it.sec} с${it.plan?` · план ${pd} из ${it.plan.items.length}`:''}`;
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
  const apps=[['📞','#2F9E5B','Телефон'],['💬','#3B82F6','Сообщения'],['✈️','#2AABEE','Telegram','tg'],['📷','#555','Камера'],['⏰','#1F2937','Часы'],['📅','#E05D44','Календарь'],['⚙️','#6B7280','Настройки'],['ours','','Вахтёр','app']];
  return `<div class="layer home">${statusBar(false)}<div class="clk">9:41</div><div class="dt">пятница, 17 октября</div>
  <div class="grid">${apps.map(a=>`<button class="app" data-open="${a[3]||''}">${a[0]==='ours'?oursIcon():`<span class="ico" style="background:${a[1]}">${a[0]}</span>`}${a[2]}</button>`).join('')}</div>
  <div class="dock"><span class="ico" style="background:#2F9E5B">📞</span><span class="ico" style="background:#3B82F6">💬</span><span class="ico" style="background:#F59E0B">🌐</span><span class="ico" style="background:#555">📷</span></div></div>`;
}
function lock(){ return `<div class="layer lock">${statusBar(false)}<div class="clk">9:41</div><div class="dt">пятница, 17 октября</div><button class="unl" data-act="unlock">Разблокировать отпечатком</button></div>` }

const voiceOk = () => S.w.model==='ready' && S.w.mic==='ok';
function recTime(){ const s=S.rec?Math.floor((Date.now()-S.rec.t0)/1000):0; return `0:${String(s).padStart(2,'0')}` }
function liveHtml(){
  // Запись в разговоре — тот же живой знак, что в пустом листе, своей строкой над полем (на историю не наезжает). Эквалайзера нет.
  if (S.rec) return `<div class="live recl" data-st="rec">${vMark('mid')}</div>`;
  if (S.recog) return `<div class="live recl" data-st="recog">${vMark('mid')}</div>`;
  if (S.ov&&S.ov.live) return `<div class="live ${S.ov.liveQ?'q':''}">${S.ov.live}</div>`;
  return '';
}
/** Вкладки как в камере: листаются пальцем, отпустили — та, что по центру, выбрана сама; выбранная всегда по центру. «+» — отдельно справа. */
function modeTabs(){ const am=activeModes(); return `<div class="mtabw"><div class="mtabs snap" role="tablist" aria-label="Режимы">${am.map(m=>`<button role="tab" data-pickmode="${m.id}" aria-pressed="${S.mode===m.id}">${m.title}</button>`).join('')}</div><button class="madd" data-act="addmode" aria-label="Новый режим">+</button></div>` }
/* ⋮ в шапке разговора — меню, как привычно в Android: пункты-действия без пояснений. */
function chatMenu(){ if(!S.chatMenu) return ''; const empty=!S.feed.length;
  return `<div class="cmenu" role="menu"><button role="menuitem" data-act="cm:edit">Изменить режим</button><button role="menuitem" data-act="cm:restart" ${empty?'disabled':''}>Начать заново</button></div>` }
function attachMenu(){ if(!S.attachMenu) return ''; const m=curMode(), A=m.accepts, photoMain=m.primary==='photo';
  const it=(act,ic,t,s)=>`<button data-act="${act}"><span class="mi2">${ic}</span><span><b>${t}</b><small>${s}</small></span></button>`;
  return `<div class="mpicker" role="menu">${A.photo&&!photoMain?it('camera',I.cam,'Камера','снять и отправить'):''}${A.photo?it('gallery',I.image,'Галерея','системный выбор фото'):''}${A.file?it('file',I.doc,'Файл','PDF, таблица, документ'):''}</div>` }
/** o.into='try' — проба диктовки в первом запуске: та же кнопка и те же жесты, без вкладок режимов и вложений. */
function composerHtml(o={}){
  const tabs=o.into?'':modeTabs(), into=o.into?` data-into="${o.into}"`:'';
  // «Вам чего?» — только в пустом разговоре, пока ничего не спрашивали: шутка не должна надоедать
  // В листе «Вам чего?» — заголовком сцены, в поле — подсказка режима; в пустом разговоре приложения — в поле.
  const ph=!brainReady()?'Команда агенту':(S.ov&&!S.ov.full)||S.feed.some(f=>f.t==='me')?curMode().placeholder:'Вам чего?';
  const mic=`<button class="micbtn ${voiceOk()?'':'off'} ${S.rec&&S.rec.mode==='hold'?'recmic':''} ${S.recog?'proc':''}" data-mic${into} aria-label="Зажмите, чтобы говорить; короткое нажатие — запись без рук">${I.mic}</button>`;
  // Запись — как в приложении (кнопка, полоса и замок Писаря, pisar/overlay): белая полоса «● 0:07 ‹ ‹ Отмена», к кнопке — без скругления;
  // кнопка красная с ореолами; над ней белый замок. Без рук — на кнопке «Отправить», замок закрыт (зелёный кружок).
  const bar=(hint,op)=>`<div class="fld recbar"><span class="rdot"></span><span class="tm">${recTime()}</span><span class="slide" style="opacity:${op}">${hint}</span></div>`;
  if (S.rec && S.rec.mode==='hold'){ const g=S.rec.g||{x:0,y:0}; const fresh=Date.now()-S.rec.t0<250;
    return `<div class="cwrap"><div class="lockcue ${fresh?'in':''} ${g.y>22?'near':''}" style="--ly:${Math.min(g.y,45)}px">${I.lock}<span class="arr">⌃</span></div><div class="composer rec">${bar('‹ ‹&nbsp; Отмена',Math.max(.25,1-g.x/70))}<div class="micwrap" style="transform:translate(${-Math.min(g.x,70)}px,${-Math.min(g.y,45)}px)">${mic}</div></div>${tabs}</div>` }
  if (S.rec){ const gx=S.rec.lg||0; return `<div class="cwrap"><div class="lockcue shut">${I.lock}</div><div class="composer rec"><button class="fld recbar lnkc" data-act="reccancel"><span class="rdot"></span><span class="tm">${recTime()}</span><span class="slide" style="opacity:${Math.max(.25,1-gx/70)}">‹ ‹&nbsp; Отмена</span></button><div class="micwrap" style="transform:translateX(${-Math.min(gx,70)}px)"><button class="micbtn recmic sendmode" data-recsend aria-label="Отправить; потяните влево — отменить"><span class="sendw">Отправить</span></button></div></div>${tabs}</div>` }
  if (o.into==='try') return `<div class="cwrap"><div class="composer"><div class="tryhint">вверх — без рук · влево — отмена</div>${o.pulse?mic.replace('class="micbtn','class="micbtn pulse'):mic}</div></div>`;
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
    <button class="iconbtn" data-act="settings" aria-label="Настройки">${I.gear}</button><button class="iconbtn" data-act="chatmenu" aria-label="Ещё" aria-expanded="${!!S.chatMenu}">${I.more}</button></div>${chatMenu()}
  ${bannerHtml()}${promptHtml()}
  <div class="scroll" id="scroll">${feedHtml(false)}</div>
  ${S.ov?'':liveHtml()}${S.ov?'':composerHtml()}</div>`;
}
function li(icon,title,sum,act,warn,trail){ return `<button class="li ${warn?'warn':''}" ${act?`data-act="${act}"`:''}><span class="lic">${warn?I.warn:icon}</span><span class="lt"><b>${title}</b>${sum?`<small>${sum}</small>`:''}</span>${trail||''}</button>` }
function settings(){
  const w=S.w;
  return `<div class="layer appv">${statusBar(true)}
  <div class="topbar"><button class="iconbtn" data-act="back" aria-label="Назад">${I.back}</button><div class="t"><b>${S.reorder?'Порядок режимов':'Настройки'}</b></div>${S.reorder?'<button class="tbtn" data-act="reorderdone">Готово</button>':''}</div>
  <div class="scroll"><div class="list">
    <div class="lh">Режимы</div>
    ${S.modes.map((m,i)=>`<div class="lirow mrow1 ${S.reorder?'reo':''}" data-ri="${i}" data-hold="${i}">${li(mIcon(m.icon),esc(m.title),m.brain==='hermes'&&S.w.server!=='paired'?'Мозг не подключён':modeSummary(m),S.reorder?'':'editmode:'+m.id,m.brain==='hermes'&&S.w.server!=='paired')}${S.reorder?`<span class="grip" data-drag="${i}" aria-label="Перетащить «${esc(m.title)}»">${I.grip}</span>`:m.id==='main'?'<span class="swgap"></span>':`<button class="swbtn" data-menable="${m.id}" role="switch" aria-checked="${m.enabled}" aria-label="${esc(m.title)}"><span class="sw ${m.enabled?'':'off'}"></span></button>`}</div>`).join('')}
    ${S.reorder?'':`${li(I.plus,'Новый режим','Название, промпт, что принимает','addmode')}`}
    ${S.reorderedOnce||S.reorder?'':'<p class="mnote holdhint">Удерживайте режим, чтобы поменять порядок</p>'}
    <div class="lh">Помощник</div>
    ${li(I.spark,'Цифровой помощник', w.role==='ours'?'Вахтёр':'Сейчас Gemini','sysrole',w.role!=='ours')}
    ${li(I.btn,'Боковая кнопка', S.w.profile==='unknown' ? 'Проверка кнопки зависит от телефона' : {power:'Зажатие — меню выключения',bixby:'Зажатие — Bixby',double:'Помощник — на двойном нажатии',assistant:'Зажатие — цифровой помощник'}[S.w.side],'sysside',S.w.side!=='assistant')}
    <div class="lirow">${li(I.mic,'Слушать сразу при вызове',S.settings.listenOnCall===false?'Запись по нажатию':'Запись начинается сама')}<button class="swbtn" data-act="listentoggle" role="switch" aria-checked="${S.settings.listenOnCall!==false}"><span class="sw ${S.settings.listenOnCall===false?'off':''}"></span></button></div>
    ${li(I.hist,'Запоминать режим', RETURN_OPTS.find(o=>o[0]===S.settings.returnToAssistant)[2],'returnpick')}
    <div class="lh">Голос</div>
    ${li(I.wave,'Распознавание речи', w.model==='ready'?`GigaAM · на телефоне · ${mb(MODEL_TOTAL)} МБ`:w.model==='loading'?`Качается: ${S.modelPct} %`:w.model==='broken'?'Файл повреждён':'Не скачано · '+mb(MODEL_TOTAL)+' МБ','voice',w.model==='none'||w.model==='broken')}
    ${w.mic==='ok'?'':li(I.mic,'Микрофон', w.mic==='denied'?'Запрещён':'Спросит при первой записи','askmic',w.mic==='denied')}
    <div class="lh">Мозги</div>
    ${S.brains.map(b=>li(b.kind==='api'?I.spark:I.srv, b.title, b.kind==='api'?(S.w.key==='ok'?`${b.model} · подключён`:S.w.key==='bad'?'Ключ не принимается':'Не подключён'):(S.w.server==='paired'?'hermes.example.ru · подключён':'Не подключён'), b.kind==='api'?'brainpage:'+b.id:'connect', b.kind==='api'&&S.w.key!=='ok')).join('')}
    ${li(I.plus,'Добавить мозг','Адрес, модель и ключ','addbrain')}
    <div class="lh">Уведомления</div>
    ${li(I.bell,'Уведомления', w.notif!=='ok'?'Выключены · сообщу об ответе, если разрешите':S.settings.notifyAnswers===false?'Не сообщаю об ответах':'Сообщу, когда придёт ответ, а лист закрыт',w.notif==='ok'?'notiftoggle':'asknotif',false,`<span class="sw ${w.notif==='ok'&&S.settings.notifyAnswers!==false?'':'off'}"></span>`)}
    <div class="lh">Действия</div>
    ${li(I.shield,'Действия на телефоне',TOOLS.some(toolBlocked)?`Нет доступа — ${TOOLS.filter(toolBlocked).length}`:`Разрешено ${Object.values(S.policy).filter(p=>p==='auto').length}, спрашивать ${Object.values(S.policy).filter(p=>p==='ask').length}, не разрешено ${Object.values(S.policy).filter(p=>p==='off').length}`,'actions',TOOLS.some(toolBlocked))}
    <div class="divider"></div>
    ${li(I.info,'О программе', S.upd.state==='available'?`Доступна версия ${S.upd.next}`:`Версия ${S.upd.ver}`,'about',false, S.upd.state==='available'?'<span class="ldot loc"></span>':'')}
  </div></div></div>`;
}
function modePage(){ const m=modeById(S.editMode); if(!m){ S.page='settings'; return settings() } const A=m.accepts, main=m.id==='main';
  const tog=(k,t,ic,lock)=>(lock||m.builtin)?`<span class="mchip" aria-pressed="true" style="cursor:default">${ic}${t}</span>`:`<button class="mchip" data-maccept="${k}" aria-pressed="${A[k]}">${ic}${t}</button>`;
  const field=(k,label,ph)=>`<label class="fl"><span>${label}</span><input class="fin" data-mfield="${k}" value="${esc(m[k])}" placeholder="${ph}"></label>`;
  const sw=(on,k,t,s,ic)=>`<div class="lirow">${li(ic,t,s)}<button class="swbtn" data-mbool="${k}" role="switch" aria-checked="${on}"><span class="sw ${on?'':'off'}"></span></button></div>`;
  return `<div class="layer appv">${statusBar(true)}<div class="topbar"><button class="iconbtn" data-act="modeback" aria-label="Назад">${I.back}</button><div class="t"><b style="display:flex;align-items:center;gap:8px">${mIcon(m.icon)}${esc(m.title)}</b><small>${m.builtin?'Встроенный режим':'Свой режим'}</small></div></div>
  <div class="scroll"><div class="mform">
    ${m.builtin&&!main?'<div class="note">Встроенный режим: промпт можно изменить, сам режим — включить или выключить в «Настройках». Чтобы настроить всё по-своему — создайте свой режим «+».</div>':''}
    ${main||m.builtin?'':`<div class="mrow">${field('title','Название','Как назвать вкладку')}</div>
    <div><div class="lh">Значок</div>${iconGrid(m)}</div>`}
    <div><div class="lh">${main?'Свои правила':'Что делать'}</div>${main?'<p class="mnote" style="margin:0 0 6px">Добавляются к основным инструкциям: действия телефона работают как прежде.</p>':''}${`<textarea class="fieldarea ${S.rec&&S.rec.into==='prompt'?'dictating':''}" data-prompt="${m.id}" rows="1" placeholder="${main?'Например: отвечай коротко, на «ты»; я живу в Казани':'Например: переводи всё на китайский и добавляй пиньинь'}" ${S.rec&&S.rec.into==='prompt'?'readonly':''}>${esc(S.rec&&S.rec.into==='prompt'&&recText()?(m.prompt.trim()+' '+recText()).trim():m.prompt)}</textarea>
      <div class="dict">${S.rec&&S.rec.into==='prompt'
        ? (S.rec.mode==='locked'
            ? `<button class="iconbtn" data-act="reccancel" aria-label="Отменить">${I.x}</button><span class="dlive"><span class="rdot"></span>Пишу в поле · ${recTime()}</span><button class="recbtn" data-act="recsend" style="background:var(--ink);color:#fff">Готово</button>`
            : `<button class="dmic on" data-mic data-into="prompt" aria-label="Говорите">${I.mic}</button><span class="dlive"><span class="rdot"></span>Пишу в поле · отпустите — останется</span>`)
        : `<button class="dmic" data-mic data-into="prompt" aria-label="Надиктовать">${I.mic}</button><span class="dhint">Зажмите и надиктуйте — допишется в конец. Можно поправить руками.</span>`}</div>`}${(()=>{ const d=BUILTIN_MODES.find(b=>b.id===m.id); return d&&!main&&m.prompt!==d.prompt?`<div style="text-align:right"><button class="tbtn" data-act="promptreset">Вернуть как было</button></div>`:'' })()}</div>
    ${main||m.builtin?'':`${field('hint','Подсказка в пустом разговоре','Что сюда присылать')}${field('placeholder','Подсказка в поле ввода','Вопрос к фото')}`}
    <div><div class="lh">Принимает</div><div class="chips">${tog('voice','Голос',I.mic,main)}${tog('text','Текст',I.kbd,true)}${tog('photo','Фото',I.cam)}${tog('file','Файл',I.doc)}</div></div>
    ${A.photo&&!main?`<div><div class="lh">Главное</div><div class="seg2">${[['voice',I.mic+'Голос'],['photo',I.cam+'Фото']].map(([v,t])=>`<button data-mprimary="${v}" aria-pressed="${m.primary===v}">${t}</button>`).join('')}</div><p class="mnote">${m.primary==='photo'?'Большая кнопка — камера. С боковой кнопки — сразу видоискатель. Спросить голосом — микрофон в поле.':'Большая кнопка — микрофон. С боковой кнопки — сразу слушает.'}</p></div>`:''}
    <div class="setcard">${main?li(I.shield,'Действия телефона','Все'):sw(m.tools,'tools','Действия телефона',m.tools?'Включены':'Выключены · ответ быстрее',I.shield)}${li(I.wave,'Язык речи','Русский')}${li(I.wave,'Озвучка ответа','Позже','',false,'<span class="sw off" aria-disabled="true" style="opacity:.4"></span>')}</div>
    <div class="setcard">${li(brainOf(m).kind==='api'?I.spark:I.srv,'Мозг',brainOf(m).title+(m.accepts.photo?' · нужен со зрением':''),'brainpick')}</div>
    <p class="mnote">${brainOf(m).kind==='api'?'История режима хранится на телефоне и целиком уходит мозгу с каждой репликой. Смена мозга начинает разговор заново.':`В Hermes это отдельная сессия: <code>vahter:dm:&lt;телефон&gt;${main?'':':'+m.id}</code>. Промпт подмешивается в каждый ход.`}</p>
    ${m.builtin?'':`<button class="danger" data-act="delmode">${I.del}Удалить режим</button>`}
  </div></div></div>` }
/** Значки режима — два ряда по 6; последняя ячейка — свой эмодзи: пунктир и «+», выбранный эмодзи встаёт в неё. */
function iconGrid(m){ const emo=m.icon&&m.icon.startsWith('emoji:');
  return `<div class="icons grid6">${MODE_ICONS.map(k=>`<button class="icb" data-micon="${k}" aria-pressed="${m.icon===k}" aria-label="${k}">${I[k]}</button>`).join('')}<button class="icb emocell ${emo?'':'empty'}" data-act="emojipick" aria-pressed="${emo}" aria-label="Свой эмодзи">${emo?`<span class="emo">${m.icon.slice(6)}</span>`:'<span class="emo">☺</span><i>+</i>'}</button></div>
  ${S.emojiPick?`<div class="emopop"><input class="field" id="emoin" placeholder="Эмодзи с клавиатуры" autocomplete="off"><div class="emoq">${EMOJI_QUICK.map(e=>`<button data-act="emo:${e}">${e}</button>`).join('')}</div></div>`:''}` }
/** Ключ DeepSeek. Проверки (формат, связь, ключ, модель, фото) идут внутри — на экране одна строка: «Подключаю…», итог или что не так. */
function keyBody(onb){ const K=S.keyCheck;
  const res = !K ? '' : K.stage==='run' ? '<div class="kres run"><span class="spin"></span>Подключаю…</div>'
    : K.stage==='bad' ? `<div class="kres bad"><b>!</b>${K.err}</div>`
    : `<div class="kres ok"><b>✓</b><span><strong>DeepSeek подключён</strong><small>отвечает за ${K.ms} · понимает фото</small></span></div>`;   // две строки по смыслу — на узком экране переносить нечего
  return `${onb?'':'<div class="h2">Ключ DeepSeek</div><p style="color:var(--soft);margin:0 0 12px">Хранится в защищённом хранилище телефона (Keystore).</p>'}
    <div class="kfld"><input class="field" id="apikey" value="${S.w.key==='ok'||K?'sk-••••••••••••••••3f9a':''}" placeholder="Ключ API · sk-…" autocomplete="off" spellcheck="false"><button class="intool" data-act="keypaste" aria-label="Вставить">${I.paste}</button></div>
    ${onb?'<p class="klbl">Где взять ключ? <b>platform.deepseek.com</b> → API keys. Хранится в защищённом хранилище телефона.</p>':'<div style="display:flex;justify-content:flex-end;margin:8px 0 12px"><button class="fbtn" data-act="keycheck">Проверить</button></div>'}
    ${onb?`<div class="kslot">${res}</div>`:res}${onb?`<div class="khm">${I.info}<span>Другие модели — в настройках</span></div>`:''}` }   // в мастере место под итог занято заранее — ничего не подскакивает
/* ── Канон страниц настроек: шапка (назад, заголовок, подзаголовок) → поля 16 → разделы «заголовок + карточка». ── */
function page(title, sub, back, body){ return `<div class="layer appv">${statusBar(true)}<div class="topbar"><button class="iconbtn" data-act="${back}" aria-label="Назад">${I.back}</button><div class="t"><b>${title}</b>${sub?`<small>${sub}</small>`:''}</div></div><div class="scroll"><div class="mform canon">${body}</div></div></div>` }
function sect(title, inner, note){ return `<div class="sect">${title?`<div class="lh">${title}</div>`:''}${inner}${note?`<p class="mnote">${note}</p>`:''}</div>` }
function card(rows){ return `<div class="setcard">${rows}</div>` }
/** Строка «Статус»: проверяется сама при входе на страницу; точка и одна фраза, без плашек. */
function statusRow(st){ const t={check:'Проверяю…',ok:st.text||'Подключён',bad:st.text||'Не отвечает',off:'Не подключён'}[st.s];
  return `<button class="li" data-act="${st.act||''}"><span class="lic"><span class="stdot ${st.s}"></span></span><span class="lt"><b>Статус</b><small>${t}</small></span></button>` }
/** «Умеет» — один компонент у всех мозгов: значок, что, коротко. «Нет» — бледно. */
function capsCard(list){ return card(list.map(([ic,t,s,on])=>`<div class="li cap ${on?'':'off'}"><span class="lic">${ic}</span><span class="lt"><b>${t}</b><small>${s}</small></span></div>`).join('')) }

/** DeepSeek: «Подключение» (статус сам, адрес, модель, ключ) → «Умеет» → «Режимы» → «Отключить». Ключ — «Подключить»: проверить и сохранить. */
function brainPage(){ const b=brainById(S.editBrain); const users=S.modes.filter(m=>m.brain===b.id).map(m=>m.title); const on=S.w.key==='ok'; const K=S.keyCheck;
  const st = K&&K.stage==='run' ? {s:'check'} : !on ? {s:'off'} : S.brainSt==='check' ? {s:'check'} : {s:'ok', text:'Подключён · отвечает за 0,4 с'};
  const keyRow = S.keyEdit||!on
    ? `<div class="kedit"><div class="kfld"><input class="field" id="apikey" value="" placeholder="Ключ API · sk-…" autocomplete="off" spellcheck="false"><button class="intool" data-act="keypaste" aria-label="Вставить">${I.paste}</button></div>
       ${K&&K.stage==='bad'?`<p class="kerr">${K.err}</p>`:'<p class="mnote" style="margin:6px 2px 0">Где взять: platform.deepseek.com → API keys.</p>'}
       <div class="kacts">${on?'<button class="tbtn" data-act="keycanceledit">Отмена</button>':''}<button class="fbtn" data-act="keycheck">${K&&K.stage==='run'?'Подключаю…':'Подключить'}</button></div></div>`
    : li(I.lock,'Ключ','sk-…3f9a · только на этом телефоне','keyedit',false,'<span class="lact">Изменить</span>');
  return page(b.title, 'OpenAI-совместимый API', S.brainRet?'brainback':'settings',
    sect('Подключение', card(statusRow(st)+li(I.srv,'Адрес',b.url)+li(I.spark,'Модель',b.model)+keyRow))
    + sect('Умеет', capsCard([[I.cam,'Фото','Понимает снимки',true],[I.shield,'Действия телефона','Будильник, таймер, календарь',true],[I.lens,'Поиск в интернете','Скоро',false],[I.hist,'Память между днями','Нет — только разговор режима',false]]))
    + sect('Режимы', card(li(I.list, users.join(', ')||'Нет', 'Мозг выбирается в режиме','settings')))
    + (on?sect('', card(`<button class="li danger" data-act="keydel"><span class="lic">${I.del}</span><span class="lt"><b>Отключить</b><small>Ключ стирается с телефона</small></span></button>`)):'')) }
function blockedBanner(){ const miss=[...new Set(TOOLS.filter(toolBlocked).map(x=>x.perm))]; if(!miss.length) return '';
  const n=TOOLS.filter(toolBlocked).length;
  return `<div class="banner" style="margin:4px 16px 8px"><span class="bi">${I.warn}</span><p>${n} ${n===1?'действие не работает':n<5?'действия не работают':'действий не работают'}: нет доступа — ${miss.map(k=>SYS_PERMS[k]).join(', ')}.</p><div class="ba"><button class="tbtn" data-permfix="${miss[0]}">Исправить</button></div></div>` }
/** Системный экран разрешения приложения (Настройки → Приложения → Вахтёр → Разрешения → …). */
function sysPerm(){ const k=S.permKind, o=(v,t)=>`<button class="it" data-sysperm="${v}"><span class="rad ${(S.w.perms[k]==='ok')===(v==='ok')?'on':''}"></span>${t}</button>`;
  return `<div class="layer sys">${statusBar(true)}<div class="sbar"><button class="iconbtn" data-act="syspermback">${I.back}</button>${SYS_PERMS[k].replace(/[«»]/g,'')}</div><p style="margin:0 22px 6px;font-size:13px;color:#666">${k==='dnd'?'Настройки → Уведомления → Доступ к «Не беспокоить»':`Приложения → Вахтёр → Разрешения → ${SYS_PERMS[k]}`}</p><div class="grp">${k==='dnd'?o('ok','Вахтёр — разрешить')+o('denied','Не разрешать'):o('ok',k==='location'?'Разрешить только во время использования':'Разрешить')+o('denied','Не разрешать')}</div></div>` }
function actionsPage(){
  return `<div class="layer appv">${statusBar(true)}<div class="topbar"><button class="iconbtn" data-act="settings" aria-label="Назад">${I.back}</button><div class="t"><b>Действия на телефоне</b><small>что выполняется сразу, а что — после вопроса</small></div></div>
  <div class="scroll">${blockedBanner()}<div class="list">${TOOL_GROUPS.map(g=>`<div class="lh">${g}</div>${TOOLS.filter(x=>x.group===g).map(x=>`<button class="li prow" data-tool="${x.id}"><span class="lic">${I[x.icon]||I.shield}</span><span class="lt"><b>${x.title}</b>${x.sub&&!toolBlocked(x)&&!toolPending(x)?`<small>${x.sub}</small>`:''}${toolBlocked(x)?`<small>нет доступа: ${SYS_PERMS[x.perm]}</small>`:toolPending(x)?`<small class="pend">доступ «${SYS_PERMS[x.perm].replace(/[«»]/g,'')}» спросим при первом разе</small>`:''}</span><span class="pword ${toolBlocked(x)?'nope':S.policy[x.id]}">${toolBlocked(x)?'Нет доступа':POLICY_WORD[S.policy[x.id]]}</span></button>`).join('')}`).join('')}
    <div class="lh">Экран блокировки</div>${li(I.lock,'На заблокированном экране', LOCK_SCOPE.find(o=>o[0]===S.settings.lockScope)[1],'lockscope')}
    <p class="mnote" style="padding:4px 22px 20px">«Разрешено» — выполняется сразу, в разговоре видно, что сделано. «Спрашивать» — сначала показывается и ждёт «Да». Сообщения только готовятся — отправляете вы.</p></div></div></div>` }
function journalPage(){ const days=[...new Set(S.journal.map(j=>j.day))];
  return `<div class="layer appv">${statusBar(true)}<div class="topbar"><button class="iconbtn" data-act="settings" aria-label="Назад">${I.back}</button><div class="t"><b>Журнал действий</b><small>только на телефоне</small></div></div>
  <div class="scroll"><div class="list">${days.map(d=>`<div class="lh">${d}</div>${S.journal.filter(j=>j.day===d).map(j=>`<button class="jrow" data-jid="${j.id}" aria-expanded="${S.jopen===j.id}"><span class="jt">${j.at}</span><span class="jm"><b>${esc(j.title)}</b><small>${j.who}${j.status?' · '+j.status:''}</small></span></button>${S.jopen===j.id?`<div class="jd"><div><span>Фраза</span>«${esc(j.phrase)}»</div><div><span>Действие</span><code>${j.tool}</code> ${esc(j.params)}</div><div><span>Подтверждение</span>${j.confirm}</div><div><span>Экран</span>${j.lock?'заблокирован':'открыт'}</div>${j.undo?`<button class="tbtn" data-act="jundo" data-id="${j.id}">Отменить</button>`:''}</div>`:''}`).join('')}`).join('')}</div></div></div>` }
/** Режимы — своя страница: ручка ≡ слева (тянуть в любой момент, отдельного режима «Порядок» нет — как языки в настройках
 *  Android), значок, название, переключатель справа; тап по строке — страница режима. Порядок = порядок вкладок. */
function modesPage(){
  const rows=S.modes.map((m,i)=>`<div class="lirow mrow" data-ri="${i}"><span class="grip" data-drag="${i}" aria-label="Перетащить «${esc(m.title)}»">${I.grip}</span>${li(mIcon(m.icon),esc(m.title),modeSummary(m),'editmode:'+m.id)}${m.id==='main'?'<span class="swgap"></span>':`<button class="swbtn" data-menable="${m.id}" role="switch" aria-checked="${m.enabled}" aria-label="${esc(m.title)}"><span class="sw ${m.enabled?'':'off'}"></span></button>`}</div>`).join('');
  return page('Режимы','','settings', sect('', card(rows)+card(li(I.plus,'Новый режим','Название, промпт, что принимает','addmode')), 'Порядок — как во вкладках под полем. Перетащите за ≡.')) }
function about(){ const u=S.upd;
  const st = u.state==='available' ? `<div class="card"><div class="who p">Есть версия ${u.next}</div><div class="bd"><ul style="margin:0;padding-left:18px"><li>Уведомление, когда Hermes ответил</li><li>Таблицы и код в ответах</li><li>Лист открывается быстрее</li></ul></div><div class="btns"><button class="cb pri" data-act="upd">Обновить</button><button class="cb gh">Позже</button></div></div>`
    : u.state==='dl' ? `<div class="card"><div class="who p">Скачиваю ${u.next}</div><div class="bd"><div class="prog"><i style="width:${u.pct}%"></i></div><div style="font-size:12px;color:var(--soft);margin:6px 0">${u.pct} %</div></div><div class="btns"><button class="cb" data-act="updcancel">Отменить</button></div></div>` : '';
  return page('О программе','','settings',
    `<div class="abouthead">${oursIcon('width:64px;height:64px;border-radius:20px')}<b>${u.ver}</b><small>${u.state==='checking'?'Проверяю…':u.state==='latest'?'Это последняя версия':u.state==='fail'?'Не удалось проверить: нет связи':'стабильная версия'}</small></div>`
    + (st?sect('Обновление', st):'')
    + sect('', card(li(I.dl,'Проверить обновления','Сами — раз в сутки, когда открываете приложение','check')))) }

/** Hermes — тот же шаблон, что DeepSeek. Не подключён — вместо «Подключения» три способа (hermesBody). */
function hermesPage(){ const on=S.w.server==='paired'&&(!S.pair||S.pair.stage==='done'); const users=S.modes.filter(m=>m.brain==='hermes').map(m=>m.title);
  if (!on) return page('Hermes','Свой сервер','settings', hermesBody(false));
  const st = S.brainSt==='check' ? {s:'check'} : {s:'ok', text:'Подключён · отвечает за 0,6 с'};
  return page('Hermes','Свой сервер','settings',
    sect('Подключение', card(statusRow(st)+li(I.srv,'Адрес','hermes.example.ru')+li(I.lock,'Ключ телефона','Только на этом телефоне')))
    + sect('Умеет', capsCard([[I.hist,'Память','Общая с Telegram',true],[I.lens,'Поиск в интернете','Со ссылками на источники',true],[I.srv,'Команды на сервере','Опасное — после «Да»',true],[I.cam,'Фото','Если модель на сервере видит картинки',true],[I.shield,'Действия телефона','Пока нет — их делает сам телефон',false]]))
    + sect('Режимы', card(li(I.list, users.join(', ')||'Нет', 'Мозг выбирается в режиме','settings')))
    + sect('', card(`<button class="li danger" data-act="hermesoff"><span class="lic">${I.del}</span><span class="lt"><b>Отключить</b><small>Ключ телефона стирается; на сервере его можно отозвать</small></span></button>`))) }
/** Распознавание речи — по канону: «Модель» (что, где, сколько) → «Проверить» (поле с микрофоном, без замочка). */
function voicePage(){ const w=S.w;
  return page('Распознавание речи','','settings',
    sect('Модель', card(li(I.wave,'GigaAM v3', w.model==='ready'?`На телефоне · ${mb(MODEL_TOTAL)} МБ · голос не уходит наружу`:w.model==='loading'?`Качается: ${S.modelPct} %`:'Не скачана','dl',w.model!=='ready')))
    + (w.model==='ready'?sect('Проверить', `<div class="vtry tryonb"><div class="ttext">${S.rec&&S.rec.into==='try'?'<div class="tcap">Слушаю…</div>':S.recog?'<div class="tcap">Распознаю на телефоне…</div>':(S.tryShots||[]).length?`<div class="tbig">${esc(S.tryShots.at(-1).text)}</div>`:'<div class="tph">Скажите что-нибудь</div>'}</div>${composerHtml({into:'try'})}</div>`, 'Та же кнопка, что в листе: удержать — сказать — отпустить; вверх — без рук; влево — отмена.'):'')) }
// Подключение к Hermes через его API-сервер за Caddy (Архитектура 9.0, 9.8): ссылка-приглашение с ключом телефона или адрес и ключ руками.
const CHECKS=[['addr','Адрес'],['net','Соединение'],['tls','Сертификат'],['hermes','Hermes на сервере'],['key','Ключ этого телефона']];
function hermesBody(onb){
  const P=S.pair;
  if (!P) return `<div class="h2">Ваш сервер</div><p style="color:var(--soft);margin:0 0 12px">Hermes отвечает на всё, что сложнее команд телефона.</p>
    <div class="setcard">${li(I.chat,'Ссылка-приглашение','На сервере: vahter-invite.sh «имя телефона» — ссылку можно переслать себе в Telegram и открыть здесь','viatg')}${li(I.paste,'Вставить приглашение','Ссылка скопирована — вставить из буфера','viaqr')}${li(I.pen,'Ввести адрес и ключ','Адрес вида https://hermes.example.ru и ключ телефона из приглашения','viaaddr')}</div>`;
  if (P.via==='addr'&&P.stage==='input') return `<div class="h2">Адрес сервера</div>
    <input class="field ${P.err?'err':''}" id="addr" value="${esc(P.addr||'https://hermes.example.ru')}" autocomplete="off" spellcheck="false">
    <input class="field" id="tok" value="" placeholder="Ключ телефона из приглашения" autocomplete="off" spellcheck="false" style="margin-top:8px">
    <p style="font-size:12.5px;color:${P.err?'var(--danger)':'var(--soft)'}">${P.err||'Ключ телефона хранится в Keystore; ключ API Hermes телефон не знает — его подставляет сервер'}</p>
    <div style="display:flex;justify-content:flex-end;gap:6px"><button class="tbtn" data-act="pairreset">Назад</button><button class="fbtn" data-act="addrgo">Подключиться</button></div>`;
  const ck=P.checks.map(c=>`<div class="ck ${c.s}"><span class="s">${c.s==='ok'?'✓':c.s==='bad'?'!':''}</span><span>${c.t}${c.d?`<small>${c.d}</small>`:''}</span></div>`).join('');
  let tail='';
  if (P.stage==='fail') tail=`<div class="note bad">${P.err}</div><div style="display:flex;justify-content:flex-end;gap:6px;margin-top:10px"><button class="tbtn" data-act="pairreset">Другой способ</button><button class="fbtn" data-act="${P.retry}">${P.retryLabel}</button></div>`;
  if (P.stage==='done') tail=`<div class="note ok">Подключено. На сервере этот телефон — «S24 Ultra» (vahter-revoke.sh отзывает ключ). Сессии режимов живут на сервере, память Hermes общая с Telegram.</div>${onb?'':'<div style="display:flex;justify-content:flex-end;margin-top:10px"><button class="fbtn" data-act="tochat">К разговору</button></div>'}`;
  return `<div class="h2">${P.stage==='done'?'Подключено':'Подключаюсь'}</div><div class="checks">${ck}</div>${tail}`;
}
function sysRole(){ const o=[['gemini','Gemini'],['ours','Вахтёр']]; return `<div class="layer sys">${statusBar(true)}<div class="sbar"><button class="iconbtn" data-act="sysback">${I.back}</button>Цифровой помощник</div><p style="margin:0 22px 6px;font-size:13px;color:#666">Настройки → Приложения → Выбор приложений по умолчанию</p><div class="grp">${o.map(x=>`<button class="it" data-role="${x[0]}"><span class="rad ${S.w.role===x[0]?'on':''}"></span>${x[1]}</button>`).join('')}</div></div>` }
function sysMic(){ const o=(v,t)=>`<button class="it" data-micperm="${v}"><span class="rad ${S.w.mic===v?'on':''}"></span>${t}</button>`;
  return `<div class="layer sys">${statusBar(true)}<div class="sbar"><button class="iconbtn" data-act="sysback">${I.back}</button>Микрофон</div><p style="margin:0 22px 6px;font-size:13px;color:#666">Приложения → Вахтёр → Разрешения → Микрофон</p><div class="grp">${o('ok','Разрешить только во время использования приложения')}${o('ask','Спрашивать каждый раз')}${o('denied','Запретить')}</div></div>` }
function sysPick(){ const k=S.picker; const items=k==='gallery'?[['menu','Меню, стр. 1 · 19:42'],['menu2','Меню, стр. 2 · 19:42'],['tea','Чай · 18:10']]:[['Договор аренды.pdf','248 КБ · Загрузки'],['Счёт октябрь.xlsx','36 КБ · Документы'],['Паспорт объекта.pdf','1,2 МБ · Загрузки']];
  return `<div class="layer sys">${statusBar(true)}<div class="sbar"><button class="iconbtn" data-act="pickback">${I.back}</button>${k==='gallery'?'Выберите фото':'Недавние файлы'}</div>
  ${k==='gallery'?`<div class="pgrid">${items.map(([c,t])=>`<button data-pickitem="${c}" aria-label="${t}" aria-pressed="${(S.pickSel||[]).includes(c)}"><i class="th ${c}"></i><span class="psel">${(S.pickSel||[]).includes(c)?(S.pickSel.indexOf(c)+1):''}</span></button>`).join('')}</div><div style="display:flex;justify-content:flex-end;padding:12px 16px"><button class="fbtn" data-act="pickdone" ${(S.pickSel||[]).length?'':'disabled'}>Готово${(S.pickSel||[]).length?' ('+S.pickSel.length+')':''}</button></div>`:`<div class="grp">${items.map(([n,s])=>`<button class="it" data-pickitem="${n}|${s.split(' · ')[0]}">${I.doc}<span><b style="font-weight:500">${n}</b><br><small style="color:#777">${s}</small></span></button>`).join('')}</div>`}
  <p style="margin:12px 22px;font-size:12.5px;color:#777">Системный ${k==='gallery'?'выбор фото (Photo Picker)':'выбор файла (ACTION_OPEN_DOCUMENT)'} — разрешения на все файлы приложению не нужны.</p></div>` }
/* Экран боковой кнопки Samsung; как в One UI, под «Цифровым помощником» — кто им выбран. */
function sysSide(){ const o=(v,t,sub)=>`<button class="it" data-side="${v}"><span class="rad ${S.w.side===v?'on':''}"></span><span>${t}${sub?`<small style="display:block;font-size:12.5px;color:#3E6FD8;margin-top:2px">${sub}</small>`:''}</span></button>`; return `<div class="layer sys">${statusBar(true)}<div class="sbar"><button class="iconbtn" data-act="sysback">${I.back}</button>Боковая кнопка</div><p style="margin:0 22px 6px;font-size:13px;color:#666">Настройки → Дополнительные функции → Боковая кнопка</p><p style="margin:14px 24px 4px;font-size:13px;color:#3E6FD8;font-weight:600">Нажатие и удержание</p><div class="grp">${o('bixby','Bixby')}${o('assistant','Цифровой помощник',S.w.role==='ours'?'Вахтёр':'Gemini')}${o('power','Меню выключения')}</div><p style="margin:12px 24px;font-size:12.5px;color:#777">«Цифровой помощник» открывает того, кто выбран помощником в настройках Android — это отдельная настройка.</p></div>` }
function tg(){ return `<div class="layer tg">${statusBar(false)}<div class="top"><button class="iconbtn" data-act="home" style="color:#fff">${I.back}</button><span class="av">H</span><span><b>Hermes</b><br><small style="opacity:.8">бот</small></span></div><div class="msgs" id="msgs">${S.tg.map(m=>`<div class="bub ${m.me?'me':''}">${m.html}</div>`).join('')}</div><div class="compose"><span class="in">${S.tg.length?'Сообщение':'Сделай приглашение для Вахтёра для S24'}</span><button class="cb pri" data-act="tgsend" ${S.tg.length?'disabled':''}>➤</button></div></div>` }
function cam(){ return `<div class="layer cam">${statusBar(false)}<div class="frame"><div class="scanline"></div></div><p style="position:absolute;top:450px;width:100%;text-align:center;font-size:14px">Наведите на QR с экрана компьютера</p></div>` }
function qrSvg(){ let s=''; for(let i=0;i<144;i++){ const r=Math.floor(i/12),c=i%12; const f=(r<3&&c<3)||(r<3&&c>8)||(r>8&&c<3); s+=`<i style="background:${f||((r*7+c*13+r*c)%3===0)?'#111':'#fff'}"></i>` } return `<div class="qr">${s}</div>` }

/** Пометки поверх снимка: координаты в процентах кадра (0–100). */
function marksSvg(marks, crop, draft){
  const all = draft ? marks.concat(draft.k==='crop'?[]:[draft]) : marks;
  const path = m => m.k==='circle' ? `<path d="M${m.pts.map(p=>p.join(' ')).join(' L')}" />` : `<line x1="${m.a[0]}" y1="${m.a[1]}" x2="${m.b[0]}" y2="${m.b[1]}" marker-end="url(#ah)"/>`;
  const cr = draft&&draft.k==='crop' ? {x:Math.min(draft.a[0],draft.b[0]),y:Math.min(draft.a[1],draft.b[1]),w:Math.abs(draft.a[0]-draft.b[0]),h:Math.abs(draft.a[1]-draft.b[1])} : crop;
  return `<svg class="marks" viewBox="0 0 100 100" preserveAspectRatio="none"><defs><marker id="ah" viewBox="0 0 10 10" refX="7" refY="5" markerWidth="5" markerHeight="5" orient="auto"><path d="M0 0L10 5L0 10z" fill="#FF3B30"/></marker></defs>
    ${cr?`<path class="cropdim" fill-rule="evenodd" d="M0 0H100V100H0Z M${cr.x} ${cr.y}h${cr.w}v${cr.h}h${-cr.w}Z"/><rect class="croprect" x="${cr.x}" y="${cr.y}" width="${cr.w}" height="${cr.h}"/>`:''}
    <g class="ink">${all.map(path).join('')}</g></svg>` }
/** Размер миниатюры — в пропорциях снимка (как был виден в видоискателе; по умолчанию кадр телефона 400×820), с учётом обрезки; иначе пометки съедут. */
function thumbStyle(img,H=200,maxW=230,minW=110){ if(img.kind==='album') return 'width:206px'; const c=img.crop, a=(c?c.w:100)*(img.ar||400/820)/(c?c.h:100); let h=H, w=h*a; if(w>maxW){ w=maxW; h=w/a } if(w<minW){ w=minW; h=Math.min(H*1.3,w/a) } return `width:${Math.round(w)}px;height:${Math.round(h)}px` }
/** Миниатюра снимка в разговоре: обрезка применена, пометки впечены. */
function thumbHtml(img){ if(img.kind==='album') return `<div class="alb">${img.items.map(x=>`<div class="thw" style="${thumbStyle(x,130,100,48)}">${thumbHtml(x)}</div>`).join('')}</div>`; const c=img.crop; const inner=`<i class="th ${img.cls}"></i>${img.marks&&img.marks.length?marksSvg(img.marks,null):''}`;
  return c ? `<span class="cropbox" style="width:${10000/c.w}%;height:${10000/c.h}%;left:${-c.x*100/c.w}%;top:${-c.y*100/c.h}%">${inner}</span>` : inner }
function cam2(){ const c=S.cam; const m=curMode(); const qrec=S.rec&&S.screen==='cam2';
  const tools=[['crop','Обрезать',I.crop],['circle','Обвести',I.circle],['arrow','Стрелка',I.arrow]];
  const hint={crop:'Выделите нужную часть',circle:'Обведите пальцем',arrow:'Проведите от начала к острию'}[c.tool];
  const ins=!c.full;
  const vf=`<div class="vf ${c.frozen?'frozen':''}" ${c.tool?'data-draw':''}><i class="th ${c.subj}"></i>${marksSvg(c.marks,c.crop,c.draft)}</div>`;
  const hdr=`<button class="grab" aria-label="Развернуть"></button><div class="hdr"><span class="ldot"></span><span><b style="color:var(--ink);font-weight:600">${esc(m.title)}</b> · на связи</span><span class="sp"></span><button class="iconbtn" data-act="camfull" aria-label="Камера на весь экран">${I.expand}</button><button class="iconbtn" data-act="camcancel" aria-label="Закрыть камеру">${I.x}</button></div>`;
  return `<div class="layer cam2 ${ins?'insheet':''}">${ins?`${statusBar(true)}<div class="csheet">${hdr}<div class="vfwrap">${vf}`:`${vf}${statusBar(false)}`}
  <div class="ctop"><button class="cbtn" data-act="${c.frozen?'camretake':'camcancel'}" aria-label="${c.frozen?'Переснять':'Закрыть камеру'}">${I.x}</button><span class="modeName2">${I[m.icon]} ${esc(m.title)}</span>${c.frozen&&(c.marks.length||c.crop)?`<button class="cbtn" data-act="camundo" aria-label="Отменить последнее">${I.undo}</button>`:'<span style="width:40px"></span>'}</div>
  ${hint?`<div class="chint">${hint}</div>`:''}
  ${qrec?`<div class="q">${recText()||'…'}</div>`:''}
  ${ins?'</div>':''}<div class="cbottom">
    ${c.frames.length?`<div class="cstrip">${c.frames.map((f,i)=>`<div class="cfr ${c.editIdx===i?'on':''}"><button class="cfrb" data-frame="${i}" aria-label="Кадр ${i+1}: править"><span class="thw" style="${thumbStyle(f,64,60,24)}">${thumbHtml(f)}</span></button><button class="cfrx" data-framedel="${i}" aria-label="Удалить кадр ${i+1}">${I.x}</button></div>`).join('')}</div>`:''}
    <div class="ctools">${tools.map(([k,t,ic])=>`<button class="ctool" data-ctool="${k}" aria-pressed="${c.tool===k}">${ic}<span>${t}</span></button>`).join('')}</div>
    <div class="crow"><button class="cplus" data-act="camadd" aria-label="Оставить кадр и снять ещё" ${c.editIdx==null&&c.frames.length>=camMax()-1?'disabled':''}>${I.plus}</button><button class="csend" data-act="camsend" aria-label="Отправить${c.frames.length?' все кадры':''}">${I.send}${(c.frames.length+(c.editIdx==null?1:0))>1?`<b class="ccount">${c.frames.length+(c.editIdx==null?1:0)}</b>`:''}</button><button class="mic2 ${qrec?'on':''}" data-mic aria-label="Зажмите и спросите голосом">${I.mic}</button></div>
  </div>${ins?modeTabs()+'</div>':''}</div>` }
function shortcutsPop(){ if(!S.shortcuts) return ''; return `<div class="sc-pop"><small>Вахтёр</small>${activeModes().map(m=>`<button data-shortcut="${m.id}"><span style="width:22px;display:grid;place-items:center;color:#555">${I[m.icon]}</span>${m.title}</button>`).join('')}</div>` }
/** Знак Вахтёра — «лампа на вахте»: ядро и два кольца. Состояние листа задаёт цвет и движение (CSS, data-st). */
function vMark(cls=''){ return `<span class="vmark ${cls}" aria-hidden="true"><i class="r2"></i><i class="r1"></i><i class="core"></i></span>` }
/** Что сейчас делает лист: ждёт, слушает, распознаёт, работает (телефон или мозг — один цвет, янтарный; кто — видно подписью). */
function sheetSt(){ if(S.rec) return 'rec'; if(S.recog) return 'recog'; if(S.busy||(S.ov&&S.ov.live&&/st h/.test(S.ov.live))) return 'think'; return 'idle' }
/** Пустой лист — сцена, как у ассистентов: знак по центру и одна фраза. Чипы — только в первый раз (финал мастера). */
function sheetEmpty(){
  const st=sheetSt(), m=curMode(), main=m.id==='main';
  const cap = st==='rec'?'Слушаю':st==='recog'?'Распознаю на телефоне':main?'Вам чего?':esc(m.title);
  const sub = st==='rec'?'Отпустите — и отправлю':st==='recog'?'Голос не уходит с телефона':main?'':esc(m.hint||'');
  // три чипа: два в ряд, третий — по центру второго ряда. Без мозга — только то, что телефон делает сам; с мозгом таймер уступает вопросу
  const c=['⏰ Будильник на 7', brainReady()?'🔦 Включи фонарик':'⏱ Таймер на 5 минут', brainReady()?'Что посмотреть в Казани?':'🔦 Включи фонарик'];
  const chips = main&&S.firstSheet&&st==='idle' ? `<div class="chips">${c.map(x=>`<button class="chip" data-saytext="${x.replace(/^\S+ (?=[А-Я])/,'')}">${x}</button>`).join('')}</div>` : '';
  const prev = sheetFrom()>0&&st==='idle' ? (()=>{ const n=S.feed.slice(0,sheetFrom()).filter(x=>x.t==='me'||x.t==='ai').length; return `<button class="prevconv" data-act="expand">${I.hist}<span>Прошлый разговор · ${n} сообщ.</span>${I.chev}</button>` })() : '';
  return `<div class="feed scenef"><div class="scene">${vMark('big')}<b>${cap}</b>${sub?`<small>${sub}</small>`:''}${chips}${prev}</div></div>` }
/** Строка в листе, пока мозга нет: без шутки, со ссылкой на экран мозга (назад — снова лист). */
function sheetStrip(){
  if (S.w.model==='loading') return `<div class="ostrip"><span>${I.dl}</span><p>Распознавание качается — ${S.modelPct} %, пока клавиатура</p></div>`;
  // Пока голоса нет, строка не пропадает: лист открыли раньше, чем докачалось, или загрузку не начинали. «Скачать» — прямо отсюда.
  if (S.w.model!=='ready') return `<div class="ostrip"><span>${I.dl}</span><p>Голос не скачан — пока клавиатура</p><button class="tbtn" data-act="sheetdl">Скачать</button></div>`;
  if (curMode().id==='main' && !brainReady() && brainOf().kind==='api') return `<div class="ostrip"><span>🧠</span><p>Мозг не подключён — пока только команды телефона</p><button class="tbtn" data-act="brainfromsheet">Подключить</button></div>`;
  return '' }
function sideCue(){ if (!S.sideCue || !S.ov) return ''; return `<div class="sidecue">${S.w.profile==='unknown'?'Так же — долгое нажатие «Домой»':'Так открывается по боковой кнопке'}<i></i></div>` }
/* Лист без шапки: слева — маленький знак (и строка связи, только если что-то не так), по центру ручка, справа — круглые кнопки.
   В полном экране сверху проявляется шапка приложения — та же, что в chat(), чтобы подмена на экран приложения была незаметной. */
function overlay(){
  const O=S.ov; if(!O) return ''; const [d,t]=linkState(); const st=sheetSt(), empty=!sheetItems().length;
  const anim = O.full ? `full grow` : O.shrink ? 'shrink' : '';
  const style = O.full ? `--from:${O.fromH||400}px` : O.shrink ? `--to:${O.shrinkTo||400}px` : '';
  return `<div class="ov"><div class="dim" data-act="dim" style="${O.full?'opacity:0':''}"></div><div class="sheet s2 ${anim} ${O.pop?'pop':''} ${empty?'isempty':''}" data-st="${st}" style="${style}">
    <div class="glow"></div>
    <div class="ftop topbar"><button class="iconbtn" aria-label="Свернуть в лист">${I.down}</button><div class="t"><b>${curMode().title}</b><small><span class="ldot ${d}"></span>${brainOf().title} · ${t}</small></div><button class="iconbtn">${I.gear}</button><button class="iconbtn">${I.more}</button></div>
    <div class="shead">${empty?'<span></span>':`<span class="sst">${vMark('mini')}${d?`<span>${t}</span>`:''}</span>`}
      <button class="grab" data-act="expand" aria-label="Развернуть"></button>
      <span class="sbtns"><button class="cbtn" data-act="gear" aria-label="Настройки">${I.gear}</button><button class="cbtn" data-act="expand" aria-label="Развернуть в приложение">${I.expand}</button><button class="cbtn" data-act="close" aria-label="Закрыть">${I.x}</button></span></div>
    ${promptHtml()}${sheetStrip()}<div class="scroll" id="oscroll">${feedHtml(!O.full)}</div>
    ${empty&&(S.rec||S.recog)?'':liveHtml()}${composerHtml()}
  </div>${sideCue()}</div>`;
}
function dialog(){ const D=S.dialog; if(!D) return '';
  if (D.radio) return `<div class="scrim"><div class="dlg rdlg"><h3>${D.title}</h3>${D.radio.map(([v,t])=>`<button class="ropt" data-radio="${v}"><span class="rad ${D.cur===v?'on':''}"></span>${t}</button>`).join('')}<div class="dfoot"><button class="tbtn" data-dlgclose>Отмена</button></div></div></div>`;
  if (D.ours) return `<div class="scrim"><div class="osheet"><div class="g"></div>${D.ic?`<div class="di">${D.ic}</div>`:''}<h3>${D.title}</h3>${D.text?`<p>${D.text}</p>`:''}<div class="oacts">${D.opts.map((o,i)=>`<button class="${o[2]?'tbtn':'fbtn'}" data-dlg="${i}">${o[0]}</button>`).join('')}</div></div></div>`;   // наше окно, не системное
  return `<div class="scrim"><div class="dlg">${D.ic?`<div class="di">${D.ic}</div>`:''}<h3>${D.title}</h3>${D.text?`<p>${D.text}</p>`:''}${D.opts.map((o,i)=>`<button class="opt ${o[2]||''}" data-dlg="${i}">${o[0]}</button>`).join('')}</div></div>` }
function toast(){ const T=S.toast; if(!T) return ''; return `<button class="toast" data-act="toast">${oursIcon()}<span><b>${T.title}</b><span>${T.text}</span></span></button>` }

/* Перерисовка пересоздаёт весь экран — без этого анимации появления (уведомление, окно, меню, сцены приветствия)
   проигрывались бы заново на каждом render(): уведомление «выпрыгивало» по нескольку раз, пока идёт ответ.
   Элемент с тем же ключом продолжает анимацию с того места, где был, а не с начала. */
/* Фоновые перерисовки (загрузка модели и т. п.) ждут, пока палец не отпущен: иначе кнопка под пальцем пересоздаётся
   между нажатием и отпусканием и клик теряется («Далее» срабатывало со второго раза). */
let pressing=false, pendRender=false;
document.addEventListener('pointerdown',()=>{ pressing=true },true);
const unpress=()=>{ pressing=false; if(pendRender){ pendRender=false; setTimeout(()=>{ S.keepScroll=true; render() },0) } };
document.addEventListener('pointerup',unpress,true); document.addEventListener('pointercancel',unpress,true);
function softRender(){ if(pressing){ pendRender=true; return } S.keepScroll=true; render() }
const ANIM_KEYS=[['.kres',()=>S.keyCheck&&S.keyCheck.stage],['.toast',()=>S.toast&&S.toast.title+S.toast.text],['.scrim',()=>S.dialog&&S.dialog.title],['.mpicker',()=>S.attachMenu],['.cmenu',()=>S.chatMenu],['.sc-pop',()=>S.shortcuts],['.onb .art',()=>S.onbStep],['.scv',()=>'voice'],['.tsun.freed',()=>S.modelReadyAt],['.s2 .glow',()=>sheetSt()],['.s2 .vmark',()=>sheetSt()],['.s2 .shead .vmark',()=>sheetSt()],['.s2.grow',()=>'grow'],['.s2.shrink',()=>'shrink']];
const animBorn=new Map();
function keepAnims(){
  const now=performance.now(), live=new Set();
  for (const [sel,keyf] of ANIM_KEYS){ const el=$('#scr '+sel); if(!el) continue; const k=sel+'|'+keyf(); live.add(k);
    if(!animBorn.has(k)){ animBorn.set(k,now); continue }
    const t=now-animBorn.get(k); el.getAnimations({subtree:true}).forEach(a=>{ try{ a.currentTime=t }catch{} }) }
  for (const k of [...animBorn.keys()]) if(!live.has(k)) animBorn.delete(k);
}
function grow(t){ t.style.height='auto'; t.style.height=t.scrollHeight+'px' }
function render(){
  let base='';
  if (S.screen==='lock') base=lock();
  else if (S.screen==='home') base=home();
  else if (S.screen==='app') base = !S.onboarded ? onb() : S.page==='settings'?settings():S.page==='mode'?modePage():S.page==='brain'?brainPage():S.page==='actions'?actionsPage():S.page==='voice'?voicePage():S.page==='modes'?modesPage():S.page==='journal'?journalPage():S.page==='about'?about():S.page==='hermes'?hermesPage():chat();
  else if (S.screen==='tg') base=tg(); else if (S.screen==='cam') base=cam(); else if (S.screen==='cam2') base=cam2();
  else if (S.screen==='sysrole') base=sysRole(); else if (S.screen==='sysside') base=sysSide(); else if (S.screen==='sysmic') base=sysMic(); else if (S.screen==='syspick') base=sysPick(); else if (S.screen==='sysperm') base=sysPerm();
  const prev={}; ['#scroll','#oscroll'].forEach(s=>{ const el=$(s); if(el) prev[s]=el.scrollTop });
  const ae=document.activeElement, aeId=ae&&ae.id, aePos=ae&&ae.selectionStart;
  if (S.unlocking) base += `<div class="unlk"><div class="fp">${I.lock}</div><b>Разблокируйте</b><small>Приложите палец к сканеру</small></div>`;
  $('#scr').innerHTML = base + (S.screen==='cam2'?'':overlay()) + (S.screen==='home'?shortcutsPop():'') + dialog() + toast();
  document.querySelectorAll('#scr .fieldarea').forEach(grow);
  $('#side')?.classList.toggle('pressed', !!(S.sideHeld || (S.rec&&S.rec.side&&S.ov)));   // обвязка демо: кнопка корпуса вдавлена, пока держат или пока идёт запись с неё
  document.querySelectorAll('#scr .mtabs').forEach(t=>{ const b=t.querySelector('[aria-pressed=true]'); if(b) t.scrollLeft=b.offsetLeft-(t.clientWidth-b.offsetWidth)/2; tabSnap(t) });
  if (S.emojiPick) $('#emoin')?.focus();
  if (aeId==='cin'){ const el=$('#cin'); if(el){ el.focus(); try{ el.setSelectionRange(aePos,aePos) }catch{} } }
  if (S.ov) S.ov.pop=false;
  keepAnims();
  eqs=[...document.querySelectorAll('#scr .eq')].map(e=>new Eq(e));
  ['#scroll','#oscroll','#msgs'].forEach(s=>{ const el=$(s); if(!el) return; if(S.keepScroll && prev[s]!=null) el.scrollTop=prev[s]; else el.scrollTop=el.scrollHeight });
  S.keepScroll=false;
  if (S.scrollToMsg){ const id=S.scrollToMsg; S.scrollToMsg=null; requestAnimationFrame(()=>{ const el=$('#scroll'); const tgt=el&&el.querySelector(`.ai[data-msg="${id}"]`); if(tgt){ const k=el.getBoundingClientRect().height/el.offsetHeight||1; el.scrollTop += (tgt.getBoundingClientRect().top - el.getBoundingClientRect().top)/k - 8 } }) }
  renderWorld();
}

