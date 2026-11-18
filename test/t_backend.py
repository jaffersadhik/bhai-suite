import sys,json,uuid,time
sys.path.insert(0,'/home/claude/w4/test')
from api import *
ok=0
def check(c,msg):
    global ok
    assert c,msg; ok+=1; print("  ✓",msg)
adm,_=login("admin")
# password change forced? default must_change; still usable
sfx=uuid.uuid4().hex[:5]
st,d=adm.call("admin/company","POST",{"name":"Test Co "+sfx,"phone":"9876543210","billPrefix":"TST","rateChange":False,"orderNoStock":False},expect=200)
cid=d["company"]["id"]; check(d["company"]["orderNoStock"] is False,"company has orderNoStock=false")
st,d=adm.call("admin/user","POST",{"user":"s"+sfx,"name":"Sales One","type":"sales","companyId":cid,"password":"pass12345"})
print(st,d if st!=200 else "")
sales,sd=login("s"+sfx,"pass12345")
# catalog
cat={"version":0,"cats":[{"id":"g1","ta":"குரூப்","en":"Grp","emoji":"🛍️","color":"#e8eef0"}],
 "items":[{"id":"i1","cat":"g1","ta":"சட்டை","en":"Shirt","sizes":[{"size":"M","rate":100},{"size":"L","rate":120}]},
          {"id":"w1","cat":"g1","ta":"பாதாம்","en":"Almond","by":"weight","unit":"kg","sizes":[{"size":"1 kg","rate":800}]}]}
st,d=adm.call("admin/open","POST",{"companyId":cid},expect=200)
st,d=adm.call("catalog","PUT",cat,expect=200)
st,dd=sales.call("data",expect=200); v=dd["catalog"]["version"]; check(v>0,"catalog version "+str(v))
# reserve numbers
dev="dev"+sfx+"abcd"
st,r=sales.call("offline/reserve","POST",{"deviceId":dev,"bills":5,"cancels":3},expect=200)
print(r); b0,b1=r["bills"]; c0,c1=r["cancels"]; check(b1-b0==4 and c1-c0==2,"reserved ranges")
# normal online bill (no client id) -> next seq after block
st,r2=sales.call("bills","POST",{"phone":"9000000001","payMode":None,"lines":[{"id":"i1","name":"சட்டை","size":"M","qty":1,"rate":100}]},expect=200)
check(r2["bill"]["seq"]==b1+1,"normal bill seq after block: %s"%r2["bill"]["no"])
# offline bill with reserved number + clientId
cidb=str(uuid.uuid4()).replace("-","")[:24]
body={"clientId":cidb,"deviceId":dev,"offline":True,"reservedSeq":b0,"clientAt":"2026-11-14T10:00:00Z","catalogVersion":v,"phone":"9000000002","customer":"Ali","lines":[{"id":"i1","name":"சட்டை","en":"Shirt","size":"L","qty":2,"rate":120},{"id":"w1","name":"பாதாம்","size":"250 g","qty":1,"rate":200,"wt":250}]}
st,r3=sales.call("bills","POST",body,expect=200)
b=r3["bill"]; print(b["no"],b["at"]); check(b["seq"]==b0,"reserved number used: "+b["no"]); check(b["total"]==440,"total 440")
check(b["at"].startswith("2026-11-14"),"client time kept (%s)"%b["at"])
# same again -> duplicate
st,r4=sales.call("bills","POST",body,expect=200); check(r4["bill"]["no"]==b["no"] and r4["bill"].get("duplicate"),"duplicate returns same bill")
st,l=sales.call("bills",expect=200); check(sum(1 for x in l["bills"] if x["no"]==b["no"])==1,"only one saved")
# stock moved once: 
st,sk=adm.call("stock",expect=200)
# reserved seq of someone else's device refused -> falls to normal sequence
body2=dict(body); body2["clientId"]=str(uuid.uuid4()).replace("-","")[:24]; body2["deviceId"]="otherdevice1234"; body2["reservedSeq"]=b0+1
st,r5=sales.call("bills","POST",body2,expect=200); check(r5["bill"]["seq"]>b1+1,"foreign reservation ignored: "+r5["bill"]["no"])
# offline w/ stale price list and rate change disabled: custom rate accepted
body3=dict(body); body3["clientId"]=str(uuid.uuid4()).replace("-","")[:24]; body3["reservedSeq"]=b0+1; body3["catalogVersion"]=v-1; body3["lines"]=[{"id":"i1","name":"சட்டை","size":"M","qty":1,"rate":90}]
st,r6=sales.call("bills","POST",body3,expect=200); check(r6["bill"]["total"]==90 and r6["bill"]["seq"]==b0+1,"stale-version offline bill with old rate accepted")
# same list version & changed rate -> refused (rate change off)
body4=dict(body3); body4["clientId"]=str(uuid.uuid4()).replace("-","")[:24]; body4["reservedSeq"]=b0+2; body4["catalogVersion"]=v
st,r7=sales.call("bills","POST",body4); check(st==400,"same-version offline bill with changed rate refused (%s)"%st)
# cancel bill offline against the reserved bill b
cc=str(uuid.uuid4()).replace("-","")[:24]
cb={"clientId":cc,"deviceId":dev,"offline":True,"reservedSeq":c0,"clientAt":"2026-11-14T11:00:00Z","catalogVersion":v,"reason":"return","refBill":b["no"],"phone":"9000000002","lines":[{"id":"i1","name":"சட்டை","size":"L","qty":1,"rate":120},{"id":"w1","name":"பாதாம்","size":"250 g","qty":1,"rate":200,"wt":250}]}
st,r8=sales.call("cancel-bills","POST",cb,expect=200); cx=r8["cancel"]; print(cx["no"]); check(cx["no"].endswith("C%04d"%c0),"cancel reserved number "+cx["no"])
st,r9=sales.call("cancel-bills","POST",cb,expect=200); check(r9["cancel"]["no"]==cx["no"] and r9["cancel"].get("duplicate"),"cancel duplicate")
# cancel more than left -> 400
cb2=dict(cb); cb2["clientId"]=str(uuid.uuid4()).replace("-","")[:24]; cb2["reservedSeq"]=c0+1; cb2["lines"]=[{"id":"i1","name":"சட்டை","size":"L","qty":2,"rate":120}]
st,r10=sales.call("cancel-bills","POST",cb2); check(st==400,"over-cancel refused: %s"%r10)
# weight stock restored: stock of w1 in grams: -250 then +250
st,sk=adm.call("stock",expect=200); print([ (x.get("id"),x.get("size"),x.get("qty")) for x in sk.get("stock",[])][:6])
# bills/for-cancel returns wt
st,fc=sales.call("bills/for-cancel?no="+b["no"],expect=200); wl=[l for l in fc["bill"]["lines"] if l["id"]=="w1"][0]; check(wl.get("wt")==250 and wl["left"]==0,"for-cancel gives wt and left=0 for weight line")
# sync pull
st,sp=sales.call("sync?cv=%d"%v,expect=200); check(sp.get("catalogSame") and "catalog" not in sp,"sync: catalog unchanged")
check(any(x["no"]==b["no"] for x in sp["bills"]),"sync has bills"); check(any(x["no"]==cx["no"] for x in sp["cancels"]),"sync has cancels")
bl=[x for x in sp["bills"] if x["no"]==b["no"]][0]; check(bl["lines"][0].get("id")=="i1" and bl["lines"][1].get("wt")==250,"sync bill lines carry id + wt")
st,sp2=sales.call("sync?cv=0",expect=200); check("catalog" in sp2,"sync with old version sends catalog")
# order no stock
st,sh=adm.call("shop-settings","POST",{},expect=None)
# takeOrder: no stock & flag off -> refuse
o={"name":"Cust","phone":"9111111111","mode":"pickup","lines":[{"id":"i1","name":"சட்டை","size":"M","qty":3,"rate":100}]}
st,r=sales.call("orders","POST",o); check(st==409,"order without stock refused when flag off: %s"%r.get("error"))
adm.call("admin/company","POST",{"id":cid,"name":"Test Co "+sfx,"phone":"9876543210","billPrefix":"TST","orderNoStock":True},expect=200)
st,r=sales.call("orders","POST",o); check(st==200 and r["order"]["lines"][0].get("short")==3,"order accepted w/ flag on, line marked short: %s"%(r.get("error") or r["order"]["lines"][0]))
# online shop
st,co=adm.call("data",expect=200); code=co["company"]["shopSlug"]
sh=Api()
st,s1=sh.call("shop/"+code,expect=200); check(s1["company"]["orderNoStock"] is True,"shop exposes orderNoStock")
br=s1["branches"][0]["id"]
so={"name":"Web","phone":"9222222222","mode":"pickup","branch":br,"lines":[{"itemId":"i1","size":"M","qty":2}]}
st,r=sh.call("shop/"+code+"/orders","POST",so); check(st==200,"shop order w/o stock allowed when flag on: %s"%r)
adm.call("admin/company","POST",{"id":cid,"name":"Test Co "+sfx,"phone":"9876543210","billPrefix":"TST","orderNoStock":False},expect=200)
so["phone"]="9333333333"
st,r=sh.call("shop/"+code+"/orders","POST",so); check(st==409,"shop order w/o stock refused when flag off: %s"%r.get("error"))
print("ALL OK",ok)
