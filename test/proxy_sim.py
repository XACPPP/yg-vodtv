# -*- coding: utf-8 -*-
"""1:1 复刻 Proxy.java 的路由与改写逻辑，起真实 HTTP 服务验证播放链路。

Proxy.java 的约定：
  /hls/{base64url}.m3u8  -> 播放列表（内部地址改写成本机）
  /ts/{base64url}        -> 分片，原样转发
本脚本用同一套规则起服务，然后用普通 HTTP 客户端（等价于 MediaPlayer 的明文访问）
把 master -> variant -> 分片 全走一遍，确认最终能拿到合法 TS。
"""
import base64
import re
import socket
import sys
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

SITE = "https://wsyzy.cc"
API = "https://api.wsyzy.net/api.php/provide/vod/"
UA = ("Mozilla/5.0 (Linux; Android 5.1.1) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/78.0.3904.108 Mobile Safari/537.36")
PORT = 18999
URI_ATTR = re.compile(r'URI="([^"]+)"')


def open_url(url, referer):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": referer,
                                              "Accept": "*/*"})
    return urllib.request.urlopen(req, timeout=20)


def enc(s):
    return base64.urlsafe_b64encode(s.encode("utf-8")).decode().rstrip("=")


def dec(s):
    pad = "=" * ((4 - len(s) % 4) % 4)
    return base64.urlsafe_b64decode(s + pad).decode("utf-8")


def normalize(path):
    out = []
    for seg in path.split("/"):
        if seg in ("", "."):
            continue
        if seg == "..":
            if out:
                out.pop()
            continue
        out.append(seg)
    return "/" + "/".join(out)


def resolve(base, ref):
    if ref.startswith(("http://", "https://")):
        return ref
    if ref.startswith("//"):
        i = base.find("://")
        return (base[:i] if i > 0 else "https") + ":" + ref
    hs = base.find("://")
    ps = base.find("/", hs + 3)
    origin = base[:ps] if ps > 0 else base
    p = base[ps:] if ps > 0 else "/"
    p = p.split("?", 1)[0]
    sl = p.rfind("/")
    d = p[:sl + 1] if sl >= 0 else "/"
    full = ref if ref.startswith("/") else d + ref
    if "?" in full:
        pathOnly, query = full.split("?", 1)
        query = "?" + query
    else:
        pathOnly, query = full, ""
    return origin + normalize(pathOnly) + query


def wrap_playlist(url):
    return "http://127.0.0.1:%d/hls/%s.m3u8" % (PORT, enc(url))


def wrap_segment(url):
    return "http://127.0.0.1:%d/ts/%s" % (PORT, enc(url))


def rewrite(text, base):
    master = "#EXT-X-STREAM-INF" in text
    out = []
    for line in text.replace("\r", "").split("\n"):
        line = line.strip()
        if not line:
            continue
        if line.startswith("#"):
            if 'URI="' in line:
                line = URI_ATTR.sub(
                    lambda m: 'URI="%s"' % wrap_segment(resolve(base, m.group(1))), line)
            out.append(line)
        elif master:
            out.append(wrap_playlist(resolve(base, line)))
        else:
            out.append(wrap_segment(resolve(base, line)))
    return "\n".join(out) + "\n"


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, *a):
        pass

    def do_GET(self):
        path = self.path
        if path.startswith("/hls/"):
            is_pl, b64 = True, path[5:]
        elif path.startswith("/ts/"):
            is_pl, b64 = False, path[4:]
        else:
            self.send_error(404)
            return
        if b64.endswith(".m3u8"):
            b64 = b64[:-5]
        if "?" in b64:
            b64 = b64.split("?", 1)[0]
        target = dec(b64)
        try:
            r = open_url(target, SITE + "/")
            body = r.read()
            ct = r.headers.get("Content-Type", "")
        except Exception as e:
            print("  [proxy] 拉取失败 target=%r err=%s: %s" % (target, type(e).__name__, e))
            self.send_error(502, str(e))
            return
        if is_pl or "mpegurl" in ct or "m3u" in ct:
            body = rewrite(body.decode("utf-8", "replace"), target).encode("utf-8")
            ct = "application/vnd.apple.mpegurl"
        self.send_response(200)
        self.send_header("Content-Type", ct or "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    print("== 1. 取一部片子的第一集 ==")
    q = open_url(SITE + "/index.php/ajax/suggest?mid=1&wd=fanhua", SITE + "/").read().decode()
    import json
    cand = sorted(json.loads(q)["list"], key=lambda x: -len(x["name"]))
    vid = cand[0]["id"]
    print("   suggest ->", cand[0]["name"], vid)
    d = json.loads(open_url(API + "?ac=detail&ids=%d" % vid, SITE + "/").read().decode())
    item = d["list"][0]
    first = item["vod_play_url"].split("$$$")[0].split("#")[0]
    name, raw = first.split("$", 1)
    print("   剧集:", item["vod_name"], "/", name)
    print("   源地址:", raw)

    srv = HTTPServer(("127.0.0.1", PORT), H)
    import threading
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    local = wrap_playlist(raw)
    print("\n== 2. 走本地代理拉 master ==")
    print("   本地地址:", local)
    txt = open_url(local, "").read().decode()
    lines = [l for l in txt.split("\n") if l.strip()]
    print("   master 行数:", len(lines), "| 首行:", lines[0])
    subm = [l for l in lines if l.startswith("http")]
    print("   子列表改写:", subm[0][:80], "...")
    assert "/hls/" in subm[0] and subm[0].endswith(".m3u8"), "子列表必须走 /hls/ 且带 .m3u8"

    print("\n== 3. 拉媒体列表 ==")
    media = open_url(subm[0], "").read().decode()
    mlines = [l for l in media.split("\n") if l.strip()]
    segs = [l for l in mlines if not l.startswith("#")]
    print("   总行数:", len(mlines), "| 分片数:", len(segs))
    print("   首个分片:", segs[0][:90], "...")
    assert "/ts/" in segs[0], "分片必须走 /ts/"
    assert "wsyzyts.vip" not in segs[0], "分片地址必须已被改写到本机"

    print("\n== 4. 拉第一个分片 ==")
    r = open_url(segs[0], "")
    data = r.read()
    print("   HTTP", r.status, "| Content-Type:", r.headers.get("Content-Type"))
    print("   字节数:", len(data), "| 首字节: 0x%02X" % data[0])
    assert data[0] == 0x47, "TS 必须以 0x47 开头"

    print("\n== 5. 播放列表时长信息 ==")
    exts = [l for l in mlines if l.startswith("#EXTINF")]
    dur = sum(float(x.split(":")[1].split(",")[0]) for x in exts)
    print("   EXTINF 条数:", len(exts), "| 总时长: %.1f 分钟" % (dur / 60))
    print("   ENDLIST:", any("ENDLIST" in l for l in mlines))
    srv.shutdown()
    print("\n[PASS] 代理链路三跳全部通过")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    main()
