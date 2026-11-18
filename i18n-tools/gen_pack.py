#!/usr/bin/env python3
"""Pre-build a language pack through the app's own online translator (needs the server to reach the internet).
   python3 i18n-tools/gen_pack.py ar http://localhost:8080
   Every Tamil phrase of i18n/en.json (with its English as the better source) is translated and written to i18n/<code>.json.
   Not required: the app translates missing phrases on demand and caches them in the `translations` table; a pack just makes the first
   screen appear instantly and works offline."""
import sys,json,urllib.request,pathlib
code=sys.argv[1]; base=(sys.argv[2] if len(sys.argv)>2 else "http://localhost:8080").rstrip("/")
d=pathlib.Path(__file__).resolve().parent.parent/"bhai-mobile-billing/src/main/resources/static/i18n"
en=json.load(open(d/"en.json",encoding="utf-8")); out={}
try: out=json.load(open(d/(code+".json"),encoding="utf-8"))
except Exception: pass
keys=[k for k in en if k not in out and len(k)<=300]
for i in range(0,len(keys),100):
    items=[{"s":k,"e":en[k]} for k in keys[i:i+100]]
    rq=urllib.request.Request(base+"/api/translate",data=json.dumps({"to":[code],"items":items}).encode(),headers={"Content-Type":"application/json"})
    m=json.loads(urllib.request.urlopen(rq,timeout=120).read())["map"].get(code,{}); out.update(m); print(i+len(items),"/",len(keys),"got",len(m),flush=True)
    import time; time.sleep(1.2)   # stay under the server's rate limit
open(d/(code+".json"),"w",encoding="utf-8").write(json.dumps(out,ensure_ascii=False,separators=(",",":")))
print("written",code+".json",len(out),"phrases")
