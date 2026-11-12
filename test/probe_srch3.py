# -*- coding: utf-8 -*-
import sys, re, urllib.request, urllib.parse
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

UA = "Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36"

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read()

def parse(text):
    out = []
    for m in re.finditer(r'<a href="/index\.php/vod/detail/id/(\d+)\.html">(.*?)</a>', text, re.S):
        vid = int(m.group(1))
        name = re.sub(r'<[^>]+>', '', m.group(2)).strip()
        out.append((vid, name))
    # 去重保序
    seen = set(); res = []
    for v, n in out:
        if v in seen: continue
        seen.add(v); res.append((v, n))
    return res

for kw in ["qyn", "qingyunian", "庆余年", "fanhua", "繁花", "zzzznotexist"]:
    url = "https://wsyzy.cc/index.php?m=vod-search&wd=" + urllib.parse.quote(kw)
    raw = get(url)
    text = raw.decode("utf-8", "replace")
    items = parse(text)
    print("[%s] bytes=%d hits=%d" % (kw, len(raw), len(items)))
    for v, n in items[:6]:
        print("      %d  %s" % (v, n))
