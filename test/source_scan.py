# -*- coding: utf-8 -*-
"""source_scan.py -- 采集源健康度巡检 + 打分排序（「高速源收集」的执行器）

为什么单独写这个（而不是复用 probe_sources.py / verify_final.py）
    probe_sources.py 回答「这个站能不能用」，verify_final.py 回答「入围的站有没有坑」。
    本脚本回答第三个、也是唯一持续需要回答的问题：**现在哪几个源最快最稳，该排前面**。
    它产出 sources_health.json —— App 直接消费的源清单，加源/换源/调优先级不用发版。

测什么（"高速"必须可量化，不能凭感觉）
    1) list      ?ac=detail&pg=1            接口首屏延迟 / 条数 / 海报覆盖 / 简介覆盖
    2) detail    ?ac=detail&ids=<首条id>     是否有选集、播放地址是否成对
    3) class     ?ac=detail（取 class 字段）  该站自己的分类名 → 自动匹配统一频道
    4) search    ?ac=detail&wd=庆余年         中文搜索命中数
    5) suggest   /ajax/suggest?mid=1&wd=fanhua 拼音联想是否可用
    6) play      首条 m3u8                   TTFB / 分片下载速率 kbps / 是否支持 Range
    7) burst     连拉 3 个分片                抖动量（同一源自己快慢是否稳定）

打分口径（满分 100）
    速度 45：list 首屏 25 + m3u8 TTFB 12 + 分片速率 8
    内容 35：简介覆盖 15 + 海报覆盖 10 + 片库规模 10
    能力 20：搜索 8 + 选集 6 + Range 3 + 稳定性 3
    任一「硬失败」（列表不合法 JSON / 无数据 / m3u8 拿不到）直接判 DEAD，不参与排序。

用法
    python source_scan.py                     # 从 Src.java 解析现有源
    python source_scan.py --tvbox cfg.json    # 追加 TVBox 配置里的 type=1（苹果CMS）站点
    python source_scan.py --pool bases.txt    # 追加纯 base 列表
    python source_scan.py --workers 16 --timeout 10

输出
    sources_health.json  App 消费（UTF-8，含排序后顺序）
    sources_health.md    人读对照表（UTF-8）
    控制台只打 ASCII，避免 Windows GBK 控制台崩。

注意
    本机开着 Mihomo TUN，任何域名都 "connect 成功"，所以判定一律基于
    状态码 + 响应体是否合法 JSON + 有没有真数据 + m3u8 是否 #EXTM3U。
"""
from __future__ import annotations

import argparse
import concurrent.futures as futures
import io
import json
import os
import re
import ssl
import sys
import time
from urllib.parse import urlsplit

import requests

# 电视端 Http.java 静态块里设了 java.net.preferIPv4Stack=true，
# 局域网也全程禁用 IPv6；巡检脚本必须和电视同视角，否则会拿 AAAA 记录去连，结论对不上。
# 这台机器开着 Mihomo TUN，IPv6 侧有的站握手就断，脚本会误判成 DEAD，
# 而电视上其实是好的 —— 那种假 DEAD 会直接把源从源表里踢掉。
import socket as _socket
_orig_gai = _socket.getaddrinfo


def _v4_only(host, port, family=0, type=0, proto=0, flags=0):
    return _orig_gai(host, port, _socket.AF_INET, type, proto, flags)


_socket.getaddrinfo = _v4_only

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_JSON = os.path.join(HERE, "sources_health.json")
OUT_MD = os.path.join(HERE, "sources_health.md")
SRC_JAVA = os.path.join(HERE, "..", "app", "src", "com", "yg", "vodtv", "Src.java")

UA = ("Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36")
TIMEOUT = 10
WORKERS = 14
MAX_BYTES = 4 * 1024 * 1024

# 统一频道（与 Src.java 的 CH_xxx 同名同序，便于直接对照）
CHANNELS = [
    ("rec", "推荐", []),
    ("movie", "电影", ["电影"]),
    ("tv", "电视剧", ["电视剧", "连续剧"]),
    ("variety", "综艺", ["综艺"]),
    ("anime", "动漫", ["动漫", "动画"]),
    ("cntv", "国产剧", ["国产剧", "大陆剧"]),
    ("westtv", "欧美剧", ["欧美剧", "美剧"]),
    ("korea", "韩剧", ["韩剧", "韩国剧"]),
    ("japan", "日剧", ["日剧", "日本剧"]),
    ("hk", "港剧", ["港剧", "香港剧"]),
    ("tw", "台剧", ["台剧", "台湾剧"]),
    ("thai", "泰剧", ["泰剧", "泰国剧"]),
    ("action", "动作", ["动作"]),
    ("comedy", "喜剧", ["喜剧"]),
    ("romance", "爱情", ["爱情"]),
    ("scifi", "科幻", ["科幻"]),
    ("horror", "恐怖", ["恐怖"]),
    ("war", "战争", ["战争"]),
    ("doc", "纪录片", ["纪录片"]),
    ("short", "短剧", ["短剧"]),
    ("4k", "4K", ["4K", "蓝光"]),
    ("suspense", "悬疑", ["悬疑"]),
]

M3U8_RE = re.compile(r"https?://[^\s#$'\"]+?\.m3u8", re.I)

S = requests.Session()
S.headers.update({"User-Agent": UA, "Accept": "*/*",
                  "Accept-Encoding": "gzip, deflate", "Connection": "close"})
S.verify = False
try:
    requests.packages.urllib3.disable_warnings()
except Exception:
    pass


def now_ms():
    return int(time.time() * 1000)


def abs_url(base, ref):
    """把 m3u8 里的地址拼成完整 URL。

    坑：源站 master playlist 给的变体地址大多是 `/20261112/xxx/hls/index.m3u8`
    这种**根相对**路径。原来的 `base.rsplit("/")[0] + ref` 会拼成
    `.../yTuGuK9V//20261112/yTuGuK9V/hls/index.m3u8` → 404，
    于是 maoyan / 360zy / modu / uku / ikun 五个好源全被判 DEAD。
    """
    if not ref:
        return base
    if "://" in ref:
        return ref
    m = re.match(r"^(https?://[^/]+)", base)
    host = m.group(1) if m else ""
    if ref.startswith("//"):
        scheme = host.split(":")[0] if host else "https"
        return scheme + ":" + ref
    if ref.startswith("/"):
        return host + ref
    return base.rsplit("/", 1)[0] + "/" + ref



def get(url, referer=None, timeout=TIMEOUT, method="GET", stream=False):
    """返回 (status, elapsed_ms, bytes_or_text, err)。永不抛异常。"""
    t0 = now_ms()
    try:
        h = {}
        if referer:
            h["Referer"] = referer
        r = S.request(method, url, headers=h, timeout=timeout,
                      verify=False, stream=stream, allow_redirects=True)
        if stream:
            chunk = next(r.iter_content(64 * 1024), b"")
            r.close()
            return r.status_code, now_ms() - t0, chunk, None
        body = r.content[:MAX_BYTES]
        return r.status_code, now_ms() - t0, body, None
    except Exception as e:  # noqa: BLE001
        return 0, now_ms() - t0, b"", "%s: %s" % (type(e).__name__, str(e)[:80])


def as_json(raw):
    if not isinstance(raw, (bytes, bytearray)) or not raw:
        return None
    try:
        return json.loads(raw.decode("utf-8", "ignore"))
    except Exception:
        return None


# ---------------------------------------------------------------- 候选源收集

def from_src_java(path):
    """从 Src.java 的内置表解析出 (key, api, referer, types)。

    表名从 SPEC 改成了 BUILTIN（改名是为了强调「内置兜底」，远端 sources.json
    才是主表），这里两个名字都认，免得以后再来一次改名就把候选源清空 ——
    那会让巡检跑出 0 个源，CI 直接把空的 sources.json 提交上去。
    """
    out = []
    if not os.path.isfile(path):
        return out
    txt = io.open(path, encoding="utf-8").read()
    m = re.search(r"(?:SPEC|BUILTIN)\s*=\s*\{(.*?)\n\s*\};", txt, re.S)
    if not m:
        return out
    block = m.group(1)
    # 每个条目是两段字符串拼起来的：先 "key|api|referer"，再 "|1,2,3..."，
    # 那个竖线在第二段的开头。types 段允许缺失，缺了就留空、后面按内置原样透传。
    pat = (r'"([a-z0-9_]+)\|(https?://[^|"]+)\|(https?://[^|"]+)"\s*'
           r'(?:\+\s*"\|([0-9,\s]+)")?')
    for hit in re.finditer(pat, block):
        key, api, ref = hit.group(1), hit.group(2), hit.group(3)
        types = (hit.group(4) or "").strip().strip(",").strip()
        if not api.endswith("/"):
            api += "/"
        out.append({"key": key, "api": api, "referer": ref,
                    "types": types, "from": "Src.java"})
    return out


def from_tvbox(path, existing):
    """从 TVBox 配置里抽出 type=1（苹果CMS 直连接口）的站点。type=3 是 csp 爬虫，本脚本不处理。"""
    out = []
    if not os.path.isfile(path):
        print("[warn] tvbox config not found: %s" % path)
        return out
    try:
        d = json.load(io.open(path, encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        print("[warn] tvbox config parse failed: %s" % e)
        return out
    seen = {s["api"].rstrip("/") for s in existing}
    for s in d.get("sites", []):
        if s.get("type") != 1:
            continue
        api = (s.get("api") or "").strip()
        if "://" not in api:
            continue
        # TVBox 的 api 常常带 ?ac=list，去掉查询串只留接口前缀
        base = api.split("?")[0]
        if not base.endswith("/"):
            base += "/"
        if base.rstrip("/") in seen:
            continue
        seen.add(base.rstrip("/"))
        key = (s.get("key") or "").split("/")[0] or urlsplit(base).netloc
        key = re.sub(r"[^A-Za-z0-9_]", "", key) or re.sub(r"[^A-Za-z0-9]", "", urlsplit(base).netloc)
        if not key:
            key = "src%d" % len(out)
        # 去重 key
        base_key, n = key, 1
        while any(o["key"] == key for o in out):
            n += 1
            key = "%s%d" % (base_key, n)
        out.append({"key": key, "api": base,
                    "referer": "https://" + urlsplit(base).netloc + "/",
                    "from": "tvbox"})
    return out


def from_pool(path, existing):
    out = []
    if not os.path.isfile(path):
        return out
    seen = {s["api"].rstrip("/") for s in existing}
    for line in io.open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#") or "://" not in line:
            continue
        base = line.split("?")[0]
        if not base.endswith("/"):
            base += "/"
        if base.rstrip("/") in seen:
            continue
        seen.add(base.rstrip("/"))
        key = re.sub(r"[^A-Za-z0-9]", "", urlsplit(base).netloc)[:16] or ("p%d" % len(out))
        out.append({"key": key, "api": base,
                    "referer": "https://" + urlsplit(base).netloc + "/",
                    "from": "pool"})
    return out


# ---------------------------------------------------------------- 单项探测

def probe_list(src):
    """列表页：延迟、条数、海报/简介覆盖、class 分类表。"""
    r = {"ok": False, "ms": 0, "n": 0, "pic": 0, "intro": 0,
         "total": 0, "classes": [], "err": None}
    st, ms, raw, err = get(src["api"] + "?ac=detail&pg=1", src["referer"])
    r["ms"] = ms
    if st != 200:
        # 第一发超时/断了很常见（源站冷启动、CDN 回源），但一发就判 DEAD 会把
        # 好源永久踢出表 —— yhzy 实测：首问 10.4s 超时、第二问 10.3s 又超时、
        # 第三问 2.2s 才 200。只重试一次不够，所以给它三发，超时逐级放宽。
        for i, tmo in enumerate((max(TIMEOUT * 2, 20), max(TIMEOUT * 3, 30))):
            st2, ms2, raw2, err2 = get(src["api"] + "?ac=detail&pg=1",
                                       src["referer"], timeout=tmo)
            if st2 == 200:
                st, ms, raw, err = st2, ms2, raw2, err2
                r["ms"] = ms
                r["retried"] = i + 1
                break
            st, ms, raw, err = st2, ms2, raw2, err2
            r["ms"] = ms
    if st != 200:
        r["err"] = "list http %s %s" % (st, err or "")
        return r
    d = as_json(raw)
    if not isinstance(d, dict):
        r["err"] = "list not json (%d bytes)" % len(raw)
        return r
    lst = d.get("list") or []
    if not isinstance(lst, list) or not lst:
        r["err"] = "list empty"
        return r

    r["n"] = len(lst)
    try:
        r["total"] = int(d.get("total") or 0)
    except Exception:
        r["total"] = 0
    for it in lst:
        if (it.get("vod_pic") or "").strip():
            r["pic"] += 1
        c = (it.get("vod_content") or "") + (it.get("vod_blurb") or "")
        if len(c.strip()) >= 20:
            r["intro"] += 1

    cls = d.get("class")
    if isinstance(cls, list):
        for c in cls:
            try:
                r["classes"].append({"id": int(c.get("type_id")), "name": str(c.get("type_name") or "")})
            except Exception:
                pass
    if not r["classes"]:
        # ac=detail 大多数源不回 class 表，得单独问一次 ac=list。
        # 这一发只为分类，不参与打分，拿不到就算了。
        st2, _, raw2, _ = get(src["api"] + "?ac=list", src["referer"])
        if st2 == 200:
            d2 = as_json(raw2)
            if isinstance(d2, dict) and isinstance(d2.get("class"), list):
                for c in d2["class"]:
                    try:
                        r["classes"].append({"id": int(c.get("type_id")),
                                             "name": str(c.get("type_name") or "")})
                    except Exception:
                        pass
    r["ok"] = True
    r["first"] = lst[0]
    return r


def probe_detail(src, first):
    r = {"ok": False, "eps": 0, "err": None}
    vid = first.get("vod_id")
    if vid is None:
        r["err"] = "no vod_id"
        return r
    st, ms, raw, err = get("%s?ac=detail&ids=%s" % (src["api"], vid), src["referer"])
    if st != 200:
        r["err"] = "detail http %s" % st
        return r
    d = as_json(raw)
    lst = (d or {}).get("list") if isinstance(d, dict) else None
    if not lst:
        r["err"] = "detail empty"
        return r
    play = lst[0].get("vod_play_url") or ""
    # "第01集$https://...#第02集$https://..."
    pairs = 0
    for ep in play.split("#"):
        if "$" in ep and "http" in ep:
            pairs += 1
    r["eps"] = pairs
    r["ok"] = pairs > 0
    if not pairs:
        r["err"] = "no play url pairs"
    return r


def probe_search(src):
    st, ms, raw, err = get(src["api"] + "?ac=detail&wd=%E5%BA%86%E4%BD%99%E5%B9%B4", src["referer"])
    if st != 200:
        return {"ok": False, "n": 0, "ms": ms, "err": "http %s" % st}
    d = as_json(raw)
    lst = (d or {}).get("list") if isinstance(d, dict) else None
    if not isinstance(lst, list):
        return {"ok": False, "n": 0, "ms": ms, "err": "not json list"}
    return {"ok": len(lst) > 0, "n": len(lst), "ms": ms, "err": None}


def probe_suggest(src):
    root = src["api"].split("://", 1)
    if len(root) < 2:
        return {"ok": False, "n": 0}
    base = root[0] + "://" + root[1].split("/")[0]
    url = base + "/index.php/ajax/suggest?mid=1&wd=fanhua"
    st, ms, raw, err = get(url, src["referer"])
    if st != 200:
        return {"ok": False, "n": 0, "ms": ms}
    d = as_json(raw)
    lst = (d or {}).get("list") if isinstance(d, dict) else None
    return {"ok": isinstance(lst, list) and len(lst) > 0,
            "n": len(lst) if isinstance(lst, list) else 0, "ms": ms}


def probe_play(src, first):
    """m3u8 TTFB + 分片速率 + Range + 抖动。这是「高速」的核心证据。"""
    r = {"ok": False, "ttfb_ms": 0, "kbps": 0, "range": False,
         "burst": [], "err": None, "vq": ""}
    play = first.get("vod_play_url") or ""
    m = M3U8_RE.search(play)
    if not m:
        r["err"] = "no m3u8 in first item"
        return r
    url = m.group(0)

    st, ms, raw, err = get(url, src["referer"])
    if st != 200:
        r["err"] = "m3u8 http %s" % st
        return r
    txt = raw.decode("utf-8", "ignore")
    if "#EXTM3U" not in txt:
        r["err"] = "m3u8 body not playlist"
        return r
    r["ttfb_ms"] = ms

    # 取最高码率变体（如果是 master）
    media_url = url
    best_bw, best_url = -1, None
    pending_bw = -1
    for line in txt.splitlines():
        line = line.strip()
        if line.startswith("#EXT-X-STREAM-INF"):
            mb = re.search(r"BANDWIDTH=(\d+)", line)
            pending_bw = int(mb.group(1)) if mb else 0
        elif line and not line.startswith("#"):
            if pending_bw >= 0 and pending_bw > best_bw:
                best_bw = pending_bw
                best_url = abs_url(url, line)
            pending_bw = -1
    if best_url:
        media_url = best_url
        st2, ms2, raw2, _ = get(media_url, src["referer"])
        if st2 == 200:
            txt = raw2.decode("utf-8", "ignore") or txt
        # 变体拿不到就把 ttfb 留给上层判断，别静默当它 OK
        else:
            r["err"] = "variant http %s: %s" % (st2, media_url[:90])

    # 第一个分片
    seg = None
    for line in txt.splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            seg = abs_url(media_url, line)
            break
    if not seg:
        r["err"] = "no segment in media playlist"
        r["ok"] = True  # 播放列表本身可用
        return r

    for i in range(3):
        st3, ms3, chunk, _ = get(seg, src["referer"], stream=True)
        if st3 == 200 and chunk:
            kbps = 0
            if ms3 > 0:
                kbps = int(len(chunk) * 8 / ms3)
            r["burst"].append({"ms": ms3, "bytes": len(chunk), "kbps": kbps})
        else:
            r["burst"].append({"ms": ms3, "bytes": 0, "kbps": 0})
        if i == 0 and chunk[:1] == b"\x47":
            r["ok"] = True
    good = [b for b in r["burst"] if b["bytes"] > 0]
    if good:
        r["kbps"] = int(sum(b["kbps"] for b in good) / len(good))
        r["ok"] = True
    elif r["err"] is None:
        r["err"] = "segment empty: %s" % seg[:90]

    # Range 支持
    try:
        rr = S.get(seg, headers={"Range": "bytes=0-1023", "Referer": src["referer"]},
                   timeout=TIMEOUT, verify=False, stream=True)
        r["range"] = rr.status_code == 206
        rr.close()
    except Exception:
        pass

    # 编码线索（H.265 在老电视上极不稳）
    if best_bw > 0:
        r["vq"] = "master bw=%d" % best_bw
    return r


# ---------------------------------------------------------------- 频道自动匹配

def match_channels(classes):
    """把源自己的分类名映射到统一频道。classes = [{id,name}]。"""
    out = {}
    if not classes:
        return out
    for cid, label, kws in CHANNELS:
        if cid == "rec":
            out[cid] = 0
            continue
        for kw in kws:
            hit = None
            for c in classes:
                nm = c["name"]
                if nm == kw:
                    hit = c["id"]
                    break
                if hit is None and kw in nm and len(nm) <= len(kw) + 3:
                    hit = c["id"]
            if hit is not None:
                out[cid] = hit
                break
        out.setdefault(cid, 0)
    return out


# ---------------------------------------------------------------- 打分

def clamp(v, lo=0.0, hi=1.0):
    return max(lo, min(hi, v))


def score(it):
    L, P, D, SE = it["list"], it["play"], it["detail"], it["search"]
    s = {}
    play_known = bool(P.get("ok"))

    # 速度 45
    s["list_ms"] = 25 * clamp(1 - (L["ms"] - 250) / 2750.0) if L["ok"] else 0
    if play_known:
        s["ttfb"] = 12 * clamp(1 - (P["ttfb_ms"] - 200) / 2800.0)
        s["kbps"] = 8 * clamp(P.get("kbps", 0) / 6000.0)
    else:
        # 播放链路这条根本没测成（多数是 runner 出网被 CDN 地区限流），
        # 给个中间值：既不奖励也不惩罚，免得「测不到」被误当成「很慢」而把好源沉底。
        s["ttfb"] = 6.0
        s["kbps"] = 4.0

    # 内容 35
    n = max(1, L["n"])
    s["intro"] = 15 * clamp((L["intro"] / float(n) - 0.4) / 0.6)
    s["pic"] = 10 * clamp((L["pic"] / float(n) - 0.5) / 0.5)
    s["total"] = 10 * clamp((L["total"] / 150000.0) ** 0.5)

    # 能力 20
    s["search"] = 8 if SE["ok"] else 0
    s["eps"] = 6 * clamp(D["eps"] / 20.0) if D["ok"] else 0
    s["range"] = 3 if P.get("range") else 0
    bursts = [b for b in P.get("burst", []) if b["bytes"] > 0]
    if len(bursts) >= 2:
        lo, hi = min(b["kbps"] for b in bursts), max(b["kbps"] for b in bursts)
        s["stability"] = 3 * clamp(lo / float(hi)) if hi > 0 else 0
    else:
        s["stability"] = 0

    total = sum(s.values())
    it["score_parts"] = {k: round(v, 1) for k, v in s.items()}
    return round(total, 1)


def grade(sc):
    if sc >= 80:
        return "A"
    if sc >= 65:
        return "B"
    if sc >= 45:
        return "C"
    return "D"


# ---------------------------------------------------------------- 主流程

def scan_one(src):
    t0 = now_ms()
    it = {"key": src["key"], "api": src["api"], "referer": src["referer"], "from": src["from"]}
    # types 原样带过来：它是 Src.java 里「CH_MOVIE..CH_SUSPENSE 各频道 type_id」的
    # 逗号串，巡检不走它。断不能用上面自动匹配的 type_map 去替代 ——
    # CHANNELS 只有 22 个频道且缺 DRAMA/CNVAR 等，按它重排会把频道映射写错位，
    # 电视上表现为「点电影出来的是短剧」。
    it["types"] = src.get("types", "")
    L = probe_list(src)
    it["list"] = {k: L[k] for k in ("ok", "ms", "n", "pic", "intro", "total", "err")}
    it["classes"] = L.get("classes", [])
    it["type_map"] = match_channels(it["classes"])

    first = L.get("first") or {}
    it["detail"] = probe_detail(src, first) if L["ok"] else {"ok": False, "eps": 0, "err": "skip"}
    it["search"] = probe_search(src) if L["ok"] else {"ok": False, "n": 0, "ms": 0, "err": "skip"}
    it["suggest"] = probe_suggest(src) if L["ok"] else {"ok": False, "n": 0}
    it["play"] = probe_play(src, first) if (L["ok"] and first) else {"ok": False, "err": "skip"}

    # 判 DEAD 只看「结构性」证据：列表拿不到、或连选集都解不出来。这两条跟在哪跑无关。
    # 播放探测**不能**当硬失败：m3u8 的分片 CDN 普遍按地区限流。GitHub Actions 的
    # runner 在境外，ffzy/cmov（共用 vip.ffzy-plays.com）、dytt（vip.dytt-luck.com）、
    # uku、zuidazy 一律回 403；而电视和本机走同一条 Passwall，这些源全都能播。
    # 2026-11-13 首次真跑巡检就因为这个把线上源表从 14 源削到 9 源。
    it["play_ok"] = bool(it["play"].get("ok"))
    hard_fail = (not L["ok"]) or (not it["detail"]["ok"])
    if hard_fail:
        it["status"] = "DEAD"
    elif not it["play_ok"]:
        it["status"] = "OK_NOPLAY"
    else:
        it["status"] = "OK" if it["search"]["ok"] else "OK_NOSEARCH"
    it["score"] = 0 if hard_fail else score(it)
    it["grade"] = "-" if hard_fail else grade(it["score"])
    it["cost_ms"] = now_ms() - t0
    it["sample"] = first.get("vod_name") if isinstance(first, dict) else None
    return it


# ---------------------------------------------------------------- 源表 output

# 这份文件的格式就是 Src.load() 要吃的那份，**改动必须同步 Src.java**：
#   {"version":1,"updated":"...","sources":[{"key","api","referer","types"}]}
# types 用逗号串（Src.typesOf 两种形态都收，但逗号串更好读、diff 更短）。
SOURCES_JSON = os.path.join(HERE, "..", "app", "src", "main", "assets", "sources.json")


def write_sources_json(results, path=SOURCES_JSON):
    """把巡检结果写成 App 用的源表，按分数从高到低 —— 顺序就是优先级。

    为什么只放非 DEAD 的源：这张表是**线上生效表**，不是巡检报告。DEAD 的源
    进去以后只会拖慢首屏（每个频道都先试一遍再失败），而且一旦全部 DEAD，
    Src.load() 会整体拒绝、退回内置表 —— 与其让它触发兜底，不如这里就别写。

    OK_NOPLAY 必须保留：它代表「列表和选集都正常，只是这次没测到播放链路」，
    在境外 runner 上绝大多数是 CDN 地区限流，不是源坏了（见 scan_one 里的注释）。
    照旧保留，靠 score 里的折中分把它排在真实测通的源后面。
    """
    alive = [it for it in results if it.get("status") != "DEAD"]
    # 分数高的在前；分数相同保持巡检结果的原始顺序（Python 的 sort 是稳定的），
    # 这样「同分时谁在前面」跟人看报告的直觉一致，不会每天抖动。
    alive.sort(key=lambda x: -float(x.get("score") or 0))

    items = []
    for it in alive:
        api = (it.get("api") or "").strip()
        key = (it.get("key") or "").strip()
        # Src.load() 会丢掉 api 不含 scheme 的项，这里提前过滤，避免写进无效数据
        if not key or "://" not in api:
            continue
        items.append({
            "key": key,
            "api": api,
            "referer": (it.get("referer") or "").strip(),
            "types": (it.get("types") or "").strip(),
        })

    doc = {
        "version": 1,
        "updated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "sources": items,
    }
    d = os.path.dirname(os.path.abspath(path))
    if not os.path.isdir(d):
        os.makedirs(d)
    io.open(path, "w", encoding="utf-8").write(
        json.dumps(doc, ensure_ascii=False, indent=1) + "\n")
    return path, len(items)


def main():
    global TIMEOUT
    ap = argparse.ArgumentParser()
    ap.add_argument("--tvbox", default=None, help="TVBox 配置 json，抽取 type=1 站点")
    ap.add_argument("--pool", default=None, help="纯 base 列表文件")
    ap.add_argument("--workers", type=int, default=WORKERS)
    ap.add_argument("--timeout", type=int, default=TIMEOUT)
    ap.add_argument("--only", default=None, help="只测这些 key，逗号分隔")
    ap.add_argument("--out", default=OUT_JSON)
    ap.add_argument("--sources-out", default=SOURCES_JSON,
                    help="App 用的源表 json（Src.load 的输入），空字符串表示不生成")
    args = ap.parse_args()

    TIMEOUT = args.timeout

    cands = from_src_java(SRC_JAVA)
    if args.tvbox:
        cands += from_tvbox(args.tvbox, cands)
    if args.pool:
        cands += from_pool(args.pool, cands)
    if args.only:
        keep = {k.strip() for k in args.only.split(",") if k.strip()}
        cands = [c for c in cands if c["key"] in keep]

    print("[scan] candidates=%d  (src.java=%d)" %
          (len(cands), len([c for c in cands if c["from"] == "Src.java"])))
    for c in cands:
        print("   %-14s %-12s %s" % (c["key"], c["from"], c["api"]))

    results = []
    with futures.ThreadPoolExecutor(max_workers=args.workers) as ex:
        futs = {ex.submit(scan_one, c): c for c in cands}
        for i, f in enumerate(futures.as_completed(futs), 1):
            c = futs[f]
            try:
                it = f.result()
            except Exception as e:  # noqa: BLE001
                it = {"key": c["key"], "api": c["api"], "referer": c["referer"],
                      "from": c["from"], "status": "DEAD", "score": 0, "grade": "-",
                      "list": {"ok": False, "err": "exception %s" % e}, "play": {},
                      "detail": {}, "search": {}, "suggest": {}, "classes": []}
            results.append(it)
            print("[%2d/%d] %-14s %-7s %5.1f %s  list=%sms n=%s pic=%s intro=%s play=%sms %skbps" % (
                i, len(cands), it["key"], it.get("status", "?"), it.get("score", 0),
                it.get("grade", "-"),
                it.get("list", {}).get("ms"),
                it.get("list", {}).get("n"),
                it.get("list", {}).get("pic"),
                it.get("list", {}).get("intro"),
                (it.get("play") or {}).get("ttfb_ms"),
                (it.get("play") or {}).get("kbps")))

    results.sort(key=lambda x: (x.get("status") == "DEAD", -x.get("score", 0)))

    payload = {
        "generated": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "ua": UA,
        "channels": [{"id": c[0], "label": c[1]} for c in CHANNELS],
        "sources": results,
    }
    io.open(args.out, "w", encoding="utf-8").write(
        json.dumps(payload, ensure_ascii=False, indent=1))

    # 人读表
    lines = ["# 采集源健康度（%s）" % time.strftime("%Y-%m-%d %H:%M"),
             "",
             "| # | key | 等级 | 分 | 来源 | list ms | 条数 | 海报 | 简介 | TTFB | kbps | 搜索 | 选集 | 片库 | 状态 |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for i, it in enumerate(results, 1):
        L, P, D, SE = it.get("list", {}), it.get("play") or {}, it.get("detail") or {}, it.get("search") or {}
        lines.append("| %d | `%s` | **%s** | %.1f | %s | %s | %s | %s/%s | %s/%s | %sms | %s | %s | %s | %s | %s |" % (
            i, it["key"], it.get("grade", "-"), it.get("score", 0), it.get("from", ""),
            L.get("ms", "-"), L.get("n", "-"), L.get("pic", "-"), L.get("n", "-"),
            L.get("intro", "-"), L.get("n", "-"),
            P.get("ttfb_ms", "-"), P.get("kbps", "-"),
            ("%s条" % SE.get("n")) if SE.get("ok") else "✗",
            ("%s集" % D.get("eps")) if D.get("ok") else "✗",
            L.get("total", "-"), it.get("status", "-")))
    lines.append("")
    lines.append("## 自动匹配的频道 type_id")
    lines.append("")
    for it in results:
        if it.get("status") == "DEAD":
            continue
        tm = it.get("type_map") or {}
        got = {k: v for k, v in tm.items() if v and k != "rec"}
        lines.append("- `%s`：%s" % (it["key"], ", ".join("%s=%s" % (k, v) for k, v in got.items())))
    io.open(OUT_MD, "w", encoding="utf-8").write("\n".join(lines))

    alive = [r for r in results if r.get("status") != "DEAD"]
    print("\n[scan] alive=%d dead=%d -> %s / %s" %
          (len(alive), len(results) - len(alive), args.out, OUT_MD))
    print("[scan] top: " + ", ".join("%s(%.0f)" % (r["key"], r["score"]) for r in alive[:8]))

    # App 源表最后写：万一前面哪里炸了，至少不会留下一份半截的线上数据。
    # 一个都没活下来时**不写**——保留上一版源表，比写一份空表把线上打成内置好。
    if args.sources_out:
        if alive:
            p, n = write_sources_json(results, args.sources_out)
            print("[scan] sources.json %d 个源 -> %s" % (n, p))
        else:
            print("[scan] 全部 DEAD，保留上一版 sources.json 不动")
    return 0


if __name__ == "__main__":
    sys.exit(main())
