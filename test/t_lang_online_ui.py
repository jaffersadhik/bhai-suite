import sys,uuid,time,re,subprocess,urllib.request
sys.path.insert(0,'/home/claude/w9/in/bhai-mobile-billing/test')
from api import *
from playwright.sync_api import sync_playwright
BASE="http://127.0.0.1:8099/"; ok=0
TA=re.compile("[஀-௿]")
def check(c,m):
    global ok
    assert c,m; ok+=1; print("  ✓",m)
def sql(q): return subprocess.run(["mysql","-ubmb","-pbmb123","bmb","-N","-e",q],capture_output=True,text=True).stdout.strip()
def hits(): return int(urllib.request.urlopen("http://127.0.0.1:8191/count").read())
sql("UPDATE users SET max_sessions=50, idle_minutes=0 WHERE username='admin'")
sfx=uuid.uuid4().hex[:5]; adm,_=login("admin")
st,d=adm.call("admin/company","POST",{"name":"OL "+sfx,"phone":"9876543210","billPrefix":"OL"},expect=200); cid=d["company"]["id"]
adm.call("admin/user","POST",{"user":"k"+sfx,"name":"Stk","type":"stock","companyId":cid,"password":"pass12345"})
adm.call("admin/open","POST",{"companyId":cid},expect=200)
# master data typed in Tamil only (no English name)
adm.call("catalog/import","POST",{"csv":"group,item,size,rate\nபழங்கள்,ஆப்பிள்,1 pc,10\nமளிகை,அரிசி,1 pc,60\n","replace":True},expect=200)
def body(pg): return pg.evaluate("document.body.innerText")
errs=[]
with sync_playwright() as p:
    b=p.chromium.launch(args=["--no-sandbox"]); ctx=b.new_context(viewport={"width":420,"height":900},service_workers="allow"); pg=ctx.new_page()
    pg.on("pageerror",lambda e:errs.append(str(e))); pg.on("dialog",lambda d:d.accept())
    pg.goto(BASE); pg.wait_for_selector("#lUser"); time.sleep(1)
    check(not TA.search(body(pg)),"English login page: no Tamil text at all")
    pg.fill("#lUser","k"+sfx); pg.fill("#lPass","pass12345"); pg.click("#lBtn")
    pg.wait_for_function("document.body.classList.contains('authed')",timeout=15000); pg.wait_for_function("ITEMS.length>0",timeout=15000); time.sleep(3)
    t=body(pg); print("   sample:",t[:160].replace("\n"," | "))
    print("   TAMIL LEFT:",set(re.findall(r"[^\n]*[\u0b80-\u0bff][^\n]*",t))); check(not TA.search(t),"English: item / group names typed in Tamil are shown in English, no Tamil left")
    check("Fruits" in t and "Grocery" in t,"translated group names: Fruits, Grocery")
    pg.click("button.tile >> nth=0"); time.sleep(2.5); t2=body(pg)
    check(("Apple" in t2 or "Rice" in t2) and not TA.search(t2),"item screen: item name translated, no Tamil")
    pg.evaluate("typeof goHome==='function' && goHome(); 0"); pg.evaluate("closeSheet(); 0")
    # open menu and a few screens
    pg.evaluate("typeof openMenu==='function' && openMenu(); 0"); time.sleep(1.5)
    check(not TA.search(body(pg)),"English menu: no Tamil")
    pg.evaluate("closeSheet(); 0")
    # Tamil: Tamil on top + English below
    pg.evaluate("I18N.set('ta'); 0"); time.sleep(2.5)
    h=pg.evaluate("document.body.innerHTML")
    check("ஆப்பிள்" in h and "<x-s" in h and "Apple" in pg.evaluate("[...document.querySelectorAll('x-s')].map(e=>e.textContent).join('|')"),"Tamil: Tamil name + English line below")
    # Arabic: RTL, Arabic primary (+ English line), no Tamil
    pg.evaluate("I18N.set('ar'); 0"); time.sleep(4)
    check(pg.evaluate("document.documentElement.dir")=="rtl" and pg.evaluate("document.documentElement.lang")=="ar","Arabic: dir=rtl, lang=ar")
    t=body(pg); print("   ar sample:",t[:200].replace("\n"," | "))
    check(not TA.search(t),"Arabic: no Tamil anywhere")
    check("ع" in t,"Arabic: texts translated (primary Arabic)")
    pg.screenshot(path="/tmp/ar.png")
    # back to English: LTR again
    pg.evaluate("I18N.set('en'); 0"); time.sleep(1.5)
    check(pg.evaluate("document.documentElement.dir")=="ltr" and not TA.search(body(pg)),"back to English: ltr, no Tamil")
    # cache: reload does not call the online translator again for the same texts
    h0=hits(); pg.reload(); pg.wait_for_function("ITEMS.length>0",timeout=15000); time.sleep(3)
    check(hits()-h0<=3,"reload re-uses cached translations (online hits +%d)"%(hits()-h0))
    check(not errs,"no JS errors "+str(errs[:2]))
    b.close()
print("PASS",ok)
