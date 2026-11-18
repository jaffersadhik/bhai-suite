import sys,uuid,time,subprocess
sys.path.insert(0,'/home/claude/w13/in/bhai-mobile-billing/test')
from api import *
from playwright.sync_api import sync_playwright
ok=0
def check(c,m):
    global ok
    assert c,m; ok+=1; print("  ✓",m)
subprocess.run(["mysql","-ubmb","-pbmb123","bmb","-e","delete from auth_tokens"])
sfx=uuid.uuid4().hex[:5]; adm,_=login("admin")
st,d=adm.call("admin/company","POST",{"name":"Fish "+sfx,"phone":"9876543210","billPrefix":"FS"},expect=200); cid=d["company"]["id"]
adm.call("admin/open","POST",{"companyId":cid},expect=200)
csv="group,item,size,rate,sold_by,wt_unit\nSea,Seer Fish,,1100,weight,kg\nSea,Masala,100 g,60,count,\n"
adm.call("catalog/import","POST",{"csv":csv,"replace":True},expect=200)
st,co=adm.call("data",expect=200); code=co["company"]["shopSlug"]
st,sh=Api().call("shop/"+code,expect=200); br=sh["branches"][0]["id"]
items={i["en"] or i["ta"]:i for i in sh["catalog"]["items"]}; print(list(items))
errs=[]
with sync_playwright() as p:
    b=p.chromium.launch(args=["--no-sandbox"]); pg=b.new_page(viewport={"width":400,"height":900}); pg.on("pageerror",lambda e:errs.append(str(e)))
    pg.goto("http://127.0.0.1:8099/shop.html?s="+code); pg.wait_for_selector("[data-grp]"); pg.click("[data-grp]"); pg.wait_for_selector("[data-item]")
    pg.click("[data-item='%s']"%items["Seer Fish"]["id"]); pg.wait_for_selector("#wIn")
    check(pg.locator("[data-iq]").count()==0,"weight item: no piece-count stepper")
    check(pg.locator("[data-wq]").count()>=4,"weight item: weight chips shown")
    pg.click("[data-wq='500']"); time.sleep(.2); check("₹550" in pg.inner_text("#wAmt"),"500 g = ₹550: "+pg.inner_text("#wAmt"))
    pg.fill("#wIn","1.5"); time.sleep(.2); check("₹1,650" in pg.inner_text("#wAmt"),"typed 1.5 kg = ₹1,650: "+pg.inner_text("#wAmt"))
    pg.select_option("#wUn","g"); time.sleep(.2); check(pg.input_value("#wIn")=="1500","unit switch to g keeps weight")
    pg.fill("#wIn","750"); time.sleep(.2); check("₹825" in pg.inner_text("#wAmt"),"750 g = ₹825")
    pg.click("#addCart"); time.sleep(.5)
    check(pg.evaluate("cart[Object.keys(cart)[0]]")==750,"cart holds 750 (grams)")
    pg.wait_for_selector("[data-item]"); pg.click("[data-item='%s']"%items["Masala"]["id"]); pg.wait_for_selector("[data-iq]")
    pg.click("[data-iq='1']"); pg.click("#addCart"); time.sleep(.5)
    pg.click("#openCart"); pg.wait_for_selector("#placeOrder"); t=pg.inner_text("#sb")
    check("750 g" in t,"cart shows weight line as 750 g"); check("₹825" in t and "₹120" in t,"cart amounts right")
    pg.fill("#oName","Test Cust"); pg.fill("#oPhone","9876543210"); pg.click("[data-mode=pickup]"); pg.select_option("#oBranch",br); time.sleep(.3)
    pg.click("#placeOrder"); pg.wait_for_selector(".ok h2",timeout=10000)
    st,o=adm.call("orders?status=all",expect=200); L={l["name"]:l for l in o["orders"][0]["lines"]}
    w=[l for l in L.values() if l.get("wt")][0]; c=[l for l in L.values() if not l.get("wt")][0]
    check(w["wt"]==750 and w["size"]=="750 g" and abs(w["amt"]-825)<.01 and w.get("short")==750,"weight line stored: %s"%w)
    check(c["qty"]==2 and abs(c["amt"]-120)<.01,"count line stored with qty 2 (+ stepper)")
    check(not errs,"no JS errors "+str(errs[:2])); b.close()
# server-side checks
adm.call("admin/company","POST",{"id":cid,"name":"Fish "+sfx,"phone":"9876543210","billPrefix":"FS","orderNoStock":False},expect=200)
fid=items["Seer Fish"]["id"]
st,r=Api().call("shop/"+code+"/orders","POST",{"name":"X Y","phone":"9876543211","mode":"pickup","branch":br,"lines":[{"itemId":fid,"wt":500,"qty":1}]})
check(st==409 and "kg" in r.get("error","") or "g" in r.get("error",""),"pre-order OFF: weight order refused: %s"%r.get("error"))
st,r=Api().call("shop/"+code+"/orders","POST",{"name":"X Y","phone":"9876543211","mode":"pickup","branch":br,"lines":[{"itemId":fid,"wt":5,"qty":1}]})
check(st==400,"weight below 10 g refused")
print("PASS",ok)
