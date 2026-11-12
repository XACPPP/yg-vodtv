# -*- coding: utf-8 -*-
"""Check index.php/ajax/suggest search endpoint for finalist sources.

For each source: search a title that the source actually has (taken from its
own tv category), then verify the response is JSON with code=1 and total>0.
Writes result to suggest_check.txt (UTF-8).
"""
import io
import json
import re
import time
from urllib.parse import urlsplit, quote

import requests
import urllib3

urllib3.disable_warnings()

UA = ("Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36")

S = requests.Session()
S.headers.update({"User-Agent": UA, "Accept": "*/*",
                  "Accept-Encoding": "gzip, deflate", "Connection": "close"})
S.verify = False

# host -> 该站国产剧分类里真实存在的片名（用于搜索验证）
CASES = [
    ("https://api.maoyanapi.top/api.php/provide/vod/", "13", "今天是太阳雨"),
    ("https://api.guangsuapi.com/api.php/provide/vod/", "13", "谷雨"),
    ("https://caiji.moduapi.cc/api.php/provide/vod/", "26", "尬先生"),
    ("https://api.ffzyapi.com/api.php/provide/vod/", "13", "十三种爱情"),
    ("https://www.ysxq.cc/api.php/provide/vod/", "13", "走马上任"),
    ("https://yhzy.cc/api.php/provide/vod/", "13", "以神之名，对话万物"),
    ("https://360zy.com/api.php/provide/vod/", "13", "天命·成凰"),
    ("https://api.ukuapi.com/api.php/provide/vod/", "13", "尚公主"),
    ("https://bfzyapi.com/api.php/provide/vod/", "31", "今天是太阳雨"),
    ("https://api.wujinapi.me/api.php/provide/vod/", "13", "天命成凰"),
    ("https://ikunzyapi.com/api.php/provide/vod/", "23", "谷雨"),
    ("https://360zyzz.com/api.php/provide/vod/", "13", "天命·成凰"),
    ("https://maoyanzy.com/api.php/provide/vod/", "13", "今天是太阳雨"),
    ("https://www.cmov.cn/api.php/provide/vod/", "13", "炽夏"),
    ("https://cdn.dzzyapi.com/api.php/provide/vod/", "13", "卿色无双"),
    ("https://wsyzy.cc/api.php/provide/vod/", "13", "尚公主"),
]


def fetch(url, params):
    try:
        r = S.get(url, params=params, timeout=12, verify=False, allow_redirects=True)
        return r.status_code, r.text, r.url
    except Exception as e:
        return 0, "%s: %s" % (type(e).__name__, str(e)[:70]), ""


def jload(t):
    t = (t or "").strip()
    if not t.startswith("{"):
        return None
    try:
        return json.loads(t)
    except Exception:
        return None


def main():
    L = []
    A = L.append
    A("suggest endpoint check  %s" % time.strftime("%Y-%m-%d %H:%M:%S"))
    A("")
    A("%-26s %-6s %-6s %-6s %-7s %s" % ("host", "http", "code", "total", "verdict", "note"))
    A("-" * 96)
    for base, tid, kw in CASES:
        host = urlsplit(base).netloc
        # 1) 确认该关键词在该站 detail 接口里能搜到（用 t 分类页对比）
        root = base.split("/api.php")[0]
        sug = root + "/index.php/ajax/suggest"
        st, body, final = fetch(sug, {"mid": 1, "wd": kw})
        d = jload(body)
        code = d.get("code") if isinstance(d, dict) else None
        total = d.get("total") if isinstance(d, dict) else None
        note = ""
        if d is None:
            first = (body or "")[:40].replace("\n", " ")
            note = "not json: " + first
        else:
            lst = d.get("list") or []
            if lst:
                note = "hit: %s" % str(lst[0].get("name") or "")[:20]
            else:
                note = "empty list"
        verdict = "OK" if (isinstance(d, dict) and code == 1 and total) else "NO"
        A("%-26s %-6s %-6s %-6s %-7s %s" % (host, st, code if code is not None else "-",
                                            total if total is not None else "-", verdict, note))
        if final and urlsplit(final).netloc != host:
            A("   -> redirect to %s" % urlsplit(final).netloc)
    io.open("suggest_check.txt", "w", encoding="utf-8", newline="\n").write("\n".join(L) + "\n")
    print("written -> suggest_check.txt")


if __name__ == "__main__":
    main()
