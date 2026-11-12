import json, urllib.request, urllib.parse, io, time

UA = "Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36"
out = []
def log(s):
    out.append(s)
    print(s)

def fetch(u, t=8):
    r = urllib.request.Request(u, headers={"User-Agent": UA, "Accept": "*/*"})
    return urllib.request.urlopen(r, timeout=t).read()

def probe_img(u):
    try:
        b = fetch(u, 10)
        return "%d %s" % (len(b), b[:4].hex())
    except urllib.error.HTTPError as e:
        return "HTTP%d" % e.code
    except Exception as e:
        return type(e).__name__

SRC = [
    ("maoyan",  "https://api.maoyanapi.top/api.php/provide/vod/", "https://api.maoyanapi.top/"),
    ("ffzy",    "https://api.ffzyapi.com/api.php/provide/vod/",   "https://api.ffzyapi.com/"),
    ("bfzy",    "https://bfzyapi.com/api.php/provide/vod/",       "https://bfzyapi.com/"),
    ("modu",    "https://caiji.moduapi.cc/api.php/provide/vod/",  "https://caiji.moduapi.cc/"),
    ("ikun",    "https://ikunzy.com/api.php/provide/vod/",        "https://ikunzy.com/"),
    ("360zy",   "https://360zy.com/api.php/provide/vod/",         "https://360zy.com/"),
    ("yhzy",    "https://yhzy.cc/api.php/provide/vod/",           "https://yhzy.cc/"),
    ("wsyzy",   "https://wsyzy.cc/api.php/provide/vod/",          "https://wsyzy.cc/"),
    ("cmov",    "https://www.cmov.cn/api.php/provide/vod/",       "https://www.cmov.cn/"),
    ("zuidazy", "https://api.zuidapi.com/api.php/provide/vod/",   "https://api.zuidapi.com/"),
    ("dytt",    "https://caiji.dyttzyapi.com/api.php/provide/vod/", "https://caiji.dyttzyapi.com/"),
    ("jszyapi", "https://jszyapi.com/api.php/provide/vod/",       "https://jszyapi.com/"),
    ("uku",     "https://api.ukuapi.com/api.php/provide/vod/",    "https://api.ukuapi.com/"),
]

def root(api):
    i = api.find("://"); j = api.find("/", i + 3)
    return api[:j] if j > 0 else api

log("=== 每个源：列表页 + suggest 的海报路径形态，以及相对路径能否解析 ===")
for key, api, ref in SRC:
    # 列表页取 3 条看 pic 形态
    try:
        b = fetch(api + "?ac=detail&pg=1", 12)
        d = json.loads(b.decode("utf-8", "replace"))
        lst = (d.get("list") or [])[:3]
    except Exception as e:
        log("%-8s 列表 FAIL %s" % (key, type(e).__name__))
        lst = []
    rels = []
    for o in lst:
        p = o.get("vod_pic") or ""
        kind = "ABS" if p.startswith("http") else ("SLASH" if p.startswith("/") else ("REL" if p else "NONE"))
        if kind in ("SLASH", "REL"):
            rels.append(p)
        log("  %-8s [%s] %s" % (key, kind, p[:70]))

    if rels:
        p = rels[0]
        cands = []
        if p.startswith("/"):
            cands.append(root(api) + p)
            cands.append(ref.rstrip("/") + p)
        else:
            cands.append(root(api) + "/" + p)
            cands.append(ref.rstrip("/") + "/" + p)
        for c in cands:
            log("      -> %-70s %s" % (c[:70], probe_img(c)))

log("")
log("=== 各源 suggest 的 pic 形态（关键词 zhuyu）===")
for key, api, ref in SRC:
    try:
        u = root(api) + "/index.php/ajax/suggest?mid=1&wd=zhuyu"
        b = fetch(u, 8)
        d = json.loads(b.decode("utf-8", "replace"))
        lst = d.get("list") or []
    except Exception as e:
        log("  %-8s FAIL %s" % (key, type(e).__name__))
        continue
    kinds = {}
    first_rel = None
    for o in lst:
        p = o.get("pic") or ""
        k = "ABS" if p.startswith("http") else ("SLASH" if p.startswith("/") else ("REL" if p else "NONE"))
        kinds[k] = kinds.get(k, 0) + 1
        if k in ("SLASH", "REL") and first_rel is None:
            first_rel = p
    log("  %-8s n=%-3d %s" % (key, len(lst), kinds))
    if first_rel:
        base = root(api)
        u = (base + first_rel) if first_rel.startswith("/") else (base + "/" + first_rel)
        log("      -> %-70s %s" % (u[:70], probe_img(u)))

io.open("test/probe_relpic.txt", "w", encoding="utf-8").write("\n".join(out))
