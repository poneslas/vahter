/* Действия пользователя: клики, ввод, диалоги (в Android — интенты ViewModel). */
/* ───────── клики ───────── */
// Меню ⋮ закрывается нажатием мимо него.
document.addEventListener('click', e=>{ if(S.chatMenu && !e.target.closest('.cmenu,[data-act=chatmenu]')){ S.chatMenu=false; S.keepScroll=true; render() } }, true);
document.addEventListener('click', async e=>{
  const t=e.target.closest('[data-perm],[data-permfix],[data-sysperm],[data-frame],[data-framedel],[data-ctool],[data-tool],[data-jid],[data-menable],[data-maccept],[data-mprimary],[data-mbool],[data-micon],[data-radio],[data-dlgclose],[data-pickitem],[data-up],[data-ptab],[data-micperm],[data-primary],[data-idle],[data-pickmode],[data-toggle],[data-shortcut],[data-shot],[data-act],[data-open],[data-dlg],[data-role],[data-side],[data-chip],[data-cardbtn],[data-undo],[data-w],[data-fail],[data-preset],[data-say],[data-ev],[data-saytext]'); if(!t) return;
  if (t && t.dataset.ptab) return setPtab(t.dataset.ptab)
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
  if (t.dataset.ctool) return camTool(t.dataset.ctool);
  if (t.dataset.frame!==undefined) return camOpenFrame(+t.dataset.frame);
  if (t.dataset.framedel!==undefined) return camDelFrame(+t.dataset.framedel);
  if (t.dataset.perm && t.dataset.v){ S.w.perms[t.dataset.perm]=t.dataset.v; return render() }
  if (t.dataset.permfix) return permFix(t.dataset.permfix);
  if (t.dataset.sysperm){ S.w.perms[S.permKind]=t.dataset.sysperm; S.keepScroll=true; return render() }
  if (t.dataset.tool && toolBlocked(toolById(t.dataset.tool))){ const x=toolById(t.dataset.tool), k=x.perm, st=S.w.perms[k];
    S.editTool=x.id; S.dialog={title:x.title,text:`Действие разрешено, но у приложения нет доступа: ${SYS_PERMS[k]}. ${x.example?`Поэтому ${x.example} не сработает.`:''}`,opts:[[st==='ask'&&k!=='dnd'?'Разрешить доступ':'Открыть настройки','permfix:'+k],['Настроить действие','tooledit'],['Закрыть','x','sec2']]}; return render() }
  if (t.dataset.tool){ const x=toolById(t.dataset.tool); S.editTool=x.id; S.dialog={title:x.title,radio:POLICY_OPTS.filter(o=>!(x.always&&o[0]==='auto')),cur:S.policy[x.id],key:'__tool'}; return render() }
  if (t.dataset.jid){ S.jopen = S.jopen===t.dataset.jid ? null : t.dataset.jid; S.keepScroll=true; return render() }
  if (t.dataset.radio){ const D=S.dialog; S.dialog=null; if(D.key==='__tool'){ S.policy[S.editTool]=t.dataset.radio; S.keepScroll=true; return render() } if(D.key==='__brain'){ const m=modeById(S.editMode); if(m.brain!==t.dataset.radio){ m.brain=t.dataset.radio; S.feeds[m.id]=[]; trace(`Мозг режима «${esc(m.title)}» — ${brainById(m.brain).title}. Разговор начат заново: историю между мозгами не переносим.`,'p') } return render() } S.settings[D.key]=t.dataset.radio; return render() }
  if (t.dataset.dlgclose!==undefined){ S.dialog=null; return render() }
  if (t.dataset.pickitem && S.picker==='gallery'){ const v=t.dataset.pickitem; S.pickSel=S.pickSel||[]; const i=S.pickSel.indexOf(v); if(i>=0) S.pickSel.splice(i,1); else if(S.pickSel.length<(brainOf().caps.maxImages||10)) S.pickSel.push(v); return render() }
  if (t.dataset.pickitem){ const v=t.dataset.pickitem; const img=S.picker==='gallery'?{kind:'photo',cls:v}:{kind:'file',name:v.split('|')[0],size:v.split('|')[1]}; S.screen=S.pickRet.screen; S.ov=S.pickRet.ov; S.page=S.pickRet.page; S.picker=null; uploadPhoto(img,'',img.kind==='file'?'файл':'фото'); trace(img.kind==='file'?'Файл из системного выбора — уходит Hermes как документ, грузится прямо в пузыре.':'Фото из галереи — так же, как со спуска камеры.','p'); return }
  if (t.dataset.shortcut){ S.shortcuts=false; setMode(t.dataset.shortcut,'Ярлык'); trace(`Ярлык на иконке (долгое нажатие) → сразу режим «${curMode().title}». В Android это статические ярлыки приложения; их же можно вынести на рабочий стол.`,'p'); return openOverlay('ярлык') }
  if (t.dataset.shot){ S.camSubj=t.dataset.shot; if(S.cam) S.cam.subj=t.dataset.shot; return render() }
  if (t.dataset.say) return quickSay(t.dataset.say);
  if (t.dataset.saytext) return deliver(t.dataset.saytext,'нажатие');
  if (t.dataset.ev) return event(t.dataset.ev);
  if (t.dataset.w){ let v=t.dataset.v; if(v==='true')v=true; if(v==='false')v=false; return setWorld(t.dataset.w,v) }
  if (t.dataset.fail!==undefined){ S.failNext=t.dataset.fail||null; return render() }
  if (t.dataset.open!==undefined){ if(t.dataset.open==='app'){ S.screen='app'; S.fromSheet=null; if(S.onboarded) S.page='chat'; roleCheck() } else if(t.dataset.open==='tg') S.screen='tg'; else return; return render() }
  if (t.dataset.role){ S.w.role=t.dataset.role; S.dismissed=null; trace(t.dataset.role==='ours'?'Вахтёр — помощник. Окном это не запросить (requestable=false) — только экраном настроек.':'Выбран другой помощник.'); return render() }
  if (t.dataset.micperm){ S.w.mic=t.dataset.micperm; return render() }
  if (t.dataset.side){ S.w.side=t.dataset.side; S.dismissed=null; return render() }
  if (t.dataset.chip){ S.feed=S.feed.filter(f=>f.t!=='chips'); S.feed.push({t:'me',x:'На '+t.dataset.chip,via:'нажатие'}); addAct({t:'act',i:'⏰',x:'Будильник поставлен · '+t.dataset.chip,s:'на телефоне · прозвенит через 21 ч',btn:'Открыть будильники',kind:'D',id:Date.now()},{tool:'alarm.set',who:'по правилам',phrase:'поставь будильник → '+t.dataset.chip,params:'time='+t.dataset.chip,confirm:'выбор варианта',lock:S.w.locked}); trace('Будильник — интентом «Часов» (разрешение SET_ALARM). Отмена только через «Часы».','p'); return render() }
  if (t.dataset.cardbtn) return cardBtn(t.dataset.cardbtn);
  if (t.dataset.undo){ const it=S.feed.find(f=>String(f.id)===t.dataset.undo); if(t.dataset.kind==='B'){ it.undone=true; it.s+=' · отменено'; trace('Знаем id события — отмена настоящая.','i') } else trace('Открываю «Часы» — их таймер сами отменить не можем.','e'); S.keepScroll=true; return render() }
  if (t.dataset.dlg!==undefined){ const o=S.dialog.opts[+t.dataset.dlg]; S.dialog=null; return dlgAct(o[1]) }
  const a=t.dataset.act;
  if (a && a.startsWith('brainpage:')){ S.editBrain=a.split(':')[1]; S.keyCheck=null; S.page='brain'; return render() }
  // «Назад» со страницы режима — туда, откуда пришли: лист, разговор или список режимов в настройках.
  if (a && a.startsWith('editmode:')){ S.editMode=a.split(':')[1]; S.modeBack=S.ov?'sheet':S.page==='chat'||!S.page?'chat':'settings';
    if(S.ov){ S.fromSheet={screen:S.screen==='app'?'home':S.screen}; S.ov=null; S.screen='app'; S.onboarded=true; trace('Полоска промпта → страница режима в приложении, лист закрывается. «Назад» вернёт лист.') } S.page='mode'; return render() }
  switch(a){
    case 'modeback': { const b=S.modeBack; S.modeBack=null; if(b==='sheet'){ const r=S.fromSheet; S.fromSheet=null; S.screen=r?.screen||'home'; S.page='chat'; openOverlay('снова — назад со страницы режима'); return render() } S.page=b==='chat'?'chat':'settings'; return render() }
    case 'promptreset': { const m=modeById(S.editMode), d=BUILTIN_MODES.find(b=>b.id===m.id); m.prompt=d.prompt; trace('Промпт встроенного режима — снова из текстов приложения: обновится с новой версией и языком.','i'); return render() }
    case 'listentoggle': S.settings.listenOnCall = S.settings.listenOnCall===false; return render();
    case 'chatmenu': S.chatMenu=!S.chatMenu; S.keepScroll=true; return render();
    case 'cm:edit': S.chatMenu=false; S.editMode=S.mode; S.modeBack='chat'; S.page='mode'; return render();
    case 'cm:journal': S.chatMenu=false; S.page='journal'; return render();
    case 'cm:restart': S.chatMenu=false; S.keepScroll=true;   // дальше — как раньше: карточка «Стереть разговор?»
    case 'restartask': S.feed.push({t:'card',who:'p',head:'Начать заново',title:`Стереть разговор «${esc(curMode().title)}»?`,text:'Промпт и настройки режима останутся.',btns:[['Стереть','restart_yes','pri'],['Отмена','restart_no']]}); return render();
    case 'camfull': S.cam.full=!S.cam.full; return render();
    case 'noop': return;
    case 'addtile': S.dialog={ic:'▦',title:'Добавить плитку «Вахтёр» в быстрые настройки?',opts:[['Добавить','tile_ok'],['Отмена','x','sec2']]}; trace('Плитку предлагает добавить сама система — StatusBarManager.requestAddTileService (Android 13+).','p'); return render();
    case 'brainfromsheet': S.brainRet={screen:S.screen}; S.ov=null; S.screen='app'; S.keyCheck=null; S.editBrain='deepseek'; S.page='brain'; S.onboarded=true; trace('Из листа — на экран мозга в приложении; «Назад» вернёт лист.','p'); return render();
    case 'brainback': { const R=S.brainRet; S.brainRet=null; S.page='chat'; S.screen=R?.screen||'home'; openOverlay('снова — назад с экрана мозга'); return render() }
    case 'onbnext': return onbNext();
    case 'onbdone': return onbDone();
    case 'btnsetup': return btnSetup();
    case 'btnlater': return btnLater();
    case 'sysrole': S.ret={screen:S.screen,page:S.page}; S.ov=null; S.screen='sysrole'; return render();
    case 'sysside': S.ret={screen:S.screen,page:S.page}; S.ov=null; S.screen='sysside'; return render();
    case 'sysmic': S.ret={screen:S.screen,page:S.page,ov:S.ov}; S.ov=null; S.screen='sysmic'; trace('Android больше не покажет окно разрешения после двух отказов — ведём прямо в системный экран разрешений приложения (ACTION_APPLICATION_DETAILS_SETTINGS).','p'); return render();
    case 'sysback': { const R=S.ret; S.screen=R?.screen||'app'; S.page=R?.page||S.page; if(R?.ov){ S.ov=R.ov; S.ov.live = S.w.mic==='ok' ? '<span class="st">Микрофон разрешён — зажмите и говорите</span>' : S.ov.live } return render() }
    case 'dismiss': S.dismissed=issue()?.t; return render();
    case 'sheetdl': startDl(); trace('Голос качается из строки в листе; та же строка показывает проценты.','p'); return render();
    case 'dl': S.ov=null; if(S.w.model==='loading'||S.w.model==='ready'){ S.screen='app'; if(S.onboarded) S.page='about'; return render() } return startDl();
    case 'askmic': return askMic();
    case 'notifno': S.feed=S.feed.filter(f=>f.t!=='notifask'); S.keepScroll=true; return render();
    case 'permmic': return permInApp('mic');
    case 'asknotif': if (S.ov) return permInApp('notif'); S.dialog={ic:'🔔',title:'Разрешить приложению «Вахтёр» отправлять уведомления?',opts:[['Разрешить','notif_ok'],['Не разрешать','x','sec2']]}; return render();
    case 'connect': S.ov=null; S.screen='app'; if(S.w.server!=='paired') S.pair=null; if(S.onboarded) S.page='hermes'; else S.onbStep=ONB.indexOf('brain'); return render();
    case 'viatg': S.pair=null; S.screen='tg'; S.tg=[]; return render();
    case 'tgsend': S.tg.push({me:true,html:'/vahter_pair'}); render(); await wait(700); S.tg.push({html:`Ссылка для телефона — 10 минут, один раз:<br><a data-act="tglink">https://hermes.example.ru/vahter/pair#k7Q2m9xD…</a>${qrSvg()}`}); trace('Плагин выдал одноразовый токен и QR картинкой. Вызвать команду может только тот, кто уже допущен к Hermes.','h'); return render();
    case 'tglink': return runPair('link');
    case 'viaqr': S.pair=null; S.screen='cam'; render(); await wait(1500); if(S.screen==='cam') return runPair('qr'); return;
    case 'viaaddr': S.pair={via:'addr',stage:'input',checks:[]}; return render();
    case 'addrgo': { const v=($('#addr')?.value||'').trim().replace(/^https?:\/\//,'').replace(/\/.*$/,''); if(!/^[a-z0-9.-]+\.[a-z]{2,}$/i.test(v)){ S.pair.err='Нужен адрес вида hermes.example.ru'; S.pair.addr=v; return render() } return runPair('addr') }
    case 're-addr': case 're-link': case 're-qr': return runPair(a.slice(3));
    case 'pairreset': token++; S.pair=null; return render();
    case 'copycmd': try{ await navigator.clipboard.writeText('hermes pairing approve vahter K7Q2M9XD') }catch{} trace('Команда скопирована.'); return;
    case 'tochat': S.page='chat'; return render();
    case 'settings': S.ov=null; S.screen='app'; S.onboarded=true; S.page='settings'; return render();
    case 'home': S.fromSheet=null; S.screen='home'; return render();
    case 'about': S.page='about'; return render();
    case 'addmode': { S.ov=null; S.screen='app'; S.onboarded=true; const id='m-'+Math.random().toString(16).slice(2,6); S.modes.push(mode({id,title:'Новый режим',icon:'star',hint:'',prompt:''})); S.editMode=id; S.page='mode'; trace(`Новый режим получил идентификатор ${id} один раз, при создании; переименование его не меняет — иначе Hermes решит, что это новый разговор.`,'p'); render(); $('[data-mfield=title]')?.select(); return }
    case 'delmode': { const m=modeById(S.editMode); S.dialog={title:`Удалить режим «${esc(m.title)}»?`,text:brainOf(m).kind==='api'?'Разговор этого режима сотрётся — он хранится только на телефоне.':'Разговор пропадёт с телефона. В Hermes его сессия останется.',opts:[['Удалить','delmode_ok','danger'],['Отмена','x','sec2']]}; return render() }
    case 'brainsetup': S.ov=null; S.screen='app'; S.keyCheck=null; if(S.onboarded){ S.editBrain='deepseek'; S.page='brain' } else S.onbStep=ONB.indexOf('brain'); return render();
    case 'keypaste': { const el=$('#apikey'); if(el) el.value='sk-••••••••••••••••3f9a'; return }
    case 'keycheck': { const K=S.keyCheck={stage:'run',checks:[{id:'fmt',t:'Формат ключа',s:''},{id:'net',t:'api.deepseek.com',s:''},{id:'auth',t:'Ключ принят',s:''},{id:'model',t:'Модель отвечает',s:''},{id:'vision',t:'Понимает фото',s:''}]}; render();
      for (const c of K.checks){ c.s='run'; await wait(260); if(S.keyCheck!==K) return;
        if (c.id==='net' && S.w.net==='none'){ c.s='bad'; K.stage='bad'; K.err='Нет связи с api.deepseek.com — проверьте интернет.'; return render() }
        if (c.id==='auth' && S.w.key==='bad'){ c.s='bad'; K.stage='bad'; K.err='Ключ не принят — скопирован не целиком или отозван.'; return render() }
        c.s='ok'; c.d={fmt:'sk-…, 35 символов',net:'отвечает',auth:'баланс есть',model:'deepseek-chat · 0,6 с',vision:'да — фото-режимы доступны'}[c.id] }
      K.ms='0,4 с'; K.stage='done'; S.w.key='ok'; S.dismissed=null; trace('Ключ проверен пробным запросом и сохранён в Keystore. Дальше приложение говорит с DeepSeek напрямую — без своего сервера.','i'); return render() }
    case 'addbrain': S.dialog={title:'Добавить мозг',text:'Любой OpenAI-совместимый API: адрес, имя модели, ключ. После проверки он появится в выборе мозга у режимов.',opts:[['Понятно','x']]}; return render();
    case 'brainpick': { const m=modeById(S.editMode); const ok=S.brains.filter(b=>!m.accepts.photo||b.caps.vision); S.dialog={title:'Мозг режима',radio:ok.map(b=>[b.id,b.title]),cur:m.brain,key:'__brain'}; return render() }
    case 'restart': { const m=modeById(S.editMode)||curMode(); const n=(S.feeds[m.id]||[]).length; if(!n) return; S.dialog={title:`Начать «${esc(m.title)}» заново?`,text:'Разговор этого режима сотрётся с телефона. Промпт и настройки останутся.',opts:[['Начать заново','restart_ok','danger'],['Отмена','x','sec2']]}; S.restartId=m.id; return render() }
    case 'actions': S.page='actions'; return render();
    case 'syspermback': { const R=S.permRet2; S.permRet2=null; S.screen=R?.screen||'app'; S.page=R?.page||'actions'; if(R?.sheet){ S.screen=R.sheet; openOverlay('снова') } return render() }
    case 'journal': S.page='journal'; S.jopen=null; return render();
    case 'lockscope': S.dialog={title:'На заблокированном экране',radio:LOCK_SCOPE,cur:S.settings.lockScope,key:'lockScope'}; return render();
    case 'jundo': { const j=S.journal.find(x=>x.id===t.dataset.id); if(j){ j.undo=false; j.status='отменено'; trace('Отменено по-настоящему: событие удалено по event_id.','i') } S.keepScroll=true; return render() }
    case 'sideyes': S.sideProbe='yes'; trace('Пользователь подтвердил: кнопка открывает Вахтёра. Запомнили — баннер больше не нужен.','p'); return render();
    case 'sideno': S.sideProbe='no'; return render();
    case 'sidereport': S.dialog={title:'Сообщить разработчику',text:'Через «Поделиться» уйдёт: модель телефона, версия Android и оболочки, что не сработало, значения найденных ключей. Своего сервера не нужно.',opts:[['Поделиться…','x'],['Отмена','x','sec2']]}; return render();
    case 'returnpick': S.dialog={title:'Запоминать режим',radio:RETURN_OPTS.map(o=>o.slice(0,2)),cur:S.settings.returnToAssistant,key:'returnToAssistant'}; return render();
    case 'attach': S.attachMenu=!S.attachMenu; S.keepScroll=true; return render();
    case 'gallery': case 'file': S.attachMenu=false; S.pickRet={screen:S.screen,ov:S.ov,page:S.page}; S.picker=a; S.ov=null; S.screen='syspick'; trace('Лист на время выбора прячется (иначе на S24 окно выбора откроется под ним), после выбора возвращается с вложением — проверено на S24.','p'); return render();
    case 'pickdone': { const sel=S.pickSel||[]; S.pickSel=[]; S.screen=S.pickRet.screen; S.ov=S.pickRet.ov; S.page=S.pickRet.page; S.picker=null; const items=sel.map(cls=>({cls,marks:[],crop:null})); uploadPhoto(items.length>1?{kind:'album',items}:{kind:'photo',...items[0]},'',items.length>1?`альбом · ${items.length}`:'фото'); trace(items.length>1?`Несколько фото из галереи — одна реплика, ${items.length} картинки.`:'Фото из галереи — как со спуска камеры.','p'); return }
    case 'pickback': S.pickSel=[]; S.screen=S.pickRet.screen; S.ov=S.pickRet.ov; S.page=S.pickRet.page; S.picker=null; return render();
    case 'camera': S.attachMenu=false; return openCamera(false);
    case 'shoot': case 'camsend': return shotGo('');
    case 'camadd': return camAdd();
    case 'camretake': { const back=S.cam.editIdx!=null; camLive(); trace(back?'Правки кадра из ленты отменены — снова живой видоискатель.':'Переснять — пометки сброшены, снова живой видоискатель.'); return render() }
    case 'camundo': { const c=S.cam; if(c.marks.length) c.marks.pop(); else c.crop=null; return render() }
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
    case 'collapse': { const back=S.fromSheet; S.fromSheet=null; S.screen=back?.screen||'home'; openOverlay('снова — свёрнуто из приложения'); S.ov.pop=false; render(); const sh=$('#scr .sheet'); S.ov.shrinkTo=sh?sh.offsetHeight:400; S.ov.shrink=true; S.ov.pop=false; render(); await wait(400); if(S.ov){ S.ov.shrink=false; S.keepScroll=true; render() }
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
  if (b==='restart_yes'){ S.feed=[]; trace('Разговор режима стёрт — меню ⋮, долгое нажатие на название в листе или «начни заново» голосом.','p'); return render() }
  if (b==='restart_no'){ S.feed=S.feed.filter(f=>!(f.t==='card'&&f.btns.some(x=>x[1]==='restart_yes'))); return render() }
  if (b.startsWith('permfix:')) return permFix(b.slice(8));
  if (b==='call_ok'){ const idx=S.feed.findIndex(f=>f.t==='card'&&f.btns.some(x=>x[1]==='call_ok')); if(idx>=0) S.feed.splice(idx,1); addAct({t:'act',i:'📞',x:'Звонилка · Мама',s:'номер набран — звоните вы',btn:'',kind:'E',id:Date.now()},{tool:'call.dial',who:brainOf().title,phrase:'позвони маме',params:'contact=«Мама»',confirm:'«Позвонить» нажатием',lock:S.w.locked}); trace('Звонилка открывается поверх, лист перед этим прячется (иначе на S24 она окажется под листом).','p'); return render() }
  if (b==='unlock_any'){ return unlockFromSheet(()=>{ const i=S.feed.findIndex(f=>f.t==='card'&&f.btns.some(x=>x[1]==='unlock_any')); if(i>=0) S.feed.splice(i,1); const me=[...S.feed].reverse().find(f=>f.t==='me'); if(me){ S.feed.splice(S.feed.lastIndexOf(me),1); return deliver(me.x,me.via) } render() }) }
  if (b==='alarm_ok'){ const idx=S.feed.findIndex(f=>f.t==='card'&&f.btns.some(x=>x[1]==='alarm_ok')); if(idx>=0) S.feed.splice(idx,1); addAct({t:'act',i:'⏰',x:'Будильник поставлен · 7:00',s:brainOf().title+' → телефон · прозвенит через 9 ч',btn:'Открыть будильники',kind:'D',id:Date.now()},{tool:'alarm.set',who:brainOf().title,phrase:'поставь мне алярмер на семь',params:'time=07:00',confirm:'«Поставить» нажатием',lock:S.w.locked}); return render() }
  if (b==='unlock'){ return unlockFromSheet(()=>{ const i=S.feed.findIndex(f=>f.t==='card'&&f.btns.some(x=>x[1]==='unlock')); if(i>=0) S.feed.splice(i,1); return cardBtn('alarm_ok') }) }
  if (b==='brainsetup'){ S.ov=null; S.screen='app'; S.keyCheck=null; S.editBrain='deepseek'; S.page='brain'; S.onboarded=true; return render() }
  if (b==='restart'){ S.feed=[]; trace('Разговор режима начат заново.','p'); return render() }
  if (b==='connect'){ S.ov=null; S.screen='app'; S.pair=null; if(S.onboarded) S.page='hermes'; else S.onbStep=ONB.indexOf('brain'); return render() }
  const idx=S.feed.findIndex(f=>f.t==='card'&&f.btns.some(x=>x[1]===b));
  if (b==='x'){ if(idx>=0){ const cal=/календарь/.test(S.feed[idx].head); S.feed.splice(idx,1); if(cal){ S.feed.push({t:'act',i:'📅',x:'Банк · не перенесено',s:'отклонено',undone:true}); trace('Отклонено — Hermes получил отказ и не переспрашивает.') } } return render() }
  if (b==='unlockmove'){ S.dialog={ic:'🔒',title:'Разблокируйте, чтобы перенести встречу',text:'После разблокировки перенесу сам.',opts:[['Разблокировать','unlock_move'],['Отмена','x','sec2']]}; return render() }
  if (b==='apr_once'){ if(idx>=0) S.feed.splice(idx,1); S.feed.push({t:'sys',x:'Разрешено один раз'}); (async()=>{ const my=++token; S.busy=true; if(!await think(my,[{i:'⌨',t:'Команда на сервере',d:'<div class="term">$ rm -rf /var/log/app/*.gz\n(exit 0)</div>',ms:'0,3 с'}])) return; await stream(my,'<p>Удалил старые логи, освободилось 4,1 ГБ.</p>',{fast:true}); S.busy=false; render() })(); return render() }
  if (b==='apr_no'){ if(idx>=0) S.feed.splice(idx,1); S.feed.push({t:'sys',x:'Отклонено — команда не выполнена'}); return render() }
  if (b==='clar'||b==='clar_other'){ if(idx>=0) S.feed.splice(idx,1); if(b==='clar_other'){ S.feed.push({t:'sys',x:'Напишите свой ответ'}); render(); $('#cin')?.focus(); return } S.feed.push({t:'me',x:'4',via:'нажатие'}); (async()=>{ const my=++token; S.busy=true; await stream(my,'<p>Забронировал на субботу, 19:00, на четверых. Подтверждение придёт на почту.</p>',{fast:true}); S.busy=false; render() })(); return render() }
  if (b==='move'){ if(idx>=0) S.feed.splice(idx,1); addAct({t:'act',i:'📅',x:'Банк → пт, 19 окт, 10:00',s:brainOf().title+' → телефон',btn:'Отменить',kind:'B',id:Date.now()},{tool:'calendar.add',who:brainOf().title,phrase:'перенеси банк на пятницу',params:'event_id=812, start=2026-10-19T10:00',confirm:'«Перенести» нажатием',lock:false,undo:true}); S.feed.push({t:'ai',x:'<p>Перенёс на пятницу, 10:00.</p>',done:true}); trace('Событие вставлено в видимый Google-календарь; id известен — отмена настоящая.','i'); return render() }
}
/** Починить системный доступ: ещё не спрашивали — системное окно; отказано — системный экран настроек. Из листа — через приложение (на S24 окна из листа уходят под лист). */
function permFix(k){ const st=S.w.perms[k], fromSheet=!!S.ov;
  if (fromSheet){ S.ov=null; S.screen='app'; S.onboarded=true; S.page='chat' }
  if (st==='ask' && k!=='dnd'){ S.permKind=k; S.permSheet=fromSheet; S.dialog={title:`Разрешить приложению «Вахтёр» доступ: ${SYS_PERMS[k]}?`,opts:[[k==='location'?'При использовании приложения':'Разрешить','sysperm_ok'],['Не разрешать','sysperm_no','sec2']]}; return render() }
  S.permKind=k; S.permRet2={screen:S.screen,page:S.page,sheet:fromSheet?'home':null}; S.screen='sysperm'; trace(k==='dnd'?'Доступ к «Не беспокоить» — особый: только системный экран, окна разрешения нет.':'Отказано раньше — Android больше не покажет окно, ведём в системные настройки приложения.','p'); return render() }
/** Разблокировка из листа (проверено на S24): лист прячется, своя активность поверх блокировки просит отпечаток, после — лист возвращается и выполняет действие. */
async function unlockFromSheet(then){ const keep=S.ov; S.ov=null; S.unlocking=true; render(); trace('Лист спрятан → своя активность (setShowWhenLocked) → запрос разблокировки, когда окно получило фокус. Из onCreate система отменяет его через ~180 мс.','p');
  await wait(1100); S.unlocking=false; S.w.locked=false; S.screen='home'; S.ov=keep||{pop:false}; render(); trace('Отпечаток принят — лист вернулся и выполняет отложенное действие.','i'); return then() }
/** Микрофон, как в Android 11+: первый раз — системное окно; после одного отказа — наше объяснение и снова системное;
 *  после второго отказа система больше не спрашивает — наше окно ведёт в настройки разрешений. */
function askMic(){
  if (S.w.mic==='denied'){ S.dialog={ours:true,ic:'🎙',title:'Доступ к микрофону закрыт',text:'Android больше не спрашивает сам. Откройте настройки: Разрешения → Микрофон → «При использовании приложения».',opts:[['Открыть настройки','sysmicgo'],['Не сейчас','x','sec2']]}; return render() }
  if (S.micNo){ S.dialog={ours:true,ic:'🎙',title:'Голосу нужен микрофон',text:'Запись идёт, только пока вы держите кнопку, и не уходит с телефона. Без микрофона остаётся клавиатура.',opts:[['Продолжить','micsys'],['Не сейчас','x','sec2']]}; return render() }
  return micSys() }
function micSys(){ S.dialog={ic:'🎙',title:'Разрешить приложению «Вахтёр» записывать аудио?',opts:[['При использовании приложения','mic_ok'],['Только в этот раз','mic_ok'],['Не разрешать','mic_no','sec2']]}; return render() }
function onbNext(){
  if (ONB[S.onbStep]==='brain'&&S.w.key!=='ok') S.keyCheck=null;
  if (S.onbStep>=ONB.length-1) return onbDone();
  S.onbStep++; if (ONB[S.onbStep]==='try' && (S.w.model==='none'||S.w.model==='broken')) S.onbStep++;   // голос не качали — пробовать нечем
  return render() }
function onbDone(){ S.onboarded=true; S.page='chat';
  // Финал — в лист: он интереснее полноэкранного приложения, и так Вахтёр будет выглядеть каждый день. Без роли помощника
  // настоящего листа нет (showSession может только активный помощник) — тогда разговор в приложении и баннер.
  if (S.w.role!=='ours'){ trace('Помощник не выбран — показать лист нельзя: разговор в приложении, сверху баннер.','i'); return render() }
  S.screen='home'; S.mode='main'; S.firstSheet=true; S.sideCue=true; S.firstClose=true; openOverlay('финал мастера');
  trace('Мастер закончился листом: приложение уходит, VoiceInteractionService.showSession() — тот же лист, что по боковой кнопке. Сверху один раз — откуда он берётся.','p');
  setTimeout(()=>{ if(S.sideCue){ S.sideCue=false; render() } },6000) }
/** Шаг «Боковая кнопка»: «Настроить» открывает по очереди то, что ещё не настроено; «Позже» — один раз наше предупреждение. */
const btnDone=()=>S.w.role==='ours'&&(S.w.profile==='unknown'||S.sideOk);
function btnSetup(){ S.ret={screen:S.screen,page:S.page}; S.ov=null; S.screen=S.w.role!=='ours'?'sysrole':'sysside'; trace(S.screen==='sysrole'?'«Настроить» → экран «Цифровой помощник»: выберите «Вахтёр» и вернитесь.':'«Настроить» → экран боковой кнопки Samsung: «Нажатие и удержание» → «Цифровой помощник».','p'); return render() }
function btnLater(){
  if (btnDone()||S.btnWarned) return onbNext();
  S.btnWarned=true; S.dialog={ours:true,ic:'⏻',title:'Кнопка пока откроет '+(S.w.side==='bixby'?'Bixby':S.w.role==='ours'?'не Вахтёра':'Gemini'),text:'Без этого Вахтёра можно будет открыть только из приложения. Настроить можно и потом: шестерёнка → «Вызов».',opts:[['Настроить','btnsetup'],['Всё равно позже','btnskip','sec2']]}; return render() }
async function dlgAct(k){
  if (k==='btnsetup') return btnSetup();
  if (k==='btnskip') return onbNext();
  if (k==='tile_ok'){ S.tileAdded=true; trace('Плитка добавлена: шторка → «Вахтёр» открывает лист.','p'); return render() }
  if (k==='micsys') return micSys();
  if (k==='sysmicgo'){ S.ret={screen:S.screen,page:S.page,ov:null}; S.screen='sysmic'; return render() }
  if (k&&k.startsWith('permfix:')) return permFix(k.slice(8));
  if (k==='tooledit'){ const x=toolById(S.editTool); S.dialog={title:x.title,radio:POLICY_OPTS.filter(o=>!(x.always&&o[0]==='auto')),cur:S.policy[x.id],key:'__tool'}; return render() }
  if (k==='sysperm_ok'||k==='sysperm_no'){ S.w.perms[S.permKind]=k==='sysperm_ok'?'ok':'denied'; if(S.permKind==='camera'&&k==='sysperm_ok') trace('Камера разрешена в приложении — лист вернулся, видоискатель открылся.','i'); if(S.permSheet){ S.permSheet=false; S.screen='home'; openOverlay('снова') } return render() }
  if (k==='mic_ok'){ S.w.mic='ok'; if(permBack()){ S.pendingSide=false; return } if(S.ov) openOverlay(S.pendingSide?'side':'снова'); S.pendingSide=false; return render() }
  if (k==='mic_no'){ S.micNo=(S.micNo||0)+1; S.w.mic=S.micNo>=2?'denied':'ask'; trace(S.micNo>=2?'Второй отказ: Android больше не покажет окно — дальше только наше объяснение и переход в настройки.':'Первый отказ: спросить можно ещё раз, но сначала объясняем зачем (shouldShowRequestPermissionRationale).','p'); if(permBack()) return; if(S.ov) openOverlay('снова'); return render() }
  if (k==='notif_no'){ if(permBack()) return; return render() }
  if (k==='restart_ok'){ S.feeds[S.restartId]=[]; trace('Разговор режима стёрт с телефона — следующая реплика уйдёт мозгу без истории.','p'); return render() }
  if (k==='brainsetup') { S.dialog=null; return }
  if (k==='delmode_ok'){ const id=S.editMode, m=modeById(id); S.modes=S.modes.filter(x=>x.id!==id); delete S.feeds[id]; delete S.drafts[id]; if(S.mode===id) S.mode='main'; S.page='settings'; trace(`Режим «${esc(m.title)}» удалён с телефона. Сессия ${id} в Hermes осталась — удалять её телефон не умеет и не должен.`,'p'); return render() }
  if (k==='notif_ok'){ S.w.notif='ok'; S.feeds && Object.keys(S.feeds).forEach(id=>S.feeds[id]=S.feeds[id].filter(f=>f.t!=='notifask')); trace('Уведомления разрешены.','i'); if(permBack()) return; return render() }
  if (k==='dlnow') return startDl(true);
  if (k==='unlock_set'){ S.w.locked=false; S.ov=null; S.screen='app'; S.onboarded=true; S.page='settings'; return render() }
  if (k==='unlock_move'){ return unlockFromSheet(()=>cardBtn('move')) }
  render();
}
function roleCheck(){ if(S.onboarded&&S.w.role!=='ours') trace(`При запуске: isRoleHeld = false — роль помощника у Gemini. Одним баннером в разговоре, не экраном с галочками.`,'e') }

/* Назад и вперёд по шагам мастера (в Android — BackHandler и свайп в Onboarding.kt): вправо — назад, влево — вперёд, но только если на экране уже можно «Далее».
   На «Попробуйте голосом» не работает: у кнопки микрофона жест «влево — отмена». В приложении «Назад» системы — тоже шаг назад. */
let onbSw = null;
document.addEventListener('pointerdown', e => {
  onbSw = null;
  if (S.screen !== 'app' || S.onboarded || ONB[S.onbStep] === 'try') return;
  if (!e.target.closest('#scr .onb') || e.target.closest('button,input,a,[data-act],[data-role],[data-side]')) return;
  onbSw = { x: e.clientX, y: e.clientY };
});
document.addEventListener('pointerup', e => {
  if (!onbSw) return; const dx = e.clientX - onbSw.x, dy = e.clientY - onbSw.y; onbSw = null;
  if (Math.abs(dx) < 60 || Math.abs(dy) > Math.abs(dx)) return;
  if (dx > 0) return onbBack();
  if ($('#scr .onb .nav .fbtn[data-act="onbnext"]')) { trace('Свайп влево — дальше (на этом шаге уже можно «Далее»).', 'p'); onbNext() }
});
function onbBack() {
  if (S.onbStep <= 0) return;
  S.onbStep--; if (ONB[S.onbStep] === 'try' && (S.w.model === 'none' || S.w.model === 'broken')) S.onbStep--;
  S.keyCheck = null; trace('Свайп вправо — шаг назад. В приложении так же работает системный «Назад».', 'p'); render();
}
