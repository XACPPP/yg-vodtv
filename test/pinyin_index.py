#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
拼音首字母索引生成器（v2.5）

## 为什么要自己建

苹果 CMS 的搜索接口只认中文和**全拼**：
    ?ac=detail&wd=伟大的长征   -> 11 个源命中
    ?ac=detail&wd=weidadechangzheng -> 只有 bfzy 命中
    ?ac=detail&wd=wddcz        -> 只有 bfzy 返回 20 条噪声，目标片子不在里面

也就是说服务端根本没有「首字母」索引。TVBox 那套也没有（FongMi/TV 仓库里
连一个拼音工具类都没有，takagen99/Box 只有一个排序用的 PinyinAdapter），
所以只能自己在客户端建一份。

## 数据从哪来

每个源的 `?ac=list&pg=N` 都自带两个字段：
    vod_name  中文片名
    vod_en    全拼（伟大的长征 -> weidadechangzheng）
14 个源全部都有 vod_en，所以我们**不需要中文转拼音的字库**，
只要把 vod_en 按音节切开、每个音节取首字母即可：
    wei/da/de/chang/zheng -> wddcz

## 产物

    app/src/main/assets/py/manifest.json   桶名 -> 记录数 + 生成时间
    app/src/main/assets/py/{L}.txt         首字母为 L 的全部记录，纯文本

每行一条：`首字母<TAB>片名`，按首字母排序。客户端只按需下自己那一桶，
最大的一桶约 250 KB，电视上几十毫秒就能读完。

## 为什么是纯文本不是 gzip

gzip 之后每次重建整个文件都变，git 只能整份存副本：日更一年就是几百 MB
仓库体积。纯文本按行排序，新增几条只产生很小的 delta。而且 GitHub raw 本身
就对文本做 gzip 传输，客户端拿到的实际字节数跟存 gzip 差不多。

## 为什么只用一个源

bfzy 的片库最大（17 万条）且翻页最快（19 页/秒），一源建索引就够覆盖。
查到期片名以后，客户端拿**中文名**去问全部 14 个源，命中面反而最广——
索引只负责把「首字母」翻译成「中文名」，这一步不需要多源投票。

用法：
    python test/pinyin_index.py            # 全量重建（约 8 分钟）
    python test/pinyin_index.py --pages 50 # 只建前 50 页，调试用
"""

import json
import os
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import source_scan as sc  # noqa: E402  复用它的 get()：带 IPv4 强制与重试

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "..", "app", "src", "main", "assets", "py")

# 索引源：片库最大、翻页最快
BASE = "https://bfzyapi.com/api.php/provide/vod/"
REFERER = "https://bfzy.tv"
WORKERS = 20
PAGE_SIZE = 20  # 服务端强制 20 条/页，limit= 不认

# 无声调拼音音节表（410 条）。切分时要按长度从长到短试，否则
# "chang" 会被切成 "c+hang"。
SYLLABLES = set("""
a ai an ang ao
ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu
ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi
chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan
cui cun cuo
da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou
du duan dui dun duo
e ei en eng er
fa fan fang fei fen feng fo fou fu
ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo
ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo
ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun
ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo
la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu long
lou lu luan lue lun luo lv lve
ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu
na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong
nou nu nuan nue nuo nv nve
o ou
pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu
qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun
ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo
sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng
shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui
sun suo
ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo
wa wai wan wang wei wen weng wo wu
xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun
ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun
za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhen zheng
zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan
zui zun zuo
""".split())

MAX_LEN = max(len(s) for s in SYLLABLES)


def initials_of(en):
    """全拼 -> 首字母串。切不动就返回 None。

    先过滤成只剩 a-z：源站数据里有 `121212`、`nitianxieshen3D`、
    `nishishijiancuican_9a6139` 这类脏值，直接切会切出一堆单字母。
    """
    s = re.sub(r"[^a-z]", "", (en or "").lower())
    if not s:
        return None

    # 从后往前的 DP：f[i] 表示 s[i:] 能切成几个音节，切不动为 -1
    n = len(s)
    f = [-1] * (n + 1)
    step = [0] * (n + 1)  # 记录这一步切了几个字母，便于回溯
    f[n] = 0
    for i in range(n - 1, -1, -1):
        best = -1
        best_len = 0
        for L in range(min(MAX_LEN, n - i), 0, -1):
            if s[i:i + L] in SYLLABLES and f[i + L] >= 0:
                if best < 0 or f[i + L] + 1 < best:
                    best = f[i + L] + 1
                    best_len = L
        if best >= 0:
            f[i] = best
            step[i] = best_len

    if f[0] < 0:
        return None

    out = []
    i = 0
    while i < n:
        out.append(s[i])
        i += step[i]
    return "".join(out)


def fetch_page(pg):
    url = BASE + "?ac=list&pg=" + str(pg)
    st, ms, raw, err = sc.get(url, REFERER, timeout=25)
    if st != 200 or not raw:
        return (pg, None, err or ("http %s" % st))
    try:
        d = json.loads(raw.decode("utf-8", "ignore"))
    except Exception as e:
        return (pg, None, "json %s" % e)
    return (pg, d, None)


def main():
    pages_cap = 0
    if "--pages" in sys.argv:
        pages_cap = int(sys.argv[sys.argv.index("--pages") + 1])

    t0 = time.time()
    print("[py] 探第一页拿总页数 ...")
    st, ms, raw, err = sc.get(BASE + "?ac=list&pg=1", REFERER, timeout=25)
    if st != 200 or not raw:
        print("[py] 第一页就失败：%s %s" % (st, err))
        return 1
    head = json.loads(raw.decode("utf-8", "ignore"))
    total_pages = int(head.get("pagecount") or 0)
    total_items = int(head.get("total") or 0)
    print("[py] 总页数 %s，总条目 %s，限速 %s 条/页" % (total_pages, total_items, PAGE_SIZE))
    if pages_cap > 0:
        total_pages = min(total_pages, pages_cap)
        print("[py] 本次只建前 %s 页" % total_pages)

    def take(pg):
        d = head if pg == 1 else None
        if d is None:
            _, d2, e2 = fetch_page(pg)
            if d2 is None:
                return []
            d = d2
        out = []
        for x in (d.get("list") or []):
            name = (x.get("vod_name") or "").strip()
            if not name:
                continue
            ini = initials_of(x.get("vod_en"))
            if not ini:
                continue
            out.append((ini, name))
        return out

    rows = []
    done = 0
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        for got in ex.map(take, range(1, total_pages + 1)):
            rows.extend(got)
            done += 1
            if done % 500 == 0:
                print("[py] 已翻 %s/%s 页，累计 %s 条，%.1fs"
                      % (done, total_pages, len(rows), time.time() - t0))

    # 同名同首字母只留一条
    uniq = sorted(set(rows))
    print("[py] 原始 %s 条，去重后 %s 条，耗时 %.1fs"
          % (len(rows), len(uniq), time.time() - t0))

    buckets = {}
    for ini, name in uniq:
        buckets.setdefault(ini[0], []).append("%s\t%s" % (ini, name))

    if not os.path.isdir(OUT_DIR):
        os.makedirs(OUT_DIR)

    manifest = {
        "version": 1,
        "updated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source": "bfzy",
        "total": len(uniq),
        "buckets": {},
    }
    grand = 0
    for letter in sorted(buckets):
        lines = sorted(buckets[letter])
        body = "\n".join(lines) + "\n"
        path = os.path.join(OUT_DIR, letter + ".txt")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(body)
        size = os.path.getsize(path)
        grand += size
        manifest["buckets"][letter] = {"n": len(lines), "bytes": size}
        print("[py]   %s: %4d 条 -> %6d 字节" % (letter, len(lines), size))

    with open(os.path.join(OUT_DIR, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")

    print("[py] 完成：%s 个桶，合计 %d 字节（%.2f MB），耗时 %.1fs"
          % (len(buckets), grand, grand / 1048576.0, time.time() - t0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
