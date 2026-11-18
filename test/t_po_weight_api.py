import sys,json,uuid
sys.path.insert(0,'/home/claude/w5/test')
from api import *
ok=0
def check(c,msg):
    global ok
    assert c,msg; ok+=1; print("  ✓",msg)
adm,_=login("admin"); sfx=uuid.uuid4().hex[:4]
st,d=adm.call("admin/company","POST",{"name":"POW "+sfx,"phone":"9876543210","billPrefix":"PW"},expect=200); cid=d["company"]["id"]
adm.call("admin/open","POST",{"companyId":cid},expect=200)
cat={"version":0,"cats":[{"id":"g1","ta":"குரூப்","en":"Grp","emoji":"🛍️","color":"#e8eef0"}],"items":[
 {"id":"i1","cat":"g1","ta":"சட்டை","en":"Shirt","sizes":[{"size":"M","rate":200}]},
 {"id":"w1","cat":"g1","ta":"பாதாம்","en":"Almond","by":"weight","unit":"kg","sizes":[{"size":"1 kg","rate":900}]},
 {"id":"w2","cat":"g1","ta":"குங்குமப்பூ","en":"Saffron","by":"weight","unit":"g","sizes":[{"size":"1 g","rate":400}]}]}
adm.call("catalog","PUT",cat,expect=200)
st,br=adm.call("po?status=all",expect=200); print("branch info:",br.get("branch"),br.get("branches"))
bid=(br.get("branches") or [{}])[0].get("id")
def po(lines,expect=200,**k):
    body={"branch":bid,"supplier":{"name":"Sup "+sfx,"phone":"9000000000"},"orderDate":"2026-11-16","lines":lines}; body.update(k)
    return adm.call("po","POST",body,expect=expect)
L=[{"itemId":"i1","name":"சட்டை","size":"M","qty":10,"unitCost":100,"gstPct":5},
   {"itemId":"w1","name":"பாதாம்","size":"1 kg","by":"weight","qty":25500,"unitCost":700,"gstPct":0},
   {"itemId":"w2","name":"குங்குமப்பூ","size":"1 g","by":"weight","qty":2000,"unitCost":1.5,"gstPct":0},
   {"itemId":"","name":"Loose Dates","size":"","by":"weight","unit":"kg","qty":3500,"unitCost":200,"gstPct":0},
   {"itemId":"","name":"Hanger","size":"","qty":12,"unitCost":10,"gstPct":18}]
st,r=po(L); p=r["po"]
check(p["subtotal"]==1000+17850+3000+700+120,"subtotal counts weight by kg/g: %s"%p["subtotal"])
check(p["gst"]==round(1000*.05+120*.18,2),"gst only on taxed lines: %s"%p["gst"])
ls={l["name"]:l for l in p["lines"]}
check(ls["பாதாம்"]["by"]=="weight" and ls["பாதாம்"]["unit"]=="kg" and ls["பாதாம்"]["qty"]==25500,"kg line stored in grams, unit kg")
check(ls["குங்குமப்பூ"]["unit"]=="g" and ls["குங்குமப்பூ"]["amount"]==3000,"g-unit item priced per gram")
check(ls["Loose Dates"]["by"]=="weight" and ls["Loose Dates"]["amount"]==700,"custom weight line (not in catalog)")
check("by" not in ls["சட்டை"] and ls["சட்டை"]["amount"]==1000,"count line unchanged")
check(p["qty"]==22 and p["wtG"]==31000,"totals split: pieces %s, grams %s"%(p["qty"],p["wtG"]))
# validation
st,e=po([{"itemId":"w1","name":"பாதாம்","size":"1 kg","qty":5,"unitCost":700,"gstPct":0}],expect=400); check(True,"weight item sent as pieces -> 400: "+e.get("error",e.get("message","")))
st,e=po([{"itemId":"w1","name":"பாதாம்","size":"1 kg","by":"weight","qty":1500.5,"unitCost":700,"gstPct":0}],expect=400); check(True,"fractional grams -> 400")
st,e=po([{"itemId":"i1","name":"சட்டை","size":"M","by":"weight","qty":1000,"unitCost":700,"gstPct":0}],expect=400); check(True,"count item as weight -> 400")
st,e=po([{"itemId":"w1","name":"பாதாம்","size":"1 kg","by":"weight","qty":10000000,"unitCost":700,"gstPct":0}],expect=400); check(True,"too heavy -> 400")
# list view
st,lst=adm.call("po?status=all",expect=200); h=[o for o in lst["orders"] if o["id"]==p["id"]][0]
check(h["qty"]==22 and h["wtG"]==31000 and h["receivedWtG"]==0,"list head carries pieces + grams")
# edit keeps weight
bodyE={"id":p["id"],"supplier":{"id":p["supplierId"]},"orderDate":"2026-11-16","lines":L}
st,r=adm.call("po","POST",bodyE,expect=200); check(r["po"]["wtG"]==31000,"edit keeps weight lines")
# receive: partial grams
def rec(lines,expect=200): return adm.call("po/%d/receive"%p["id"],"POST",{"lines":lines,"updateCost":True},expect=expect)
def stock():
    st,s=adm.call("stock",expect=200); return s
ln={l["name"]:l["line"] for l in r["po"]["lines"]}
st,rr=rec([{"line":ln["பாதாம்"],"qty":10000},{"line":ln["சட்டை"],"qty":4},{"line":ln["குங்குமப்பூ"],"qty":500}]); q=rr["po"]
check(q["status"]=="partial" and q["receivedWtG"]==10500 and q["receivedQty"]==4,"partial receive: status partial, %s g, %s pcs"%(q["receivedWtG"],q["receivedQty"]))
s=stock(); print("stock sample:",json.dumps(s,ensure_ascii=False)[:300])
sk={(x["item"],x["size"]):x["qty"] for x in s["stock"]}
check(sk.get(("w1","1 kg"))==10000 and sk.get(("w2","1 g"))==500 and sk.get(("i1","M"))==4,"stock: grams for weight items, pieces for count: %s"%sk)
st,e=rec([{"line":ln["பாதாம்"],"qty":99999}],expect=400); check(True,"over-receive grams -> 400")
st,rr=rec([{"line":ln["பாதாம்"],"qty":15500},{"line":ln["சட்டை"],"qty":6},{"line":ln["குங்குமப்பூ"],"qty":1500},{"line":ln["Loose Dates"],"qty":3500},{"line":ln["Hanger"],"qty":12}]); q=rr["po"]
check(q["status"]=="received" and q["receivedWtG"]==31000,"full receive -> received")
sk={(x["item"],x["size"]):x["qty"] for x in stock()["stock"]}
check(sk[("w1","1 kg")]==25500,"stock w1 25500 g")
print("ALL",ok,"OK")
