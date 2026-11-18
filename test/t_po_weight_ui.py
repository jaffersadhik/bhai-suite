import sys,json,uuid,time
sys.path.insert(0,'/home/claude/w5/test')
from api import *
from playwright.sync_api import sync_playwright
BASE="http://127.0.0.1:8099/"; ok=0
def check(c,msg):
    global ok
    assert c,msg; ok+=1; print("  ✓",msg)
adm,_=login("admin"); sfx=uuid.uuid4().hex[:5]
st,d=adm.call("admin/company","POST",{"name":"POUI "+sfx,"phone":"9876543210","billPrefix":"PU"},expect=200); cid=d["company"]["id"]
adm.call("admin/user","POST",{"user":"s"+sfx,"name":"Stock UI","type":"stock","companyId":cid,"password":"pass12345"})
cat={"version":0,"cats":[{"id":"g1","ta":"குரூப்","en":"Grp","emoji":"🛍️","color":"#e8eef0"}],"items":[
 {"id":"i1","cat":"g1","ta":"சட்டை","en":"Shirt","sizes":[{"size":"M","rate":200}]},
 {"id":"w1","cat":"g1","ta":"பாதாம்","en":"Almond","by":"weight","unit":"kg","sizes":[{"size":"1 kg","rate":900}]}]}
adm.call("admin/open","POST",{"companyId":cid},expect=200); adm.call("catalog","PUT",cat,expect=200)
errs=[]
with sync_playwright() as p:
    b=p.chromium.launch(args=["--no-sandbox"])
    ctx=b.new_context(viewport={"width":420,"height":900},service_workers="allow")
    pg=ctx.new_page(); pg.on("pageerror",lambda e:errs.append(str(e))); pg.on("dialog",lambda d:d.accept("Loose Dates"))
    pg.goto(BASE); pg.wait_for_selector("#lUser")
    pg.fill("#lUser","s"+sfx); pg.fill("#lPass","pass12345"); pg.click("#lBtn")
    pg.wait_for_function("document.body.classList.contains('authed')",timeout=15000)
    pg.evaluate("openPoForm(null); 0"); pg.wait_for_selector("#poQ",timeout=8000)
    pg.fill("#psName","Sup UI"); 
    # weight catalog item: only an 'add by weight' button
    pg.fill("#poQ","பாதாம்"); pg.wait_for_selector("[data-poadd]")
    check("எடையில் சேர்" in pg.inner_text("#poSug") and "எல்லா சைஸும்" not in pg.inner_text("#poSug"),"weight item offers 'add by weight'")
    pg.click("[data-poadd]")
    check(pg.locator("select[data-f=unit]").count()==1,"weight line has kg/g selector")
    # 2.5 kg @ 700/kg
    pg.fill("[data-pol='0'][data-f=qty]","2.5"); pg.fill("[data-pol='0'][data-f=unitCost]","700")
    check("₹1,750" in pg.inner_text("#pam0"),"2.5 kg × ₹700/kg = ₹1,750: "+pg.inner_text("#pam0"))
    # switch to grams: value converts to 2500 g
    pg.select_option("[data-pol='0'][data-f=unit]","g")
    check(pg.input_value("[data-pol='0'][data-f=qty]")=="2500","kg->g converts 2.5 -> 2500")
    pg.fill("[data-pol='0'][data-f=qty]","500")
    check("₹350" in pg.inner_text("#pam0"),"500 g at ₹700/kg = ₹350: "+pg.inner_text("#pam0"))
    # count item
    pg.fill("#poQ","சட்டை"); pg.wait_for_selector("[data-poadd]"); pg.click("[data-poadd][data-posz='M']")
    pg.fill("[data-pol='1'][data-f=qty]","3"); pg.fill("[data-pol='1'][data-f=unitCost]","100")
    check(pg.locator("[data-pol='1'][data-f=unit]").count()==0,"count line has no kg/g selector")
    # custom line toggled to weight
    pg.click("#poCustom"); pg.wait_for_selector("select[data-f=by]")
    pg.select_option("select[data-f=by]","weight")
    check(pg.locator("[data-pol='2'][data-f=unit]").count()==1,"custom line can switch to ⚖️ weight")
    pg.fill("[data-pol='2'][data-f=qty]","1.5"); pg.fill("[data-pol='2'][data-f=unitCost]","200")
    check("₹300" in pg.inner_text("#pam2"),"custom 1.5 kg × 200 = ₹300")
    tot=pg.inner_text("#poTot"); check("3 பீஸ்" in tot and "500 g" not in tot and "2 kg" in tot,"totals show pieces + weight: "+tot.replace("\n"," | "))
    pg.screenshot(path="/tmp/po_form.png",full_page=False)
    pg.click("#poSave"); pg.wait_for_selector("#poRecv",timeout=10000)
    txt=pg.inner_text("#sheetBody")
    check("500 g" in txt and "1.5 kg" in txt and "₹700/kg" in txt.replace(" ",""),"detail shows weights + price per unit")
    wa=pg.evaluate("poText(poCur)"); check("500 g × ₹700.00/kg" in wa or "500 g × ₹700/kg" in wa,"WhatsApp text has weight: "+wa.split("\n")[5])
    # receive 250 g + 1 kg ... via kg/g selectors
    pg.click("#poRecv"); pg.wait_for_selector("[data-rcvu]")
    check(pg.locator("[data-rcvu]").count()==2,"receive screen: kg/g selector for 2 weight lines")
    ins=pg.locator("[data-rcv][data-w]"); 
    ins.nth(0).fill("250"); pg.select_option("[data-rcvu]>>nth=0","g"); 
    ins.nth(1).fill("1"); 
    pg.fill("[data-rcv]:not([data-w])","1")
    pg.screenshot(path="/tmp/po_recv.png")
    pg.click("#rcvSave"); pg.wait_for_selector("#poRecv",timeout=10000); time.sleep(.3)
    txt=pg.inner_text("#sheetBody")
    check("250 g" in txt and "1 kg" in txt,"received shows 250 g + 1 kg: ")
    # over-receive blocked: 600 g of the 250 g left
    pg.click("#poRecv"); pg.wait_for_selector("[data-rcvu]")
    pg.locator("[data-rcv][data-w]").nth(0).fill("600"); pg.select_option("[data-rcvu]>>nth=0","g"); pg.click("#rcvSave")
    check(pg.locator("[data-rcv][data-w]").count()>0,"over-receive rejected, stays on screen")
    # stock check via API
    pg.click("#rcvBack"); pg.wait_for_selector("#poEdit,#poRecv"); 
    b.close()
st,s=adm.call("stock",expect=200); sk={(x["item"],x["size"]):x["qty"] for x in s["stock"]}
check(sk.get(("w1","1 kg"))==250,"stock w1 = 250 g (kg item kept in grams)")
check(not errs,"no JS errors: %s"%errs)
print("ALL",ok,"OK")
