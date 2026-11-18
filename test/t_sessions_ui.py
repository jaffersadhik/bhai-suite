import sys,uuid,time
sys.path.insert(0,'/home/claude/w8/src/bhai-mobile-billing/test')
from api import *
from playwright.sync_api import sync_playwright
BASE="http://127.0.0.1:8099/"; ok=0
def check(c,m):
    global ok
    assert c,m; ok+=1; print("  ✓",m)
sfx=uuid.uuid4().hex[:5]
adm,_=login("admin")
st,d=adm.call("admin/company","POST",{"name":"SUI "+sfx,"phone":"9876543210","billPrefix":"SU"},expect=200); cid=d["company"]["id"]
errs=[]
with sync_playwright() as p:
    b=p.chromium.launch(args=["--no-sandbox"])
    def newpage():
        ctx=b.new_context(viewport={"width":420,"height":900},service_workers="allow"); pg=ctx.new_page()
        pg.on("pageerror",lambda e:errs.append(str(e))); pg.on("dialog",lambda d:d.accept()); return pg
    def ui_login(pg,u,pw="admin1234"):
        pg.goto(BASE); pg.wait_for_selector("#lUser"); pg.fill("#lUser",u); pg.fill("#lPass",pw); pg.click("#lBtn")
    A=newpage(); ui_login(A,"admin","admin1234"); A.wait_for_function("document.body.classList.contains('authed')",timeout=15000)
    A.wait_for_function("adminData && adminData.users",timeout=15000); time.sleep(.5)
    A.evaluate("openUserForm(null); 0"); A.wait_for_selector("#uMaxS")
    check(A.input_value("#uMaxS")=="2" and A.input_value("#uIdle")=="10","user form defaults: 2 sessions, 10 min idle")
    A.click("[data-utype=owner]"); A.select_option("#uComp",cid); A.fill("#uName","UiUser"); A.fill("#uUser","ui"+sfx); A.fill("#uIdle","3"); A.click("#userSave")
    A.wait_for_selector("#credTxt",timeout=10000)
    u=[x for x in adm.call("admin/overview",expect=200)[1]["users"] if x["user"]=="ui"+sfx][0]
    check(u["maxSessions"]==2 and u["idleMinutes"]==3,"saved from the form: 2 sessions, 3 min idle")
    # two browsers log in, third is refused
    U1=newpage(); ui_login(U1,"ui"+sfx); U1.wait_for_function("document.body.classList.contains('authed')",timeout=15000)
    U2=newpage(); ui_login(U2,"ui"+sfx); U2.wait_for_function("document.body.classList.contains('authed')",timeout=15000)
    U3=newpage(); ui_login(U3,"ui"+sfx); time.sleep(1.5)
    check(not U3.evaluate("document.body.classList.contains('authed')") and "2" in U3.inner_text("#lErr"),"3rd browser refused: "+U3.inner_text("#lErr")[:50])
    # the session carries the idle limit; the browser uses an access token
    check(U1.evaluate("me.idleMinutes")==3,"client receives idle limit (3 min)")
    sent=U1.evaluate("""fetch('/api/me',{headers:{Authorization:'Bearer '+token}}).then(r=>r.status)"""); check(sent==200,"Bearer header accepted from browser")
    # admin sessions sheet
    A.evaluate("closeSheet(); 0"); A.evaluate("openSessions(); 0"); A.wait_for_selector("[data-sesskill]",timeout=8000)
    txt=A.inner_text("#sheetBody"); check("UiUser" in txt and "2/2" in txt,"admin sessions sheet lists UiUser with 2/2")
    A.screenshot(path="/tmp/sess.png")
    n0=A.locator("[data-sesskill]").count()
    A.locator("[data-sesskill]").first.click(); time.sleep(1.5)
    check(A.locator("[data-sesskill]").count()==n0-1,"admin ended one session from the sheet (%d -> %d)"%(n0,n0-1))
    # whoever was killed gets the login screen on its next call
    for pg in (U1,U2):
        pg.evaluate("api('me').catch(()=>0); 0")
    time.sleep(1.5)
    out=[pg.evaluate("!document.body.classList.contains('authed')") for pg in (U1,U2)]
    check(sorted(out)==[False,True],"exactly one of the two browsers was logged out")
    live=U1 if not out[0] else U2
    # idle auto logout in the browser
    live.evaluate("lastAct=Date.now()-4*60000; idleCheck(); 0"); time.sleep(1.5)
    check(live.evaluate("!document.body.classList.contains('authed')") and "3" in live.inner_text("#lErr"),"idle > 3 min -> auto logout with message")
    # activity keeps it alive
    ui_login(live,"ui"+sfx); live.wait_for_function("document.body.classList.contains('authed')",timeout=15000)
    live.evaluate("lastAct=Date.now()-2*60000; 0"); live.mouse.click(50,50); live.evaluate("idleCheck(); 0"); time.sleep(.8)
    check(live.evaluate("document.body.classList.contains('authed')"),"activity resets the idle timer")
    b.close()
check(not errs,"no JS errors %s"%errs)
print("ALL",ok,"OK")
