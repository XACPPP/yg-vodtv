# -*- coding: utf-8 -*-
"""按分类各取一条片源，抓首分片判定编码（只打印结论，不落盘）。"""
import json
import sys
import time
import urllib.request

SITE = "https://wsyzy.cc"
API = "https://api.wsyzy.net/api.php/provide/vod/"
UA = ("Mozilla/5.0 (Linux; Android 5.1.1) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/78.0.3904.108 Mobile Safari/537.36")

TYPES = [(6, "动作片"), (29, "国产动漫"), (25, "大陆综艺"), (20, "纪录片"), (62, "4K电影")]


def get(url, binary=False, retry=5):
    last = None
    for i in range(retry):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": SITE + "/"})
            d = urllib.request.urlopen(req, timeout=30).read()
            return d if binary else d.decode("utf-8", "replace")
        except Exception as e:
            last = e
            time.sleep(2.0 * (i + 1))
    raise last


def codec_of(buf):
    pkts = []
    i = 0
    while i + 188 <= len(buf):
        if buf[i] == 0x47:
            pkts.append(buf[i:i + 188])
        i += 188
    pmt_pid = None
    streams = []
    for p in pkts:
        pid = ((p[1] & 0x1F) << 8) | p[2]
        if not (p[1] & 0x40):
            continue
        afc = (p[3] >> 4) & 3
        if afc in (0, 2):
            continue
        q = p[4:]
        if afc == 3:
            q = q[1 + q[0]:]
        if not q:
            continue
        q = q[1 + q[0]:]                     # pointer_field
        if q[0] == 0x00 and pmt_pid is None:
            body = q[8:3 + (((q[1] & 0x0F) << 8) | q[2]) - 4]
            for k in range(0, len(body) - 3, 4):
                prog = (body[k] << 8) | body[k + 1]
                if prog:
                    pmt_pid = ((body[k + 2] & 0x1F) << 8) | body[k + 3]
                    break
        elif q[0] == 0x02 and pid == pmt_pid:
            end = min(3 + (((q[1] & 0x0F) << 8) | q[2]) - 4, len(q))
            k = 12 + (((q[10] & 0x0F) << 8) | q[11])
            while k + 4 < end:
                streams.append((q[k], ((q[k + 1] & 0x1F) << 8) | q[k + 2]))
                k += 5 + (((q[k + 3] & 0x0F) << 8) | q[k + 4])
    name = {0x1B: "H.264", 0x24: "H.265", 0x01: "MPEG-1V", 0x02: "MPEG-2V",
            0x10: "MPEG-4V", 0x0F: "AAC", 0x11: "AAC-LATM", 0x03: "MP1", 0x04: "MP2",
            0x81: "AC3", 0x8A: "DTS", 0x86: "SCTE35"}
    return [(name.get(t, "0x%02X" % t), pid) for t, pid in streams]


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    for tid, tname in TYPES:
        try:
            js = json.loads(get(API + "?ac=detail&t=%d&pg=1" % tid))
            lst = js.get("list") or []
            if not lst:
                print("%-6s -> 分类无数据" % tname)
                continue
            d = lst[0]
            raw = d["vod_play_url"].split("$$$")[0].split("#")[0]
            if "$" not in raw:
                print("%-6s -> 无播放地址" % tname)
                continue
            url = raw.split("$", 1)[1]
            m = get(url)
            subs = [l.strip() for l in m.split("\n") if l.strip() and not l.startswith("#")]
            media_url = url if not subs else (
                subs[0] if subs[0].startswith("http") else url.rsplit("/", 1)[0] + "/" + subs[0])
            media = get(media_url)
            segs = [l.strip() for l in media.split("\n") if l.strip() and not l.startswith("#")]
            if not segs:
                print("%-6s -> 无分片" % tname)
                continue
            seg = segs[0]
            if not seg.startswith("http"):
                seg = media_url.rsplit("/", 1)[0] + "/" + seg
            buf = get(seg, binary=True)
            print("%-6s | %-22s | %s | %d 分片" % (
                tname, d["vod_name"][:22], codec_of(buf), len(segs)))
        except Exception as e:
            print("%-6s -> 失败 %s" % (tname, e))


if __name__ == "__main__":
    main()
