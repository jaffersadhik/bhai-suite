import sys,json,uuid,subprocess
sys.path.insert(0,'/home/claude/w8/src/bhai-mobile-billing/test')
from api import *
ok=0
def check(c,m):
    global ok
    assert c,m; ok+=1; print("  ✓",m)
def sql(q): return subprocess.run(["mysql","-ubmb","-pbmb123","bmb","-N","-e",q],capture_output=True,text=True).stdout.strip()
sql("UPDATE users SET max_sessions=50, idle_minutes=0 WHERE username='admin'")
adm,_=login("admin"); sfx=uuid.uuid4().hex[:5]
st,d=adm.call("admin/company","POST",{"name":"PL "+sfx,"phone":"9876543210","billPrefix":"PL"},expect=200); cid=d["company"]["id"]
for t,n in (("owner","o"),("sales","s")): adm.call("admin/user","POST",{"user":n+sfx,"name":n,"type":t,"companyId":cid,"password":"pass12345"})
adm.call("admin/open","POST",{"companyId":cid},expect=200)
base="group,item,size,rate,wholesale,cost,sold_by,wt_unit\nGrp,Apple,1 pc,10,8,5,count,\nGrp,Apple,6 pc,50,45,30,count,\nGrp,Rice,,60,55,40,weight,kg\n"
st,r=adm.call("catalog/import","POST",{"csv":base,"replace":True},expect=200)
cat=r["catalog"]; ids={i["ta"]:i["id"] for i in cat["items"]}
cv0=sql(f"SELECT catalog_version FROM companies WHERE id='{cid}'")
# preview changes nothing
csv="item_id,group,item,size,rate,wholesale,cost\n%s,Grp,Apple,1 pc,12,,\n%s,Grp,Apple,6 pc,50,45,30\n%s,Grp,Rice,,65,,\nzzz,Grp,Ghost,1 pc,9,,\n"%(ids["Apple"],ids["Apple"],ids["Rice"])
st,p=adm.call("catalog/import","POST",{"csv":csv,"pricesOnly":True,"preview":True},expect=200)
check(p["changed"]==2 and p["unchanged"]==1 and p["skipped"]==1 and p["preview"],"preview: 2 changed, 1 unchanged, 1 skipped")
check(sql(f"SELECT catalog_version FROM companies WHERE id='{cid}'")==cv0,"preview does not bump catalog_version")
st,a=adm.call("catalog/import","POST",{"csv":csv,"pricesOnly":True},expect=200)
check(a["changed"]==2 and a["catalog"],"apply returns catalog")
ap=[i for i in a["catalog"]["items"] if i["ta"]=="Apple"][0]; s1=[z for z in ap["sizes"] if z["size"]=="1 pc"][0]
check(float(s1["rate"])==12 and float(s1["wrate"])==8,"rate changed, blank wholesale kept")
rc=[i for i in a["catalog"]["items"] if i["ta"]=="Rice"][0]; check(float(rc["sizes"][0]["rate"])==65,"weight item price changed")
check(len(a["catalog"]["items"])==2 and not any(i["ta"]=="Ghost" for i in a["catalog"]["items"]),"no new item created")
check(int(sql(f"SELECT catalog_version FROM companies WHERE id='{cid}'"))==int(cv0)+1,"catalog_version bumped")
# match by name without item_id
st,b=adm.call("catalog/import","POST",{"csv":"group,item,size,rate\nGrp,Apple,6 pc,55\n","pricesOnly":True},expect=200); check(b["changed"]==1,"match by group+item+size")
st,b=adm.call("catalog/import","POST",{"csv":"item,size,rate\nApple,1 pc,13\n","pricesOnly":True},expect=200); check(b["changed"]==1,"match by unique item name")
st,b=adm.call("catalog/import","POST",{"csv":"item_id,rate\n%s,-4\n"%ids["Apple"],"pricesOnly":True},expect=200); check(b["skipped"]==1 and b["changed"]==0,"negative rate rejected")
st,b=adm.call("catalog/import","POST",{"csv":"item_id,name\nx,y\n","pricesOnly":True}); check(st==400,"missing price columns -> 400")
# sales role forbidden
sl,_=login("s"+sfx,"pass12345"); st,_=sl.call("catalog/import","POST",{"csv":csv,"pricesOnly":True}); check(st==403,"sales role cannot import prices")
# language
ow,r=login("o"+sfx,"pass12345"); check(r["user"].get("lang","")=="" ,"new user lang empty (=English)")
st,l=ow.call("me/lang","POST",{"lang":"ta"},expect=200); check(l["lang"]=="ta","set lang ta")
st,m=ow.call("me/lang","POST",{"lang":"x1!"}); check(st==400,"invalid lang rejected")
ow2,r2=login("o"+sfx,"pass12345"); check(r2["user"]["lang"]=="ta","lang returned at next login")
st,me=ow2.call("me",expect=200); check(me["user"]["lang"]=="ta","lang in /me")
print("PASS",ok)
