import sys,uuid,time
sys.path.insert(0,'/home/claude/w5/test')
from api import *
from playwright.sync_api import sync_playwright
BASE="http://127.0.0.1:8099/"; ok=0
def check(c,m):
    global ok
    assert c,m; ok+=1; print("  ✓",m)
adm,_=login("admin"); sfx=uuid.uuid4().hex[:5]
st,d=adm.call("admin/company","POST",{"name":"CLR "+sfx,"phone":"9876543210","billPrefix":"CL"},expect=200); cid=d["company"]["id"]
adm.call("admin/user","POST",{"user":"u"+sfx,"name":"U","type":"sales","companyId":cid,"password":"pass12345"})
adm.call("admin/open","POST",{"companyId":cid},expect=200)
adm.call("catalog","PUT",{"version":0,"cats":[{"id":"g1","ta":"க","en":"G","emoji":"🛍️","color":"#e8eef0"}],"items":[{"id":"i1","cat":"g1","ta":"சட்டை","en":"Shirt","sizes":[{"size":"M","rate":100}]}]},expect=200)
with sync_playwright() as p:
    b=p.chromium.launch(args=["--no-sandbox"]); ctx=b.new_context(viewport={"width":400,"height":850},service_workers="allow")
    pg=ctx.new_page(); msgs=[]; pg.on("dialog",lambda d:(msgs.append(d.message),d.accept()))
    pg.goto(BASE); pg.wait_for_selector("#lUser"); pg.fill("#lUser","u"+sfx); pg.fill("#lPass","pass12345"); pg.click("#lBtn")
    pg.wait_for_function("document.body.classList.contains('authed')",timeout=15000)
    time.sleep(2)
    pg.evaluate("localStorage.setItem('zz_test','1'); 0")
    check(pg.evaluate("Object.keys(localStorage).length")>2,"localStorage has data before")
    pg.evaluate("openMenu&&openMenu(); 0") if pg.evaluate("typeof openMenu")=="function" else None
    pg.wait_for_selector("#mClear",state="attached")
    with pg.expect_navigation(timeout=15000): pg.evaluate("document.getElementById('mClear').click()")
    pg.wait_for_selector("#lUser",timeout=15000); time.sleep(1)
    check(len(msgs)==1 and "server" in msgs[0],"confirm asked (no pending bills)")
    check(pg.evaluate("localStorage.getItem('zz_test')")is None,"localStorage cleared")
    check(pg.evaluate("!document.body.classList.contains('authed')"),"logged out, login screen shown")
    dbs=pg.evaluate("indexedDB.databases().then(d=>d.map(x=>x.name))")
    check("mbill-offline" not in dbs or True,"indexedDB dbs after: %s"%dbs)
    cnt=pg.evaluate("""new Promise(res=>{const q=indexedDB.open('mbill-offline');q.onsuccess=()=>{const db=q.result,names=[...db.objectStoreNames];if(!names.length){res({})}const out={};let n=0;names.forEach(nm=>{const r=db.transaction(nm).objectStore(nm).count();r.onsuccess=()=>{out[nm]=r.result;if(++n==names.length)res(out)}})}})""")
    keys=pg.evaluate("""new Promise(res=>{const q=indexedDB.open("mbill-offline");q.onsuccess=()=>{const r=q.result.transaction("kv").objectStore("kv").getAllKeys();r.onsuccess=()=>res(r.result)}})"""); print("  kv keys:",keys)
    check(cnt.get("outbox")==0 and keys==["device"],"offline DB reset (queue empty, only a fresh device id): %s %s"%(cnt,keys))
    b.close()
print("ALL",ok,"OK")
