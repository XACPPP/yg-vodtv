#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对 m3u 做质量审计：找出假台/占位台/重复地址，统计央视与卫视覆盖。

飞牛那边「全部不能播」时，常见状况是列表里混进了大量占位流（无信号、商品频道、
影视切片、春晚回放）和同一地址反复占用不同台名——看起来 400 多台，实际能看的没几个。

用法：
  python audit_m3u.py out/yg-live-app-final.m3u
  python audit_m3u.py out/yg-live-web-final.m3u --json out/audit.json
"""
import argparse
import io
import json
import re
import sys
from collections import Counter, defaultdict

# 明显不是直播频道的名字（占位/切片/回放/广告）
FAKE_PAT = re.compile(r"无信号|商品|购物|测试|彩条|占位|春晚|花絮|预告|片段|合集|剪辑")

# 央视 / 卫视 标准台名，用来判断覆盖度
CCTV = ["CCTV-%d" % i for i in range(1, 18)] + ["CCTV-5+", "CCTV-4K", "CCTV-8K", "CGTN"]
SAT = ["湖南卫视", "浙江卫视", "江苏卫视", "东方卫视", "北京卫视", "安徽卫视", "山东卫视",
       "广东卫视", "深圳卫视", "天津卫视", "四川卫视", "湖北卫视", "河南卫视", "河北卫视",
       "江西卫视", "福建东南卫视", "辽宁卫视", "黑龙江卫视", "吉林卫视", "山西卫视",
       "陕西卫视", "甘肃卫视", "青海卫视", "宁夏卫视", "新疆卫视", "云南卫视", "贵州卫视",
       "广西卫视", "海南卫视", "重庆卫视", "内蒙古卫视", "西藏卫视", "厦门卫视",
       "三沙卫视", "延边卫视", "康巴卫视", "安多卫视", "农林卫视", "海峡卫视"]


def is_fake(name):
    n = (name or "").strip()
    if not n:
        return True
    return bool(FAKE_PAT.search(n))


def load(path):
    entries, i = [], 0
    lines = io.open(path, encoding="utf-8").read().split("\n")
    while i < len(lines):
        if lines[i].startswith("#EXTINF"):
            name = lines[i].rsplit(",", 1)[-1].strip()
            group = ""
            for p in lines[i].split(",")[0].split():
                if p.startswith("group-title="):
                    group = p.split("=", 1)[1].strip('"')
            if i + 1 < len(lines):
                entries.append({"name": name, "group": group, "url": lines[i + 1].strip()})
            i += 2
        else:
            i += 1
    return entries


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("m3u")
    ap.add_argument("--json")
    a = ap.parse_args()

    es = load(a.m3u)
    names = Counter(e["name"] for e in es)
    urls = Counter(e["url"] for e in es)
    fake = [e for e in es if is_fake(e["name"])]
    dup_url = {u: c for u, c in urls.items() if c > 1}

    # 同一地址挂多个台名 = 假台（一个流不可能同时是 3 个频道）
    by_url = defaultdict(list)
    for e in es:
        by_url[e["url"]].append(e["name"])
    alias_fake = {u: ns for u, ns in by_url.items() if len(set(ns)) > 1}

    have = set(names)
    cctv_miss = [c for c in CCTV if c not in have]
    sat_miss = [s for s in SAT if s not in have]

    report = {
        "file": a.m3u,
        "total": len(es),
        "unique_name": len(names),
        "unique_url": len(urls),
        "fake": len(fake),
        "dup_url": len(dup_url),
        "alias_fake": len(alias_fake),
        "cctv_have": len([c for c in CCTV if c in have]),
        "cctv_need": len(CCTV),
        "cctv_miss": cctv_miss,
        "sat_have": len([s for s in SAT if s in have]),
        "sat_need": len(SAT),
        "sat_miss": sat_miss[:15],
        "groups": dict(Counter(e["group"] for e in es)),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if fake:
        print("\n占位/假台样例:", [e["name"] for e in fake[:10]])
    if alias_fake:
        k = list(alias_fake)[0]
        print("同一地址挂多台名样例:", alias_fake[k], k[:70])
    if a.json:
        io.open(a.json, "w", encoding="utf-8").write(
            json.dumps({"report": report,
                        "fake_names": [e["name"] for e in fake],
                        "alias": {u: ns for u, ns in alias_fake.items()}},
                       ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
