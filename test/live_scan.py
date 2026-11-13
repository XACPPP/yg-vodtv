# -*- coding: utf-8 -*-
"""live_scan.py -- 直播源采集 + 测速 + 分组归一，产出 app/src/main/assets/live.json

为什么必须测速，不能照抄源站列表
    Guovin/iptv-api 的 result.txt 有 1660 条 URL，但实测采样 134 条只有 54 条真能播（40%）。
    源站给的是「它扫过」，不是「现在能播」；直接塞进 App 的结果就是用户点 10 个台 6 个黑屏。
    本脚本对每条 URL 发一次 GET，只有 200 + 响应体前 4096 字节含 #EXTM3U 才算可播，
    并按 TTFB 排序 —— 「卡了换源」要靠同一频道的多条 URL，所以要留住最快的 3 条。

为什么判定只能靠 200 + #EXTM3U
    本机开着 Mihomo TUN，任何地址任何端口都「连得上」，TCP 连通性完全失去判别力。
    必须看真拿到播放列表内容（#EXTM3U）才算数。

IPv6 必须整条过滤
    电视端 Http.java 静态块里设了 java.net.preferIPv4Stack=true，局域网 IPv6 也禁着。
    源里混着 http://[2409:...]:port/... 这种，手机/电脑能放，电视放不了 —— 直接丢。
    同时复用 source_scan.py 顶部对 socket.getaddrinfo 打的 IPv4-only 补丁，
    否则域名解析拿到 AAAA 记录也会连出一个电视上不存在的「可播」。

分组归一
    源站 41 个 emoji 分组（📺央视频道 / 💰央视付费频道 / ☘️广东频道 ……）对电视端太碎，
    收敛成 8 个固定组，顺序写死，只输出有频道的组。

用法
    python live_scan.py                  # 全量，写 assets/live.json
    python live_scan.py --limit 200      # 只测前 200 条（调试），默认写 test/live_preview.json
    python live_scan.py --out xxx.json   # 指定输出

输出
    {version, updated, total, groups:[{id,name,chs:[{name,logo,urls[]}]}]}
    控制台只打 ASCII —— Windows GBK 控制台遇到 emoji 频道名会直接 UnicodeEncodeError。
"""
from __future__ import annotations

import argparse
import concurrent.futures as futures
import datetime
import io
import json
import os
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT_JSON = os.path.join(ROOT, "app", "src", "main", "assets", "live.json")
PREVIEW_JSON = os.path.join(HERE, "live_preview.json")

# 复用 source_scan.py 的 UA / IPv4-only 补丁；它 import 时只是建 Session，没有副作用。
sys.path.insert(0, HERE)
try:
    import source_scan as _sc
    UA = _sc.UA
    _HAVE_SC = True
except Exception:  # noqa: BLE001  单独跑也要能用
    _HAVE_SC = False
    UA = ("Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 "
          "(KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36")
    import socket as _socket
    _orig_gai = _socket.getaddrinfo

    def _v4_only(host, port, family=0, type=0, proto=0, flags=0):
        return _orig_gai(host, port, _socket.AF_INET, type, proto, flags)

    _socket.getaddrinfo = _v4_only

import requests

try:
    requests.packages.urllib3.disable_warnings()
except Exception:  # noqa: BLE001
    pass

SRC1 = "https://raw.githubusercontent.com/Guovin/iptv-api/gd/output/result.txt"
SRC2 = "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.txt"

UI_UA = ("Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 "
         "(KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36")
CONNECT_TIMEOUT = 4
READ_TIMEOUT = 6
FIRST_BYTES = 4096
WORKERS = 24
MAX_URLS_PER_CH = 3
MIN_ALIVE_CH = 20
GENRE_SUFFIX = ",#genre#"

# 固定 8 组，顺序写死。空的关键字列表 = 兜底组。
GROUPS = [
    ("cctv", "央视", ["央视频道", "央视付费"]),
    ("sat", "卫视", ["卫视频道"]),
    ("sports", "体育", ["体育频道"]),
    ("movie", "电影", ["电影频道", "经典剧场"]),
    ("hkmotw", "港澳台", ["港·澳·台"]),
    ("kids", "动画", ["动画频道", "游戏频道"]),
    ("local", "地方", [
        # `地方频道` 是源站 315 条最大的一个分组（第一财经 / 北京科教 / 各市台……），
        # 表里没列，但语义就是地方台；不并在 local 会让 other 变成 264 个台的垃圾桶。
        "地方频道",
        "广东频道", "浙江频道", "北京频道", "上海频道", "江苏频道", "湖南频道",
        "湖北频道", "河南频道", "河北频道", "山东频道", "四川频道", "福建频道",
        "安徽频道", "广西频道", "辽宁频道", "陕西频道", "黑龙江频道", "山西频道",
        "贵州频道", "云南频道", "甘肃频道", "海南频道", "天津频道", "新疆频道",
        "吉林频道", "内蒙古频道", "宁夏频道", "青海频道", "重庆频道",
    ]),
    ("other", "其他", []),
]

_DOTS = "·・•‧∙･"


def _nz(s):
    """去掉各种间隔号，港澳台那组源站写的是 `港·澳·台`，间隔号字符不统一。"""
    for d in _DOTS:
        s = s.replace(d, "")
    return s


# ---------------------------------------------------------------- 采集

def fetch_text(url, timeout=25):
    """拉源站文本。失败返回 None（第二个源失败不算错）。"""
    try:
        r = requests.get(url, headers={"User-Agent": UI_UA, "Accept": "*/*"},
                         timeout=timeout, verify=False)
        if r.status_code != 200:
            return None
        return r.content.decode("utf-8", "ignore")
    except Exception:  # noqa: BLE001
        return None


def url_ok(u):
    """过滤：非 http(s) / 带空格 / IPv6 字面量（电视端不认）。"""
    if not u or " " in u or "\t" in u:
        return False
    if not (u.startswith("http://") or u.startswith("https://")):
        return False
    host = u.split("//", 1)[1].split("/", 1)[0]
    host = host.rsplit("@", 1)[-1]
    if host.startswith("["):
        return False
    if host.count(":") > 1:  # 裸 IPv6（理论上非法 URL，防御一下）
        return False
    return True


def norm_name(name):
    """频道名收敛：`CCTV5` -> `CCTV-5`，两个源就能并到同一台，换源才有得换。"""
    n = " ".join(name.split())
    low = n.upper()
    if low.startswith("CCTV"):
        tail = n[4:].strip()
        if tail and tail[0] not in "-":
            n = "CCTV-" + tail
    return n


def parse_text(text):
    """解析 `频道名,url` + `分组名,#genre#`。返回 [(genre, name, url)]。"""
    out = []
    genre = ""
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("//"):
            continue
        if line.endswith("#genre#"):
            g = line[:-len("#genre#")]
            if g.endswith(","):
                g = g[:-1]
            genre = g.strip()
            continue
        name, sep, url = line.partition(",")
        if not sep:
            continue
        name = norm_name(name.strip())
        url = url.strip()
        if not name or not url:
            continue
        out.append((genre, name, url))
    return out


def map_genre(genre):
    """按 GROUPS 表顺序匹配，一个源分组只归一个目标组。"""
    g = _nz(genre)
    for gid, _name, keys in GROUPS:
        for k in keys:
            if _nz(k) and _nz(k) in g:
                return gid
    return "other"


# ---------------------------------------------------------------- 测速

_TL = threading.local()


def _sess():
    s = getattr(_TL, "s", None)
    if s is None:
        s = requests.Session()
        s.headers.update({"User-Agent": UI_UA, "Accept": "*/*",
                          "Accept-Encoding": "gzip, deflate", "Connection": "close"})
        s.verify = False
        _TL.s = s
    return s


def probe(url):
    """返回 (ok, ttfb_ms, status)。ok 必须同时满足 200 且首 4096 字节含 #EXTM3U。"""
    t0 = time.time()
    code = 0
    buf = b""
    ttfb = -1
    try:
        r = _sess().get(url, timeout=(CONNECT_TIMEOUT, READ_TIMEOUT),
                        stream=True, allow_redirects=True, verify=False)
        code = r.status_code
        try:
            if code == 200:
                for chunk in r.iter_content(1024):
                    if not chunk:
                        continue
                    if ttfb < 0:
                        ttfb = int((time.time() - t0) * 1000)  # 首块数据到达
                    buf += chunk
                    # 拿到 #EXTM3U 就够判定了，不必等满 4096，也不必等服务器断连
                    if len(buf) >= FIRST_BYTES or b"#EXTM3U" in buf:
                        break
        finally:
            r.close()
    except Exception:  # noqa: BLE001
        return False, -1, 0
    if ttfb < 0:
        ttfb = int((time.time() - t0) * 1000)
    ok = (code == 200) and (b"#EXTM3U" in buf[:FIRST_BYTES])
    return ok, ttfb, code


def probe_all(urls, workers):
    res = {}
    done = 0
    alive = 0
    t0 = time.time()
    n = len(urls)
    with futures.ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(probe, u): u for u in urls}
        for f in futures.as_completed(futs):
            u = futs[f]
            try:
                ok, ttfb, code = f.result()
            except Exception:  # noqa: BLE001
                ok, ttfb, code = False, -1, 0
            res[u] = (ok, ttfb, code)
            done += 1
            if ok:
                alive += 1
            if done % 100 == 0 or done == n:
                print("[live] probed %d/%d alive=%d" % (done, n, alive))
    return res, alive, time.time() - t0


# ---------------------------------------------------------------- 组装 / 输出

def build(pairs, probed):
    """pairs: gid -> [(name, url)]；probed: url -> (ok, ttfb, code)。"""
    groups_out = []
    total_chs = 0
    stat = []
    for gid, gname, _keys in GROUPS:
        buckets = {}
        for name, url in pairs.get(gid, ()):
            p = probed.get(url)
            if not p or not p[0]:
                continue
            buckets.setdefault(name, []).append((p[1], url))
        chs = []
        for name, lst in buckets.items():
            lst.sort(key=lambda x: (x[0], x[1]))
            urls = []
            for _ttfb, u in lst:
                if u in urls:
                    continue
                urls.append(u)
                if len(urls) >= MAX_URLS_PER_CH:
                    break
            if not urls:
                continue
            chs.append((lst[0][0], {"name": name, "logo": "", "urls": urls}))
        if not chs:
            continue
        chs.sort(key=lambda x: (x[0], x[1]["name"]))
        groups_out.append({"id": gid, "name": gname,
                           "chs": [c[1] for c in chs]})
        total_chs += len(chs)
        stat.append((gid, len(chs)))
    return groups_out, total_chs, stat


def write_json(path, groups_out, total_chs):
    doc = {
        "version": 1,
        "updated": datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"),
        "total": total_chs,
        "groups": groups_out,
    }
    txt = json.dumps(doc, ensure_ascii=False, indent=1)
    d = os.path.dirname(path)
    if d and not os.path.isdir(d):
        os.makedirs(d)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(txt)
        f.write("\n")
    return os.path.getsize(path)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0, help="only probe first N urls (debug)")
    ap.add_argument("--out", default="", help="output json path")
    ap.add_argument("--workers", type=int, default=WORKERS)
    args = ap.parse_args(argv)

    t_start = time.time()
    # Windows GBK 控制台：本项目路径和频道名都是中文，直接 print 会 UnicodeEncodeError
    # 把整个巡检打断。这里把 stdout 锁成 ASCII + replace，中文一律退化成 '?'。
    try:
        sys.stdout.reconfigure(encoding="ascii", errors="replace")
    except Exception:  # noqa: BLE001
        pass
    raw = []
    for i, src in enumerate((SRC1, SRC2), 1):
        text = fetch_text(src)
        if text is None:
            print("[live] source%d FAIL (skipped)" % i)
            continue
        items = parse_text(text)
        gs = sorted(set(g for g, _n, _u in items))
        print("[live] source%d ok lines=%d groups=%d" % (i, len(items), len(gs)))
        raw.extend(items)

    if not raw:
        print("[live] no source reachable, keep old file")
        return 0

    # 归组 + URL 过滤 + 全局去重（同一条 URL 只测一次）
    pairs = {}
    seen = set()
    urls = []
    drop_genre = drop_ipv6 = drop_bad = 0
    for genre, name, url in raw:
        if "更新时间" in genre:
            drop_genre += 1
            continue
        if not url_ok(url):
            if "[" in url.split("//", 1)[-1].split("/", 1)[0]:
                drop_ipv6 += 1
            else:
                drop_bad += 1
            continue
        gid = map_genre(genre)
        pairs.setdefault(gid, []).append((name, url))
        if url not in seen:
            seen.add(url)
            urls.append(url)

    print("[live] kept=%d uniq_url=%d (drop genre=%d ipv6=%d bad=%d)"
          % (len(raw) - drop_genre - drop_ipv6 - drop_bad, len(urls),
             drop_genre, drop_ipv6, drop_bad))

    todo = urls[:args.limit] if args.limit and args.limit > 0 else urls
    probed, alive, cost = probe_all(todo, max(1, args.workers))
    print("[live] probe done alive=%d/%d in %.1fs" % (alive, len(todo), cost))

    groups_out, total_chs, stat = build(pairs, probed)

    debug = bool(args.limit and args.limit > 0)
    out = args.out
    if not out:
        out = PREVIEW_JSON if debug else OUT_JSON
    if debug and not args.out:
        print("[live] --limit debug run -> %s (assets/live.json untouched)"
              % os.path.basename(out))

    # 安全阀只管线上那份；调试预览照写，否则 --limit 永远看不到结果。
    if total_chs < MIN_ALIVE_CH and not debug:
        print("[live] too few alive channels (%d), keep old file" % total_chs)
        return 0

    size = write_json(out, groups_out, total_chs)
    print("[live] groups: " + " ".join("%s=%d" % (g, n) for g, n in stat))
    n_url = sum(len(c["urls"]) for g in groups_out for c in g["chs"])
    print("[live] channels=%d urls=%d bytes=%d -> %s (%.1fs)"
          % (total_chs, n_url, size, out, time.time() - t_start))
    return 0


if __name__ == "__main__":
    sys.exit(main())
