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
        return r.read().decode("utf-8", "replace")

KW = "繁花"
base = "https://wsyzy.cc/index.php?m=vod-search&wd=" + urllib.parse.quote(KW)
text = get(base)
print("len", len(text))
for tag in ["<img", "data-original", "data-src", "lazy", ".jpg", ".png", "pic"]:
    print("%-14s -> %d" % (tag, text.count(tag)))

# 打印第一个 li 块完整内容
m = re.search(r'<ul>\s*<li>\s*<span class="tt">.*?</li>\s*</ul>', text, re.S)
print("---- first li block ----")
print(m.group(0)[:1200] if m else "no match")

# 分页
pg = re.findall(r'href="([^"]*page[^"]*)"', text)
print("---- pagination ----")
for p in pg[:10]:
    print("  ", p)
