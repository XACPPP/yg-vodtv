# -*- coding: utf-8 -*-
"""Build the final recommended-source markdown table from the two JSON reports."""
import io
import json

FIN = "sources_final.json"
REP = "sources_report.json"
OUT = "sources_recommended.md"

# 排名：内容指纹去重后的代表站，先「剧/综/动漫三类齐全 + 2026 满屏 + m3u8 直连 + 搜索可用」
RANK = [
    ("api.maoyanapi.top", "30 条/页，全部 2026；三分类都满；搜索正常"),
    ("api.guangsuapi.com", "同源镜像最多（21 个域名同一内容库），容灾能力最强"),
    ("caiji.moduapi.cc", "moduapi 同源三域名之一；动漫/综艺更新快"),
    ("api.ffzyapi.com", "ffzy 主站；老片排序问题已消失，首条即 2026"),
    ("www.ysxq.cc", "自有内容库；国产剧 20/20 全是 2026；会 301 到 ysxq.cc"),
    ("yhzy.cc", "自有内容库；国产剧 20/20 全是 2026"),
    ("bfzyapi.com", "自有内容库；综艺分类号 t=46 较特殊，搜索一次返 20 条"),
    ("ikunzyapi.com", "自有内容库；动漫更新快（转生成为魔剑了 第二季）"),
    ("www.cmov.cn", "备用：国产剧 19/20 是 2026，搜索用真实片名可命中"),
    ("360zy.com", "备用：与 caiji.moduapi.cc 同内容库，二选一即可"),
]


def load(p):
    return json.load(io.open(p, encoding="utf-8"))


def main():
    fin = {r["host"]: r for r in load(FIN)}
    rep = {}
    for r in load(REP)["results"]:
        rep.setdefault(r["host"], r)

    L = []
    A = L.append
    A("# YG影视TV 可用采集源（实测）")
    A("")
    A("测试时间 2026-11-12 12:15–12:18（北京时间），UA 固定 Android 5.1.1 VIDAA TV / Chrome 78，超时 12s。")
    A("判定标准：`ac=detail&pg=1` 返回 200 + 合法 JSON + `list` 非空 + 含 `vod_id/vod_name/vod_pic/vod_play_url`，")
    A("且抓取首条 `vod_play_url` 里的 m3u8 真能 200 返回 `#EXTM3U`。")
    A("")
    A("| # | 接口 base | 条/页 | 国产剧 t= | 剧2026 | 综艺 t= | 动漫 t= | m3u8 | suggest | 备注 |")
    A("|---|---|---|---|---|---|---|---|---|---|")
    for i, (host, note) in enumerate(RANK, 1):
        f = fin.get(host) or {}
        r = rep.get(host) or {}
        ch = r.get("cat_hit") or {}
        va = ch.get("variety") or {}
        an = ch.get("anime") or {}
        tv_tid = f.get("tv_tid") or (ch.get("tv") or {}).get("tid") or "-"
        tv_2026 = f.get("tv_2026")
        tv_cnt = f.get("tv_count") or 20
        sug = "OK" if f.get("search_ok") else "FAIL"
        A("| %d | `%s` | %s | %s | %s | %s | %s | %s | %s | %s |" % (
            i, f.get("base", host), f.get("count") or "-", tv_tid,
            ("%s/%s" % (tv_2026, tv_cnt)) if tv_2026 is not None else "-",
            ("%s(%s)" % (va.get("tid"), va.get("name"))) if va else "-",
            ("%s(%s)" % (an.get("tid"), an.get("name"))) if an else "-",
            "LIVE" if f.get("m3u8_live") else "DEAD", sug, note))
    A("")
    A("## 每个源的首条实证")
    A("")
    A("| 接口 base | 首页首条 | 年份 | vod_time | 国产剧首条 | 年份 | m3u8 样例 |")
    A("|---|---|---|---|---|---|---|")
    for host, _ in RANK:
        f = fin.get(host) or {}
        A("| `%s` | %s | %s | %s | %s | %s | `%s` |" % (
            f.get("base", host), f.get("first_name") or "-", f.get("first_year") or "-",
            (f.get("first_time") or "-")[:19], f.get("tv_first") or "-",
            f.get("tv_first_year") or "-", (f.get("m3u8_sample") or "-")[:88]))
    A("")
    A("## 排除说明")
    A("")
    A("| 接口 | 原因 |")
    A("|---|---|")
    A("| `https://api.juliang.live/api.php/provide/vod/` | 首轮探测正常，复核时全路径 404（站点已下线或改路径），不可用 |")
    A("| `https://api.1080zyku.com/api.php/provide/vod/` | 返回 RSS XML 而非 JSON，`at/json` 也一样，不是标准 JSON 接口 |")
    A("| `https://ys.cxkjg.cn/api.php/provide/vod/` | 308 跳转后落回 404 的 HTML 页面（站点前端路由），无 JSON |")
    A("| `https://api.ukuapi.com/api.php/provide/vod/` | 列表接口可用，但 `suggest` 301 到 `api.ukuapi88.com` 后返回 HTML，搜索链路断 |")
    A("| `https://api.wujinapi.me/api.php/provide/vod/` | 列表接口可用，但 `suggest` 被 Cloudflare 挡成 403，搜索链路断 |")
    A("| `cj.lziapi.com` / `lzizy*.com` 全族 | 列表 JSON 正常，但所有 m3u8 直连取不到（DEAD），播放必失败 |")
    A("| `caiji.maotaizy.cc` / `mtzy5.com` / `tyyszy*` / `hong.hgyx.vip` / `api.zeqaht.com` | 同上，m3u8 DEAD |")
    A("| `cdn.dzzyapi.com` | 可用但国产剧首页 2026 数为 0（最新只到 2025），内容偏旧 |")
    A("| 成人向站点（seep.eu.org、ckzy.me、api.heiapi.cc 等） | 接口可用但内容不适合家庭 TV，已单列在 `sources_report.txt` [B] 段 |")

    io.open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(L) + "\n")
    print("written -> %s (%d lines)" % (OUT, len(L)))


if __name__ == "__main__":
    main()
