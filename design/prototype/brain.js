/* Разговор с мозгом: отправка реплик, ответы, шаги (в Android — ChatRepository + клиент контракта; ответы здесь — фейковый мозг). */
/* ───────── фразы и ответы ───────── */
const PHR={
  timer:{t:'Таймер на пять минут',w:['таймер','на','пять','минут'],fin:'Таймер на 5 минут.'},
  ask:{t:'Что у меня завтра?',w:['что','у','меня','завтра'],fin:'Что у меня завтра?'},
  backup:{t:'Как настроить бэкап сервера?',w:['как','настроить','бэкап','сервера'],fin:'Как настроить бэкап сервера?'},
  logo:{t:'Нарисуй три варианта логотипа',w:['нарисуй','три','варианта','логотипа'],fin:'Нарисуй три варианта логотипа.'},
  logs:{t:'Почисти старые логи на сервере',w:['почисти','старые','логи','на','сервере'],fin:'Почисти старые логи на сервере.'},
  table:{t:'Забронируй столик на субботу',w:['забронируй','столик','на','субботу'],fin:'Забронируй столик на субботу.'},
  move:{t:'Перенеси банк на пятницу',w:['перенеси','банк','на','пятницу'],fin:'Перенеси банк на пятницу.'},
  alarm:{t:'Поставь будильник',w:['поставь','будильник'],fin:'Поставь будильник.'},
  price:{t:'Сколько это в рублях?',w:['сколько','это','в','рублях'],fin:'Сколько это в рублях?'},
  spicy:{t:'А что здесь не острое?',w:['а','что','здесь','не','острое'],fin:'А что здесь не острое?'},
  pr:{t:'(диктовка промпта)',w:['если','есть','рыба','на','гриле','—','советуй','её','первой'],fin:'Если есть рыба на гриле — советуй её первой.'},
  toilet:{t:'Где здесь туалет?',w:['где','здесь','туалет'],fin:'Где здесь туалет?'},
};
const guessKey = v => { v=v.toLowerCase(); return /таймер/.test(v)?'timer':/будильник/.test(v)?'alarm':/перенеси/.test(v)?'move':/бэкап|backup/.test(v)?'backup':/логотип/.test(v)?'logo':/лог/.test(v)?'logs':/столик/.test(v)?'table':'ask' };
async function deliver(text, via){
  const P=S.pendingUp; if (P && P.mode===S.mode && (P.up.st==='up'||P.up.st==='wait'||P.up.st==='fail') && text){ P.x=(P.x?P.x+' ':'')+text; P.via=via+' · с фото'; S.keepScroll=true; trace('Фото ещё грузится — вопрос приклеен к нему и уйдёт одним сообщением.','p'); return render() }
  const img=S.draftImg; S.draftImg=null;
  if (S.mode!=='main' || img) return deliverMode(text, via, img);
  const key=guessKey(text);
  if (S.busy){ S.feed.push({t:'me',x:text,via:via+' · учтено'}); trace('Мозг ещё работает — реплика учтена в идущей работе (как в Telegram).','h'); return render() }
  const my=++token; S.busy=true;
  S.feed.push({t:'me',x:text,via}); if(S.ov){ S.ov.live=''; S.ov.liveQ=false } render();
  await route(key,my);
  S.busy=false; if (my===token&&S.ov){ S.ov.live='' } render();
}
async function deliverMode(text, via, img, bubble){
  if (bubble && bubble.mode!==S.mode){ const F=S.feeds[bubble.mode], m=allModes().find(x=>x.id===bubble.mode); await wait(1300);
    F.push({t:'ai',x:bubble.mode==='m-4c1e'?'<p><b>Берите 宫保鸡丁</b> без острого — 不要辣 (bú yào là).</p>':'<p>Ответ по фото — в этом режиме.</p>',done:true}); render();
    showToast(`Ответ в «${m.title}»`,'Фото ушло в том режиме, где его сняли'); return }
  return deliverModeIn(text,via,img,bubble) }
async function deliverModeIn(text, via, img, bubble){
  const m=curMode(); const my=++token; S.busy=true;
  if(!bubble) S.feed.push({t:'me',x:text||'',via:img?(text?via+' · с фото':'фото'):via,img}); if(S.ov){ S.ov.live=''; S.ov.liveQ=false } render();
  if (!brainOk()){ S.feed.push({t:'q',x:'Нет связи с Hermes. Передам, когда появится',s:'в очереди'}); S.busy=false; return render() }
  trace(`Режим «${m.title}»: thread_id=${m.id} → своя сессия Hermes (…:tasya:dm:&lt;телефон&gt;:${m.id}). Промпт режима плагин подмешивает в ход через pre_llm_call${m.tools?'':'; действия телефона для режима выключены — меньше инструментов, быстрее ответ'}.`,'h');
  if (S.ov){ S.ov.live='<span class="st h shim">Hermes думает…</span>'; S.ov.liveQ=true } render();
  await wait(img?1300:900); if(my!==token) return;
  if (m.id==='m-4c1e' && !img)
    await stream(my,`<p>Сфотографируйте меню — выберу под ваши вкусы.</p>`,{fast:true});
  else if (m.id==='m-4c1e' && img.cls!=='menu')
    await stream(my,`<p>Это не похоже на меню. Сфотографируйте страницу с блюдами.</p>`,{fast:true});
  else if (m.id==='m-4c1e')
    await stream(my,`<p><b>Берите:</b></p><ol><li><b>宫保鸡丁</b> — курица гунбао, ¥48. Попросите без острого: <b>不要辣</b> (bú yào là).</li><li><b>酸辣汤</b> — кисло-острый суп с грибами и яйцом, ¥22. Тоже <b>不要辣</b>.</li></ol><p>Не берите <b>麻婆豆腐</b>: острое и с фаршем из свинины. Рыбы в этом меню нет — если есть другая страница, снимите её.</p>`,{fast:true});
  else if (m.id==='translate' && img && img.cls==='menu' && text && /остр/i.test(text))
    await stream(my,`<p>Не острое здесь — <b>酸辣汤 только наполовину</b>: кисло-острый суп можно попросить без перца. Скажите: «不要辣» (bú yào là) — «без острого».</p>`,{fast:true});
  else if (m.id==='translate' && img && img.cls==='menu')
    await stream(my,`<div class="tbl"><table><tr><th>В меню</th><th>Перевод</th><th>Что это</th></tr><tr><td>宫保鸡丁 ¥48</td><td>Курица гунбао</td><td>острая, с арахисом</td></tr><tr><td>麻婆豆腐 ¥36</td><td>Тофу мапо</td><td>очень острое, с фаршем</td></tr><tr><td>酸辣汤 ¥22</td><td>Кисло-острый суп</td><td>с грибами и яйцом</td></tr></table></div><p>Если не любите острое — скажите «不要辣» (bú yào là).</p>`,{fast:true});
  else if (m.id==='translate')
    { if(!await stream(my,`<p><b>${text&&/туалет/i.test(text)?'洗手间在哪里？':'请给我看一下菜单。'}</b><br><span style="color:var(--soft)">${text&&/туалет/i.test(text)?'Xǐshǒujiān zài nǎlǐ? — Где здесь туалет?':'Qǐng gěi wǒ kàn yīxià càidān. — Покажите, пожалуйста, меню.'}</span></p>`,{fast:true})) return;
      const last=S.feed[S.feed.length-1]; last.speak=true; trace('Озвучка ответа — v1.1 (системный синтез Android умеет китайский). Кнопка заложена в режим заранее.','p') }
  else if (img && text && /рубл/i.test(text))
    await stream(my, `<p><b>Жасминовый чай</b> (茉莉花茶): ¥38 ≈ <b>470 ₽</b> за 250 г — обычная цена.</p>`, {fast:true});
  else if (!img)
    await stream(my, `<p>${text?'Без фото не скажу наверняка. ':''}Сфотографируйте предмет или сделайте снимок экрана — коротко объясню, что это.</p>`, {fast:true});
  else
    await stream(my, img&&img.cls==='scrn' ? `<p><b>Кофемашина Siemens EQ.6 plus</b> — автоматическая, с капучинатором.</p><ul><li>¥6 299 ≈ 77 000 ₽ — нормальная цена для Китая, в России дороже.</li><li>Проверьте вилку: китайская версия — 220 В, но вилка другая.</li></ul>`
      : `<p><b>Жасминовый чай</b> (茉莉花茶), 250 г, ¥38 ≈ 470 ₽.</p><ul><li>Зелёный чай с цветами жасмина — классика, пьют без сахара.</li><li>Цена обычная для супермаркета.</li><li>Заваривать 80 °C, 2–3 минуты.</li></ul>`, {fast:true});
  S.busy=false; if(S.ov) S.ov.live=''; render();
}
async function say(key,typed){ return deliver(PHR[key].fin, typed?'клавиатура':'голос') }   // для сценариев


async function think(my,steps){
  if (S.ov){ S.ov.live='<span class="st h shim">Hermes думает…</span>'; S.ov.liveQ=true }
  const st={t:'steps',id:Date.now(),run:true,cur:'Думаю…',items:[],plan:null,sec:0}; S.feed.push(st); render(); let ms=0;
  for (const s of steps){
    if (s.todo){ // вызов todo_list: в шагах не показываем, обновляем план целиком по ответу инструмента
      st.plan={rev:s.rev,items:s.todo.map(([c,stt])=>({c,st:stt}))}; render(); await wait(350); if(my!==token) return false; continue }
    st.cur=s.t+'…'; st.items.push(s); render(); await wait(s.wait||800); ms+=s.wait||800; if(my!==token) return false }
  st.run=false; st.sec=Math.max(1,Math.round(ms/1000)); return true;   // в конце хода ничего не досчитываем за модель
}
let msgSeq=0;
async function stream(my,html,opts={}){
  const blocks=html.split('<!--b-->'); const msg={t:'ai',id:'m'+(++msgSeq),x:'',long:opts.long,interim:opts.interim,done:false}; S.feed.push(msg);
  if (S.ov){ S.ov.live='' }
  for (const b of blocks){ msg.x+=b; render(); await wait(opts.fast?120:280); if(my!==token) return false }
  msg.done=true; render(); return true;
}
async function sendAtt(my,a,cap){ await wait(450); if(my!==token) return false; S.feed.push({t:'att',a,cap}); render(); return true }
async function route(key,my){
  if (key==='timer'){ S.feed.push({t:'act',i:'⏱',x:'Таймер · 5:00',s:'на телефоне',btn:'Открыть таймеры',kind:'D',id:Date.now()}); trace('Правило: «таймер на {длительность}» — без сети. Отменить таймер «Часов» Android не даёт — кнопка честная: «Открыть таймеры».','p'); return }
  if (key==='alarm'){ S.feed.push({t:'ph',x:'На когда?'}); S.feed.push({t:'chips',c:['7:00','7:30','8:00']}); trace('Не хватает времени → варианты без модели: стоящие будильники, обычный подъём, первая встреча минус час.','p'); return }
  if (S.w.server==='none'){ S.feed.push({t:'card',who:'h',head:'Нужен агент на сервере',title:'Сервер не подключён',text:'Команды телефона работают и так.',btns:[['Подключить','connect','pri']]}); trace('Не команда телефона, а сервера нет — предлагаем подключить.','e'); return }
  if (!brainOk()){ S.feed.push({t:'q',x:'Нет связи с Hermes — передам, когда появится',s:'в очереди'}); trace('Вопрос в очереди (Room); уйдёт фоновой задачей, даже если закрыть лист.','e'); return }
  if (key==='ask'){ if(!await think(my,[{i:'📅',t:'Смотрю календарь',phone:true,d:'phone.calendar.query · завтра · 2 события',ms:'0,3 с'}])) return;
    await stream(my,'<p>Завтра две встречи: в <b>10:00</b> банк, в <b>15:30</b> созвон с Олей. Утро свободно.</p>',{fast:true}); trace('Короткий ответ — одно сообщение, целиком в листе.','h'); return }
  if (key==='move'){ if(!await think(my,[{i:'📅',t:'Ищу встречу с банком',phone:true,d:'phone.calendar.query',ms:'0,2 с'}])) return;
    S.feed.push({t:'card',who:'h',head:'Hermes просит · календарь',title:'Встреча с банком',kv:[['Было','чт, 18 окт, 10:00'],['Станет','пт, 19 окт, 10:00–10:30']],btns:S.w.locked?[['Разблокировать и перенести','unlockmove','pri'],['Отмена','x','gh']]:[['Перенести','move','pri'],['Изменить','x'],['Отмена','x','gh']]}); return }
  if (key==='backup'){
    if(!await stream(my,'<p>Посмотрю, что уже стоит на сервере, и соберу план.</p>',{fast:true,interim:true})) return;
    trace('Сообщение 1 — промежуточное (on_interim_message): короткое, приглушённое.','h');
    if(!await think(my,[
      {todo:[['Узнать, что уже стоит на сервере','in_progress'],['Выбрать инструмент','pending'],['Расписать шаги','pending'],['Сделать памятку','pending']],rev:1},
      {i:'🔎',t:'Ищу в интернете',d:'<div class="src"><i style="background:#E05D44"></i>restic.readthedocs.io</div><div class="src"><i style="background:#2F9E5B"></i>wiki.archlinux.org</div><div class="src"><i style="background:#2238B8"></i>habr.com</div>',ms:'2,1 с',wait:1000},
      {i:'⌨',t:'Команда на сервере',d:'<div class="term">$ df -h /srv\nFilesystem  Size  Used Avail Use%\n/dev/sda1   200G  121G   79G  61%\n$ which restic\n/usr/bin/restic</div>',ms:'0,8 с',wait:900},
      {todo:[['Узнать, что уже стоит на сервере','completed'],['Выбрать инструмент','completed'],['Расписать шаги','in_progress'],['Сделать памятку','pending']],rev:2},
      {i:'⚙',t:'Выполняю код',d:'execute_code · python · 38 строк → backup-plan.pdf',ms:'1,4 с',wait:900},
      {todo:[['Узнать, что уже стоит на сервере','completed'],['Выбрать инструмент','completed'],['Расписать шаги','completed'],['Сделать памятку','completed']],rev:3},
    ])) return;
    trace('План — это вызовы todo_list самой модели (ревизии 1→2→3), не шаги. Между вызовами план может отставать от работы — показываем как есть. Если модель не отметит пункт, после хода он останется «в работе» без анимации; досчитывать за неё не будем.','h');
    if(!await stream(my,LONG_MD,{long:true})) return;
    trace('Сообщение 2 — финальный текст (сырой Markdown; при потоке — правки одного сообщения). В листе — первые ~240 px и «Развернуть сообщение» у этого сообщения.','h');
    if(!await sendAtt(my,[{k:'chart',title:'disk-forecast.png'}])) return;
    if(!await sendAtt(my,[{k:'file',type:'PDF',name:'backup-plan.pdf',meta:'84 КБ'}])) return;
    trace('Дальше — вложения, вырезанные из финала: сначала картинки (send_multiple_images, даже одна), потом документы по одному (send_document). Подписей у файлов нет — показываем имя. Текста после файлов в этом ходе не будет.','h'); return }
  if (key==='logo'){
    if(!await think(my,[{i:'🎨',t:'Рисую',d:'image_generate · вариант 1',ms:'6,2 с',wait:700},{i:'🎨',t:'Рисую',d:'image_generate · вариант 2',ms:'5,8 с',wait:700},{i:'🎨',t:'Рисую',d:'image_generate · вариант 3',ms:'6,0 с',wait:700}])) return;
    if(!await stream(my,'<p>Три варианта: строгий, игривый и только знак. Какой ближе?</p>',{fast:true})) return;
    if(!await sendAtt(my,[{k:'imgs',n:3}])) return;
    trace('image_generate сам дописывает MEDIA-теги → после текста одним send_multiple_images приходит альбом из 3 картинок. Лимита на число нет: 40 картинок пришли бы одним вызовом — сетка показывает 4 и «+36», остальное в просмотрщике.','h'); return }
  if (key==='logs'){
    if(!await think(my,[{i:'⌨',t:'Команда на сервере',d:'<div class="term">$ du -sh /var/log/app/*.gz | tail -1\n4.1G  total</div>',ms:'0,4 с'}])) return;
    S.feed.push({t:'card',who:'h',head:'Hermes просит разрешения',title:'Выполнить команду на сервере?',code:'rm -rf /var/log/app/*.gz',text:'Причина: рекурсивное удаление',tmr:'Без ответа за 5 минут — не выполнится.',btns:[['Один раз','apr_once','pri'],['До конца сессии','apr_once'],['Всегда','apr_once'],['Нет','apr_no','gh']]});
    trace('Это редкий случай. По умолчанию у Hermes approvals.mode: smart — безобидное разрешает, явно опасное запрещает вспомогательная модель, человека спрашивает только в сомнительных случаях. Hermes присылает лишь команду, причину по шаблону (англ. «recursive delete» — переводим по словарю) и варианты; заголовок-пересказ мы не выдумываем. При умном запрете вариантов только «Один раз / Нет».','h'); render(); return }
  if (key==='table'){
    if(!await think(my,[{i:'🔎',t:'Ищу в интернете',d:'<div class="src"><i style="background:#E05D44"></i>restoclub.ru</div>',ms:'1,2 с'}])) return;
    S.feed.push({t:'card',who:'h',head:'Hermes уточняет',title:'На сколько человек?',btns:[['2','clar'],['4','clar'],['6','clar'],['Другое…','clar_other','gh']]});
    trace('Уточнение — инструмент clarify (send_clarify): варианты с сервера, «Другое» рисуем сами (mark_awaiting_text), бывает мультивыбор и открытый вопрос без вариантов.','h'); render(); return }
}
const LONG_MD=`<h3>Бэкап сервера на restic — за 15 минут</h3><!--b--><p>На сервере уже стоит <code>restic</code>, свободно 79 ГБ. Предлагаю шифрованный бэкап в S3 раз в сутки: храним 7 дней, 4 недели и 6 месяцев.</p><!--b--><h4>Шаги</h4><ol><li>Создайте бакет и ключ доступа в хранилище.</li><li>Подготовьте репозиторий:</li></ol><!--b--><div class="code"><div class="ch"><span>bash</span><button>Копировать</button></div><pre>export RESTIC_REPOSITORY=s3:s3.example.ru/backup
export RESTIC_PASSWORD_FILE=/root/.restic-pass
restic init</pre></div><!--b--><ol start="3"><li>Добавьте запуск по расписанию:</li></ol><div class="code"><div class="ch"><span>ini</span><button>Копировать</button></div><pre>[Timer]
OnCalendar=*-*-* 03:30
Persistent=true</pre></div><!--b--><h4>Сколько стоит</h4><div class="tbl"><table><tr><th>Вариант</th><th>Место</th><th>В месяц</th><th>Восстановление</th></tr><tr><td>S3, стандарт</td><td>~140 ГБ</td><td>~280 ₽</td><td>минуты</td></tr><tr><td>S3, холодное</td><td>~140 ГБ</td><td>~90 ₽</td><td>часы</td></tr><tr><td>Второй сервер</td><td>200 ГБ</td><td>~450 ₽</td><td>минуты</td></tr></table></div><!--b--><blockquote>Пароль от репозитория храните отдельно от сервера: без него бэкап не открыть.</blockquote><!--b--><p>Все команды — в памятке ниже. Если хотите, могу сам создать бакет и включить таймер — понадобится ваше «да».</p><div class="src-row"><span class="cite">restic.readthedocs.io</span><span class="cite">wiki.archlinux.org</span><span class="cite">habr.com</span></div>`;
function flushQueue(){ let n=0; S.feed.forEach(f=>{ if(f.t==='q'){ f.t='ph'; f.x='Ушло Hermes из очереди'; n++ } }); if(n) trace(`Связь есть — очередь (${n}) ушла по порядку.`,'i') }

let toastT=null;
function showToast(title,text,act){ S.toast={title,text,act}; S.keepScroll=true; render(); clearTimeout(toastT); toastT=setTimeout(()=>{S.toast=null;S.keepScroll=true;render()},4200) }

