/* Действия пользователя: клики, ввод, диалоги (в Android — интенты ViewModel). */
/* ───────── клики ───────── */
document.addEventListener('click', async e=>{
  const t=e.target.closest('[data-tool],[data-jid],[data-menable],[data-maccept],[data-mprimary],[data-mbool],[data-micon],[data-radio],[data-dlgclose],[data-pickitem],[data-up],[data-ptab],[data-tour],[data-micperm],[data-primary],[data-idle],[data-pickmode],[data-toggle],[data-shortcut],[data-shot],[data-act],[data-open],[data-dlg],[data-role],[data-side],[data-chip],[data-cardbtn],[data-undo],[data-w],[data-fail],[data-preset],[data-say],[data-ev],[data-saytext]'); if(!t) return;
  if (t && t.dataset.ptab){ if(t.dataset.ptab==='tour' && curPreset!==null) return startTour(); return setPtab(t.dataset.ptab) }
  if (t && t.dataset.tour){ if(t.dataset.tour==='restart') return startTour(); tourStep++; return renderTour() }
  if (t.dataset.preset!==undefined) return preset(+t.dataset.preset);
  if (t.dataset.pickmode){ if(sw===false){ sw=null; return } setMode(t.dataset.pickmode,'Вкладка режима'); S.keepScroll=true; return render() }
  if (t.dataset.up){ S.upSpeed=t.dataset.up; return render() }
  if (t.dataset.idle!==undefined){ S.idle=+t.dataset.idle; return render() }
  /* редактирование режима: все поля — из модели Mode (model.js) */
  if (t.dataset.menable){ const m=modeById(t.dataset.menable); m.enabled=!m.enabled; if(!m.enabled && S.mode===m.id) setMode('main','Режим выключен — вернулись'); S.keepScroll=true; return render() }
  if (t.dataset.maccept){ const m=modeById(S.editMode); m.accepts[t.dataset.maccept]=!m.accepts[t.dataset.maccept]; if(!m.accepts.photo) m.primary='voice'; S.keepScroll=true; return render() }
  if (t.dataset.mprimary){ modeById(S.editMode).primary=t.dataset.mprimary; S.keepScroll=true; return render() }
  if (t.dataset.mbool){ const m=modeById(S.editMode); m[t.dataset.mbool]=!m[t.dataset.mbool]; S.keepScroll=true; return render() }
  if (t.dataset.micon){ modeById(S.editMode).icon=t.dataset.micon; S.keepScroll=true; return render() }
  if (t.dataset.tool){ const x=toolById(t.dataset.tool); S.editTool=x.id; S.dialog={title:x.title,radio:POLICY_OPTS.filter(o=>!(x.always&&o[0]==='auto')),cur:S.policy[x.id],key:'__tool'}; return render() }
  if (t.dataset.jid){ S.jopen = S.jopen===t.dataset.jid ? null : t.dataset.jid; S.keepScroll=true; return render() }
  if (t.dataset.radio){ const D=S.dialog; S.dialog=null; if(D.key==='__tool'){ S.policy[S.editTool]=t.dataset.radio; S.keepScroll=true; return render() } if(D.key==='__brain'){ const m=modeById(S.editMode); if(m.brain!==t.dataset.radio){ m.brain=t.dataset.radio; S.feeds[m.id]=[]; trace(`Мозг режима «${esc(m.title)}» — ${brainById(m.brain).title}. Разговор начат заново: историю между мозгами не переносим.`,'p') } return render() } S.settings[D.key]=t.dataset.radio; return render() }
  if (t.dataset.dlgclose!==undefined){ S.dialog=null; return render() }
  if (t.dataset.pickitem){ const v=t.dataset.pickitem; const img=S.picker==='gallery'?{kind:'photo',cls:v}:{kind:'file',name:v.split('|')[0],size:v.split('|')[1]}; S.screen=S.pickRet.screen; S.ov=S.pickRet.ov; S.page=S.pickRet.page; S.picker=null; uploadPhoto(img,'',img.kind==='file'?'файл':'фото'); trace(img.kind==='file'?'Файл из системного выбора — уходит Hermes как документ, грузится прямо в пузыре.':'Фото из галереи — так же, как со спуска камеры.','p'); return }
  if (t.dataset.shortcut){ S.shortcuts=false; setMode(t.dataset.shortcut,'Ярлык'); trace(`Ярлык на иконке (долгое нажатие) → сразу режим «${curMode().title}». В Android это статические ярлыки приложения; их же можно вынести на рабочий стол.`,'p'); return openOverlay('ярлык') }
  if (t.dataset.shot){ S.camSubj=t.dataset.shot; if(S.cam) S.cam.subj=t.dataset.shot; return render() }
  if (t.dataset.say) return quickSay(t.dataset.say);
  if (t.dataset.saytext) return deliver(t.dataset.saytext,'нажатие');
  if (t.dataset.ev) return event(t.dataset.ev);
  if (t.dataset.w){ let v=t.dataset.v; if(v==='true')v=true; if(v==='false')v=false; return setWorld(t.dataset.w,v) }
  if (t.dataset.fail!==undefined){ S.failNext=t.dataset.fail||null; return render() }
  if (t.dataset.open!==undefined){ if(t.dataset.open==='app'){ S.screen='app'; S.fromSheet=null; if(S.onboarded) S.page='chat'; roleCheck() } else if(t.dataset.open==='tg') S.screen='tg'; else return; return render() }
  if (t.dataset.role){ S.w.role=t.dataset.role; S.dismissed=null; trace(t.dataset.role==='ours'?'Тася — помощник. Окном это не запросить (requestable=false) — только экраном настроек.':'Выбран другой помощник.'); return render() }
  if (t.dataset.micperm){ S.w.mic=t.dataset.micperm; return render() }
  if (t.dataset.side){ S.w.side=t.dataset.side; S.dismissed=null; return render() }
  if (t.dataset.chip){ S.feed=S.feed.filter(f=>f.t!=='chips'); S.feed.push({t:'me',x:'На '+t.dataset.chip,via:'нажатие'}); addAct({t:'act',i:'⏰',x:'Будильник · '+t.dataset.chip,s:'на телефоне · через 21 ч',btn:'Открыть Часы',kind:'D',id:Date.now()},{tool:'alarm.set',who:'по правилам',phrase:'поставь будильник → '+t.dataset.chip,params:'time='+t.dataset.chip,confirm:'выбор варианта',lock:S.w.locked}); trace('Будильник — интентом «Часов» (разрешение SET_ALARM). Отмена только через «Часы».','p'); return render() }
  if (t.dataset.cardbtn) return cardBtn(t.dataset.cardbtn);
  if (t.dataset.undo){ const it=S.feed.find(f=>String(f.id)===t.dataset.undo); if(t.dataset.kind==='B'){ it.undone=true; it.s+=' · отменено'; trace('Знаем id события — отмена настоящая.','i') } else trace('Открываю «Часы» — их таймер сами отменить не можем.','e'); S.keepScroll=true; return render() }
  if (t.dataset.dlg!==undefined){ const o=S.dialog.opts[+t.dataset.dlg]; S.dialog=null; return dlgAct(o[1]) }
  const a=t.dataset.act;
  if (a && a.startsWith('brainpage:')){ S.editBrain=a.split(':')[1]; S.keyCheck=null; S.page='brain'; return render() }
  if (a && a.startsWith('editmode:')){ S.editMode=a.split(':')[1]; S.page='mode'; return render() }
  switch(a){
    case 'onbnext': if (ONB[S.onbStep]==='brain'&&S.w.key!=='ok') S.keyCheck=null; S.onbStep=Math.min(S.onbStep+1,ONB.length-1); return render();
    case 'onbdone': S.onboarded=true; S.page='chat'; trace('Главный экран приложения — разговор, тот же, что в листе. Настройки — за шестерёнкой; что не готово — одним баннером сверху.','i'); return render();
    case 'sysrole': S.ret={screen:S.screen,page:S.page}; S.ov=null; S.screen='sysrole'; return render();
    case 'sysside': S.ret={screen:S.screen,page:S.page}; S.ov=null; S.screen='sysside'; return render();
    case 'sysmic': S.ret={screen:S.screen,page:S.page,ov:S.ov}; S.ov=null; S.screen='sysmic'; trace('Android больше не покажет окно разрешения после двух отказов — ведём прямо в системный экран разрешений приложения (ACTION_APPLICATION_DETAILS_SETTINGS).','p'); return render();
    case 'sysback': { const R=S.ret; S.screen=R?.screen||'app'; S.page=R?.page||S.page; if(R?.ov){ S.ov=R.ov; S.ov.live = S.w.mic==='ok' ? '<span class="st">Микрофон разрешён — зажмите и говорите</span>' : S.ov.live } return render() }
    case 'dismiss': S.dismissed=issue()?.t; return render();
    case 'dl': S.ov=null; if(S.w.model==='loading'||S.w.model==='ready'){ S.screen='app'; if(S.onboarded) S.page='about'; return render() } return startDl();
    case 'askmic': if (S.w.mic==='denied'){ S.ret={screen:S.screen,page:S.page,ov:null}; S.screen='sysmic'; return render() } S.dialog={ic:'🎙',title:'Разрешить приложению «Тася» записывать аудио?',opts:[['При использовании приложения','mic_ok'],['Только в этот раз','mic_ok'],['Не разрешать','mic_no','sec2']]}; return render();
    case 'notifno': S.feed=S.feed.filter(f=>f.t!=='notifask'); S.keepScroll=true; return render();
    case 'asknotif': S.dialog={ic:'🔔',title:'Разрешить приложению «Тася» отправлять уведомления?',opts:[['Разрешить','notif_ok'],['Не разрешать','x','sec2']]}; return render();
    case 'connect': S.ov=null; S.screen='app'; if(S.w.server!=='paired') S.pair=null; if(S.onboarded) S.page='connect'; else S.onbStep=ONB.indexOf('server'); return render();
    case 'viatg': S.pair=null; S.screen='tg'; S.tg=[]; return render();
    case 'tgsend': S.tg.push({me:true,html:'/tasya_pair'}); render(); await wait(700); S.tg.push({html:`Ссылка для телефона — 10 минут, один раз:<br><a data-act="tglink">https://hermes.example.ru/tasya/pair#k7Q2m9xD…</a>${qrSvg()}`}); trace('Плагин выдал одноразовый токен и QR картинкой. Вызвать команду может только тот, кто уже допущен к Hermes.','h'); return render();
    case 'tglink': return runPair('link');
    case 'viaqr': S.pair=null; S.screen='cam'; render(); await wait(1500); if(S.screen==='cam') return runPair('qr'); return;
    case 'viaaddr': S.pair={via:'addr',stage:'input',checks:[]}; return render();
    case 'addrgo': { const v=($('#addr')?.value||'').trim().replace(/^https?:\/\//,'').replace(/\/.*$/,''); if(!/^[a-z0-9.-]+\.[a-z]{2,}$/i.test(v)){ S.pair.err='Нужен адрес вида hermes.example.ru'; S.pair.addr=v; return render() } return runPair('addr') }
    case 're-addr': case 're-link': case 're-qr': return runPair(a.slice(3));
    case 'pairreset': token++; S.pair=null; return render();
    case 'copycmd': try{ await navigator.clipboard.writeText('hermes pairing approve tasya K7Q2M9XD') }catch{} trace('Команда скопирована.'); return;
    case 'tochat': S.page='chat'; return render();
    case 'settings': S.ov=null; S.screen='app'; S.onboarded=true; S.page='settings'; return render();
    case 'home': S.fromSheet=null; S.screen='home'; return render();
    case 'about': S.page='about'; return render();
    case 'addmode': { S.ov=null; S.screen='app'; S.onboarded=true; const id='m-'+Math.random().toString(16).slice(2,6); S.modes.push(mode({id,title:'Новый режим',icon:'star',hint:'',prompt:''})); S.editMode=id; S.page='mode'; trace(`Новый режим получил идентификатор ${id} один раз, при создании; переименование его не меняет — иначе Hermes решит, что это новый разговор.`,'p'); render(); $('[data-mfield=title]')?.select(); return }
    case 'delmode': { const m=modeById(S.editMode); S.dialog={title:`Удалить режим «${esc(m.title)}»?`,text:brainOf(m).kind==='api'?'Разговор этого режима сотрётся — он хранится только на телефоне.':'Разговор пропадёт с телефона. В Hermes его сессия останется.',opts:[['Удалить','delmode_ok','danger'],['Отмена','x','sec2']]}; return render() }
    case 'brainsetup': S.ov=null; S.screen='app'; S.keyCheck=null; if(S.onboarded){ S.editBrain='deepseek'; S.page='brain' } else S.onbStep=ONB.indexOf('brain'); return render();
    case 'keypaste': { const el=$('#apikey'); if(el) el.value='sk-••••••••••••••••3f9a'; return }
    case 'keycheck': { const K=S.keyCheck={stage:'run',checks:[{id:'fmt',t:'Формат ключа',s:''},{id:'net',t:'api.deepseek.com',s:''},{id:'auth',t:'Ключ принят',s:''},{id:'model',t:'Модель отвечает',s:''},{id:'vision',t:'Понимает фото',s:''}]}; render();
      for (const c of K.checks){ c.s='run'; render(); await wait(350); if(S.keyCheck!==K) return;
        if (c.id==='net' && S.w.net==='none'){ c.s='bad'; K.stage='bad'; K.err='Нет интернета на телефоне.'; return render() }
        if (c.id==='auth' && S.w.key==='bad'){ c.s='bad'; K.stage='bad'; K.err='Ключ не принят (401). Проверьте, что скопирован целиком и не отозван.'; return render() }
        c.s='ok'; c.d={fmt:'sk-…, 35 символов',net:'отвечает',auth:'баланс есть',model:'deepseek-chat · 0,6 с',vision:'да — фото-режимы доступны'}[c.id] }
      K.stage='done'; S.w.key='ok'; S.dismissed=null; trace('Ключ проверен пробным запросом и сохранён в Keystore. Дальше приложение говорит с DeepSeek напрямую — без своего сервера.','i'); return render() }
    case 'addbrain': S.dialog={title:'Добавить мозг',text:'Любой OpenAI-совместимый API: адрес, имя модели, ключ. После проверки он появится в выборе мозга у режимов.',opts:[['Понятно','x']]}; return render();
    case 'brainpick': { const m=modeById(S.editMode); const ok=S.brains.filter(b=>!m.accepts.photo||b.caps.vision); S.dialog={title:'Мозг режима',radio:ok.map(b=>[b.id,b.title]),cur:m.brain,key:'__brain'}; return render() }
    case 'restart': { const m=modeById(S.editMode)||curMode(); const n=(S.feeds[m.id]||[]).length; if(!n) return; S.dialog={title:`Начать «${esc(m.title)}» заново?`,text:'Разговор этого режима сотрётся с телефона. Промпт и настройки останутся.',opts:[['Начать заново','restart_ok','danger'],['Отмена','x','sec2']]}; S.restartId=m.id; return render() }
    case 'tools': S.page='tools'; return render();
    case 'journal': S.page='journal'; S.jopen=null; return render();
    case 'lockscope': S.dialog={title:'Без разблокировки',radio:LOCK_SCOPE,cur:S.settings.lockScope,key:'lockScope'}; return render();
    case 'jundo': { const j=S.journal.find(x=>x.id===t.dataset.id); if(j){ j.undo=false; j.status='отменено'; trace('Отменено по-настоящему: событие удалено по event_id.','i') } S.keepScroll=true; return render() }
    case 'returnpick': S.dialog={title:'Возвращаться к Ассистенту',radio:RETURN_OPTS,cur:S.settings.returnToAssistant,key:'returnToAssistant'}; return render();
    case 'attach': S.attachMenu=!S.attachMenu; S.keepScroll=true; return render();
    case 'gallery': case 'file': S.attachMenu=false; S.pickRet={screen:S.screen,ov:S.ov,page:S.page}; S.picker=a; S.ov=null; S.screen='syspick'; trace(a==='file'?'Файл: системный выбор (ACTION_OPEN_DOCUMENT) из листа через startAssistantActivity — проверяется спайком; лист после выбора возвращается.':'Галерея: системный Photo Picker — разрешение на все фото не нужно.','p'); return render();
    case 'pickback': S.screen=S.pickRet.screen; S.ov=S.pickRet.ov; S.page=S.pickRet.page; S.picker=null; return render();
    case 'camera': S.attachMenu=false; return openCamera(false);
    case 'shoot': return shotGo('');
    case 'upcancel': { const b=Object.values(S.feeds).flat().find(x=>x.id===t.dataset.id); if(b){ b.up.st='cancel'; if(S.pendingUp===b) S.pendingUp=null; if(b.x){ S.draft=b.x } trace(b.x?'Отправка отменена. Фото убрано, вопрос вернулся в поле.':'Отправка отменена — ничего не ушло.','p') } S.keepScroll=true; return render() }
    case 'upretry': { const b=Object.values(S.feeds).flat().find(x=>x.id===t.dataset.id); if(b){ S.pendingUp=b; runUpload(b) } return }
    case 'camcancel': S.screen=S.camRet.screen; S.ov=S.camRet.ov; S.cam=null; if(S.rec){ recTok++; S.rec=null } return render();
    case 'speaksoon': trace('Озвучка — v1.1. Для переводчика она первая в очереди.','p'); return;
    case 'back': S.page='chat'; return render();
    case 'check': S.upd.state='checking'; render(); await wait(800); S.upd.state=S.w.net==='none'?'fail':S.upd.ver==='0.4.0'?'available':'latest'; return render();
    case 'upd': S.upd.state='dl'; S.upd.pct=0; while(S.upd.state==='dl'&&S.upd.pct<100){ await wait(120); S.upd.pct=Math.min(100,S.upd.pct+8); render() } if(S.upd.state!=='dl') return; S.upd.ver=S.upd.next; S.upd.state='none'; trace('Поставлено через PackageInstaller без окна (приложение обновляет само себя). Роль помощника на месте.','i'); showToast('Обновлено до 0.5.0','Что нового — в «О программе»'); return render();
    case 'updcancel': S.upd.state='available'; return render();
    case 'gear': if(S.w.locked){ S.dialog={ic:'🔒',title:'Разблокируйте, чтобы открыть настройки',opts:[['Разблокировать','unlock_set'],['Отмена','x','sec2']]}; return render() } S.ov=null; S.screen='app'; S.onboarded=true; S.page='settings'; trace('Шестерёнка → настройки приложения (startAssistantActivity), лист закрывается.'); return render();
    case 'expand': return expand(t.dataset.msg);
    case 'collapse': { const back=S.fromSheet; S.fromSheet=null; S.screen=back?.screen||'home'; openOverlay('снова — свёрнуто из приложения'); S.ov.full=true; render(); await wait(30); S.ov.full=false; S.keepScroll=false; render();
      trace('«Свернуть» (или системный жест «Назад»): экран приложения закрывается (finish), сервис ассистента снова показывает лист (showSession) поверх того приложения, где вы были. Разговор тот же — он один.','p'); return }
    case 'close': return closeOverlay();
    case 'dim': trace('Нажатие по затемнению лист не закрывает — чтобы не терять его случайно.'); return;
    case 'csend': { const v=(S.draft||'').trim(); if(!v) return; const via=S.draftVoice?'голос, правка':'клавиатура'; S.draft=''; S.draftVoice=false; return deliver(v,via) }
    case 'recsend': return finishRec('send');
    case 'reccancel': return finishRec('cancel');
    case 'togglesteps': { const it=S.feed.find(f=>String(f.id)===t.dataset.id); if(it){ it.open=!it.open; S.keepScroll=true; render() } return }
    case 'openfile': trace(`«${t.dataset.name}»: скачивание по нажатию по короткой ссылке, затем системный просмотрщик или «Поделиться».`); return;
    case 'unlock': S.w.locked=false; S.screen='home'; return render();
    case 'toast': { const T=S.toast; S.toast=null; if(T?.act==='push') openOverlay('из уведомления'); else render(); return }
  }
});
document.addEventListener('keydown',e=>{ if(e.key==='Enter'){ if(e.target.id==='cin'){ S.draft=e.target.value; $('[data-act=csend]')?.click() } if(e.target.id==='addr') $('[data-act=addrgo]').click() } });
document.addEventListener('change',e=>{ if(e.target.dataset&&e.target.dataset.mfield){ S.keepScroll=true; render() } });
document.addEventListener('input',e=>{ if(e.target.dataset&&e.target.dataset.mfield){ const m=modeById(S.editMode); m[e.target.dataset.mfield]=e.target.value; return }
  if(e.target.dataset&&e.target.dataset.prompt){ const m=modeById(e.target.dataset.prompt); m.prompt=e.target.value; grow(e.target); return } if(e.target.id==='cin'){ const had=!!S.draft; S.draft=e.target.value; if(had!==!!S.draft){ S.keepScroll=true; render(); const el=$('#cin'); el.focus(); el.setSelectionRange(el.value.length,el.value.length) } } });

function cardBtn(b){
  if (b==='alarm_ok'){ const idx=S.feed.findIndex(f=>f.t==='card'&&f.btns.some(x=>x[1]==='alarm_ok')); if(idx>=0) S.feed.splice(idx,1); addAct({t:'act',i:'⏰',x:'Будильник · 7:00',s:brainOf().title+' → телефон · через 9 ч',btn:'Открыть Часы',kind:'D',id:Date.now()},{tool:'alarm.set',who:brainOf().title,phrase:'поставь мне алярмер на семь',params:'time=07:00',confirm:'«Поставить» нажатием',lock:S.w.locked}); return render() }
  if (b==='unlock'){ S.w.locked=false; return render() }
  if (b==='brainsetup'){ S.ov=null; S.screen='app'; S.keyCheck=null; S.editBrain='deepseek'; S.page='brain'; S.onboarded=true; return render() }
  if (b==='restart'){ S.feed=[]; trace('Разговор режима начат заново.','p'); return render() }
  if (b==='connect'){ S.ov=null; S.screen='app'; S.pair=null; if(S.onboarded) S.page='connect'; else S.onbStep=ONB.indexOf('server'); return render() }
  const idx=S.feed.findIndex(f=>f.t==='card'&&f.btns.some(x=>x[1]===b));
  if (b==='x'){ if(idx>=0){ const cal=/календарь/.test(S.feed[idx].head); S.feed.splice(idx,1); if(cal){ S.feed.push({t:'act',i:'📅',x:'Банк · не перенесено',s:'отклонено',undone:true}); trace('Отклонено — Hermes получил отказ и не переспрашивает.') } } return render() }
  if (b==='unlockmove'){ S.dialog={ic:'🔒',title:'Разблокируйте, чтобы перенести встречу',text:'После разблокировки перенесу сам.',opts:[['Разблокировать','unlock_move'],['Отмена','x','sec2']]}; return render() }
  if (b==='apr_once'){ if(idx>=0) S.feed.splice(idx,1); S.feed.push({t:'sys',x:'Разрешено один раз'}); (async()=>{ const my=++token; S.busy=true; if(!await think(my,[{i:'⌨',t:'Команда на сервере',d:'<div class="term">$ rm -rf /var/log/app/*.gz\n(exit 0)</div>',ms:'0,3 с'}])) return; await stream(my,'<p>Удалил старые логи, освободилось 4,1 ГБ.</p>',{fast:true}); S.busy=false; render() })(); return render() }
  if (b==='apr_no'){ if(idx>=0) S.feed.splice(idx,1); S.feed.push({t:'sys',x:'Отклонено — команда не выполнена'}); return render() }
  if (b==='clar'||b==='clar_other'){ if(idx>=0) S.feed.splice(idx,1); if(b==='clar_other'){ S.feed.push({t:'sys',x:'Напишите свой ответ'}); render(); $('#cin')?.focus(); return } S.feed.push({t:'me',x:'4',via:'нажатие'}); (async()=>{ const my=++token; S.busy=true; await stream(my,'<p>Забронировал на субботу, 19:00, на четверых. Подтверждение придёт на почту.</p>',{fast:true}); S.busy=false; render() })(); return render() }
  if (b==='move'){ if(idx>=0) S.feed.splice(idx,1); addAct({t:'act',i:'📅',x:'Банк → пт, 19 окт, 10:00',s:brainOf().title+' → телефон',btn:'Отменить',kind:'B',id:Date.now()},{tool:'calendar.add',who:brainOf().title,phrase:'перенеси банк на пятницу',params:'event_id=812, start=2026-10-19T10:00',confirm:'«Перенести» нажатием',lock:false,undo:true}); S.feed.push({t:'ai',x:'<p>Перенёс на пятницу, 10:00.</p>',done:true}); trace('Событие вставлено в видимый Google-календарь; id известен — отмена настоящая.','i'); return render() }
}
async function dlgAct(k){
  if (k==='mic_ok'){ S.w.mic='ok'; if(S.ov) openOverlay(S.pendingSide?'side':'снова'); S.pendingSide=false; return render() }
  if (k==='mic_no'){ S.w.mic='denied'; if(S.ov) openOverlay('снова'); return render() }
  if (k==='restart_ok'){ S.feeds[S.restartId]=[]; trace('Разговор режима стёрт с телефона — следующая реплика уйдёт мозгу без истории.','p'); return render() }
  if (k==='brainsetup') { S.dialog=null; return }
  if (k==='delmode_ok'){ const id=S.editMode, m=modeById(id); S.modes=S.modes.filter(x=>x.id!==id); delete S.feeds[id]; delete S.drafts[id]; if(S.mode===id) S.mode='main'; S.page='settings'; trace(`Режим «${esc(m.title)}» удалён с телефона. Сессия ${id} в Hermes осталась — удалять её телефон не умеет и не должен.`,'p'); return render() }
  if (k==='notif_ok'){ S.w.notif='ok'; S.feeds && Object.keys(S.feeds).forEach(id=>S.feeds[id]=S.feeds[id].filter(f=>f.t!=='notifask')); trace('Уведомления разрешены.','i'); return render() }
  if (k==='dlnow') return startDl(true);
  if (k==='unlock_set'){ S.w.locked=false; S.ov=null; S.screen='app'; S.onboarded=true; S.page='settings'; return render() }
  if (k==='unlock_move'){ S.w.locked=false; S.screen='home'; render(); await wait(300); return cardBtn('move') }
  render();
}
function roleCheck(){ if(S.onboarded&&S.w.role!=='ours') trace(`При запуске: isRoleHeld = false — роль помощника у Gemini. Одним баннером в разговоре, не экраном с галочками.`,'e') }

