# 飞牛影视接入（YG影视 资源 → 手机/电视飞牛影视里直接看）

目标：不动电视端的 YG影视，把它的**点播片库**和**电视直播**搬进飞牛影视，
在手机飞牛影视 App 里像看自己 NAS 里的片子一样点开就看。

两条链路，互相独立，可以只做其中一条：

| 资源 | 手法 | 产物 |
| --- | --- | --- |
| 电视直播（央视/卫视/江西电信） | 飞牛影视「新增媒体库 → 电视直播」上传 m3u | `dist/飞牛直播/0X-*.m3u` |
| 影视点播（电影/剧/动漫/综艺） | 生成 STRM 库交给飞牛影视刮削 | `out/strm/**` |

---

## 一、电视直播

### 1.1 为什么第一版 470 台全黑

第一版 `gen_live_m3u.py` 只验证「播放列表能打开」，没验证「分片能不能下」。
源里 159 条江西电信是**两层列表**（master → 真列表），飞牛播放器拿到第一层里没有视频分片，
就一直转圈。所以判定标准必须是「**真下载到一个 `.ts` 分片且首字节是 `0x47`**」。

### 1.2 现在的工具链（按执行顺序）

```bash
# ① 汇总原始源 → 全量 m3u（合并 live_jx.txt + live.json，只留 http(s)）
python gen_live_m3u.py

# ② 四关校验：播放列表合法 → 分片是 TS/fMP4 → PMT 拿到编码 → 带 ACAO
python build_live_m3u.py --m3u out/yg-live.m3u --workers 20
#   → out/yg-live-web.m3u（CORS 全过，网页端免插件）
#   → out/yg-live-app.m3u（媒体有效即可，手机/TV App 用）

# ③ 拍平成单层：master 递归解到最后一层，只写带分片的那版列表地址
python gen_flat_m3u.py --m3u out/yg-live-app.m3u --out out/yg-live-flat.m3u --workers 24
python gen_flat_m3u.py --m3u out/yg-live-web.m3u --out out/yg-live-flat-web.m3u --cors --workers 24

# ④ 复验 + 审计
python check_play.py --m3u out/yg-live-flat.m3u --out out/app_play.csv --workers 24
python audit_m3u.py --m3u out/yg-live-app-final.m3u
```

关键实现点（改脚本前先看这里）：

- **不能按逗号截断**：江西电信子列表地址形如
  `.../01.m3u8?fmt=ts2hls,7824,01.m3u8,0,8467,0,0`，逗号后面是鉴权参数，
  截断后会退回上一层，永远拿不到 `.ts`。
- **PMT 解析要跳过 `pointer_field`**：`pkt[4]` 是 pointer，表头从 `pkt[1+1+ptr]` 开始，
  否则 535 条全部判成 `NO_PMT`。
- **master 里的子列表要挑最高 BANDWIDTH**，递归深度上限 4。
- `check_play.py` 会带 VIDAA UA 和 Referer，比飞牛播放器宽松 —— 它 OK 不代表飞牛一定 OK，
  所以最终交付前还要人眼在飞牛里点一遍。

### 1.3 交付文件（`dist/飞牛直播/`）

| 文件 | 台数 | 用途 |
| --- | --- | --- |
| `01-先试这10台.m3u` | 10 | 先导这个验证链路 |
| `02-央视卫视影视精选240台.m3u` | 240 | 去掉地方台的日常版 |
| `03-全量415台(手机App用).m3u` | 415 | 拍平后剔掉拉不到分片的 |
| `04-网页端216台(免插件).m3u` | 216 | 带 ACAO，浏览器直接用 |
| `05-去假台401台.m3u` | 401 | 再剔无信号/购物/春晚回放/同址多名 |
| `06-纯HTTPS-76台.m3u` | 76 | 排查 http 明文被拦 |
| `07-代理版401台(需本机开机).m3u` | 401 | 指向 `live_proxy.py`，绕跨域 + 藏真实源 |

导入路径：飞牛影视 → 媒体库 → 新增媒体库 → 内容类型「**电视直播**」→
选「**上传 m3u 文件**」。
> 不要填局域网 m3u 链接：飞牛影视会把内网地址判成「请填写正确的直播源链接」，
> 上传文件是唯一稳的入口（社区 65654 / 65572 都是这个坑）。

### 1.4 本地代理（可选，用于网页端和藏源）

```bash
python live_proxy.py --m3u out/yg-live-clean.m3u --port 8899
# 浏览器/飞牛打开 http://<本机IP>:8899/list.m3u
```

- 反代上游分片、统一补 `Access-Control-Allow-Origin: *`，顺便把真实源藏起来。
- 列表地址按请求的 `Host` 头拼，手机用 `10.1.1.9:8899` 访问就输出 `10.1.1.9`，
  不会出现 `127.0.0.1` 死链（这个坑踩过一次）。
- 入口：`/list.m3u`、`/live/<idx>.m3u8`、`/seg?u=<urlsafe-b64>`、`/health`。
- 代价：电脑得开着。要长期用就挂到飞牛的计划任务或 systemd。

### 1.5 已知边界

- 飞牛**不支持 udp/rtp 组播**，本脚本只导出 http(s) HLS。
- Web 端跨域还有个别源不行，播不了就换 `04`/`07`，或装
  [KNAS 插件](https://help.fnnas.com/articles/v1/media/iptv-help)。
- 地方台多数是小水管，卡顿属正常。
- 采集站直链会失效，建议每周重跑一次 §1.2 的 ①~④。

## 二、影视点播（STRM 库）

原理：`.strm` 就是一行播放地址的文本文件，飞牛影视扫到后用自己的播放器去拉流。
NAS 上只占几 KB，不下载视频本体。

1. 生成 STRM 目录树（本机或 NAS）：

   ```bash
   python gen_strm.py                      # 每源 20 页，输出 out/strm
   python gen_strm.py --pages 60 --per-source 1500   # 想要更全的片库
   python gen_strm.py --dry-run            # 只看会生成多少，不落盘
   python gen_strm.py --prune              # 连带清理下架条目
   ```

   目录结构：

   ```
   out/strm/
     电影/片名 (2026)/片名 (2026).strm
     电视剧/剧名 (2025)/Season 01/剧名 S01E01.strm
     动漫/…   综艺/…   纪录片/…
   ```

   已产出的现成包：`dist/yg-strm.zip`（74160 个 strm，电影 1847 / 电视剧 12635 /
   动漫 53521 / 综艺 6037 / 纪录片 120）。

2. 把 `out/strm` 整个目录拷到 NAS，例如
   `/vol1/1000/media/ygvod/`（飞牛「文件管理」上传，或 SMB 拖进去）。

3. 飞牛影视 → 媒体库 → 新增媒体库，**按类型分开建**，路径指到对应子目录：

   | 内容类型 | 目录 |
   | --- | --- |
   | 电影 | `/vol1/1000/media/ygvod/电影` |
   | 电视剧 | `/vol1/1000/media/ygvod/电视剧` |
   | 动漫 | `/vol1/1000/media/ygvod/动漫` |
   | 综艺 | `/vol1/1000/media/ygvod/综艺` |
   | 纪录片 | `/vol1/1000/media/ygvod/纪录片` |

   建库时打开「定时扫描媒体库」，以后重新生成 strm 会自动补进来。

注意事项（踩过的坑）：

- **必须带年份**：飞牛影视没有本地 nfo，只靠文件名/目录名匹配 TMDB，
  `片名 (2026)` 这种写法识别率最高。
- **播放地址不能是 127.0.0.1 / localhost**：飞牛在播放端请求地址，写本机地址必然失败。
  本脚本导出的都是采集站公网直链，没这个问题。
- **目录权限**：如果 strm 是用 root 生成的，飞牛可能读不到，`chmod -R 755` 或换成普通用户跑。
- **小文件过滤**：飞牛影视刮削默认忽略 <3MB 的视频文件，strm 只有几百字节，
  若某个库扫不出条目，把该库的「过滤小文件」关掉。
- **配音版本要归并**：同一部片「国语版/粤语版/HD国语」会生成多份目录，
  `gen_strm.py` 的 `dedupe_key` 已处理（第一版没做，柯南一部铺了 7686 个文件）。
- m3u8 直链会随采集站下架而失效，建议每天跑一次 `gen_strm.py --prune`
  （飞牛「计划任务」里加一条 shell 任务即可）。

## 三、定时更新（可选，飞牛计划任务）

飞牛 → 设置 → 计划任务 → 新增，执行周期每天一次，命令示例：

```bash
cd /vol1/1000/apps/ygbridge && /usr/bin/python3 gen_strm.py --pages 20 --prune >> /tmp/ygstrm.log 2>&1
cd /vol1/1000/apps/ygbridge && /usr/bin/python3 gen_flat_m3u.py --m3u out/yg-live-app.m3u --out out/yg-live-flat.m3u >> /tmp/yglive.log 2>&1
```

所有脚本只用 Python 标准库，不需要 pip 装任何东西。
