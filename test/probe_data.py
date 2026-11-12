# -*- coding: utf-8 -*-
import json, urllib.request, urllib.parse

UA = "Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36"

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read()

def probe(wd):
    url = "https://wsyzy.cc/index.php/ajax/data?mid=1&wd=" + urllib.parse.quote(wd)
    try:
        raw = fetch(url)
    except Exception as e:
        print("[%s] ERR %s" % (wd, e))
        return
    try:
        j = json.loads(raw.decode("utf-8", "replace"))
    except Exception as e:
        print("[%s] JSON ERR %s len=%d head=%r" % (wd, e, len(raw), raw[:120]))
        return
    lst = j.get("list") or []
    names = [x.get("vod_name") or x.get("name") for x in lst]
    print("[%s] bytes=%d total=%s pagecount=%s items=%d" % (wd, len(raw), j.get("total"), j.get("pagecount"), len(lst)))
    print("      " + " | ".join(str(n) for n in names[:6]))

for wd in ["qyn", "qingyunian", "庆余年", "fanhua", "繁花", "zzzzzznotexist"]:
    probe(wd)
