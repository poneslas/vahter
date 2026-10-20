/* Модель данных и состояние экрана (в Android — data-классы, DataStore, ViewModel state). */
/* ───────── состояние ───────── */
const MODEL_TOTAL = 224893347; // GigaAM v3 e2e, один файл (как у Гига Писаря для Android)
const mb = b => (b/1048576).toFixed(0);
/**
 * Режим — отдельный разговор с готовым промптом. Хранится на телефоне, уходит мозгу с каждой репликой
 * (turn.mode = {id, prompt, tools}). Отвечает мозг режима (brain): у прямого API история режима хранится на телефоне
 * и целиком уходит с каждой репликой; у Hermes — своя сессия по thread_id = id (Архитектура, 2.2 и 9.6.1).
 * @typedef {Object} Mode
 * @property {string}  id          постоянный: встроенные 'main' | 'translate' | 'whatsthis', свои — 'm-xxxx' при создании
 * @property {string}  title       название на вкладке
 * @property {string}  icon        ключ из MODE_ICONS (значок в настройках, ярлыках, на камере)
 * @property {string}  hint        текст в пустом разговоре режима
 * @property {string}  placeholder подсказка в поле ввода
 * @property {string}  prompt      инструкция мозгу; у Ассистента пусто
 * @property {{voice:boolean, photo:boolean, file:boolean}} accepts  что принимает (текст — всегда)
 * @property {'voice'|'photo'} primary  большая кнопка и действие при входе: слушать или камера
 * @property {string}  brain       id мозга из S.brains; режиму с фото нужен мозг со зрением
 * @property {boolean} tools       действия телефона доступны мозгу
 * @property {boolean} builtin     встроенный: нельзя удалить; выключить можно всё, кроме Ассистента
 * @property {boolean} enabled     показан во вкладках и ярлыках
 * @property {'ru'}    asr         язык распознавания (сейчас только русский — GigaAM)
 * @property {'off'}   tts         озвучка ответа (v1.1)
 */
const MODE_ICONS=['spark','transl','lens','food','cart','flight','book','star','edit'];
const mode = o => ({hint:'', placeholder:'Вопрос или задача', prompt:'', accepts:{voice:true,photo:false,file:false}, primary:'voice', brain:'deepseek', tools:false, builtin:false, enabled:true, asr:'ru', tts:'off', ...o});
const BUILTIN_MODES=[
  mode({id:'main', title:'Ассистент', icon:'spark', hint:'Спросите что угодно или попросите телефон: таймер, будильник, календарь.', placeholder:'Спросить или попросить', accepts:{voice:true,photo:true,file:true}, tools:true, builtin:true}),
  mode({id:'translate', title:'Переводчик', icon:'transl', builtin:true, hint:'Скажите или напишите — переведу. Можно сфотографировать надпись или меню.', placeholder:'Что перевести?', accepts:{voice:true,photo:true,file:false},
    prompt:'Всё не на русском — переведи на русский. Всё на русском — переведи на китайский, добавь пиньинь. Коротко, без вступлений. Не сохраняй это в память.'}),
  mode({id:'whatsthis', title:'Что это?', icon:'lens', builtin:true, primary:'photo', hint:'Сфотографируйте товар, вывеску или меню — коротко объясню, что это.', placeholder:'Вопрос к фото', accepts:{voice:true,photo:true,file:false},
    prompt:'Пользователь в поездке. По фото коротко скажи: что это, для чего, нормальная ли цена, на что обратить внимание. Переведи надписи. Не сохраняй это в память.'}),
];
/** Пример своего режима — как если бы его создали через «+». */
const SAMPLE_CUSTOM=mode({id:'m-4c1e', title:'Гастрогид', icon:'food', primary:'photo', hint:'Сфотографируйте меню — выберу под ваши вкусы.', placeholder:'Вопрос к меню', accepts:{voice:true,photo:true,file:false},
  prompt:'Я пришлю фото меню. Выбери мне 1–2 блюда и напиток под мои вкусы: не острое, без свинины, люблю рыбу и овощи. Если меню не на русском — пиши названия в оригинале и по-русски, чтобы я мог показать официанту. Коротко. Не сохраняй это в память.'});
/**
 * Мозг — кто отвечает. Два вида, абстракции общие с первого дня: прямой API любого OpenAI-совместимого провайдера
 * (цикл вызова инструментов — в приложении) и Hermes на своём сервере (память, поиск, сервер, расписание, Telegram).
 * Порядок реализации: сначала API, следом Hermes (Архитектура, 17).
 * @typedef {Object} Brain
 * @property {string} id
 * @property {'api'|'hermes'} kind
 * @property {string} title
 * @property {string} [url]   адрес API (kind=api)
 * @property {string} [model] имя модели (kind=api)
 * @property {{vision:boolean, tools:boolean, memory:boolean, server:boolean, maxImages:number}} caps  maxImages — сколько картинок в одной реплике (DeepSeek: наш лимит 10, API принимает не меньше — замер 18.10)
 */
const BRAINS=[
  {id:'deepseek', kind:'api', title:'DeepSeek', url:'https://api.deepseek.com', model:'deepseek-chat', caps:{vision:true,tools:true,memory:false,server:false,maxImages:10}},
  {id:'hermes', kind:'hermes', title:'Hermes', caps:{vision:true,tools:true,memory:true,server:true,maxImages:10}},
];
/**
 * Действие телефона (реестр — Архитектура, 7). Пользователю показываем только одно: делает ли Вахтёр это сам,
 * спрашивает или не делает. Как фраза понята (правилом или мозгом) и класс отмены — внутренняя кухня, в настройках не видны.
 * @typedef {Object} Tool
 * @property {string} id     имя в реестре: 'alarm.set' (видно только в журнале)
 * @property {string} title  общепринятое название, как в настройках Android: «Будильник», «Местоположение»
 * @property {string} group  группа по смыслу
 * @property {'auto'|'ask'|'off'} def  по умолчанию
 * @property {boolean} [safe]   можно на заблокированном телефоне в режиме «только безопасное»
 * @property {boolean} [always] всегда спрашивает (сообщения): «Разрешить» не предлагается
 * @property {'calendar'|'location'|'contacts'|'dnd'} [perm] системный доступ Android, без которого действие не работает
 */
const TOOL_GROUPS=['Часы','Календарь','Телефон','Приложения и связь'];
const TOOLS=[
  ['alarm.set','Будильник','Часы','auto',{safe:true}],
  ['alarm.dismiss','Выключение будильника','Часы','auto',{safe:true}],
  ['timer.set','Таймер','Часы','auto',{safe:true}],
  ['timer.stop','Остановка таймера','Часы','auto',{safe:true}],
  ['alarm.next','Ближайший будильник','Часы','auto',{safe:true}],
  ['calendar.query','Просмотр календаря','Календарь','auto',{perm:'calendar'}],
  ['calendar.add','Создание и удаление событий','Календарь','ask',{perm:'calendar'}],
  ['torch.set','Фонарик','Телефон','auto',{safe:true}],
  ['volume.set','Громкость','Телефон','auto'],
  ['dnd.set','Не беспокоить','Телефон','auto',{perm:'dnd'}],
  ['media.control','Управление музыкой','Телефон','auto'],
  ['location.get','Местоположение','Телефон','ask',{perm:'location'}],
  ['place.save','Сохранённые места','Телефон','auto'],
  ['note.remember','Заметки','Телефон','auto'],
  ['app.open','Запуск приложений','Приложения и связь','auto'],
  ['settings.open','Системные настройки','Приложения и связь','auto'],
  ['call.dial','Звонки','Приложения и связь','ask',{perm:'contacts'}],
  ['message.draft','Сообщения','Приложения и связь','ask',{always:true,perm:'contacts'}],
].map(([id,title,group,def,o])=>({id,title,group,def,...(o||{})}));
/** Слова — как в системном диалоге разрешений Android. */
/** Системные доступы Android, от которых зависят действия. Состояние: ok — выдан, ask — ещё не спрашивали, denied — отказано (окно больше не покажется — только настройки). */
const SYS_PERMS={camera:'Камера',calendar:'Календарь',location:'Местоположение',contacts:'Контакты',dnd:'«Не беспокоить»'};
const permOk = k => !k || S.w.perms[k]==='ok';
/** Не работает: Android отказал в доступе. «Ещё не спрашивали» — не поломка: спросим при первом использовании. */
const toolBlocked = x => S.policy[x.id]!=='off' && !!x.perm && S.w.perms[x.perm]==='denied';
const toolPending = x => S.policy[x.id]!=='off' && !!x.perm && S.w.perms[x.perm]==='ask';
const POLICY_OPTS=[['auto','Разрешить'],['ask','Спрашивать каждый раз'],['off','Не разрешать']];
const POLICY_WORD={auto:'Разрешено',ask:'Спрашивать',off:'Не разрешено'};
/** На заблокированном экране: off — сначала разблокировка (по умолчанию); safe — только будильник, таймер, фонарик; on — разговор работает, действия с данными просят разблокировку. */
const LOCK_SCOPE=[['off','Не открывать'],['safe','Только будильник, таймер, фонарик'],['on','Открывать']];

/** Настройки приложения, которых нет у режимов. */
const RETURN_OPTS=[['30m','Через 30 минут без дела'],['never','Никогда — открывать последний режим'],['always','Всегда — боковая кнопка открывает Ассистента']];
const clone = o => JSON.parse(JSON.stringify(o));
/** Одна строка описания режима в списке — выводится из полей, не хранится. */
function modeSummary(m){
  if (m.id==='main') return 'Основной разговор · все действия телефона';
  const ins=[m.accepts.photo&&'фото',m.accepts.file&&'файл'].filter(Boolean);
  return [m.builtin?'':'Свой', m.primary==='photo'?'сразу камера':'голос', ins.length?ins.join(', '):''].filter(Boolean).join(' · ') }

const fresh = () => { const o = ({
  w:{ net:'wifi', key:'none', server:'none', side:'power', profile:'samsung', perms:{camera:'ok',calendar:'ok',location:'ask',contacts:'ok',dnd:'ask'}, model:'none', locked:false, role:'gemini', mic:'ask', notif:'ask' },
  modelPct:0, screen:'home', page:'chat', onbStep:0, onboarded:false,
  dialog:null, toast:null, ov:null, pair:null, tg:[], failNext:null,
  modes:[...clone(BUILTIN_MODES), clone(SAMPLE_CUSTOM)], brains:clone(BRAINS), keyCheck:null, mode:'main', feeds:{}, drafts:{}, editMode:null, shortcuts:false, attachMenu:false, pendingUp:null, idle:0,
  settings:{ returnToAssistant:'30m', lockScope:'off' },
  policy:Object.fromEntries(TOOLS.map(t=>[t.id,t.def])), editTool:null, jopen:null,
  journal:[
    {id:'j3',day:'Сегодня',at:'09:05',tool:'calendar.add',title:'Встреча с Олей · пт 15:00',who:'DeepSeek',phrase:'запиши встречу с Олей в пятницу в 15',params:'title="Встреча с Олей", start=2026-10-23T15:00',confirm:'«Да» нажатием',lock:false,undo:true},
    {id:'j2',day:'Сегодня',at:'08:40',tool:'torch.set',title:'Фонарик · выключен',who:'по правилам',phrase:'выключи фонарик',params:'on=false',confirm:'сразу',lock:true,status:'отменено'},
    {id:'j1',day:'Сегодня',at:'07:12',tool:'alarm.next',title:'Ближайший будильник · 7:00',who:'по правилам',phrase:'когда у меня будильник?',params:'—',confirm:'сразу',lock:false},
    {id:'j4',day:'Вчера',at:'22:30',tool:'alarm.set',title:'Будильник · 7:00',who:'по правилам',phrase:'будильник на семь',params:'time=07:00',confirm:'сразу',lock:false},
  ],
  upd:{ state:'none', pct:0, ver:'0.4.0', next:'0.5.0' }, busy:false, rec:null, nextKey:'ask', notifAsked:false, ret:null, dismissed:null,
}); /* роль «Цифровой помощник» (w.role) и действие боковой кнопки Samsung (w.side) — два разных состояния */
  Object.defineProperty(o,'sideOk',{get(){ return this.w.side==='assistant' }, set(v){ this.w.side=v?'assistant':'power' }, enumerable:false});
  Object.defineProperty(o,'feed',{get(){ return this.feeds[this.mode] ??= [] }, set(v){ this.feeds[this.mode]=v }, enumerable:false});
  Object.defineProperty(o,'draft',{get(){ return this.drafts[this.mode]||'' }, set(v){ this.drafts[this.mode]=v }, enumerable:false}); return o };
const modeById = id => S.modes.find(m=>m.id===id);
const toolById = id => TOOLS.find(t=>t.id===id);
/** Можно ли на заблокированном телефоне без разблокировки. */
const lockOk = id => S.settings.lockScope!=='off' && !!toolById(id).safe;
/** Строка итога в разговоре + запись в журнал действий. */
function addAct(item, j){ S.feed.push(item); const d=new Date(); S.journal.unshift({id:'j'+Date.now(),day:'Сегодня',at:d.toTimeString().slice(0,5),title:item.x,status:item.undone?'отклонено':'',...j}) }
const brainById = id => S.brains.find(b=>b.id===id);
const brainOf = (m=curMode()) => brainById(m.brain) || S.brains[0];
/** Мозг готов ответить: у API — есть ключ и сеть; у Hermes — сервер подключён и есть сеть. */
const brainReady = (b=brainOf()) => b.kind==='api' ? S.w.key==='ok' : S.w.server==='paired';
const activeModes = () => S.modes.filter(m=>m.enabled);
const curMode = () => modeById(S.mode) || S.modes[0];
function setMode(id, why){ const was=S.mode; if (S.rec && was!==id){ recTok++; S.rec=null; pd=null; if(S.ov){ S.ov.live=''; S.ov.liveQ=false } trace('Запись отменена переключением режима — ничего не ушло. Новая запись сама не начинается: вы уже трогаете экран, микрофон рядом.','p') } S.mode=id; S.attachMenu=false; camOnModeSwitch(was); if(was!==id){ const m=curMode(); trace(`${why||'Переключено'}: «${m.title}». Своя лента и своя сессия в Hermes (thread_id=${m.id==='main'?'—':m.id}); память Hermes общая.`,'p') } }
/* Камера в листе при смене режима (как в приложении): режим «сразу камера» — камера остаётся, иначе закрывается и виден лист. */
function camOnModeSwitch(was){ if (S.screen!=='cam2'||!S.cam||was===S.mode) return; const m=curMode();
  if (m.primary==='photo'&&m.accepts.photo) return;
  S.screen=S.camRet.screen; S.ov=S.camRet.ov; S.cam=null; if(S.rec){ recTok++; S.rec=null }
  trace(`«${m.title}» начинается с голоса — камера закрыта, виден лист.`,'p') }
let S = fresh(); let token = 0;
function trace(text,kind=''){ const t=$('#trace'); const p=document.createElement('p'); p.className=kind; p.innerHTML=text; t.prepend(p); while(t.children.length>40) t.lastChild.remove(); const L=$('#lastlog'); if(L) L.innerHTML=text; if(typeof ptab!=='undefined' && ptab!=='log'){ const n=$('#lognew'); n.textContent=String((+n.textContent||0)+1) } }

