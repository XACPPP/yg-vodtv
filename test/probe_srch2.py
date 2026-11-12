# -*- coding: utf-8 -*-
import sys, re, urllib.request, urllib.parse
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

UA = "Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36"
KW = "繁花"
url = "https://wsyzy.cc/index.php?m=vod-search&wd=" + urllib.parse.quote(KW)
req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
raw = urllib.request.urlopen(req, timeout=25).read()
text = raw.decode("utf-8", "replace")
print("len", len(raw))
# 找详情链接
links = re.findall(r'/index\.php/vod/detail/id/(\d+)\.html', text)
print("detail links:", len(links), links[:20])
# 找片名（常见模板：<a ... title="片名"> 或 alt="片名"）
titles = re.findall(r'title="([^"]{1,40})"', text)
print("titles sample:", titles[:25])
# 找 vod 名称段落
m = re.findall(r'<h4[^>]*>(.*?)</h4>', text, re.S)
print("h4:", [re.sub(r'<[^>]+>', '', x).strip() for x in m[:15]])
# 打印包含关键词的上下文
i = text.find(KW)
print("ctx:", repr(text[max(0,i-300):i+400]))
