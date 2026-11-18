import sys,uuid
sys.path.insert(0,'/home/claude/w4/test')
from api import *
from playwright.sync_api import sync_playwright
adm,_=login("admin"); sfx=uuid.uuid4().hex[:5]
st,d=adm.call("admin/company","POST",{"name":"Shop "+sfx,"phone":"9876543210","billPrefix":"SHP","orderNoStock":True},expect=200); cid=d["company"]["id"]
adm.call("admin/open","POST",{"companyId":cid},expect=200)
adm.call("catalog","PUT",{"version":0,"cats":[{"id":"g1","ta":"குரூப்","en":"Grp","emoji":"🛍️","color":"#e8eef0"}],"items":[{"id":"i1","cat":"g1","ta":"சட்டை","en":"Shirt","sizes":[{"size":"M","rate":100}]}]},expect=200)
st,co=adm.call("data",expect=200); code=co["company"]["shopSlug"]
def run(flag):
    adm.call("admin/company/"+cid,"PUT",{"name":"Shop "+sfx,"phone":"9876543210","billPrefix":"SHP","orderNoStock":flag}) if False else None
with sync_playwright() as p:
    b=p.chromium.launch(args=["--no-sandbox"]); pg=b.new_page(viewport={"width":400,"height":800}); errs=[]
    pg.on("pageerror",lambda e:errs.append(str(e)))
    pg.goto("http://127.0.0.1:8099/shop.html?s="+code); pg.wait_for_selector("[data-grp]"); pg.click("[data-grp]"); pg.wait_for_selector("[data-item]")
    pg.click("[data-item]"); pg.wait_for_selector("#addCart")
    dis=pg.evaluate("document.getElementById('addCart').disabled"); txt=pg.inner_text("#sb")
    print("addCart disabled:",dis,"| note:", "முன்பதிவு" in txt); pg.screenshot(path="/tmp/shop_ns.png")
    pg.click("#addCart"); print("cart keys:",pg.evaluate("Object.keys(cart)"),"errs",errs)
    assert not dis and pg.evaluate("Object.keys(cart).length")==1
    b.close()
print("SHOP OK")
