# -*- coding: utf-8 -*-
"""按实测结果重建 src.txt / sources.json。

- 只保留「内容库互不重复」的采集站（同一片库的镜像只留最快域名）
- 成人向站点不进入配置（家庭电视场景）
- types 列由各站 ac=list 的 class 表真实生成（App 目前不消费，但保持字段有效）

用法: python test/build_src.py
"""
import json
import re
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse

import requests
import urllib3

urllib3.disable_warnings()

UA = ("Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/78.0 Safari/537.36")
HDR = {"User-Agent": UA, "Accept": "application/json,text/plain,*/*"}

# key | 接口 | 说明（同一 key 前缀 = 同一个片库，只是不同域名）
SRC = [
    ("maoyan", "https://api.maoyanapi.top/api.php/provide/vod/", "猫眼 30条/页 元数据全"),
    ("ffzy", "https://api.ffzyapi.com/api.php/provide/vod/", "非凡"),
    ("bfzy", "https://by.bfzyapi.com/api.php/provide/vod/", "暴风"),
    ("modu", "https://www.mdzyapi.com/api.php/provide/vod/", "魔都"),
    ("ikun", "https://ikunzyapi.com/api.php/provide/vod/", "爱坤"),
    ("360zy", "https://360zy.tv/api.php/provide/vod/", "360"),
    ("360zz", "https://360zyzz.com/api.php/provide/vod/", "360zz 另一库"),
    ("yhzy", "https://yhzy.cc/api.php/provide/vod/", "樱花"),
    ("wsyzy", "https://wsyzy.cc/api.php/provide/vod/", "武士"),
    ("cmov", "https://www.cmov.cn/api.php/provide/vod/", "CMOV"),
    ("zuidazy", "https://api.zuidapi.com/api.php/provide/vod/", "最大"),
    ("dytt", "https://caiji.dyttzyapi.com/api.php/provide/vod/", "电影天堂"),
    ("jszyapi", "https://jszyapi.com/api.php/provide/vod/", "极速"),
    ("uku", "https://api.ukuapi88.com/api.php/provide/vod/", "u酷"),
    ("ysxq", "https://www.ysxq.cc/api.php/provide/vod/", "影视新青"),
    ("wujin", "https://api.wujinapi.me/api.php/provide/vod/", "无尽"),
    ("haohua", "https://hhzyapi.com/api.php/provide/vod/", "豪华（与虎牙/金鹰/速播同库，取最快）"),
    ("tianya", "https://tyyszy.com/api.php/provide/vod/", "天涯"),
    ("iqiyi", "https://iqiyizyapi.com/api.php/provide/vod/", "爱奇艺"),
    ("lzizy", "https://lzizy.net/api.php/provide/vod/", "量子"),
    ("zeqaht", "https://api.zeqaht.com/api.php/provide/vod/", "美剧向"),
    ("xgzy", "https://caiji.xgzyapi.com/api.php/provide/vod/", "西瓜"),
    ("hongguo", "https://hong.hgyx.vip/api.php/provide/vod/", "红果"),
]

ADULT = ("伦理", "福利", "写真", "自拍", "成人", "里番", "三级")


def referer(api):
    u = urlparse(api)
    return "%s://%s/" % (u.scheme, u.netloc)


def probe(item):
    key, api, note = item
    url = api + "?ac=list&pg=1"
    t0 = time.time()
    try:
        r = requests.get(url, headers=HDR, timeout=12, verify=False)
        ms = int((time.time() - t0) * 1000)
        if r.status_code != 200:
            return key, api, note, [], "HTTP %s" % r.status_code, ms
        data = r.json()
        cls = data.get("class") or []
        ids = [str(c.get("type_id")) for c in cls if c.get("type_id") is not None]
        names = [str(c.get("type_name", "")) for c in cls]
        adult = [n for n in names if any(a in n for a in ADULT)]
        return key, api, note, ids, ("ADULT:%s" % adult if adult else "ok"), ms
    except Exception as e:  # noqa: BLE001
        return key, api, note, [], "ERR:%s" % repr(e)[:60], 99999


def main():
    rows = []
    with ThreadPoolExecutor(max_workers=10) as ex:
        for r in ex.map(probe, SRC):
            rows.append(r)
    lines, jsrc, bad = [], [], []
    for key, api, note, ids, status, ms in rows:
        types = ",".join(ids) if ids else "0"
        line = "%s|%s|%s|%s" % (key, api, referer(api), types)
        lines.append(line)
        jsrc.append({"key": key, "api": api, "referer": referer(api), "types": types})
        flag = "" if status == "ok" else "  <<<  " + status
        print("%-9s %5dms  %-34s %-12s %s%s" % (key, ms, api, "", note, flag))
        if status != "ok":
            bad.append((key, status))

    with open("app/src/main/assets/src.txt", "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")

    doc = {"version": 1,
           "updated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
           "sources": jsrc}
    with open("app/src/main/assets/sources.json", "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
        f.write("\n")

    print("\n写入 %d 个源；异常 %d 个" % (len(lines), len(bad)))
    for k, s in bad:
        print("  !", k, s)


if __name__ == "__main__":
    main()
