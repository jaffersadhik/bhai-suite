/* MBill service worker — keeps the app shell on the phone so it opens (and works) without internet.
   /api/* is never touched here: offline data lives in IndexedDB (offline.js). Bump VER when the shell file list changes. */
const VER="mbill-shell-v9", RUNTIME="mbill-run-v1";
const SHELL=["./","index.html","offline.js","i18n.js","manifest.json","i18n/languages.json","i18n/en.json","i18n/labels.json","vendor/jspdf.umd.min.js","vendor/html2canvas.min.js","vendor/zxing.min.js","icons/icon-192.png","icons/icon-512.png"];
self.addEventListener("install",e=>{
  e.waitUntil(caches.open(VER).then(c=>Promise.all(SHELL.map(u=>c.add(new Request(u,{cache:"reload"})).catch(()=>{})))).then(()=>self.skipWaiting()));
});
self.addEventListener("activate",e=>{
  e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==VER&&k!==RUNTIME).map(k=>caches.delete(k)))).then(()=>self.clients.claim()));
});
const timeout=(p,ms)=>new Promise((res,rej)=>{ const t=setTimeout(()=>rej(new Error("timeout")),ms); p.then(v=>{clearTimeout(t);res(v);},er=>{clearTimeout(t);rej(er);}); });
const isShellPage=u=>{ const p=u.pathname, base=new URL("./",self.registration.scope).pathname; return p===base||p===base+"index.html"; };
self.addEventListener("fetch",e=>{
  const r=e.request; if(r.method!=="GET") return;
  const u=new URL(r.url);
  if(u.pathname.indexOf("/api/")>=0) return;                       // data: never cached here
  if(r.mode==="navigate"){
    if(!isShellPage(u)) return;                                    // shop.html etc.: normal network
    e.respondWith((async()=>{
      const c=await caches.open(VER);
      try{ const res=await timeout(fetch(r),4000); if(res&&res.ok){ c.put("index.html",res.clone()); } return res; }
      catch(er){ return (await c.match("index.html"))||(await c.match("./"))||Response.error(); }
    })());
    return;
  }
  const sameOrigin=u.origin===location.origin;
  const font=/fonts\.(googleapis|gstatic)\.com$/.test(u.hostname);
  if(!sameOrigin&&!font) return;
  e.respondWith((async()=>{                                         // stale-while-revalidate
    const c=await caches.open(sameOrigin&&SHELL.some(s=>u.pathname.endsWith("/"+s))?VER:RUNTIME);
    const hit=await c.match(r,{ignoreVary:true});
    const net=fetch(r).then(res=>{ if(res&&(res.ok||res.type==="opaque")) c.put(r,res.clone()); return res; }).catch(()=>null);
    if(hit){ e.waitUntil(net); return hit; }
    return (await net)||Response.error();
  })());
});
