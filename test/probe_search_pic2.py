import json, urllib.request, urllib.parse, io, time

UA = "Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36"
out = []
def log(s):
    out.append(s)
    print(s)

def get(u, t=10):
    r = urllib.request.Request(u, headers={"User-Agent": UA, "Accept": "*/*"})
    return urllib.request.urlopen(r, timeout=t).read()

def head(u, t=10):
    """返回 (status, bytes_len, magic)"""
    try:
        b = get(u, t)
        return (200, len(b), b[:4].hex())
    except urllib.error.HTTPError as e:
        return (e.code, 0, "")
    except Exception as e:
        return (type(e).__name__, 0, "")

# 1) bfzy suggest 的相对路径，拼到哪些基址能取到
log("=== bfzy suggest 相对路径拼接测试 ===")
u = "https://bfzyapi.com/index.php/ajax/suggest?mid=1&wd=zhuyu"
d = json.loads(get(u).decode("utf-8", "replace"))
rel = None
for o in (d.get("list") or []):
    p = o.get("pic") or ""
    if p.startswith("/"):
        rel = p
        log("  相对路径样本: id=%s name=%s pic=%s" % (o.get("id"), o.get("name"), p))
        break
if rel:
    for base in ["https://p.bfvp26.com", "https://bfzyapi.com", "https://www.bfzyapi.com",
                 "https://bfzyapi.com/", "http://p.bfvp26.com"]:
        s, n, mg = head(base + rel)
        log("  %-32s -> %s len=%s magic=%s" % (base, s, n, mg))

# 2) suggest 拿到的 id 调 detail 能否补全 pic+content
log("")
log("=== suggest id -> detail 补全测试（用 maoyan）===")
kw = "zhuyu"
u = "https://api.maoyanapi.top/index.php/ajax/suggest?mid=1&wd=" + kw
d = json.loads(get(u).decode("utf-8", "replace"))
lst = d.get("list") or []
log("  suggest 返回 %d 条，前 3 个 id: %s" % (len(lst), [o.get("id") for o in lst[:3]]))
for o in lst[:3]:
    vid = o.get("id")
    try:
        u2 = "https://api.maoyanapi.top/api.php/provide/vod/?ac=detail&ids=" + str(vid)
        d2 = json.loads(get(u2).decode("utf-8", "replace"))
        arr = d2.get("list") or []
        if arr:
            a = arr[0]
            log("  id=%-8s name=%-12s pic=%s content=%d字" % (
                vid, a.get("vod_name"), (a.get("vod_pic") or "NONE")[:55],
                len(a.get("vod_content") or "")))
        else:
            log("  id=%-8s detail 空" % vid)
    except Exception as e:
        log("  id=%-8s FAIL %s" % (vid, type(e).__name__))

# 3) bfzy 的 suggest 条目 detail 是否也给绝对路径
log("")
log("=== bfzy suggest id -> detail ===")
u = "https://bfzyapi.com/index.php/ajax/suggest?mid=1&wd=" + kw
d = json.loads(get(u).decode("utf-8", "replace"))
lst = d.get("list") or []
for o in lst[:3]:
    vid = o.get("id")
    try:
        u2 = "https://bfzyapi.com/api.php/provide/vod/?ac=detail&ids=" + str(vid)
        d2 = json.loads(get(u2).decode("utf-8", "replace"))
        arr = d2.get("list") or []
        if arr:
            a = arr[0]
            log("  id=%-8s name=%-10s pic=%s content=%d字" % (
                vid, a.get("vod_name"), (a.get("vod_pic") or "NONE")[:58],
                len(a.get("vod_content") or "")))
        else:
            log("  id=%-8s detail 空" % vid)
    except Exception as e:
        log("  id=%-8s FAIL %s" % (vid, type(e).__name__))

# 4) 各源 suggest 的 pic 是相对路径的比例
log("")
log("=== 各源 suggest 相对路径占比（关键词 zhuyu）===")
SRC = [
    ("maoyan", "https://api.maoyanapi.top/api.php/provide/vod/"),
    ("ffzy", "https://api.ffzyapi.com/api.php/provide/vod/"),
    ("bfzy", "https://bfzyapi.com/api.php/provide/vod/"),
    ("modu", "https://caiji.moduapi.cc/api.php/provide/vod/"),
    ("ikun", "https://ikunzy.com/api.php/provide/vod/"),
    ("360zy", "https://360zy.com/api.php/provide/vod/"),
    ("yhzy", "https://yhzy.cc/api.php/provide/vod/"),
    ("wsyzy", "https://wsyzy.cc/api.php/provide/vod/"),
    ("cmov", "https://www.cmov.cn/api.php/provide/vod/"),
    ("zuidazy", "https://api.zuidapi.com/api.php/provide/vod/"),
    ("dytt", "https://caiji.dyttzyapi.com/api.php/provide/vod/"),
    ("jszyapi", "https://jszyapi.com/api.php/provide/vod/"),
    ("uku", "https://api.ukuapi.com/api.php/provide/vod/"),
]
def root(api):
    i = api.find("://"); j = api.find("/", i + 3)
    return api[:j] if j > 0 else api
for key, api in SRC:
    try:
        u = root(api) + "/index.php/ajax/suggest?mid=1&wd=" + kw
        d = json.loads(get(u, 8).decode("utf-8", "replace"))
        lst = d.get("list") or []
        rel_n = sum(1 for o in lst if (o.get("pic") or "").startswith("/"))
        empty_n = sum(1 for o in lst if not (o.get("pic") or ""))
        log("  %-8s n=%-3d 相对=%-3d 空=%-3d" % (key, len(lst), rel_n, empty_n))
    except Exception as e:
        log("  %-8s FAIL %s" % (key, type(e).__name__))

io.open("test/search_pic_probe2.txt", "w", encoding="utf-8").write("\n".join(out))
