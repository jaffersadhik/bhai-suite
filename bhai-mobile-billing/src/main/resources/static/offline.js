/* MBill offline engine — IndexedDB cache + outbox queue + sync. Loaded before the main script; uses its globals only inside functions.
   What works without internet: billing, cancel bill (from cart / from an earlier bill), bill history, catalog + rates, WhatsApp text.
   Bills / cancels made offline wait in the outbox and are sent (idempotent, client_id) when the network is back. */
const OFF=(()=>{
  const S={ok:true, online:navigator.onLine!==false, box:[], dev:"", busy:false, last:0, needLogin:false, c:null, catPending:null, tmp:0, timer:null};
  const lsGet=k=>{ try{ return localStorage.getItem(k); }catch(e){ return null; } };
  const lsSet=(k,v)=>{ try{ localStorage.setItem(k,v); }catch(e){} };

  /* ---------- IndexedDB (kv + outbox) ---------- */
  let dbp=null;
  function idb(){
    if(dbp) return dbp;
    dbp=new Promise((res,rej)=>{
      if(!window.indexedDB){ rej(new Error("no indexedDB")); return; }
      const q=indexedDB.open("mbill-offline",1);
      q.onupgradeneeded=()=>{ const d=q.result; d.createObjectStore("kv"); d.createObjectStore("outbox",{keyPath:"cid"}); };
      q.onsuccess=()=>res(q.result); q.onerror=()=>rej(q.error); q.onblocked=()=>rej(new Error("blocked"));
    });
    return dbp;
  }
  function tx(store,mode,fn){
    return idb().then(d=>new Promise((res,rej)=>{
      const t=d.transaction(store,mode), s=t.objectStore(store); let out; const r=fn(s);
      if(r) r.onsuccess=()=>{ out=r.result; };
      t.oncomplete=()=>res(out); t.onerror=t.onabort=()=>rej(t.error||new Error("idb"));
    }));
  }
  const kvGet=k=>tx("kv","readonly",s=>s.get(k));
  const kvSet=(k,v)=>tx("kv","readwrite",s=>s.put(v,k));
  const kvDel=k=>tx("kv","readwrite",s=>s.delete(k));
  const obAll=()=>tx("outbox","readonly",s=>s.getAll());
  const obPut=e=>tx("outbox","readwrite",s=>s.put(e));
  const obDel=cid=>tx("outbox","readwrite",s=>s.delete(cid));
  const safe=p=>p.catch(()=>{ S.ok=false; });

  const rnd=()=>{ try{ const a=new Uint8Array(8); crypto.getRandomValues(a); return Array.from(a,x=>x.toString(16).padStart(2,"0")).join(""); }catch(e){ return Math.random().toString(16).slice(2,10)+Date.now().toString(16); } };
  const newCid=p=>(p||"c")+Date.now().toString(36)+rnd().slice(0,8);   // client id: <= 40 chars, A-Za-z0-9

  async function init(){
    try{
      let d=await kvGet("device"); if(!d) d=lsGet("bmb_device");
      if(!d){ d="d"+rnd(); }
      S.dev=d; lsSet("bmb_device",d); await kvSet("device",d);
      S.box=(await obAll()).sort((a,b)=>a.t-b.t);
    }catch(e){ S.ok=false; S.dev=lsGet("bmb_device")||("d"+rnd()); lsSet("bmb_device",S.dev); }
    paint();
  }

  /* ---------- per-company cache (in memory + IndexedDB) ---------- */
  const key=(cid,n)=>"c:"+cid+":"+n;
  async function openCompany(cid){
    S.c={id:cid,data:null,bills:[],cancels:[],blocks:{prefix:"",bills:[],cancels:[],tb:0,tc:0}};
    try{
      S.c.data=await kvGet(key(cid,"data"))||null;
      S.c.bills=await kvGet(key(cid,"bills"))||[];
      S.c.cancels=await kvGet(key(cid,"cancels"))||[];
      const b=await kvGet(key(cid,"blocks")); if(b) S.c.blocks=b;
    }catch(e){ S.ok=false; }
    paint();
  }
  const saveBlocks=()=>S.c&&safe(kvSet(key(S.c.id,"blocks"),S.c.blocks));
  const saveBills=()=>S.c&&safe(kvSet(key(S.c.id,"bills"),S.c.bills));
  const saveCancels=()=>S.c&&safe(kvSet(key(S.c.id,"cancels"),S.c.cancels));
  function saveData(r){   // {company,payModes,catalog}
    if(!S.c||!r||!r.catalog) return;
    S.c.data={company:r.company,payModes:r.payModes||[],catalog:r.catalog,at:Date.now()};
    safe(kvSet(key(S.c.id,"data"),S.c.data));
  }
  const getData=()=>S.c&&S.c.data;
  const saveSess=s=>{ if(s&&s.user) safe(kvSet("sess",{user:s.user,company:s.company||null,branch:s.branch||null,defaultPassword:!!s.defaultPassword,at:Date.now()})); };
  const getSess=()=>kvGet("sess").catch(()=>null);
  const dropSess=()=>safe(kvDel("sess"));

  /* ---------- bill / cancel numbers: blocks reserved on the server, tmp numbers when they run out ---------- */
  const prefix=()=>(S.c&&S.c.blocks.prefix)||((typeof company!=="undefined"&&company&&company.billPrefix)||"INV");
  const pad=n=>String(n).padStart(4,"0");
  function left(kind){ const L=S.c?S.c.blocks[kind==="cancel"?"cancels":"bills"]:[]; return L.reduce((a,r)=>a+Math.max(0,r.to-r.next+1),0); }
  function takeNo(kind){
    const bl=S.c.blocks, L=bl[kind==="cancel"?"cancels":"bills"], p=prefix();
    for(const r of L){ if(r.next<=r.to){ const seq=r.next++; saveBlocks(); return {seq,no:p+"-"+(kind==="cancel"?"C":"")+pad(seq),real:true}; } }
    const k=kind==="cancel"?"tc":"tb"; bl[k]=(bl[k]||0)+1; saveBlocks();
    return {seq:0,no:p+"-"+(kind==="cancel"?"CT":"T")+pad(bl[k]),real:false};
  }
  async function reserve(){
    if(!S.c||!S.online||!S.dev) return;
    const nb=left("bill")<=15?40:0, nc=left("cancel")<=3?10:0; if(!nb&&!nc) return;
    try{
      const r=await api("offline/reserve","POST",{deviceId:S.dev,bills:nb,cancels:nc},{quiet:true,timeout:15000});
      const bl=S.c.blocks; if(r.prefix) bl.prefix=r.prefix;
      if(r.bills) bl.bills.push({from:r.bills[0],to:r.bills[1],next:r.bills[0]});
      if(r.cancels) bl.cancels.push({from:r.cancels[0],to:r.cancels[1],next:r.cancels[0]});
      bl.bills=bl.bills.filter(x=>x.next<=x.to); bl.cancels=bl.cancels.filter(x=>x.next<=x.to);
      saveBlocks();
    }catch(e){ /* try again at next sync */ }
  }

  /* ---------- network state ---------- */
  function net(ok){
    const was=S.online; S.online=!!ok&&navigator.onLine!==false;
    if(ok) S.needLogin=false;
    if(was!==S.online){ paint(); if(S.online) setTimeout(()=>sync(),400); }
  }
  addEventListener("online",()=>{ S.online=true; paint(); setTimeout(()=>sync({force:true}),300); });
  addEventListener("offline",()=>{ S.online=false; paint(); });
  document.addEventListener("visibilitychange",()=>{ if(!document.hidden) sync(); });

  /* ---------- outbox ---------- */
  const mine=e=>typeof me!=="undefined"&&me&&typeof company!=="undefined"&&company&&e.co===company.id&&e.user===me.user;
  const pend=()=>S.box.filter(e=>e.state!=="error"&&mine(e));
  const errs=()=>S.box.filter(e=>e.state==="error"&&mine(e));
  async function enqueue(kind,body,view){
    const e={cid:body.clientId,kind,co:company.id,user:me.user,t:Date.now(),no:view.no,body,view,state:"queued",tries:0,err:""};
    S.box.push(e); await safe(obPut(e)); paint(); return e;
  }
  async function remove(cid){ S.box=S.box.filter(e=>e.cid!==cid); await safe(obDel(cid)); paint(); }
  const saveEntry=e=>safe(obPut(e));

  /* a bill got another number on the server (tmp number, or reserved block was not accepted) */
  function renamed(oldNo,newNo){
    if(!oldNo||oldNo===newNo) return;
    S.box.forEach(e=>{ if(e.kind==="cancel"&&e.body.refBill===oldNo){ e.body.refBill=newNo; e.view.refBill=newNo; saveEntry(e); } });
    if(S.c) S.c.cancels.forEach(c=>{ if(c.refBill===oldNo) c.refBill=newNo; });
    try{ const lg=JSON.parse(lsGet("bmb_renamed")||"[]"); lg.push({o:oldNo,n:newNo,at:new Date().toISOString()}); lsSet("bmb_renamed",JSON.stringify(lg.slice(-30))); }catch(e){}
  }
  function applied(e,r){
    const x=e.kind==="bill"?r.bill:r.cancel; if(!x) return;
    if(x.no!==e.no){ renamed(e.no,x.no); toast((e.kind==="bill"?"பில் ":"ரத்து பில் ")+e.no+" → "+x.no+" (server எண்)"); }
    if(S.c&&S.c.id===e.co){
      if(e.kind==="bill"){ S.c.bills=[x].concat(S.c.bills.filter(b=>b.clientId!==e.cid&&b.no!==x.no)); saveBills(); }
      else { S.c.cancels=[x].concat(S.c.cancels.filter(b=>b.clientId!==e.cid&&b.no!==x.no)); saveCancels(); }
    }
  }
  async function push(){
    let n=0, stop=false;
    for(const e of S.box.slice()){
      if(stop) break;
      if(e.state==="error"||!mine(e)) continue;
      try{
        const r=await api(e.kind==="bill"?"bills":"cancel-bills","POST",e.body,{quiet:true,timeout:25000});
        applied(e,r); await remove(e.cid); n++;
        if(typeof stockData!=="undefined"){ stockData=null; srData=null; repData=null; }
      }catch(er){
        if(er.offline){ stop=true; }
        else if(er.status===401){ S.needLogin=true; stop=true; }
        else if(er.status>=400&&er.status<500){ e.state="error"; e.err=er.message||("Error "+er.status); await saveEntry(e); }
        else { e.tries=(e.tries||0)+1; e.err=er.message||""; await saveEntry(e); stop=true; }
      }
    }
    return n;
  }

  /* ---------- pull: company, payment modes, catalog, bills + cancels of the last 62 days ---------- */
  async function pull(){
    if(!S.c) return;
    const cv=(S.c.data&&S.c.data.catalog&&S.c.data.catalog.version)||-1;
    const r=await api("sync?cv="+cv+"&days=62","GET",undefined,{quiet:true,timeout:30000});
    const d=S.c.data||{};
    const cat=r.catalog||d.catalog;
    S.c.data={company:r.company,payModes:r.payModes||[],catalog:cat,at:Date.now()};
    safe(kvSet(key(S.c.id,"data"),S.c.data));
    const q=new Set(S.box.map(e=>e.cid));
    S.c.bills=(r.bills||[]).filter(b=>!b.clientId||!q.has(b.clientId)); saveBills();
    S.c.cancels=(r.cancels||[]).filter(b=>!b.clientId||!q.has(b.clientId)); saveCancels();
    S.last=Date.now(); lsSet("bmb_lastsync",String(S.last));
    if(r.catalog) liveCatalog(S.c.data); else { if(typeof company!=="undefined"&&company&&r.company) company=r.company; }
    if(typeof payModes!=="undefined") payModes=S.c.data.payModes;
  }
  /* new price list: used straight away when the cart is empty, otherwise after the bill */
  function liveCatalog(d){
    if(typeof cart==="undefined") return;
    const busyCart=Object.keys(cart).length||Object.keys(customs).length;
    if(busyCart||(typeof catBusy!=="undefined"&&catBusy)){ S.catPending=d; return; }
    S.catPending=null; company=d.company; catalog=d.catalog; payModes=d.payModes||[]; buildCatalog();
    if(typeof renderAll==="function"&&me) renderAll();
  }
  function applyPending(){ if(S.catPending) liveCatalog(S.catPending); }

  async function sync(o){
    o=o||{};
    if(S.busy||typeof me==="undefined"||!me||!company||isOwnerOnly()) return;
    if(!S.online&&!o.force) return;
    S.busy=true; paint();
    try{
      if(!S.online){ await api("me","GET",undefined,{quiet:true,timeout:6000}); }   // probe: success flips S.online
      if(o.retry) S.box.forEach(e=>{ if(e.state==="error"&&mine(e)){ e.state="queued"; e.tries=0; saveEntry(e); } });
      await reserve();
      const n=await push();
      if(!pend().length&&(o.force||n||!S.last||Date.now()-S.last>300000)){ await pull().catch(er=>{ if(er.status===401) S.needLogin=true; }); await reserve(); }
    }catch(e){ /* offline again */ }
    finally{ S.busy=false; paint(); const sh=document.getElementById("syncBox"); if(sh) renderSync(); }
  }
  const isOwnerOnly=()=>typeof isOwner==="function"&&isOwner();
  function start(){
    clearInterval(S.timer);
    S.timer=setInterval(()=>{ if(!document.hidden) sync({force:!S.online||pend().length>0}); },30000);
    setTimeout(()=>sync({force:true}),600);
  }

  /* ---------- helpers for the screens ---------- */
  const todayStr=()=>new Date(Date.now()+330*60000).toISOString().slice(0,10);
  const dayOf=iso=>{ const d=new Date(iso); return isNaN(d)?"":new Date(d.getTime()+330*60000).toISOString().slice(0,10); };
  /* offline bill history: waiting bills first, then the cached ones */
  function billsView(){
    const q=S.box.filter(e=>e.kind==="bill"&&mine(e)).map(e=>e.view).reverse();
    const list=q.concat(S.c?S.c.bills:[]).slice(0,100);
    const t=todayStr(); let cnt=0,tot=0;
    q.concat(S.c?S.c.bills:[]).forEach(b=>{ if(dayOf(b.at)===t){ cnt++; tot+=Number(b.total)||0; } });
    return {bills:list,today:{count:cnt,total:Math.round(tot*100)/100}};
  }
  function cancelsView(){
    const q=S.box.filter(e=>e.kind==="cancel"&&mine(e)).map(e=>e.view).reverse();
    const list=q.concat(S.c?S.c.cancels:[]).slice(0,200);
    const t=todayStr(); let cnt=0,tot=0;
    list.forEach(b=>{ if(dayOf(b.at)===t){ cnt++; tot+=Number(b.total)||0; } });
    return {cancels:list,today:{count:cnt,total:Math.round(tot*100)/100},month:{count:cnt,total:Math.round(tot*100)/100}};
  }
  const queuedViews=kind=>S.box.filter(e=>e.kind===kind&&mine(e)).map(e=>e.view).reverse();
  const allBills=()=>S.box.filter(e=>e.kind==="bill"&&mine(e)).map(e=>e.view).concat(S.c?S.c.bills:[]);
  const allCancels=()=>S.box.filter(e=>e.kind==="cancel"&&mine(e)).map(e=>e.view).concat(S.c?S.c.cancels:[]);
  /* same maths as the server's cancelView: left = sold - cancelled per item|size, shared over duplicate lines */
  const lkey=l=>(l.id||("c:"+l.name))+"|"+(l.size||"");
  function cancelView(b){
    const pool={};
    (b.lines||[]).forEach(l=>{ const k=lkey(l); pool[k]=(pool[k]||0)+(Number(l.qty)||0); });
    allCancels().forEach(c=>{ if(c.refBill===b.no) (c.lines||[]).forEach(l=>{ const k=lkey(l); if(k in pool) pool[k]-=(Number(l.qty)||0); }); });
    let lp=0;
    const ls=(b.lines||[]).map(l=>{
      const k=lkey(l), lf=Math.max(0,Math.min(pool[k]||0,Number(l.qty)||0)); if(k in pool) pool[k]=Math.max(0,(pool[k]||0)-lf);
      lp+=lf;
      const o={id:l.id||null,name:l.name,en:l.en||"",size:l.size||"",qty:l.qty,rate:l.rate,left:lf,cancelled:(Number(l.qty)||0)-lf};
      if(l.wt!=null) o.wt=l.wt; return o;
    });
    return {id:b.id||null,no:b.no,at:b.at,customer:b.customer||"",phone:b.phone||"",by:b.by||"",branch:b.branch||"",pieces:b.pieces,total:b.total,leftPieces:lp,lines:ls,kind:"retail",gstPct:0,partyId:null};
  }
  function billsByPhone(phone,months){
    const from=new Date(); from.setDate(1); from.setMonth(from.getMonth()-(months-1)); from.setHours(0,0,0,0);
    const B=allBills().filter(b=>b.phone===phone&&new Date(b.at)>=from).sort((a,b)=>new Date(b.at)-new Date(a.at)).slice(0,60).map(cancelView);
    return {phone,from:from.toISOString(),months,bills:B,offline:true};
  }
  function billByNo(no){
    const b=allBills().find(x=>String(x.no).toUpperCase()===no);
    if(!b) return null; return cancelView(b);
  }

  /* ---------- status chip / banner / sync sheet ---------- */
  function chipHtml(){
    if(typeof me==="undefined"||!me||isOwnerOnly()) return "";
    const p=pend().length, er=errs().length;
    let ic,tx,cl;
    if(!S.online){ ic="📴"; tx=p?String(p):"Offline"; cl="off"; }
    else if(S.needLogin){ ic="🔑"; tx="login"; cl="warn"; }
    else if(er){ ic="⚠️"; tx=String(er); cl="warn"; }
    else if(S.busy||p){ ic="🔄"; tx=p?String(p):""; cl="sync"; }
    else { ic="🟢"; tx=""; cl="on"; }
    return `<button class="net ${cl}" id="offChip" aria-label="Sync">${ic}${tx?`<b>${tx}</b>`:""}</button>`;
  }
  function paint(){
    const chip=document.getElementById("offChip"); const h=chipHtml();
    if(chip){ if(h){ chip.outerHTML=h; } else chip.remove(); }
    const bar=document.getElementById("offBar"); if(!bar) return;
    const show=typeof me!=="undefined"&&me&&!S.online&&!isOwnerOnly(), p=pend().length;
    bar.classList.toggle("on",!!show);
    if(show) bar.innerHTML=`📴 இணையம் இல்லை — பில்லிங் தொடரலாம். ${p?`<b>${p}</b> பில்/ரத்து காத்திருக்கிறது — இணையம் வந்ததும் தானாக அனுப்பப்படும்.`:"பில்கள் இந்த போனில் சேமிக்கப்படும்."}`;
  }
  const ago=t=>{ if(!t) return "—"; const m=Math.round((Date.now()-t)/60000); return m<1?"இப்போது":m<60?m+" நிமிடம் முன்":Math.round(m/60)+" மணி முன்"; };
  function renderSync(){
    const el=document.getElementById("syncBox"); if(!el) return;
    const L=S.box.filter(mine).sort((a,b)=>a.t-b.t);
    el.innerHTML=`<div class="sum"><div><span>நிலை</span><span>${S.online?"🟢 Online":"📴 Offline"}${S.busy?" · 🔄":""}</span></div>
      <div><span>காத்திருப்பவை</span><span>${pend().length}</span></div><div><span>பிழை</span><span>${errs().length}</span></div>
      <div><span>கடைசி sync</span><span>${esc(ago(S.last||+lsGet("bmb_lastsync")||0))}</span></div>
      <div><span>ஒதுக்கிய எண்கள்</span><span>${left("bill")} பில் · ${left("cancel")} ரத்து</span></div></div>
      ${S.needLogin?`<p class="hint" style="color:var(--bad);font-weight:700">🔑 Session முடிந்துள்ளது — Logout செய்து மீண்டும் login செய்யவும் (காத்திருக்கும் பில்கள் இழக்கப்படாது)</p>`:""}
      <div class="box">${L.length?L.map(e=>`<div class="bh"><div class="r1"><b>${e.kind==="bill"?"🧾":"✖"} ${esc(e.no)}</b><b class="amt">${inr(e.view.total)}</b></div>
        <div class="meta">${esc(fmtDate(e.view.at))} · ${e.view.pieces} pcs${e.view.customer?" · "+esc(e.view.customer):""} · ${e.state==="error"?`<span style="color:var(--bad);font-weight:700">⚠ ${esc(e.err||"பிழை")}</span>`:"⏳ காத்திருக்கிறது"}</div>
        ${e.state==="error"?`<div class="acts"><button class="mini" data-osretry="${esc(e.cid)}">மீண்டும்</button><button class="mini" data-osdel="${esc(e.cid)}">நீக்கு</button><button class="mini" data-oscopy="${esc(e.cid)}">Copy</button></div>`:""}</div>`).join("")
        :`<p class="sub" style="margin:6px 0">✓ எல்லாம் server-க்கு அனுப்பப்பட்டது</p>`}</div>
      <button class="cta" id="osNow">🔄 இப்போது Sync செய்</button>`;
  }
  function openSync(){
    $("#sheetBody").innerHTML=`<h2>🔄 Sync / Offline</h2><p class="sub">${esc(company?company.name:"")} · சாதனம் ${esc(S.dev.slice(0,7))}</p><div id="syncBox"></div>
      <p class="hint">இணையம் இல்லாதபோது போடப்படும் பில்கள் இங்கே காத்திருக்கும்; இணையம் வந்ததும் தானாக server-க்கு போகும். Logout செய்தாலும் அவை அழியாது.</p>
      <button class="cta ghost" data-close>மூடு</button>`;
    renderSync(); showSheet();
  }
  document.addEventListener("click",e=>{
    const t=e.target.closest&&e.target.closest("button"); if(!t) return;
    if(t.id==="offChip"||t.id==="mSync"){ e.stopPropagation(); openSync(); return; }
    if(t.id==="osNow"){ sync({force:true,retry:true}); return; }
    const d=t.dataset;
    if(d.osretry){ const x=S.box.find(z=>z.cid===d.osretry); if(x){ x.state="queued"; x.tries=0; saveEntry(x); sync({force:true}); renderSync(); } return; }
    if(d.osdel){ if(confirm("இந்த பில்லை நீக்கவா? (server-க்கு அனுப்பப்படாது)")) remove(d.osdel).then(renderSync); return; }
    if(d.oscopy){ const x=S.box.find(z=>z.cid===d.oscopy); if(x){ const txt=JSON.stringify(x.body,null,1); try{ navigator.clipboard.writeText(txt); toast("Copy ஆனது"); }catch(er){} } return; }
  },true);

  return {S,init,openCompany,saveData,getData,saveSess,getSess,dropSess,newCid,takeNo,left,net,enqueue,remove,pend,errs,sync,start,applyPending,
    billsView,cancelsView,queuedViews,billsByPhone,billByNo,chipHtml,paint,openSync,applied,device:()=>S.dev,get online(){ return S.online; },get ok(){ return S.ok; }};
})();
OFF.init();
