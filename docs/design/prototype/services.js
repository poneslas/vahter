/* Модель распознавания и подключение к серверу (в Android — ModelDownloader, Pairing). */
/* ───────── модель ───────── */
let dlT=null;
function startDl(skipAsk){
  if (S.w.net==='cell'&&!skipAsk){ S.dialog={ic:'📶',title:`Скачать ${mb(MODEL_TOTAL)} МБ по мобильной сети?`,text:'Можно дождаться Wi-Fi — загрузка начнётся сама.',opts:[['Скачать сейчас','dlnow'],['Дождаться Wi-Fi','x','sec2']]}; return render() }
  S.w.model='loading'; if(!S.modelPct||S.modelPct>=100) S.modelPct=1;
  if (!S.onboarded && S.w.mic==='ask' && !S.micNo) setTimeout(askMic,400);   // первый запуск: микрофон спрашиваем на шаге «Голос», пока качается модель
  trace(`Загрузка ${mb(MODEL_TOTAL)} МБ: файлы без архива, докачка по Range, проверка sha256. Сначала наш сервер, запасные — Hugging Face и GitHub.`,'p');
  clearInterval(dlT); dlT=setInterval(()=>{ if(S.w.model!=='loading'){clearInterval(dlT);return} if(S.w.net==='none'){ S.keepScroll=true; render(); return } S.modelPct=Math.min(100,S.modelPct+(RM?25:4)); if(S.modelPct>=100){ clearInterval(dlT); S.w.model='ready'; trace('Модель готова: голос работает без сети.','i'); showToast('Распознавание готово','Зажмите боковую кнопку и говорите') } S.keepScroll=true; render() },300);
  render();
}

/* ───────── подключение ───────── */
async function runPair(via){
  const my=++token; S.pair={via,stage:'checks',checks:CHECKS.map(c=>({id:c[0],t:c[1],d:'',s:'wait'}))};
  S.screen='app'; if(S.onboarded) S.page='connect'; else S.onbStep=ONB.indexOf('server');
  render();
  const map={dns:'net',tls:'tls',plugin:'plugin',expired:'admit'}; const fail=S.failNext;
  for (const c of S.pair.checks){
    c.s='run'; render(); await wait(400); if(my!==token) return;
    if ((S.w.net==='none'||S.w.server==='down')&&c.id==='net') return pairFail(c,S.w.net==='none'?'Нет интернета на телефоне.':'Сервер не отвечает. Проверьте адрес и что Hermes запущен.','Повторить');
    if (fail&&map[fail]===c.id){ S.failNext=null; const m={dns:'Сервер hermes.example.ru не найден. Проверьте адрес.',tls:'Сертификат сервера недействителен. Подключаться нельзя — это защита от подмены.',plugin:'Hermes отвечает, но плагина vahter на нём нет: hermes plugins enable vahter.',expired:'Ссылка устарела или уже использована. Попросите новую: /vahter_pair.'}[fail]; return pairFail(c,m,fail==='expired'?'Открыть Telegram':'Повторить',fail==='expired'?'viatg':null) }
    c.s='ok'; c.d={addr:'hermes.example.ru',net:'отвечает',tls:'действителен до 12.01.2027',plugin:'vahter 0.4 · Hermes 0.22.2',proto:'1',key:'в защищённом хранилище',admit:''}[c.id];
    if (c.id==='admit'){
      if (via==='addr'){ c.s='run'; c.d='Hermes выдал код'; S.pair.stage='code'; S.pair.code='K7Q2-M9XD'; trace('Неизвестный телефон получил код — штатный механизм Hermes (8 символов, 1 час). Нужно явно unauthorized_dm_behavior: pair — иначе при GATEWAY_ALLOWED_USERS Hermes промолчит.','h'); return render() }
      c.d='по одноразовой ссылке';
    }
    render();
  }
  pairDone();
}
function pairFail(c,msg,label,retry){ c.s='bad'; Object.assign(S.pair,{stage:'fail',err:msg,retryLabel:label,retry:retry||('re-'+S.pair.via)}); trace(msg,'e'); render() }
function pairDone(){ const c=S.pair.checks.find(x=>x.id==='admit'); c.s='ok'; c.d=c.d||'владелец подтвердил'; S.pair.stage='done'; S.w.server='paired'; S.dismissed=null; trace('Телефон допущен штатным PairingStore Hermes. Дальше вход — подписью ключа.','i'); render() }

