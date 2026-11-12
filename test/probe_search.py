# -*- coding: utf-8 -*-
import sys, json, urllib.request, urllib.parse
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

UA = "Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36"
KW = "繁花"

CANDIDATES = [
    ("api+w d detail", "https://wsyzy.cc/api.php/provide/vod/?ac=detail&wd=" + urllib.parse.quote(KW)),
    ("api list wd",    "https://wsyzy.cc/api.php/provide/vod/?ac=list&wd=" + urllib.parse.quote(KW)),
    ("api videolist",  "https://wsyzy.cc/api.php/provide/vod/?ac=videolist&wd=" + urllib.parse.quote(KW)),
    ("at json wd",     "https://wsyzy.cc/api.php/provide/vod/at/json/?ac=detail&wd=" + urllib.parse.quote(KW)),
    ("ajax suggest",   "https://wsyzy.cc/index.php/ajax/suggest?mid=1&wd=" + urllib.parse.quote("qyn")),
    ("ajax data wd",   "https://wsyzy.cc/index.php/ajax/data?mid=1&wd=" + urllib.parse.quote(KW)),
    ("search html",    "https://wsyzy.cc/index.php/vod/search.html?wd=" + urllib.parse.quote(KW)),
    ("search json",    "https://wsyzy.cc/index.php/vod/search.html?wd=" + urllib.parse.quote(KW) + "&ajax=1"),
    ("index search",   "https://wsyzy.cc/index.php?m=vod-search&wd=" + urllib.parse.quote(KW)),
]

def probe(name, url):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
        with urllib.request.urlopen(req, timeout=25) as r:
            raw = r.read()
            code = r.status
    except Exception as e:
        print("%-16s ERR %s" % (name, e))
        return
    text = raw.decode("utf-8", "replace")
    hit = KW in text
    has_list = '"list"' in text
    print("%-16s %s len=%-7d 含关键词=%-5s 含list=%-5s 头部=%r" % (
        name, code, len(raw), hit, has_list, text[:80].replace("\n", " ")))

for n, u in CANDIDATES:
    probe(n, u)
