import sys,subprocess,urllib.request,json
sys.path.insert(0,'/home/claude/w9/in/bhai-mobile-billing/test')
from api import *
ok=0
def check(c,m):
    global ok
    assert c,m; ok+=1; print("  ✓",m)
def hits(): return int(urllib.request.urlopen("http://127.0.0.1:8191/count").read())
def tr(to,items):
    st,d=Api().call("translate","POST",{"to":to,"items":items}); return st,d
st,d=tr(["en"],[{"s":"ஆப்பிள்"},{"s":"மளிகை"}]); check(st==200 and d["map"]["en"]["ஆப்பிள்"]=="Apple","Tamil master data -> English (no login needed)")
h0=hits(); st,d=tr(["en"],[{"s":"ஆப்பிள்"},{"s":"மளிகை"}]); check(hits()==h0 and d["map"]["en"]["மளிகை"]=="Grocery","second time comes from the translations table (no online call)")
st,d=tr(["ar"],[{"s":"அரிசி","e":"Rice"}]); check(d["map"]["ar"]["அரிசி"].startswith("ع"),"Arabic target")
st,d=tr(["en"],[{"s":"hello world"}]); check(d["map"]["en"]=={},"arbitrary English text is NOT translated (no open proxy)")
st,d=tr(["ar"],[{"s":"Admin Panel"}]); check("Admin Panel" in d["map"]["ar"],"English app label can be translated: "+str(d["map"]["ar"])[:40])
st,d=tr(["ta"],[{"s":"x"}]); check(st==400,"ta target refused")
st,d=tr(["en"],[{"s":"அ"}]*200); check(st==400,"more than 150 texts refused")
print("rows",subprocess.run(["mysql","-ubmb","-pbmb123","bmb","-N","-e","select count(*) from translations"],capture_output=True,text=True).stdout.strip())
n=0
for i in range(130):
    st,d=tr(["en"],[{"s":"அ"}]); n+= st==429
check(n>=1,"rate limit kicks in (429 x%d)"%n)
print("PASS",ok)
