# -*- coding: utf-8 -*-
"""生成「协议对照诊断」m3u。

目的：用一次导入定位飞牛影视到底卡在哪一层。
  - 春晚（https + 点播）能播，CCTV（http + 直播）不能播
  - 究竟是「http 明文」的问题，还是「直播流」的问题，还是「这批次源本身」的问题

这批 8 台就是把变量拆开：
  1 https+直播  cztv 央视13
  2 http +直播  cztv 央视13   ← 与 1 是同一个流，只有协议不同
  3 https+直播  heb 央视14
  4 http +直播  江西电信 央视1（IP 型）
  5 https+直播  cztv 浙江卫视
  6 https+直播  CGTN 纪录（央视官方域名）
  7 http +直播  本机代理 央视1（内网 IP + 代理）
  8 https+点播  2025 春晚（已知能播，做对照基线）
"""
import io
import os
import ssl
import time
import urllib.request

CTX = ssl._create_unverified_context()
UA = "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 Chrome/91.0 Safari/537.36"
PROXY = "http://10.1.1.16:8899/live/0.m3u8"

CASES = [
    ("诊断1 https直播 央视13", "https://ali-m-l.cztv.com/channels/lantian/channel21/1080p.m3u8",
     "https + 直播"),
    ("诊断2 http直播 央视13", "http://ali-m-l.cztv.com/channels/lantian/channel21/1080p.m3u8",
     "http + 直播（与诊断1同一个流）"),
    ("诊断3 https直播 央视14", "https://event.pull.hebtv.com/jishi/cp2.m3u8",
     "https + 直播（河北台转发）"),
    ("诊断4 http直播 央视1-IP", "http://115.153.207.89:80/wh7f454c46tw1628903636_-846904360/PLTV/88888888/224/3221225699/iptv8040.smil/01.m3u8?fmt=ts2hls,6684,01.m3u8,0,8466,0,0",
     "http + 直播（江西电信，纯 IP）"),
    ("诊断5 https直播 浙江卫视", "https://ali-m-l.cztv.com/channels/lantian/channel001/1080p.m3u8",
     "https + 直播（cztv 另一频道）"),
    ("诊断6 https直播 CGTN纪录", "https://english-livetx.cgtn.com/hls/yypdjlctzb_hd.m3u8",
     "https + 直播（央视官方域名）"),
    ("诊断7 http直播 内网代理央视1", PROXY,
     "http + 直播 + 内网 IP（要让本机开着；验证内网地址能否访问）"),
    ("诊断8 https点播 2025春晚", "https://svip.high24-playback.com/20250129/17159_7bca93c4/2000k/hls/mixed.m3u8",
     "https + 点播（已知能播，基线对照）"),
]


def check(url):
    try:
        t0 = time.time()
        body = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}),
                                      timeout=8, context=CTX).read(3000).decode("utf-8", "ignore")
        if "#EXT" not in body:
            return "非HLS", 0, 0, "-"
        live = "#EXT-X-ENDLIST" not in body
        segs = [x.strip() for x in body.split("\n") if x.strip() and not x.startswith("#")]
        if not segs:
            return "空列表", 0, 0, "-"
        # 分片地址可能是绝对 URL、/开头根路径、或纯文件名；逐个试，摸到一个能下的就算通
        base = url.split("?")[0].rsplit("/", 1)[0]
        host = url.split("/")[0] + "//" + url.split("/")[2]
        tried = []
        for s in ([segs[0], segs[-1]] + segs[:3] + segs[-3:]):
            if s.startswith("http"):
                cand = s
            elif s.startswith("/"):
                cand = host + s
            else:
                cand = base + "/" + s
            if cand in tried:
                continue
            tried.append(cand)
            try:
                data = urllib.request.urlopen(urllib.request.Request(cand, headers={"User-Agent": UA}),
                                              timeout=12, context=CTX).read(100000)
                if len(data) >= 1000:
                    return "OK", len(data), round(time.time() - t0, 2), "直播" if live else "点播"
            except Exception:
                continue
        return "分片全失败", 0, 0, "-"
    except Exception as e:
        return type(e).__name__, 0, 0, "-"


def main():
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "dist", "飞牛直播",
                       "08-诊断用8台.m3u")
    out = os.path.abspath(out)
    lines = ["#EXTM3U"]
    print("%-26s %-16s %8s %6s %-4s %s" % ("台名", "自检", "首片B", "秒", "类型", "说明"))
    for name, url, note in CASES:
        st, size, cost, kind = check(url)
        print("%-26s %-16s %8d %6s %-4s %s" % (name, st, size, cost, kind, note))
        lines.append('#EXTINF:-1 tvg-name="%s" group-title="诊断",%s' % (name, name))
        lines.append(url)
    io.open(out, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
    print("\n已写出 %s（%d 台）" % (out, len(CASES)))


if __name__ == "__main__":
    main()
