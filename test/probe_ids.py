# -*- coding: utf-8 -*-
import sys, json, urllib.request
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

UA = "Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36"

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read()

for ids in ["143192,135547,135074", "135547"]:
    url = "https://wsyzy.cc/api.php/provide/vod/?ac=detail&ids=" + ids
    raw = get(url)
    try:
        j = json.loads(raw.decode("utf-8", "replace"))
        lst = j.get("list") or []
        print("ids=%s -> bytes=%d total=%s items=%d" % (ids, len(raw), j.get("total"), len(lst)))
        for x in lst:
            print("     id=%s name=%s pic=%s" % (x.get("vod_id"), x.get("vod_name"), (x.get("vod_pic") or "")[:60]))
    except Exception as e:
        print("ids=%s -> JSON ERR %s len=%d" % (ids, e, len(raw)))
