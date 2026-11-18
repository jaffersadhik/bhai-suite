import sys,json,uuid,time
sys.path.insert(0,'/home/claude/w5/test')
from api import *
from playwright.sync_api import sync_playwright
BASE="http://127.0.0.1:8099/"; ok=0
def check(c,msg):
    global ok
    assert c,msg; ok+=1; print("  ✓",msg)
adm,_=login("admin"); sfx=uuid.uuid4().hex[:5]
st,d=adm.call("admin/company","POST",{"name":"View Co "+sfx,"phone":"9876543210","address":"12 Main Road","city":"Chennai","billPrefix":"VW"},expect=200); cid=d["company"]["id"]
adm.call("admin/user","POST",{"user":"u"+sfx,"name":"Sales UI","type":"sales","companyId":cid,"password":"pass12345"})
cat={"version":0,"cats":[{"id":"g1","ta":"குரூப்","en":"Grp","emoji":"🛍️","color":"#e8eef0"}],
 "items":[{"id":"i1","cat":"g1","ta":"சட்டை","en":"Shirt","sizes":[{"size":"M","rate":100},{"size":"L","rate":120}]}]}
adm.call("admin/open","POST",{"companyId":cid},expect=200); adm.call("catalog","PUT",cat,expect=200)
errs=[]
with sync_playwright() as p:
    b=p.chromium.launch(args=["--no-sandbox"])
    ctx=b.new_context(viewport={"width":400,"height":850},service_workers="allow")
    pg=ctx.new_page(); pg.on("pageerror",lambda e:errs.append(str(e))); pg.on("dialog",lambda d:d.accept())
    pg.goto(BASE); pg.wait_for_selector("#lUser")
    pg.fill("#lUser","u"+sfx); pg.fill("#lPass","pass12345"); pg.click("#lBtn")
    pg.wait_for_function("document.body.classList.contains('authed')",timeout=15000)
    pg.wait_for_function("OFF.left('bill')>0 && OFF.S.last>0",timeout=20000)
    def make(cart,ph,nm):
        pg.evaluate("c=>{cart=c; save(); renderAll(); openBill()}",cart)
        pg.click("[data-pay]"); pg.fill("#iPhone",ph); pg.fill("#iName",nm); pg.click("#send")
        pg.wait_for_selector("#newBill",timeout=10000); pg.click("#newBill"); time.sleep(.4)
    make({"i1|M":2,"i1|L":1},"9000000011","Ali")
    make({"i1|M":1},"9000000022","Basha")
    # ---------- history
    pg.evaluate("openBills()"); pg.wait_for_selector("[data-bview]",timeout=8000)
    check(pg.locator("[data-bview]").count()>=2 and pg.locator("[data-bprint]").count()>=2,"history rows show 👁 பில் பார் + 🖨 Print buttons")
    pg.locator("[data-bview]").first.click(); pg.wait_for_selector(".bv-meta"); time.sleep(.3)
    txt=pg.inner_text("#sheetBody")
    check("Basha" in txt and "₹100" in txt,"view sheet shows latest bill (customer + total)")
    pg.screenshot(path="/tmp/bv1.png")
    # lines + totals for the 2-line bill
    pg.click("#mBills"); pg.wait_for_selector("[data-bview]"); pg.locator("[data-bview]").nth(1).click(); pg.wait_for_selector(".bv-meta")
    txt=pg.inner_text("#sheetBody")
    check("Ali" in txt and "3 pcs" in txt and "₹320" in txt and "Size: M" in txt and "Size: L" in txt,"view sheet shows all lines, 3 pcs, ₹320")
    check(pg.locator("[data-pfmt]").count()==3 and pg.locator("[data-pfmt].on").get_attribute("data-pfmt")=="80","3 paper sizes, default 80 mm")
    pg.screenshot(path="/tmp/bv2.png")
    # ---------- print (window.print stubbed; check print area + page css + cleanup)
    pg.evaluate("window.__pc=0; window.print=()=>{ window.__pc++; window.__snap={cls:document.body.className, html:document.getElementById('printArea').innerHTML, css:document.getElementById('bpPageCss').textContent}; }; 0")
    for f,w in (("58","58mm "),("80","80mm "),("a4","A4")):
        pg.click(f'[data-pfmt="{f}"]'); check(pg.locator("[data-pfmt].on").get_attribute("data-pfmt")==f,"select "+f)
        pg.locator('.cta[data-bprint]').click(); time.sleep(.5)
        s=pg.evaluate("window.__snap"); cnt=pg.evaluate("window.__pc")
        import re
        check("pbill" in s["cls"] and w in s["css"] and (f=="a4" or re.search(r"size:%smm \d+mm;margin:0"%f,s["css"])) and "VW-" in s["html"] and "Ali" in s["html"] and "₹320" in s["html"],f"print {f}: area filled, @page {w.strip()}, bill no + customer + total present")
        if f=="a4": check("<th" in s["html"] and "12 Main Road" in s["html"],"A4 layout has table + address")
        else: check("rcpt t"+f in s["html"] and "📞" not in s["html"],"thermal layout t"+f+", no emoji")
        pg.evaluate("window.dispatchEvent(new Event('afterprint'))"); time.sleep(.2)
        check("pbill" not in pg.evaluate("document.body.className") and pg.evaluate("document.getElementById('printArea').innerHTML")=="","cleaned up after print")
    check(pg.evaluate("localStorage.getItem('mb.pfmt')")=="a4","paper size choice remembered")
    # Print straight from the history row
    pg.click("#mBills"); pg.wait_for_selector("[data-bprint]"); pg.locator("[data-bprint]").first.click(); time.sleep(.4)
    check("Basha" in pg.evaluate("window.__snap.html") and pg.evaluate("window.__pc")==4,"🖨 from history row prints that bill")
    pg.evaluate("window.dispatchEvent(new Event('afterprint'))")
    # weighted line + exchange rendering (synthetic)
    h=pg.evaluate("""billPrintHtml({no:'VW-9',at:new Date().toISOString(),customer:'X',by:'me',pieces:2,total:500,exchange:100,exchangeRef:'VW-1',paid:400,pay:'Cash',
        lines:[{name:'பாதாம்',en:'Almond',size:'',qty:1,rate:500,amt:500,wt:250}]},'80')""")
    check("250 g" in h and "Exchange" in h and "₹400" in h,"weighed line shows weight; exchange + paid shown")
    # ---------- offline: queued bill is viewable + printable
    ctx.set_offline(True); time.sleep(.5)
    make({"i1|M":1},"9000000033","Offline Omar")
    pg.evaluate("openBills()"); pg.wait_for_selector("[data-bview]",timeout=8000)
    check("⏳" in pg.inner_text("#sheetBody"),"queued bill listed with ⏳")
    pg.locator("[data-bview]").first.click(); pg.wait_for_selector(".bv-meta")
    check(pg.locator(".bv-note").count()==1 and "Offline Omar" in pg.inner_text("#sheetBody"),"offline bill: view works + sync note")
    pg.locator('.cta[data-bprint]').click(); time.sleep(.4)
    check("Offline Omar" in pg.evaluate("window.__snap.html"),"offline bill prints")
    pg.evaluate("window.dispatchEvent(new Event('afterprint'))")
    ctx.set_offline(False)
    # ---------- reports list (daily) also has the buttons
    check(len([e for e in errs if "ResizeObserver" not in e])==0,"no page errors: "+str(errs))
    b.close()
print("BILLVIEW OK",ok,"checks")
