import sys,uuid,time,urllib.parse,subprocess
sys.path.insert(0,'/home/claude/w10/in/bhai-mobile-billing/test')
from api import *
from playwright.sync_api import sync_playwright
BASE="http://127.0.0.1:8099/"; ok=0
def check(c,m):
    global ok
    assert c,m; ok+=1; print("  ✓",m)
subprocess.run(["mysql","-ubmb","-pbmb123","bmb","-e","delete from auth_tokens; UPDATE users SET max_sessions=50, idle_minutes=0 WHERE username='admin'"])
sfx=uuid.uuid4().hex[:5]; adm,_=login("admin")
st,d=adm.call("admin/company","POST",{"name":"Share Co "+sfx,"phone":"9876543210","billPrefix":"SH","address":"12 Main Road","city":"Trichy","gstin":"33ABCDE1234F1Z5","email":"shop@example.com","owner":"Mr Owner"},expect=200); cid=d["company"]["id"]
st,u=adm.call("admin/user","POST",{"user":"u"+sfx,"name":"Ameen","type":"sales","companyId":cid,"password":"firstpass1"},expect=200); uid=u["user"]["id"]
errs=[]
with sync_playwright() as p:
    b=p.chromium.launch(args=["--no-sandbox"]); pg=b.new_context(viewport={"width":420,"height":900}).new_page()
    pg.on("pageerror",lambda e:errs.append(str(e))); pg.on("dialog",lambda d:d.accept())
    pg.goto(BASE); pg.wait_for_selector("#lUser"); pg.fill("#lUser","admin"); pg.fill("#lPass","admin1234"); pg.click("#lBtn")
    pg.wait_for_function("document.body.classList.contains('authed')",timeout=15000); pg.wait_for_function("adminData && adminData.users",timeout=15000); time.sleep(1)
    pg.evaluate("window.__open=[]; window.open=function(u){ if(typeof u===\"string\"&&u.indexOf(\"wa.me\")>0) window.__open.push(u); return null}")
    pg.evaluate("document.body.dataset.x=1"); 
    check(pg.locator(f'[data-shareuser="{uid}"]').count()>=1,"📲 WhatsApp button on the user card")
    pg.evaluate(f"openShareUser('{uid}')"); pg.wait_for_selector("#shTxt"); time.sleep(.5)
    check(pg.input_value("#shPh")=="9876543210","number pre-filled with the company phone")
    pg.fill("#shPh","98 7654-3211"); pg.fill("#shPw","newpass99"); time.sleep(.3)
    txt=pg.inner_text("#shTxt"); print(txt)
    for w in ["Share Co "+sfx,"Username: u"+sfx,"Password: newpass99","12 Main Road","Trichy","33ABCDE1234F1Z5","9876543210","shop@example.com","Ameen",BASE]:
        check(w in txt,"message contains: "+w)
    # missing number -> not sent
    pg.fill("#shPh",""); pg.click("#shSend"); time.sleep(.6); print("OPEN",pg.evaluate("window.__open")); check(pg.evaluate("window.__open.length")==0,"no number -> nothing sent")
    pg.fill("#shPh","98 7654-3211"); pg.click("#shSend"); time.sleep(2)
    urls=pg.evaluate("window.__open"); check(len(urls)==1 and urls[0].startswith("https://wa.me/919876543211?text="),"opens wa.me/919876543211 : "+(urls[0][:40] if urls else ""))
    body=urllib.parse.unquote(urls[0].split("text=")[1]); check("Password: newpass99" in body and "Username: u"+sfx in body,"WhatsApp text has username + password")
    st,r=Api().call("login","POST",{"user":"u"+sfx,"pass":"newpass99"}); check(st==200,"new password really set on the user")
    st,r=Api().call("login","POST",{"user":"u"+sfx,"pass":"firstpass1"}); check(st==401,"old password no longer works")
    # second send in same session reuses the known password (no re-set)
    pg.evaluate(f"openShareUser('{uid}')"); pg.wait_for_selector("#shTxt"); check(pg.input_value("#shPw")=="newpass99","password remembered for this session")
    check(not errs,"no JS errors "+str(errs[:2])); b.close()
print("PASS",ok)
