# -*- coding: utf-8 -*-
"""把片源分片抓到本地，离线解析编码（API 经常 522，抓一次就够了）。"""
import json
import os
import sys
import time
import urllib.request

SITE = "https://wsyzy.cc"
API = "https://api.wsyzy.net/api.php/provide/vod/"
UA = ("Mozilla/5.0 (Linux; Android 5.1.1) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/78.0.3904.108 Mobile Safari/537.36")
OUT = os.path.join(os.path.dirname(__file__), "samples")


def get(url, binary=False, retry=6):
    last = None
    for i in range(retry):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": SITE + "/"})
            data = urllib.request.urlopen(req, timeout=30).read()
            return data if binary else data.decode("utf-8", "replace")
        except Exception as e:
            last = e
            time.sleep(2.0 * (i + 1))
    raise last


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    os.makedirs(OUT, exist_ok=True)
    targets = []
    for vid in [149930, 149165]:
        try:
            d = json.loads(get(API + "?ac=detail&ids=%d" % vid))["list"][0]
        except Exception as e:
            print("跳过", vid, e)
            continue
        raw = d["vod_play_url"].split("$$$")[0].split("#")[0].split("$", 1)[1]
        targets.append((vid, d["vod_name"], raw))

    for vid, name, raw in targets:
        print("下载", name, vid)
        m = get(raw)
        subs = [l.strip() for l in m.split("\n") if l.strip() and not l.startswith("#")]
        media_url = raw if not subs else (
            subs[0] if subs[0].startswith("http") else raw.rsplit("/", 1)[0] + "/" + subs[0])
        media = get(media_url)
        segs = [l.strip() for l in media.split("\n") if l.strip() and not l.startswith("#")]
        seg = segs[0]
        if not seg.startswith("http"):
            seg = media_url.rsplit("/", 1)[0] + "/" + seg
        buf = get(seg, binary=True)
        p = os.path.join(OUT, "%d.ts" % vid)
        with open(p, "wb") as f:
            f.write(buf)
        with open(os.path.join(OUT, "%d.m3u8" % vid), "w", encoding="utf-8") as f:
            f.write(media)
        print("  已保存 %s (%d 字节) 分片数=%d" % (p, len(buf), len(segs)))


if __name__ == "__main__":
    main()
