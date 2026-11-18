import sys,json,uuid,time
sys.path.insert(0,'/home/claude/w4/test')
from api import *
from playwright.sync_api import sync_playwright
BASE="http://127.0.0.1:8099/"
ok=0
def check(c,msg):
    global ok
    assert c,msg; ok+=1; print("  ✓",msg)
adm,_=login("admin"); sfx=uuid.uuid4().hex[:5]
st,d=adm.call("admin/company","POST",{"name":"Off Co "+sfx,"phone":"9876543210","billPrefix":"OFF","rateChange":False,"orderNoStock":False},expect=200); cid=d["company"]["id"]
adm.call("admin/user","POST",{"user":"u"+sfx,"name":"Sales UI","type":"sales","companyId":cid,"password":"pass12345"})
cat={"version":0,"cats":[{"id":"g1","ta":"குரூப்","en":"Grp","emoji":"🛍️","color":"#e8eef0"}],
 "items":[{"id":"i1","cat":"g1","ta":"சட்டை","en":"Shirt","sizes":[{"size":"M","rate":100},{"size":"L","rate":120}]},
          {"id":"w1","cat":"g1","ta":"பாதாம்","en":"Almond","by":"weight","unit":"kg","sizes":[{"size":"1 kg","rate":800}]}]}
adm.call("admin/open","POST",{"companyId":cid},expect=200); adm.call("catalog","PUT",cat,expect=200)
# give stock
st,sk=adm.call("stock",expect=200)
sales,_=login("u"+sfx,"pass12345")
def stock_of(key="i1|M"):
    st,s=adm.call("stock",expect=200); return s
errs=[]
with sync_playwright() as p:
    b=p.chromium.launch(args=["--no-sandbox"])
    ctx=b.new_context(viewport={"width":400,"height":800},service_workers="allow")
    pg=ctx.new_page(); pg.on("pageerror",lambda e:errs.append(str(e))); pg.on("dialog",lambda d:d.accept())
    pg.goto(BASE); pg.wait_for_selector("#lUser")
    pg.fill("#lUser","u"+sfx); pg.fill("#lPass","pass12345"); pg.click("#lBtn")
    pg.wait_for_function("document.body.classList.contains('authed')",timeout=15000)
    pg.wait_for_function("navigator.serviceWorker.controller||navigator.serviceWorker.ready.then(()=>true)",timeout=15000)
    pg.evaluate("navigator.serviceWorker.ready")
    pg.wait_for_function("OFF.left('bill')>0 && OFF.S.last>0",timeout=20000); check(True,"logged in; SW ready; numbers reserved + first pull done")
    # reload once online so the SW controls the page and everything is precached
    pg.reload(); pg.wait_for_function("document.body.classList.contains('authed')",timeout=15000); time.sleep(1)
    check(pg.evaluate("!!navigator.serviceWorker.controller"),"service worker controls the page")
    # ---- go offline, reload: app must open from cache
    ctx.set_offline(True)
    pg.reload(); pg.wait_for_function("document.body.classList.contains('authed')",timeout=20000)
    check(pg.evaluate("document.getElementById('offBar').classList.contains('on')"),"offline reload opens app with offline banner")
    check(pg.evaluate("ITEMS.length")==2,"catalog loaded from cache")
    # ---- offline bill
    pg.evaluate("cart={'i1|M':2}; save(); renderAll(); openBill()")
    pg.click("[data-pay]"); pg.fill("#iPhone","9000000011"); pg.fill("#iName","Ali")
    t0=time.time(); pg.click("#send")
    try: pg.wait_for_selector("#newBill",timeout=8000)
    except Exception: print("TOAST:",pg.inner_text("#toast"),"| PAYMODES",pg.evaluate("JSON.stringify(payModes)"),"| ERRS",errs); raise
    check(time.time()-t0<6,"offline bill saved without hang (%.1fs)"%(time.time()-t0))
    txt=pg.inner_text("#sheetBody"); n1=pg.evaluate("OFF.S.box[0].no"); print("   offline bill no",n1)
    check("Offline" in txt and n1.startswith("OFF-") and "T" not in n1.split("-")[1],"reserved real number offline: "+n1)
    check(pg.evaluate("OFF.pend().length")==1,"1 waiting in outbox")
    # second offline bill + weight line
    pg.click("#newBill") if pg.query_selector("#newBill") else None
    pg.evaluate("closeSheet(); cart={'i1|L':1,'w1|W':250}; save(); renderAll(); openBill()")
    pg.click("[data-pay]"); pg.fill("#iPhone","9000000011"); pg.click("#send"); pg.wait_for_selector("#newBill",timeout=8000)
    n2=pg.evaluate("OFF.S.box[1].no"); check(n2!=n1,"second offline bill another number: "+n2)
    # ---- offline history shows them
    pg.evaluate("closeSheet(); openBills()"); pg.wait_for_selector(".bh",timeout=5000)
    h=pg.inner_text("#sheetBody"); check(n1 in h and n2 in h and "Offline" in h,"offline bill history lists waiting bills")
    # ---- offline cancel from the offline bill (by phone)
    pg.evaluate("closeSheet(); openCancelFromBill('9000000011')"); pg.wait_for_selector("[data-cfbbill]",timeout=5000)
    check(pg.locator("[data-cfbbill]").count()==2,"cancel-from-bill finds both queued bills offline")
    pg.evaluate("cfbByNo(%s)"%json.dumps(n1)); pg.wait_for_selector("[data-cfbq]",timeout=5000)
    pg.click('[data-cfbq="0"][data-n="1"]'); pg.fill("#cfbReason","test return"); pg.click("#cfbSave"); pg.wait_for_selector("#cfbAgain",timeout=8000)
    cn1=pg.evaluate("OFF.S.box.filter(e=>e.kind==='cancel')[0].no"); print("   offline cancel no",cn1)
    check("-C" in cn1 and pg.evaluate("OFF.pend().length")==3,"offline cancel queued: "+cn1)
    # left now 1 for that line
    pg.evaluate("cfbByNo(%s)"%json.dumps(n1)); pg.wait_for_selector("[data-cfbq]")
    check(("remaining 1" in pg.inner_text("#sheetBody")) or ("மீதம் 1" in pg.inner_text("#sheetBody")),"left quantity reduced offline (1 left)")
    # ---- offline cancel from cart
    pg.evaluate("closeSheet(); cart={'i1|M':1}; save(); renderAll(); openCancelForm()")
    pg.fill("#cxReason","wrong item"); pg.click("#saveCancelBill"); pg.wait_for_selector("#newBill",timeout=8000)
    check(pg.evaluate("OFF.pend().length")==4,"cancel from cart queued offline")
    pg.evaluate("closeSheet(); OFF.openSync()"); time.sleep(.4); pg.screenshot(path="/tmp/off_sync.png")
    pg.evaluate("closeSheet(); window.scrollTo(0,0)"); time.sleep(.3); pg.screenshot(path="/tmp/off_home.png")
    # ---- back online: everything syncs
    ctx.set_offline(False); pg.evaluate("window.dispatchEvent(new Event('online'))")
    pg.wait_for_function("OFF.pend().length===0",timeout=30000); check(True,"outbox drained after going online")
    st,l=sales.call("bills",expect=200); nos=[x["no"] for x in l["bills"]]
    check(n1 in nos and n2 in nos,"both bills on server with same numbers")
    st,cl=sales.call("cancel-bills",expect=200); check(len(cl["cancels"])==2,"2 cancels on server")
    st,v=sales.call("bills/for-cancel?no="+n1,expect=200); li=v["bill"]["lines"][0]; check(li["left"]==1,"server left=1 after cancel sync")
    # ---- unreachable server while navigator thinks it is online: timeout/abort path, no hang
    pg.evaluate("closeSheet(); cart={'i1|M':1}; save(); renderAll(); openBill()")
    pg.route("**/api/bills",lambda r:r.abort()); pg.click("[data-pay]"); pg.fill("#iPhone","9000000012")
    t0=time.time(); pg.click("#send"); pg.wait_for_selector("#newBill",timeout=20000)
    check("Offline" in pg.inner_text("#sheetBody"),"server down -> queued (%.1fs)"%(time.time()-t0))
    n3=pg.evaluate("OFF.S.box[0].no")
    pg.unroute("**/api/bills"); pg.evaluate("OFF.sync({force:true})"); pg.wait_for_function("OFF.pend().length===0",timeout=30000)
    st,l=sales.call("bills",expect=200); check(n3 in [x["no"] for x in l["bills"]],"queued bill synced after server back")
    # ---- lost response: server saved the bill but the reply was dropped -> no duplicate after retry
    pg.evaluate("closeSheet(); cart={'i1|L':1}; save(); renderAll(); openBill()")
    def lose(route):
        route.fetch(); route.abort()
    pg.route("**/api/bills",lose); pg.click("[data-pay]"); pg.fill("#iPhone","9000000013")
    pg.click("#send"); pg.wait_for_selector("#newBill",timeout=20000)
    n4=pg.evaluate("OFF.S.box[0].no"); pg.unroute("**/api/bills"); pg.evaluate("OFF.sync({force:true})"); pg.wait_for_function("OFF.pend().length===0",timeout=30000)
    st,l=sales.call("bills",expect=200); c4=[x for x in l["bills"] if x["phone"]=="9000000013"]
    check(len(c4)==1,"lost response: exactly one bill on server (%s -> %s)"%(n4,c4[0]["no"]))
    print("   page errors:",errs)
    b.close()
print("ALL OK",ok)
