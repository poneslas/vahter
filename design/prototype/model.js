/* Модель данных и состояние экрана (в Android — data-классы, DataStore, ViewModel state). */
/* ───────── состояние ───────── */
const MODEL_TOTAL = 225539522 + 24110855;
const mb = b => (b/1048576).toFixed(0);
const MODES=[
  {id:'main', title:'Ассистент', icon:I.spark, sum:'Всегда включён · обычный разговор', photo:false, screen:false, tools:true, prompt:''},
  {id:'translate', title:'Переводчик', icon:I.transl, sum:'Голос и фото · русский ↔ китайский', photo:true, screen:false, tools:false, prompt:'Всё не на русском — переведи на русский. Всё на русском — переведи на китайский, добавь пиньинь. Коротко, без вступлений. Не сохраняй это в память.'},
  {id:'whatsthis', title:'Что это?', icon:I.lens, sum:'Сразу камера · товар, вывеска, экран', photo:true, screen:true, tools:false, prompt:'Пользователь в поездке. По фото или снимку экрана коротко скажи: что это, для чего, нормальная ли цена, на что обратить внимание. Переведи надписи. Не сохраняй это в память.'},
];
const fresh = () => { const o = ({
  w:{ net:'wifi', server:'none', model:'none', locked:false, role:'gemini', mic:'ask', notif:'ask' },
  sideOk:false, modelPct:0, screen:'home', page:'chat', onbStep:0, onboarded:false,
  dialog:null, toast:null, ov:null, pair:null, feeds:{main:[],translate:[],whatsthis:[],'m-4c1e':[]}, mode:'main', modeOn:{translate:true,whatsthis:true}, camFirst:{translate:false,whatsthis:true,'m-4c1e':true}, sendOnShot:{translate:false,whatsthis:true,'m-4c1e':true}, voiceOn:{main:true,translate:true,whatsthis:true,'m-4c1e':true}, stick:'30', idle:0, custom:[{id:'m-4c1e', title:'Гастрогид', icon:I.food, sum:'Свой · сразу камера · выбор по меню', photo:true, screen:false, tools:false, custom:true, prompt:'Я пришлю фото меню. Выбери мне 1–2 блюда и напиток под мои вкусы: не острое, без свинины, люблю рыбу и овощи. Если меню не на русском — пиши названия в оригинале и по-русски, чтобы я мог показать официанту. Коротко. Не сохраняй это в память.'}], modePick:false, draftImg:null, editMode:null, shortcuts:false, tg:[], failNext:null,
  upd:{ state:'none', pct:0, ver:'0.4.0', next:'0.5.0' }, busy:false, rec:null, sideAuto:true, nextKey:'ask', notifAsked:false, ret:null, dismissed:null,
}); o.drafts={}; Object.defineProperty(o,'feed',{get(){ return this.feeds[this.mode] }, set(v){ this.feeds[this.mode]=v }, enumerable:false});
  Object.defineProperty(o,'draft',{get(){ return this.drafts[this.mode]||'' }, set(v){ this.drafts[this.mode]=v }, enumerable:false}); return o };
const allModes = () => MODES.concat(S.custom);
const activeModes = () => allModes().filter(m=>m.id==='main'||m.custom||S.modeOn[m.id]);
const curMode = () => allModes().find(m=>m.id===S.mode) || MODES[0];
function setMode(id, why){ const was=S.mode; if (S.rec && was!==id){ recTok++; S.rec=null; pd=null; if(S.ov){ S.ov.live=''; S.ov.liveQ=false } trace('Запись отменена переключением режима — ничего не ушло. Новая запись сама не начинается: вы уже трогаете экран, микрофон рядом.','p') } S.mode=id; S.draftImg=null; S.modePick=false; if(!S.feeds[id]) S.feeds[id]=[]; if(was!==id){ const m=curMode(); trace(`${why||'Переключено'}: «${m.title}». Своя лента и своя сессия в Hermes (thread_id=${m.id==='main'?'—':m.id}); память Hermes общая.`,'p') } }
let S = fresh(); let token = 0;
function trace(text,kind=''){ const t=$('#trace'); const p=document.createElement('p'); p.className=kind; p.innerHTML=text; t.prepend(p); while(t.children.length>40) t.lastChild.remove(); const L=$('#lastlog'); if(L) L.innerHTML=text; if(typeof ptab!=='undefined' && ptab!=='log'){ const n=$('#lognew'); n.textContent=String((+n.textContent||0)+1) } }

