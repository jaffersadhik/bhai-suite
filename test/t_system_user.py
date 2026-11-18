import sys,uuid,subprocess
sys.path.insert(0,'/home/claude/w14/in/bhai-mobile-billing/test')
from api import *
ok=0
def check(c,m):
    global ok
    assert c,m; ok+=1; print("  ✓",m)
subprocess.run(["mysql","-ubmb","-pbmb123","bmb","-e","delete from auth_tokens"])
sfx=uuid.uuid4().hex[:5]
sysu,d=login("admin"); check(d["user"]["type"]=="system","existing admin became the System user")
def mk(un,t,**k):
    return sysu.call("admin/user","POST",dict({"user":un,"name":un,"type":t,"password":"pass1234"},**k),expect=200)[1]["user"]
a1=mk("a1"+sfx,"admin"); a2=mk("a2"+sfx,"admin"); check(a1["type"]=="admin","System creates Admin users")
s2=mk("s2"+sfx,"system"); check(s2["type"]=="system","System creates another System user")
A1,_=login("a1"+sfx,"pass1234"); A2,_=login("a2"+sfx,"pass1234")
st,c1=A1.call("admin/company","POST",{"name":"Co1 "+sfx,"phone":"9876543210","billPrefix":"C1"},expect=200); cid=c1["company"]["id"]
st,u1=A1.call("admin/user","POST",{"user":"s1"+sfx,"name":"S1","type":"sales","companyId":cid,"password":"pass1234"},expect=200); uid=u1["user"]["id"]
check(True,"Admin creates his own company + sales user")
st,r=A1.call("admin/user","POST",{"user":"x1"+sfx,"name":"X","type":"admin","password":"pass1234"}); check(st==403,"Admin cannot create an Admin user (%s)"%st)
st,r=A1.call("admin/user","POST",{"user":"x2"+sfx,"name":"X","type":"system","password":"pass1234"}); check(st==403,"Admin cannot create a System user (%s)"%st)
st,r=A1.call("admin/user","POST",{"id":a1["id"],"user":"a1"+sfx,"name":"A1 renamed","type":"system"}); check(st==403,"Admin cannot promote himself to System")
st,r=A1.call("admin/user","POST",{"id":a1["id"],"user":"a1"+sfx,"name":"A1 renamed","type":"admin"}); check(st==200,"Admin can edit his own profile")
st,o1=A1.call("admin/overview",expect=200); check([c["id"] for c in o1["companies"]]==[cid] and [u["id"] for u in o1["users"]]==[uid],"Admin 1 sees only his company + users")
st,o2=A2.call("admin/overview",expect=200); check(cid not in [c["id"] for c in o2["companies"]] and uid not in [u["id"] for u in o2["users"]],"Admin 2 does NOT see Admin 1's company / users")
st,r=A2.call("admin/open","POST",{"companyId":cid}); check(st==404,"Admin 2 cannot open Admin 1's company")
st,r=A2.call("admin/user","POST",{"id":uid,"user":"s1"+sfx,"name":"hack","type":"sales","companyId":cid}); check(st==404,"Admin 2 cannot edit Admin 1's user")
st,r=A2.call("admin/user/"+uid,"DELETE"); check(st==404,"Admin 2 cannot delete Admin 1's user")
st,r=A2.call("admin/user","POST",{"user":"y"+sfx,"name":"Y","type":"sales","companyId":cid,"password":"pass1234"}); check(st==404,"Admin 2 cannot add a user into Admin 1's company")
st,r=A2.call("admin/company","POST",{"id":cid,"name":"Stolen","phone":"9876543210"}); check(st==404,"Admin 2 cannot edit Admin 1's company")
st,r=A1.call("admin/invoices"); check(st==403,"Admin has no platform invoices (System only)")
st,r=A1.call("admin/tests/alert"); check(st==403,"Admin has no post-deploy tests (System only)")
st,so=sysu.call("admin/overview",expect=200); check(cid in [c["id"] for c in so["companies"]] and uid in [u["id"] for u in so["users"]],"System sees every company and user")
st,r=sysu.call("admin/open","POST",{"companyId":cid}); check(st==200,"System opens any company")
st,r=sysu.call("admin/invoices"); check(st==200,"System has platform invoices")
st,r=sysu.call("admin/user","POST",{"id":uid,"user":"s1"+sfx,"name":"S1 by sys","type":"sales","companyId":cid}); check(st==200,"System edits any user")
print("PASS",ok)
