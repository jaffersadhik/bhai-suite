import sys,uuid,time
sys.path.insert(0,'/home/claude/w4/test')
from api import *
from playwright.sync_api import sync_playwright
adm,_=login("admin"); sfx=uuid.uuid4().hex[:5]
st,d=adm.call("admin/company","POST",{"name":"BC "+sfx,"phone":"9876543210","billPrefix":"BC"},expect=200); cid=d["company"]["id"]
adm.call("admin/user","POST",{"user":"b"+sfx,"name":"BC","type":"stock","companyId":cid,"password":"pass12345"})
adm.call("admin/open","POST",{"companyId":cid},expect=200)
adm.call("catalog","PUT",{"version":0,"cats":[{"id":"g1","ta":"குரூப்","en":"Grp","emoji":"🛍️","color":"#e8eef0"}],"items":[]},expect=200)
with sync_playwright() as p:
    b=p.chromium.launch(args=["--no-sandbox"]); pg=b.new_page(viewport={"width":400,"height":800}); errs=[]; pg.on("pageerror",lambda e:errs.append(str(e)))
    pg.goto("http://127.0.0.1:8099/"); pg.fill("#lUser","b"+sfx); pg.fill("#lPass","pass12345"); pg.click("#lBtn")
    pg.wait_for_function("document.body.classList.contains('authed')")
    pg.evaluate("openItemForm('g1')"); pg.fill("#iTa","சட்டை"); pg.fill("#iEn","Shirt"); pg.click('[data-iset="custom"]'); pg.fill("#iSizes","M, L")
    pg.fill("#iSizes","M, L"); pg.dispatch_event("#iSizes","input"); time.sleep(.3)
    inp=pg.locator("[data-isz]"); inp.nth(0).fill("100"); inp.nth(1).fill("120")
    pg.fill("#ic0","8901234567890"); pg.fill("#ic1","ABC-777")
    print("scan buttons:",pg.locator("[data-scanfill]").count())
    pg.click("#itemSave"); time.sleep(1.5)
    pg.evaluate("closeSheet(); location.hash=''; renderAll()")
    pg.fill("#q","8901234"); time.sleep(.4)
    print("search by partial code finds:", pg.evaluate("document.querySelectorAll('[data-item]').length"), "errs",errs)
    assert pg.evaluate("document.querySelectorAll('[data-item]').length")>=1
    pg.fill("#q","abc-777"); pg.keyboard.press("Enter"); time.sleep(.5)
    print("Enter exact code -> cart:",pg.evaluate("JSON.stringify(cart)"))
    assert "L" in pg.evaluate("JSON.stringify(cart)")
    b.close()
print("BC OK")
