#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""探测直播流实际编码（H.264 / HEVC / 音频格式）。

为什么需要：飞牛影视、浏览器、手机 App 的 HLS 播放器对 HEVC(H.265)、
MP2 音频的支持很不一致，源能拉到分片 ≠ 能出画面。判活脚本只验证了
「分片拉得到」，这里再往下走一层，读 TS 里的 PMT 看真实编码。

输出 CSV: name, codec, audio, verdict, url
  verdict = H264_OK   视频 H.264（兼容性最好）
            H265      视频 HEVC，多数播放器要硬解支持
            OTHER     其他编码
            NO_PMT    拿不到 PMT（多为 fMP4 或异常流）

用法:
  python probe_codec.py                       # 查 out/yg-live-alive.m3u
  python probe_codec.py --m3u x.m3u --workers 24
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

VIDEO = {0x01: "MPEG1", 0x02: "MPEG2", 0x1B: "H264", 0x24: "HEVC", 0x10: "MPEG4"}
AUDIO = {0x03: "MP3", 0x04: "MP3", 0x0F: "AAC", 0x11: "AAC-LATM", 0x81: "AC3", 0x87: "EAC3"}


def http_get(url, timeout=8, limit=400_000, start=None):
    hdr = {"User-Agent": UA, "Accept": "*/*"}
    if start is not None:
        hdr["Range"] = "bytes=%d-%d" % (start, start + limit - 1)
    req = urllib.request.Request(url, headers=hdr)
    with urllib.request.urlopen(req, timeout=timeout, context=_SSL) as r:
        return r.read(limit)


def playlist_segments(url):
    body = http_get(url, timeout=8, limit=200_000).decode("utf-8", "ignore")
    variants, segs = [], []
    lines = [l.strip() for l in body.splitlines() if l.strip()]
    for i, l in enumerate(lines):
        if l.startswith("#EXT-X-STREAM-INF"):
            for nxt in lines[i + 1:]:
                if not nxt.startswith("#"):
                    variants.append(nxt)
                    break
    for l in lines:
        if not l.startswith("#"):
            segs.append(l)
    return body, segs, variants, url


def abs_url(u, base):
    if u.startswith("http"):
        return u
    if u.startswith("/"):
        m = re.match(r"(https?://[^/]+)", base)
        return (m.group(1) if m else "") + u
    return base.rsplit("/", 1)[0] + "/" + u


def parse_pmt(data):
    """在 TS 包里找 PAT/PMT，返回 (视频编码集合, 音频编码集合)。

    TS 有 188 字节定长包，payload 首字节是 pointer_field，真正的表从它后面开始。
    """
    if len(data) < 376 or data[0] != 0x47:
        return None, None
    pmt_pid = None
    for off in range(0, len(data) - 188, 188):
        pkt = data[off:off + 188]
        if pkt[0] != 0x47:
            continue
        pid = ((pkt[1] & 0x1F) << 8) | pkt[2]
        if not (pkt[1] & 0x40):  # payload_unit_start_indicator
            continue
        afc = (pkt[3] >> 4) & 0x3
        if afc not in (1, 3):
            continue
        p = 4
        if afc == 3:
            p += 1 + pkt[4]
        if p >= 188:
            continue
        ptr = pkt[p]
        sec = pkt[p + 1 + ptr:]
        if len(sec) < 12:
            continue
        table_id = sec[0]
        slen = ((sec[1] & 0x0F) << 8) | sec[2]
        end = min(len(sec), 3 + slen - 4)
        if table_id == 0x00 and pmt_pid is None:  # PAT
            i = 8
            while i + 4 <= end:
                prog = (sec[i] << 8) | sec[i + 1]
                pmt = ((sec[i + 2] & 0x1F) << 8) | sec[i + 3]
                if prog != 0:
                    pmt_pid = pmt
                    break
                i += 4
        elif table_id == 0x02 and pmt_pid is not None and pid == pmt_pid:  # PMT
            pil = ((sec[10] & 0x0F) << 8) | sec[11]
            i = 12 + pil
            vids, auds = set(), set()
            while i + 5 <= end:
                st = sec[i]
                if st in VIDEO:
                    vids.add(VIDEO[st])
                elif st in AUDIO:
                    auds.add(AUDIO[st])
                eslen = ((sec[i + 3] & 0x0F) << 8) | sec[i + 4]
                i += 5 + eslen
            return vids, auds
    return None, None


def check(item):
    name, url = item
    rec = {"name": name, "codec": "", "audio": "", "verdict": "", "url": url}
    try:
        cur, data = url, b""
        for _ in range(3):  # 有些源的「分片」其实还是播放列表
            body, segs, variants, base = playlist_segments(cur)
            target = segs[0] if segs else (variants[0] if variants else None)
            if not target:
                break
            target = target.split(",")[0].strip()  # 有些行尾带 ,7824,01.m3u8 之类的参数
            cur = abs_url(target, base)
            data = http_get(cur, timeout=10)
            if data[:1] == b"G" or data[4:8] in (b"ftyp", b"styp", b"moof", b"moov"):
                break
        vids, auds = parse_pmt(data)
    except Exception as e:  # noqa: BLE001
        rec["verdict"] = "NO_PMT"
        rec["codec"] = repr(e)[:40]
        return rec

    if not vids and not auds:
        if data[4:8] in (b"ftyp", b"styp", b"moof", b"moov"):
            rec["verdict"] = "FMP4"
            rec["codec"] = "fMP4"
        else:
            rec["verdict"] = "NO_PMT"
            rec["codec"] = "PMT 未解析到"
        return rec
    rec["codec"] = "/".join(sorted(vids)) or "-"
    rec["audio"] = "/".join(sorted(auds)) or "-"
    if vids == {"H264"}:
        rec["verdict"] = "H264_OK"
    elif "HEVC" in vids:
        rec["verdict"] = "H265"
    else:
        rec["verdict"] = "OTHER"
    return rec


def load_m3u(path):
    items, cur = [], None
    for line in open(path, encoding="utf-8", errors="ignore"):
        line = line.strip()
        if not line:
            continue
        if line.startswith("#EXTINF"):
            m = re.search(r",(.+)$", line)
            cur = m.group(1).strip() if m else "?"
        elif not line.startswith("#") and cur is not None:
            items.append((cur, line))
            cur = None
    return items


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--m3u", default=os.path.join(HERE, "out", "yg-live-alive.m3u"))
    ap.add_argument("--out", default=os.path.join(HERE, "out", "codec_report.csv"))
    ap.add_argument("--workers", type=int, default=24)
    a = ap.parse_args()

    items = load_m3u(a.m3u)
    print("探测编码 %d 个频道" % len(items), flush=True)
    rows, done = [], 0
    with ThreadPoolExecutor(max_workers=a.workers) as ex:
        for rec in ex.map(check, items):
            rows.append(rec)
            done += 1
            if done % 50 == 0:
                print("  %d/%d" % (done, len(items)), flush=True)

    with open(a.out, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["name", "verdict", "codec", "audio", "url"])
        w.writeheader()
        w.writerows(rows)

    stat = {}
    for r in rows:
        stat[r["verdict"]] = stat.get(r["verdict"], 0) + 1
    print("\n=== 编码分布 ===")
    for k, v in sorted(stat.items(), key=lambda x: -x[1]):
        print("  %-10s %d" % (k, v))
    print("报告: %s" % a.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
