/* Голос: эквалайзер, запись push-to-talk, жесты микрофона (в Android — VoiceRecorder + GigaAM). */
/* ───────── эквалайзер ───────── */
class Eq{
  constructor(el){ this.el=el; this.sm=el.classList.contains('sm'); const N=this.sm?9:13; el.innerHTML='<i></i>'.repeat(N); this.b=[...el.children]; this.n=N; this.h=Array(13).fill(0); this.g=Array(13).fill(1); this.lag=Array(13).fill(0); this.L=0; this.pv=0; this.ph=0; this.f=0; this.t=0; this.syl=0; this.end=0 }
  shape(i){ return .72+.28*Math.sin(Math.PI*(i+.5)/this.n) }
  reroll(L){ for(let i=0;i<this.n;i++){ const lo=.75-.55*L; this.g[i]=lo+Math.random()*(1-lo); this.lag[i]=Math.floor(Math.random()*(Math.max(3,9-6*L)+1)) } }
  voice(dt){ this.t+=dt; if(this.t>this.end){ this.t=0; this.end=120+Math.random()*140; this.syl=Math.random()<.18?.05:.35+Math.random()*.6 } return this.syl*(.4+.6*Math.sin(Math.PI*Math.min(1,this.t/this.end))) }
  step(dt){
    const m=this.el.dataset.mode, mn=this.sm?3:5, H=this.sm?15:33; this.f++;
    if(m==='dots'){ this.b.forEach(b=>b.style.height=mn+'px'); return }
    if(m==='wave'){ this.ph+=.28*dt/16.7; this.b.forEach((b,i)=>{ const s=.5+.5*Math.sin(this.ph-i*.55); b.style.height=(mn+H*(.18+.32*s))+'px' }); return }
    const inp=this.voice(dt); this.L+=(inp-this.L)*(inp>this.L?.75:.13); const L=this.L;
    if(inp-this.pv>.1) this.reroll(L); else if(L>.35&&this.f%Math.max(4,Math.round(18-15*L))===0) this.reroll(L); this.pv=inp;
    for(let i=0;i<this.n;i++){ if(this.lag[i]>0){this.lag[i]--;continue} const tg=L*this.shape(i)*this.g[i]; this.h[i]+=(tg-this.h[i])*(tg>this.h[i]?.75:.13+.45*L); this.b[i].style.height=(mn+H*Math.min(1,this.h[i]))+'px' }
  }
}
let eqs=[]; let last=performance.now();
(function loop(now){ const dt=Math.min(50,now-last); last=now; eqs.forEach(e=>e.step(dt)); requestAnimationFrame(loop) })(last);


/* запись: зажать — говорить — отпустить; вверх — замочек; влево — отмена */
let recTok=0;
async function startRec(mode, into, silent){
  if (S.rec) return; if (!voiceOk()) return micUnavailable();
  // silent: запись включила боковая кнопка — в прототипе «речь» появится, когда выберете фразу в «Сказать»
  S.rec={mode, key:into==='prompt'?'pr':into==='try'?'dict':silent?null:(S.nextKey||'ask'), n:0, into: into||(S.draft?'append':'send'), t0:Date.now()}; const my=++recTok; render();
  while (S.rec && my===recTok){ await wait(320); if(!S.rec||my!==recTok) return; if(!S.rec.key) continue; const ph=PHR[S.rec.key]; if (S.rec.n<ph.w.length) S.rec.n++; S.keepScroll=true; render() }
}
function recText(){ const r=S.rec; if(!r||!r.key||!r.n) return ''; const ph=PHR[r.key]; return r.n>=ph.w.length ? ph.fin : (ph.w.slice(0,r.n).join(' ').replace(/^./,c=>c.toUpperCase())) }
async function finishRec(how){
  const r=S.rec; if(!r) return; const text=recText(); recTok++; S.rec=null;
  if (r.into==='prompt'){ const m=modeById(S.editMode); if(how!=='cancel' && text){ m.prompt=(m.prompt.trim()+' '+text).trim(); trace('Надиктовано локально (GigaAM) и дописано в конец промпта — тот же компонент, что в листе.','p') } S.keepScroll=true; return render() }
  if (r.into==='try'){ if(how!=='cancel' && text){ S.tryText=((S.tryText||'')+' '+text).trim(); trace('Проба в первом запуске: надиктовано локально и дописано в поле, никуда не отправлено.','p') } return render() }
  if (how==='cancel' || !text){ trace(how==='cancel'?'Отменено жестом влево или крестиком — ничего не ушло.':'Пусто — ничего не отправлено.'); return render() }
  if (S.screen==='cam2' && S.cam){ trace('Отпустил микрофон на камере — снимок и вопрос уходят вместе одним сообщением.','p'); return shotGo(text) }
  if (S.ov){ S.ov.live=`<span class="st p">Распознаю</span>${esc(text)}`; S.ov.liveQ=false } render(); await wait(380);
  if (how==='release') how='send';   // отпустил — отправить; если в поле был текст, он уходит вместе с голосом
  const full = r.into==='append' ? (S.draft+' '+text) : text; S.draft=''; S.draftVoice=false;
  trace(r.mode==='hold'?'Отпустил — отправлено (push-to-talk, как в Писаре). Чистовик GigaAM уже с пунктуацией.':'«Отправить» из замочка — отправлено.','p');
  return deliver(full,'голос');
}
function micUnavailable(){
  if (S.w.mic==='ask'){ if(S.ov){ S.ov.live=`<span class="st">Нужен доступ к микрофону</span><span style="font-size:14.5px;font-weight:400;color:var(--soft)">Спросит приложение — лист на секунду развернётся.</span><br><button class="recbtn" data-act="permmic" style="margin-top:10px;background:var(--ink);color:#fff">Разрешить</button>`; return render() } S.dialog={ic:'🎙',title:'Разрешить приложению «Вахтёр» записывать аудио?',opts:[['При использовании приложения','mic_ok'],['Только в этот раз','mic_ok'],['Не разрешать','mic_no','sec2']]}; return render() }
  const why = S.w.model==='loading'?`распознавание ещё качается (${S.modelPct} %)`:S.w.model!=='ready'?'распознавание не скачано':S.w.mic==='denied'?'нет доступа к микрофону':'микрофон занят звонком';
  trace(`Микрофон недоступен: ${why}. Поле ввода работает.`,'e'); if(S.ov){ S.ov.live=`<span class="st e">Голос недоступен</span><span style="font-size:14.5px;font-weight:400;color:var(--soft)">${why[0].toUpperCase()+why.slice(1)}.</span>${S.w.mic==='denied'?'<br><button class="recbtn" data-act="sysmic" style="margin-top:10px;background:var(--ink);color:#fff">Открыть настройки</button>':''}` } render();
}
let iconT=null, iconLong=false;
document.addEventListener('pointerdown',e=>{ const ic=e.target.closest('[data-open="app"]'); if(!ic) return; iconLong=false; iconT=setTimeout(()=>{ iconLong=true; S.shortcuts=true; render() },500) });
document.addEventListener('pointerup',()=>{ clearTimeout(iconT) });
document.addEventListener('click',e=>{ if(iconLong && e.target.closest('[data-open="app"]')){ e.stopImmediatePropagation(); iconLong=false } else if(S.shortcuts && !e.target.closest('.sc-pop')){ S.shortcuts=false; render() } }, true);
let sw=null;
document.addEventListener('pointerdown',e=>{ const c=e.target.closest('[data-swipe]'); if(!c) { sw=null; return } sw={x:e.clientX,y:e.clientY} });
document.addEventListener('pointerup',e=>{ if(!sw) return; const dx=e.clientX-sw.x, dy=e.clientY-sw.y; sw=null; if(Math.abs(dx)<50||Math.abs(dy)>40) return; const am=activeModes(); if(am.length<2) return;
  const i=am.findIndex(m=>m.id===S.mode); const n=am[(i+(dx<0?1:-1)+am.length)%am.length]; setMode(n.id,'Свайп по вкладкам режимов'); sw=false; S.keepScroll=true; render(); setTimeout(()=>{ if(sw===false) sw=null },0) });
/* запись без рук: тап по кругу — отправить, потянуть влево — отмена */
let pl=null;
document.addEventListener('pointerdown',e=>{ const b=e.target.closest('[data-recsend]'); if(!b) return; e.preventDefault(); pl={x:e.clientX,t:Date.now()} });
document.addEventListener('pointermove',e=>{ if(!pl||!S.rec) return; const k=$('#dev').getBoundingClientRect().width/W; const dx=Math.max(0,(pl.x-e.clientX)/k); S.rec.lg=dx;
  const mw=$('#scr .micwrap'); if(mw) mw.style.transform=`translateX(${-Math.min(dx,70)}px)`; const sl=$('#scr .slide'); if(sl) sl.style.opacity=Math.max(.25,1-dx/70);
  if (dx>70){ pl=null; finishRec('cancel') } });
const lockedUp=()=>{ if(!pl) return; const moved=(S.rec&&S.rec.lg)||0; pl=null; if(!S.rec) return; if(moved<12) return finishRec('send'); S.rec.lg=0; S.keepScroll=true; render() };
document.addEventListener('pointerup',lockedUp); document.addEventListener('pointercancel',lockedUp);
let pd=null;
document.addEventListener('pointerdown',e=>{ const m=e.target.closest('[data-mic]'); if(!m) return; e.preventDefault(); if(S.rec&&S.rec.mode==='locked') return; pd={x:e.clientX,y:e.clientY,t:Date.now()}; startRec('hold', m.dataset.into) });
document.addEventListener('dragstart',e=>{ if(e.target.closest&&e.target.closest('[data-mic]')) e.preventDefault() });
document.addEventListener('pointermove',e=>{ if(!pd||!S.rec||S.rec.mode!=='hold') return; const k=$('#dev').getBoundingClientRect().width/W; const dy=(pd.y-e.clientY)/k, dx=(pd.x-e.clientX)/k;
  if (!pd.axis && Math.hypot(dx,dy)>8) pd.axis = Math.abs(dy)>=Math.abs(dx) ? 'y' : 'x';   // направление выбирается один раз, как в Telegram
  S.rec.g={x:pd.axis==='x'?Math.max(0,dx):0, y:pd.axis==='y'?Math.max(0,dy):0};
  const mw=$('#scr .micwrap'); if(mw) mw.style.transform=`translate(${-Math.min(S.rec.g.x,70)}px,${-Math.min(S.rec.g.y,45)}px)`;
  const lc=$('#scr .lockcue'); if(lc){ lc.style.setProperty('--ly',Math.min(S.rec.g.y,45)+'px'); lc.classList.toggle('near',S.rec.g.y>22) }
  const sl=$('#scr .slide'); if(sl) sl.style.opacity=Math.max(.25,1-S.rec.g.x/70);
  if (pd.axis==='y' && dy>45){ S.rec.mode='locked'; pd=null; trace('Потянул вверх — замочек: запись идёт без пальца. Круг микрофона стал «Отправить», влево — отмена.','p'); S.keepScroll=true; render() }
  else if (pd.axis==='x' && dx>70){ pd=null; finishRec('cancel') } });
function micRelease(){ if(!pd) return; const dt=Date.now()-pd.t; pd=null; if(!S.rec||S.rec.mode!=='hold') return;
  if (dt<260){ S.rec.mode='locked'; trace('Короткое нажатие — сразу запись без рук (замочек).','p'); S.keepScroll=true; return render() }
  finishRec('release') }
document.addEventListener('pointerup',micRelease); document.addEventListener('pointercancel',micRelease); window.addEventListener('blur',micRelease);
async function quickSay(k){ // имитация «зажал — сказал — отпустил» для кнопок панели
  S.nextKey=k; if (S.rec){ S.rec.key=k; S.rec.n=0; render(); if (S.rec.mode==='locked' && S.rec.into!=='prompt'){ const my=recTok; await wait(320*PHR[k].w.length+400); if (S.rec && my===recTok){ trace('Сказал — и нажал «Отправить» на круге (в прототипе нажатие сделано за вас).','p'); finishRec('send') } } return }
  if (!voiceOk()){ trace('Голос недоступен — фраза введена как с клавиатуры.','e'); return deliver(PHR[k].fin,'клавиатура') }
  startRec('hold'); const my=recTok; await wait(320*PHR[k].w.length+250); if (S.rec && my===recTok && S.rec.mode==='hold') finishRec('release') }
