#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""直播代理：把乱七八糟的上游 HLS 洗成「飞牛影视一定吃得下」的标准流。

为什么需要它
------------
飞牛影视的直播播放器（尤其 Web / 手机端）对上游很挑：播放列表要能直接解析、
分片要跨域可读、不能有 master 嵌套、相对路径要拼得对。我们手上的源刚好三类毛病都有：

  * 江西电信那 159 个台是「master → 带逗号鉴权参数的子列表 → ts」两层结构；
  * 绝大多数源不带 Access-Control-Allow-Origin，浏览器直接拒绝（hls.js 读不了）；
  * 少数源要特定 UA / Referer 才给拉。

代理一次性吃掉这些问题：自己去按原始参数拉上游、解开嵌套、把分片地址改写成
自己的地址、统一补 CORS，对外只吐一份扁平干净的标准 HLS。播放器看到的就是
「一个 http 地址 + 一串 ts」，没有任何花样。

用法
----
  python live_proxy.py --m3u out/yg-live-web.m3u --port 8899
  # 播放器/浏览器访问  http://<NAS-IP>:8899/list.m3u

路由
----
  /list.m3u           频道列表（地址指向本代理）
  /live/<序号>.m3u8    某个频道的播放列表
  /seg?u=<base64>      分片 / 密钥 / init 段转发
  /health             存活检查，返回频道数
"""
import argparse
import base64
import gzip
import io
import os
import re
import ssl
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, quote

HERE = os.path.dirname(os.path.abspath(__file__))
UA = ("Mozilla/5.0 (Linux; Android 9; VIDAA) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/90.0 Mobile Safari/537.36")
_SSL = ssl._create_unverified_context()
CACHE_TTL = 2.0          # 播放列表缓存秒数，太短会把上游打爆，太长会卡住直播
CHANNELS = []            # [{"name","group","url"}]
_cache = {}              # url -> (ts, lines, base)
_lock = threading.Lock()
PUBLIC_HOST = ""         # 生成 m3u 时对外公布的地址，如 http://10.1.1.9:8899


# ---------------------------------------------------------------- 上游抓取
def http_get(url, referer="", timeout=10, limit=4_000_000):
    hdr = {"User-Agent": UA, "Accept": "*/*", "Accept-Encoding": "gzip"}
    if referer:
        hdr["Referer"] = referer
    req = urllib.request.Request(url, headers=hdr)
    with urllib.request.urlopen(req, timeout=timeout, context=_SSL) as r:
        raw = r.read(limit)
        if r.headers.get("Content-Encoding") == "gzip":
            try:
                raw = gzip.decompress(raw)
            except OSError:
                pass
        return raw, r.geturl(), dict((k.lower(), v) for k, v in r.getheaders())


def abs_url(u, base):
    if u.startswith("http://") or u.startswith("https://"):
        return u
    if u.startswith("//"):
        return "http:" + u
    if u.startswith("/"):
        m = re.match(r"(https?://[^/]+)", base)
        return (m.group(1) if m else "") + u
    return base.rsplit("/", 1)[0] + "/" + u


def pick_variant(lines):
    """从 master 播放列表里挑最高带宽的那一路。"""
    best, best_bw = None, -1
    for i, l in enumerate(lines):
        if not l.startswith("#EXT-X-STREAM-INF"):
            continue
        m = re.search(r"BANDWIDTH=(\d+)", l)
        bw = int(m.group(1)) if m else 0
        for nxt in lines[i + 1:]:
            if not nxt.startswith("#"):
                if bw >= best_bw:
                    best, best_bw = nxt, bw
                break
    return best


def fetch_playlist(url, referer="", depth=0):
    """拉播放列表；如果是 master 就往下钻，直到拿到真正带分片的那一层。"""
    raw, final, _ = http_get(url, referer=referer)
    text = raw.decode("utf-8", "ignore")
    if "#EXTM3U" not in text and "#EXTINF" not in text:
        raise ValueError("上游不是播放列表")
    lines = [l.strip() for l in text.splitlines() if l.strip()]
    has_media = any(l.startswith("#EXTINF") for l in lines)
    v = pick_variant(lines)
    if v and not has_media and depth < 4:
        return fetch_playlist(abs_url(v, final), referer, depth + 1)
    return lines, final


# ---------------------------------------------------------------- 地址改写
def _b64(s):
    return base64.urlsafe_b64encode(s.encode("utf-8")).decode("ascii").rstrip("=")


def _unb64(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4)).decode("utf-8")


def seg_url(target, referer=""):
    q = "/seg?u=" + _b64(target)
    if referer:
        q += "&r=" + _b64(referer)
    return q


def rewrite(lines, base, referer=""):
    """把上游播放列表里的每条地址换成指向本代理的相对地址。"""
    out = ["#EXTM3U"]
    skip_next_uri = False
    for l in lines:
        if l.startswith("#EXTM3U"):
            continue
        if l.startswith("#EXT-X-STREAM-INF") or l.startswith("#EXT-X-I-FRAME-STREAM-INF"):
            skip_next_uri = True
            continue
        if l.startswith("#EXT-X-KEY") or l.startswith("#EXT-X-SESSION-KEY"):
            l = re.sub(r'URI="([^"]+)"',
                       lambda m: 'URI="%s"' % seg_url(abs_url(m.group(1), base), referer), l)
            out.append(l)
            continue
        if l.startswith("#EXT-X-MAP"):
            l = re.sub(r'URI="([^"]+)"',
                       lambda m: 'URI="%s"' % seg_url(abs_url(m.group(1), base), referer), l)
            out.append(l)
            continue
        if l.startswith("#"):
            out.append(l)
            continue
        if skip_next_uri:      # 这是被丢掉的 master variant 地址，跳过
            skip_next_uri = False
            continue
        out.append(seg_url(abs_url(l, base), referer))
    return out


def channel_playlist(idx):
    ch = CHANNELS[idx]
    with _lock:
        hit = _cache.get(idx)
        if hit and time.time() - hit[0] < CACHE_TTL:
            return hit[1]
    lines, base = fetch_playlist(ch["url"], referer=ch.get("referer", ""))
    body = rewrite(lines, base, ch.get("referer", ""))
    with _lock:
        _cache[idx] = (time.time(), body)
    return body


# ---------------------------------------------------------------- HTTP 服务
class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "YGLiveProxy/1.0"

    def log_message(self, fmt, *a):   # 压掉默认访问日志，只留错误
        pass

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        self.send_header("Access-Control-Expose-Headers", "*")

    def _text(self, body, ctype="application/vnd.apple.mpegurl; charset=utf-8", code=200):
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self._cors()
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def _proxy_stream(self, target, referer=""):
        try:
            hdr = {"User-Agent": UA, "Accept": "*/*"}
            if referer:
                hdr["Referer"] = referer
            req = urllib.request.Request(target, headers=hdr)
            r = urllib.request.urlopen(req, timeout=25, context=_SSL)
        except Exception as e:                                    # noqa: BLE001
            self._text("upstream error: %s" % e, "text/plain; charset=utf-8", 502)
            return
        try:
            self.send_response(r.getcode() if r.getcode() in (200, 206) else 200)
            self._cors()
            ctype = r.headers.get("Content-Type") or "application/octet-stream"
            self.send_header("Content-Type", ctype)
            clen = r.headers.get("Content-Length")
            if clen:
                self.send_header("Content-Length", clen)
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            if self.command == "HEAD":
                return
            while True:
                chunk = r.read(65536)
                if not chunk:
                    break
                self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            r.close()

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        parsed = urlparse(self.path)
        path, qs = parsed.path, parse_qs(parsed.query)

        if path == "/health":
            self._text("ok channels=%d" % len(CHANNELS), "text/plain; charset=utf-8")
            return

        if path in ("/list.m3u", "/", "/index.m3u8"):
            # 用请求自带的 Host 拼地址：手机用 10.1.1.9 访问，列表里就是 10.1.1.9，
            # 不用手工配置对外地址，也不会出现 127.0.0.1 这种播放端请求自己的坑。
            base = PUBLIC_HOST or ("http://" + (self.headers.get("Host") or "127.0.0.1"))
            out = ["#EXTM3U"]
            for i, ch in enumerate(CHANNELS):
                out.append('#EXTINF:-1 tvg-name="%s" tvg-logo="" group-title="%s",%s'
                           % (ch["name"], ch["group"], ch["name"]))
                out.append("%s/live/%d.m3u8" % (base, i))
            self._text("\n".join(out) + "\n")
            return

        m = re.match(r"^/live/(\d+)\.m3u8$", path)
        if m:
            idx = int(m.group(1))
            if not (0 <= idx < len(CHANNELS)):
                self._text("no such channel", "text/plain; charset=utf-8", 404)
                return
            try:
                body = channel_playlist(idx)
            except Exception as e:                                # noqa: BLE001
                self._text("playlist error: %s" % e, "text/plain; charset=utf-8", 502)
                return
            self._text("\n".join(body) + "\n")
            return

        if path == "/seg":
            try:
                target = _unb64(qs.get("u", [""])[0])
                referer = _unb64(qs.get("r", [""])[0]) if qs.get("r") else ""
            except Exception:                                     # noqa: BLE001
                self._text("bad url", "text/plain; charset=utf-8", 400)
                return
            if not target.startswith("http"):
                self._text("bad url", "text/plain; charset=utf-8", 400)
                return
            self._proxy_stream(target, referer)
            return

        self._text("404", "text/plain; charset=utf-8", 404)


# ---------------------------------------------------------------- 入口
def load_m3u(path):
    chans, cur = [], None
    for line in io.open(path, encoding="utf-8", errors="ignore"):
        line = line.strip()
        if not line:
            continue
        if line.startswith("#EXTINF"):
            nm = re.search(r",(.+)$", line)
            gp = re.search(r'group-title="([^"]*)"', line)
            cur = (nm.group(1).strip() if nm else "?", gp.group(1) if gp else "其他")
        elif not line.startswith("#") and cur:
            chans.append({"name": cur[0], "group": cur[1], "url": line})
            cur = None
    return chans


def main():
    global CHANNELS, PUBLIC_HOST
    ap = argparse.ArgumentParser()
    ap.add_argument("--m3u", default=os.path.join(HERE, "out", "yg-live-web.m3u"))
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8899)
    ap.add_argument("--public-host", default="",
                    help="对外公布的地址，如 http://10.1.1.9:8899；留空自动猜")
    a = ap.parse_args()

    CHANNELS = load_m3u(a.m3u)
    if not CHANNELS:
        print("频道列表是空的: %s" % a.m3u)
        return 1
    PUBLIC_HOST = a.public_host.rstrip("/")

    srv = ThreadingHTTPServer((a.host, a.port), Handler)
    srv.daemon_threads = True
    print("直播代理已启动  频道 %d 个" % len(CHANNELS))
    print("  列表: http://<本机IP>:%d/list.m3u" % a.port)
    print("  监听: %s:%d" % (a.host, a.port))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n收到中断，退出")
    finally:
        srv.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
