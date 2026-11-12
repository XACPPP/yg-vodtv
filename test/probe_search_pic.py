import json, urllib.request, urllib.parse, io, time

UA = "Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36"

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

out = []
def log(s):
    out.append(s)
    print(s)

def get(u, t=10):
    r = urllib.request.Request(u, headers={"User-Agent": UA, "Accept": "*/*"})
    return urllib.request.urlopen(r, timeout=t).read()

def root(api):
    i = api.find("://")
    j = api.find("/", i + 3)
    return api[:j] if j > 0 else api

KW_CN = "逐玉"
KW_PY = "zhuyu"

for label, kw, mode in [("中文 wd=", KW_CN, "wd"), ("拼音 suggest", KW_PY, "sug")]:
    log("")
    log("======== %s  关键词=%s ========" % (label, kw))
    for key, api, ref in SRC:
        try:
            if mode == "wd":
                u = api + "?ac=detail&wd=" + urllib.parse.quote(kw)
            else:
                u = root(api) + "/index.php/ajax/suggest?mid=1&wd=" + urllib.parse.quote(kw)
            t0 = time.time()
            b = get(u, 8)
            ms = int((time.time() - t0) * 1000)
            d = json.loads(b.decode("utf-8", "replace"))
            lst = d.get("list") or []
            n = len(lst)
            withpic = 0
            withcontent = 0
            sample = ""
            for o in lst:
                p = (o.get("vod_pic") or o.get("pic") or "")
                if p:
                    withpic += 1
                c = (o.get("vod_content") or o.get("vod_blurb") or "")
                if c:
                    withcontent += 1
                if not sample:
                    sample = (o.get("vod_name") or o.get("name") or "?") + " pic=" + (p[:60] if p else "NONE")
            log("  %-8s n=%-3d pic=%-3d content=%-3d %5dms  首条: %s" % (key, n, withpic, withcontent, ms, sample))
        except Exception as e:
            log("  %-8s FAIL %s: %s" % (key, type(e).__name__, str(e)[:70]))

io.open("test/search_pic_probe.txt", "w", encoding="utf-8").write("\n".join(out))
