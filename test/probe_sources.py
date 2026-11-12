# -*- coding: utf-8 -*-
"""
probe_sources.py -- 批量实测 MacCMS V10 (苹果CMS) 采集接口可用性

用途
    为 YG影视TV (Android TV 影视 App) 挑选片源：从 GitHub 上的「采集接口合集 /
    TVBox 配置仓库」收集候选 API，逐个真实联网请求，判定能否直接接入。

为什么必须看内容而不是看连接
    本机开着 Mihomo TUN，任何域名都 "connect 成功"。所以判定完全基于 HTTP 状态码
    + 响应体是否为合法 JSON + 是否真有数据。

判定口径（USABLE 需全部满足）
    1) GET <base>?ac=detail&pg=1 -> 200 且合法 JSON
    2) 有 list 数组，元素含 vod_id / vod_name / vod_pic / vod_play_url
    3) vod_play_url 是 m3u8 直链（web 播放页不算）
    4) vod_pic 非空
    5) 有最新内容：t=13 或分类里存在 2025/2026 年条目
    另外 DEEP 校验会真去 GET 首条 m3u8，确认返回 #EXTM3U。

用法（Windows PowerShell + python 3.10 + requests）
    python probe_sources.py                        # 内置候选 + 同目录池文件
    python probe_sources.py --pool candidate_pool.txt --deep
    python probe_sources.py --deep --only-bases hosts.txt

输出
    sources_report.txt / sources_report.json（UTF-8）；控制台只打 ASCII。

注意
    GBK 控制台放不下中文，片名统一 \\uXXXX 转义；完整中文见 sources_report.txt。
"""

import argparse
import concurrent.futures as futures
import io
import json
import os
import re
import ssl
import sys
import time
from urllib.parse import urlsplit, urlencode

UA = ("Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36")
TIMEOUT = 12
WORKERS = 14
MAX_BYTES = 4 * 1024 * 1024

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_OUT = os.path.join(HERE, "sources_report.txt")
DEFAULT_JSON = os.path.join(HERE, "sources_report.json")

HAS_REQUESTS = True
try:
    import requests
except Exception:  # pragma: no cover
    HAS_REQUESTS = False

if not HAS_REQUESTS:
    import urllib.request
    import urllib.error

_SSL_CTX = None
if HAS_REQUESTS:
    try:
        import urllib3
        urllib3.disable_warnings()
    except Exception:
        pass
else:
    _SSL_CTX = ssl.create_default_context()
    _SSL_CTX.check_hostname = False
    _SSL_CTX.verify_mode = ssl.CERT_NONE


# --------------------------------------------------------------------------
# HTTP
# --------------------------------------------------------------------------
def http_get(url, params=None, timeout=TIMEOUT, want_text=False):
    """Return (status, body_bytes_or_text, err). Never raises."""
    headers = {
        "User-Agent": UA,
        "Accept": "application/json, text/javascript, */*; q=0.01",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Accept-Encoding": "identity",
        "Connection": "close",
    }
    if HAS_REQUESTS:
        try:
            r = requests.get(url, params=params, headers=headers, timeout=timeout,
                             verify=False, allow_redirects=True, stream=True)
            buf = []
            size = 0
            for chunk in r.iter_content(65536):
                buf.append(chunk)
                size += len(chunk)
                if size > MAX_BYTES:
                    break
            body = b"".join(buf)
            return r.status_code, (body.decode("utf-8", "replace") if want_text else body), None
        except requests.exceptions.SSLError as e:
            if url.lower().startswith("https://"):
                try:
                    return http_get("http://" + url[8:], params, timeout, want_text)
                except Exception:
                    pass
            return 0, (b"" if not want_text else ""), "SSLError %s" % str(e)[:60]
        except requests.exceptions.Timeout:
            return 0, (b"" if not want_text else ""), "timeout>%ds" % timeout
        except requests.exceptions.ConnectionError as e:
            return 0, (b"" if not want_text else ""), "conn: %s" % str(e)[:70]
        except Exception as e:
            return 0, (b"" if not want_text else ""), "%s: %s" % (type(e).__name__, str(e)[:70])
    else:
        try:
            full = url
            if params:
                full = url + ("&" if "?" in url else "?") + urlencode(params)
            req = urllib.request.Request(full, headers=headers)
            with urllib.request.urlopen(req, timeout=timeout, context=_SSL_CTX) as f:
                raw = f.read(MAX_BYTES)
                return f.getcode(), (raw.decode("utf-8", "replace") if want_text else raw), None
        except urllib.error.HTTPError as e:
            return e.code, (b"" if not want_text else ""), "http %s" % e.code
        except Exception as e:
            return 0, (b"" if not want_text else ""), "%s: %s" % (type(e).__name__, str(e)[:70])


def parse_json(body):
    if not body:
        return None, "empty body"
    raw = body
    if isinstance(raw, str):
        raw = raw.encode("utf-8", "replace")
    if raw[:2] == b"\x1f\x8b":
        try:
            import gzip as _gz
            raw = _gz.decompress(raw)
        except Exception:
            pass
    try:
        txt = raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        txt = raw.decode("gbk", "replace")
    txt = txt.strip()
    if not txt:
        return None, "empty body"
    if txt[0] not in "{[":
        head = txt[:50].replace("\n", " ").replace("\r", " ")
        return None, "not json |head=%s|" % head
    try:
        return json.loads(txt), None
    except Exception as e:
        return None, "json error: %s" % str(e)[:60]


# --------------------------------------------------------------------------
# 内容解析
# --------------------------------------------------------------------------
M3U8_RE = re.compile(r"https?://[^\s#$'\"]+?\.m3u8", re.I)
YEAR_RE = re.compile(r"\b(19\d{2}|20\d{2})\b")

# 明显成人/擦边内容关键词：TV App 默认不接入，标出来让老板自己决定
ADULT_KW = (
    "杏吧", "麻豆", "无码", "爆操", "内射", "射精", "骚穴", "骚货", "巨乳", "肉便器",
    "偷拍", "女同", "百合双姬", "淫", "骚", "舔逼", "口爆", "颜射", "自慰", "强奸",
    "10musume", "MIDV", "SNOS", "WAAA", "JURA", "MFYD", "OKG", "VENX", "MBRBA",
    "18禁", "剛毛", "おもらし", "カップル", "童帝", "辛尤里",
)


def adult_hits(text):
    """返回命中的关键词个数（去重）。"""
    s = str(text or "")
    up = s.upper()
    hit = set()
    for k in ADULT_KW:
        if k in s or k.upper() in up:
            hit.add(k)
    return len(hit)


def looks_adult(name, extra=""):
    """首条片名命中 1 个即可；分类名 + 片名合计命中 2 个判成人。"""
    n = adult_hits(name)
    e = adult_hits(extra)
    if n >= 1 and e >= 1:
        return True
    if n + e >= 3:
        return True
    # 分类名本身就很脏（如 "无码精品"、"杏吧"）
    return e >= 1 and ("杏吧" in str(extra) or "麻豆" in str(extra) or "无码" in str(extra))


def playurl_kind(vod_play_url):
    """('m3u8'|'web'|'none', first_m3u8)"""
    if not vod_play_url:
        return "none", ""
    s = str(vod_play_url)
    m = M3U8_RE.findall(s)
    if m:
        return "m3u8", m[0]
    if "$http" in s or s.startswith("http"):
        return "web", ""
    return "none", ""


def years_in(text):
    out = []
    for m in YEAR_RE.finditer(str(text or "")):
        try:
            out.append(int(m.group(1)))
        except Exception:
            pass
    return out


def max_year(text):
    ys = years_in(text)
    return max(ys) if ys else 0


# --------------------------------------------------------------------------
# 单站探测
# --------------------------------------------------------------------------
def probe_base(base):
    base = (base or "").strip()
    if not base:
        return None
    if not base.endswith("/"):
        base += "/"
    parts = urlsplit(base)
    rec = {
        "base": base, "host": parts.netloc, "scheme": parts.scheme,
        "ok": False, "reason": "", "http": 0, "ms": 0, "count": 0,
        "pic_ok": False, "play_kind": "none", "play_sample": "",
        "first_name": "", "first_year": 0, "first_time": "",
        "cat13_count": 0, "cat13_new": 0, "cat13_first": "", "cat13_year": 0,
        "latest_ok": False, "adult": False, "m3u8_live": None,
        "cats": "", "checked_at": time.strftime("%Y-%m-%d %H:%M:%S"),
    }
    t0 = time.time()
    status, body, err = http_get(base, {"ac": "detail", "pg": 1})
    rec["ms"] = int((time.time() - t0) * 1000)
    rec["http"] = status
    if status == 0:
        rec["reason"] = err or "connect fail"
        return rec
    if status != 200:
        rec["reason"] = "http %s" % status
        return rec

    data, jerr = parse_json(body)
    if data is None:
        rec["reason"] = jerr or "not json"
        return rec
    if not isinstance(data, dict):
        rec["reason"] = "json root %s" % type(data).__name__
        return rec
    lst = data.get("list")
    if not isinstance(lst, list):
        rec["reason"] = "no list (keys=%s)" % ",".join(list(data.keys())[:5])
        return rec
    if not lst:
        rec["reason"] = "empty list"
        return rec

    rec["count"] = len(lst)
    items = [x for x in lst if isinstance(x, dict)]
    if not items:
        rec["reason"] = "list items not objects"
        return rec
    need = ("vod_id", "vod_name", "vod_pic", "vod_play_url")
    missing = [k for k in need if k not in items[0]]
    if missing:
        rec["reason"] = "missing %s" % ",".join(missing)
        return rec

    rec["pic_ok"] = sum(1 for x in items if str(x.get("vod_pic") or "").startswith("http")) >= max(1, len(items) // 2)

    kinds = {"m3u8": 0, "web": 0, "none": 0}
    sample = ""
    for x in items:
        k, u = playurl_kind(x.get("vod_play_url"))
        kinds[k] += 1
        if k == "m3u8" and not sample:
            sample = u
    rec["play_kind"] = ("m3u8" if kinds["m3u8"] >= max(1, (kinds["m3u8"] + kinds["web"]) // 2)
                        else ("web" if kinds["web"] else "none"))
    rec["play_sample"] = sample

    f = items[0]
    rec["first_name"] = str(f.get("vod_name") or "")[:70]
    rec["first_year"] = max_year(f.get("vod_year"))
    rec["first_time"] = str(f.get("vod_time") or "")[:19]
    rec["adult"] = looks_adult(rec["first_name"])

    # t=13 国产剧
    st2, body2, _ = http_get(base, {"ac": "detail", "t": 13, "pg": 1})
    if st2 == 200 and body2:
        d2, _ = parse_json(body2)
        if isinstance(d2, dict) and isinstance(d2.get("list"), list):
            l2 = [x for x in d2["list"] if isinstance(x, dict)]
            rec["cat13_count"] = len(l2)
            ys = []
            for x in l2:
                y = max_year(x.get("vod_year")) or max_year(x.get("vod_time")[:4])
                if y:
                    ys.append(y)
            rec["cat13_new"] = sum(1 for y in ys if y >= 2025)
            if l2:
                rec["cat13_first"] = str(l2[0].get("vod_name") or "")[:70]
                rec["cat13_year"] = (max_year(l2[0].get("vod_year"))
                                     or max_year(str(l2[0].get("vod_time") or "")[:4]))
    # 有些站 t=13 不是剧，看首屏年份兜底
    if rec["cat13_new"] == 0 and rec["cat13_count"] == 0:
        rec["cat13_new"] = sum(1 for x in items
                               if (max_year(x.get("vod_year")) or max_year(str(x.get("vod_time") or "")[:4])) >= 2025)

    rec["latest_ok"] = rec["cat13_new"] > 0 or rec["first_year"] >= 2025

    if rec["play_kind"] != "m3u8":
        rec["reason"] = "play=%s" % rec["play_kind"]
    elif not rec["pic_ok"]:
        rec["reason"] = "vod_pic empty"
    else:
        rec["ok"] = True
        rec["reason"] = "ok" if rec["latest_ok"] else "ok, no 2025+"
    return rec


CAT_PATTERNS = (
    # 顺序即优先级：越靠前的分类名越优先探测（子分类通常有内容，父分类常为空）
    ("tv", ("国产剧", "大陆剧", "内地剧", "电视剧", "连续剧", "剧集",
            "港台剧", "海外剧", "日韩剧", "欧美剧")),
    ("variety", ("大陆综艺", "国内综艺", "综艺", "晚会", "真人秀", "脱口秀")),
    ("anime", ("国产动漫", "中国动漫", "大陆动漫", "国漫", "日漫",
               "动漫", "动画", "番剧")),
    ("movie", ("电影", "片")),
)


def find_cats(base):
    """抓 ac=list 分类表 -> [(type_id, type_name)]"""
    st, body, _ = http_get(base, {"ac": "list"}, want_text=True)
    out = []
    if st != 200 or not body:
        return out
    d, _ = parse_json(body)
    if not isinstance(d, dict):
        return out
    cls = d.get("class")
    if not isinstance(cls, list):
        return out
    for c in cls:
        if isinstance(c, dict) and c.get("type_id") is not None and c.get("type_name"):
            out.append((str(c["type_id"]), str(c["type_name"])))
    return out


def cat_probe(base, tid):
    """取某分类首页，返回 (count, first_name, first_year, type_name)"""
    st, body, _ = http_get(base, {"ac": "detail", "t": tid, "pg": 1})
    if st != 200 or not body:
        return 0, "", 0, ""
    d, _ = parse_json(body)
    if not isinstance(d, dict) or not isinstance(d.get("list"), list):
        return 0, "", 0, ""
    items = [x for x in d["list"] if isinstance(x, dict)]
    if not items:
        return 0, "", 0, ""
    f = items[0]
    y = max_year(f.get("vod_year")) or max_year(str(f.get("vod_time") or "")[:4])
    return len(items), str(f.get("vod_name") or "")[:60], y, str(f.get("type_name") or "")


def deep_check(rec):
    """真去 GET 首条 m3u8 确认 #EXTM3U；并按分类表实测 电视剧/综艺/动漫。"""
    m3u = rec.get("play_sample") or ""
    if m3u:
        st, body, _ = http_get(m3u, timeout=TIMEOUT, want_text=True)
        rec["m3u8_live"] = bool(st == 200 and "#EXTM3U" in (body or "")[:64])
        rec["m3u8_http"] = st
    base = rec["base"]
    cats = find_cats(base)
    rec["cats"] = " ".join("%s:%s" % (i, n) for i, n in cats)[:500]
    rec["cat_map"] = {i: n for i, n in cats}

    # 每个 kind 收集所有可能分类，按 CAT_PATTERNS 关键词优先级排序后逐个探，
    # 取「有内容且年份最新」的那个（父分类常为空，如 t=2 电视剧 但 t=13 大陆剧 有货）
    cands = {}   # kind -> [(rank, tid, name)]
    for kind, keys in CAT_PATTERNS:
        for tid, name in cats:
            for rank, k in enumerate(keys):
                if k in name:
                    cands.setdefault(kind, []).append((rank, tid, name))
                    break
    rec["cat_hit"] = {}
    for kind in ("tv", "variety", "anime"):
        best = None
        tried = 0
        for rank, tid, tname in sorted(cands.get(kind, [])):
            if tried >= 4:
                break
            tried += 1
            n, first, yr, item_tname = cat_probe(base, tid)
            if not n:
                continue
            cand = {"tid": tid, "name": tname, "n": n,
                    "first": first, "year": yr, "item_type": item_tname}
            if best is None or (yr or 0) > (best.get("year") or 0):
                best = cand
            if (yr or 0) >= 2025:
                break
        rec["cat_hit"][kind] = best
    # 用「国产剧分类实测」覆盖 t=13 的粗糙判断
    tv = rec["cat_hit"].get("tv")
    if tv and tv["n"]:
        rec["tv_n"] = tv["n"]
        rec["tv_year"] = tv["year"]
        rec["tv_first"] = tv["first"]
        if (tv["year"] or 0) >= 2025:
            rec["latest_ok"] = True

    # 用分类名 + 三条首条片名重新判成人内容
    parts = []
    for k in ("tv", "variety", "anime"):
        c = (rec.get("cat_hit") or {}).get(k)
        if isinstance(c, dict):
            parts.append(str(c.get("first") or ""))
    sample = " ".join(parts)
    rec["adult"] = looks_adult(rec.get("cat13_first") or rec.get("first_name"),
                               (rec.get("cats") or "") + " " + sample)
    return rec


def summarize_quality(rec):
    """算一个综合分：有剧 + 有综艺 + 有动漫 + m3u8 live"""
    s = 0
    if rec.get("ok"):
        s += 10
    if rec.get("m3u8_live"):
        s += 6
    for kind, w in (("tv", 5), ("variety", 3), ("anime", 3)):
        c = (rec.get("cat_hit") or {}).get(kind)
        if c and c.get("n") and (c.get("year") or 0) >= 2025:
            s += w
        elif c and c.get("n"):
            s += 1
    return s


# --------------------------------------------------------------------------
# 候选池
# --------------------------------------------------------------------------
BUILTIN = [
    "https://wsyzy.cc/api.php/provide/vod/",
    "https://api.guangsuapi.com/api.php/provide/vod/",
    "https://caiji.moduapi.cc/api.php/provide/vod/",
    "https://360zy.com/api.php/provide/vod/",
    "https://360zy.tv/api.php/provide/vod/",
    "https://api.ffzyapi.com/api.php/provide/vod/",
    "https://cj.ffzyapi.com/api.php/provide/vod/",
    "https://ffzy5.tv/api.php/provide/vod/",
    "https://cj.lziapi.com/api.php/provide/vod/",
    "https://jszyapi.com/api.php/provide/vod/",
    "https://jinyingzy.com/api.php/provide/vod/",
    "https://api.zuidapi.com/api.php/provide/vod/",
    "https://caiji.dyttzyapi.com/api.php/provide/vod/",
    "https://api.ukuapi.com/api.php/provide/vod/",
    "https://www.cmov.cn/api.php/provide/vod/",
    "https://api.maoyanapi.top/api.php/provide/vod/",
    "https://tyyszy.com/api.php/provide/vod/",
    "https://api.wujinapi.me/api.php/provide/vod/",
    "https://cj.yayazy.net/api.php/provide/vod/",
    "https://caiji.maotaizy.cc/api.php/provide/vod/",
    "https://cj.rycjapi.com/api.php/provide/vod/",
    "https://www.ysxq.cc/api.php/provide/vod/",
    "https://subocaiji.com/api.php/provide/vod/",
    "https://hongniuzy2.com/api.php/provide/vod/",
    "https://ikunzyapi.com/api.php/provide/vod/",
    "https://iqiyizyapi.com/api.php/provide/vod/",
]


def norm_base(u):
    u = (u or "").strip()
    if not u:
        return ""
    i = u.find("/api.php/provide/vod")
    if i >= 0:
        u = u[:i + len("/api.php/provide/vod")]
    else:
        i = u.find("/provide/vod")
        if i < 0:
            return ""
        u = u[:i + len("/provide/vod")]
    return u.rstrip("/") + "/"


def load_pool(path):
    out = []
    if not path or not os.path.isfile(path):
        return out
    raw = io.open(path, "rb").read()
    for enc in ("utf-8-sig", "utf-8", "gbk", "latin-1"):
        try:
            txt = raw.decode(enc)
            break
        except Exception:
            continue
    else:
        return out
    for line in txt.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        b = norm_base(line)
        if b:
            out.append(b)
    return out


# --------------------------------------------------------------------------
# 主流程
# --------------------------------------------------------------------------
def ascii_safe(s, limit=36):
    out = []
    for ch in str(s or "")[:limit]:
        out.append(ch if 32 <= ord(ch) < 127 else "\\u%04x" % ord(ch))
    return "".join(out)


def run(bases, workers=WORKERS, deep=False, label=""):
    uniq, seen = [], set()
    for b in bases:
        nb = norm_base(b)
        if not nb:
            nb = (b or "").strip()
            if nb and not nb.endswith("/"):
                nb += "/"
        if not nb:
            continue
        k = nb.lower()
        if k in seen:
            continue
        seen.add(k)
        uniq.append(nb)
    results, total, done = [], len(uniq), 0
    print("probing %d candidates (deep=%s)..." % (total, deep))
    with futures.ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(probe_base, b): b for b in uniq}
        for fu in futures.as_completed(futs):
            done += 1
            b = futs[fu]
            try:
                r = fu.result()
            except Exception as e:
                r = {"base": b, "host": urlsplit(b).netloc, "ok": False,
                     "reason": "crash %s" % str(e)[:60], "count": 0, "pic_ok": False,
                     "play_kind": "none", "latest_ok": False, "first_name": "",
                     "first_year": 0, "first_time": "", "cat13_count": 0, "cat13_new": 0,
                     "cat13_first": "", "cat13_year": 0, "http": 0, "ms": 0,
                     "play_sample": "", "adult": False, "cats": "", "scheme": "",
                     "m3u8_live": None, "checked_at": ""}
            results.append(r)
            sys.stdout.write("[%3d/%3d] %s %-36s n=%-3s %s\n" % (
                done, total, "OK " if r.get("ok") else "-- ",
                r.get("host", "")[:36], r.get("count", 0), ascii_safe(r.get("reason"), 40)))
            sys.stdout.flush()

    if deep:
        targets = [r for r in results if r.get("ok")]
        print("deep-checking %d usable sources (m3u8 + cats)..." % len(targets))
        with futures.ThreadPoolExecutor(max_workers=min(10, workers)) as ex:
            list(ex.map(deep_check, targets))
    return results


def sort_key(r):
    return (-summarize_quality(r),
            0 if r.get("m3u8_live") else 1,
            -int(r.get("cat13_new") or 0),
            -int(r.get("count") or 0))


def write_report(results, out_txt, out_json):
    good = sorted([r for r in results if r.get("ok")], key=sort_key)
    mainstream = [r for r in good if not r.get("adult")]
    adult = [r for r in good if r.get("adult")]
    bad = sorted([r for r in results if not r.get("ok")],
                 key=lambda r: (-int(r.get("count") or 0), r.get("host", "")))
    semi = [r for r in bad if r.get("play_kind") == "m3u8" and int(r.get("count") or 0) > 0]
    dead = [r for r in bad if r not in semi]

    L = []
    A = L.append
    A("=" * 108)
    A(" MacCMS V10 采集接口实测报告")
    A(" 候选 %d / 可用 %d / 其中正常影视 %d / 疑似成人 %d" % (
        len(results), len(good), len(mainstream), len(adult)))
    A(" 时间: %s" % time.strftime("%Y-%m-%d %H:%M:%S"))
    A(" UA: Android5.1.1 VIDAA TV Chrome78   超时 %ds" % TIMEOUT)
    A(" 判定: HTTP200 + 合法JSON + list + vod_id/name/pic/play_url + m3u8 + 图片非空 + 2025/2026内容")
    A("=" * 108)

    A("")
    A("[A] 推荐接入（正常影视 + 2026 内容 + m3u8 直连）")
    A("-" * 108)
    A("%-3s %-28s %4s %3s %3s %5s %5s %-4s %s" % (
        "#", "域名", "条数", "剧", "综", "动漫", "m3u8", "图", "首条剧(年份)"))
    A("-" * 108)
    for i, r in enumerate(mainstream, 1):
        live = {True: "LIVE", False: "DEAD", None: "-"}[r.get("m3u8_live")]
        ch = r.get("cat_hit") or {}
        tv = ch.get("tv") or {}
        va = ch.get("variety") or {}
        an = ch.get("anime") or {}
        A("%-3d %-28s %4d %3s %3s %5s %5s %-4s %s(%s)" % (
            i, r["host"][:28], r["count"],
            "Y" if tv.get("n") else "-",
            "Y" if va.get("n") else "-",
            "Y" if an.get("n") else "-",
            live, "Y" if r["pic_ok"] else "N",
            (tv.get("first") or r["first_name"])[:22], tv.get("year") or r["first_year"] or "-"))
    if not mainstream:
        A("  (无)")

    A("")
    A("[B] 疑似成人内容（接口本身可用，播放正常，自行决定是否接入）")
    A("-" * 108)
    for i, r in enumerate(adult, 1):
        live = {True: "LIVE", False: "DEAD", None: "-"}[r.get("m3u8_live")]
        A("%-3d %-30s %5d %6d %4s %6s  %s(%s)" % (
            i, r["host"][:30], r["count"], r["cat13_new"],
            "Y" if r["pic_ok"] else "N", live, r["first_name"][:26], r["first_year"] or "-"))

    A("")
    A("[C] 接口活着但不能直接当片源")
    A("-" * 108)
    A("%-32s %5s %-8s %s" % ("域名", "条数", "播放", "原因"))
    A("-" * 108)
    for r in semi:
        A("%-32s %5s %-8s %s" % (r["host"][:32], r.get("count"), r.get("play_kind"), r.get("reason")[:48]))
    if not semi:
        A("  (无)")

    A("")
    A("[D] 不可用")
    A("-" * 108)
    A("%-38s %-6s %s" % ("域名", "HTTP", "原因"))
    A("-" * 108)
    for r in dead:
        A("%-38s %-6s %s" % (r.get("host", "")[:38], r.get("http", 0), r.get("reason", "")[:50]))
    if not dead:
        A("  (无)")

    A("")
    A("[E] 明细")
    A("-" * 108)
    for r in mainstream + adult:
        A(r["base"])
        A("   http=%s ms=%s count=%s pic_ok=%s m3u8_live=%s adult=%s" % (
            r.get("http"), r.get("ms"), r.get("count"), r.get("pic_ok"),
            r.get("m3u8_live"), r.get("adult")))
        A("   首条: %s | year=%s | vod_time=%s" % (
            r.get("first_name"), r.get("first_year") or "-", r.get("first_time") or "-"))
        A("   t=13: n=%s new2025+=%s 首条=%s(%s)" % (
            r.get("cat13_count"), r.get("cat13_new"),
            r.get("cat13_first") or "-", r.get("cat13_year") or "-"))
        ch = r.get("cat_hit") or {}
        for kind, cn in (("tv", "电视剧"), ("variety", "综艺"), ("anime", "动漫")):
            c = ch.get(kind)
            if not c:
                A("   %s: 无对应分类" % cn)
            else:
                A("   %s: t=%s(%s) n=%s 首条=%s(%s)" % (
                    cn, c.get("tid"), c.get("name"), c.get("n"),
                    c.get("first") or "-", c.get("year") or "-"))
        if r.get("play_sample"):
            A("   m3u8: %s" % r["play_sample"][:120])
        if r.get("cats"):
            A("   分类: %s" % r["cats"][:300])
        A("")

    io.open(out_txt, "w", encoding="utf-8", newline="\n").write("\n".join(L) + "\n")
    io.open(out_json, "w", encoding="utf-8", newline="\n").write(json.dumps({
        "generated": time.strftime("%Y-%m-%d %H:%M:%S"),
        "total": len(results), "usable": len(good),
        "mainstream": len(mainstream), "adult": len(adult),
        "results": results}, ensure_ascii=False, indent=1))
    return mainstream, adult, semi, dead


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pool", action="append", default=[])
    ap.add_argument("--only-bases", default="")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--json-out", default=DEFAULT_JSON)
    ap.add_argument("--workers", type=int, default=WORKERS)
    ap.add_argument("--deep", action="store_true", help="额外校验 m3u8 直连与分类表")
    ap.add_argument("--no-builtin", action="store_true")
    args = ap.parse_args()

    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    except Exception:
        pass

    bases = []
    if args.only_bases:
        bases = load_pool(args.only_bases)
    else:
        if not args.no_builtin:
            bases = list(BUILTIN)
        for p in args.pool:
            bases += load_pool(p)
        for name in ("candidate_pool.txt", "all_bases.txt", "candidates.txt"):
            cand = os.path.join(HERE, name)
            if os.path.isfile(cand):
                bases += load_pool(cand)

    print("probe_sources | requests=%s | timeout=%ds | workers=%d"
          % (HAS_REQUESTS, TIMEOUT, args.workers))
    results = run(bases, args.workers, deep=args.deep)
    main_rows, adult_rows, semi, dead = write_report(results, args.out, args.json_out)

    print("")
    print("=== RECOMMENDED (%d mainstream) ===" % len(main_rows))
    for i, r in enumerate(main_rows[:15], 1):
        print("%2d. %-30s n=%-3d t13new=%-3d m3u8=%s first=%s(%s)" % (
            i, r["host"][:30], r["count"], r["cat13_new"],
            {True: "LIVE", False: "DEAD", None: "-"}[r.get("m3u8_live")],
            ascii_safe(r["first_name"], 18), r["first_year"] or "-"))
    print("")
    print("report -> %s" % args.out)
    print("json   -> %s" % args.json_out)


if __name__ == "__main__":
    main()
