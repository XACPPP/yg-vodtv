# -*- coding: utf-8 -*-
"""live_scan.py -- 直播源采集 + 测速 + 台标落盘 + 分组归一，产出 app/src/main/assets/live.json

为什么必须测速，不能照抄源站列表
    Guovin/iptv-api 的 result.txt 有上千条 URL，但实测能播的不到一半。
    源站给的是「它扫过」，不是「现在能播」；直接塞进 App 的结果就是用户点 10 个台 6 个黑屏。
    本脚本对每条 URL 发 GET，只有 200 + 响应体前 4096 字节含 #EXTM3U 才算可播，
    并按 TTFB 排序 —— 「卡了换源」要靠同一频道的多条 URL，所以要留住最快的 3 条。

为什么判定只能靠 200 + #EXTM3U
    本机开着 Mihomo TUN，任何地址任何端口都「连得上」，TCP 连通性完全失去判别力。
    必须看真拿到播放列表内容（#EXTM3U）才算数。

为什么要两轮复测
    「第一轮能播、一分钟后就 403」的源是最坑的一种：它通过了所有一次性检查，用户却在
    电视上看着卡屏。第二轮把第一轮通过的全再探一次，两轮都过才留 —— 多花一倍探测时间，
    换掉的是「点开就黑屏」的投诉。

为什么还要真取一个媒体分片算吞吐
    播放列表小到几十 KB，能秒回不代表片子拉得动。很多源列表放在 CDN 上，分片却在一条
    超售的落地机上：列表 200 OK、TTFB 40ms，分片只有 20KB/s，表现就是幻灯片。
    所以顺着 #EXTINF 后面那行真取一个分片，读满 2 秒或 256KB（先到者为准）算 bytes/秒，
    低于 40KB/s（约 320kbps）直接判死。这个数字天天变，只用于筛选，不写进 JSON。

IPv6 必须整条过滤
    电视端 Http.java 静态块里设了 java.net.preferIPv4Stack=true，局域网 IPv6 也禁着。
    源里混着 http://[2409:...]:port/... 这种，手机/电脑能放，电视放不了 —— 直接丢。
    同时复用 source_scan.py 顶部对 socket.getaddrinfo 打的 IPv4-only 补丁，
    否则域名解析拿到 AAAA 记录也会连出一个电视上不存在的「可播」。

台标为什么手搓 PNG 编解码
    CI 只 pip install requests。多一个 Pillow 就多一次「装不上整条流水线挂掉」的概率，
    而台标是 96x54 的小图，纯 zlib + struct 足够快（单张几十毫秒）。只在真需要时
    import PIL 的写法也不做 —— 本机能 import 到，CI 不能，行为分叉比慢更危险。
    只接受真 PNG：jpge/webp/svg 一律跳过留空，宁可让 App 用首字块兜底，
    也不拿「勉强解出来但发虚」的图糊弄。

分组归一
    源站 41 个 emoji 分组（📺央视频道 / 💰央视付费频道 / ☘️广东频道 ……）对电视端太碎，
    收敛成 9 个固定组，顺序写死，只输出有频道的组。

用法
    python live_scan.py                  # 全量，写 assets/live.json + assets/logo/*.png
    python live_scan.py --limit 200      # 只测前 200 条（调试），默认写 test/live_preview.json
    python live_scan.py --out xxx.json   # 指定输出

输出
    {version, updated, total, groups:[{id,name,chs:[{name,logo,urls[]}]}]}
    控制台只打 ASCII —— Windows GBK 控制台遇到 emoji 频道名会直接 UnicodeEncodeError。
"""
from __future__ import annotations

import argparse
import concurrent.futures as futures
import datetime
import hashlib
import io
import json
import os
import struct
import sys
import threading
import time
import unicodedata
import zlib
from urllib.parse import urljoin

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT_JSON = os.path.join(ASSETS, "live.json")
PREVIEW_JSON = os.path.join(HERE, "live_preview.json")
LOGO_DIR = os.path.join(ASSETS, "logo")

# 复用 source_scan.py 的 UA / IPv4-only 补丁；它 import 时只是建 Session，没有副作用。
sys.path.insert(0, HERE)
try:
    import source_scan as _sc
    UA = _sc.UA
    _HAVE_SC = True
except Exception:  # noqa: BLE001  单独跑也要能用
    _HAVE_SC = False
    UA = ("Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 "
          "(KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36")
    import socket as _socket
    _orig_gai = _socket.getaddrinfo

    def _v4_only(host, port, family=0, type=0, proto=0, flags=0):
        return _orig_gai(host, port, _socket.AF_INET, type, proto, flags)

    _socket.getaddrinfo = _v4_only

import requests

try:
    requests.packages.urllib3.disable_warnings()
except Exception:  # noqa: BLE001
    pass

SRC1 = "https://raw.githubusercontent.com/Guovin/iptv-api/gd/output/result.txt"
SRC2 = "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.txt"

UI_UA = ("Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 "
         "(KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36")
CONNECT_TIMEOUT = 4
READ_TIMEOUT = 6
FIRST_BYTES = 4096
WORKERS = 24
MAX_URLS_PER_CH = 3
MIN_ALIVE_CH = 20
GENRE_SUFFIX = ",#genre#"

# ---- 质量门槛（治「特别卡」）----
TTFB_MAX_MS = 3000      # 首块数据超过 3s，用户按下遥控器就觉得电视坏了
MIN_KBPS = 40           # 约 320kbps，低于这个连 480p 都撑不住，实际是幻灯片
SEG_SECONDS = 2.0       # 每个分片最多读这么久；再长一轮探测就兜不住 CI 的 15 分钟上限
SEG_BYTES = 256 * 1024  # 或者读满 256KB 就收工，快源没必要陪它下完整个 TS
SEG_READ_TIMEOUT = 2.0  # 分片读卡住时的 socket 超时，不能沿用 6s，否则 3 倍耗时
PLAYLIST_MAX = 256 * 1024
PLAYLIST_DEADLINE = 6.0

# ---- 台标 ----
LOGO_API_CH = "https://iptv-org.github.io/api/channels.json"
LOGO_API_LG = "https://iptv-org.github.io/api/logos.json"
# 台标以 `asset:logo/xxx.png` 形式进 live.json：build.sh 的 aapt2 link 带 -A assets
# 把 logo/ 打进 APK，App 侧 Ui.assetBitmap() 取 url.substring("asset:".length())
# 得到 `logo/xxx.png` 再 getAssets().open()。前缀必须原样保留，不能加前导斜杠。
LOGO_PREFIX = "asset:"
LOGO_W, LOGO_H = 96, 54
LOGO_DEADLINE = 180.0     # 台标整体耗时上限；超时只是少几张图，不能拖垮巡检
LOGO_MAX_BYTES = 3 << 20
LOGO_MIN_KEEP = 10        # 台标少于这个数就不做孤儿清理，防止一次网络抽风删空目录
# 台标只在华语区找，CN 优先：iptv-org 里同一个台可能在多个国家登记，
# 用 HK/TW 的图替 CN 的台属于「差不多但不该凑」。
COUNTRY_PRIO = ("CN", "HK", "TW", "MO")

# 中文台名 -> iptv-org channel id 的硬映射。
#
# 为什么必须手写：iptv-org 的省级卫视条目 alt_names 大多是空的
# （GuangdongSatelliteTV / HainanSatelliteTV / HubeiSatelliteTV / SichuanSatelliteTV /
#  YanbianSatelliteTV / HenanTVSatellite / HunanTV 全是 []），而源站给的是纯中文
# 「广东卫视」。两边归一化后一个是 GUANGDONGSATELLITETV、一个是 广东卫视，永远对不上。
# 实测只靠自动匹配，382 个台里只能命中 48 个（落地 41 张图）。
#
# 这张表的每一条都手工核对过「logos.json 里确实有这个 id 的图 + 名字确实是这个台」，
# 不确定的一律不写 —— 宁可留空让 App 画短标签色块，也不贴一张别的台的台标。
MANUAL_LOGO_IDS = {
    # 省级卫视（37 个里能核实的 35 个；人间卫视、安多卫视在 iptv-org 里没有图）
    "三沙卫视": "SanshaTV.cn",
    "东南卫视": "FujianSoutheastTV.cn",
    "东方卫视": "DragonTV.cn",
    "云南卫视": "YunnanSatelliteTV.cn",
    "内蒙古卫视": "NeiMonggolTV.cn",
    "北京卫视": "BeijingSatelliteTV.cn",
    "吉林卫视": "JilinSatelliteTV.cn",
    "四川卫视": "SichuanSatelliteTV.cn",
    "大湾区卫视": "GBASatelliteTV.cn",
    "天津卫视": "TianjinTV.cn",
    "宁夏卫视": "NingxiaSatelliteChannel.cn",
    "安徽卫视": "AnhuiSatelliteTV.cn",
    "山东卫视": "ShandongSatelliteTV.cn",
    "山西卫视": "ShanxiTV.cn",
    "广东卫视": "GuangdongSatelliteTV.cn",
    "广西卫视": "GuangxiTV.cn",
    "延边卫视": "YanbianSatelliteTV.cn",
    "新疆卫视": "XinjiangTV1.cn",
    "江苏卫视": "JiangsuTV.cn",
    "江西卫视": "JiangxiTV.cn",
    "河北卫视": "HebeiTV.cn",
    "河南卫视": "HenanTVSatellite.cn",
    "浙江卫视": "ZhejiangSatelliteTV.cn",
    "海南卫视": "HainanSatelliteTV.cn",
    "深圳卫视": "ShenzhenTV.cn",
    "湖北卫视": "HubeiSatelliteTV.cn",
    "湖南卫视": "HunanTV.cn",
    "甘肃卫视": "GansuSatelliteTV.cn",
    "西藏卫视": "XizangTVChinese.cn",
    "贵州卫视": "GuizhouTV.cn",
    "辽宁卫视": "LiaoningTV.cn",
    "重庆卫视": "ChongqingSatelliteTV.cn",
    "陕西卫视": "ShaanxiSatelliteTV.cn",
    "青海卫视": "QinghaiTV.cn",
    "黑龙江卫视": "HeilongjiangTV.cn",
    # 央视：自动匹配救不了这两个 —— CCTV-4 在 iptv-org 拆成亚洲/欧洲/美洲三版，
    # CCTV-5+ 归一化后 key 是 CCTV5，会被 CCTV-5 抢先占位、贴成 5 套的台标。
    "CCTV-4": "CCTV4Asia.cn",
    "CCTV-5+": "CCTV5Plus.cn",
    # CGTN 各语种是独立频道，不能复用主台标
    "CGTN": "CGTN.cn",
    "CGTN法语": "CGTNFrench.cn",
    "CGTN西语": "CGTNSpanish.cn",
    "CGTN阿语": "CGTNArabic.cn",
    "CGTN纪录": "CGTNDocumentary.cn",
    # 港澳台
    "翡翠台": "Jade.hk",
    "凤凰香港": "PhoenixHongKongChannel.hk",
    "TVBS亚洲": "TVBSAsia.tw",
    "纬来体育": "VideolandSports.tw",
    "三立新闻": "SETNews.tw",
    "东森超视": "EBCSuper.tw",
    # 省级地面频道（名字能一一对应的）
    "广东体育": "GuangdongSports.cn",
    "广东珠江": "GuangdongPearlRiverChannel.cn",
    "广东新闻": "GuangdongNewsChannel.cn",
    "广西国际": "GuangxiInternationalChannel.cn",
    "山东教育": "ShandongEducationTV.cn",
    "浙江少儿": "ZhejiangChildrensChannel.cn",
    "浙江经济生活": "ZhejiangEconomicChannel.cn",
    "浙江国际": "ZhejiangInternationalChannel.cn",
    "浙江民生休闲": "ZhejiangMinshengLeisure.cn",
    "浙江教科影视": "ZhejiangFilmEntertainmentChannel.cn",
    "浙江教育": "ZhejiangEducationTechnologyChannel.cn",
    # 电影频道
    "CHC电影": "CHCHomeTheater.cn",
}

# 固定 9 组，顺序写死。空的关键字列表 = 兜底组。
GROUPS = [
    ("cctv", "央视", ["央视频道", "央视付费"]),
    ("sat", "卫视", ["卫视频道"]),
    ("sports", "体育", ["体育频道"]),
    ("movie", "电影", ["电影频道", "经典剧场"]),
    ("hkmotw", "港澳台", ["港·澳·台"]),
    ("kids", "动画", ["动画频道", "游戏频道"]),
    ("local", "地方", [
        # `地方频道` 是源站 315 条最大的一个分组（第一财经 / 北京科教 / 各市台……），
        # 表里没列，但语义就是地方台；不并在 local 会让 other 变成 264 个台的垃圾桶。
        "地方频道",
        "广东频道", "浙江频道", "北京频道", "上海频道", "江苏频道", "湖南频道",
        "湖北频道", "河南频道", "河北频道", "山东频道", "四川频道", "福建频道",
        "安徽频道", "广西频道", "辽宁频道", "陕西频道", "黑龙江频道", "山西频道",
        "贵州频道", "云南频道", "甘肃频道", "海南频道", "天津频道", "新疆频道",
        "吉林频道", "内蒙古频道", "宁夏频道", "青海频道", "重庆频道",
    ]),
    ("other", "其他", []),
]

_DOTS = "·・•‧∙･"


def _nz(s):
    """去掉各种间隔号，港澳台那组源站写的是 `港·澳·台`，间隔号字符不统一。"""
    for d in _DOTS:
        s = s.replace(d, "")
    return s


# ---------------------------------------------------------------- 采集

def fetch_text(url, timeout=25):
    """拉源站文本。失败返回 None（第二个源失败不算错）。"""
    try:
        r = requests.get(url, headers={"User-Agent": UI_UA, "Accept": "*/*"},
                         timeout=timeout, verify=False)
        if r.status_code != 200:
            return None
        return r.content.decode("utf-8", "ignore")
    except Exception:  # noqa: BLE001
        return None


def url_ok(u):
    """过滤：非 http(s) / 带空格 / IPv6 字面量（电视端不认）。"""
    if not u or " " in u or "\t" in u:
        return False
    if not (u.startswith("http://") or u.startswith("https://")):
        return False
    host = u.split("//", 1)[1].split("/", 1)[0]
    host = host.rsplit("@", 1)[-1]
    if host.startswith("["):
        return False
    if host.count(":") > 1:  # 裸 IPv6（理论上非法 URL，防御一下）
        return False
    return True


def norm_name(name):
    """频道名收敛：`CCTV5` -> `CCTV-5`，两个源就能并到同一台，换源才有得换。"""
    n = " ".join(name.split())
    low = n.upper()
    if low.startswith("CCTV"):
        tail = n[4:].strip()
        if tail and tail[0] not in "-":
            n = "CCTV-" + tail
    return n


def parse_text(text):
    """解析 `频道名,url` + `分组名,#genre#`。返回 [(genre, name, url)]。"""
    out = []
    genre = ""
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("//"):
            continue
        if line.endswith("#genre#"):
            g = line[:-len("#genre#")]
            if g.endswith(","):
                g = g[:-1]
            genre = g.strip()
            continue
        name, sep, url = line.partition(",")
        if not sep:
            continue
        name = norm_name(name.strip())
        url = url.strip()
        if not name or not url:
            continue
        out.append((genre, name, url))
    return out


def map_genre(genre):
    """按 GROUPS 表顺序匹配，一个源分组只归一个目标组。"""
    g = _nz(genre)
    for gid, _name, keys in GROUPS:
        for k in keys:
            if _nz(k) and _nz(k) in g:
                return gid
    return "other"


# ---------------------------------------------------------------- 测速

_TL = threading.local()


def _sess():
    s = getattr(_TL, "s", None)
    if s is None:
        s = requests.Session()
        s.headers.update({"User-Agent": UI_UA, "Accept": "*/*",
                          "Accept-Encoding": "gzip, deflate", "Connection": "close"})
        s.verify = False
        _TL.s = s
    return s


def _pick_variant(txt, base):
    """master playlist：取 BANDWIDTH 最大的变体，解析成绝对地址。

    为什么挑最大的：分片吞吐量是要判断「这条源能不能撑住看电视」，用最低码率变体
    测出来的数字会把一条只能放 240p 的源判成合格。
    """
    best = None
    lines = txt.splitlines()
    for i, ln in enumerate(lines):
        s = ln.strip()
        if not s.upper().startswith("#EXT-X-STREAM-INF"):
            continue
        bw = 0
        for part in s.split(":", 1)[-1].split(","):
            part = part.strip()
            if part.upper().startswith("BANDWIDTH="):
                try:
                    bw = int(part.split("=", 1)[1].strip())
                except Exception:  # noqa: BLE001
                    bw = 0
                break
        for j in range(i + 1, len(lines)):
            nxt = lines[j].strip()
            if not nxt or nxt.startswith("#"):
                continue
            if best is None or bw > best[0]:
                best = (bw, nxt)
            break
    if not best:
        return ""
    return urljoin(base, best[1])


def _pick_segment(txt):
    """媒体 playlist：取 #EXTINF 后面第一行非注释地址（= 一个媒体分片）。

    没有 #EXTINF 的裸地址列表也认（部分自制源就这么写）。
    """
    lines = txt.splitlines()
    for i, ln in enumerate(lines):
        if not ln.strip().upper().startswith("#EXTINF"):
            continue
        for j in range(i + 1, len(lines)):
            s = lines[j].strip()
            if not s or s.startswith("#"):
                continue
            return s
    for ln in lines:
        s = ln.strip()
        if s and not s.startswith("#"):
            return s
    return ""


def _fetch_playlist(url):
    """取一份播放列表，返回 (text, final_url)。失败返回 (None, url)。"""
    try:
        r = _sess().get(url, timeout=(CONNECT_TIMEOUT, READ_TIMEOUT), stream=True,
                        allow_redirects=True, verify=False)
        try:
            if r.status_code != 200:
                return None, url
            buf = bytearray()
            t0 = time.time()
            for chunk in r.iter_content(4096):
                if not chunk:
                    continue
                buf += chunk
                if len(buf) >= PLAYLIST_MAX or time.time() - t0 > PLAYLIST_DEADLINE:
                    break
            return bytes(buf).decode("utf-8", "ignore"), (r.url or url)
        finally:
            r.close()
    except Exception:  # noqa: BLE001
        return None, url


def _throughput(url):
    """取一个媒体分片，返回实测 KB/s（0 表示拉不动）。

    读满 SEG_SECONDS 或 SEG_BYTES 先到者为准：快源 256KB 几十毫秒就够，
    慢源最多陪它 2 秒 —— 这一条决策直接决定整轮探测的总耗时能不能压进 CI 的 15 分钟。
    中途 socket 超时不算失败，把已经收到的那部分按实际耗时算速率就是答案。
    """
    t0 = time.time()
    n = 0
    try:
        r = _sess().get(url, timeout=(CONNECT_TIMEOUT, SEG_READ_TIMEOUT), stream=True,
                        allow_redirects=True, verify=False)
        try:
            if r.status_code != 200:
                return 0
            for chunk in r.iter_content(16384):
                if not chunk:
                    continue
                n += len(chunk)
                el = time.time() - t0
                if n >= SEG_BYTES or el >= SEG_SECONDS:
                    break
        finally:
            r.close()
    except Exception:  # noqa: BLE001
        pass  # 读超时/断连：按已收字节算，不额外惩罚
    el = max(time.time() - t0, 0.05)
    return int(n / el / 1024.0)


def probe(url):
    """一轮探测，返回 (ok, ttfb_ms, status, kbps, why)。

    ok 同时要求：200 + 前 4096 字节含 #EXTM3U + TTFB<=3s + 分片吞吐>=40KB/s。
    这四项缺一项都会在电视上表现成「点开黑屏/幻灯片」，所以缺一项就不留。
    why 是淘汰原因：ok 时 "ok"，否则 http / ttfb / kbps / seg。
    带上原因是为了能统计「到底是被哪条门槛筛掉的」—— 只有通过率一个数字，
    下次不知道该放松 TTFB 还是该放松码率。
    """
    t0 = time.time()
    ttfb = -1
    code = 0
    buf = bytearray()
    base = url
    try:
        r = _sess().get(url, timeout=(CONNECT_TIMEOUT, READ_TIMEOUT), stream=True,
                        allow_redirects=True, verify=False)
        code = r.status_code
        try:
            if code == 200:
                for chunk in r.iter_content(1024):
                    if not chunk:
                        continue
                    if ttfb < 0:
                        ttfb = int((time.time() - t0) * 1000)  # 首块数据到达
                    buf += chunk
                    if len(buf) >= PLAYLIST_MAX:
                        break
                    if time.time() - t0 > PLAYLIST_DEADLINE:
                        break
                    # 媒体列表：找到「#EXTINF + 下一行地址」就没必要再读了（顺带省掉
                    # 等服务器断连的那几秒 —— 上千条 URL 累起来就是几分钟）。
                    # 但只在这一行确实读完整（有换行，或已经攒够 8KB）时才停：
                    # 分块边界落在 URI 中间会解析出半截地址，测出来的「不可播」是假的。
                    if b"#EXT-X-STREAM-INF" not in buf and b"#EXTINF" in buf:
                        if buf.endswith(b"\n") or len(buf) >= 8192:
                            if _pick_segment(bytes(buf).decode("utf-8", "ignore")):
                                break
        finally:
            base = r.url or url           # 跟随重定向后的真实地址，相对分片按它解析
            r.close()
    except Exception:  # noqa: BLE001
        return False, -1, 0, 0, "http"
    if ttfb < 0:
        ttfb = int((time.time() - t0) * 1000)

    head = bytes(buf[:FIRST_BYTES])
    if code != 200 or b"#EXTM3U" not in head:
        return False, ttfb, code, 0, "http"
    if ttfb > TTFB_MAX_MS:
        return False, ttfb, code, 0, "ttfb"

    txt = bytes(buf).decode("utf-8", "ignore")
    if "#EXT-X-STREAM-INF" in txt.upper():
        vurl = _pick_variant(txt, base)
        if not vurl or not url_ok(vurl):
            return False, ttfb, code, 0, "seg"
        sub, sub_base = _fetch_playlist(vurl)
        if sub is None:
            return False, ttfb, code, 0, "seg"
        txt, base = sub, sub_base

    seg = _pick_segment(txt)
    if not seg:
        return False, ttfb, code, 0, "seg"
    seg_url = urljoin(base, seg)
    if not url_ok(seg_url):
        return False, ttfb, code, 0, "seg"
    kbps = _throughput(seg_url)
    if kbps < MIN_KBPS:
        return False, ttfb, code, kbps, "kbps"
    return True, ttfb, code, kbps, "ok"


def probe_all(urls, workers, tag="1"):
    """并发探一轮，打进度。返回 url -> (ok, ttfb, status, kbps, why)。"""
    res = {}
    done = 0
    alive = 0
    t0 = time.time()
    n = len(urls)
    if not n:
        print("[live] round%s %d urls, skipped" % (tag, n))
        return res, 0, 0.0
    with futures.ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(probe, u): u for u in urls}
        for f in futures.as_completed(futs):
            u = futs[f]
            try:
                ok, ttfb, code, kbps, why = f.result()
            except Exception:  # noqa: BLE001
                ok, ttfb, code, kbps, why = False, -1, 0, 0, "http"
            res[u] = (ok, ttfb, code, kbps, why)
            done += 1
            if ok:
                alive += 1
            if done % 200 == 0 or done == n:
                el = time.time() - t0
                # 进度里带 ETA：CI 只有 15 分钟额度，看日志要能一眼判断要不要砍
                eta = (el / done * (n - done)) if done else 0
                print("[live] round%s %d/%d alive=%d (%.0fs, eta %.0fs)"
                      % (tag, done, n, alive, el, eta))
    cost = time.time() - t0
    print("[live] round%s done %d/%d alive=%d in %.1fs (%.0f urls/s)"
          % (tag, alive, n, alive, cost, n / max(cost, 0.1)))
    return res, alive, cost


# ---------------------------------------------------------------- 纯 Python PNG

PNG_SIG = b"\x89PNG\r\n\x1a\n"


def _paeth(a, b, c):
    p = a + b - c
    pa = abs(p - a)
    pb = abs(p - b)
    pc = abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def png_decode(raw):
    """解 PNG -> (w, h, bytearray RGBA)；不支持就返回 None（调用方跳过留空）。

    只覆盖 iptv-org 台标里真正出现的形态：非隔行、8 位、颜色类型 0/2/3/4/6。
    16 位与隔行图直接放弃 —— 为一堆用不上的分支写代码，只会让这里更难维护。
    """
    if not raw or len(raw) < 32 or not raw.startswith(PNG_SIG):
        return None
    i = 8
    w = h = depth = ctype = interlace = -1
    plte = b""
    trns = b""
    idat = bytearray()
    n = len(raw)
    while i + 8 <= n:
        ln = struct.unpack(">I", raw[i:i + 4])[0]
        typ = raw[i + 4:i + 8]
        data = raw[i + 8:i + 8 + ln]
        i += 12 + ln
        if typ == b"IHDR":
            if len(data) < 13:
                return None
            w, h, depth, ctype, comp, filt, interlace = struct.unpack(">IIBBBBB", data[:13])
            if comp or filt:
                return None
        elif typ == b"PLTE":
            plte = data
        elif typ == b"tRNS":
            trns = data
        elif typ == b"IDAT":
            idat += data
        elif typ == b"IEND":
            break
    if w <= 0 or h <= 0 or w * h > 2000000 or interlace != 0 or depth != 8:
        return None
    nch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(ctype)
    if nch is None or not idat:
        return None
    if ctype == 3 and not plte:
        return None
    try:
        buf = zlib.decompress(bytes(idat))
    except Exception:  # noqa: BLE001
        return None
    stride = w * nch
    if len(buf) < (stride + 1) * h:
        return None

    # 反过滤：逐行按 filter type 还原成原始扫描线
    rows = []
    prev = bytearray(stride)
    pos = 0
    for _y in range(h):
        ft = buf[pos]
        pos += 1
        line = bytearray(buf[pos:pos + stride])
        pos += stride
        if ft == 1:
            for x in range(nch, stride):
                line[x] = (line[x] + line[x - nch]) & 0xFF
        elif ft == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 0xFF
        elif ft == 3:
            for x in range(stride):
                a = line[x - nch] if x >= nch else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 0xFF
        elif ft == 4:
            for x in range(stride):
                a = line[x - nch] if x >= nch else 0
                c = prev[x - nch] if x >= nch else 0
                line[x] = (line[x] + _paeth(a, prev[x], c)) & 0xFF
        elif ft != 0:
            return None
        rows.append(line)
        prev = line

    # 统一摊成 RGBA：下游缩放只认一种像素格式，分支越少越不容易错
    px = bytearray(w * h * 4)
    o = 0
    for line in rows:
        if ctype == 6:
            px[o:o + stride] = line
            o += stride
        elif ctype == 2:
            for x in range(0, stride, 3):
                px[o] = line[x]
                px[o + 1] = line[x + 1]
                px[o + 2] = line[x + 2]
                px[o + 3] = 255
                o += 4
        elif ctype == 0:
            for x in range(w):
                v = line[x]
                px[o] = v
                px[o + 1] = v
                px[o + 2] = v
                px[o + 3] = 255
                o += 4
        elif ctype == 4:
            for x in range(w):
                v = line[x * 2]
                px[o] = v
                px[o + 1] = v
                px[o + 2] = v
                px[o + 3] = line[x * 2 + 1]
                o += 4
        else:  # ctype == 3
            for x in range(w):
                k = line[x]
                j = k * 3
                if j + 2 < len(plte):
                    px[o] = plte[j]
                    px[o + 1] = plte[j + 1]
                    px[o + 2] = plte[j + 2]
                px[o + 3] = trns[k] if k < len(trns) else 255
                o += 4
    return w, h, px


def _logo_usable(w, h, px):
    """挡住「死链占位图」：整张不透明的暗色块，在深色卡片上就是一块黑框。

    iptv-org 里确实有这种数据 —— CCTV-9 / 浙江卫视 指向 i.imgur.com 的图，
    imgur 把过期的图换成一张深灰底「image does not exist」占位图（161x81、
    两色、平均亮度 51、完全不透明）。这类图放进 App 就是把老板说的「黑框」
    原样搬回来，所以判死：平均亮度 <90 且透明占比 <5% 的一律不要。
    正常的深色台标（CETV-1 亮度 61）带 45% 透明边，不会被这条挡住。
    """
    n = w * h
    if n <= 0:
        return False
    step = max(1, n // 4000)
    opaque = 0
    lum = 0
    seen = 0
    for i in range(0, n, step):
        o = i * 4
        a = px[o + 3]
        seen += 1
        if a > 200:
            opaque += 1
            lum += 0.299 * px[o] + 0.587 * px[o + 1] + 0.114 * px[o + 2]
    if not seen:
        return False
    if opaque * 100 // seen < 95:
        return True                      # 有透明边，正常台标
    return (lum / max(1, opaque)) >= 90


def _fit(w, h, px, tw, th):
    """按宽高比缩到 tw x th 里居中，空白留全透明。

    用面积平均而不是最近邻：台标里的文字笔画在缩小时最容易断，最近邻会把
    「CCTV-5」缩成锯齿块。每轴最多采 8 个样点，512x229 的图单张几十毫秒。
    """
    if w <= 0 or h <= 0:
        return None
    scale = min(tw / float(w), th / float(h))
    dw = max(1, min(tw, int(round(w * scale))))
    dh = max(1, min(th, int(round(h * scale))))
    ox = (tw - dw) // 2
    oy = (th - dh) // 2
    out = bytearray(tw * th * 4)
    for dy in range(dh):
        y0 = dy * h // dh
        y1 = max(y0 + 1, (dy + 1) * h // dh)
        stepy = max(1, (y1 - y0) // 8)
        orow = ((oy + dy) * tw + ox) * 4
        for dx in range(dw):
            x0 = dx * w // dw
            x1 = max(x0 + 1, (dx + 1) * w // dw)
            stepx = max(1, (x1 - x0) // 8)
            sa = sr = sg = sb = 0
            cnt = 0
            for yy in range(y0, y1, stepy):
                base = yy * w
                for xx in range(x0, x1, stepx):
                    o = (base + xx) * 4
                    a = px[o + 3]
                    if a:
                        sa += a
                        sr += px[o] * a
                        sg += px[o + 1] * a
                        sb += px[o + 2] * a
                    cnt += 1
            if sa and cnt:
                o = orow + dx * 4
                out[o] = sr // sa
                out[o + 1] = sg // sa
                out[o + 2] = sb // sa
                out[o + 3] = sa // cnt
    return out


def png_encode(w, h, px):
    """RGBA -> PNG 字节。filter 全 0 + zlib -9：调色板优化省不下几 KB，不值得写。"""
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += px[y * w * 4:(y + 1) * w * 4]

    def chunk(typ, data):
        return (struct.pack(">I", len(data)) + typ + data +
                struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF))

    return (PNG_SIG +
            chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(bytes(raw), 9)) +
            chunk(b"IEND", b""))


# ---------------------------------------------------------------- 台标

def logo_key(s):
    """频道名归一化键：大小写、全角、空格、`-`/`_`/`.`/`+` 都不参与比较。

    这样 `CCTV-1` / `CCTV1` / `CCTV 1` / `ＣＣＴＶ－１` 会落到同一个键上 ——
    源站和 iptv-org 对同一个台的写法本来就不统一，不归一化等于放弃匹配。
    """
    s = unicodedata.normalize("NFKC", s or "").upper()
    for ch in " \t\u3000-_.,+/" + _DOTS:
        s = s.replace(ch, "")
    return s


def fetch_json(url, timeout=30):
    try:
        r = requests.get(url, headers={"User-Agent": UI_UA}, timeout=timeout, verify=False)
        if r.status_code != 200:
            return None
        return r.json()
    except Exception:  # noqa: BLE001
        return None


def build_logo_index(channels):
    """建两张表：正名/别名 -> 频道项，以及「去掉 CCTV/CGTN/CETV 前缀」-> 频道项。

    第二张表是用来救央视那批中文名的：源站写「央视台球」，iptv-org 的 alt_names 是
    `CCTV-Billiards`，去前缀后剩 `BILLIARDS`，拼不回中文，但反过来 —— 中文名归一化后
    是 `央视台球`，可以从 alt_names 里 `央视台球` 这个别名直接命中。所以：
    alt_names 里的中文别名本身就进正表，前缀表只为 `5+`/`17` 这类纯编号兜底。
    """
    idx = {}
    npx = {}
    pri = {c: i for i, c in enumerate(COUNTRY_PRIO)}
    rows = [c for c in (channels or []) if isinstance(c, dict) and c.get("country") in pri]
    # 按国家优先级排序后 setdefault：CN 先占位，HK/TW/MO 抢不走正名
    rows.sort(key=lambda c: pri.get(c.get("country"), 99))
    for c in rows:
        cid = c.get("id")
        if not cid:
            continue
        k = logo_key(c.get("name"))
        if k:
            idx.setdefault(k, c)
        for a in (c.get("alt_names") or []):
            k = logo_key(a)
            if not k:
                continue
            idx.setdefault(k, c)
            for p in ("CCTV", "CGTN", "CETV"):
                if k.startswith(p) and len(k) > len(p):
                    npx.setdefault(k[len(p):], c)
    return idx, npx


def pick_logo(urls_items):
    """从该频道所有 logo 里按偏好排序：in_use 优先，其次 PNG，最后看尺寸。"""
    def rank(x):
        fmt = (x.get("format") or "").upper()
        return (1 if x.get("in_use") else 0,
                1 if fmt == "PNG" else 0,
                int(x.get("width") or 0))
    return sorted(urls_items, key=rank, reverse=True)


def logo_asset_name(name):
    """文件名 = 频道名 MD5 前 12 位。用名字而不是序号，每天重跑不会产生新文件。"""
    h = hashlib.md5(name.encode("utf-8")).hexdigest()
    return "logo/%s.png" % h[:12]


def _download_logo(url):
    """下一张图（限 3MB），返回原始字节或 None。"""
    try:
        r = requests.get(url, headers={"User-Agent": UI_UA}, timeout=(6, 10),
                         verify=False, stream=True)
        try:
            if r.status_code != 200:
                return None
            buf = bytearray()
            for chunk in r.iter_content(16384):
                if not chunk:
                    continue
                buf += chunk
                if len(buf) > LOGO_MAX_BYTES:
                    return None
            return bytes(buf)
        finally:
            r.close()
    except Exception:  # noqa: BLE001
        return None


def logo_stage(names, log):
    """抓台标落盘。返回 name -> "asset:logo/<hash>.png"（仅成功项）。

    为什么是 asset: 前缀而不是网络地址
        build.sh 的 aapt2 link 带 -A assets，logo/ 会进 APK；App 侧
        Ui.assetBitmap() 就是 substr 掉 "asset:" 再 getAssets().open()。
        台标是 96x54 的小图，本地读比走网络快得多，也不吃用户带宽。

    失败一律跳过留 ""：App 端有首字色块兜底，而 "asset:" 后面空路径会走异常分支。
    """
    out = {}
    if not names:
        return out
    chans = fetch_json(LOGO_API_CH)
    logos = fetch_json(LOGO_API_LG)
    if not chans or not logos:
        log("logo api FAIL, skip logo stage")
        return out
    by_ch = {}
    for x in logos:
        cid = x.get("channel")
        if cid:
            by_ch.setdefault(cid, []).append(x)
    idx, npx = build_logo_index(chans)
    log("logo api ok channels=%d logos=%d cn_index=%d" % (len(chans), len(logos), len(idx)))

    matched = []
    by_id = {}
    for c in chans:
        cid = c.get("id")
        if cid:
            by_id[cid] = c
    manual_hit = 0
    for nm in names:
        k = logo_key(nm)
        if not k:
            continue
        # 先走手写映射：省级卫视的 alt_names 是空的，自动匹配根本够不着
        c = None
        mid = MANUAL_LOGO_IDS.get(nm)
        if mid:
            c = by_id.get(mid)
            if c:
                manual_hit += 1
        if not c:
            c = idx.get(k) or npx.get(k)
        if not c:
            continue
        items = pick_logo(by_ch.get(c["id"]) or [])
        if items:
            matched.append((nm, c["id"], items))
    log("logo matched %d/%d (%.1f%%), manual map hit %d"
        % (len(matched), len(names), 100.0 * len(matched) / max(1, len(names)), manual_hit))
    if not matched:
        return out

    if not os.path.isdir(LOGO_DIR):
        os.makedirs(LOGO_DIR)
    t0 = time.time()
    done = 0
    for nm, cid, items in matched:
        if time.time() - t0 > LOGO_DEADLINE:
            log("logo deadline hit after %d, rest skipped" % done)
            break
        done += 1
        rel = logo_asset_name(nm)
        dst = os.path.join(ASSETS, rel.replace("/", os.sep))
        for it in items[:4]:  # 同一个台最多试 4 个候选源，别在死链上耗光预算
            u = it.get("url") or ""
            if not u.startswith("http"):
                continue
            raw = _download_logo(u)
            if not raw:
                continue
            dec = png_decode(raw)
            if not dec:
                continue  # jpg/webp/svg：宁可没有，也不凑一张发虚的
            w, h, px = dec
            if not _logo_usable(w, h, px):
                continue  # 死链占位图（暗色不透明块），放上去就是黑框
            small = _fit(w, h, px, LOGO_W, LOGO_H)
            if not small:
                continue
            try:
                with open(dst, "wb") as f:
                    f.write(png_encode(LOGO_W, LOGO_H, small))
            except Exception:  # noqa: BLE001
                break
            out[nm] = LOGO_PREFIX + rel
            break
        if done % 10 == 0 or done == len(matched):
            log("logo %d/%d ok=%d (%.0fs)" % (done, len(matched), len(out),
                                              time.time() - t0))
    log("logo stage done ok=%d in %.1fs" % (len(out), time.time() - t0))
    return out


def clean_orphans(used, log):
    """删掉 assets/logo 里没被本次 live.json 引用的 PNG。

    不清理的话每天重跑（哪怕只改一个台名）都会留下旧文件，仓库体积会一直涨。
    used 少于 LOGO_MIN_KEEP 时跳过：一次 API 抽风导致的「零命中」不该把图删光。
    """
    if len(used) < LOGO_MIN_KEEP:
        log("logo orphan cleanup skipped (only %d refs)" % len(used))
        return 0, 0
    if not os.path.isdir(LOGO_DIR):
        return 0, 0
    removed = 0
    freed = 0
    for fn in os.listdir(LOGO_DIR):
        if not fn.lower().endswith(".png"):
            continue
        if fn in used:
            continue
        p = os.path.join(LOGO_DIR, fn)
        try:
            sz = os.path.getsize(p)
            os.remove(p)
            removed += 1
            freed += sz
        except Exception:  # noqa: BLE001
            pass
    log("logo orphan cleanup removed=%d freed=%dB" % (removed, freed))
    return removed, freed


def dir_stat(path):
    n = 0
    total = 0
    if os.path.isdir(path):
        for fn in os.listdir(path):
            p = os.path.join(path, fn)
            if os.path.isfile(p):
                n += 1
                try:
                    total += os.path.getsize(p)
                except Exception:  # noqa: BLE001
                    pass
    return n, total


# ---------------------------------------------------------------- 组装 / 输出

def build(pairs, probed, logos=None):
    """pairs: gid -> [(name, url)]；probed: url -> (ok, ttfb, code)；logos: name -> asset。"""
    logos = logos or {}
    groups_out = []
    total_chs = 0
    stat = []
    for gid, gname, _keys in GROUPS:
        buckets = {}
        for name, url in pairs.get(gid, ()):
            p = probed.get(url)
            if not p or not p[0]:
                continue
            buckets.setdefault(name, []).append((p[1], url))
        chs = []
        for name, lst in buckets.items():
            lst.sort(key=lambda x: (x[0], x[1]))  # 两轮 TTFB 均值，越小越靠前
            urls = []
            for _ttfb, u in lst:
                if u in urls:
                    continue
                urls.append(u)
                if len(urls) >= MAX_URLS_PER_CH:
                    break
            if not urls:
                continue
            chs.append((lst[0][0], {"name": name, "logo": logos.get(name, ""),
                                    "urls": urls}))
        if not chs:
            continue
        chs.sort(key=lambda x: (x[0], x[1]["name"]))
        groups_out.append({"id": gid, "name": gname,
                           "chs": [c[1] for c in chs]})
        total_chs += len(chs)
        stat.append((gid, len(chs)))
    return groups_out, total_chs, stat


def _logo_basename(lg):
    """从 logo 值里取本地文件名；不是 asset: 形式就当没有。"""
    if lg.startswith(LOGO_PREFIX):
        return lg[len(LOGO_PREFIX):].rsplit("/", 1)[-1]
    return ""


def carry_old_logos(old_doc, groups_out, log):
    """本次没抓到、但上一版有台标且文件还在的台，沿用旧值。

    每天全量重跑时网络总有几次抽风；如果每次都把已有台标清成 ""，用户看到的就是
    台标忽有忽无。只沿用**文件确实存在**的旧值，避免线上引用一个查不到的 asset。
    """
    old = {}
    try:
        for g in (old_doc or {}).get("groups") or []:
            for c in g.get("chs") or []:
                lg = c.get("logo") or ""
                if _logo_basename(lg):
                    old[c.get("name")] = lg
    except Exception:  # noqa: BLE001
        return 0
    kept = 0
    for g in groups_out:
        for c in g["chs"]:
            if c.get("logo"):
                continue
            lg = old.get(c.get("name"))
            fn = _logo_basename(lg or "")
            if not fn:
                continue
            if os.path.isfile(os.path.join(LOGO_DIR, fn)):
                c["logo"] = LOGO_PREFIX + "logo/" + fn
                kept += 1
    if kept:
        log("logo carried over %d from previous live.json" % kept)
    return kept


def write_json(path, groups_out, total_chs):
    doc = {
        "version": 1,
        "updated": datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"),
        "total": total_chs,
        "groups": groups_out,
    }
    txt = json.dumps(doc, ensure_ascii=False, indent=1)
    d = os.path.dirname(path)
    if d and not os.path.isdir(d):
        os.makedirs(d)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(txt)
        f.write("\n")
    return os.path.getsize(path)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0, help="only probe first N urls (debug)")
    ap.add_argument("--out", default="", help="output json path")
    ap.add_argument("--workers", type=int, default=WORKERS)
    args = ap.parse_args(argv)

    t_start = time.time()
    # Windows GBK 控制台：本项目路径和频道名都是中文，直接 print 会 UnicodeEncodeError
    # 把整个巡检打断。这里把 stdout 锁成 ASCII + replace，中文一律退化成 '?'。
    try:
        sys.stdout.reconfigure(encoding="ascii", errors="replace")
    except Exception:  # noqa: BLE001
        pass

    def log(msg):
        print("[live] %s" % msg)

    raw = []
    for i, src in enumerate((SRC1, SRC2), 1):
        text = fetch_text(src)
        if text is None:
            log("source%d FAIL (skipped)" % i)
            continue
        items = parse_text(text)
        gs = sorted(set(g for g, _n, _u in items))
        log("source%d ok lines=%d groups=%d" % (i, len(items), len(gs)))
        raw.extend(items)

    if not raw:
        log("no source reachable, keep old file")
        return 0

    # 归组 + URL 过滤 + 全局去重（同一条 URL 只测一次）
    pairs = {}
    seen = set()
    urls = []
    drop_genre = drop_ipv6 = drop_bad = 0
    for genre, name, url in raw:
        if "更新时间" in genre:
            drop_genre += 1
            continue
        if not url_ok(url):
            if "[" in url.split("//", 1)[-1].split("/", 1)[0]:
                drop_ipv6 += 1
            else:
                drop_bad += 1
            continue
        gid = map_genre(genre)
        pairs.setdefault(gid, []).append((name, url))
        if url not in seen:
            seen.add(url)
            urls.append(url)

    log("kept=%d uniq_url=%d (drop genre=%d ipv6=%d bad=%d)"
        % (len(raw) - drop_genre - drop_ipv6 - drop_bad, len(urls),
           drop_genre, drop_ipv6, drop_bad))

    debug = bool(args.limit and args.limit > 0)
    todo = urls[:args.limit] if debug else urls
    workers = max(1, args.workers)

    log("probe start urls=%d workers=%d (round1 + round2, segment<=%.1fs/%dKB)"
        % (len(todo), workers, SEG_SECONDS, SEG_BYTES // 1024))
    p1, alive1, cost1 = probe_all(todo, workers, "1")
    pass1 = [u for u in todo if p1.get(u, (False,))[0]]
    p2, alive2, cost2 = probe_all(pass1, workers, "2")

    probed = {}
    for u in todo:
        r1 = p1.get(u)
        if not r1 or not r1[0]:
            continue
        r2 = p2.get(u)
        if not r2 or not r2[0]:
            continue
        # 排序键用两轮的 TTFB 均值：单轮 30ms、另一轮 900ms 的源，用户体感就是后者
        probed[u] = (True, (r1[1] + r2[1]) // 2, r2[2])

    n_pass = len(probed)
    log("probe rounds: r1=%d/%d(%.0f%%) in %.0fs, r2=%d/%d(%.0f%%) in %.0fs"
        % (alive1, len(todo), 100.0 * alive1 / max(1, len(todo)), cost1,
           alive2, len(pass1), 100.0 * alive2 / max(1, len(pass1)), cost2))
    log("probe final pass=%d/%d (%.0f%%), total %.1fs"
        % (n_pass, len(todo), 100.0 * n_pass / max(1, len(todo)),
           cost1 + cost2))
    # 淘汰原因是分项统计的：只看「通过率 40%」没法判断是源烂还是门槛定太狠，
    # 而这个数字正是下次调 MIN_KBPS / TTFB_MAX_MS 的唯一依据。
    def _whys(res):
        c = {}
        for u in res:
            w = res[u][4]
            if w != "ok":
                c[w] = c.get(w, 0) + 1
        return c

    w1 = _whys(p1)
    w2 = _whys(p2)
    log("probe drop round1: http=%d ttfb>%dms=%d kbps<%dKB/s=%d noseg=%d"
        % (w1.get("http", 0), TTFB_MAX_MS, w1.get("ttfb", 0),
           MIN_KBPS, w1.get("kbps", 0), w1.get("seg", 0)))
    log("probe drop round2 (retest failed): http=%d ttfb=%d kbps=%d noseg=%d total=%d"
        % (w2.get("http", 0), w2.get("ttfb", 0), w2.get("kbps", 0),
           w2.get("seg", 0), alive1 - n_pass))

    groups_out, total_chs, stat = build(pairs, probed)

    # 安全阀只管线上那份；调试预览照写，否则 --limit 永远看不到结果。
    if total_chs < MIN_ALIVE_CH and not debug:
        log("too few alive channels (%d), keep old file" % total_chs)
        return 0

    out = args.out
    if not out:
        out = PREVIEW_JSON if debug else OUT_JSON

    old_doc = None
    if not debug:
        try:
            with io.open(OUT_JSON, "r", encoding="utf-8") as f:
                old_doc = json.load(f)
        except Exception:  # noqa: BLE001
            old_doc = None

    if debug:
        log("debug run (--limit), logo stage skipped, assets/live.json untouched")
    else:
        # 台标与源探测是两条独立链路：iptv-org 挂了、PNG 解码遇到怪格式、
        # 磁盘写不进去 —— 任何一种都不该把已经跑完 5 分钟的探测结果一起丢掉。
        try:
            names = [c["name"] for g in groups_out for c in g["chs"]]
            logos = logo_stage(names, log)
            for g in groups_out:
                for c in g["chs"]:
                    c["logo"] = logos.get(c["name"], "")
            carry_old_logos(old_doc, groups_out, log)
        except Exception as e:  # noqa: BLE001
            log("logo stage FAIL (%s), keep empty logos" % type(e).__name__)

    size = write_json(out, groups_out, total_chs)

    if not debug:
        used = set()
        for g in groups_out:
            for c in g["chs"]:
                fn = _logo_basename(c.get("logo") or "")
                if fn:
                    used.add(fn)
        clean_orphans(used, log)

    n_logo = sum(1 for g in groups_out for c in g["chs"] if c.get("logo"))
    n_url = sum(len(c["urls"]) for g in groups_out for c in g["chs"])
    log("groups: " + " ".join("%s=%d" % (g, n) for g, n in stat))
    log("channels=%d urls=%d logos=%d (%.1f%%) bytes=%d -> %s"
        % (total_chs, n_url, n_logo, 100.0 * n_logo / max(1, total_chs), size, out))
    if not debug:
        fn, fb = dir_stat(LOGO_DIR)
        log("assets/logo files=%d bytes=%d" % (fn, fb))
    log("TOTAL %.1fs" % (time.time() - t_start))
    return 0


if __name__ == "__main__":
    sys.exit(main())
