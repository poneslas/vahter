/* Обвязка демо, в Android не переносится: «мир», боковая кнопка корпуса, сценарии, знакомство, масштаб телефона. */
/* ───────── мир ───────── */
const WORLD=[['net','Сеть',[['wifi','Wi-Fi'],['cell','Мобильная'],['none','Нет']]],['key','Ключ DeepSeek',[['none','Нет'],['ok','Есть'],['bad','Отозван']]],['server','Hermes (v2)',[['none','Не подключён'],['paired','Подключён'],['down','Не отвечает']]],['model','Модель',[['none','Нет'],['loading','Качается'],['ready','Готова'],['broken','Повреждена']]],['locked','Экран',[[false,'Открыт'],[true,'Заблокирован']]],['role','Помощник',[['gemini','Gemini'],['bixby','Bixby'],['ours','Тася']]],['mic','Микрофон',[['ask','Не спрашивали'],['ok','Разрешён'],['denied','Запрещён'],['call','Идёт звонок']]]];
function renderWorld(){
  $('#world').innerHTML=WORLD.map(([k,l,o])=>`<label>${l}</label><div class="seg">${o.map(x=>`<button data-w="${k}" data-v="${x[0]}" aria-pressed="${S.w[k]===x[0]}">${x[1]}</button>`).join('')}</div>`).join('')
    + `<label>Без дела</label><div class="seg">${[[0,'только что'],[31,'31 мин']].map(x=>`<button data-idle="${x[0]}" aria-pressed="${(S.idle>=30?31:0)===x[0]}">${x[1]}</button>`).join('')}</div>`
    + `<label>Загрузка фото</label><div class="seg">${[['fast','быстро'],['slow','медленно'],['fail','обрыв']].map(x=>`<button data-up="${x[0]}" aria-pressed="${(S.upSpeed||'fast')===x[0]}">${x[1]}</button>`).join('')}</div>`
    + `<label>В кадре камеры</label><div class="seg">${[['tea','Товар'],['menu','Меню']].map(x=>`<button data-shot="${x[0]}" aria-pressed="${(S.cam?S.cam.subj:S.camSubj||'tea')===x[0]}">${x[1]}</button>`).join('')}</div>`
   +`<label>Сбой подключения</label><div class="seg">${[['','нет'],['dns','адрес'],['tls','сертификат'],['plugin','нет плагина'],['expired','ссылка устарела']].map(x=>`<button data-fail="${x[0]}" aria-pressed="${(S.failNext||'')===x[0]}">${x[1]}</button>`).join('')}</div>`;
  const canSay = !S.busy && (S.ov || (S.screen==='app'&&S.onboarded&&S.page==='chat'));
  document.querySelectorAll('#say button').forEach(b=>b.disabled=!canSay);
}
function setWorld(k,v){
  S.w[k]=v; S.dismissed=null;
  if (k==='model'){ if(v==='loading'){ if(!S.modelPct||S.modelPct>=100) S.modelPct=12; startDl(true) } if(v==='ready') S.modelPct=100; if(v==='none') S.modelPct=0 }
  const why={net:{wifi:'Wi-Fi: соединение восстанавливается, очередь уходит по порядку.',cell:'Мобильная сеть: всё работает, но 240 МБ без спроса не качаем.',none:'Нет сети: команды телефона работают, вопросы Hermes ждут в очереди, загрузка модели на паузе.'},server:{none:'Сервер не подключён: в разговоре баннер «Подключить».',paired:'Подключено: вход подписью ключа телефона.',down:'Сервер не отвечает: переподключение с паузами, вопросы в очереди.'},model:{none:'Модели нет: лист открывается, вместо голоса — клавиатура, в разговоре — баннер.',loading:'Модель качается: прогресс в баннере.',ready:'Модель готова.',broken:'Не сошлась контрольная сумма — файл не используем.'},locked:{true:'Экран заблокирован: вопросы да, действия с данными — через разблокировку.',false:'Разблокировано.'},role:{gemini:'Помощник — Gemini.',bixby:'Помощник — Bixby.',ours:'Помощник — Тася; держится после обновлений (объявлен RecognitionService).'},mic:{ask:'Микрофон спросим при первом открытии листа.',ok:'Микрофон разрешён.',denied:'Микрофон запрещён: клавиатура и баннер «Разрешить».',call:'Идёт звонок: микрофон занят.'}};
  trace(why[k]?.[String(v)]||'', (v==='none'&&k==='net')||v==='down'||v==='denied'||v==='broken'?'e':'');
  if (k==='net'&&v!=='none'&&brainReady()) flushQueue();
  if (k==='locked'){ S.screen = v ? 'lock' : (S.screen==='lock'?'home':S.screen); if(v) S.ov=null }
  render();
}


let holdT=null; const side=$('#side');
side.addEventListener('pointerdown',()=>{ side.classList.add('hold'); holdT=setTimeout(()=>{ holdT=null; side.classList.remove('hold'); longPress() },450) });
side.addEventListener('pointerup',()=>{ if(holdT){ clearTimeout(holdT); holdT=null; side.classList.remove('hold'); S.ov=null; S.w.locked=true; S.screen='lock'; render() } });
side.addEventListener('pointerleave',()=>{ if(holdT){ clearTimeout(holdT); holdT=null; side.classList.remove('hold') } });
side.addEventListener('keydown',e=>{ if(e.key==='Enter'||e.key===' '){ e.preventDefault(); longPress() } });
function longPress(){
  if (!S.sideOk){ S.dialog={ic:'⏻',title:'Меню выключения',text:'Боковая кнопка ещё не настроена на помощника.',opts:[['Понятно','x']]}; return render() }
  if (S.w.role!=='ours'){ const who=S.w.role==='bixby'?'Bixby':'Gemini'; S.dialog={ic:'✦',title:who,text:`Открылся ${who}: он выбран помощником.`,opts:[['Закрыть','x']]}; return render() }
  openOverlay('side');
}
async function event(k){
  if (k==='approve'){ if(S.pair?.stage==='code'){ trace('Владелец: hermes pairing approve tasya K7Q2M9XD','h'); return pairDone() } return }
  if (k==='ctx'){ S.feed.push({t:'card',who:'h',head:brainOf().title+' · ошибка',title:'Разговор слишком длинный',text:'Модель больше не помещает всю историю режима. Сжимать не будем — начните заново: промпт и настройки останутся.',btns:[['Начать заново','restart','pri']]}); trace('Провайдер вернул ошибку длины контекста. Сжатия нет по решению — честно предлагаем начать разговор режима заново.','e'); S.keepScroll=true; return render() }
  if (k==='push'){ if(!brainReady()) return; S.feed.push({t:'ai',x:'<p>Готово: три рецепта сырников без муки, лучший — на рисовой.</p>',done:true}); if(S.ov||(S.screen==='app'&&S.page==='chat')) return render(); if(S.w.notif!=='ok'){ trace('Ответ пришёл, уведомления выключены — увидите при открытии.','e'); return } trace(brainOf().kind==='api'?'Лист закрыли, а запрос к DeepSeek дожала фоновая работа приложения; ответ сохранён в разговор режима, уведомление показал сам телефон — без своего сервера и пуш-службы.':'Пуш без содержимого через свой ntfy → телефон забирает текст и сам показывает уведомление.','h'); return showToast(curMode().title,'Готово: три рецепта сырников…','push') }
  if (k==='cron'){ S.feed.push({t:'sys',x:'<b>По расписанию</b> · утренняя сводка'}); S.feed.push({t:'ai',id:'m'+(++msgSeq),x:'<p>Сегодня: банк в 10:00, созвон с Олей в 15:30. Погода +12, к вечеру дождь.</p>',done:true}); trace('Cron приходит вне хода (adapter.send с job_id). Обёртку «Cronjob Response: …» выключаем (cron.wrap_response: false) и рисуем свою плашку по job_id.','h'); if(!S.ov&&!(S.screen==='app'&&S.page==='chat')&&S.w.notif==='ok') return showToast('Hermes · по расписанию','Сегодня: банк в 10:00…','push'); return render() }
  if (k==='bg'){ S.feed.push({t:'sys',x:'Задача на сервере завершилась · <code>pytest</code> · 12 с'}); trace('Фоновые процессы приходят вне хода текстом без маркера («✅ Background task finished — `cmd`»). Узнаём по шаблону известных строк и показываем системной строкой по-русски; незнакомое — как есть. Можно выключить: background_process_notifications: off.','h'); return render() }
  if (k==='rate'){ S.feed.push({t:'err',x:'Сервис модели ограничивает запросы. Попробуйте через минуту.'}); trace('Ошибки провайдера приходят как финальный ответ («⏱️ The AI model service is rate-limiting…»), без отдельного маркера. Распознаём по словарю известных строк Hermes → красная плашка по-русски. Незнакомые — показываем исходный текст.','e'); return render() }
  if (k==='update'){ S.upd.state='available'; showToast('Есть версия 0.5.0','Что нового — в «О программе»'); return render() }
}
const ready=()=>{ S=fresh(); S.onboarded=true; S.page='chat'; S.sideOk=true; Object.assign(S.w,{role:'ours',mic:'ok',notif:'ok',key:'ok',model:'ready'}) };
/** Версия 2: Ассистент на Hermes. */
const readyH=()=>{ ready(); S.w.server='paired'; modeById('main').brain='hermes' };
const PRESETS=[
  ['Первый запуск','всё с нуля',()=>{ S=fresh(); S.screen='app'; S.onboarded=false },'start','first'],
  ['Обычный день','всё готово — зажмите кнопку',()=>{ ready() },'day','day'],
  ['Сказал криво: «алярмер»','правила не поняли — DeepSeek вызвал действие телефона',()=>{ ready(); S.autoSay='alarmfz' },'day','alarmfz'],
  ['Длинный разговор','предел контекста модели → «Начать заново»',()=>{ ready(); S.autoEv='ctx' },'fail','ctx'],
  ['Серия сообщений','промежуточное, длинный Markdown, PDF, картинка, короткий текст',()=>{ readyH(); S.autoSay='backup' },'brain','series'],
  ['Картинки','альбом из image_generate',()=>{ readyH(); S.autoSay='logo' },'brain','images'],
  ['Подтверждение команды','редкий случай: Hermes сомневается',()=>{ readyH(); S.autoSay='logs' },'brain','confirm'],
  ['Уточнение','варианты и «Другое»',()=>{ readyH(); S.autoSay='table' },'brain','clarify'],
  ['Режимы: в магазине','«Что это?» и переводчик, фото',()=>{ ready(); S.mode='whatsthis'; S.screen='home' },'day','shop'],
  ['Режим «Гастрогид»','свой режим: фото меню → выбор',()=>{ ready(); S.mode='m-4c1e'; S.screen='home'; S.camSubj='menu' },'day','gastro'],
  ['Модель качается','баннер с прогрессом',()=>{ ready(); S.w.model='loading'; S.modelPct=43; S.screen='app'; startDl(true) },'start','model'],
  ['Мозг не подключён','нет ключа — только команды телефона',()=>{ ready(); S.w.key='none'; S.screen='app' },'start','nokey'],
  ['Нет сети','команды да, вопросы в очередь',()=>{ ready(); S.w.net='none' },'fail','nonet'],
  ['Экран заблокирован','календарь — с разблокировкой',()=>{ ready(); S.w.locked=true; S.screen='lock' },'fail','locked'],
  ['Микрофон запрещён','кнопка «Открыть настройки»',()=>{ ready(); S.w.mic='denied' },'fail','nomic'],
];
function preset(i,restored){ token++; clearInterval(dlT); const p=PRESETS[i]; curPreset=i; openGroup=p[3]; save({k:'p',slug:p[4]}); history.replaceState(null,'','#'+p[4]); renderGroups(); p[2](); if(restored && (S.autoSay||S.autoEv)){ S.autoSay=null; S.autoEv=null; render(); trace(`Восстановлен сценарий «${p[0]}» — сам он не проигрывается. Нажмите его в «Сценариях», чтобы проиграть.`,'p') } if(p[0].startsWith('Режим «Гастрогид»')) setTimeout(()=>{ openOverlay('боковой кнопкой — последний режим'); trace('«Гастрогид» — режим, добавленный вручную через «+» (идентификатор m-4c1e). Вкусы записаны в его промпте. Открывается сразу камерой: снимите меню — ответ придёт с названиями в оригинале, чтобы показать официанту.','p') },300);
  else if(p[0].startsWith('Режимы')) setTimeout(()=>{ openOverlay('боковой кнопкой — последний режим'); trace('Боковая кнопка открыла последний режим — «Что это?», он сразу открывается камерой. Спуск — снимок уходит сам (2 с на «Отмена»). Зажать микрофон справа — спросить голосом, снимок уйдёт с вопросом. Режимы — вкладки под полем ввода (как режимы в приложении камеры): нажать или свайпнуть.','p') },300); if(!['lock','app'].includes(S.screen)) S.screen='home'; $('#trace').innerHTML=''; trace(`<b>${p[0]}.</b> ${p[1]}.`); render();
  if (S.autoEv){ const k=S.autoEv; S.autoEv=null; openOverlay('из сценария'); setTimeout(()=>event(k),350) }
  if (S.autoSay){ const k=S.autoSay; S.autoSay=null; openOverlay('из сценария'); setTimeout(()=>quickSay(k),350) } }
const GROUPS=[['day','Каждый день','обычный день, режимы, кривая команда'],['start','Первый запуск','с нуля, модель, ключ'],['fail','Сбои','сеть, блокировка, микрофон, контекст'],['brain','Hermes · версия 2','серия, картинки, подтверждение, уточнение']];
let openGroup='day', curPreset=null;
function renderGroups(){ $('#groups').innerHTML=GROUPS.map(([g,t,d])=>`<details class="grp2" data-g="${g}" ${openGroup===g?'open':''}><summary>${t}<small>${d}</small></summary><div class="presets">${PRESETS.map((p,i)=>p[3]===g?`<button class="pre" data-preset="${i}" aria-current="${curPreset===i}"><b>${p[0]}</b><span>${p[1]}</span></button>`:'').join('')}</div></details>`).join('') }
document.addEventListener('toggle',e=>{ const d=e.target; if(!d.matches||!d.matches('.grp2')||!d.open) return; openGroup=d.dataset.g; document.querySelectorAll('.grp2').forEach(x=>{ if(x!==d) x.open=false }) },true);
/* вкладки панели */
let ptab='tour', seenLog=0;
function setPtab(t){ ptab=t; document.querySelectorAll('[data-ptab]').forEach(b=>b.matches('.ptabs button')&&b.setAttribute('aria-pressed',b.dataset.ptab===t)); document.querySelectorAll('.pt').forEach(x=>x.classList.toggle('on',x.dataset.pt.split(' ').includes(t))); if(t==='log'){ seenLog=$('#trace').children.length; $('#lognew').textContent='' } $('#lastlog').style.display=t==='log'?'none':'' }
/* знакомство */
const meIn=(id,f)=>(S.feeds[id]||[]).some(it=>it.t==='me'&&f(it));
const TOUR=[
  ['Зажмите боковую кнопку','справа на корпусе телефона — откроется лист, запись пойдёт сама',()=>!!S.ov],
  ['Скажите «Таймер на пять минут»','кнопка «Таймер» ниже — или зажмите микрофон в листе. Телефон сделает сам, без сервера',()=>meIn('main',it=>/таймер/i.test(it.x))],
  ['Спросите «Что у меня завтра?»','ответит DeepSeek, а календарь посмотрит через действие телефона',()=>meIn('main',it=>/завтра/i.test(it.x))],
  ['Внизу листа — вкладка «Гастрогид», снимите меню','большая кнопка справа — камера; спуск — и снимок уходит сам',()=>meIn('m-4c1e',it=>it.img)],
  ['Ассистент: скрепка → «Файл»','выберите договор и допишите вопрос, пока он грузится',()=>meIn('main',it=>it.img&&it.img.kind==='file')],
  ['«+» в конце вкладок — свой режим','надиктуйте промпт микрофоном под полем',()=>S.modes.filter(m=>!m.builtin).length>1],
];
let tourStep=0;
function startTour(){ token++; clearInterval(dlT); ready(); S.screen='home'; S.mode='main'; tourStep=0; curPreset=null; save({k:'tour'}); render(); renderGroups(); renderTour(); setPtab('tour'); trace('Знакомство: телефон уже настроен. Первый запуск — во вкладке «Сценарии».','p') }
function renderTour(){ const done=tourStep>=TOUR.length;
  $('#tour').innerHTML=`<h3>${done?'Готово — это основное':'Знакомство с Тасей'}</h3><p class="tsub">${done?'Остальное — во вкладке «Сценарии»: ответы мозга, первый запуск, сбои.':'Пара нажатий — и ответ. Шаги отмечаются сами, когда вы их делаете.'}</p>
  <ol>${TOUR.map((t,i)=>`<li class="${i<tourStep?'done':i===tourStep?'cur':''}"><span class="n">${i<tourStep?'✓':i+1}</span><span>${t[0]}${i===tourStep?`<small>${t[1]}</small>`:''}</span></li>`).join('')}</ol>
  <div class="tact">${done?'<button data-ptab="sc">К сценариям</button>':'<button data-tour="skip">Пропустить шаг</button>'}<button data-tour="restart">Сначала</button></div>` }
function tourCheck(){ if(curPreset!==null) return; let moved=false; while(tourStep<TOUR.length && TOUR[tourStep][2]()){ tourStep++; moved=true } if(moved) renderTour() }
/* запоминание */
function save(v){ try{ localStorage.setItem('tasya-proto',JSON.stringify(v)) }catch{} }
function restore(){ const h=decodeURIComponent(location.hash.slice(1)); const bySlug=PRESETS.findIndex(p=>p[4]===h); if(bySlug>=0) return {k:'p',slug:h}; try{ return JSON.parse(localStorage.getItem('tasya-proto')) }catch{ return null } }
$('#say').innerHTML=Object.entries(PHR).filter(([k])=>k!=='pr').map(([k,v])=>`<button data-say="${k}">«${v.t}»</button>`).join('');
$('#say').insertAdjacentHTML('beforeend','');
$('#events').innerHTML=[['approve','Владелец подтвердил код'],['push','Ответ пришёл, пока лист закрыт'],['ctx','Разговор упёрся в предел модели'],['cron','Hermes (v2): утренняя сводка'],['bg','Hermes (v2): фоновая задача'],['rate','Ошибка провайдера модели'],['update','Вышла новая версия']].map(x=>`<button data-ev="${x[0]}">${x[1]}</button>`).join('');
renderGroups();
{ const R=restore(); const i=R&&R.k==='p'?PRESETS.findIndex(p=>p[4]===R.slug):-1; if(i>=0){ preset(i,true); setPtab('sc') } else startTour() }

/* телефон: пропорции корпуса S24 Ultra (79 × 162 мм ≈ 1 : 2,05), масштаб — по высоте окна, не больше 90 % */
const W=400, H=820;
function fit(){
  const dev=$('#dev'), wrap=dev.parentElement, lab=wrap.querySelector('.side-l');
  const k=Math.max(0.55, Math.min(0.9, (innerHeight-64)/H));
  dev.style.transform=`scale(${k})`;
  wrap.style.width=(W*k+8)+'px'; wrap.style.height=(H*k+30)+'px';
  lab.style.top=(H*k+8)+'px';
}
addEventListener('resize',fit); fit();
