# -*- coding: utf-8 -*-
"""Final verification of shortlisted MacCMS V10 sources for YG影视TV.

Checks per source:
  1) GET <base>?ac=detail&pg=1        -> 200 / JSON / list / fields
  2) GET <base>?ac=detail&t=<tv>&pg=1 -> TV category, newest item name+year
  3) GET <base>?ac=detail&ids=<id>    -> single-item detail works
  4) GET <suggest>?mid=1&wd=<kw>      -> search endpoint works
  5) GET first m3u8                   -> 200 + #EXTM3U (direct playable)
Writes UTF-8 results to sources_final.txt (console stays ASCII).
"""
import io
import json
import re
import ssl
import sys
import time
from urllib.parse import urlsplit

import requests

UA = ("Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36")
TIMEOUT = 12
MAX_BYTES = 4 * 1024 * 1024
OUT = "sources_final.txt"

M3U8_RE = re.compile(r"https?://[^\s#$'\"]+?\.m3u8", re.I)
YEAR_RE = re.compile(r"\b(19\d{2}|20\d{2})\b")

S = requests.Session()
S.headers.update({
    "User-Agent": UA,
    "Accept": "*/*",
    "Accept-Encoding": "gzip, deflate",
    "Connection": "close",
})
S.verify = False
try:
    requests.packages.urllib3.disable_warnings()
except Exception:
    pass

# 内容指纹去重后的代表站（host -> 说明）
SHORTLIST = [
    "https://api.maoyanapi.top/api.php/provide/vod/",
    "https://api.guangsuapi.com/api.php/provide/vod/",
    "https://caiji.moduapi.cc/api.php/provide/vod/",
    "https://api.juliang.live/api.php/provide/vod/",
    "https://www.cmov.cn/api.php/provide/vod/",
    "https://api.ffzyapi.com/api.php/provide/vod/",
    "https://api.ukuapi.com/api.php/provide/vod/",
    "https://www.ysxq.cc/api.php/provide/vod/",
    "https://yhzy.cc/api.php/provide/vod/",
    "https://bfzyapi.com/api.php/provide/vod/",
    "https://api.wujinapi.me/api.php/provide/vod/",
    "https://cdn.dzzyapi.com/api.php/provide/vod/",
    "https://360zy.com/api.php/provide/vod/",
    "https://api.1080zyku.com/api.php/provide/vod/",
    "https://ikunzyapi.com/api.php/provide/vod/",
    "https://360zyzz.com/api.php/provide/vod/",
    "https://maoyanzy.com/api.php/provide/vod/",
    "https://ys.cxkjg.cn/api.php/provide/vod/",
    "https://wsyzy.cc/api.php/provide/vod/",
]


def get(url, params=None, want_text=False):
    for attempt in (0, 1):
        try:
            r = S.get(url, params=params, timeout=TIMEOUT, stream=True,
                      verify=False, allow_redirects=True)
            raw = b""
            for chunk in r.iter_content(65536):
                raw += chunk
                if len(raw) > MAX_BYTES:
                    break
            body = raw.decode("utf-8-sig", "replace")
            return r.status_code, body
        except Exception as e:
            err = "%s: %s" % (type(e).__name__, str(e)[:80])
            if attempt == 0 and ("SSL" in err or "EOF" in err):
                if url.startswith("https://"):
                    url = "http://" + url[len("https://"):]
                    continue
            return 0, err
    return 0, "fail"


def jload(body):
    if not body:
        return None
    t = body.strip()
    if t[:2] == "\x1f\x8b":
        return None
    if not t.startswith("{") and not t.startswith("["):
        return None
    try:
        return json.loads(t)
    except Exception:
        return None


def maxyear(s):
    ys = [int(m) for m in YEAR_RE.findall(str(s or ""))]
    return max(ys) if ys else 0


def item_year(x):
    return maxyear(x.get("vod_year")) or maxyear(str(x.get("vod_time") or "")[:4])


def find_tv_cat(base):
    """返回 (tid, 分类名)，按优先级找国产/大陆剧类目"""
    st, body = get(base, {"ac": "list"})
    d = jload(body)
    if not isinstance(d, dict):
        return None, ""
    best = None
    keys = ("国产剧", "大陆剧", "内地剧", "电视剧", "连续剧", "剧集")
    for c in (d.get("class") or []):
        if not isinstance(c, dict):
            continue
        name = str(c.get("type_name") or "")
        tid = c.get("type_id")
        if tid is None:
            continue
        for rank, k in enumerate(keys):
            if k in name:
                if best is None or rank < best[0]:
                    best = (rank, str(tid), name)
                break
    if not best:
        return None, ""
    return best[1], best[2]


def verify(base):
    r = {"base": base, "host": urlsplit(base).netloc}
    st, body = get(base, {"ac": "detail", "pg": 1})
    r["http"] = st
    d = jload(body)
    if not isinstance(d, dict) or not isinstance(d.get("list"), list) or not d["list"]:
        r["ok"] = False
        r["reason"] = "bad list (http=%s)" % st
        return r
    items = [x for x in d["list"] if isinstance(x, dict)]
    r["count"] = len(items)
    f = items[0]
    r["first_name"] = str(f.get("vod_name") or "")
    r["first_year"] = item_year(f)
    r["first_time"] = str(f.get("vod_time") or "")
    r["pic_ok"] = sum(1 for x in items if str(x.get("vod_pic") or "").startswith("http"))
    u = ""
    for x in items:
        m = M3U8_RE.findall(str(x.get("vod_play_url") or ""))
        if m:
            u = m[0]
            break
    r["m3u8_sample"] = u
    # 直连播放验证
    if u:
        mst, mbody = get(u)
        r["m3u8_http"] = mst
        r["m3u8_live"] = bool(mst == 200 and "#EXTM3U" in mbody[:64])
        r["m3u8_head"] = mbody[:60].replace("\n", "|")
    else:
        r["m3u8_http"] = 0
        r["m3u8_live"] = False
        r["m3u8_head"] = ""
    # 电视剧分类
    tid, tname = find_tv_cat(base)
    r["tv_tid"] = tid
    r["tv_cname"] = tname
    if tid:
        st2, b2 = get(base, {"ac": "detail", "t": tid, "pg": 1})
        d2 = jload(b2)
        if isinstance(d2, dict) and isinstance(d2.get("list"), list) and d2["list"]:
            l2 = [x for x in d2["list"] if isinstance(x, dict)]
            r["tv_count"] = len(l2)
            r["tv_first"] = str(l2[0].get("vod_name") or "")
            r["tv_first_year"] = item_year(l2[0])
            r["tv_2026"] = sum(1 for x in l2 if item_year(x) >= 2026)
            r["tv_2025_plus"] = sum(1 for x in l2 if item_year(x) >= 2025)
            # 单条详情接口
            vid = l2[0].get("vod_id")
            r["vod_id"] = vid
            st3, b3 = get(base, {"ac": "detail", "ids": vid})
            d3 = jload(b3)
            r["ids_ok"] = bool(isinstance(d3, dict) and d3.get("list"))
    # 搜索接口
    root = base.split("/api.php")[0]
    st4, b4 = get(root + "/index.php/ajax/suggest", {"mid": 1, "wd": "尚公主"})
    d4 = jload(b4)
    r["search_http"] = st4
    r["search_total"] = (d4 or {}).get("total") if isinstance(d4, dict) else None
    r["search_ok"] = bool(isinstance(d4, dict) and d4.get("code") == 1)
    r["ok"] = True
    r["reason"] = "ok"
    return r


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    except Exception:
        pass
    rows = []
    for i, b in enumerate(SHORTLIST, 1):
        print("[%2d/%2d] %s" % (i, len(SHORTLIST), urlsplit(b).netloc))
        try:
            rows.append(verify(b))
        except Exception as e:
            rows.append({"base": b, "host": urlsplit(b).netloc, "ok": False,
                         "reason": "%s: %s" % (type(e).__name__, str(e)[:80])})
    L = []
    A = L.append
    A("MacCMS V10 final verification  %s" % time.strftime("%Y-%m-%d %H:%M:%S"))
    A("UA=%s" % UA)
    A("shortlist=%d  ok=%d" % (len(rows), sum(1 for r in rows if r.get("ok"))))
    A("")
    for r in rows:
        A("=" * 96)
        A("%s  (%s)" % (r["host"], "OK" if r.get("ok") else "FAIL: " + str(r.get("reason"))))
        A("  base        : %s" % r["base"])
        A("  list        : http=%s count=%s pic_ok=%s" % (
            r.get("http"), r.get("count"), r.get("pic_ok")))
        A("  first item  : %s | year=%s | vod_time=%s" % (
            r.get("first_name"), r.get("first_year"), r.get("first_time")))
        A("  tv cat      : tid=%s name=%s count=%s first=%s year=%s 2026=%s 2025+=%s" % (
            r.get("tv_tid"), r.get("tv_cname"), r.get("tv_count"), r.get("tv_first"),
            r.get("tv_first_year"), r.get("tv_2026"), r.get("tv_2025_plus")))
        A("  detail ids  : ok=%s (vod_id=%s)" % (r.get("ids_ok"), r.get("vod_id")))
        A("  search      : http=%s ok=%s total=%s" % (
            r.get("search_http"), r.get("search_ok"), r.get("search_total")))
        A("  m3u8        : http=%s live=%s head=%s" % (
            r.get("m3u8_http"), r.get("m3u8_live"), r.get("m3u8_head")))
        A("  m3u8 url    : %s" % (r.get("m3u8_sample") or "-")[:110])
        A("")
    io.open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(L) + "\n")
    io.open(OUT.replace(".txt", ".json"), "w", encoding="utf-8",
            newline="\n").write(json.dumps(rows, ensure_ascii=False, indent=1))
    print("written -> %s" % OUT)


if __name__ == "__main__":
    main()
