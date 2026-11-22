#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""端到端验证直播源：m3u 里的地址到底能不能播出一帧画面。

只看播放列表返回 200 是不够的（探活阶段就吃了这个亏），
这里必须走到「拉到第一个分片」才知道飞牛/手机能不能出画面。

判定分级：
  OK        播放列表 + 首个分片都正常，分片是合法 TS(0x47) 或 fMP4(ftyp)
  LIST_ONLY 播放列表能读，但分片拉不到 / 不是合法容器 → 播放器必然黑屏
  DEAD      播放列表就打不开（403/404/超时）

用法:
  python check_play.py                        # 查 out/yg-live-alive.m3u
  python check_play.py --m3u out/yg-live.m3u --workers 24
  python check_play.py --out out/play_report.csv
"""
import argparse
import csv
import os
import re
import ssl
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
UA = ("Mozilla/5.0 (Linux; Android 9; VIDAA) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/90.0 Mobile Safari/537.36")
_SSL = ssl._create_unverified_context()


def http_get(url, timeout=8, referer=None, max_bytes=None):
    hdr = {"User-Agent": UA, "Accept": "*/*"}
    if referer:
        hdr["Referer"] = referer
    req = urllib.request.Request(url, headers=hdr)
    with urllib.request.urlopen(req, timeout=timeout, context=_SSL) as r:
        if max_bytes:
            data = r.read(max_bytes)
        else:
            data = r.read()
        return r.getcode(), r.geturl(), data


def first_segment(playlist_text, base_url):
    """从 m3u8 里找第一个真正的分片地址；若是 master 播放列表，先下钻一层。"""
    lines = [l.strip() for l in playlist_text.splitlines() if l.strip()]
    variants = []
    for i, l in enumerate(lines):
        if l.startswith("#EXT-X-STREAM-INF"):
            for nxt in lines[i + 1:]:
                if not nxt.startswith("#"):
                    variants.append(nxt)
                    break
    if variants:
        return variants[0], True
    for l in lines:
        if l.startswith("#"):
            continue
        return l, False
    return None, False


def absolute(url, base):
    if url.startswith("http://") or url.startswith("https://"):
        return url
    if url.startswith("/"):
        m = re.match(r"(https?://[^/]+)", base)
        return (m.group(1) if m else "") + url
    return base.rsplit("/", 1)[0] + "/" + url


def check_one(item):
    name, url = item
    rec = {"name": name, "url": url, "verdict": "", "detail": "", "bytes": 0}
    try:
        code, final, body = http_get(url, timeout=8)
    except Exception as e:  # noqa: BLE001
        rec["verdict"] = "DEAD"
        rec["detail"] = "播放列表打不开: %s" % repr(e)[:80]
        return rec
    text = body.decode("utf-8", "ignore")
    if code != 200 or "#EXTM3U" not in text and "#EXTINF" not in text:
        rec["verdict"] = "DEAD"
        rec["detail"] = "播放列表非法 http=%s 长度=%d" % (code, len(body))
        return rec

    seg, is_master = first_segment(text, final)
    if not seg:
        rec["verdict"] = "DEAD"
        rec["detail"] = "播放列表里没有分片地址"
        return rec
    seg_url = absolute(seg, final)

    if is_master:  # 再下钻一层拿分片
        try:
            _, final2, body2 = http_get(seg_url, timeout=8)
            seg2, _ = first_segment(body2.decode("utf-8", "ignore"), final2)
            if seg2:
                seg_url = absolute(seg2, final2)
        except Exception as e:  # noqa: BLE001
            rec["verdict"] = "LIST_ONLY"
            rec["detail"] = "子播放列表失败: %s" % repr(e)[:60]
            return rec

    try:
        code2, final3, data = http_get(seg_url, timeout=10, max_bytes=188 * 8)
    except Exception as e:  # noqa: BLE001
        rec["verdict"] = "LIST_ONLY"
        rec["detail"] = "分片拉不到: %s" % repr(e)[:70]
        return rec

    rec["bytes"] = len(data)
    if not data:
        rec["verdict"] = "LIST_ONLY"
        rec["detail"] = "分片为空"
        return rec
    ts_like = data[:1] == b"\x47" or data.find(b"\x47", 0, 4) == 188 or data[188:189] == b"\x47"
    mp4_like = data[4:8] in (b"ftyp", b"styp", b"moof", b"moov")
    if ts_like or mp4_like:
        rec["verdict"] = "OK"
        rec["detail"] = "分片 %d 字节 %s" % (len(data), "TS" if ts_like else "fMP4")
    else:
        rec["verdict"] = "LIST_ONLY"
        head = data[:16].hex()
        rec["detail"] = "分片不是 TS/fMP4（头部 %s）" % head
    return rec


def load_m3u(path):
    items = []
    cur = None
    for line in open(path, encoding="utf-8", errors="ignore"):
        line = line.strip()
        if not line:
            continue
        if line.startswith("#EXTINF"):
            m = re.search(r",(.+)$", line)
            cur = (m.group(1).strip() if m else "?", line)
        elif not line.startswith("#") and cur:
            items.append((cur[0], line))
            cur = None
    return items


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--m3u", default=os.path.join(HERE, "out", "yg-live-alive.m3u"))
    ap.add_argument("--out", default=os.path.join(HERE, "out", "play_report.csv"))
    ap.add_argument("--workers", type=int, default=20)
    ap.add_argument("--show", type=int, default=12, help="控制台抽样展示几条失败详情")
    a = ap.parse_args()

    items = load_m3u(a.m3u)
    print("待检频道 %d 个 -> %s" % (len(items), a.m3u), flush=True)

    rows = []
    done = 0
    with ThreadPoolExecutor(max_workers=a.workers) as ex:
        for rec in ex.map(check_one, items):
            rows.append(rec)
            done += 1
            if done % 50 == 0:
                print("  已检 %d/%d" % (done, len(items)), flush=True)

    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    with open(a.out, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["name", "verdict", "detail", "bytes", "url"])
        w.writeheader()
        w.writerows(rows)

    stat = {}
    for r in rows:
        stat[r["verdict"]] = stat.get(r["verdict"], 0) + 1
    print("\n=== 结果 ===")
    for k in ("OK", "LIST_ONLY", "DEAD"):
        print("  %-10s %d" % (k, stat.get(k, 0)))
    print("报告: %s" % a.out)

    bad = [r for r in rows if r["verdict"] != "OK"]
    if bad:
        print("\n失败样例（前 %d 条）:" % min(a.show, len(bad)))
        for r in bad[:a.show]:
            print("  [%s] %s | %s" % (r["verdict"], r["name"], r["detail"]))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
