# 验证 Proxy.java 里 resolve/normalize/rewrite 的逻辑是否正确
# 用真实片源跑一遍：master -> 变体 -> TS
import json
import urllib.parse
import urllib.request

API = "https://api.wsyzy.net/api.php/provide/vod/"
UA = ("Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36")


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": "https://wsyzy.cc/"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read(), r.status


def normalize(path):
    out = []
    for x in path.split("/"):
        if x == "" or x == ".":
            continue
        if x == "..":
            if out:
                out.pop()
            continue
        out.append(x)
    return "/" + "/".join(out)


def resolve(base, ref):
    if ref.startswith("http://") or ref.startswith("https://"):
        return ref
    if ref.startswith("//"):
        i = base.find("://")
        return (base[:i] if i > 0 else "https") + ":" + ref
    hs = base.find("://")
    if hs < 0:
        return ref
    ps = base.find("/", hs + 3)
    origin = base[:ps] if ps > 0 else base
    p = base[ps:] if ps > 0 else "/"
    q = p.find("?")
    if q >= 0:
        p = p[:q]
    sl = p.rfind("/")
    d = p[:sl + 1] if sl >= 0 else "/"
    full = ref if ref.startswith("/") else d + ref
    rq = full.find("?")
    path_only = full if rq < 0 else full[:rq]
    query = "" if rq < 0 else full[rq:]
    return origin + normalize(path_only) + query


def rewrite(playlist, base, tag="local"):
    lines = []
    for line in playlist.replace("\r", "").split("\n"):
        line = line.strip()
        if not line:
            continue
        if line.startswith("#"):
            if 'URI="' in line:
                import re
                line = re.sub(r'URI="([^"]+)"',
                              lambda m: 'URI="%s"' % ("<<%s>>" % resolve(base, m.group(1))), line)
            lines.append(line)
        else:
            lines.append("<<%s>>" % resolve(base, line))
    return "\n".join(lines)


# 1. 取真实影片
body, _ = get(API + "?ac=detail&ids=135547")
d = json.loads(body)
v = d["list"][0]
print("影片:", v["vod_name"], "|", v["vod_remarks"])
ep = v["vod_play_url"].split("#")[0]
url = ep.split("$", 1)[1]
print("首集地址:", url)

# 2. master m3u8
raw, st = get(url)
master = raw.decode("utf-8", "replace")
print("\n=== master (%d bytes, HTTP %d) ===" % (len(raw), st))
print(master)
rw = rewrite(master, url)
print("\n=== master 改写后 ===")
print(rw)

# 3. 变体 m3u8
variants = [l[2:-2] for l in rw.split("\n") if l.startswith("<<")]
assert variants, "没找到变体"
var_url = variants[0]
print("\n变体地址:", var_url)
assert ".." not in var_url and "/video/" in var_url, "相对路径解析异常: %s" % var_url

raw2, st2 = get(var_url)
media = raw2.decode("utf-8", "replace")
print("\n=== media playlist 前 12 行 (HTTP %d, %d bytes) ===" % (st2, len(raw2)))
for l in media.split("\n")[:12]:
    print(l)
rw2 = rewrite(media, var_url)
segs = [l[2:-2] for l in rw2.split("\n") if l.startswith("<<")]
print("\n分片数:", len(segs))
print("首个分片:", segs[0] if segs else "无")
assert segs, "没解析到分片"

# 4. TS 可达性
ts, st3 = get(segs[0])
print("\nTS 拉取: HTTP %d, %d 字节, 首字节 %s" % (st3, len(ts), ts[:1].hex()))
assert st3 == 200 and len(ts) > 100000
assert ts[0] == 0x47, "不是合法 TS 包"
print("\n✅ 三跳链路全部验证通过：master -> 变体 -> TS")
