import sys,uuid,time,subprocess,tempfile,os,csv,io
sys.path.insert(0,'/home/claude/w8/src/bhai-mobile-billing/test')
from api import *
from playwright.sync_api import sync_playwright
BASE="http://127.0.0.1:8099/"; ok=0
def check(c,m):
    global ok
    assert c,m; ok+=1; print("  ✓",m)
def sql(q): return subprocess.run(["mysql","-ubmb","-pbmb123","bmb","-N","-e",q],capture_output=True,text=True).stdout.strip()
sql("UPDATE users SET max_sessions=50, idle_minutes=0 WHERE username='admin'")
sfx=uuid.uuid4().hex[:5]; adm,_=login("admin")
st,d=adm.call("admin/company","POST",{"name":"LP "+sfx,"phone":"9876543210","billPrefix":"LP"},expect=200); cid=d["company"]["id"]
adm.call("admin/user","POST",{"user":"o"+sfx,"name":"Own","type":"owner","companyId":cid,"password":"pass12345"})
adm.call("admin/open","POST",{"companyId":cid},expect=200)
adm.call("catalog/import","POST",{"csv":"group,item,size,rate,wholesale,cost\nகுரூப்,ஆப்பிள்,1 pc,10,8,5\nகுரூப்,ஆப்பிள்,6 pc,50,45,30\n","replace":True},expect=200)
errs=[]
with sync_playwright() as p:
    b=p.chromium.launch(args=["--no-sandbox"])
    ctx=b.new_context(viewport={"width":420,"height":900},service_workers="allow",accept_downloads=True); pg=ctx.new_page()
    pg.on("pageerror",lambda e:errs.append(str(e))); pg.on("dialog",lambda d:d.accept())
    def lang(): return pg.evaluate("I18N.lang")
    def login_ui(u,pw="pass12345"):
        pg.goto(BASE); pg.wait_for_selector("#lUser"); pg.fill("#lUser",u); pg.fill("#lPass",pw); pg.click("#lBtn")
        pg.wait_for_function("document.body.classList.contains('authed')",timeout=15000); time.sleep(1)
    pg.goto(BASE); pg.wait_for_selector("#lUser"); time.sleep(.8)
    check(lang()=="en","login page starts in English")
    login_ui("o"+sfx); check(lang()=="en","new user: app in English")
    # change language inside the app
    pg.evaluate("I18N.set('ta'); 0"); pg.evaluate("window.onLangPick('ta'); 0"); time.sleep(1)
    check(sql(f"SELECT lang FROM users WHERE username='o{sfx}'")=="ta","choice saved on the server")
    pg.evaluate("logout()")
    time.sleep(1.5); pg.goto(BASE); pg.wait_for_selector("#lUser"); time.sleep(.8)
    check(lang()=="en","login page is English again after logout")
    login_ui("o"+sfx); check(lang()=="ta","after login the user's saved language (ta) is applied")
    # another user on same browser starts English
    adm.call("admin/user","POST",{"user":"p"+sfx,"name":"Two","type":"owner","companyId":cid,"password":"pass12345"})
    pg.evaluate("logout()"); time.sleep(1.5); pg.goto(BASE); pg.wait_for_selector("#lUser"); time.sleep(.5); check(lang()=="en","after logout: login page English (no device-wide language)")
    # language picked on the login page counts as the user's choice
    pg.fill("#lUser","p"+sfx); pg.fill("#lPass","pass12345"); pg.evaluate("I18N.set('hi'); window.onLangPick('hi'); 0"); time.sleep(.8); pg.click("#lBtn")
    pg.wait_for_function("document.body.classList.contains('authed')",timeout=15000); time.sleep(1.2)
    check(lang()=="hi" and sql(f"SELECT lang FROM users WHERE username='p{sfx}'")=="hi","picked on login page: applied and saved")
    # ---- price list
    adm.call("admin/user","POST",{"user":"k"+sfx,"name":"Stk","type":"stock","companyId":cid,"password":"pass12345"})
    pg.evaluate("logout()"); time.sleep(1.5); login_ui("k"+sfx); pg.wait_for_function("ITEMS.length>0",timeout=15000)
    check(lang()=="en","stock user (no saved language) -> English")
    pg.evaluate("openPrices(); 0"); pg.wait_for_selector("#prDown",timeout=8000)
    with pg.expect_download() as dl: pg.click("#prDown")
    path=dl.value.path(); txt=open(path,encoding="utf-8-sig").read(); rows=list(csv.DictReader(io.StringIO(txt)))
    check(len(rows)==2 and rows[0]["item_id"] and "cost" in rows[0] and rows[0]["rate"]=="10","downloaded CSV has 2 rows with item_id/rate/cost")
    rows[0]["rate"]="15"; rows[1]["rate"]="70"; rows[1]["wholesale"]=""
    out=io.StringIO(); w=csv.DictWriter(out,fieldnames=list(rows[0].keys())); w.writeheader(); w.writerows(rows)
    fp=os.path.join(tempfile.mkdtemp(),"edited.csv"); open(fp,"w",encoding="utf-8").write(out.getvalue())
    pg.set_input_files("#prFileIn",fp); pg.wait_for_selector("#prApply",timeout=8000)
    check("2" in pg.inner_text("#prPrev") and not pg.is_disabled("#prApply"),"preview shows 2 changes")
    pg.screenshot(path="/tmp/pl.png")
    pg.click("#prApply"); pg.wait_for_function("document.querySelector('#sheetBody').innerText.indexOf('2')>=0 && !document.querySelector('#prApply')",timeout=8000); time.sleep(.5)
    r=sql(f"SELECT s.rate,s.wrate FROM item_sizes s WHERE s.company_id='{cid}' ORDER BY s.rate")
    check(r.replace("\t"," ").split("\n")[0].startswith("15") and "70" in r,"prices updated in DB: "+r.replace("\n"," | "))
    check(pg.evaluate("ITEMS[0].sizes.map(z=>z.rate).join()")=="15,70","UI catalog refreshed")
    check(not errs,"no JS errors: "+str(errs[:2]))
    b.close()
print("PASS",ok)
