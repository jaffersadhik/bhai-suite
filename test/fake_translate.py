"""Stand-in for the online translator (same URL shape as the free Google endpoint) so tests run without internet.
   python3 fake_translate.py [port]   - answers [[["<translated>","<source>"]],null,"<sl>"]; counts hits at /count"""
import sys,json,hashlib,urllib.parse
from http.server import BaseHTTPRequestHandler,HTTPServer
D={"ஆப்பிள்":"Apple","குரூப்":"Group","பழங்கள்":"Fruits","அரிசி":"Rice","மளிகை":"Grocery"}
HITS=[0]
class H(BaseHTTPRequestHandler):
    def log_message(s,*a): pass
    def do_GET(s):
        u=urllib.parse.urlparse(s.path); q=urllib.parse.parse_qs(u.query)
        if u.path=="/count": s.send_response(200); s.end_headers(); s.wfile.write(str(HITS[0]).encode()); return
        HITS[0]+=1
        t=q.get("q",[""])[0]; tl=q.get("tl",["en"])[0]
        out=D.get(t) if tl=="en" and t in D else (tl.upper()+"_"+hashlib.md5(t.encode()).hexdigest()[:5])
        if tl=="ar": out="ع"+out
        body=json.dumps([[[out,t,None,None,10]],None,q.get("sl",["ta"])[0]],ensure_ascii=False).encode()
        s.send_response(200); s.send_header("Content-Type","application/json; charset=UTF-8"); s.end_headers(); s.wfile.write(body)
HTTPServer(("127.0.0.1",int(sys.argv[1]) if len(sys.argv)>1 else 8191),H).serve_forever()
