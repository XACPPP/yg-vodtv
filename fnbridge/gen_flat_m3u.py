#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把嵌套的 master 播放列表「拍平」成单层 m3u8 地址，供飞牛影视直接导入。

背景：飞牛影视的电视直播播放器对 master(多码率) 播放列表支持不稳，
江西电信那批全是 master → 子列表 这种两层结构，导进去就黑屏。
本脚本对每个频道：
  1. 拉取原始地址；
  2. 若返回 master，挑 BANDWIDTH 最高的子列表，解析成绝对地址；
  3. 拉该子列表，取第一条分片，真正下载验证是 TS/fMP4；
  4. 输出「频道名 + 子列表绝对地址」的单层 m3u。

用法：
  python gen_flat_m3u.py --m3u out/yg-live-app.m3u --out out/yg-live-flat.m3u
  python gen_flat_m3u.py --m3u out/yg-live-web.m3u --out out/yg-live-flat-web.m3u --cors
"""
import argparse
import ssl
import sys
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = ("Mozilla/5.0 (Linux; Android 5.1.1; VIDAA) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/60.0 Safari/537.36")
TIMEOUT = 12
SSLCTX = ssl._create_unverified_context()


def fetch(url, referer=""):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Referer": referer or url,
        "Accept": "*/*",
    })
    with urllib.request.urlopen(req, timeout=TIMEOUT, context=SSLCTX) as r:
        return r.read(256 * 1024), dict(r.headers), r.geturl()


def parse(lines, base):
    """返回 (是否 master, [(bw, url)], [分片 url])"""
    variants, segs = [], []
    is_master = False
    pending_bw = 0
    for ln in lines:
        ln = ln.strip()
        if not ln:
            continue
        if ln.startswith("#EXT-X-STREAM-INF"):
            is_master = True
            for part in ln.split(":")[1].split(","):
                if part.upper().startswith("BANDWIDTH="):
                    try:
                        pending_bw = int(part.split("=")[1])
                    except ValueError:
                        pending_bw = 0
        elif ln.startswith("#"):
            continue
        else:
            u = urllib.parse.urljoin(base, ln)
            if is_master and pending_bw is not None:
                variants.append((pending_bw, u))
                pending_bw = 0
            else:
                segs.append(u)
    return is_master, variants, segs


def resolve(url, depth=0):
    """把 master 解析到子列表绝对地址；已是单层则原样返回。"""
    if depth > 3:
        return None
    body, hdr, final = fetch(url)
    text = body.decode("utf-8", "ignore")
    if "#EXTM3U" not in text:
        return None
    lines = text.splitlines()
    is_master, variants, segs = parse(lines, final)
    if is_master and variants:
        variants.sort(reverse=True)
        return resolve(variants[0][1], depth + 1)
    return final if segs else None


def verify(url):
    """拉子列表首条分片，确认是 TS(0x47) / fMP4。返回 (ok, codec_hint, acao)"""
    try:
        body, hdr, final = fetch(url)
        text = body.decode("utf-8", "ignore")
        lines = text.splitlines()
        _, _, segs = parse(lines, final)
        if not segs:
            return False, "no-seg", hdr.get("Access-Control-Allow-Origin", "")
        sb, sh, _ = fetch(segs[0])
        if len(sb) < 188:
            return False, "tiny", sh.get("Access-Control-Allow-Origin", "")
        if sb[0:1] == b"\x47":
            codec = "TS"
        elif b"ftyp" in sb[:32]:
            codec = "FMP4"
        else:
            codec = "OTHER:%s" % sb[:4].hex()
            return False, codec, sh.get("Access-Control-Allow-Origin", "")
        acao = hdr.get("Access-Control-Allow-Origin", "") or sh.get("Access-Control-Allow-Origin", "")
        return True, codec, acao
    except Exception as e:  # noqa: BLE001
        return False, type(e).__name__, ""


def one(item):
    name, group, url = item
    try:
        flat = resolve(url)
        if not flat:
            return None, (name, "resolve-fail", url, "")
        ok, codec, acao = verify(flat)
        if not ok:
            return None, (name, codec, url, "")
        return (name, group, flat, codec, acao), None
    except Exception as e:  # noqa: BLE001
        return None, (name, type(e).__name__, url, "")


def load_m3u(path):
    items, cur = [], None
    for ln in open(path, encoding="utf-8"):
        ln = ln.rstrip("\n")
        if ln.startswith("#EXTINF"):
            name = ln.rsplit(",", 1)[-1].strip()
            group = ""
            for part in ln.split(",")[0].split():
                if part.startswith("group-title="):
                    group = part.split("=", 1)[1].strip('"')
            cur = (name, group)
        elif ln.startswith("http") and cur:
            items.append((cur[0], cur[1], ln.strip()))
            cur = None
    return items


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--m3u", required=True, help="输入 m3u（app 版或 web 版）")
    ap.add_argument("--out", required=True, help="输出单层 m3u")
    ap.add_argument("--workers", type=int, default=16)
    ap.add_argument("--cors", action="store_true", help="只保留响应头带 ACAO 的频道（Web 端用）")
    a = ap.parse_args()

    items = load_m3u(a.m3u)
    print("输入 %d 条" % len(items))
    keep, fail = [], []
    with ThreadPoolExecutor(max_workers=a.workers) as ex:
        for i, (good, bad) in enumerate(ex.map(one, items), 1):
            if good:
                if a.cors and not good[4]:
                    fail.append((good[0], "no-cors", "", ""))
                else:
                    keep.append(good)
            elif bad:
                fail.append(bad)
            if i % 50 == 0:
                print("  已处理 %d/%d  成功 %d" % (i, len(items), len(keep)), flush=True)

    seen = {}
    for k in keep:
        if k[0] not in seen:
            seen[k[0]] = k
    final = sorted(seen.values(), key=lambda x: x[0])

    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("#EXTM3U\n")
        for name, group, url, codec, _acao in final:
            f.write('#EXTINF:-1 tvg-name="%s" group-title="%s",%s\n' % (name, group, name))
            f.write(url + "\n")
    print("\n=== 结果 ===")
    print("  单层可用 %d 台（去重后）；失败 %d" % (len(final), len(fail)))
    from collections import Counter
    print("  编码:", dict(Counter(k[3] for k in final)))
    print("  分组:", dict(Counter(k[1] for k in final)))
    print("输出:", a.out)
    if fail:
        print("  失败样例:", [f[0] for f in fail[:8]])


if __name__ == "__main__":
    sys.exit(main())
