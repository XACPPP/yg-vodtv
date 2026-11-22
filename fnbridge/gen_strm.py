#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 YG影视 的点播源导成 STRM 库，交给飞牛影视刮削播放。

飞牛影视读一个 .strm 文件（里面就一行播放地址）就会用自带播放器去播，
所以这里把每个源的片单抓下来，按飞牛认得的命名规则铺成目录树：

    电影/片名 (2026)/片名 (2026).strm
    电视剧/剧名 (2025)/Season 01/剧名 S01E01.strm
    动漫/…  综艺/…  纪录片/…

命名带年份是给飞牛刮削 TMDB 用的（飞牛没有本地 nfo，只认文件名）。

用法:
    python gen_strm.py                          # 每源抓 20 页，输出到 out/strm
    python gen_strm.py --pages 60 --per-source 1200
    python gen_strm.py --sources maoyan,ffzy    # 只跑指定源
    python gen_strm.py --dry-run                # 只看统计不落盘
"""
import argparse
import json
import os
import re
import ssl
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.request import Request, urlopen

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.abspath(os.path.join(HERE, "..", "app", "src", "main", "assets"))
UA = ("Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/78.0 Safari/537.36")
BAD = re.compile(r'[\\/:*?"<>|\r\n\t]')
_print_lock = threading.Lock()

# 站点自带的分类名 → 库目录
RULES = [
    ("动漫", ("动漫", "动画", "番剧", "里番")),
    ("综艺", ("综艺", "晚会", "脱口秀")),
    ("纪录片", ("纪录", "记录")),
    ("电视剧", ("电视剧", "连续剧", "剧集", "国产剧", "欧美剧", "日剧", "韩剧",
                "港剧", "台剧", "泰剧", "海外剧", "短剧", "微短剧")),
    ("电影", ("电影", "片")),
]


def log(*a):
    with _print_lock:
        print(*a, flush=True)


def clean(name, limit=70):
    s = BAD.sub(" ", str(name or "")).strip()
    s = re.sub(r"\s+", " ", s)
    s = s.strip(" .")
    return s[:limit].strip() or "未命名"


def bucket(type_name):
    t = str(type_name or "")
    for lib, keys in RULES:
        for k in keys:
            if k in t:
                return lib
    return "电影"


def load_sources(path):
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            p = line.split("|")
            if len(p) >= 3:
                out.append((p[0], p[1], p[2]))
    return out


_SSL = ssl._create_unverified_context()  # 采集站证书五花八门，飞牛/电视端也不校验证书


def get(url, referer=None, timeout=20):
    hdr = {"User-Agent": UA, "Accept": "application/json,text/plain,*/*"}
    if referer:
        hdr["Referer"] = referer
    req = Request(url, headers=hdr)
    with urlopen(req, timeout=timeout, context=_SSL) as r:
        return json.loads(r.read().decode("utf-8", "ignore"))


def fetch_source(src, pages, per_source):
    key, api, ref = src
    rows = []
    for pg in range(1, pages + 1):
        data = None
        for attempt in range(3):
            try:
                data = get("%s?ac=detail&pg=%d" % (api, pg), ref)
                break
            except Exception as e:  # noqa: BLE001
                if attempt == 2:
                    log("  ! %s 第%d页失败: %s" % (key, pg, repr(e)[:70]))
                time.sleep(1 + attempt)
        if not data:
            break
        lst = data.get("list") or []
        if not lst:
            break
        rows.extend(lst)
        if len(rows) >= per_source:
            break
        total_pages = int(str(data.get("pagecount") or 0) or 0)
        if total_pages and pg >= total_pages:
            break
    return rows


def episodes_of(item):
    """返回 [(集号, url)]，按线路里集数最多的一条取。"""
    play = str(item.get("vod_play_url") or "")
    if not play:
        return []
    best = []
    for line in play.split("$$$"):
        eps = []
        for seg in line.split("#"):
            seg = seg.strip()
            if not seg or "$" not in seg:
                continue
            label, url = seg.rsplit("$", 1)
            url = url.strip()
            if not url.lower().startswith("http"):
                continue
            m = re.search(r"(\d{1,4})", label)
            no = int(m.group(1)) if m else len(eps) + 1
            eps.append((no, url))
        if len(eps) > len(best):
            best = eps
    return best


BADGE_RE = re.compile(r"[（(【\[]\s*(?:中配|国语|粤语|日语|英语|韩语|泰语|普通话|双语|中字|台配|原声|"
                      r"日语版|国语版|中字版|高清|超清|蓝光|4K|HD|TC|TS)(?:版)?\s*[）)】\]]", re.I)
SUFFIX_RE = re.compile(r"\s*(?:国语版|粤语版|中配版|日语版|普通话版|普通话|国语|粤语|中配|日语|"
                       r"原声版|双语版|高清版|超清版|中字版)$")


YEAR_RE = re.compile(r"\s*[（(](\d{4})[）)]\s*$")


def dedupe_key(lib, title):
    """同剧的不同配音/清晰度版本归成一个 key，避免同一部剧铺出好几份。"""
    m = YEAR_RE.search(title)
    year = " (%s)" % m.group(1) if m else ""
    t = title[:m.start()] if m else title
    t = BADGE_RE.sub("", t)
    prev = None
    while prev != t:
        prev = t
        t = SUFFIX_RE.sub("", t)
    t = re.sub(r"\s+", " ", t).strip() or title
    return (lib, t + year)


def build_entry(item, lib):
    name = clean(item.get("vod_name"))
    year = str(item.get("vod_year") or "").strip()
    year = re.sub(r"\D", "", year)[:4]
    eps = episodes_of(item)
    if not eps:
        return None
    title = "%s (%s)" % (name, year) if year else name
    if lib == "电影" or (len(eps) == 1 and lib in ("电影",)):
        # 单片：飞牛按「文件名 = 片名」刮削
        return lib, title, None, [(1, eps[0][1])], "%s.strm" % title
    # 剧集：季目录 + SxxExx
    ep_sorted = sorted(eps, key=lambda x: x[0])
    return lib, title, "Season 01", ep_sorted, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=os.path.join(ASSETS, "src.txt"))
    ap.add_argument("--out", default=os.path.join(HERE, "out", "strm"))
    ap.add_argument("--pages", type=int, default=20, help="每源抓多少页（每页 20~30 条）")
    ap.add_argument("--per-source", type=int, default=600, help="每源最多收录多少条")
    ap.add_argument("--sources", default="", help="只跑这些 key，逗号分隔")
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--prune", action="store_true", help="删除上次生成、这次不再存在的 strm")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    sources = load_sources(a.src)
    if a.sources:
        want = {s.strip() for s in a.sources.split(",") if s.strip()}
        sources = [s for s in sources if s[0] in want]
    log("源 %d 个，每源 %d 页 / 最多 %d 条" % (len(sources), a.pages, a.per_source))

    t0 = time.time()
    raw = []
    with ThreadPoolExecutor(max_workers=a.workers) as ex:
        futs = {ex.submit(fetch_source, s, a.pages, a.per_source): s[0] for s in sources}
        for f in futs:
            key = futs[f]
            try:
                rows = f.result()
            except Exception as e:  # noqa: BLE001
                log("  ! %s 抓取异常 %s" % (key, repr(e)[:70]))
                rows = []
            log("  %-9s %d 条" % (key, len(rows)))
            raw.extend(rows)

    # 同名只留集数最多的一条（多源同片），并按 库/片名 聚合
    picked = {}
    stats = {}
    for it in raw:
        lib = bucket(it.get("type_name") or it.get("vod_type_name"))
        rec = build_entry(it, lib)
        if not rec:
            continue
        lib, title, season, eps, single = rec
        k = dedupe_key(lib, title)
        if k not in picked or len(eps) > len(picked[k][3]):
            picked[k] = rec
        stats[lib] = stats.get(lib, 0) + 1

    log("抓取 %d 条，聚合后 %d 部（库分布：%s）"
        % (len(raw), len(picked), ", ".join("%s %d" % (k, v) for k, v in sorted(stats.items()))))
    if a.dry_run:
        for (lib, title), rec in list(picked.items())[:5]:
            log("  样例: %s/%s  集数=%d" % (lib, title, len(rec[3])))
        return

    manifest_path = os.path.join(os.path.dirname(a.out), "manifest.json")
    old = {}
    if os.path.exists(manifest_path):
        try:
            with open(manifest_path, encoding="utf-8") as f:
                old = json.load(f)
        except Exception:  # noqa: BLE001
            old = {}

    new = {}
    wrote = skipped = 0
    for (lib, title), (_, _, season, eps, single) in picked.items():
        for no, url in eps:
            if single:
                rel = os.path.join(lib, title, single)
            else:
                fname = "%s S01E%02d.strm" % (title, no)
                rel = os.path.join(lib, title, season, fname)
            rel = rel.replace("\\", "/")
            new[rel] = url
            full = os.path.join(a.out, rel)
            if old.get(rel) == url and os.path.exists(full):
                skipped += 1
                continue
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "w", encoding="utf-8", newline="\n") as f:
                f.write(url + "\n")
            wrote += 1

    pruned = 0
    if a.prune:
        for rel in old:
            if rel in new:
                continue
            full = os.path.join(a.out, rel)
            if os.path.exists(full):
                try:
                    os.remove(full)
                    pruned += 1
                except OSError:
                    pass

    os.makedirs(os.path.dirname(manifest_path), exist_ok=True)
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(new, f, ensure_ascii=False, indent=0)
    log("写入 %d 个 strm（跳过未变化 %d，清理 %d），清单 %s"
        % (wrote, skipped, pruned, manifest_path))
    log("耗时 %.0fs，共 %d 个台词条 → %s" % (time.time() - t0, len(new), a.out))


if __name__ == "__main__":
    sys.exit(main())
