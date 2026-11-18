import sys,uuid,time,subprocess
sys.path.insert(0,'/home/claude/w13/in/bhai-mobile-billing/test')
from api import *
from playwright.sync_api import sync_playwright
ok=0
def check(c,m):
    global ok
    assert c,m; ok+=1; print("  ✓",m)
subprocess.run(["mysql","-ubmb","-pbmb123","bmb","-e","delete from auth_tokens; UPDATE users SET max_sessions=50, idle_minutes=0 WHERE username='admin'"])
sfx=uuid.uuid4().hex[:5]; adm,_=login("admin")
# a company created without saying anything about pre-order -> ON by default
st,d=adm.call("admin/company","POST",{"name":"Fish "+sfx,"phone":"9876543210","billPrefix":"FS"},expect=200); cid=d["company"]["id"]
check(d["company"]["orderNoStock"] is True,"new company: pre-order (order without stock) is ON by default")
adm.call("admin/open","POST",{"companyId":cid},expect=200)
csv="group,item,size,rate,sold_by,wt_unit\nSea,Seer Fish,,1100,weight,kg\nSea,Masala,100 g,60,count,\n"
adm.call("catalog/import","POST",{"csv":csv,"replace":True},expect=200)
st,co=adm.call("data",expect=200); code=co["company"]["shopSlug"]
st,sh=Api().call("shop/"+code,expect=200); br=[b for b in sh["branches"]][0]["id"]
errs=[]
def order_ui(pg):
    pg.goto("http://127.0.0.1:8099/shop.html?s="+code); pg.wait_for_selector("[data-grp]"); pg.click("[data-grp]"); pg.wait_for_selector("[data-item]")
    pg.click("[data-item] >> nth=0"); pg.wait_for_selector("#addCart"); dis=pg.evaluate("document.getElementById('addCart').disabled")
    return dis
with sync_playwright() as p:
    b=p.chromium.launch(args=["--no-sandbox"]); pg=b.new_page(viewport={"width":400,"height":900}); pg.on("pageerror",lambda e:errs.append(str(e)))
    dis=order_ui(pg); check(not dis,"no stock + pre-order ON: Add to cart is enabled")
    check("Pre-order" in pg.inner_text("#sb") or "முன்பதிவு" in pg.inner_text("#sb"),"item page shows the pre-order note")
    pg.click("#addCart"); time.sleep(.5)
    pg.evaluate("openCart && openCart()") if pg.evaluate("typeof openCart")=="function" else pg.click(".bar button")
    pg.wait_for_selector("#placeOrder"); time.sleep(.5)
    check(pg.is_visible("#preNote"),"cart shows the pre-order banner")
    pg.fill("#oName","Test Cust"); pg.fill("#oPhone","9876543210"); pg.click("[data-mode=pickup]"); pg.select_option("#oBranch",br)
    time.sleep(.4); pg.screenshot(path="/tmp/preorder.png"); pg.click("#placeOrder"); time.sleep(2); print("SB",pg.evaluate("document.getElementById('toast').textContent"),errs); pg.wait_for_selector("#waSend, .ok h2",timeout=10000); time.sleep(.5)
    t=pg.inner_text("#sb"); check("ORD-" in t,"Place Order works with zero stock: "+t[:40].replace("\n"," "))
    check("pre-order" in t.lower() or "முன்பதிவு" in t,"confirmation says it is a pre-order")
    wa=pg.evaluate("document.getElementById('waSend')&&decodeURIComponent(document.getElementById('waSend').href)") or ""; check("pre-order" in wa.lower() or "முன்பதிவு" in wa,"WhatsApp order text marks the pre-order line")
    # admin sees the pre-order flag
    st,o=adm.call("orders?status=all",expect=200); l=o["orders"][0]["lines"][0]; check(bool(l.get("short")),"order line carries short=1 (pre-order)")
    # switch pre-order OFF: Place Order is refused again
    adm.call("admin/company","POST",{"id":cid,"name":"Fish "+sfx,"phone":"9876543210","billPrefix":"FS","orderNoStock":False},expect=200)
    st,r=Api().call("shop/"+code+"/orders","POST",{"name":"X Y","phone":"9876543211","mode":"pickup","branch":br,"lines":[{"itemId":l["id"],"wt":1000,"qty":1}]})
    check(st==409,"pre-order OFF: order without stock refused (409)")
    check(not errs,"no JS errors "+str(errs[:2])); b.close()
# admin UI shows the tag
print("PASS",ok)
