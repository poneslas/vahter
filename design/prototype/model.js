/* Модель данных и состояние экрана (в Android — data-классы, DataStore, ViewModel state). */
/* ───────── состояние ───────── */
const MODEL_TOTAL = 225539522 + 24110855;
const mb = b => (b/1048576).toFixed(0);
/**
 * Режим — отдельный разговор с готовым промптом. Хранится на телефоне, уходит мозгу с каждой репликой
 * (turn.mode = {id, prompt, tools}); в Hermes — своя сессия по thread_id = id (Архитектура, 9.6.1).
 * @typedef {Object} Mode
 * @property {string}  id          постоянный: встроенные 'main' | 'translate' | 'whatsthis', свои — 'm-xxxx' при создании
 * @property {string}  title       название на вкладке
 * @property {string}  icon        ключ из MODE_ICONS (значок в настройках, ярлыках, на камере)
 * @property {string}  hint        текст в пустом разговоре режима
 * @property {string}  placeholder подсказка в поле ввода
 * @property {string}  prompt      инструкция мозгу; у Ассистента пусто
 * @property {{voice:boolean, photo:boolean, file:boolean}} accepts  что принимает (текст — всегда)
 * @property {'voice'|'photo'} primary  большая кнопка и действие при входе: слушать или камера
 * @property {boolean} tools       действия телефона доступны мозгу
 * @property {boolean} builtin     встроенный: нельзя удалить; выключить можно всё, кроме Ассистента
 * @property {boolean} enabled     показан во вкладках и ярлыках
 * @property {'ru'}    asr         язык распознавания (сейчас только русский — GigaAM)
 * @property {'off'}   tts         озвучка ответа (v1.1)
 */
const MODE_ICONS=['spark','transl','lens','food','cart','flight','book','star','edit'];
const mode = o => ({hint:'', placeholder:'Вопрос или задача', prompt:'', accepts:{voice:true,photo:false,file:false}, primary:'voice', tools:false, builtin:false, enabled:true, asr:'ru', tts:'off', ...o});
const BUILTIN_MODES=[
  mode({id:'main', title:'Ассистент', icon:'spark', placeholder:'Написать Hermes', accepts:{voice:true,photo:true,file:true}, tools:true, builtin:true}),
  mode({id:'translate', title:'Переводчик', icon:'transl', builtin:true, hint:'Скажите или напишите — переведу. Можно сфотографировать надпись или меню.', placeholder:'Что перевести?', accepts:{voice:true,photo:true,file:false},
    prompt:'Всё не на русском — переведи на русский. Всё на русском — переведи на китайский, добавь пиньинь. Коротко, без вступлений. Не сохраняй это в память.'}),
  mode({id:'whatsthis', title:'Что это?', icon:'lens', builtin:true, primary:'photo', hint:'Сфотографируйте товар, вывеску или меню — коротко объясню, что это.', placeholder:'Вопрос к фото', accepts:{voice:true,photo:true,file:false},
    prompt:'Пользователь в поездке. По фото коротко скажи: что это, для чего, нормальная ли цена, на что обратить внимание. Переведи надписи. Не сохраняй это в память.'}),
];
/** Пример своего режима — как если бы его создали через «+». */
const SAMPLE_CUSTOM=mode({id:'m-4c1e', title:'Гастрогид', icon:'food', primary:'photo', hint:'Сфотографируйте меню — выберу под ваши вкусы.', placeholder:'Вопрос к меню', accepts:{voice:true,photo:true,file:false},
  prompt:'Я пришлю фото меню. Выбери мне 1–2 блюда и напиток под мои вкусы: не острое, без свинины, люблю рыбу и овощи. Если меню не на русском — пиши названия в оригинале и по-русски, чтобы я мог показать официанту. Коротко. Не сохраняй это в память.'});
/** Настройки приложения, которых нет у режимов. */
const RETURN_OPTS=[['30m','Через 30 минут без дела'],['never','Никогда — открывать последний режим'],['always','Всегда — боковая кнопка открывает Ассистента']];
const clone = o => JSON.parse(JSON.stringify(o));
/** Одна строка описания режима в списке — выводится из полей, не хранится. */
function modeSummary(m){
  if (m.id==='main') return 'Основной разговор · все действия телефона';
  const ins=[m.accepts.photo&&'фото',m.accepts.file&&'файл'].filter(Boolean);
  return [m.builtin?'':'Свой', m.primary==='photo'?'сразу камера':'голос', ins.length?ins.join(', '):''].filter(Boolean).join(' · ') }

const fresh = () => { const o = ({
  w:{ net:'wifi', server:'none', model:'none', locked:false, role:'gemini', mic:'ask', notif:'ask' },
  sideOk:false, modelPct:0, screen:'home', page:'chat', onbStep:0, onboarded:false,
  dialog:null, toast:null, ov:null, pair:null, tg:[], failNext:null,
  modes:[...clone(BUILTIN_MODES), clone(SAMPLE_CUSTOM)], mode:'main', feeds:{}, drafts:{}, editMode:null, shortcuts:false, attachMenu:false, pendingUp:null, idle:0,
  settings:{ returnToAssistant:'30m' },
  upd:{ state:'none', pct:0, ver:'0.4.0', next:'0.5.0' }, busy:false, rec:null, nextKey:'ask', notifAsked:false, ret:null, dismissed:null,
}); Object.defineProperty(o,'feed',{get(){ return this.feeds[this.mode] ??= [] }, set(v){ this.feeds[this.mode]=v }, enumerable:false});
  Object.defineProperty(o,'draft',{get(){ return this.drafts[this.mode]||'' }, set(v){ this.drafts[this.mode]=v }, enumerable:false}); return o };
const modeById = id => S.modes.find(m=>m.id===id);
const activeModes = () => S.modes.filter(m=>m.enabled);
const curMode = () => modeById(S.mode) || S.modes[0];
function setMode(id, why){ const was=S.mode; if (S.rec && was!==id){ recTok++; S.rec=null; pd=null; if(S.ov){ S.ov.live=''; S.ov.liveQ=false } trace('Запись отменена переключением режима — ничего не ушло. Новая запись сама не начинается: вы уже трогаете экран, микрофон рядом.','p') } S.mode=id; S.attachMenu=false; if(was!==id){ const m=curMode(); trace(`${why||'Переключено'}: «${m.title}». Своя лента и своя сессия в Hermes (thread_id=${m.id==='main'?'—':m.id}); память Hermes общая.`,'p') } }
let S = fresh(); let token = 0;
function trace(text,kind=''){ const t=$('#trace'); const p=document.createElement('p'); p.className=kind; p.innerHTML=text; t.prepend(p); while(t.children.length>40) t.lastChild.remove(); const L=$('#lastlog'); if(L) L.innerHTML=text; if(typeof ptab!=='undefined' && ptab!=='log'){ const n=$('#lognew'); n.textContent=String((+n.textContent||0)+1) } }

