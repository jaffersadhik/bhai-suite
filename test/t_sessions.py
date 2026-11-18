import sys,json,uuid,subprocess,urllib.request,urllib.error
sys.path.insert(0,'/home/claude/w8/src/bhai-mobile-billing/test')
from api import *
BASE="http://127.0.0.1:8099/api/"; ok=0
def check(c,m):
    global ok
    assert c,m; ok+=1; print("  ✓",m)
def sql(q): return subprocess.run(["mysql","-ubmb","-pbmb123","bmb","-N","-e",q],capture_output=True,text=True).stdout.strip()
def bearer(tok,path,method="GET",body=None):
    req=urllib.request.Request(BASE+path,method=method,data=None if body is None else json.dumps(body).encode(),headers={"Content-Type":"application/json","Authorization":"Bearer "+tok})
    try: r=urllib.request.urlopen(req,timeout=20); return r.status,json.loads(r.read() or b"{}"),dict(r.headers)
    except urllib.error.HTTPError as e: return e.code,json.loads(e.read() or b"{}"),dict(e.headers)
def raw_login(u,p="pass12345"):
    req=urllib.request.Request(BASE+"login",method="POST",data=json.dumps({"user":u,"pass":p,"remember":True}).encode(),headers={"Content-Type":"application/json","User-Agent":"Mozilla/5.0 (Linux; Android 14) Chrome/120.0 Mobile Safari/537.36","X-Forwarded-For":"203.0.113.7"})
    try: r=urllib.request.urlopen(req,timeout=20); return r.status,json.loads(r.read())
    except urllib.error.HTTPError as e: return e.code,json.loads(e.read())
sql("UPDATE users SET max_sessions=50, idle_minutes=0 WHERE username='admin'")
adm,_=login("admin"); sfx=uuid.uuid4().hex[:5]
st,d=adm.call("admin/company","POST",{"name":"SES "+sfx,"phone":"9876543210","billPrefix":"SE"},expect=200); cid=d["company"]["id"]
st,u=adm.call("admin/user","POST",{"user":"a"+sfx,"name":"A","type":"owner","companyId":cid,"password":"pass12345"},expect=200); uid=u["user"]["id"]
check(u["user"]["maxSessions"]==2 and u["user"]["idleMinutes"]==10,"new user defaults: 2 sessions, 10 min idle")
# access token response
st,r=raw_login("a"+sfx); check(st==200 and r["token_type"]=="Bearer" and r["access_token"]==r["token"] and r["expires_in"]>0,"login returns access_token / Bearer / expires_in")
t1=r["access_token"]
st,me,h=bearer(t1,"me"); check(st==200 and me["user"]["user"]=="a"+sfx and me["idleMinutes"]==10,"Authorization: Bearer works")
st,e,h=bearer("bad"*20,"me"); check(st==401 and h.get("WWW-Authenticate")=="Bearer","bad token -> 401 + WWW-Authenticate: Bearer")
st,r2=raw_login("a"+sfx); t2=r2["access_token"]; check(st==200,"second session allowed")
st,r3=raw_login("a"+sfx); check(st==409 and "2" in r3["error"],"third session blocked: "+r3["error"][:60])
# old header still works
st,_=Api(t2).call("me",expect=200); check(True,"legacy X-Auth still accepted")
# admin list
st,s=adm.call("admin/sessions",expect=200); mine=[x for x in s["sessions"] if x["userId"]==uid]
check(len(mine)==2 and mine[0]["device"]=="Chrome · Android" and mine[0]["ip"]=="203.0.113.7","admin sees 2 sessions with device + ip: %s %s"%(mine[0]["device"],mine[0]["ip"]))
check(any(x["current"] for x in s["sessions"]),"admin's own session flagged current")
# kill one
sid=mine[0]["sid"]; st,k=adm.call("admin/sessions/kill","POST",{"sid":sid},expect=200)
check(k["killed"]==1 and len([x for x in k["sessions"] if x["userId"]==uid])==1,"admin killed one session")
dead=[t for t in (t1,t2) if bearer(t,"me")[0]==401]; check(len(dead)==1,"killed token now 401")
live=[t for t in (t1,t2) if bearer(t,"me")[0]==200][0]
st,e=adm.call("admin/sessions/kill","POST",{"sid":[x for x in k["sessions"] if x["current"]][0]["sid"]},expect=400); check(True,"cannot kill own session")
st,r4=raw_login("a"+sfx); check(st==200,"slot freed after kill -> login ok")
st,k=adm.call("admin/sessions/kill","POST",{"userId":uid},expect=200); check(k["killed"]==2 and not [x for x in k["sessions"] if x["userId"]==uid],"kill all sessions of a user")
# non-admin cannot list
st,r=raw_login("a"+sfx); st2,e,_=bearer(r["access_token"],"admin/sessions"); check(st2==403,"non-admin cannot view sessions")
# idle timeout
st,u2=adm.call("admin/user","POST",{"user":"b"+sfx,"name":"B","type":"owner","companyId":cid,"password":"pass12345","maxSessions":5,"idleMinutes":1},expect=200)
st,r=raw_login("b"+sfx); tb=r["access_token"]; check(bearer(tb,"me")[0]==200,"idle-limited user logs in")
sql("UPDATE auth_tokens SET last_seen=DATE_SUB(CONVERT_TZ(NOW(),'+00:00','+05:30'),INTERVAL 3 MINUTE) WHERE user_id='%s'"%u2["user"]["id"])
st,e,_=bearer(tb,"me"); check(st==401 and "logout" in e["error"].lower(),"idle > limit -> 401: "+e["error"][:50])
st,r=raw_login("b"+sfx); tb=r["access_token"]
sql("UPDATE auth_tokens SET last_seen=DATE_SUB(CONVERT_TZ(NOW(),'+00:00','+05:30'),INTERVAL 50 SECOND) WHERE user_id='%s'"%u2["user"]["id"])
st,p,_=bearer(tb,"ping","POST",{}); check(st==200,"ping keeps session alive")
seen=sql("SELECT TIMESTAMPDIFF(SECOND,last_seen,CONVERT_TZ(NOW(),'+00:00','+05:30')) FROM auth_tokens WHERE user_id='%s'"%u2["user"]["id"]); check(int(seen)<10,"ping refreshed last_seen (%ss)"%seen)
# idle 0 = never
sql("UPDATE users SET idle_minutes=0 WHERE id='%s'"%u2["user"]["id"]); sql("UPDATE auth_tokens SET last_seen=DATE_SUB(CONVERT_TZ(NOW(),'+00:00','+05:30'),INTERVAL 5 HOUR) WHERE user_id='%s'"%u2["user"]["id"])
check(bearer(tb,"me")[0]==200,"idle 0 = never auto-logout")
# lowering max sessions evicts extras
for i in range(3): raw_login("b"+sfx)
adm.call("admin/user","POST",{"id":u2["user"]["id"],"user":"b"+sfx,"name":"B","type":"owner","companyId":cid,"maxSessions":1,"idleMinutes":0},expect=200)
n=sql("SELECT COUNT(*) FROM auth_tokens WHERE user_id='%s'"%u2["user"]["id"]); check(n=="1","lowering the limit trims sessions to 1")
# validation
adm.call("admin/user","POST",{"user":"c"+sfx,"name":"C","type":"owner","companyId":cid,"maxSessions":21},expect=400); check(True,"maxSessions>20 rejected")
adm.call("admin/user","POST",{"user":"c"+sfx,"name":"C","type":"owner","companyId":cid,"idleMinutes":2000},expect=400); check(True,"idleMinutes>1440 rejected")
print("ALL",ok,"OK")
