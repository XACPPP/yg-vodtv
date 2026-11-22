# -*- coding: utf-8 -*-
"""按片名集合的 Jaccard 相似度找出「其实是同一个片库」的源，并给出建议。

用法: python test/dedupe_check.py [pages]
输出: test/dedupe_report.txt
"""
import itertools
import sys
from concurrent.futures import ThreadPoolExecutor

import requests
import urllib3

urllib3.disable_warnings()

UA = ("Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/78.0 Safari/537.36")
PAGES = int(sys.argv[1]) if len(sys.argv) > 1 else 3
THRESH = 0.55


def norm(s):
    return "".join(ch for ch in str(s) if ch.isalnum())


def grab(row):
    key, api, ref, _ = row
    names = set()
    for pg in range(1, PAGES + 1):
        for attempt in range(2):
            try:
                r = requests.get(api + "?ac=detail&pg=%d" % pg,
                                 headers={"User-Agent": UA, "Referer": ref},
                                 timeout=15, verify=False)
                for it in (r.json().get("list") or []):
                    names.add(norm(it.get("vod_name")))
                break
            except Exception:  # noqa: BLE001
                if attempt:
                    pass
    return key, api, names


def main():
    rows = [l.split("|") for l in open("app/src/main/assets/src.txt",
                                       encoding="utf-8").read().splitlines() if l.strip()]
    res = {}
    with ThreadPoolExecutor(max_workers=8) as ex:
        for key, api, names in ex.map(grab, rows):
            res[key] = (api, names)
    keys = list(res)
    lines = ["片名集合相似度（%d 页/源，阈值 %.2f）" % (PAGES, THRESH), ""]
    dup = []
    for a, b in itertools.combinations(keys, 2):
        na, nb = res[a][1], res[b][1]
        if not na or not nb:
            continue
        j = len(na & nb) / len(na | nb)
        if j >= THRESH:
            dup.append((j, a, b))
    dup.sort(reverse=True)
    for j, a, b in dup:
        lines.append("  %.2f  %s <-> %s" % (j, a, b))
    if not dup:
        lines.append("  没有发现重复片库")
    lines.append("")
    lines.append("各源条目数：")
    for k in keys:
        lines.append("  %-9s %d" % (k, len(res[k][1])))
    with open("test/dedupe_report.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines[:40]))


if __name__ == "__main__":
    main()
