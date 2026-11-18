import sys,uuid,time,subprocess
sys.path.insert(0,'/home/claude/w14/in/bhai-mobile-billing/test')
from api import *
from playwright.sync_api import sync_playwright
ok=0
def check(c,m):
    global ok
    assert c,m; ok+=1; print("  ✓",m)
subprocess.run(["mysql","-ubmb","-pbmb123","bmb","-e","delete from auth_tokens"])
sfx=uuid.uuid4().hex[:5]; sysu,_=login("admin")
sysu.call("admin/user","POST",{"user":"ad"+sfx,"name":"Adm","type":"admin","password":"pass1234"},expect=200)
def chips(user,pw):
    with sync_playwright() as p:
        b=p.chromium.launch(args=["--no-sandbox"]); pg=b.new_context(viewport={"width":420,"height":900}).new_page(); errs=[]
        pg.on("pageerror",lambda e:errs.append(str(e)))
        pg.goto("http://127.0.0.1:8099/"); pg.wait_for_selector("#lUser"); pg.fill("#lUser",user); pg.fill("#lPass",pw); pg.click("#lBtn")
        pg.wait_for_function("document.body.classList.contains('authed')",timeout=15000); pg.wait_for_function("adminData && adminData.users",timeout=15000); time.sleep(1)
        pg.evaluate("openUser(null)") if pg.evaluate("typeof openUser")=="function" else pg.click("#addUser"); pg.wait_for_selector("[data-utype]"); time.sleep(.3)
        t=[x.get_attribute("data-utype") for x in pg.locator("[data-utype]").all()]
        qa=pg.locator("#openQa").count(); inv=pg.locator("#openInv").count(); print("QA",qa,"INV",inv,pg.evaluate("isSystem()")); b.close(); return t,qa,inv,errs
t,qa,inv,e=chips("admin","admin1234"); check("admin" in t and "system" in t,"System user sees Admin + System type buttons"); check(qa>=1 and inv==1,"System sees Tests + Software Invoices tiles"); check(not e,"no JS errors")
t,qa,inv,e=chips("ad"+sfx,"pass1234"); check("admin" not in t and "system" not in t and "sales" in t,"Admin does NOT get Admin / System type buttons: %s"%t); check(qa==0 and inv==0,"Admin has no Tests / Invoices tiles"); check(not e,"no JS errors")
print("PASS",ok)
