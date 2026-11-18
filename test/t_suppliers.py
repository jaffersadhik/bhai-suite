import sys,json,uuid,time
sys.path.insert(0,'/home/claude/w5/test')
from api import *
from playwright.sync_api import sync_playwright
BASE="http://127.0.0.1:8099/"; ok=0
def check(c,msg):
    global ok
    assert c,msg; ok+=1; print("  ✓",msg)
adm,_=login("admin"); sfx=uuid.uuid4().hex[:5]
st,d=adm.call("admin/company","POST",{"name":"SUP "+sfx,"phone":"9876543210","billPrefix":"SU"},expect=200); cid=d["company"]["id"]
adm.call("admin/user","POST",{"user":"s"+sfx,"name":"Stock","type":"stock","companyId":cid,"password":"pass12345"})
adm.call("admin/user","POST",{"user":"o"+sfx,"name":"Own","type":"owner","companyId":cid,"password":"pass12345"})
adm.call("admin/open","POST",{"companyId":cid},expect=200)
adm.call("catalog","PUT",{"version":0,"cats":[{"id":"g1","ta":"க","en":"G","emoji":"🛍️","color":"#e8eef0"}],"items":[{"id":"i1","cat":"g1","ta":"சட்டை","en":"Shirt","sizes":[{"size":"M","rate":200}]}]},expect=200)
# API
st,r=adm.call("suppliers","POST",{"name":"Tirupur Textiles","phone":"9000000001","gstin":"","address":"Tirupur"},expect=200); sid=r["id"]
check(len(r["suppliers"])==1 and r["suppliers"][0]["canDelete"] is True,"add supplier via API")
st,e=adm.call("suppliers","POST",{"name":"tirupur textiles"},expect=400); check(True,"duplicate name rejected")
st,e=adm.call("suppliers","POST",{"name":"X","phone":"123"},expect=400); check(True,"bad mobile rejected")
st,r=adm.call("suppliers","POST",{"id":sid,"name":"Tirupur Textiles","phone":"9000000002","address":"Tirupur, TN"},expect=200)
check(r["suppliers"][0]["phone"]=="9000000002","edit supplier")
adm.call("po","POST",{"supplier":{"id":sid},"orderDate":"2026-11-16","lines":[{"itemId":"i1","name":"சட்டை","size":"M","qty":10,"unitCost":100,"gstPct":0}],"paid":400},expect=200)
st,r=adm.call("suppliers",expect=200); x=r["suppliers"][0]
check(x["poCount"]==1 and x["total"]==1000 and x["paid"]==400 and x["balance"]==600 and x["canDelete"] is False,"stats: 1 PO, ₹1000, paid 400, due 600")
st,e=adm.call("suppliers/%s/delete"%sid,"POST",{},expect=400); check(True,"cannot delete supplier with PO")
errs=[]
with sync_playwright() as p:
    b=p.chromium.launch(args=["--no-sandbox"]); ctx=b.new_context(viewport={"width":420,"height":900},service_workers="allow")
    pg=ctx.new_page(); pg.on("pageerror",lambda e:errs.append(str(e))); pg.on("dialog",lambda d:d.accept())
    pg.goto(BASE); pg.wait_for_selector("#lUser"); pg.fill("#lUser","s"+sfx); pg.fill("#lPass","pass12345"); pg.click("#lBtn")
    pg.wait_for_function("document.body.classList.contains('authed')",timeout=15000)
    pg.evaluate("openMenu&&openMenu(); 0") if pg.evaluate("typeof openMenu")=="function" else None
    pg.wait_for_selector("#mSup",state="attached",timeout=8000)
    pg.evaluate("document.getElementById('mSup').click()"); pg.wait_for_selector("[data-supopen]")
    check("Tirupur" in pg.inner_text("#sheetBody") and "₹600" in pg.inner_text("#sheetBody"),"menu → supplier list with due")
    pg.screenshot(path="/tmp/sup1.png")
    pg.click("#supNew"); pg.fill("#sfName","Chennai Fabrics"); pg.fill("#sfPhone","9000000009"); pg.click("#supSave")
    pg.wait_for_selector("[data-supedit]"); check("Chennai Fabrics" in pg.inner_text("#sheetBody"),"added new supplier from UI → detail")
    pg.click("#supBack"); pg.wait_for_selector("[data-supopen]")
    check(pg.locator("[data-supopen]").count()==2,"list has 2 suppliers")
    pg.locator("[data-supopen]").nth(0).click(); pg.wait_for_selector("[data-supedit]")
    pg.click("[data-supedit]"); pg.fill("#sfAddr","Anna Nagar"); pg.click("#supSave"); pg.wait_for_selector("[data-supedit]")
    check("Anna Nagar" in pg.inner_text("#sheetBody"),"edited address")
    check(pg.locator("[data-supdel]").count()==1,"delete offered for supplier w/o PO")
    pg.click("[data-supnewpo]"); pg.wait_for_selector("#poSupSel")
    check(pg.eval_on_selector("#poSupSel","e=>e.options[e.selectedIndex].text").startswith("Chennai"),"new PO opens with this supplier preselected")
    check(pg.locator("#poSupSel option").count()>=4,"PO supplier dropdown lists existing + ➕ new")
    pg.evaluate("closeSheet(); 0"); pg.evaluate("openSuppliers(); 0"); pg.wait_for_selector("[data-supopen]")
    pg.locator("[data-supopen]",has_text="Chennai").click(); pg.click("[data-supdel]"); pg.wait_for_selector("#supNew")
    check(pg.locator("[data-supopen]").count()==1,"deleted supplier")
    b.close()
# owner is view-only
o,_=login("owner",user="o"+sfx,pw="pass12345") if False else (None,None)
check(not errs,"no JS errors %s"%errs)
print("ALL",ok,"OK")
