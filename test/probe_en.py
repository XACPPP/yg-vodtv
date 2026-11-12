# -*- coding: utf-8 -*-
import sys, json, urllib.request, urllib.parse
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

UA = "Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36"

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read().decode("utf-8", "replace")

for kw in ["fanhua", "qingyunian", "繁花"]:
    j = json.loads(get("https://wsyzy.cc/index.php/ajax/suggest?mid=1&wd=" + urllib.parse.quote(kw)))
    print("=== %s  keys=%s" % (kw, list((j.get("list") or [{}])[0].keys())))
    for x in (j.get("list") or []):
        print("    id=%-7s en=%-28s name=%s" % (x.get("id"), x.get("en"), x.get("name")))
