# -*- coding: utf-8 -*-
"""对探活通过的采集站做「内容库去重」聚类。

同一套片源会挂很多域名镜像（360zy.com / 360zy.tv / 360zyzz.com 等）。
本脚本抓每个 base 的首页片名集合做签名，签名相同即视为同一库，
只保留响应最快的一个域名，避免 src.txt 里塞满重复镜像。

用法:
    python test/cluster_sources.py test/probe_run6.txt [--pool test/candidate_pool2.txt]
输出:
    test/source_clusters.txt
"""
import hashlib
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import requests
import urllib3

urllib3.disable_warnings()

UA = ("Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/78.0 Safari/537.36")
HDR = {"User-Agent": UA, "Accept": "application/json,text/plain,*/*"}
TIMEOUT = 12


def bases_from_report(path):
    """从 probe 报告里抽 A 段（推荐接入）的域名，拼回接口地址。"""
    out = []
    in_a = False
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("[A]"):
                in_a = True
                continue
            if line.startswith("[B]"):
                break
            if in_a:
                m = re.match(r"^\s*\d+\s+(\S+)\s", line)
                if m:
                    out.append(m.group(1))
    return out


def build_url(host):
    if host.startswith(("http://", "https://")):
        base = host
    else:
        base = "https://" + host
    if "/api.php" in base:
        return base if base.endswith("/") else base + "/"
    return base.rstrip("/") + "/api.php/provide/vod/"


def probe(base):
    url = build_url(base) + "?ac=detail&pg=1"
    t0 = time.time()
    try:
        r = requests.get(url, headers=HDR, timeout=TIMEOUT, verify=False)
        ms = int((time.time() - t0) * 1000)
        if r.status_code != 200:
            return None
        data = r.json()
        lst = data.get("list") or []
        if not lst:
            return None
        names = tuple(sorted(str(x.get("vod_name", ""))[:16] for x in lst[:10]))
        sig = hashlib.md5("|".join(names).encode()).hexdigest()[:10]
        newest = max((str(x.get("vod_year", "")) for x in lst), default="")
        return {
            "base": build_url(base),
            "host": base,
            "ms": ms,
            "n": len(lst),
            "sig": sig,
            "first": str(lst[0].get("vod_name", "")),
            "year": newest,
            "sample": [str(x.get("vod_name", "")) for x in lst[:3]],
        }
    except Exception:  # noqa: BLE001
        return None


def hosts_from_list(path):
    """从纯域名/URL 清单（或 probe 的 console 日志 OK 行）里抽 base。"""
    out = []
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.rstrip()
            m = re.search(r"OK\s+([a-z0-9.\-]+\.[a-z]{2,}(?::\d+)?)", line)
            if m:
                out.append(m.group(1))
                continue
            if line and not line.startswith(("#", "=", " ", "\t")):
                out.append(line.strip())
    return out


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "test/probe_run6.txt"
    with open(src, encoding="utf-8", errors="ignore") as f:
        head = f.read(400)
    hosts = bases_from_report(src) if head.lstrip().startswith("=") else hosts_from_list(src)
    if not hosts:
        print("没有解析到 base:", src)
        return
    outdir = sys.argv[2] if len(sys.argv) > 2 else "test"
    print("待聚类 base:", len(hosts))
    seen, uniq = set(), []
    for h in hosts:
        if h not in seen:
            seen.add(h)
            uniq.append(h)
    results = []
    with ThreadPoolExecutor(max_workers=14) as ex:
        for r in ex.map(probe, uniq):
            if r:
                results.append(r)
    print("响应成功:", len(results))

    clusters = {}
    for r in results:
        clusters.setdefault(r["sig"], []).append(r)

    lines = []
    lines.append("=" * 96)
    lines.append(" 采集站内容库聚类（同一签名 = 同一个片库的不同镜像，只留最快域名）")
    lines.append(" 库数 %d / 域名 %d" % (len(clusters), len(results)))
    lines.append("=" * 96)
    picked = []
    for i, (sig, rs) in enumerate(sorted(clusters.items(),
                                         key=lambda kv: -max(len(x["sample"]) and 1 for x in kv[1])), 1):
        rs.sort(key=lambda x: x["ms"])
        best = rs[0]
        picked.append(best)
        lines.append("")
        lines.append("[库%02d] 最快 %.0fms  %s   条数=%d 首条=%s 最新年=%s"
                     % (i, best["ms"], best["base"], best["n"], best["first"], best["year"]))
        lines.append("        样例: %s" % " / ".join(best["sample"]))
        if len(rs) > 1:
            lines.append("        同库镜像(%d): %s" % (len(rs) - 1,
                                                    ", ".join("%s %.0fms" % (x["host"], x["ms"]) for x in rs[1:8])))

    with open("test/source_clusters.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    with open("test/source_picked.txt", "w", encoding="utf-8") as f:
        for b in picked:
            f.write(b["base"] + "\n")
    print("库数:", len(clusters), "-> test/source_clusters.txt, test/source_picked.txt")


if __name__ == "__main__":
    import urllib3 as u

    u.disable_warnings()
    main()
