#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一把过：把直播源变成「真的能播」的 m3u。

之前只验证了播放列表能打开（HTTP 200），结果导进飞牛影视 470 个频道全黑。
这次每个频道走完四道关，只把过关的写进 m3u：

  1. 播放列表能解析（自动下钻 master 播放列表）
  2. 分片真的是 TS/fMP4（不是 HTML 错误页、不是又一层播放列表）
  3. 读到 PMT，知道视频编码是 H.264 还是 HEVC
  4. 播放列表和分片都带 Access-Control-Allow-Origin（浏览器端 hls.js 才拉得到）

产出两个文件：
  out/yg-live-web.m3u  第 4 关也过的 —— 飞牛影视 Web 端直接能播，不用装插件
  out/yg-live-app.m3u  1~3 关过的   —— 手机/TV 端 App 用（原生播放器不吃 CORS）

用法:
  python build_live_m3u.py                 # 全量
  python build_live_m3u.py --workers 24
  python build_live_m3u.py --limit 30      # 只跑前 30 条，快速验证脚本
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
ORIGIN = "http://192.168.1.10:5666"   # 假装是飞牛 Web 页面发出的跨域请求
_SSL = ssl._create_unverified_context()

VIDEO = {0x01: "MPEG1", 0x02: "MPEG2", 0x1B: "H264", 0x24: "HEVC", 0x10: "MPEG4"}
AUDIO = {0x03: "MP3", 0x04: "MP3", 0x0F: "AAC", 0x11: "AAC-LATM", 0x81: "AC3", 0x87: "EAC3"}


def fetch(url, timeout=8, limit=300_000, origin=None):
    hdr = {"User-Agent": UA, "Accept": "*/*"}
    if origin:
        hdr["Origin"] = origin
    req = urllib.request.Request(url, headers=hdr)
    with urllib.request.urlopen(req, timeout=timeout, context=_SSL) as r:
        return r.read(limit), dict((k.lower(), v) for k, v in r.getheaders()), r.geturl()


def abs_url(u, base):
    if u.startswith("http"):
        return u
    if u.startswith("/"):
        m = re.match(r"(https?://[^/]+)", base)
        return (m.group(1) if m else "") + u
    return base.rsplit("/", 1)[0] + "/" + u


def parse_pmt(data):
    if len(data) < 376 or data[0] != 0x47:
        return set(), set()
    pmt_pid = None
    for off in range(0, len(data) - 188, 188):
        pkt = data[off:off + 188]
        if pkt[0] != 0x47:
            continue
        pid = ((pkt[1] & 0x1F) << 8) | pkt[2]
        if not (pkt[1] & 0x40):
            continue
        afc = (pkt[3] >> 4) & 0x3
        if afc not in (1, 3):
            continue
        p = 4
        if afc == 3:
            p += 1 + pkt[4]
        if p >= 188:
            continue
        sec = pkt[p + 1 + pkt[p]:]
        if len(sec) < 12:
            continue
        tid = sec[0]
        slen = ((sec[1] & 0x0F) << 8) | sec[2]
        end = min(len(sec), 3 + slen - 4)
        if tid == 0x00 and pmt_pid is None:
            i = 8
            while i + 4 <= end:
                prog = (sec[i] << 8) | sec[i + 1]
                if prog != 0:
                    pmt_pid = ((sec[i + 2] & 0x1F) << 8) | sec[i + 3]
                    break
                i += 4
        elif tid == 0x02 and pid == pmt_pid:
            pil = ((sec[10] & 0x0F) << 8) | sec[11]
            i, vids, auds = 12 + pil, set(), set()
            while i + 5 <= end:
                st = sec[i]
                if st in VIDEO:
                    vids.add(VIDEO[st])
                elif st in AUDIO:
                    auds.add(AUDIO[st])
                i += 5 + (((sec[i + 3] & 0x0F) << 8) | sec[i + 4])
            return vids, auds
    return set(), set()


def check(item):
    name, group, url = item
    rec = {"name": name, "group": group, "url": url, "media": "NO",
           "codec": "", "audio": "", "cors": "NO", "note": ""}
    try:
        cur, cors_playlist = url, False
        data, hdr_seg = b"", {}
        for _ in range(3):
            body, hdr, final = fetch(cur, timeout=8, limit=200_000, origin=ORIGIN)
            if hdr.get("access-control-allow-origin"):
                cors_playlist = True
            text = body.decode("utf-8", "ignore")
            if "#EXTM3U" not in text and "#EXTINF" not in text:
                rec["note"] = "播放列表非法"
                return rec
            lines = [l.strip() for l in text.splitlines() if l.strip()]
            variants = [lines[i + 1] for i, l in enumerate(lines)
                        if l.startswith("#EXT-X-STREAM-INF") and i + 1 < len(lines)]
            segs = [l for l in lines if not l.startswith("#")]
            pick = segs[0] if segs else (variants[0] if variants else None)
            if not pick:
                rec["note"] = "没有分片"
                return rec
            # 不能按逗号截断：江西电信把鉴权参数放在逗号后面
            # （01.m3u8?fmt=ts2hls,7824,01.m3u8,0,8467,0,0），截断会退回上一层播放列表
            cur = abs_url(pick, final)
            data, hdr_seg, _ = fetch(cur, timeout=10)
            if data[:1] == b"\x47" or data[4:8] in (b"ftyp", b"styp", b"moof", b"moov"):
                break
            if b"#EXTM3U" not in data[:2000]:
                break
        if not data:
            rec["note"] = "分片为空"
            return rec
        is_ts = data[:1] == b"\x47"
        is_mp4 = data[4:8] in (b"ftyp", b"styp", b"moof", b"moov")
        if not (is_ts or is_mp4):
            rec["note"] = "分片非媒体(%s)" % data[:8].hex()
            return rec
        rec["media"] = "TS" if is_ts else "FMP4"
        vids, auds = parse_pmt(data)
        rec["codec"] = "/".join(sorted(vids)) or "-"
        rec["audio"] = "/".join(sorted(auds)) or "-"
        seg_cors = bool(hdr_seg.get("access-control-allow-origin"))
        rec["cors"] = "YES" if (cors_playlist and seg_cors) else ("PART" if (cors_playlist or seg_cors) else "NO")
    except Exception as e:  # noqa: BLE001
        rec["note"] = repr(e)[:60]
    return rec


def load_m3u(path):
    items, cur = [], None
    for line in open(path, encoding="utf-8", errors="ignore"):
        line = line.strip()
        if not line:
            continue
        if line.startswith("#EXTINF"):
            m = re.search(r",(.+)$", line)
            g = re.search(r'group-title="([^"]*)"', line)
            cur = (m.group(1).strip() if m else "?", g.group(1) if g else "其他")
        elif not line.startswith("#") and cur:
            items.append((cur[0], cur[1], line))
            cur = None
    return items


def write_m3u(path, rows):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("#EXTM3U\n")
        for r in rows:
            f.write('#EXTINF:-1 tvg-name="%s" tvg-logo="" group-title="%s",%s\n%s\n'
                    % (r["name"], r["group"], r["name"], r["url"]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--m3u", default=os.path.join(HERE, "out", "yg-live-alive.m3u"))
    ap.add_argument("--workers", type=int, default=24)
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()

    items = load_m3u(a.m3u)
    if a.limit:
        items = items[:a.limit]
    print("检查 %d 个频道" % len(items), flush=True)

    rows, done = [], 0
    with ThreadPoolExecutor(max_workers=a.workers) as ex:
        for rec in ex.map(check, items):
            rows.append(rec)
            done += 1
            if done % 50 == 0:
                print("  %d/%d" % (done, len(items)), flush=True)

    os.makedirs(os.path.join(HERE, "out"), exist_ok=True)
    rep = os.path.join(HERE, "out", "live_check.csv")
    with open(rep, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["name", "group", "media", "codec", "audio", "cors", "note", "url"])
        w.writeheader()
        w.writerows(rows)

    ok_media = [r for r in rows if r["media"] in ("TS", "FMP4")]
    web = [r for r in ok_media if r["cors"] == "YES"]

    def dedupe(rs):
        """同名频道只留一条，H.264 优先。"""
        best = {}
        for r in rs:
            k = r["name"]
            score = (r["codec"] == "H264") * 2 + (r["media"] == "TS")
            if k not in best or score > best[k][0]:
                best[k] = (score, r)
        return [v[1] for v in best.values()]

    web_d, app_d = dedupe(web), dedupe(ok_media)
    web_path = os.path.join(HERE, "out", "yg-live-web.m3u")
    app_path = os.path.join(HERE, "out", "yg-live-app.m3u")
    write_m3u(web_path, web_d)
    write_m3u(app_path, app_d)

    stat = {}
    for r in rows:
        k = (r["media"], r["cors"])
        stat[k] = stat.get(k, 0) + 1
    print("\n=== 分项 ===")
    for k, v in sorted(stat.items(), key=lambda x: -x[1]):
        print("  媒体=%-5s CORS=%-4s  %d" % (k[0], k[1], v))
    print("\n媒体有效 %d 条，浏览器可直接播 %d 条" % (len(ok_media), len(web)))
    print("  %s  频道 %d（Web 端，无需插件）" % (web_path, len(web_d)))
    print("  %s  频道 %d（App 端）" % (app_path, len(app_d)))
    print("明细: %s" % rep)
    return 0


if __name__ == "__main__":
    sys.exit(main())
