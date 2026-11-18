import json,urllib.request,urllib.error,sys,atexit
_TOKS=[]
def _bye():
    for t in _TOKS:
        try: Api(t).call("logout","POST")
        except Exception: pass
atexit.register(_bye)
BASE="http://127.0.0.1:8099/api/"
class Api:
    def __init__(s,tok=None): s.tok=tok
    def call(s,path,method="GET",body=None,expect=None):
        req=urllib.request.Request(BASE+path,method=method,data=None if body is None else json.dumps(body).encode(),headers={"Content-Type":"application/json",**({"X-Auth":s.tok} if s.tok else {})})
        try:
            r=urllib.request.urlopen(req,timeout=20); st=r.status; d=json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            st=e.code; d=json.loads(e.read() or b"{}")
        if expect is not None and st!=expect: raise AssertionError(f"{method} {path}: HTTP {st} {d}")
        return st,d
def login(u,p="admin1234",tok_only=False):
    st,d=Api().call("login","POST",{"user":u,"pass":p,"remember":True},expect=200); _TOKS.append(d["token"]); return Api(d["token"]),d
