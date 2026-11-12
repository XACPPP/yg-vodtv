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
        return r.read()

def suggest(kw):
    raw = get("https://wsyzy.cc/index.php/ajax/suggest?mid=1&wd=" + urllib.parse.quote(kw))
    j = json.loads(raw.decode("utf-8", "replace"))
    return [(x.get("id"), x.get("name")) for x in (j.get("list") or [])]

for kw in ["fanhua", "qingyunian", "kuangbiao", "sanguo"]:
    items = suggest(kw)
    print("=== %s  (%d 条)" % (kw, len(items)))
    for i, (vid, nm) in enumerate(items):
        print("   %2d  %-8s %s" % (i + 1, vid, nm))
