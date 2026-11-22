#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 YG影视 的电视直播源导成飞牛影视能吃的 m3u。

飞牛影视的「电视直播」媒体库支持上传 m3u 文件（填局域网 URL 会被判非法），
所以这里生成一个本地文件，直接上传即可。

数据来源（都在 YG影视TV/app/src/main/assets/）：
    live_jx.txt   江西电信 IPTV，行格式 组|台名|url
    live.json     公共源，{groups:[{id,name,chs:[{name,logo,urls:[...]}]}]}

只保留 http/https 的 HLS 地址：飞牛不支持 udp/rtp 组播（论坛已确认）。

用法:
    python gen_live_m3u.py                      # 全量导出到 out/yg-live.m3u
    python gen_live_m3u.py --check              # 先探活，只留能播的
    python gen_live_m3u.py --check --workers 24 --out out/yg-live.m3u
"""
import argparse
import json
import os
import re
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse
from urllib.request import Request, urlopen

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.abspath(os.path.join(HERE, "..", "app", "src", "main", "assets"))
UA = "Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0 Safari/537.36"
SKIP_SCHEME = ("udp://", "rtp://", "rtsp://", "igmp://", "file://")
GROUP_NAME = {"jxitv": "江西电信", "cctv": "央视",
              "jxws": "江西卫视", "jxcctv": "江西央视", "jxfilm": "江西影视",
              "jxmore": "江西其他"}


_print_lock = threading.Lock()


def log(*a):
    with _print_lock:
        print(*a, flush=True)


def is_http(u):
    return u.lower().startswith(("http://", "https://"))


def load_jx(path):
    """返回 [(group, name, url)]"""
    out = []
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("|")
            if len(parts) < 3:
                continue
            grp, name, url = parts[0], parts[1], parts[2]
            grp = GROUP_NAME.get(grp, grp)
            out.append((grp, name, url))
    return out


def load_json(path):
    out = []
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    for g in doc.get("groups") or []:
        gname = g.get("name") or g.get("id") or "其他"
        for ch in g.get("chs") or []:
            name = ch.get("name") or "未命名"
            for url in ch.get("urls") or []:
                out.append((gname, name, url))
    return out


def probe(url, timeout=8):
    """能拉到 #EXTM3U 或返回 200 且带 ts 分片，就算活。"""
    try:
        req = Request(url, headers={"User-Agent": UA})
        with urlopen(req, timeout=timeout) as r:
            if r.status != 200:
                return False
            head = r.read(2048).decode("utf-8", "ignore")
        if "#EXTM3U" in head or "#EXTINF" in head:
            return True
        body = head.lstrip()
        return body.startswith("#EXTM3U") or ".ts" in head
    except Exception:  # noqa: BLE001
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(HERE, "out", "yg-live.m3u"))
    ap.add_argument("--check", action="store_true", help="探活，丢弃播不了的地址")
    ap.add_argument("--workers", type=int, default=20)
    ap.add_argument("--timeout", type=int, default=8)
    ap.add_argument("--jx", default=os.path.join(ASSETS, "live_jx.txt"))
    ap.add_argument("--json", default=os.path.join(ASSETS, "live.json"))
    ap.add_argument("--all-lines", action="store_true",
                    help="每个频道的所有备用线路都输出（默认每台只留线路1）")
    a = ap.parse_args()

    items = load_jx(a.jx) + load_json(a.json)
    total = len(items)
    items = [(g, n, u) for (g, n, u) in items if is_http(u)]
    log("原始 %d 条，http 可尝试 %d 条（丢弃 udp/rtp 等 %d 条）"
        % (total, len(items), total - len(items)))

    if a.check:
        urls = [u for _, _, u in items]
        log("开始探活 %d 个地址，并发 %d ..." % (len(urls), a.workers))
        alive = {}
        done = [0]

        def work(u):
            ok = probe(u, a.timeout)
            done[0] += 1
            if done[0] % 50 == 0:
                log("  已测 %d/%d，存活 %d" % (done[0], len(urls), sum(1 for v in alive.values() if v)))
            return u, ok

        with ThreadPoolExecutor(max_workers=a.workers) as ex:
            for u, ok in ex.map(work, urls):
                alive[u] = ok
        kept = [(g, n, u) for (g, n, u) in items if alive.get(u)]
        log("探活结果：存活 %d / %d" % (len(kept), len(items)))
        items = kept

    # 按频道名归并：保持出现顺序，第一条线路优先
    order, by_name = [], {}
    for g, n, u in items:
        k = (g, n)
        if k not in by_name:
            by_name[k] = []
            order.append(k)
        if u not in by_name[k]:
            by_name[k].append(u)

    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    n_ch = 0
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("#EXTM3U\n")
        for (g, n) in order:
            urls = by_name[(g, n)]
            lines = urls if a.all_lines else urls[:1]
            for i, u in enumerate(lines):
                label = n if len(lines) == 1 else "%s-线路%d" % (n, i + 1)
                host = urlparse(u).netloc
                f.write('#EXTINF:-1 tvg-name="%s" tvg-logo="" group-title="%s",%s\n%s\n'
                        % (label, g, label, u))
                n_ch += 1
    log("已写出 %s：%d 条（频道 %d 个）" % (a.out, n_ch, len(order)))
    log("提示：飞牛影视 → 媒体库 → 新增「电视直播」→ 上传这个 m3u 文件。")


if __name__ == "__main__":
    sys.exit(main())
