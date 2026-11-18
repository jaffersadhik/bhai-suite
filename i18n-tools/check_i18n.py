#!/usr/bin/env python3
"""
MBill i18n helper — run from the project root:   python3 i18n-tools/check_i18n.py

1. Finds Tamil phrases in index.html / shop.html (and server messages in the Java sources)
   that are NOT in static/i18n/en.json  -> new text you added; add its English to en.json.
2. For every language in static/i18n/languages.json prints how many phrases are translated
   and writes  i18n-tools/missing-<code>.tsv   (Tamil source <TAB> English <TAB> empty column to fill).
3. Refreshes the "coverage" numbers in languages.json.

To add a language:  1) add {"code","native","english"} to languages.json
                    2) create static/i18n/<code>.json  ->  {"<Tamil source>": "<translation>", ...}
                       (start from missing-<code>.tsv; English-only labels use the English text as the key)
                    3) run this script again.
"""
import json, os, re, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "bhai-mobile-billing/src/main/")
I18N = os.path.join(MAIN, "resources/static/i18n/")
FILES = ["resources/static/index.html", "resources/static/shop.html", "resources/static/offline.js",
         "java/com/bhai/billing/core/BillingService.java", "java/com/bhai/billing/web/ApiController.java",
         "java/com/bhai/billing/core/PostDeployTests.java", "java/com/bhai/billing/core/CatalogImport.java"]
TA = re.compile(r"[஀-௿]")
SEP = re.compile(r"[<>`\"'\\\n\r${}\[\];=|&@#*^~]")

def strip_comments(t):
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    t = re.sub(r"<!--.*?-->", "", t, flags=re.S)
    return re.sub(r"(?m)(^|[ \t;{}])//(?!/).*$", r"\1", t)

def fragments():
    out = {}
    for f in FILES:
        t = strip_comments(open(MAIN + f, encoding="utf8").read())
        t = re.sub(r"\\[nrt]", "\n", t)
        for seg in SEP.split(t):
            s = seg.strip()
            if s and TA.search(s):
                out.setdefault(s, set()).add(os.path.basename(f))
    return out

def load(code):
    p = I18N + code + ".json"
    return json.load(open(p, encoding="utf8")) if os.path.exists(p) else {}

def main():
    en = load("en")
    frags = fragments()
    ig = os.path.join(ROOT, "i18n-tools", "ignore.txt")
    ignore = set(l.rstrip("\n") for l in open(ig, encoding="utf8") if l.strip() and not l.startswith("#")) if os.path.exists(ig) else set()
    new = [k for k in frags if k not in en and k not in ignore]
    print(f"{len(frags)} Tamil phrases in the source, {len(en)} in en.json")
    if new:
        print(f"\n{len(new)} phrase(s) have no English yet (sample catalogue names in ignore.txt are skipped):")
        for k in new[:60]: print("  -", k)
        if len(new) > 60: print(f"  ... and {len(new)-60} more")
    langs = json.load(open(I18N + "languages.json", encoding="utf8"))
    ui_keys = [k for k in en if any(f.endswith(".html") for f in frags.get(k, []))] or list(en)
    for l in langs:
        code = l["code"]
        if code in ("ta", "en"): l["coverage"] = 100; continue
        d = load(code)
        done = sum(1 for k in ui_keys if d.get(k))
        l["coverage"] = round(100 * done / max(1, len(ui_keys)))
        miss = [k for k in ui_keys if not d.get(k)]
        with open(os.path.join(ROOT, "i18n-tools", f"missing-{code}.tsv"), "w", encoding="utf8") as fh:
            fh.write("Tamil source\tEnglish\tTranslation\n")
            for k in miss: fh.write(f"{k}\t{en.get(k,'')}\t\n")
        print(f"{code:3} {l['english']:10} {done}/{len(ui_keys)} screen phrases translated ({l['coverage']}%)  -> missing-{code}.tsv")
    json.dump(langs, open(I18N + "languages.json", "w", encoding="utf8"), ensure_ascii=False, indent=1)

if __name__ == "__main__":
    main()
