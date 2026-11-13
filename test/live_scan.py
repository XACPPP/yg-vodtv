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

为什么还要真取媒体分片算吞吐，而且必须连续 3 个
    播放列表小到几十 KB，能秒回不代表片子拉得动。很多源列表放在 CDN 上，分片却在一条
    超售的落地机上：列表 200 OK、TTFB 40ms，分片只有 20KB/s，表现就是幻灯片。
    v2.7 只测 1 个分片、门槛 40KB/s（320kbps），这就是「10 个台 7 个卡」的直接原因：
    320kbps 连 480p 都撑不住，而且单个分片正好赶上 CDN 边缘缓存命中时，一条烂源
    能测出 2MB/s。v2.8 改成连续 3 个分片，门槛 220KB/s（约 1.8Mbps：720p 直播普遍
    1.5~2.5Mbps，1080p 低码率档也在这一档），再加一条掉速判据 —— 第 3 片吞吐不得低于
    第 1 片的 45%，专治「首片吃 CDN 缓存飞快、后面回源立刻打回原形」的源，那种源的
    用户体感就是「刚点开还行，然后一直转圈」。绝对码率门槛抓不到它：它每片都能过线，
    但斜率是塌的。这些数字天天变，只用于筛选，不写进 JSON。

为什么必须区分「真直播」和「点播文件冒充」
    三类假源能过掉所有连通性检查，用户看到的就是「卡」或「黑屏」：
      1) 快手/抖音/西瓜的点播 CDN（txmov2.a.kwimgs.com 之类）：把短视频切片拼成
         m3u8，播 3~15 秒就 #EXT-X-ENDLIST 结束，观感等于卡死；
      2) 带 e=<过期时间> 签名的地址（mgtv 那种）：现在能播，几小时后 403，
         第二天早上电视上就是黑屏 —— 所以只收「还能活 6 小时以上」的签名；
      3) 静态 m3u8（无 MEDIA-SEQUENCE 推进 / 带 ENDLIST）：根本没有滚动直播窗口，
         播完就停。
    这三类一律在 probe 里判死，并在 why 字段分类统计，方便看「到底被杀在哪条判据上」。

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
import base64
import concurrent.futures as futures
import datetime
import hashlib
import io
import json
import os
import re
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

# SRC3：境外 IP 镜像站的 URL 模板。SRC1/SRC2 这两份 GitHub 列表里压根没有这批
# 直连镜像，而它们恰恰是 CCTV / 卫视最稳的一批（实测 220~294KB/s、TTFB 0.9~2.5s、
# 两轮复测都过）。缺点是随时可能挂，所以这里只**生成候选**，照样走完整 probe +
# 30 秒后复测，挂掉的下轮自然消失。
SRC3_TEMPLATES = (
    "http://198.204.228.26/live/{code}.m3u8",
    "http://204.12.221.218:8181/3m1080p/{code}.m3u8",
    "http://74.91.26.218:82/live/{code}.m3u8",
    "http://63.141.230.178:82/gslb/zbdq5.m3u8?id={code}",
)
# (源分组关键字, 频道名, 镜像 code)。分组关键字要和 GROUPS 里的 keys 对得上，
# 这样镜像候选能和 SRC1/SRC2 的同名台并进同一个桶，换源才有得换。
# sxwshd 在三个站上都出现，含义可能是山西也可能是陕西；没有额外证据，先归山西。
MIRROR_CODES = (
    ("央视频道", "CCTV-1", "cctv1"),
    ("央视频道", "CCTV-2", "cctv2"),
    ("央视频道", "CCTV-14", "cctv14"),
    ("央视频道", "CCTV-4K", "cctv4k"),
    ("央视频道", "CCTV-8K", "cctv8k"),
    ("卫视频道", "湖南卫视", "hnwshd"),
    ("卫视频道", "江苏卫视", "jswshd"),
    ("卫视频道", "东方卫视", "dfwshd"),
    ("卫视频道", "北京卫视", "bjwshd"),
    ("卫视频道", "广东卫视", "gdwshd"),
    ("卫视频道", "河北卫视", "hbwshd"),
    ("卫视频道", "天津卫视", "tjwshd"),
    ("卫视频道", "山西卫视", "sxwshd"),
    ("卫视频道", "云南卫视", "ynwshd"),
    ("卫视频道", "深圳卫视", "szwshd"),
    ("卫视频道", "安徽卫视", "ahwshd"),
    ("卫视频道", "广西卫视", "gxwshd"),
    ("卫视频道", "贵州卫视", "gzwshd"),
    ("卫视频道", "江西卫视", "jxwshd"),
    ("卫视频道", "甘肃卫视", "gswshd"),
    ("卫视频道", "四川卫视", "scwshd"),
    ("体育频道", "天元围棋", "gztywq"),
)
# 镜像站主机名：只用来统计「SRC3 贡献了多少条」，不参与判活（判活一律靠 probe）。
MIRROR_HOSTS = ("198.204.228.26", "204.12.221.218", "74.91.26.218", "63.141.230.178")

UI_UA = ("Mozilla/5.0 (Linux; Android 5.1.1; VIDAA TV) AppleWebKit/537.36 "
         "(KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36")
CONNECT_TIMEOUT = 4
READ_TIMEOUT = 6
FIRST_BYTES = 4096
# 16 而不是 24：一轮里每条源要连续下 3 个分片，并发太高会把自己的出口带宽吃满，
# 测出来的是「本地抢带宽」而不是「源站快不快」，好源会被自己人挤死。
WORKERS = 16
# 筛选变严后单台通过率会掉，但事故是「留下来在电视上卡」：宁可多留两条兜底。
MAX_URLS_PER_CH = 5
MIN_ALIVE_CH = 20
GENRE_SUFFIX = ",#genre#"
# 零源台的排序哨兵：比任何真实 TTFB(ms) 都大，保证它们沉到本组末尾，
# 不会把真能播的台挤出 MAX_URLS_PER_CH 的视野之外。
_EMPTY_TTFB = 1 << 30
# 源列表里出现过、这次一条源都没过的台，是否也要保名（urls 留空数组）。
# 打开（v2.8 上一轮的错误做法）：源站里出现过的台名全部保名。2026-11-13 实测
#   708 台里 532 台是空壳，抽屉里点谁谁「没有可用的线路」，比少几台更难受。
# 关闭（当前）：只保住「上一版 live.json 里已有」的台名。湖南卫视这类老台即使
#   这次全灭也不会消失（keep 列表兜底，urls 留空），但源站里新冒出来又全灭的
#   台不进表 —— 空壳台从 532 台降到 183 台，且全是用户昨天真看过的台。
KEEP_SOURCE_ONLY_CHS = False

# ---- 质量门槛（治「特别卡」）----
TTFB_MAX_MS = 3000      # 首块数据超过 3s，用户按下遥控器就觉得电视坏了
# 约 1.8Mbps。这是「720p 直播能流畅放」的下限：720p 普遍 1.5~2.5Mbps，
# 1080p 的低码率档也在 1.8~2.5Mbps。旧值 40KB/s(320kbps) 才是「10 个台 7 个卡」
# 的直接原因；但门槛也不能往 4.8Mbps(600KB/s) 抬 —— 那会把大量其实能流畅播的
# 720p 源和整个央视组一起判死，属于判据太狠而不是源烂。
MIN_KBPS = 220
# 连续下载 3 个分片，每个都要过 MIN_KBPS。只测 1 个分片时，正好赶上 CDN 边缘缓存
# 命中就能把一条烂源测成 2MB/s —— 第 2、3 个分片才暴露真实吞吐。
MIN_STABLE_SEGMENTS = 3
# 掉速容忍度：第 3 片吞吐不能低于第 1 片的 45%。这是「卡」的真正成因 ——
# 首片走 CDN 缓存飞快、后续回源立刻打回原形，用户体感就是「刚点开还行，然后一直转圈」。
# 只看绝对码率抓不到这种源：它每片都能过 220KB/s，但斜率是塌的。
SEG_DECAY_RATIO = 0.45
# 分片观测窗口：>=2.0s 才够高码率分片跑出稳定读数，太短会因为「还没读满就被截断」
# 把好源测低。
SEG_SECONDS = 2.5       # 每个分片的读表上限（从收到第一个字节开始算）
SEG_BYTES = 512 * 1024  # 或者读满 512KB 就收工；样本太小算不出稳定吞吐
SEG_MIN_BYTES = 64 * 1024   # 一个分片连 64KB 都读不出来，就别说 220KB/s 了
SEG_READ_TIMEOUT = 2.0  # 分片读卡住时的 socket 超时，不能沿用 6s，否则 3 倍耗时
PLAYLIST_MAX = 256 * 1024
PLAYLIST_DEADLINE = 6.0
# 两轮复测之间至少隔 30 秒：间隔为 0 时两轮读到的是同一段 CDN 缓存，复测等于没做。
RETEST_GAP_SEC = 30.0
# 等 8 秒再拉一次同一份 m3u8，看 #EXT-X-MEDIA-SEQUENCE 有没有推进 —— 直播窗口是滚动的，
# 静态点播文件的序号永远停在原地（这类源「能起播但黑屏/播完就死」）。
SEQ_WAIT_SEC = 8.0
# 带 e=<unix秒> 时效签名的地址，剩余寿命不足 6 小时就不收：现在能播，半夜 403，
# 用户在电视上看到的就是黑屏，而巡检是每天固定时间跑的，救不了「第二天早上」。
SIG_MIN_LIFE_SEC = 6 * 3600
# 分辨率达到 720p 的源优先排到同一台的前面（电视上肉眼差别最大的就是这个）。
HD_MIN_W, HD_MIN_H = 1280, 720

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


# ---- 片库噪声过滤（源站把点播片库当频道列出来）----
# 命中的名字是「电影/电视剧/综艺单集」，不是频道：用户点进去是点播文件，
# 播几十秒就 ENDLIST 或直接黑屏，跟「卡」是同一种观感。
# 判据顺序刻意设计成「黑名单正则 → 白名单关键词 → MANUAL_LOGO_IDS 豁免」：
# 纯白名单会误杀 Viutv / 卡酷动画 / 南京教科 / 第一财经 / 金鹰纪实 / 湖南经视
# 这些真实频道，所以先让明确是片名的模式优先判死，再让真频道特征词保命。
NOISE_PATTERNS = (
    re.compile(r"\d{4}\s*年?\s*春晚"),          # 1987年春晚 … 2025年春晚
    re.compile(r"第[一二三四五六七八九十\d]+\s*[季部集]"),   # 航拍中国第二季
    re.compile(r"^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}"),      # 2026-08-27 14:06:22
    re.compile(r"^www\.", re.I),                            # www.izbds.com
)
# 真频道特征词：命中任一即保留（大小写不敏感）
CHANNEL_WORDS = (
    "卫视", "CCTV", "CGTN", "CETV", "频道", "剧场", "新闻", "体育", "影视", "纪录",
    "少儿", "卡通", "动画", "动漫", "综合", "电视台", "一套", "二套", "三套", "公共",
    "都市", "生活", "科教", "经济", "财经", "娱乐", "法治", "国际", "电影", "影院",
    "戏曲", "戏剧", "文艺", "资讯", "中文", "凤凰", "翡翠", "无线", "TVB", "亚视",
    "港台", "教育", "党建", "农业", "农村", "乡村", "文旅", "文化", "旅游", "民生",
    "经视", "竞赛", "纪实", "人文", "地理", "足球", "篮球", "网球", "高尔夫", "垂钓",
    "购物", "时尚", "健康", "养生", "汽车", "家居", "美食", "游戏", "音乐", "广播",
    "县", "市", "台", "高清", "4K", "HD", "TV", "睛彩", "风云", "优优", "NewTV",
    "CHC", "上虞", "余姚", "余杭", "伊犁", "长白", "玛纳斯", "教科", "垄上", "钱江",
    "十八", "文娱", "明珠", "梨园", "遂昌", "双辽", "东丰", "自贸", "电视剧", "文体",
    "农民", "公园", "炫动", "外语", "农牧", "洋片", "经典", "J2", "七彩", "村庄",
    "双河", "南国", "奎屯", "哈萨克", "汉语", "靖天", "映画", "天元", "社会", "先锋",
    "环球", "财富", "证券", "围棋", "兵器", "遂宁", "遂川", "广电", "融媒",
    "三峡", "移民", "文教", "生活", "公共", "频道",
)


def is_noise_name(name):
    """台名是不是片库条目（不是频道）。True = 当噪声丢弃。"""
    if name in MANUAL_LOGO_IDS:      # 手写映射表里的都是真频道，无条件保留
        return False
    for p in NOISE_PATTERNS:
        if p.search(name):
            return True
    up = name.upper()
    for w in CHANNEL_WORDS:
        if w.upper() in up:
            return False
    return True


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


def _pick_variant_ex(txt, base):
    """master playlist：取 BANDWIDTH 最大的变体，返回 (绝对地址, (w, h))。

    为什么挑最大的：分片吞吐量是要判断「这条源能不能撑住看电视」，用最低码率变体
    测出来的数字会把一条只能放 240p 的源判成合格。
    顺带把 RESOLUTION 读出来给排序用 —— 同一台有 1080p 和 576p 两条时，
    电视用户先看到的那条应该是 1080p，而不是 TTFB 早 20ms 的 576p。
    """
    best = None
    lines = txt.splitlines()
    for i, ln in enumerate(lines):
        s = ln.strip()
        if not s.upper().startswith("#EXT-X-STREAM-INF"):
            continue
        bw = 0
        res = (0, 0)
        for part in s.split(":", 1)[-1].split(","):
            part = part.strip()
            pu = part.upper()
            if pu.startswith("BANDWIDTH=") and not bw:
                try:
                    bw = int(part.split("=", 1)[1].strip())
                except Exception:  # noqa: BLE001
                    bw = 0
            elif pu.startswith("RESOLUTION="):
                try:
                    wh = part.split("=", 1)[1].strip().lower().split("x")
                    res = (int(wh[0]), int(wh[1]))
                except Exception:  # noqa: BLE001
                    res = (0, 0)
        for j in range(i + 1, len(lines)):
            nxt = lines[j].strip()
            if not nxt or nxt.startswith("#"):
                continue
            if best is None or bw > best[0]:
                best = (bw, nxt, res)
            break
    if not best:
        return "", (0, 0)
    return urljoin(base, best[1]), best[2]


def _pick_variant(txt, base):
    """老签名的薄壳（只要地址）。"""
    return _pick_variant_ex(txt, base)[0]


def _segments(txt, limit=0):
    """媒体 playlist：#EXTINF 后面那一行地址，按出现顺序返回（最多 limit 条）。

    没有 #EXTINF 的裸地址列表也认（部分自制源就这么写）。
    要连续测 3 个分片，所以这里必须返回列表而不是第一条 —— 只测第一条时，
    正好命中 CDN 边缘缓存的那一条会被测成 2MB/s，用户第二天就投诉卡。
    """
    out = []
    lines = txt.splitlines()
    for i, ln in enumerate(lines):
        if not ln.strip().upper().startswith("#EXTINF"):
            continue
        for j in range(i + 1, len(lines)):
            s = lines[j].strip()
            if not s or s.startswith("#"):
                continue
            if s not in out:
                out.append(s)
            break
        if limit and len(out) >= limit:
            return out
    if out:
        return out
    for ln in lines:
        s = ln.strip()
        if s and not s.startswith("#"):
            out.append(s)
            if limit and len(out) >= limit:
                break
    return out


def _pick_segment(txt):
    """媒体 playlist：取第一个分片地址（老的薄壳，保 probe 里的早停逻辑可用）。"""
    segs = _segments(txt, 1)
    return segs[0] if segs else ""


_PL_SEQ_RE = re.compile(r"#EXT-X-MEDIA-SEQUENCE:\s*(\d+)", re.I)
_PL_RES_RE = re.compile(r"RESOLUTION=(\d+)x(\d+)", re.I)


def _pl_meta(txt):
    """读播放列表的关键字段。返回 dict：

    seq       #EXT-X-MEDIA-SEQUENCE 值，没有则 -1
    target    是否有 #EXT-X-TARGETDURATION
    endlist   是否出现 #EXT-X-ENDLIST（点播/已结束，不是直播）
    live      是否出现 #EXT-X-MEDIA-SEQUENCE 或 #EXT-X-TARGETDURATION
    res       从 #EXT-X-STREAM-INF 的 RESOLUTION= 读到的 (w, h)，没有则 (0, 0)
    """
    up = (txt or "").upper()
    m = _PL_SEQ_RE.search(txt or "")
    seq = int(m.group(1)) if m else -1
    res = (0, 0)
    m2 = _PL_RES_RE.search(txt or "")
    if m2:
        try:
            res = (int(m2.group(1)), int(m2.group(2)))
        except Exception:  # noqa: BLE001
            res = (0, 0)
    return {
        "seq": seq,
        "target": "#EXT-X-TARGETDURATION" in up,
        "endlist": "#EXT-X-ENDLIST" in up,
        "live": ("#EXT-X-MEDIA-SEQUENCE" in up) or ("#EXT-X-TARGETDURATION" in up),
        "res": res,
    }


_SIG_RE = re.compile(r"[?&]e=(\d{7,12})(?:[&#/]|$)")


def _sig_expire(url):
    """从 URL 里读 e=<unix秒> 时效签名，返回过期时间戳；没有签名返回 0。

    mgtv 那种 nn_x64/<base64> 形式，e= 藏在 base64 里，要解一层再看。
    位数取 7~12 而不是固定 10：mgtv 这批源的签名是 e=6914604（7 位，1970 年的
    时间戳，早已作废），只按 10 位匹配会整批漏过去，正好漏掉用户投诉的那几条。
    """
    m = _SIG_RE.search(url or "")
    if m:
        try:
            return int(m.group(1))
        except Exception:  # noqa: BLE001
            return 0
    # base64 段里再翻一遍：dWlwPTEyNy4w... 解码后是 uip=127.0.0.1&uid=...&e=6914604&...
    for tok in (url or "").split("/"):
        if len(tok) < 24 or not re.match(r"^[A-Za-z0-9_\-]+={0,2}$", tok):
            continue
        try:
            pad = tok + "=" * (-len(tok) % 4)
            dec = base64.urlsafe_b64decode(pad.encode("ascii")).decode("utf-8", "ignore")
        except Exception:  # noqa: BLE001
            continue
        m = re.search(r"[?&]e=(\d{7,12})(?:&|\b)", dec)
        if m:
            try:
                return int(m.group(1))
            except Exception:  # noqa: BLE001
                return 0
    return 0


# 短视频点播 CDN：内容是真的，但不是直播。播 3~15 秒就 ENDLIST 结束，
# 用户在电视上看到的就是「卡住」或者「黑屏」，而连通性检查全都是 200。
VOD_HOSTS = (
    "kwimgs.com",         # 快手（txmov2.a.kwimgs.com 就是这个）
    "douyinvod.com",      # 抖音
    "ixigua.com",         # 西瓜视频
    "douyinpic.com",
    "bytecdn.cn",
    "zjcdn.com",
)
# 本地/内网地址：只可能出现在自制源里，电视端本来也放不了。
BAD_HOSTS = ("localhost", "127.0.0.1", "0.0.0.0", "[::1]")


def host_of(u):
    """取 URL 的 host（小写，去端口、去 userinfo）。"""
    try:
        h = u.split("//", 1)[1].split("/", 1)[0]
        h = h.rsplit("@", 1)[-1]
        if h.startswith("["):
            return h.split("]", 1)[0].lower() + "]"
        return h.split(":", 1)[0].lower()
    except Exception:  # noqa: BLE001
        return ""


def host_bad(u):
    """返回坏源的类别（"vod"/"local"），好源返回 ""。"""
    h = host_of(u)
    if not h:
        return "local"
    for d in VOD_HOSTS:
        if h == d or h.endswith("." + d):
            return "vod"
    for d in BAD_HOSTS:
        if h == d:
            return "local"
    if h.startswith("192.168.") or h.startswith("10."):
        return "local"
    parts = h.split(".")
    if len(parts) == 4 and parts[0] == "172":
        try:
            if 16 <= int(parts[1]) <= 31:   # 172.16.0.0/12
                return "local"
        except Exception:  # noqa: BLE001
            pass
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
    """取一个媒体分片，返回 (实测 KB/s, 收到的字节数)。

    计时从**第一个字节到达**开始：连接和排队的耗时已经由 TTFB 那条门槛管了，
    这里要量的是「这条源能不能持续供流」，把握手算进速率会把一条 200ms RTT 的
    好源凭空压掉一截。读满 SEG_SECONDS（传输时间）或 SEG_BYTES 先到者为准。
    中途 socket 超时/断连不算崩溃，把已收字节按实际耗时折算 —— 那就是真实速率。
    """
    n = 0
    t_first = 0.0
    try:
        r = _sess().get(url, timeout=(CONNECT_TIMEOUT, SEG_READ_TIMEOUT), stream=True,
                        allow_redirects=True, verify=False)
        try:
            if r.status_code != 200:
                return 0, 0
            for chunk in r.iter_content(16384):
                if not chunk:
                    continue
                if not t_first:
                    t_first = time.time()
                n += len(chunk)
                el = time.time() - t_first
                if n >= SEG_BYTES or el >= SEG_SECONDS:
                    break
        finally:
            r.close()
    except Exception:  # noqa: BLE001
        pass
    if not t_first or n <= 0:
        return 0, n
    el = max(time.time() - t_first, 0.05)
    return int(n / el / 1024.0), n


def _probe_segments(txt, base, n=MIN_STABLE_SEGMENTS):
    """连续取 n 个媒体分片，返回 (ok, min_kbps, why)。

    why ∈ {"ok","seg","kbps","decay"}。三条判据：
      1) 每个分片都要读够 SEG_MIN_BYTES —— 连 64KB 都拉不动就是断流；
      2) 每个分片都要 >= MIN_KBPS —— 绝对码率不够，电视上是幻灯片；
      3) 第 n 片不能低于第 1 片的 SEG_DECAY_RATIO —— 掉速检测。
    为什么要连续取：只测第 1 个分片时，正好赶上 CDN 边缘缓存命中就能测出 2MB/s，
    而第 2、3 个分片走的是回源路径，真实吞吐立刻打回原形 —— 用户体感就是
    「刚点开那几秒还行，然后就一直转圈」。第 3 条专门抓这种源：它的每一片都
    能过绝对码率门槛，但斜率是塌的，只看最小值永远判不出来。
    """
    segs = _segments(txt, n)
    if not segs or len(segs) < n:
        # 只有 1~2 个分片的播放列表本身就是点播残余，不去凑合
        return False, 0, "seg"
    rates = []
    for s in segs[:n]:
        u = urljoin(base, s)
        if not url_ok(u):
            return False, 0, "seg"
        kbps, got = _throughput(u)
        if got < SEG_MIN_BYTES:
            return False, kbps, "seg"
        if kbps < MIN_KBPS:
            return False, kbps, "kbps"
        rates.append(kbps)
    floor = max(1, int(rates[0] * SEG_DECAY_RATIO))
    if rates[-1] < floor:
        return False, min(rates), "decay"
    return True, min(rates), "ok"


def _seq_advances(pl_url, first_txt, first_meta):
    """等 SEQ_WAIT_SEC 秒再拉同一份 m3u8，确认滚动窗口真的在推进。

    直播的 #EXT-X-MEDIA-SEQUENCE 每隔一个 TARGETDURATION 就会 +1；静态点播文件
    永远停在原地。这类源「能起播但黑屏」的成因正是窗口不滚 —— 第一屏之后没有新分片。
    对少数不写 MEDIA-SEQUENCE 的直播源，退化成比第一段分片地址有没有换。
    """
    time.sleep(SEQ_WAIT_SEC)
    txt2, _ = _fetch_playlist(pl_url)
    if txt2 is None:
        return False
    meta2 = _pl_meta(txt2)
    if first_meta["seq"] >= 0:
        return meta2["seq"] > first_meta["seq"]
    a = _segments(first_txt, 1)
    b = _segments(txt2, 1)
    return bool(a and b and a[0] != b[0])


def probe(url):
    """一轮探测，返回 (ok, ttfb_ms, status, kbps, why, res)。

    ok 要求以下全部成立：
      1) 200 + 前 4096 字节含 #EXTM3U + TTFB<=3s（这三条老的，一条不动）
      2) 不是点播：媒体列表里有 #EXT-X-MEDIA-SEQUENCE / TARGETDURATION，且无 ENDLIST
      3) 连续 MIN_STABLE_SEGMENTS 个分片，每个都 >= MIN_KBPS，且第 3 片不低于
         第 1 片的 SEG_DECAY_RATIO（掉速检测：首片吃 CDN 缓存、后面回源塌方）
      4) 等 8s 再拉同一份 m3u8，MEDIA-SEQUENCE 必须推进（真直播窗口）
    why 是淘汰原因，用来分项统计「到底被杀在哪一条」—— 只给通过率一个数字，
    下次不知道该放松码率还是该补源。
    res 是 (w, h)，从 #EXT-X-STREAM-INF 的 RESOLUTION 读，读不到就是 (0,0)，
    仅供同台内排序用，不写进 JSON。
    """
    t0 = time.time()
    ttfb = -1
    code = 0
    buf = bytearray()
    base = url
    seg_ready_at = 0.0
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
                    # 早停要留够余量：#EXT-X-ENDLIST 在文件最末尾，一旦提前断开就
                    # 永远判不出「这是点播」。所以拿到足够分片后再多等 1 秒收尾，
                    # 既不漏 ENDLIST，也不会被挂死的服务器拖满 6 秒。
                    if b"#EXT-X-STREAM-INF" not in buf:
                        t = bytes(buf).decode("utf-8", "ignore")
                        if len(_segments(t, MIN_STABLE_SEGMENTS)) >= MIN_STABLE_SEGMENTS:
                            if not seg_ready_at:
                                seg_ready_at = time.time()
                            if time.time() - seg_ready_at > 1.0:
                                break
        finally:
            base = r.url or url           # 跟随重定向后的真实地址，相对分片按它解析
            r.close()
    except Exception:  # noqa: BLE001
        return False, -1, 0, 0, "http", (0, 0)
    if ttfb < 0:
        ttfb = int((time.time() - t0) * 1000)

    head = bytes(buf[:FIRST_BYTES])
    if code != 200 or b"#EXTM3U" not in head:
        return False, ttfb, code, 0, "http", (0, 0)
    if ttfb > TTFB_MAX_MS:
        return False, ttfb, code, 0, "ttfb", (0, 0)

    txt = bytes(buf).decode("utf-8", "ignore")
    res = (0, 0)
    pl_url = base
    if "#EXT-X-STREAM-INF" in txt.upper():
        vurl, res = _pick_variant_ex(txt, base)
        if not vurl or not url_ok(vurl):
            return False, ttfb, code, 0, "seg", (0, 0)
        sub, sub_base = _fetch_playlist(vurl)
        if sub is None:
            return False, ttfb, code, 0, "seg", res
        txt, pl_url = sub, sub_base

    meta = _pl_meta(txt)
    if not res and meta["res"] != (0, 0):
        res = meta["res"]
    if meta["endlist"]:
        # 点播文件冒充直播：播到结尾就没了，电视上就是「播一会儿卡死」
        return False, ttfb, code, 0, "endlist", res
    if not meta["live"]:
        # 既没有 MEDIA-SEQUENCE 也没有 TARGETDURATION：静态 m3u8，不是直播窗口
        return False, ttfb, code, 0, "static", res

    ok, kbps, why = _probe_segments(txt, pl_url)
    if not ok:
        return False, ttfb, code, kbps, why, res
    if not _seq_advances(pl_url, txt, meta):
        return False, ttfb, code, kbps, "seq", res
    return True, ttfb, code, kbps, "ok", res


def probe_all(urls, workers, tag="1"):
    """并发探一轮，打进度。返回 (res, alive, cost, times)。

    res: url -> (ok, ttfb, status, kbps, why, res)；times: url -> 该条测完的时刻，
    给「两轮必须间隔 30 秒」那条规则算最小等待。
    """
    res = {}
    times = {}
    done = 0
    alive = 0
    t0 = time.time()
    n = len(urls)
    if not n:
        print("[live] round%s %d urls, skipped" % (tag, n))
        return res, 0, 0.0, times
    with futures.ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(probe, u): u for u in urls}
        for f in futures.as_completed(futs):
            u = futs[f]
            try:
                row = f.result()
            except Exception:  # noqa: BLE001
                row = (False, -1, 0, 0, "http", (0, 0))
            res[u] = row
            times[u] = time.time()
            done += 1
            if row[0]:
                alive += 1
            if done % 100 == 0 or done == n:
                el = time.time() - t0
                # 进度里带 ETA：CI 只有 15 分钟额度，看日志要能一眼判断要不要砍
                eta = (el / done * (n - done)) if done else 0
                print("[live] round%s %d/%d alive=%d (%.0fs, eta %.0fs)"
                      % (tag, done, n, alive, el, eta))
    cost = time.time() - t0
    print("[live] round%s done %d/%d alive=%d in %.1fs (%.0f urls/s)"
          % (tag, alive, n, alive, cost, n / max(cost, 0.1)))
    return res, alive, cost, times


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

def _rank_key(item):
    """同一台内排序：先看是不是 >=720p，再看 TTFB，最后按地址稳定 tie-break。

    probed 里带分辨率就是为了这一步 —— 1080p 但 TTFB 800ms 的源，电视上观感
    远好过 480p 但 TTFB 200ms 的源；只按 TTFB 排会把清晰度更高的一条挤到后面。
    """
    ttfb, url, res = item
    hd = 0 if (res[0] >= HD_MIN_W and res[1] >= HD_MIN_H) else 1
    return (hd, ttfb, url)


def build(pairs, probed, logos=None, keep=(), keep_gids=()):
    """pairs: gid -> [(name, url)]；probed: url -> (ok, ttfb, code, kbps, why, res)。

    keep: 上一版 live.json 里的 (gid, name)。这些台**一条源都没过也要保名**，只把
    urls 清空 —— App 端 Store 记着「上次看的第几组第几台」，把台整条删掉，老用户
    点进直播就是一句「没有可用的直播源」；留着空台，播放器会自己跳到下一个能播的台。

    keep_gids: 上一版里有台的分组 id。这些组即使这次一条活源都没过也照旧输出
    （成员可能全是空台），否则用户昨天还在看的「体育」分组会连入口一起消失。
    """
    logos = logos or {}
    keep_by_gid = {}
    for gid, name in keep:
        if gid and name:
            keep_by_gid.setdefault(gid, set()).add(name)
    keep_gids = set(keep_gids or ())
    groups_out = []
    total_chs = 0
    total_live = 0
    stat = []
    for gid, gname, _keys in GROUPS:
        buckets = {}
        # 源站里这次的台名先全部占位：哪怕它每一条源都被判死，台名也要留在
        # live.json 里（urls 写空数组），只把线路清空。
        # 代价是抽屉里会多出一批「点进去提示没线路」的空台 —— 这是拿空间换
        # 「老用户按台名还能找到这个台」，比整台消失友好。
        if KEEP_SOURCE_ONLY_CHS:
            for name, _url in pairs.get(gid, ()):
                buckets.setdefault(name, [])
        for name, url in pairs.get(gid, ()):
            p = probed.get(url)
            if not p or not p[0]:
                continue
            res = p[5] if len(p) > 5 else (0, 0)
            buckets.setdefault(name, []).append((p[1], url, res))
        # 上一版有、这次全灭（或源里彻底没出现）的台：先占位，urls 留空
        for name in keep_by_gid.get(gid, ()):
            buckets.setdefault(name, [])
        chs = []
        for name, lst in buckets.items():
            lst.sort(key=_rank_key)   # 分辨率优先，其次两轮 TTFB 均值
            urls = []
            for _ttfb, u, _res in lst:
                if u in urls:
                    continue
                urls.append(u)
                if len(urls) >= MAX_URLS_PER_CH:
                    break
            # 空源台排到本组末尾：它们只是占位，不该挤掉能看的台
            chs.append((lst[0][0] if lst else _EMPTY_TTFB,
                        {"name": name, "logo": logos.get(name, ""), "urls": urls}))
        if not chs:
            continue
        chs.sort(key=lambda x: (x[0], x[1]["name"]))
        n_live = sum(1 for c in chs if c[1]["urls"])
        # 整组一条活源都没有、上一版也没有这个分组 → 整组不出。凭空多出一个
        # 全是空台的「动画」入口，点谁谁失败，比没这个分组更难受；但上一版有的
        # 分组要留着，不能从用户眼皮底下把入口抽走。
        if n_live == 0 and gid not in keep_gids:
            continue
        groups_out.append({"id": gid, "name": gname,
                           "chs": [c[1] for c in chs]})
        total_chs += len(chs)
        total_live += n_live
        stat.append((gid, len(chs), n_live))
    return groups_out, total_chs, total_live, stat


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

    # SRC3：按模板 × code 生成镜像候选，混进 raw 一起走后面的过滤 + probe。
    # 生成阶段不做任何「这条应该能播」的假设 —— 境外 IP 镜像今天通明天挂，
    # 只有 probe 的两轮复测说了算。
    gen3 = []
    for genre, name, code in MIRROR_CODES:
        for tpl in SRC3_TEMPLATES:
            gen3.append((genre, name, tpl.format(code=code)))
    log("source3 generated=%d (templates=%d x codes=%d)"
        % (len(gen3), len(SRC3_TEMPLATES), len(MIRROR_CODES)))
    raw.extend(gen3)

    # 归组 + URL 过滤 + 全局去重（同一条 URL 只测一次）
    pairs = {}
    seen = set()
    urls = []
    drop_genre = drop_ipv6 = drop_bad = drop_vod = drop_local = drop_sig = drop_noise = 0
    now_ts = int(time.time())
    for genre, name, url in raw:
        if "更新时间" in genre:
            drop_genre += 1
            continue
        # 片库条目（电影名/春晚/综艺单集）不是频道，先按名字判死：
        # 这类名字的源几乎必然是同一条点播文件的镜像，测速也救不回来。
        if is_noise_name(name):
            drop_noise += 1
            continue
        if not url_ok(url):
            if "[" in url.split("//", 1)[-1].split("/", 1)[0]:
                drop_ipv6 += 1
            else:
                drop_bad += 1
            continue
        # 三类「连通性正常但看了就是卡/黑屏」的源，在探测前直接判死：
        # 短视频点播 CDN、本地内网地址、快过期的时效签名。
        hb = host_bad(url)
        if hb == "vod":
            drop_vod += 1
            continue
        if hb == "local":
            drop_local += 1
            continue
        exp = _sig_expire(url)
        if exp and exp < now_ts + SIG_MIN_LIFE_SEC:
            drop_sig += 1
            continue
        gid = map_genre(genre)
        pairs.setdefault(gid, []).append((name, url))
        if url not in seen:
            seen.add(url)
            urls.append(url)

    log("kept=%d uniq_url=%d (drop genre=%d noise=%d ipv6=%d bad=%d vod=%d local=%d sig_expiring=%d)"
        % (len(raw) - drop_genre - drop_noise - drop_ipv6 - drop_bad - drop_vod
           - drop_local - drop_sig,
           len(urls), drop_genre, drop_noise, drop_ipv6, drop_bad, drop_vod,
           drop_local, drop_sig))

    debug = bool(args.limit and args.limit > 0)
    todo = urls[:args.limit] if debug else urls
    workers = max(1, args.workers)

    log("probe start urls=%d workers=%d (round1 + round2 gap>=%.0fs, "
        "segments=%d x <=%.1fs/%dKB, min %.0fKB/s, seq wait %.0fs)"
        % (len(todo), workers, RETEST_GAP_SEC, MIN_STABLE_SEGMENTS, SEG_SECONDS,
           SEG_BYTES // 1024, MIN_KBPS, SEQ_WAIT_SEC))
    p1, alive1, cost1, t1 = probe_all(todo, workers, "1")
    pass1 = [u for u in todo if p1.get(u, (False,))[0]]

    # 两轮之间必须隔够 30 秒：间隔为 0 时第二轮读到的是同一段 CDN 缓存，
    # 「两轮都过」就退化成了「同一秒测了两次」，烂源照样通过。
    # 用第一轮**最晚**测完的那条算等待，第二轮又从头并发开始，
    # 于是每条 URL 自己的间隔都 >= 30s（最早测的反而是间隔最长的）。
    gap = 0.0
    if pass1:
        newest = max(t1.get(u, 0) for u in pass1)
        wait = RETEST_GAP_SEC - (time.time() - newest)
        if wait > 0:
            gap = wait
            log("retest gap: wait %.0fs (every url gets >=%.0fs between rounds)"
                % (wait, RETEST_GAP_SEC))
            time.sleep(wait)
    p2, alive2, cost2, _t2 = probe_all(pass1, workers, "2")

    probed = {}
    for u in todo:
        r1 = p1.get(u)
        if not r1 or not r1[0]:
            continue
        r2 = p2.get(u)
        if not r2 or not r2[0]:
            continue
        # 排序键用两轮的 TTFB 均值：单轮 30ms、另一轮 900ms 的源，用户体感就是后者。
        # 分辨率取第一轮（两轮读的是同一个 master，结果一致）。
        probed[u] = (True, (r1[1] + r2[1]) // 2, r2[2], r2[3], "ok", r1[5])

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

    def _why_line(tag, w, total):
        # 每一类判据单独一行，名字直接写清楚「这是什么坏源」，
        # 否则第二天看日志只知道死了 800 条，不知道死在哪。
        parts = [
            ("http", "非200/非m3u8"),
            ("ttfb", "TTFB>%dms" % TTFB_MAX_MS),
            ("seg", "分片<3个或拉不动"),
            ("kbps", "吞吐<%dKB/s" % MIN_KBPS),
            ("decay", "掉速<首片%d%%" % int(SEG_DECAY_RATIO * 100)),
            ("endlist", "点播ENDLIST"),
            ("static", "无MEDIA-SEQUENCE"),
            ("seq", "序号不推进"),
        ]
        s = " ".join("%s=%d" % (name, w.get(k, 0)) for k, name in parts)
        log("%s (total=%d): %s" % (tag, total, s))

    w1 = _whys(p1)
    w2 = _whys(p2)
    _why_line("probe drop round1", w1, len(todo))
    _why_line("probe drop round2 (retest failed)", w2, alive1 - n_pass)
    log("probe drop TOTAL: http=%d ttfb=%d seg=%d kbps=%d decay=%d endlist=%d static=%d seq=%d"
        " => pass=%d"
        % (w1.get("http", 0) + w2.get("http", 0),
           w1.get("ttfb", 0) + w2.get("ttfb", 0),
           w1.get("seg", 0) + w2.get("seg", 0),
           w1.get("kbps", 0) + w2.get("kbps", 0),
           w1.get("decay", 0) + w2.get("decay", 0),
           w1.get("endlist", 0) + w2.get("endlist", 0),
           w1.get("static", 0) + w2.get("static", 0),
           w1.get("seq", 0) + w2.get("seq", 0), n_pass))

    # 上一版 live.json 要提前读：它既供 build() 保住「这次全灭」的台名，也供
    # carry_old_logos() 沿用旧台标。debug 预览不读，免得 --limit 打出一份残缺表还带上旧台。
    old_doc = None
    if not debug:
        try:
            with io.open(OUT_JSON, "r", encoding="utf-8") as f:
                old_doc = json.load(f)
        except Exception:  # noqa: BLE001
            old_doc = None

    keep = []
    keep_gids = []
    for g in (old_doc or {}).get("groups") or []:
        gid = g.get("id") or ""
        if gid:
            keep_gids.append(gid)
        for c in g.get("chs") or []:
            nm = c.get("name") or ""
            # 上一版里也可能混着「倩女幽魂」「1987年春晚」这种片库条目：它们这次已被
            # 预过滤判死，别再靠 keep 复活成空台。
            if gid and nm and not is_noise_name(nm):
                keep.append((gid, nm))
    if keep:
        log("carry over %d channel names from previous live.json (gids=%d)"
            % (len(keep), len(keep_gids)))

    groups_out, total_chs, total_live, stat = build(pairs, probed, keep=keep,
                                                    keep_gids=keep_gids)

    # SRC3 镜像源到底贡献了多少：分两行看 —— 「pass」是过完两轮探测的，
    # 「in live.json」是最后真排进表的。两个数字差得远说明同台的公共源更好，
    # 镜像被 MAX_URLS_PER_CH 挤掉了；差得少说明这批 IP 镜像就是主力。
    url_name = {}
    for gid, items in pairs.items():
        for nm, u in items:
            url_name.setdefault(u, nm)
    mir_pass_urls = [u for u in probed if host_of(u) in MIRROR_HOSTS]
    mir_pass_chs = set(url_name.get(u, "?") for u in mir_pass_urls)
    mir_out = []
    for g in groups_out:
        for c in g["chs"]:
            for u in c["urls"]:
                if host_of(u) in MIRROR_HOSTS:
                    mir_out.append((c["name"], u))
    log("source3 contribution: pass urls=%d channels=%d" % (len(mir_pass_urls), len(mir_pass_chs)))
    for nm, u in mir_out:
        log("  src3 %s %s" % (nm, u))
    log("source3 in live.json: urls=%d channels=%d"
        % (len(mir_out), len(set(nm for nm, _u in mir_out))))

    # 安全阀只看「真能播的台」：这次一条源都没过、纯粹靠旧表占位的空台不能算数，
    # 否则全网抽风时 total_chs 依然漂亮，巡检却把一份空壳表覆盖上线了。
    # 调试预览照写，否则 --limit 永远看不到结果。
    if total_live < MIN_ALIVE_CH and not debug:
        log("too few alive channels (%d < %d), keep old file" % (total_live, MIN_ALIVE_CH))
        return 0

    out = args.out
    if not out:
        out = PREVIEW_JSON if debug else OUT_JSON

    if debug:
        log("debug run (--limit), logo stage skipped, assets/live.json untouched")
    else:
        # 台标与源探测是两条独立链路：iptv-org 挂了、PNG 解码遇到怪格式、
        # 磁盘写不进去 —— 任何一种都不该把已经跑完 5 分钟的探测结果一起丢掉。
        try:
            # 只给「这次真有源」的台去 iptv-org 查台标：零源台留着只是占位，
            # 为它们发几百次请求纯属浪费 LOGO_DEADLINE，旧值由 carry_old_logos 兜。
            names = [c["name"] for g in groups_out for c in g["chs"] if c["urls"]]
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
    n_empty = total_chs - total_live
    log("groups: " + " ".join("%s=%d/%d" % (g, n, live) for g, n, live in stat))
    log("channels=%d (live=%d empty=%d) urls=%d logos=%d (%.1f%%) bytes=%d -> %s"
        % (total_chs, total_live, n_empty, n_url, n_logo,
           100.0 * n_logo / max(1, total_chs), size, out))
    if not debug:
        fn, fb = dir_stat(LOGO_DIR)
        log("assets/logo files=%d bytes=%d" % (fn, fb))
    log("TOTAL %.1fs" % (time.time() - t_start))
    return 0


if __name__ == "__main__":
    sys.exit(main())
