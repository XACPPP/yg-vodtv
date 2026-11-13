# YG影视TV

自用的电视端 **影视 + 直播** 双板块 App，纯原生 Android（无 Gradle、无第三方库），专为海信
VIDAA 电视（Android 5.1.1 / API 22，已 root）定制。点播片源来自 **14 个苹果CMS采集源聚合**，
App 内部只认统一频道号，按源查表翻译成各站自己的 `type_id`，所以换源、加源不用改界面代码；
直播源主要来自 GitHub 两份公共 IPTV 清单，逐条测速后只留真能播的，台标随表内置（v2.7 / v2.7.1 修好打包）；
v2.11 起另内置一组**江西电信 IPTV 单播源**（运营商内网直连，22ms 首包、8.4Mbps 码率），排在最前面。

当前版本：**v2.12（versionCode 24）**，首页标题栏右侧会显示版本与构建时间，用来确认电视上跑的是哪一版。
源码与成品都在 <https://github.com/XACPPP/yg-vodtv>，打 tag 自动出包并发 Release，App 里能自己检查更新。

## 一、它是什么

一句话：**遥控器点开就能搜片、选集、播放的电视 App。**

- 首页：**24 个统一频道**（推荐、4K 电影、动作、喜剧、国产剧、动漫、综艺、悬疑剧……）海报墙，
  每页最多 84 条、14 个源并行聚合去重，遥控器方向键浏览，点「加载更多」翻页；
  **第一页列表落盘 + 相邻频道预取**，切频道不转圈，海报整屏一次出（v2.4）
- **电视直播**（v2.6）：顶栏「电视 / 影视」双板块一键互跳。直播表来自 GitHub 两份公共 IPTV
  清单（Guovin + vbskycn，约 2200 条），逐条实测 `200 + #EXTM3U`，**只留真能播的那 41%**，
  同台最多留 3 条按首播毫秒排序。**卡了一下就换源**：菜单键呼出线路面板、左右键直接切、
  遥控器 CH+/CH- 也行；一条挂了自动静默切下一条，三条全挂才提示。上下键在同组内换台
- **源表热更**：源的定义是一份远端 JSON，GitHub Actions 每天自动巡检打分重排，
  换源 / 加源 / 调优先级**不用发版**；远端出任何问题都自动退回内置表（v2.4）
- 搜索：**一屏左右分栏**——左边键盘 + 最近搜过的词，右边实时结果；**停手 2 秒自动搜**，
  不用再按「搜索」；中文关键词并发问 14 个源的 `wd=`，纯拼音改问各源的 `suggest`（实测比 `wd=` 准得多），
  两路结果合并后按贴合度重排，再把「只是碰巧带这几个字」的噪声剪掉；
  **拼音首字母也能搜到**（v2.5）——本地维护一份 14.5 万条片名的首字母表，`wddcz` 先翻成
  「伟大的长征」再走 `wd=`；结果不再死等 10 秒，凑够一屏就走
- 详情：海报 + **导演/主演/地区/语言 + 简介（点一下展开全文，缺失时自动跨源回填）** + 选集列表，每行 10 个选集按钮
- 播放：全屏 HLS 播放，上下键选集、左右键快进快退 15 秒、OK 暂停、自动连播下一集；
  画面比例**默认自动**（裁得少就铺满、裁得多就留黑），遥控器菜单键循环「自动 → 适应 → 铺满」
- **续看**：每 5 秒记一次播放位置，退出/切集时再存一次；下次进详情页会自动把焦点落在上次看到的那一集上（该集按钮显示为金色），点进去从断开处接着播
- **自检**：标题栏「自检」按钮，9 步串行验证片源链路（网络参数 → 代理端口 → 多源分类 → **海报下载** →
  海报缓存 → 多源搜索 → 详情集数 → m3u8 首跳 → 分片首包），全绿就说明能看；下半屏直接显示环形错误日志
- **检查更新**：标题栏「检查更新」按钮（开机也会静默查一次），有新版直接在 App 里下载并拉起系统安装器，不用再插 U 盘

## 二、技术方案

### 片源链路（v1.7 起是多源）

所有源都是**苹果CMS 标准协议**，请求形状完全一样，只有 `type_id` 各不相同：

```
分类列表  GET <源base>?ac=detail&pg=<页码>[&t=<type_id>]
          → 20~30 条/页，带 vod_pic / vod_content / vod_play_url
详情      GET <源base>?ac=detail&ids=<id>
          → vod_play_url = "第01集$https://.../index.m3u8#第02集$..."
中文搜索  GET <源base>?ac=detail&wd=<关键词>        ← 除 wsyzy 外九个源都支持
拼音联想  GET https://wsyzy.cc/index.php/ajax/suggest?mid=1&wd=<拼音>
          → [{id, name, pic}]；实测 14 个源里有 11 个支持，v2.0 起按源并发问
```

四点必须记住：

- **`type_id` 不能跨源混用**。国产剧在 `wsyzy.cc` 是 `t=13`、`caiji.moduapi.cc` 是 `t=26`、
  `bfzyapi.com` 是 `t=31`、`ikunzyapi.com` 是 `t=23`。所以 App 内部只认 `Src.CH_xxx` 频道号，
  取数时用 `Src.type(频道)` 查表翻译，查不到（返回 0）就跳过这个源，整条链路不写死任何 type_id。
- **`wd=` 不是所有源都支持**：`wsyzy.cc` 直接返回 18 字节纯文本「暂不支持搜索」（不是 JSON），
  其余九个源都能正常返回 `list`。所以中文搜索走多源 `wd=`，拼音只能走 wsyzy 的 suggest。
- **`ac=list` 不能用**：比 `ac=detail` 小十倍，但**不返回 `vod_pic`/`vod_year`/`vod_area`**，
  海报墙会全空，所以只能一律用 `ac=detail`。
- **`limit=` 参数各源都不认**（实测传 `limit=7` 仍旧返回默认条数），所以每页条数只能本地截断。

### 多源聚合（v1.7 新增，`Src.java` + `Multi.java`）

```
Src.java    14 个源的 key / base / Referer + 「频道 → 各源 type_id」映射表
Multi.java  按频道并行取各源第 N 页 → 轮转交织 → 按「片名+年份」去重 → 截断到 84 条
```

关键设计：

| 点 | 做法 | 为什么 |
| --- | --- | --- |
| 并行 | 6 线程池 + `CountDownLatch`；**集齐 6 个源就立刻出画面**，最多等 9 秒 | 一个慢源不能拖死整页；实测 24 个频道中位数 1.0s、最慢 2.3s |
| 熔断 | 某源连续失败 → 冷却 **3 分钟**不再请求 | 一个站彻底挂掉时，不让每个频道都白等它重试 |
| 交织 | 第 n 轮取「每个源的第 n 条」 | 首屏混着各站的片，不会出现「前 20 条全是同一个站」 |
| 去重 | 键 = 片名（空格归一）+ `|` + 年份 | 同一部剧在多个源都有，不能重复占卡位 |
| 去重后上限 | 84 条/页（7 列 × 12 行） | 遥控器翻太久反而难用 |
| 单源超时 | 8 秒连接 / 10 秒读取 | 够取 87KB 的整页，又不至于卡在握手 |
| 简介回填 | 详情页简介 <20 字时，拿片名跨源搜同名，取前 6 条里年份不冲突且有长简介的回填 | 单源最新页的 `vod_content` 覆盖率只有 3/20，其余源在 12~30/20 |

源优先级按「简介覆盖率 + 响应速度」排：`maoyan`（30 条/页、简介 30/30、0.8s）→ `ffzy` →
`bfzy` → `ysxq`（片库最大 429174 部）→ `modu` → `ikun` → `360zy` → `yhzy` → `wsyzy`（简介差、
但拼音联想独苗，必须留）→ `cmov`（4.9s 最慢，兜底）。

实测（`bash run.sh tabs`）：24 个频道**共聚合 1704 条，每个频道都有数据且有海报**；
每个频道 6~9 个源有返回。`maoyan` 的「欧美综艺」是空列表，属源侧情况，不影响聚合结果。

### 采集源实测（2026-11-12）

老板反馈「片源太少、大多是电影、没有最新电视剧」。为此从 GitHub 上的采集站合集
（`mylazily/ziyuanzhan`、`greatyingzi/cms-source-health`、`evenbsies/tvbox-harvest`、
`lubin776/tvbox-api-backup`、`dimogu/tvbox-merged`、`gaotianliuyun/gao` 等）拉了 **241 个候选 base**，
用 `test/probe_sources.py` 全量实测（UA 固定 Android 5.1.1 VIDAA TV、超时 12s），
再用 `test/verify_final.py` 对 19 个入围站二次复核（列表 / 详情 `ids=` / 分类页 / `suggest` 搜索 / m3u8 直连）。

- 结果：152 个接口返回合法 JSON 且有数据，**按内容指纹去重后只有 27 组**（大量镜像站同源）。
- 判定要点：**Mihomo TUN 下「连得上」完全不算数**，必须看返回体是不是合法 JSON、`list` 非空，
  且 `vod_play_url` 里的 m3u8 真能 200 返回 `#EXTM3U`。
- 完整表格见 `test/sources_recommended.md`，全量明细见 `test/sources_report.txt`。

**实际接入 App 的 14 个源**（v2.0 定表，v2.4 起同时存在于 `Src.BUILTIN` 与 `assets/sources.json`，按优先级排）：

| key | 接口 base | 条/页 | 首条海报 | 简介覆盖 | 页首耗时 |
|---|---|---|---|---|---|
| `maoyan` | `https://api.maoyanapi.top/api.php/provide/vod/` | 30 | ✔ | 30/30 | 0.8s |
| `ffzy` | `https://api.ffzyapi.com/api.php/provide/vod/` | 20 | ✔ | 20/20 | 1.2s |
| `bfzy` | `https://bfzyapi.com/api.php/provide/vod/` | 20 | ✔ | 20/20 | 1.4s |
| `modu` | `https://caiji.moduapi.cc/api.php/provide/vod/` | 20 | ✔ | 15/20 | 1.3s |
| `ikun` | `https://ikunzy.com/api.php/provide/vod/` | 20 | ✔ | 17/20 | 1.0s |
| `360zy` | `https://360zy.com/api.php/provide/vod/` | 20 | ✔ | 18/20 | 1.8s |
| `yhzy` | `https://yhzy.cc/api.php/provide/vod/` | 20 | ✔ | 12/20 | 1.4s |
| `wsyzy` | `https://wsyzy.cc/api.php/provide/vod/` | 20 | ✔ | **3/20** | 0.9s |
| `cmov` | `https://www.cmov.cn/api.php/provide/vod/` | 20 | ✔ | 20/20 | 4.9s |
| `zuidazy` | `https://api.zuidapi.com/api.php/provide/vod/` | 20 | ✔ | 16/20 | 0.1s |
| `dytt` | `https://caiji.dyttzyapi.com/api.php/provide/vod/` | 20 | ✔ | 20/20 | 0.25s |
| `jszyapi` | `https://jszyapi.com/api.php/provide/vod/` | 20 | ✔ | 20/20 | 0.13s |
| `uku` | `https://api.ukuapi.com/api.php/provide/vod/` | 20 | ✔ | 16/20 | 0.35s |
| `ysxq` | `https://www.ysxq.cc/api.php/provide/vod/` | 20 | — | — | **503 停摆** |

片库规模（`total`）：`ysxq` 429174（停摆）> `bfzy` 171653 > `zuidazy` 125836 > `wsyzy` 127940 >
`jszyapi` 116466 > `yhzy` 105335 > `ffzy` 99083 > `modu` 91655 > `cmov` 89832 > `dytt` 85094 >
`360zy` 74931 > `ikun` 67663 > `uku` 60501 > `maoyan` 34484。

### 巡检复测（2026-11-13，v2.4 首跑）

`python test/source_scan.py` 全量跑了一遍，**14 个源全部存活、0 个 DEAD**，得分排序：

| 排名 | key | 分 | 等级 | 关键数据 |
|---|---|---|---|---|
| 1 | `jszyapi` | 80.5 | A | list 921ms · 简介 20/20 · m3u8 TTFB 1183ms |
| 2 | `bfzy` | 78.4 | A | list 1003ms · 简介 18/20 · 片库 171663 |
| 3 | `ffzy` | 73.3 | B | 选集 57 集 |
| 4 | `dytt` | 72.4 | B | 简介 17/20 · 选集 217 集 |
| 5 | `ysxq` | 72.0 | B | **已恢复**，片库 429268 最大 |
| 6 | `cmov` | 69.3 | B | 简介 19/20（最好） |
| 7 | `360zy` | 68.2 | B | TTFB 438ms · 分片 898kbps |
| 8 | `maoyan` | 66.3 | B | 30 条/页 · TTFB 228ms（最快） |
| 9 | `zuidazy` | 66.3 | B | list 737ms · 片库 125860 |
| 10 | `yhzy` | 65.1 | B | 简介 11/20 · 分片 185kbps（最慢） |
| 11 | `ikun` | 63.9 | C | 分片 352kbps 偏慢 |
| 12 | `wsyzy` | 61.1 | C | **简介已改善到 17/20**（旧记录 3/20），但 `wd=` 仍不支持 |
| 13 | `modu` | 60.1 | C | 选集 355 集、简介 10/20 |
| 14 | `uku` | 50.6 | C | 简介 4/20、选集 1 集，纯凑数 |

分数每次跑都会浮动几分（源站负载、CDN 回源），差 3 分以内的名次变化没有意义，
不用盯着看 —— 真正要看的是**有没有源掉出存活线**。

**两个把好源误判成 DEAD 的脚本 bug（已修）**：

1. **m3u8 变体地址拼错**：源站 master playlist 给的变体是
   `/20261112/xxx/hls/index.m3u8` 这种**根相对**路径，脚本按目录拼接得到
   `.../xxx//20261112/xxx/hls/...` → 404 → 首分片取不到 → 判 DEAD。
   第一版跑出来 `maoyan`/`360zy`/`modu`/`uku`/`ikun` 五个好源全是 0kbps，
   就是这一条造成的。修法：新增 `abs_url(base, ref)`，遇到 `/` 开头的直接拼
   `scheme://host`（和 App 里 `Proxy.resolve()` 的规则一致）。
2. **list 只问一次**：`yhzy` 冷启动首问 10.4s 超时 → 判 DEAD。复测间隔 0.5s
   就是 459ms 正常。修法：现在给三发，超时逐级放宽到 20s / 30s
   （后来实测第二次也会超时、第三次 2.2s 才 200，只重试一次不够）。

外加一个可用性改进：`ac=detail` 大多数源不回 `class` 表，导致「自动匹配频道」一节
全是空的。现在 `probe_list` 在 `classes` 为空时补问一次 `ac=list` 专门拿分类表。

`wsyzy` 排在中间偏下不是因为它差，而是**它最新页的 `vod_content` 覆盖率只有 3/20**，
这正是老板看到「全是电影、没简介」的根因；但它的 `suggest` 好用，所以留着。
`cmov` 最慢（4.9s）且不支持 `h=` 参数，放最后兜底。
**`ysxq` 从 2026-11-12 起所有域名变体都返回 503**，代码里留在最后一行等它恢复，
熔断会把它冷却 3 分钟，不拖累其它源。

v2.0 新增的四个源是从 145 个存活采集站里筛出来的，判据是**和现有源的内容重复度**
（jaccard 0.05~0.43，互不同库）而不是「连得上」：`zuidazy` 补 4K 电影 212 条、
`dytt` 补短剧库 22247 条、`jszyapi` 与 `uku` 补一批 maoyan/ffzy 没有的新剧。
实测 `玫瑰战争` 在 `zuidazy` 有 2026 国语+粤语双版本（其余源只有 bfzy 有）。

另外两个域名坑记在这：`ikunzyapi.com` 从这台机器 TLS 握手直接断（`SSLHandshakeException`），
换 `ikunzy.com` 完全正常；`ffzy` 的 web 域名 `www.ffzyapi.com` 也握不上手，
但接口在 `api.ffzyapi.com`，所以 `suggest` 必须用接口自己的 host 而不是 Referer。

注意：**分类 `type_id` 不是全网统一的**。`wsyzy.cc` 的国产剧是 `t=13`，但 `caiji.moduapi.cc` 是 `t=26`、
`bfzyapi.com` 是 `t=31`、`ikunzyapi.com` 是 `t=23`，`bfzyapi.com` 连电影大类都是 `t=20` 而不是 1。
所以代码里必须是「统一频道号 → 查表翻译」，不能把任何 `t=` 硬编码到业务逻辑里。
十站的完整 `id=名称` 对照表见 `test/class_view.txt`（UTF-8，30 行）。

已排除：`api.juliang.live`（复核时全路径 404）、`api.1080zyku.com`（返回 RSS XML 不是 JSON）、
`ys.cxkjg.cn`（308 后落 404 HTML）、`api.ukuapi.com` / `api.wujinapi.me`（列表可用但 `suggest`
分别是 HTML / Cloudflare 403，搜索链路断）、`lzizy*` 一族与 `tyyszy*`（m3u8 全部 DEAD）。

### 为什么要自己写本地代理（`Proxy.java`）

这是整个项目最关键的一块，不写就播不了。

`MediaPlayer` 的 HTTP 走的是 native 栈，**用不到 App 里 `HttpsURLConnection` 装的信任全部证书
的 SSLContext**。而片源的 `*.wsyzym3u8.com` 用的是 Certum DV 证书、`*.wsyzyts.vip` 用 Let's
Encrypt YE2、`wsyzy.cc` 用 Google Trust Services —— 这些根证书在 Android 5.1 的证书库里
基本都没有，直接丢给 MediaPlayer 会握手失败。

所以 App 内起一个只监听 `127.0.0.1` 的小 HTTP 服务（端口 18765~18800 自动选空闲的）：

```
/hls/{base64url}.m3u8   播放列表 → 用 App 的 SSLContext 拉上游，把内部地址全部改写成本机地址
/ts/{base64url}         分片/密钥 → 原样流式转发，保留 Content-Length / Content-Range / Range
```

关键设计：

- **master 多码率列表在代理里就地解开**：上游返回的是 `#EXT-X-STREAM-INF` 多码率列表，代理自己
  挑一个变体（带宽最大但 ≤ 5 Mbps，避免老芯片解不动）再往下拉一层，直接把媒体列表喂给播放器。
  Android 5.1 的 NuPlayer 对 master 列表兼容性不稳，少一层跳转就少一个失败点。
- master 里的子列表继续走 `/hls/`，媒体列表里的分片走 `/ts/`
- `#EXT-X-KEY` / `#EXT-X-MAP` 的 `URI="..."` 也改写成 `/ts/`
- 路径后缀 `.m3u8` 是硬要求 —— MediaPlayer 靠它判定这是 HLS，否则会当普通文件嗅探失败
- 相对路径解析是手写的（`resolve()` + `normalize()`），因为 `java.net.URI` 碰到 sign 参数里的特殊字符会抛异常
- 代理故意不调 `disconnect()`，让底层连接回池复用
- 支持 HEAD 请求（播放器探测元信息时会发）与 Range 请求（拖动进度条依赖它）

### v2.12：真机复检后的三处修补（分类条、菜单键、直播默认组）

v2.11 出包后把电视的 ADB 授权打开，第一次能在真机上验证，当天补三处：

#### 一、分类条：14 个分类只有 11 个露得出来
真机 1920×1080 截图量出来的：胶囊内边距 36px 时，14 个分类总宽 2279px，
超出可用 1760px 一大截，「日剧/港剧/台剧」被右侧渐隐吃掉，看着像只有 11 个。
- `tabChip` 内边距 36 → **22**、`tabGap` 24 → **14**，字号一点没动（还是 25px）；
- 总宽降到 1749px，14 个刚好排满；
- 两层渐隐改成「内容溢出才显示」——刚好排满那一版如果把最后一个分类压暗，
  比不显示更难看。

#### 二、菜单键：键帽写着「菜单 搜索」，代码里从来没拦过
首页底部从 v2.6 起就提示「菜单 搜索」，但 `MainActivity.onKeyDown` 只处理过
搜索层可见时的 BACK。按下去没反应，用户自然以为「搜索坏了」。
- `onKeyDown` 加 `KEYCODE_MENU → openSearch()`。

#### 三、直播：升级后默认分组还在公共表上
v2.11 把江西电信源内置进来了，但记忆的分组下标还指在公共表（央视/卫视），
升级完点进直播看到的仍是那个三路源全在转圈的台，等于这次换源白做。
- `Store` 加一次性标记 `live_jx_mig` + `clearLiveName()`；
- `Live.jiangxiGroup()` 返回内置江西电信组下标；
- `LivePlayerActivity.onCreate` 里：没带 groupIdx 且没迁移过 → `saveLive(jx, 0)`
  并清掉记忆台名（不清的话 `restoreByName()` 会按台名把人拽回去），迁一次就够。

#### 四、验证
- `javac` 全量 24 个源文件 0 error（160 个 .class）；`run.sh` 16 项、`run.sh livetab` 46 项全 PASS。
- 真机（VIDAA TV / Hi3751V551 / 1920×1080 / Android 5.1.1 / WiFi 2.4G 144Mbps）：
  v2.11 装机截图确认 5 列海报 + 30px 片名 + dock 300；搜索页 5 列键盘、「按 → 进入」、
  焦点能落到结果卡上。
- 电视上 `adb forward` 打通本地代理实测：江西卫视 master → 媒体列表 **0.15s**（2169 字节、
  6 个分片），分片首字节 0x47 —— 电视这台机器本身能连上 `115.153.245.70`。

### v2.11：10 尺字号、搜索进不去、换江西电信源

v2.10 出包后老板在真机上试了一遍，提了三条：

> 我回到家了，你自己调试去吧，电信ADB开了，IP是10.1.1.4；目前我试验到的问题有：
> 1、整体布局太小气，坐远点看屏幕上的字都费劲！好好让stitch再设计设计；
> 2、搜索界面用遥控器按键选不到影片；
> 3、电视直播卡出屎来了，我建议你找找江西电信的源，我家里宽带是电信的。

#### 一、字号太小：从 7 列 22px 到 5 列 30px

v2.7 那版是照 Stitch 出的一张 1920×1080 网页稿抄的，稿子本身是按**桌面浏览器**比例画的：
片名 22px、副标题 13px、dock 150px。放在电视上 13px 只有屏高的 1.2%，老板坐沙发上三米外
自然看不清。10 尺界面（10-foot UI）的经验值是：正文 ≥ 屏高 2.2%（1080p 约 24px）、
片名 2.8%（约 30px）、重点标题 5%（约 56px）。旧稿整体小了约 40%。

做法上分两步：

1. **让 Stitch 重新出稿**。在同一个 Stitch 项目（`projects/9584627652671101173`）里另起一屏
   「Android TV 影视板块首页（10 尺超清远视版 1080P）」，把上面的人机工程数值写进 prompt。
   新稿规格：海报 5 列 328×492、gap 24、页边距 56；片名 30px、副标题 18px；顶栏 88px；
   分类胶囊 26px（高 60）；dock 300px、dock 片名 56px、简介 26px 两行、按钮 30px/78px 高。
2. **在唯一的文字出口统一乘系数**，而不是改 200 多个调用点。`Ui.java` 新增：

   ```java
   public static final float TYPE = 1.4f;   // 10 尺稿相对 v2.7 网页稿的放大量
   ```

   `Ui.text()` 里改成 `t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size * TYPE))`，
   `Ui.bold()` 内部调 `text()` 自动跟着放大。另有 8 处没走 `Ui.text()` 的（详情页两处、
   直播抽屉台名一处，以及三处 Canvas `drawText` 画台标），前三个显式补上 `* Ui.TYPE`，
   Canvas 那三处是按位图尺寸比例算的，不动。

配套的版式改动（`MainActivity` / `Chrome`）：

- 首页 `COLS` 7 → **5**：7 列时单卡只有 240 宽，×1.4 后 30px 片名会折成两行；
  5 列是 345×483，和新稿的 328×492 对齐
- 顶栏 `BAR_H` 72 → **88**（装不下 30px 品牌字标 + 胶囊内边距）；品牌字标 32 → 22（×1.4 = 31px，
  留 32 会变 45px 抢片名的视觉权重）；品牌方块 40 → 52；板块胶囊内边距 24/7 → 28/10；
  统计胶囊 13px → 14px、内边距 16/6 → 20/8
- 分类胶囊内边距 32/6 → 36/10、间距 20 → 24；顶栏小胶囊（搜索/自检/清缓存）20/8 → 24/10
- dock 高度 150 → **300**，内边距 10/4 → 56/20；简介从 1 行改 **2 行**、颜色从暗灰 `FG_DIM`
  改正文色 `FG_BODY`（离远了暗灰等于不可读）；按钮字号 18 → 20、内边距 28/24 → 40/34 × 20、
  圆角 8 → 12
- **删掉「影视大厅」那行标题**：字号放大 + dock 加到 300 之后，这一行要吃掉 70px，
  海报区只剩 640px，第二排只露 20px 的帖边，看着像加载失败。它写的「14 个采集源」
  在顶栏统计胶囊里本来就有，删掉后第二排能露出 130px

#### 二、搜索页遥控器选不到影片：三个叠在一起的原因

1. **`ScrollView` 自己抢焦点**。`resultScroll` 没关 `focusable`，默认又是
   `FOCUS_AFTER_DESCENDANTS`，方向键向右找焦点时它比结果卡更靠近左栏（左边缘 828 < 832），
   焦点一落到 ScrollView 上，上下键就只是滚动它，永远落不到海报上。改成
   `setFocusable(false)` + `setDescendantFocusability(FOCUS_AFTER_DESCENDANTS)`。
2. **背后的主界面还能聚焦**。搜索层只是叠在 `root` 上的可视层，底下的海报墙和顶栏仍然可聚焦，
   焦点从键盘往下走会"漏"到被遮住的控件上，表现就是按键没反应。修法：把 `buildUi()` 里的
   `col` 提成字段 `pageCol`，`openSearch()` 时 `INVISIBLE`，`closeSearch()` 时恢复。
3. **没有任何"怎么进结果区"的提示**。新增 `searchKey()` 把左右键钉死：焦点不在结果区时按右键
   直接跳到第一张结果卡；结果区第一列按左键回键盘；结果区第一行按上键也回键盘。
   标题顺手改成「「关键词」N 个结果 · 按 → 进入」。

搜索结果列数也从 4 改成 **3**：4 列时单卡 251 宽，放大后 24px 片名又变小了；
3 列 334 宽和首页 345 宽基本一致。

#### 三、直播卡：找到江西电信的内网单播源

先排除自家代码的问题：v2.10 已经把上游列表超时收到 5s/8s、多档失败逐档回退、记住每台上次
成功的线路。剩下就是源本身慢。公共 IPTV 清单里能播的多数是转了好几手的地址，码率低、丢包高。

按老板的提示去找江西电信的源，实测出一条**运营商内网单播**通道：

```
http://115.153.245.70/PLTV/88888888/224/{频道ID}/{令牌}.smil/index.m3u8?fmt=ts2hls
```

几个必须记住的坑（全部实测踩过）：

- **`?fmt=ts2hls` 不能省**，省了直接 404；这是华为平台把 RTSP 转 HLS 的入口
- 首跳是 **302**，跳到 `115.153.207.107:80/wh7f454c46tw…`（带 `RTS`/`hms_devid` 签名），
  必须跟随重定向。App 的 `Http.open()` 本来就是 `setInstanceFollowRedirects(true)`
- master 只有 103 字节，变体行是 **`01.m3u8?fmt=ts2hls,7824,01.m3u8,0,8466,0,0`** 这种带逗号的
  私有格式，**必须整条当 URI 用**；只取 `?fmt=ts2hls` 之前那段会又转回 master，
  变成 master → master 的死循环
- 实测指标：首包 **22–27ms**、单档 `BANDWIDTH=8669184`（8.4Mbps）、10 秒分片 10.5–11.8MB、
  下载 4.5s（≈19Mbps 实测吞吐，**余量 2.3 倍**）、首字节 `0x47`（合法 TS）

内置了 12 台（各 1 条线路）：江西卫视、江西都市、江西经济生活、江西公共农业、江西新闻、
江西少儿、江西教育、CCTV-1、CCTV-13、湖南卫视、浙江卫视、东方卫视。另外 4 个台
（CCTV-5、江西影视旅游、江西移动电视、风尚购物）令牌已失效返回 404，没有内置。

这批源**写死在 `Live.java` 的内置表里，不进 CI 巡检**：`115.153.245.70` 是运营商内网地址，
GitHub Actions 的 runner 根本连不上，跑 `live_scan.py` 只会把它们判死剪掉。
`Live.groups()` 现在返回「内置江西电信 + 远端表」的拼接，江西电信永远排第一。

端到端验证（不只是 curl 一下）：用 `Proxy.wrapPlaylist()` 把这条地址交给本地代理，
再由代理取列表和分片 —— 列表 200、2047 字节纯媒体列表（master 已解开）、分片行全部改写成
`/ts/`，取回分片 **10836132 字节 / 4532ms / 首字节 0x47**。链路「App → 本地代理 → 运营商内网」通了。

#### 四、验证

- `javac` 全量 24 个源文件（`-source 8 -target 8` + `android-30/android.jar`）→ **0 error**
- `bash test/harness/run.sh` → **[PASS] 16 项**
- `bash test/harness/run.sh livetab` → **[PASS] 46 项**（新增 4 项江西电信断言：
  排在最前 / 两组 / 12 台 / 每条都带 `fmt=ts2hls`）

#### 五、实发记录

- 提交 `8fce67b`「v2.11：10 尺字号（5 列海报 + dock 300）、修搜索遥控器选不到影片、内置江西电信 12 路源」
- tag `v2.11` → CI run `43449027830` **success**，Release 产物 `YG.TV.apk` / `YGTV.apk` 各 572345 字节
- 产物核对：`aapt2 dump badging` 得到 `versionCode='23' versionName='2.11'`；
  `classes.dex` 里能查到 `115.153.245.70`、`fmt=ts2hls`、「按 → 进入」、「还没选片」四处新代码
- **待真机确认**（老板电视的 ADB 未授权，连不上，所以这三条只有代码与链路证据，没有截图）：
  1. 三米外看首页片名/dock 是否够大；第二排海报是否露出足够高度
  2. 搜索页按右键能不能进到结果海报、按左键能不能回键盘
  3. 直播切到「江西电信」组，江西卫视等台是否秒开、是否还会转圈

### v2.10：直播卡顿与「没选片就显示简介」

v2.9 把首页修出来之后，老板提了两件事：

> 1、不显示影片的问题解决了，接下来把直播源慢的问题解决一下；
> 2、首页点开怎么下方直接显示第一部影片的简介了，我都还没选影片，还以为界面卡了,

#### 一、dock 没选片就把第一部片的简介灌进来

根因不是「渲染时多调了一次 `updateDock()`」这么简单。`renderPage()` 会自动把焦点
`requestFocus()` 到第一张海报，而 `Cards.poster()` 的 `OnFocusChangeListener` 里

```java
if (has && cb != null) { cb.onCardFocus(v); }
```

会把「焦点落在第 1 张卡」当成「用户选中了这部片」回调出去。所以哪怕把
`renderPage` 里那行 `updateDock(v)` 删掉，焦点一落上去，dock 照样被填满 ——
程序眼里「有焦点」和「用户选的」是同一个事件，这是根子。

改法：加一个 `dockArmed` 开关，只在用户真的按键之后置真。

- `MainActivity.onKeyDown()` 拦 `DPAD_UP/DOWN/LEFT/RIGHT/CENTER/ENTER` → `armDock()`
- `updateDock()` 开头 `if (!dockArmed) return;`，右臂没开就不填内容
- `selectTab()` 里 `dockArmed = false; setDockIdle(true);` —— 换频道等于换一批海报，之前的选中作废
- 初始态 `setDockIdle(true)`：标题「还没选片」、副行「方向键选片 · OK 看详情 · 菜单搜索」，
  「影片详情 / 立即播放」两个按钮 alpha 降到 0.35 且 `setFocusable(false)`。
  **能聚焦但点了没反应的按钮也是「以为界面卡了」的来源**，所以顺手把焦点也从上面挪走。
- `Cards.poster()` 给外层 box 加 `box.setTag(v)`，`armDock()` 用
  `getCurrentFocus().getTag()` 反查当前焦点是哪一部，不用另外维护焦点索引。

#### 二、直播卡：三个真实的机制性原因

1. **播放列表超时和分片一样长。** `Proxy.fetch()` 里 `Http.open(target, Api.REFERER)`
   用的是默认 12s 建连 / 20s 读，而 m3u8 只有一两百字节 —— 5 秒建不上连基本可以判定这路源死了。
   现在按 `guessPlaylist`（路由是 `/hls/` 或地址带 `.m3u8`）分开设：
   列表 5s/8s，分片 6s/20s。分片读超时保留 20s，否则正常码率也会被误杀。
2. **master 拍平只取一档，失败就整条线路作废。** `while (#EXT-X-STREAM-INF)` 里
   `String sub = bestVariant(text)` 只返回一个地址，`Http.get` 又是 3 次重试 + 12s/20s，
   最坏情况下单层等 36 秒，然后 `break` —— 用户看到的就是一直转圈直到换台。
   现在 `bestVariants()` 按码率接近度（目标 2.6Mbps）排出最多 4 档候选，逐个
   `Http.getQuick()`（单次、5s/8s）试，拉不到就换下一档。
3. **每个台都从排第一的源开始。** `pickChannel()` 里 `urlIdx = 0` 是硬编码的，
   而巡检排序第一的源未必在这台电视/这条宽带上通。新增
   `Store.saveLiveUrlIdx(台名, 线路下标)`，在 `onPrepared`（真起来了才记）写入，
   换台入口统一走 `playPreferred()` 从这个台上次成功的线路起播。

顺带：播放列表加了 1.5 秒的极短缓存（`PL_CACHE_MS`，最多 48 条）。直播列表 2~6 秒
才滚动一次，但播放器在起播/切台/重新准备时会短时间内重复请求同一个 m3u8，
每次都实打实走一趟上游，叠加起来就是「按键之后一直转圈」。TTL 只给 1.5 秒，
够挡掉同一秒内的重复请求，又不会拿到过期列表（过期列表会表现为分片 404 / 画面倒退）。

`START_TIMEOUT_MS` 15s → 12s：Proxy 都 5 秒放弃建连了，再让用户多盯 3 秒转圈没意义。

#### 三、巡检排序换了权重（`live_scan.py`）

原来 `_rank_key` 是 `(hd, ttfb, url)` —— 只要 TTFB 快就往前排，结果排第一的
经常是「响应很快但码率只有 400kbps」的源，电视上一放大就是糊 + 卡；
而 2Mbps 但 TTFB 1.2s 的源反而被挤到后面。**TTFB 只影响起播那一下，之后卡不卡看的是持续吞吐。**

现在：

```python
def _rank_key(item):
    ttfb, url, res, kbps = item
    mir = 1 if host_of(url) in MIRROR_HOSTS else 0
    hd = 0 if (res[0] >= HD_MIN_W and res[1] >= HD_MIN_H) else 1
    return (mir, hd, _kbps_band(kbps), ttfb, url)
```

- `mir`：`MIRROR_HOSTS` 那几个绕境外的中转以前只做统计，实测 TTFB 很小但吞吐抖得厉害、
  长期霸占每台第一位，现在直接沉到同档末尾
- `_kbps_band()`：≥3000→0、≥1500→1、≥800→2、>0→3、无数据→4
- `buckets` 里存的四元组随之变成 `(ttfb, url, res, kbps)`

#### 四、实发记录

- 提交 `7dd2f35`「v2.10：直播提速（列表短超时+多档回退+记线路）与 dock 未选片占位」已推 `origin/master`，tag `v2.10` 已推。
- 本地只跑了 `javac` 全量语法/类型检查（0 error，**不产 APK**，按规矩不在本机打包）+ `test/harness/run.sh` 16 项全过。
- CI run [`43431811845`](https://github.com/XACPPP/yg-vodtv/actions/runs/43431811845) success。
- Release <https://github.com/XACPPP/yg-vodtv/releases/tag/v2.10>，资产 `YGTV.apk` / `YG.TV.apk` 均 **572345 字节**。
- 待真机确认：①首页进影视板块，dock 应是「还没选片」而不是第一部片的简介；②同一个直播台第二次进，
  抽屉里副标题的「第 N/5 路源」应停在上次成功的那一路。

### v2.9：首页一片空白 —— 是我自己加的渐隐层把海报墙挤成了 0 高

v2.8 发出去之后，老板在手机上打开影视板块：

> 准确来说是频道能显示和点击，但是没有任何影片，连个框框都没有，首页只剩顶部栏，
> 下面是纯黑，连海报占位框都没渲染出来 分类能切，但每个分类点进去都是空的，
> 估计接口返回空数组或解析全挂了

先按老板的猜测查数据侧，全部排除：

- `bash run.sh tabs` 24 个分类 1686 条全绿（每类都有数据、都有海报）
- PC 上用 App 的 UA 直接打 14 个源的 `?ac=detail&pg=1`，12 个返回 200 且 `list` 非空
  （只有 `uku`、`ysxq` 两个源 301，被 `Http.get` 判失败后冷却 3 分钟，不影响其它 12 个）
- `Multi.list` 单源异常有 `catch`，永不返回 null；`Multi.decode` 有兜底
- 三套占位图 `loading/letter/placeholder/channelTile` 全部 `Math.max(8, …)`，0 尺寸不崩

真正的凶手在 `MainActivity.java` 的 `chipWrap` —— **v2.8 为了抄设计稿的左右渐隐，自己加的那层 `FrameLayout`**：

```java
FrameLayout chipWrap = new FrameLayout(this);          // WRAP_CONTENT 高
chipWrap.addView(chipScroll, MATCH_PARENT × WRAP_CONTENT);
chipWrap.addView(fadeL, Ui.px(32) × MATCH_PARENT);     // ← 病根
chipWrap.addView(fadeR, Ui.px(96) × MATCH_PARENT);
```

`chipWrap` 自己是 `WRAP_CONTENT`，父 `LinearLayout` 量它时给的是
`AT_MOST(整屏高)`；`FrameLayout` 于是把两个 `height=MATCH_PARENT` 的子 View
也量成 `AT_MOST(整屏高)`，而 `View.getDefaultSize()` 对 `AT_MOST` 直接返回
**上限值**（就是整屏高）。结果两层渐隐各占一整屏，`chipWrap` 被撑成整屏高，
下面 `weight=1` 的 ScrollView 只能分到 **0 高**：

| 元素 | 用户看到的 |
|---|---|
| 顶栏 `bar` | 正常 |
| `titleRow` / `chipWrap`（分类条在它顶部） | 正常、可点、可切 |
| `scroll`（海报墙，weight=1） | **0 高，整片黑** |
| `buildDock()` | 被顶到屏幕外，看不到 |

这和「v2.7.1 一切正常」也完全对得上 —— v2.7.1 没有 `chipWrap`，`chipScroll`
是直接 `col.addView` 的。

修法：

1. 两个渐隐层初始高度给 `0`（不占位），挂 `OnGlobalLayoutListener`，
   按 `chipScroll.getHeight()` 的实测值同步高度。不再用 `MATCH_PARENT`。
2. 顺手修 `Cards.java` 的横向溢出：外层 `box` 有 `padding = Ui.px(4)`，
   内层 `f` 却用满宽 `outerW`，7 列一行每张多出 `2*4`，合计溢出 `56px`。
   改成 `Math.max(1, outerW - padOuter * 2)`。

教训写在这里：**`WRAP_CONTENT` 的父容器里放 `MATCH_PARENT` 的子 View，
在 `AT_MOST` 约束下会被量成上限值**。抄设计稿做遮罩/渐隐层时，这类叠层一律
给确定高度或 `GONE`，不能指望它「跟着父容器自适应」。

### v2.8：比例算对、直播能看、直播不再像影视

老板原话（三条一起骂的）：

> 1、自适应做的很奇怪，有的视频明明可以完美做满屏，却被留出大黑边，比例算错了；有的视频全屏又铺满的又很奇怪！！
> 2、电视直播10个频道有7个都是卡顿，而且有些源还是黑屏，比如湖南卫视；
> 3、电视直播不要做成影视那样，点进去直接出电视直播才对啊，你可以去看看别人的电视直播软件是咋做的，
> 怎么会弄成影视那样呢，很奇怪啊，找找别人的软件学习一下，然后找stitch重新设计过。

#### 一、比例：旧判据比错了对象

`FitVideoView.wantFill()` 在 v2.7 及之前算的是「**铺满之后要裁掉多少**」，
`crop <= 0.34` 就判铺满。这个语义本身是错的 —— 它比的是「代价」，不是「形状是否相同」：

| 片源 | 容器 16:9 时铺满要裁 | 旧逻辑判定 | 结果 |
|---|---|---|---|
| 16:9（1920×1080） | 0% | 铺满 | 正确 |
| 4:3（1440×1080） | 25% | 铺满（≤34%） | **上下被切掉一块** |
| 2.35:1 电影 | 8% | 铺满 | 上下各切一条 |
| 9:16 竖屏 | 68% | 适应 | 正确 |

改成比「**视频宽高比 vs 容器宽高比的偏差**」：

```java
double videoAr = effectiveAr(vw, vh);
double boxAr   = (double) availW / (double) availH;
double bigger  = Math.max(videoAr, boxAr);
double dev     = Math.abs(videoAr - boxAr) / bigger;
return dev <= maxDev;          // maxDev = MAX_AR_DEV = 0.04
```

δ≤4% 才铺满（同形状，铺满等于刚好铺满），超过就适应（形状不同，硬铺一定切画面）。
常量 `MAX_CROP = 0.34` 整个删掉，换成 `MAX_AR_DEV = 0.04`。

还有第二个坑：**非方形像素（anamorphic）**。`MediaPlayer.getVideoWidth()/getVideoHeight()`
报的是**编码尺寸**，不含 SAR。国产老剧大量使用 1440×1080 / 960×720 这种
「4:3 像素存 16:9 画面」的编码，按 1.333 算比例就会被判成 4:3 → 左右留两条大黑边。
新增 `effectiveAr(vw, vh)` 只认几组确定无疑的：

| 编码尺寸 | 真实显示比例 |
|---|---|
| 1440×1080 / 1456×1080 | 16:9（HDV / 早期高清剧） |
| 1440×1088 / 1920×1088 | 16:9（1088 是 1080 的宏块对齐版） |
| 960×720 / 1280×720 | 16:9 |
| 720×576 | 4:3（PAL DVD，本来就是 4:3） |

没做泛化推测 —— 猜错的代价是把真 4:3 内容拉变形，比留黑边更难看。

第三处：`setVideoSize()` 在 v2.7 只有直播页调用，点播页一直在靠反射读
`mVideoWidth`（**上一帧**的值，起播时是 0）。v2.8 在 `onPrepared` 和每次
`MEDIA_INFO_VIDEO_TRACK_LAGGING` / `BUFFERING_*` 回调里都喂一次
`setVideoSize(mp.getVideoWidth(), mp.getVideoHeight())` 再 `requestLayout()`，
码率切换后分辨率变了也能立刻重算。

#### 二、直播卡顿 / 黑屏

看门狗原来只有三条判据（起播 15s、缓冲 9s、位置 12s 不动）。
**「有声无画」这一类源能全部骗过去**：`isPlaying()` 是 true、`getCurrentPosition()` 在走、
一次 buffering 都不报，屏幕就是纯黑。湖南卫视当时就是这种。

新增第 4 条判据：

```java
// prepared 之后 6 秒还一个像素都没解出来 → 「这一路只有声音没有画面」
if (preparedAt > 0 && videoW <= 0 && now - preparedAt > BLANK_TIMEOUT_MS) {
    autoNext("这一路只有声音没有画面，换下一路");
}
```

`videoW` 从 `mp.getVideoWidth()` 取，在 `onPrepared` 记一次、`MEDIA_INFO_VIDEO_RENDERING_START`
（what==3，真正出画）再记一次并把 `preparedAt` 清零（出画就作废黑屏计时）。

源表那边同时收紧了三道筛选（详见「巡检」段）：吞吐 <40KB/s 淘汰、TTFB >3s 淘汰、两轮复测。

#### 三、直播不再是一页海报墙

v2.7 的直播板块就是「把影视首页那套海报网格复制一份，只是把海报换成台标」——
点进去先看一页频道卡，再点一次才播。老板说得对，这不是电视直播的样子。

v2.8 按主流直播 App（电视家 / 火星直播 / TiviMate）的交互重做：

- **`LiveActivity.java` 整页删除**（834 行）。`MainActivity` 点「电视」直接
  `startActivity(LivePlayerActivity)`，开机即播。
- `onCreate` 恢复上次看的台：`getIntent().getIntExtra("groupIdx", Store.liveGroup())`、
  同理 `chIdx`。没带参数就接着上次那台看。
- **全屏播放 + 右侧频道抽屉**：`OK` 键呼出/收起抽屉（宽 560px，
  `topMargin = 84px`、`bottomMargin = 56px`，只圆左侧 20px）。
  抽屉里 `↑↓` 选台、`← →` 切分组、`OK` 播放、`返回` 收起。分组胶囊只改
  `browseGroupIdx`，**不会打断正在播的台**。
- **顶部信息条**：品牌块 + 台名 26px + 清晰度角标（FHD/4K/8K）+ 副行
  「第 NN 台 · 别名 · 清晰度 · 第 n/m 路源」；右侧「正在直播」红色胶囊（白点呼吸）
  + 时钟。
- **音量 HUD**：`KEYCODE_VOLUME_UP/DOWN` 自己拦（不再交给系统），左下角浮出
  喇叭图标 + 琥珀轨道 + 数字，2 秒后淡出。
- 底部键帽条改成设计稿样式（红键特殊红底），`MENU` 仍开「线路」面板、`红键` 循环画面比例。

设计稿走 Stitch 重新出了一版：`projects/9584627652671101173` 下的
「YG影视 - Android TV 电视直播『边看边换台』1080P体验」。第一次生成的是
2560×2048（1.25:1）画布，绝对定位全歪，回炉改成 16:9 / 1920×1080 重出一版；
同时让它删掉画面中央的巨型台标水印（真播起来挡节目）、去掉我们没有数据的
EPG「节目名」行（数据源只有台名/别名/源数）。

#### 四、影视首页补完设计稿剩下的四处

v2.7 只抄了配色和圆角，骨架没抄。这轮补齐：

| 位置 | v2.7 | v2.8 |
|---|---|---|
| 底部信息 dock | **没有** | 150px 高的 `#11141A` dock：片名 40px bold + meta 行（年份/地区/类型/备注/导演）+ 一行简介 + 「影片详情」「立即播放」两钮 + 底部键帽条 |
| 海报卡副标题 | 没有 | 13px 灰字第二行（`年份 · 地区 · 类型`），焦点时变琥珀色 |
| 焦点提示 | 只有描边 | 8px 琥珀圆点 + 「正在选中」12px（设计稿的 pulse dot） |
| 海报蒙层 | 42% 高两色渐变 | `Ui.posterScrim()` 四段 to-top 渐变（0.96 → 0.6 → 透明 → 透明），覆盖底部 62% |
| 分类胶囊 | 全圆角 999 | 8px 圆角（`rounded-lg`）、`px(32)/py(6)`、未选 `bg-white/[0.08]` |
| 分类条两端 | 硬切 | `Ui.railFade()` 左右渐隐（左 32px / 右 96px） |
| 顶栏品牌字 | 「极清影院」20px FG | 32px bold `#FFD79B`（`text-primary`） |

焦点卡动画按设计稿改成 `scale 1.06` + `translationY -4px` + 180ms，
未聚焦卡片统一 `alpha 0.76`（否则一屏 84 张全亮没层次），焦点时恢复 1.0。

**踩到的坑**：dock 主行一开始写成 `height=0 + weight=1`，结果整行消失 ——
dock 自身是 `WRAP_CONTENT`，LinearLayout 在 `WRAP_CONTENT` 下没有「剩余空间」
可分给 weight 子 View，高度直接算成 0。改成 `WRAP_CONTENT` 并把原因写进注释。

#### 五、照 Stitch「严格 1080P 版」再对一遍直播页

老板后来又提醒一句：「stitch有些画面看着也不太正常，你可以要求它修改哇，又不是只能改一次」。
确实 —— 前两版稿子的画布是 2560×2048（1.25:1），所有绝对定位都是按这个比例摆的，
搬到 16:9 电视上必然整体跑偏。第三版把改稿要求写成硬约束喂回去：

- 根节点 `width:1920px; height:1080px; overflow:hidden`，禁用 `w-screen/h-screen` 与 `rem/vw/vh`；
- 背景图 `inset:0; width:1920px; height:1080px; object-fit:cover`；
- 顶条 84px、底条 56px、抽屉 `560px`（`top:84 bottom:56`）、HUD 左 48 底 72、水印右 24 底 76。

产出 screen `0f6be93f13f64ef3b9d049c5ced86e7b`（元数据 3840×2160，16:9 正确）。拿它的 HTML 逐条对代码，补了九处：

| 位置 | 改前 | 改后（= 设计稿） |
|---|---|---|
| 播放画面上层 | 什么都没有 | `Ui.playerVeil()`：左右 `0.72→0.3→0.85` + 上下 `0.7→0.2→0.75` 两层渐变叠起来，四边压暗、中间最亮 |
| 右下角水印 | 无 | 28px 斜体 900 白 15% `YG`，右 24 底 76 |
| 顶条背景 | `roundGrad(0xD9000000→透明)` 纯黑两段 | `Ui.fade()` 三段 `rgba(14,17,22,.92/.5/0)` |
| 底条背景 | `roundGrad(透明→0xEB000000)` | `rgba(14,17,22,.85)` + 顶部 1px 白线 |
| 底条右侧 | 空 | `YG TV OS · 1080P 60Hz` 12px 灰 |
| 抽屉底色 | `0xF2171B22` | `0xF212161D`（`rgba(18,22,29,.95)`） |
| 抽屉标题 | `Chrome.title` 38px | 26px（560 宽放 38px 会把「共 N 台」挤出去） |
| 抽屉列表 | 直接挂 ScrollView | 外面包一层 FrameLayout，上下各盖 32px 渐隐，滚到边界时行不会被切一半 |
| 「正在直播」呼吸 | 只有 alpha `1↔0.4`、900ms | alpha + `scaleX/scaleY 1↔0.85`、1800ms（只做透明度像「灯坏了」） |

抽屉频道行也补成了设计稿的焦点态：指示条从 4×30 变 **4×32 发光琥珀**、台标块从半透明白底变
**白底 + 2px 琥珀描边**、台名 18→20px、右下角多两个只在焦点时出现的角标
（`正在播` 琥珀胶囊 + 清晰度绿标）、副行从「别名 · 高清」变「别名 · 第 2/3 路源」。
普通行底色 `rgba(255,255,255,.03)`（原 `.04`），焦点行 `rgba(255,179,0,.16)` + 2px 琥珀描边不变。

#### 六、详情页：先把假数据删干净，再谈好不好看

详情页原来的问题是「像那么回事，但一半是编的」：评分胶囊写着 `★ 9.4 豆瓣高分榜`，
接口的 `vod_score` 其实恒为 `"0.0"`；`继续观看 第12集 (已看至35:20)` 里的秒数我们根本没存；
`TV-MA` 分级、`杜比视界 · 杜比全景声 认证内容源`、`预告片与花絮 (8)` 全都是设计稿里的占位。

所以这一版对 Stitch 提的改稿要求第一条就是**删**：把上面五类假数据 + `YG影音系统 v4.8.2 · 遥控器信号 优秀`
一起从稿子里拿掉，再让它在 1920×1080 的锁死画布上重排。产出 screen `133e364a8ee540b4886086375e8145d2`。

代码侧（`DetailActivity.java` 整页重写，412 → 约 700 行）：

| 位置 | 改前 | 改后 |
|---|---|---|
| 骨架 | 横排 = 左栏海报 + 右栏，无顶栏无底条 | 竖排 = 72px 顶栏 / 主体（左 380 栏 + 右自适应）/ 40px 键帽条 |
| 顶栏 | 只有「← 返回列表」贴在海报下面 | 顶栏内「← 返回」胶囊 + 来源名 + 右侧 26px 时钟（进页面就对时，onResume 也刷） |
| 名次 | 无 | `← 返回` 默认 `requestFocus()`，按返回键才退，符合 10 尺遥控习惯 |
| 海报 | 380 宽 × `Cards.RATIO`(1.5) = 570 高 | 380×540（设计稿值，比 2:3 略瘦） |
| 海报角标 | 无 | 只按 `remarks` 里真实出现的字样贴：含 `4K/蓝光/1080` → 左上紫标，含 `VIP/会员` → 右上金标；**没有就不贴** |
| 海报下方 | 一个「← 返回列表」按钮 | 三个等宽 44px 按钮：收藏 / 分享 / 缓存；收藏直接读 `Store.liked()` 显示「已收藏」并变琥珀 |
| 元信息 | `备注·年份·地区·类型·源名` 五段拼一行 20px 琥珀 | 严格四项 `年份 · 地区 · 类型 · 备注`，20px `FG_DIM`（设计稿要求只这四项） |
| 导演/主演/语言 | 一整行 `导演：xxx    主演：xxx` 纯文本 | 三个胶囊（`bg-white/5 + 1px 白描边 + 999 圆角`），**空字段的胶囊不出现** |
| 简介 | 18px `FG_BODY`，3 行可展开 | 17px `FG_DIM`，3 行可展开（设计稿 `text-[17px]`） |
| 主操作 | 无（只能点选集格子） | 「▶ 立即播放」琥珀大按钮（内嵌 `OK` 深色键帽，焦点时转为外发光）+ 描边「从头播放」 |
| 选集标题 | `选集加载中… / 共 30 集 · 上次看到 第12集 · 选一集开始播放` 一坨 22px 灰字 | 左「选集」24px bold + 右「共 30 集」15px 灰，两段分开 |
| 选集格尺寸 | `px(118) × px(56)` 定宽，10 列固定溢出风险 | 按右栏实际宽度算 `(rightW - 9*gap) / 10`，10 列永远刚好铺满不横向溢出 |
| 选集格文字 | 直接印 `epNames[i]`（常常是「第01集」甚至整条 URL） | `epLabel()` 归一化：`第01集 → 01`、URL → 集号、其他截 6 字 |
| 已看进度条 | 固定 `w*0.36` 宽，纯粹装饰 | 按 `Store.pos() / Store.dur()` 真实比例画；为此新增 `Store.dur/saveDur`，播放器每 5 秒落盘位置时顺手把总时长记下 |
| 焦点格 | 只有 `focusBgGlow` 换背景 | `scale 1.08` + 发光描边 + 数字 18→20px 变白（设计稿 `scale-[1.08] outline 3px`） |
| 底部 | 无 | 40px 条：左绿点「YG 影音系统 · 遥控器操控」，右五组键帽 `OK/◀▶/▲▼/菜单/返回` |

`Store` 新增两组持久化：

```java
public static boolean liked(String key)        // 收藏状态，逗号串存
public static boolean toggleLike(String key)   // 切换并返回新状态
public static int dur(String vid, int ep)      // 某集总时长（毫秒）
public static void saveDur(String vid, int ep, int ms)
```

`PlayerActivity.updateProgress()` 里每 5 秒落盘位置时多记一项 `Store.saveDur(key, index, d)` ——
不记总时长的话，选集格子上的「已看进度条」只能画个固定长度，看着像装饰。

#### 七、验收

| 项 | 结果 |
|---|---|
| `app/build.sh` | `[build] OK`，APK 568249 字节（96 条目 / assets 84 / testzip None / md5 `f7821ae3979ff9f950e1b725242e0887`） |
| `Ver.java` / Manifest | `2.8` / `versionCode 20` |
| `live.json` | 382 台、空源台 0、台标引用 84 个且文件齐全 |
| harness `wire` | PASS（代理字节格式符合 MediaPlayer 预期） |
| harness `tabs` | PASS（全部分类都有数据且有海报） |
| harness `rank` | PASS（搜索排序符合预期） |
| harness `meta` | PASS 9 项 |
| harness `upd` | PASS 19 项 |
| harness `srctab` | PASS 23 项 |
| harness `cachetab` | PASS 34 项 |
| harness `pytab` | PASS 24 项 |
| harness `livetab` | PASS 41 项（含「空源台也保留」） |
| **Release v2.8** | CI `build` success，产物 568249 字节 / 96 条目 / assets 84 / `aapt2 dump badging` → `versionCode='20' versionName='2.8'` |

**真机未验证**（本机没法跑 Android TV，下面几条只有电视上才能确认）：

1. 「1440×1080 老剧现在铺满」—— 需要找一部确实是非方形像素编码的片子。
2. 黑屏判据的 6 秒窗口是否够（网络慢的源可能 6 秒还没解出第一帧，会被误判切台）。
3. 抽屉里的焦点在「分组胶囊 ↔ 频道行」之间能否顺畅上下移动。
4. 音量键自己拦之后，电视遥控器上那对音量键在直播页是否真的生效。

#### 八、「1920×1080 是谁定的」

老板问的这句得正面回答，分三件事，别混成一件：

**1）代码里的 1920×1080 是设计基准坐标系，不是输出分辨率。**

```java
// Ui.java:79-91
private static int screenW = 1920;      // 默认值，仅作为兜底
private static int screenH = 1080;
...
screenW = c.getResources().getDisplayMetrics().widthPixels;   // 真机读数覆盖
screenH = c.getResources().getDisplayMetrics().heightPixels;
scale   = screenW / 1920f;                                    // 等比缩放系数
```

所有 `Ui.dp()` 的尺寸都写成「1920 宽画布下的像素值」，运行时按真机 `widthPixels/1920`
放缩。电视是 1366×768 就缩 0.71 倍，是 4K 就放 2 倍 —— **画布是尺子，不是画幅**。
这个做法从 v1.0 就在，不是这轮新定的。

**2）画布定成 1920×1080 是为了对上 Stitch，属于我定的。**

Stitch 第一版稿子画布是 2560×2048（1.25:1），不是 16:9，所有绝对定位搬到电视上必然整体跑偏
—— 这才是「抄都抄不像」的真正原因。改稿时要求它锁成 `width:1920px; height:1080px`，
原因是：电视端设计稿必须有一个确定的像素坐标系，否则它给你的 `left: 380px`
在不同画布上代表不同的物理位置；选 1080P 是因为它是电视端最通用的基准档，
且我们 `Ui` 里本来就以 1920 为基准。**这个决定是我做的，老板没下过这个指示。**

**3）底条那句 `YG TV OS · 1080P 60Hz` 是装饰文案，容易误导。**

`LivePlayerActivity.java:599-601` 照设计稿原样抄了一行状态字。设计稿写它是因为画布是 1080P，
搬到代码里就成了「宣称这台机器是 1080P60 直出」—— 若电视是 1366×768 或 4K，这句话就是假的。
它不影响任何功能（纯 `TextView`），但会让人以为我们在硬编分辨率。要不要改成读真机分辨率的
实时文案，等老板定。

### 直播表：`KEEP_SOURCE_ONLY_CHS` 打开一天就回滚

v2.8 中途为了让「湖南卫视这类台消失」有兜底，把 `live_scan.py` 的
`KEEP_SOURCE_ONLY_CHS` 打开（源站出现过的台名全部保名，`urls` 留空数组），
`Live.java` 也相应改成不再丢弃空 urls 的台。

一天后实测代价：

| 项 | 关闭（HEAD） | 打开（做坏的那版） |
|---|---|---|
| `total` | 382 台 | **708 台** |
| 真有线路的台 | 382（100%） | **176（25%）** |
| 空壳台 | 0 | **532** |
| `live.json` 行数 | — | +4006 行 |

抽屉里 3/4 的台点进去都是「这个台没有可用的线路」，比少几台更难受。处置：

- `app/src/main/assets/live.json` + `logo/` 回滚到 HEAD 健康版（382 台 / 空源 0 / 台标 84 张齐全）；
- `KEEP_SOURCE_ONLY_CHS` 改回 `False`，注释写清这次实测代价。**保留** 的是 `keep` 列表那条逻辑：
  「上一版 live.json 里已有」的台名即使这次全灭也保名 —— 湖南卫视不会消失，但源站里新冒出来
  又全灭的台不再进表（空壳从 532 降到 183，且全是用户昨天真看过的台）；
- 播放页对空 urls 台的兜底（`LivePlayerActivity.java:698`、`:1163`、`:1455`）保留，不冲突。

**补源实验结论（别再走这条路）**：拿 `live_scan.py --limit 120` 抽查 120 条源，
round1 活 12 条（10%），round2 复测活 4 条（3%）；淘汰分布 `http=78 / seg=4 / kbps=33 / 其余 0`
—— 主要杀手是 **220KB/s 码率门槛**，不是连通性。四个境外镜像 IP 模板在这台机器上只剩 1 条能过
（`CCTV-3 http://74.91.26.218:82/live/cctv3hd.m3u8`）。不是模板选错，是这批镜像本身退化成
「TTFB 正常、分片拉不动」。所以：**接受「台少但能看」**，不能把 `MIN_KBPS` 降回去 —— 那正是
「10 个台 7 个卡」的成因。


### v2.7.1：修 CI 漏打包 assets（v2.7 的 Release 里台标一张都没有）

v2.7 发出去之后核对产物才发现：**GitHub Release 上的 APK 只有 136049 字节，本地构建是 560057 字节**，
差的正是 `app/src/main/assets/logo/` 那 84 张台标。

根因：`aapt2 link` 少了 `-A build/assets`。之前只改了本机用的 `app/build.sh`，
**CI 走的是另一份 `app/build-ci.sh`**，两条构建链各写一遍参数，改一条漏一条。

| 项 | v2.7（坏的） | v2.7.1（修好） |
|---|---|---|
| `app/build-ci.sh` link 参数 | 无 `-A` | `-A build/assets` |
| Release APK 大小 | 136049 字节 | **560057 字节** |
| APK 内 `assets/` 条目 | **0** | **84**（总条目 96） |
| 电视上看到的台标 | 全是短标签色块 | 90 台真实台标 |

修法：`build-ci.sh` 的 `[2/6] aapt2 link` 之前加 `rm -rf build/assets` /
`mkdir -p build/assets` / `cp -r src/main/assets/logo build/assets/logo`，
参数里补 `-A build/assets`，与本机 `build.sh` 完全一致。
版本号提到 **2.7.1 / versionCode 19**，这样电视上的「检查更新」能识别到这次修复。

验收：`gh release download v2.7.1` 拉下来的 APK → `zipfile` 条目 96 / assets 84 /
`testzip()` 返回 `None`；`aapt2 dump badging` 显示 `versionCode='19' versionName='2.7.1'`。

> 教训：**构建脚本有两份就必须同步改**，发版后要拿 Release 产物本身复核字节数和 assets 条目，
> 不能只看 CI 是绿的 —— CI 绿只代表「跑完了」，不代表「打进去了」。

### v2.7：抄设计稿、修画面截断、直播台标 + 源质量

老板原话：

> 1、UI界面怎么那么low啊，我看了stitch的设计稿，人家设计的那么好看，你怎么不抄呢？优化一下！
> 2、电视直播画面播放不正常，像是放大截断了，是不是跟之前影视一样的渲染位置的问题？找出问题优化一下；
> 3、电视直播咋一个海报都没有，全是黑框框，丑死了，而且好多源都特别卡，你没做筛选吗？优化一下。

#### 一、「low」的不是配色，是层次

先核对了 Stitch 项目 `9584627652671101173`「YG影视 TV」的设计系统，`customColor #ffb300`、
`neutral #0e1116`，跟 `Ui.java` 里原有的 `ACCENT=0xFFFFB300` / `BG=0xFF0E1116` **完全一致**。
所以问题不在调色板，而在于这套色板没被用出层次。逐项比出来的差距：

| 项 | 设计稿 | v2.6 实际 |
|---|---|---|
| 页面底色 | `radial-gradient(circle at 50% 35%, #151A22, #0E1116)` | `setBackgroundColor(Ui.BG)` 纯色 |
| 品牌标识 | 40×40 琥珀渐变圆角方块嵌 `YG` + 「极清影院」字标 | 一个裸文字 `YG` |
| 板块切换 | 内凹胶囊容器（`#151A22` 底 + 描边）包两个 pill | 两个 8px 圆角按钮并排 |
| 页面主标题 | 38px 特大标题 + 内联小标签 + 副标题 | **完全没有** |
| 焦点态 | `outline 3px + 0 0 28px 外发光 + 投影` 三件套 | 只有 3px 描边 |
| 底部 | 键帽 chip + 说明 + 右侧在线状态点 | 一行灰色提示文字 |
| 直播卡 | CH 编号 + 画质角标 + 台名 + 副名 + 源数 + 台标 | 6px 竖条 + 居中台名 + N 源 |

逐条落地（新增 `Chrome.java` 承载共享零件，直播/影视两页共用同一套语言）：

- `Ui.pageBg()`：`RadialGradient(50% 35%, 半径 0.78×屏宽, #151A22 → #0E1116)` 的自绘 Drawable，
  四个 Activity 全部换成它。
- `Chrome.brandBlock()` 渐变方块品牌 + `brandText()`「极清影院」+ `vDivider()` 竖分隔。
- `Chrome.boardCapsule()` 内凹胶囊容器 + `boardPill()` 选中态带外发光。
- `Chrome.title()/inlineTag()/subtitle()`：影视页补「影视大厅 / VOD · 多源聚合 / 14 个采集源…」，
  直播页补「电视直播 / LIVE · IP-TV / 多源自动切换…」。
- `Ui.focusBgGlow()`：`LayerDrawable` 两层 —— 外层 5px 描边当光晕（inset -2px），
  内层实底 + 2px 描边当主体。卡片、胶囊、选集格全部换过去。
- `Chrome.keyHintRow()` + `Ui.keycap()`：底部键帽条，OK 键用琥珀底深色字。
- `Chrome.statusDot()`：右侧绿点在线状态。
- 直播卡重做成四层：`CH 001` + 画质角标 / 台标 + 台名 + 副名 / 「当前聚焦」+「N 源」；
  焦点卡换 `#1F2530` 底、scale 1.045、`ObjectAnimator` 呼吸点。
- 分组条改胶囊（圆角 999、高 44、17px bold），选中琥珀实心 + 外发光。

#### 二、直播「放大截断」的三处真因

`PlayerActivity.FitVideoView` 本来就写得对，直播页却从没调用过它：

1. **从没设过缩放模式**。`LivePlayerActivity` 只 `new FitVideoView` 就加进容器，`mode` 停在默认
   `MODE_AUTO` + `maxCrop=0.34`。直播频道混着 4:3 和 16:9，34% 的容差足以把 4:3 拉成铺满，
   上下直接切掉。→ 直播页默认改 `MODE_FIT`（完整显示），容差收到 `0.12`，红键循环三档。
2. **靠反射读 `mVideoWidth` 有竞态**。`onMeasure` 里反射拿的是上一帧的值，直播起播快、尺寸晚到，
   第一次测量常常拿到 0 → 退回 `super.onMeasure` 按容器铺满；拿到尺寸后又没人再触发
   `requestLayout` → 一直停在满屏值。→ `FitVideoView` 加 `setVideoSize(w,h)` 显式喂尺寸，
   `onPrepared` 和 `MEDIA_INFO_VIDEO_RENDERING_START` 各喂一次 + `requestLayout`，
   `onMeasure` 优先用这个值，反射只做兜底。
3. **父容器默认 `START|TOP`**。铺满时视图比容器大，不居中的话溢出全落在右下，
   看起来就是「只截掉一边」。→ `addView(vv, ..., Gravity.CENTER)`。

另加看门狗（2 秒一轮）：起播 15 秒未 prepared / 缓冲 9 秒未结束 / 位置 12 秒不前进，
三种情况自动换下一路，跳过次数上限 `urls.size()*2`。

#### 三、直播「全是黑框」与源卡

**黑框**：`live.json` 里 428 个频道的 `logo` 字段**全是空串** —— `live_scan.py` 从来没抓过台标，
`LiveActivity` 也从没画过 `ImageView`，只有文字。所以那不是「海报没加载出来」，是压根没做。

改法（四条都做）：

1. 直播卡里塞进真实台标 `ImageView`，走 `Ui.loadPoster()`，拿不到就回退频道短标签色块。
2. `live_scan.py` 从 `iptv-org` 的 `channels.json` + `logos.json` 建索引抓台标，
   纯 Python（zlib）重编码成 96×54 PNG，写进 `app/src/main/assets/logo/`，`live.json` 里
   写成 `asset:logo/<md5前12位>.png`；`Ui.assetBitmap()` 走 `getAssets().open()` 本地解码。
3. **透明底被压成黑**：台标 PNG 是透明底的，`assetBitmap()` 原本按 `RGB_565` 解码 ——
   丢 alpha 后透明区变纯黑，整张图就是一个黑洞。改 `ARGB_8888`，并加 `ensureVisible()`：
   先合成到与卡片同色（`SURFACE_HIGH`）的不透明底上，再按「不透明像素平均亮度 < 110
   或暗像素占比 ≥ 45%」垫一层很淡的白。实测 CETV-1 的不透明像素里 57.9% 亮度低于 60，
   不垫白贴在深色卡上等于隐形。
4. **台标框改 16:9**：iptv-org 的台标本来就是 96×54，塞进正方形只用到 62×35，字全糊；
   改成 `logoH = cardH*0.40`、`logoW = logoH*1.62`、`FIT_CENTER`。

没台标的台（382 台里 292 台）也不能全画同一个大字母：`Ui.channelTile()/channelLabel()`
按台名生成短标签 —— `CCTV-8 → 8`、`CCTV-5+ → 5+`、`CGTN纪录 → 纪`、`湖南卫视 → 湖南`、
`广东体育 → 体育`，其余取前两字。实测 382 个台名生成 241 个不同标签（取首字只有 206 个，
且「浙」一个标签重复 22 次），色块底走 8 色哈希调色板。

**顺带挖出一个更严重的坑**：`app/build.sh` 的 `aapt2 link` 原来**没有 `-A` 参数**，
`assets/` 从来没被打进 APK（旧 APK 只有 12 个条目，一个 assets 都没有）。
也就是说凡是走 `asset:` 的东西在电视上都必然读不到。已补上 `-A build/assets`，
并且只拷 `assets/logo` 到临时根目录 —— `py/*.txt` 那 4.6MB 只给 CI 用，不能进包。
验证：塞一张 8×8 测试 PNG → 重跑 → `zipfile.namelist()` 里出现 `assets/logo/__selftest.png`
→ `zipalign -c -v 4` 报 `Verification succesful`。

**源卡**：v2.6 的筛选判据只有「`200` + 首 4KB 含 `#EXTM3U`」，那只是「连得上」，
不代表「播得动」。v2.7 的巡检加了三道：

- **TTFB 上限 3 秒**：超过就直接淘汰（用户按下遥控器 3 秒没画面就觉得电视坏了）。
- **实际吞吐**：取 m3u8 里真实分片下 256KB，算 KB/s，**低于 40KB/s（约 320kbps）淘汰**——
  那个码率连 480p 都撑不住，播出来就是幻灯片。
- **两轮复测**：间隔一轮再探一次，只有两轮都活着的才留（昨天能播今天 403 的源太多）。

巡检结果：1757 条候选 → 留存 382 台 / **573 条线路**（v2.6 是 428 台 / 669 条）。
台数变少是筛选变严的必然结果，换来的是「点开就能看」的概率。台标命中 **90/382（23.6%）**。

**台标覆盖率这一步单独说**，因为第一次只做到 41 台（10.7%），查下去发现不是脚本笨：
iptv-org 的省级卫视条目 `alt_names` 大多是空数组（`GuangdongSatelliteTV`/`HainanSatelliteTV`/
`HubeiSatelliteTV`/`SichuanSatelliteTV`/`YanbianSatelliteTV`/`HenanTVSatellite`/`HunanTV`
全是 `[]`），而源站给的台名是纯中文「广东卫视」——一个归一化成 `GUANGDONGSATELLITETV`、
一个是「广东卫视」，fuzzy 怎么调都对不上。所以加了一张 `MANUAL_LOGO_IDS` 硬映射表
（`test/live_scan.py`），把中文台名直接对到 iptv-org 的 channel id，**每条都手工核实过
「logos.json 里确实有这个 id 的图 + 名字确实是这个台」**：35 个省级卫视、CCTV-4/CCTV-5+
（这两个也必须手写 —— CCTV-4 被拆成亚洲/欧洲/美洲三版，CCTV-5+ 归一化后会被 CCTV-5
抢先占位）、CGTN 五个语种、6 个港澳台、11 个省级地面、CHC 电影。

效果：匹配 48 → **100 个（26.2%）**，其中手写映射贡献 **66 个**；实际落盘 **84 张 PNG**。
抓不到就写空串走短标签色块，**不硬凑错图** —— `人间卫视`、`安多卫视` 在 iptv-org 里
确实没有台标，宁可空着。

#### 验收

| 项 | 结果 |
|---|---|
| `app/build.sh` | `[build] OK`，APK **560057 字节**，dex 200088 字节 |
| APK 内 `assets/logo/*` | **84 个条目进包**（`zipfile` 实测），总条目 96 |
| `live.json` ↔ `logo/` 一致性 | 90 条引用（含沿用旧图）、**0 缺失、0 孤儿** |
| `test/harness/run.sh wire` | PASS（21 项，代理字节格式符合 NuPlayer 预期） |
| `test/harness/run.sh tabs` | PASS（24 分类全部有数据且有海报） |
| `test/harness/run.sh livetab` | PASS（40 项，含新增 `aliasOf`） |
| `meta` / `upd` / `srctab` / `cachetab` / `pytab` / `rank` | 全部 PASS |

**已知未解决**：`live.json` 里有 16 个台名出现在两个分组（`河北卫视` 在 sat+local、
`广东体育` 在 sports+local、`浙江科教影视` 在 movie+local 等），是巡检脚本分类规则重叠，
不影响播放，下次调分组规则时一起修。

真机未验证：径向渐变在 API 22 上的渲染开销、`RadialGradient` 在 1080p 上的实际观感、
`ObjectAnimator` 呼吸点在低端芯片上的掉帧情况、台标 `ARGB_8888` 解码（84 张 96×54 约 1.7MB
内存 + `ensureVisible` 每张再合成一份，理论上无压力但没实测）。

### v2.6：电视直播板块 + 双板块 UI

老板原话：

> 1、新增一个电视功能，你去GitHub上找找高速电视源，集合进去，要高速、流畅，整合多源，方便卡的时候换源；
> 2、再优化一下UI，排版有点冗余，没必要把24个频道都显示出来，这也显得画面整体很拥挤小气
> 建议改成横向滚动一行，只露 6 到 8 个，其余滑动查看，可以找stitch重新设计个简约版的，
> 整体就做两个版本，一个电视板块、一个影视板块，要高端、大气、科技感一点；

#### 一、直播源：不是「接个列表」就完事

公共 IPTV 清单都是几千行的 `频道名,url`，照抄进来能播的不到一半。实测：

| 项 | 实测值 |
|---|---|
| 源1 Guovin `result.txt` | 1619 行 / 40 分组 |
| 源2 vbskycn `iptv4.txt` | 551 行 / 12 分组 |
| 去重后唯一 URL | **1757** 条（丢掉 IPv6 67 条、`更新时间` 分组 3 条、非法 3 条） |
| 探测结果 | **可播 721 条 = 41.0%**，24 线程 157 秒 |
| 最终入库 | **428 个频道 / 669 条 URL** |

所以 `test/live_scan.py` 的判据是「真能播」而不是「列表里有」：

- `status == 200` **且** 首 4096 字节含 `#EXTM3U` —— 只看连通性会被 https 劫持站和 302 骗过去
- TTFB 取**首块数据到达**时刻，不是请求完成时刻。否则一个 6 秒才发完的源会排在 200ms 的正常源前面
- 同台最多留 3 条按 TTFB 升序 —— 这就是「卡了换下一个」的备用池
- 全量探测 < 20 个台可播时**保留旧文件、退出码 0**，CI 不会因为源站集体抽风就把直播页清空

分组从 41 个收敛成 7 个（`cctv` 36 / `sat` 39 / `sports` 6 / `movie` 28 / `hkmotw` 9 / `local` 267 / `other` 43）。
`kids` 动画组是 **0**：源里动画那 54 条单探 40 条，全是 migu token 中转和已失效的 freetv.fun，403 或连不上 ——
是源的问题，不是筛选逻辑，等有可用源再放开。

#### 二、换源是这一页存在的核心理由

直播和点播最大的差别：点播选错了源，换个播放器还能播；直播源挂了就是黑屏，**必须一键换**。
所以 `LivePlayerActivity` 三个入口都能切，而且互不冲突：

| 操作 | 行为 |
|---|---|
| 菜单键 | 呼出线路面板，选具体某条 |
| ← / → | 直接切上一条 / 下一条 |
| CH+ / CH-（码 166/167） | **换台**，不是换源 —— 频道键按电视惯例就该换台；换源交给 ←→ 和菜单键 |
| 一条播挂了 | **静默自动切下一条**，用户几乎无感 |
| 三条全挂 | 才提示「该频道暂时看不了，试试别的台」 |

为什么不复用 `PlayerActivity`：那边一整套是为点播写的 —— 进度条、断点续播、`onCompletion` 自动下一集、
选集浮层。直播没有「总时长」，`onCompletion` 一触发就去播下一集，反而把正常播放打断。
所以直播单独写一份薄的：`VideoView` + 换源 + 换台，**没有进度条和时长**。

#### 三、UI：24 个频道改成只露 6.5 个

老板说的是「24 个频道全显示出来显得拥挤小气」。改法不是简单加个滚动，而是**定宽**：

```java
// 一屏完整露 6 个，第 7 个被右边缘裁一半
int tabUsable = Ui.w() - Ui.px(104) - (int)(tabGap * (TAB_VISIBLE - 1));
int tabW = Math.max(Ui.px(120), Math.round(tabUsable / TAB_VISIBLE));
```

`WRAP_CONTENT` 的 24 个胶囊长短不一，即使能滑也显得碎；定宽之后节奏一致，而右边那半个
就是「还能往右滑」的天然提示。`TAB_VISIBLE = 6.5f` 是特意留的半个 —— 整数个会让人以为「就这些了」。

顶栏也从「YG影视 + 24 个频道」改成 **`YG` + 双板块胶囊**：

- 「电视」透明底 → 点它进 `LivePlayerActivity`（v2.8 起直达播放，挑台用 OK 键呼出右侧抽屉）
- 「影视」实心琥珀 → 当前所在
- 两个板块平级、各占一屏。**没有把直播塞成第 25 个频道** —— 那样频道条只会更长，正是被吐槽的那个问题

设计稿在 Stitch 项目 `9584627652671101173`（「Cinematic Cinema 10-Foot Experience」设计系统：
DARK / INTER / ROUND_EIGHT / `#ffb300`，画布 1920×1080，overscan 横 56px / 纵 40px，
焦点 `scale(1.06)` + 3px 琥珀描边 + 4px outline-offset）。落地时全部复用 `Ui.java` 已有的设计令牌，
没有引入新颜色，所以两个板块看着是同一套东西。

#### 四、直播表同样是「数据化 + 每日巡检」

照 v2.4 点播源表那套做，一行没改：

```
GitHub Actions 每天 02:00
  ├─ source_scan.py  → sources.json   （点播源，14 个）
  ├─ pinyin_index.py → py/*.txt       （首字母表，23 桶 4.6MB）
  └─ live_scan.py    → live.json      （直播源，428 台 669 条）  ← v2.6
  ↓ 有变化才 commit
raw.githubusercontent.com/.../assets/*
  ↓ App 启动时拉
LiveSync → Live.load()（六层容错，0 组则整体放弃保留内置）
```

`Live.load()` 的六层容错和 `Src.load()` 一个思路，但多一条直播特有的：**URL 里带 `[` 的一律丢**
（IPv6 地址在 API 22 的 NuPlayer 上直接连不上，实测 67 条）。另外 `urls` 数组在 harness 替身上
没有 `optString(int)`，所以是「把整个数组当文本读、剥方括号、按逗号切、碎片不以 http(s) 开头就并回上一条」——
这样 URL 里带逗号（`?a=1,2`）也不会被切坏。

#### 五、验收

| 套件 | 结果 |
|---|---|
| `bash run.sh livetab` | **40/40 PASS**（不联网：坏 JSON / 缺 groups / 全坏项 / IPv6 清洗 / 快照副本 / 被放弃的 load 不动 seq） |
| `bash run.sh`（Harness） | 16/16 PASS |
| `bash run.sh wire` | 21/21 PASS |
| `bash run.sh tabs` | 24 个分类全部有数据且有海报（共聚合 1611 条） |
| `bash run.sh rank` / `meta` / `upd` | PASS / 9 项 PASS / 19 项 PASS |
| `bash run.sh srctab` / `cachetab` / `pytab` | 23 / 34 / 24 PASS |
| 直播真机采样 | 随机 14 条 → **14/14 可播**，最快 33ms、最慢 1310ms |

出包：APK **123761 字节**（v2.5 是 111473），dex **176628 字节**（v2.5 是 149200），
`versionCode='17' versionName='2.6'`，`minSdkVersion 21` / `targetSdkVersion 22` 未变。

#### 六、实发记录

- 提交 `81488bf`「v2.6：电视直播板块 + 双板块 UI」已推 `origin/master`，tag `v2.6` 已推。
- CI run [`43372349140`](https://github.com/XACPPP/yg-vodtv/actions/runs/43372349140) success（36 秒）。
- Release <https://github.com/XACPPP/yg-vodtv/releases/tag/v2.6>，资产 `YGTV.apk` / `YG.TV.apk` 均 **123761 字节**。
- Release notes 已用 `gh release edit v2.6 --notes-file` 换成完整 changelog（本文件第 217 行起的同款内容）。

### v2.5：拼音首字母能搜到了

老板原话：「我搜伟大的长征，搜的拼音缩写 wddcz，出来的结果都没有这部剧」，
接着又一句：「搜索出结果很慢，有的时候可能都要几秒，你要不然去 GitHub 上抄抄别人是怎么做拼音首字母搜索的，比如 TVBox 那套」。

#### 一、先证明「这件事抄不到」

按老板的意思去翻 TVBox 系仓库，结论是**没有可抄的实现**：

| 查了什么 | 结果 |
| --- | --- |
| `FongMi/TV` 全仓库树（`git/trees?recursive=1`，499180 字节） | 搜 `inyin`/`PinYin` 路径**命中 0 个**，连拼音工具类都没有 |
| `takagen99/Box` 全仓库树（257636 字节） | 只有 `ui/adapter/PinyinAdapter.java`，那是**列表按拼音排序**的适配器，跟搜索无关 |
| `raw.githubusercontent.com/.../PinyinUtil.java` | 404 |

真正的解法不是抄代码，是搞清**服务端到底认什么**。用 `test/source_scan.py` 的 `get()` 直连 14 个源实测：

| 输入 | bfzy | 其余 13 源 |
| --- | --- | --- |
| `wd=wddcz`（首字母） | 20 条噪声：我的独裁者 / 伟大的转折 / 温暖的村庄…，**没有目标** | 全 0 条 |
| `wd=weidadechangzheng`（全拼） | **1 条，正是《伟大的长征》** | 全 0 条 |
| `wd=伟大的长征`（中文） | 20 条（含目标） | **11 个源命中** |

也就是说苹果CMS 只给 `vod_en`（全拼）建了索引，**首字母这条路服务端根本不存在**。
再试加 `limit=100`（拿 40 条，是站点硬顶）、`pg=1..5`（pg3 起全空）、把 `wd` 缩成 `wddc`/`wdd`/`wd`——
40 条里始终没有《伟大的长征》。也试过 `suggest?mid=2`（空）、`mid=0`（参数错误）、
`/index.php/vod/search.html`（HTML 站内搜索页）、`/vodsearch/xxx.html`（404），**没有一个现成的首字母接口**。

#### 二、所以自己建索引，而且是拿 `vod_en` 切出来的

关键发现：**14 个源的 `?ac=list` 全部自带 `vod_en`**（全拼，如 `伟大的长征 → weidadechangzheng`）。
于是根本不需要中文转拼音的 2 万字库，只要把 `vod_en` 按音节切开、每个音节取首字母：

```
weidadechangzheng → wei/da/de/chang/zheng → wddcz
jiamianguilai     → jia/mian/gui/lai      → jmgl
```

`test/pinyin_index.py`（249 行）做这件事：410 条无声调拼音音节表 + 从后往前 DP 做最大匹配，
先把 `vod_en` 过滤成只剩 `a-z`（原始值里有 `121212`、`nitianxieshen3D`、`nishishijiancuican_9a6139` 这类脏值）。
按首字母分 23 个桶写 `app/src/main/assets/py/{a..z}.txt`，每行 `首字母<TAB>中文片名`。

| 指标 | 实测值 |
| --- | --- |
| 全库翻页 | 8584 页 / 171667 条，20 线程，**297.9 秒**（约 5 分钟） |
| 去重后写入 | **145202 条**（重名率极低，146112 → 145187 只掉 925 条） |
| 索引总体积 | **4784607 字节（4.56 MB）**，23 个桶 |
| 最大的桶 | `s.txt` 15740 条 / 741785 字节；其次 `w.txt` 10519 条 / 345997 字节 |
| 最小的桶 | `o.txt` 150 条 / 4788 字节 |

为什么是纯文本不是 gzip：gzip 每次重建整份文件都变，git 只能整份存副本，日更一年几百 MB 仓库体积；
纯文本按行排序，新增几条只产生小 delta，而 GitHub raw 本身对文本就做 gzip 传输，客户端实际拿到的字节数差不多。

#### 三、App 侧只下自己那一桶

`PyIndex.java`（317 行）：`lookup("wddcz")` → 只看首字母决定下 `w.txt`（346 KB，下过一次永久缓存在 `/cache/py/`）→
`pick(body, "wddcz", 8)` 扫两轮：第一轮收 key **完全等于**输入的，第二轮才收「以输入开头」的长 key。
两轮的理由是「头号候选查不到就退下一个」——`wddcz` 的两条必须先于 `wddczxxx` 这种长 key 出来。

`Multi.search()` 也跟着改了两处：

1. **首字母先翻中文名**：`ascii` 且索引命中时 `query = cand.get(0)`，各源走 `Api.wd(s, query)` 而不是 `suggest`。
   排序关键词也用翻出来的中文名（`rankKw`）——拿 `wddcz` 去比中文片名必然 100 分，`rank`/`prune` 会全失效。
2. **不再死等 10 秒**：`awaitSearch` 的判据从「5 个源有结果」改成三重——全部线程结束 / 凑够 21 条 /
   已有结果且 1200ms 内没有新增。旧判据在首字母场景下必然等满 `SEARCH_BUDGET=10000`：全 14 个源只有 bfzy 有货，
   永远凑不满 5 个，哪怕它 810ms 就回来了。

首字母全落空时还会拿索引里的第二个候选再试一轮（只在这时多等，有结果的情况绝不加延迟）。

#### 四、验收

| 套件 | 项数 | 覆盖 |
| --- | --- | --- |
| PyTab | 24 | `PyIndex.pick` 纯函数：两条 `wddcz` 且正主在前、前缀天然包含、精确 key 优先、cap 上限、脏行、去重、CRLF |

`app/build.sh` / `build-ci.sh` 的 class 清单跟着补到 **21 个**（新增 `PyIndex`）。
`.github/workflows/scan.yml` 加了「重建拼音索引」步骤（`timeout-minutes: 20` + `continue-on-error: true`，
站点挂了不算错、索引保持上一版），提交步骤加 `git add -A app/src/main/assets/py`。

#### 五、实发记录

- CI run [`43365961024`](https://github.com/XACPPP/yg-vodtv/actions/runs/43365961024) 一次通过（38 秒）。
- Release：<https://github.com/XACPPP/yg-vodtv/releases/tag/v2.5>。
- 资产 `YGTV.apk` / `YG.TV.apk` 均 **111473 字节**，sha256 `70c74df0177fa739715a8924eef014fccd8ad23b3a6010a89978659918943f2a`。
- 索引推上去后 `https://raw.githubusercontent.com/.../assets/py/w.txt` 实测 **200 / 345997 字节**，
  用它跑一遍 `pick("wddcz")` → `['伟大的长征', '我的独裁者']`，正主在第一位。

### v2.4：源表热更 + 消灭「加载中」和「海报一张张跳」

老板原话：「我觉得可以重构一下，GitHub 上的 TVBOX 很成熟啊」→ 讨论后否掉二开；
后来「我其实想要的是无感预加载，因为现在打开任一界面其实还是偏慢，会出现加载中的提示」。
这一版只解决两件事：**换源不再发版**，**首屏不再一张张出图**。

#### 一、为什么没有二开 TVBox

| 项目 | 星数 | 能不能装在这台电视 | 结论 |
| --- | --- | --- | --- |
| FongMi/TV | 10470 | `minSdk = 24`（Android 7.0），**API 22 装不上** | 否决 |
| takagen99/Box | 3105 | `minSdk 21` 能装，但 2026-02-27 起停更 | 否决 |
| q215613905/TVBoxOSC | 21 | 2022-07 停更 | 否决 |

更关键的是内容结构：实测一份主流 TVBox 配置（`sites=135`）里 **111 个是 type=3 csp 爬虫源**，
依赖 QuickJS/Node 运行时，只有 **22 个 type=1 苹果CMS 直连**。TVBox 生态里最值钱的网盘/弹幕/解析
能力，恰好全在 Android 5.1 上成功率最低的那半边。**结论：抄协议不抄代码。**

#### 二、源表热更（`Src.java` + `SrcSync.java`）

源的定义从「编译进代码的常量」变成「一份可以远端替换的 JSON」：

| 项 | 做法 |
| --- | --- |
| 内置表 | `Src.BUILTIN`（原 `SPEC` 改名的，14 行没动内容），编译进 APK，**永远兜底** |
| 远端表 | `SrcSync.URL` 指向仓库里的 `app/src/main/assets/sources.json`（走 `raw.githubusercontent.com`） |
| 生效方式 | `Src.load(jsonText)` 整体替换源表；**远端坏了等价于什么都没发生** |
| 换源 / 加源 / 调优先级 | 改 JSON → 推上去 → 电视下次开机自动生效，**不用发版** |
| 自检页 | 第 0 步「网络参数」会带上 `内置 14 / 当前 14 / 远端 11-13 09:51`，两个数字不一样才说明远端表真生效 |

> 注意：`assets/` **没有打进 APK**（`build.sh` 的 aapt2 link 没带 `-A`）。这个 JSON 的用途是
> 给 GitHub raw 当远端源、给 CI 巡检当输出物，App 靠 `SrcSync` 联网拉，不是编译期内置的。
> 真正的编译期兜底是 `Src.BUILTIN`。

`Src.load()` 六层容错，每一层都是为了让「远端出问题」等于「什么都没发生」：

1. null / 太短 → 直接 return，源表不动
2. JSON 解析抛异常 → `catch (Throwable)` 吞掉，保留内置
3. 没有 `sources` 数组 / 数组为空 → return
4. 逐项校验（`key` 非空、`api` 含 `://`），坏项只丢这一条
5. `types` 为空串 → 补 `"0"`（不补的话 `"".split(",")` 会让 `Integer.parseInt` 抛异常，整条源被丢）
6. **合法项为 0 → 整体放弃**（防止远端写了个空数组把 App 变砖）

`Src.all()` 和 `byKey()` 现在返回**副本**而不是 `ALL` 本体：`SrcSync` 在后台线程 clear+addAll，
`Multi` 在另一个线程按索引遍历，直接暴露本体就是 `ConcurrentModificationException` / 越界。
`Multi.list()` / `Multi.search()` 也都改成开头只取一次快照。

#### 三、巡检流水线（`test/source_scan.py` + `.github/workflows/scan.yml`）

GitHub Actions 每天北京 02:00（`cron: '0 18 * * *'`，源站负载最低的时段）跑一次：

```
14 个源 + 外部候选池
   → 七项探测：list 延迟/条数/海报/简介/分类、detail 选集、search、suggest、play（m3u8 TTFB + 首分片 kbps + Range）
   → 100 分制打分：速度 45 + 内容 35 + 能力 20
   → 硬失败（列表非 JSON / 无数据 / 无选集）判 DEAD，不参与排序
   → 播放链路没测成判 OK_NOPLAY：仍写入源表，只用折中分排在测通的源后面
   → 按分数降序写 app/src/main/assets/sources.json（顺序就是 App 的源优先级）
   → v2.5：再跑 test/pinyin_index.py 重建拼音首字母表（bfzy 全库 8584 页 → assets/py/*.txt）
   → 有变化才 git commit && push
```

三道保险：**全 DEAD 时不写文件**（避免把线上打成空表）；脚本里强制 IPv4
（`socket.getaddrinfo` 打补丁传 `AF_INET`），因为电视端 `Http.java` 设了 `preferIPv4Stack=true`，
巡检必须和电视同视角，否则 IPv6 侧的假 DEAD 会把好源踢掉；**播放探测不做硬失败**
（见下一节）。

#### 播放链路为什么不参与判死（2026-11-13 线上事故）

`scan.yml` 第一次真跑（run `43355683398`）就把源表从 14 源削到了 9 源，
被砍掉的 5 个是 `uku` / `zuidazy` / `ffzy` / `dytt` / `cmov`，错误全是同一句：

```
m3u8 http 403
```

但这 5 个源的 `list` **完全正常**（20 条、海报 20/20、简介 15~19/20、片库 6~12 万），
而且在本机（走同一条 Impwrt Passwall，和电视同路径）逐个复测 `m3u8` **全是 200**，
换任何 Referer（源站参考 / 留空 / m3u8 自己域名）都 200 —— **不是防盗链**。

`ffzy` 和 `cmov` 的 m3u8 落在同一个主机 `vip.ffzy-plays.com`，`dytt` 在 `vip.dytt-luck.com`。
结论：**这几个播放 CDN 按地区整片 403，而 GitHub runner 在境外**。

所以现在的判据是：`list` 拿不到 / 选集解不出来 → 结构性坏了 → DEAD；
播放探测失败但前两项正常 → `OK_NOPLAY` → **照旧写进源表**，
只是打分时 TTFB 给 6/12、kbps 给 4/8 的中间值，让它排在真测通的源后面（实测下来
`ffzy` 68.3、`dytt` 68.6、`cmov` 67.0，都是 B 级，不会沉底，也不会挤掉 A 级）。

顺带还修了 `probe_list` 的重试次数：`yhzy` 实测首问 10.4s 超时、第二问 10.3s
又超时、第三问 2.2s 才 200 —— 原来只重试一次，仍会把它判 DEAD。现在给三发，
超时逐级放宽到 20s / 30s。

#### 四、消灭「加载中」：列表 JSON 落盘（`Cache.java` + `Multi.raw`）

以前列表 JSON 只活在内存里，`renderPage()` 用完即丢 —— 这就是「切个频道就转圈」的根因。

现在第一页落盘到 `cacheDir/list/{ch}_{pg}.json`（16MB 上限 / 12MB 触发清理，实际只用 6.5MB）：

| 时机 | 行为 |
| --- | --- |
| 切 Tab / 开机 | **先读盘 → 同步铺满画面**（一个「加载中」都不给看）→ 再发网络请求 |
| 后台返回 | 内容跟盘上一样（`before.equals(p.raw)`）→ **不重画**，避免闪一下 |
| 后台返回空 | **不清空已有画面**（网络抖一下不该把满屏海报换成一句「没取到数据」） |
| 加载中又点了别的频道 | 记在 `wantType` 里，这一轮回来立刻补一刀（以前直接 `return`，后点的完全没反应） |
| 切回上次的频道 | `Store.tab()` / `saveTab()` 记住冷启动的频道 |

`Prefetch.java` 在空闲时单线程预热**相邻两个频道**的首页 JSON（`cur-1` / `cur+1`，
每个 87KB，成本几乎为零，间隔 400ms）。

#### 五、消灭「海报一张张跳」：批量解码 + 一次上屏（`Ui.java`）

这个问题跟有没有缓存**无关**。真凶是提交模型：`pump()` 逐张调 `startLoad()`，
`startLoad()` 对每张图单独 `DISKPOOL.execute`，而 `DISKPOOL` 只有 2 条线程 ——
42 张首屏 = 42 个任务 + 42 次 `MAIN.post`，叠着出现是必然的。

| 改动 | 效果 |
| --- | --- |
| 新增 `READY` 队列 + `pushReady()` + 单次 `FLUSH` | 一批图**在一个主线程消息里**全部 `setImageBitmap`，整屏同时出现 |
| `pump()` 重写 | 内存命中直接入队，其余**汇成一个** `DISKPOOL` 任务循环解码，未命中才走网络 |
| `fromDisk()` 加 `inPreferredConfig = RGB_565` | 一张从 384KB 降到 192KB，48MB 的 LRU 从装 128 张变 256 张 |
| `applyToWaiting()` 改走 `pushReady()` | 预取下完的图也批量贴，不再一张一条消息 |

`FLUSH` 里先持锁把队列拷走再解锁，真正的 `setImageBitmap` 在锁外做；每张都校验
`url.equals(iv.getTag())`，卡片被复用了就丢掉这张，不会贴错图。

**验证判据**：进推荐页等图出来 → 返回 → 再进，必须**整屏一次出**；清缓存后首次允许逐张，
下载完再进同样要整屏出。

#### 六、新增两套离线验收（`SrcTab` / `CacheTab`）

v2.4 改的两处都是「失败时很安静」的地方：源表解析错了电视会直接用内置表（看不出来），
缓存编解码错了表现为「有缓存却点进去是空页」。所以各配一套不联网的 JVM 断言，
和既有的 Harness/Wire/Tabs/Rank/Meta/Upd 一起跑，加上 v2.5 的 PyTab 总共 9 套：

| 套件 | 项数 | 覆盖 |
| --- | --- | --- |
| SrcTab | 23 | `Src.load()` 六层容错、types 两种形态、坏项只丢一条、`all()` 返副本 |
| CacheTab | 34 | `Multi.encode/decode` 17 字段往还原、脏字符转义、7 组坏输入不抛、`Cache` 落盘不串 |
| PyTab | 24 | `PyIndex.pick` 精确 key 优先于前缀、cap 上限、脏行、CRLF、去重（v2.5） |

#### 七、实发记录

- CI run [`43355284246`](https://github.com/XACPPP/yg-vodtv/actions/runs/43355284246) 一次通过
  （35 秒，`校验 tag 与 Ver.NAME 一致` → `构建 APK` → `发 Release` 全绿）。
- Release：<https://github.com/XACPPP/yg-vodtv/releases/tag/v2.4>（2026-11-13 10:53）。
- 资产 `YGTV.apk` / `YG.TV.apk` 均 **107377 字节**，CI 侧 md5 `19b2e6fb1ebfcd091bf27ff0b03381d0`；
  本地出包同尺寸（md5 因 zipalign 时间戳不同而不同，属正常）。

### v2.3：换掉那个「憨批」图标

老板原话：「这个 APP 图标是不是有点憨批了，太简陋了吧」。原来是一个 192×192 的纯蓝圆角方块
加中间 14px 的白字「YG」——48px 下那两个字糊成一团白点，等于没有图标。

前后试了 8 轮：琥珀播放键 → 胶片框 → 光阑 Y → 连体粗臂 Y → Q 版人脸。中间两个坑值得记下来：

- **光阑 Y 不能做**：三片分离扇区 + 中心圆点，是辐射标志的国际通用造型，第一眼就读成「核辐射」。
- **深色底等于隐形**：深色图标在深色启动器上直接消失。手机桌面大多是深色壁纸，这条否决了
  「深底 + 琥珀标记」这一整类方案。

最后定的是**紫粉渐变底 + Q 版机器人**（`test/make_icon_robot.py` 生成，纯 Pillow 画，不引外部图）：

| 部分 | 取值 |
| --- | --- |
| 底 | 深紫 `#4A2B8C` → `#7B3DC9` → 洋红 `#E04EA8` 对角渐变，左上舞台光、右下压暗、底部琥珀反光 |
| 头壳 | 圆角方块 `hw=0.330 / hh=0.300`，竖向两段色 `#F6F8FF`→`#C5D0E6`，`#9FADC8` 描边 |
| 面部屏 | `#12161F`，上半 `#262E3D` 分块，外沿一层 `#3A465C` 内发光 |
| 眼睛 | 琥珀 `#FFC41E` 椭圆 + 黑瞳 + 双白高光，外圈一层 `#FFD64A` 软光晕 |
| 点缀 | 天线粉球、两侧粉色耳机、脸颊两颗粉色指示灯、背景一颗琥珀四角星 |

为什么是机器人而不是人脸：头脑大、五官少，40px 下比人脸抗糊；天线+耳机+分块面部，
缩到 32px 仍是「一个方脑袋 + 两只亮点」的清晰剪影。

产出的 5 个密度都写进 `app/res/drawable-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png`，
外加 `drawable/` 里的 192px 兜底（电视横幅 `android:banner` 也复用这张图）。

- 交付：`test/icon_candidates/` 下的 `robot_512.png`（512 大图）、`robot_round.png`（圆形裁切）、
  `robot_ladder.png`（32/40/48/72/96/144/192 七档同屏对比，用来验小尺寸可读性）。

生成脚本 `test/make_icon_robot.py` 会把图量化到 200 色调色板再落盘——这个图标一共就 6 个色系
加渐变，肉眼看不出差别，192px 从 30KB 降到 13KB，五个密度总共只让 APK 长了 25KB
（74193 → 99185）。

- CI run [`43215127017`](https://github.com/XACPPP/yg-vodtv/actions/runs/43215127017) 一次通过。
- Release：<https://github.com/XACPPP/yg-vodtv/releases/tag/v2.3>（2026-11-12 20:08）。
- 资产 `YG.TV.apk` / `YGTV.apk` 均 **99185 字节**，sha256
  `27173ae660ea41aecc6b28f50bc3c836ce7c8ec30bb54bf0f4eb58eaf64f5b65`。
- `aapt2 dump badging` → `versionCode='14' versionName='2.3'`，五个密度的
  `application-icon-160/240/320/480/640` 全部指向新的 `ic_launcher.png`。
- dex **128176 字节**（与 v2.2 一致——这版只改了资源和版本号，没动代码）。

### v2.2：画面「自动」比例 + 缓存按字节封顶

老板两条反馈：「手机上看虽然画面正常了但没全屏」「预加载不能让缓存一直无限增大」。

| 反馈 | 真因 | 改法 |
| --- | --- | --- |
| 手机上「没全屏」 | 不是 bug。手机约 20:9，16:9 片源在「适应」下必然左右留黑；v2.0 默认就是适应 | 加默认**自动**档：`MAX_CROP=0.34`，铺满裁掉 ≤34% 就铺满，超过就适应。菜单键循环「自动→适应→铺满」 |
| 缓存一直变大 | 上限按**文件数**（600 张），单张 15KB~400KB，最坏 200MB+；且每 40 次才检查一次，一页 84 张就能涨十几 MB | 改成按**字节**：64MB 触发、清到 40MB 停；排序键 `lastModified`（读命中 touch），最久没看的先删；每写 20 次 + 启动时各查一次 |
| 自检「海报缓存」永远是绿的 | 那一步无条件 `pass++`，缓存涨到 500MB 也显示通过 | 按「实际占用 vs 上限」判定，超限标红 |

另加：顶栏「清缓存」按钮（只删海报，播放记录/搜索历史在 SharedPreferences 里不受影响）+ 一条 2.5 秒自动消失的提示条。

- CI run [`43211283062`](https://github.com/XACPPP/yg-vodtv/actions/runs/43211283062) 一次通过。
- Release：<https://github.com/XACPPP/yg-vodtv/releases/tag/v2.2>（2026-11-12 19:41）。
- 资产 `YG.TV.apk` / `YGTV.apk` 均 **74193 字节**，sha256
  `b74975e8f15a46195b2c97c38fccb91e29930953ed5e28b596608bedad33ba04`；
  经 `gh-proxy.com` 镜像下载 **74193 字节一致**（md5 `2122fd3861a9e86fe939ffa2f0ce63d8`）。
- dex **128176 字节**（v2.1 是 124488），内确认含 `MODE_AUTO`、`wantFill`、`describe`、
  `DISK_MAX_BYTES`、`clearDisk`、`trimDiskAsync`、`clearCacheConfirm`、`banner`、`diskLimit`。

### v2.1：修「搜索没封面 / 预加载白干 / 缓存没用」

三处真 bug 的定位与改法见上面 `### 海报（v2.1 修「预加载白干」三处真 bug）`。
一句话总结：**图从来不是取不到，是我们自己把地址丢了、把图下完没上屏、把整页都当成「可见」。**

- CI run `43207971822` 一次通过。
- Release：<https://github.com/XACPPP/yg-vodtv/releases/tag/v2.1>。
- 资产 `YG.TV.apk` / `YGTV.apk` 均 **74193 字节**，sha256
  `61b3badd6534bc4f03bf311e375a5797edd0d5071b2be5f0363a31b495c6eaaa`；
  经 `gh-proxy.com` 镜像下载**字节数与 sha256 全部一致**。
- dex **124488 字节**（v2.0 是 121888），含 `applyToWaiting`、`DISKPOOL`、`bucket`、`diskBytes`、`baseOf`。

### v2.0 实发记录

- CI run `43202720182` 一次通过（37 秒），只有 3 条 action 版本弃用警告。
- Release：<https://github.com/XACPPP/yg-vodtv/releases/tag/v2.0>，发布于 2026-11-12 18:43（北京时间）。
- 资产 `YG.TV.apk` / `YGTV.apk` 均 **70097 字节**。
- 本地构建 md5 `0f541a31b9e03bde2c78e59fc2afabca`；CI 产物两条下载通道
  （`gh-proxy` 1.1s / `ghfast` 1.7s）md5 都是 `e6ec00bd732587471c7d999f05102ee2`，
  与本地不同只是因为 apksigner 每次签名带时间戳，正常。
- 元信息经 `gh-proxy.com` 1.0s 取到，`tag_name = v2.0`、`size = 70097`。
- 注意：本机**直连** GitHub 是 `SSL: CERTIFICATE_VERIFY_FAILED`（DNS 被旁路由劫持到 198.19.x），
  所以直连那一路在这台机器上验不了，App 里它本来就只当兜底。
- dex 内确认含 `FitVideoView`、`toggleScaleMode`、`sizeInfo`、`letterBlock`、`prefetch`、
  `cacheStat`、`directState`、`normPic`、`prune`、`zuidazy`、`dytt`、`jszyapi`、`uku`。

### v1.9 实发记录

- CI run `43175910776` 一次通过。
- Release：<https://github.com/XACPPP/yg-vodtv/releases/tag/v1.9>，发布于 2026-11-12 15:32（北京时间）。
- 资产 `YGTV.apk` / `YG.TV.apk` 均 **66001 字节**，md5 `a57a00fdf9533f37b05246291ce1788a`。
- CI 产物 dex **111944 字节**，确认含 `loadPosterDeferred`、`pump`、`AUTO_SEARCH`、`addHistory`。

### v1.8 实发记录

- CI run `43172267408` 一次通过（约 40 秒）。
- Release：<https://github.com/XACPPP/yg-vodtv/releases/tag/v1.8>，发布于 2026-11-12 15:02（北京时间）。
- 资产 `YGTV.apk` / `YG.TV.apk` 均 **66001 字节**。
- 三条下载通道（`gh-proxy` 3.6s / `ghfast` 6.0s / 直连 4.5s）拿到**同一个 md5
  `652c86b0863b881942349f2e09c1a5c5`**。
- CI 产物 dex **107348 字节**，确认含 `preferIPv4Stack`、`attempts`、`downCount`、`MIN_SRC`、`failed`。

### 网络层健壮性（v1.8 新增）

电视实机跑自检时抓到的日志暴露了三个此前在桌面测不出来的问题，v1.8 逐条修掉：

| 现象（真机日志） | 根因 | v1.8 改法 |
|---|---|---|
| `SocketException(Connection reset)` 连续打挂 modu / ysxq / ikun | 原来只重试 1 次、等 400ms，这类瞬时断连没救回来 | 连接层重试 **3 次**，退避 300ms / 900ms；`SSLException` 也算可重试 |
| `connect ikunzyapi.com/[::]:443` 直接失败 | Android 优先选 AAAA 记录，电视网络没有 IPv6 出口 | 静态块里设 `java.net.preferIPv4Stack=true`，强制走 IPv4 |
| 聚合只有 `7/10 个源有数据` | 十个源同一瞬间各发一次 TLS 握手，被中间设备当异常流量 reset | 并发从 8 降到 **6**；再加**源级熔断**（连续失败冷却 3 分钟）。v2.0 有 14 个源，熔断更重要 |
| 港剧/港台综艺耗时顶到 9000ms 上限 | `latch.await` 死等满预算，一个慢源拖累整页 | 改成**集齐 6 个源立刻出画面**；预算 7s→9s 容纳重试 |
| 海报自检 3018ms | https/http/代理三跳串行，每跳都要等满超时 | 第一跳用**快超时 3s/5s**，失败直接上图片代理；常见场景只花一跳 |

自检页因此多了第 1 步「网络参数」，直接显示 IPv4 是否强制生效、重试次数、以及当前有几个源在冷却。

### v1.9：卡顿与「很多片没封面」的根因（同一件事）

v1.8 上线后老板在手机上装了一次，反馈「还是很多视频没封面，整体很卡」。
桌面端 42 张海报全部 200 可下载（10 个源各取 6 张），所以**不是片源没给图**，
问题在客户端一次要的太多。

算一笔账就明白了：

```
一页 84 张卡 × 每张 6 个 View  = 504 个 View 在主线程一次性建完
同时 84 个海报请求全丢进线程池 = 队列排 14 轮
每张失败要重试 3 次，单张最坏 = 24 秒
→ 最后一排海报要 5.6 分钟后才拿到
```

更要命的是失败处理：**任何一张图失败一次就被塞进 `DEAD` 集合，本次运行内永不重试**。
84 张同时轰出去，网络稍有波动就成片失败，于是整墙被永久标记成灰块——
「很多片没封面」就是这么来的，不是图挂了，是客户端把图拉黑了。

v1.9 三处改动：

| 改动 | 具体做法 | 效果 |
| --- | --- | --- |
| **惰性取图** | `Ui.loadPosterDeferred()` 只登记；滚动时 `Ui.pump()` 把进入视口（含下方一屏预取）的发出去 | 首屏只需下约 40 张，不再排队几分钟 |
| **失败不再永久毒化** | `DEAD` 从裸 `HashSet` 改成 `Map<url, {时间, 次数}>`；冷却 90 秒后自动重试，连续失败 2 次才真正放弃 | 网络抖一下不会把整墙变灰 |
| **降并发** | 海报线程池 6 → 4 | 不把图床惹毛（对应 v1.8 的 reset 问题） |

顺带压掉 View 数量：卡片外层由 `LinearLayout` 改 `FrameLayout`，`ImageView` 不再单独设圆角
背景（每张一个 `GradientDrawable` 是几十个 Drawable，滚动掉帧的隐性成本），
角标没有 `remarks` 时不创建。

### 搜索（v1.9 重做交互）

旧版：点「搜索」→ 弹出**全屏**键盘 →一个字一个字点 →按「搜索」→键盘消失→出结果
→想改一个字母得再点「重新输入」。遥控器上改一个字母要按四次键。

新版一屏搞定：

```
┌─────────────────────┬──────────────────────────────┐
│ 搜索片名…            │                              │
│ 停手 2 秒自动搜       │        ← 结果实时刷新         │
│ 最近：繁花 庆余年 …   │        4 列海报               │
│ ┌─┬─┬─┬─┬─┬─┐        │                              │
│ │A│B│C│D│E│F│        │                              │
│ ├─┼─┼─┼─┼─┼─┤        │                              │
│ │…│…│…│…│…│…│        │                              │
│ └─┴─┴─┴─┴─┴─┘        │                              │
└─────────────────────┴──────────────────────────────┘
```

- **停手 2 秒自动搜**（`AUTO_SEARCH_MS = 2000`）：遥控器一个字一个字点，每点一次就搜会把
  十个源打爆，所以等手停下来。每次输入都会重置计时器。
- **结果实时刷新，键盘不消失**：接着改词直接看右边变化，没有「重新输入」这一步。
- **最近 6 个搜索词**在输入框下方，一下就能命中最常用的（`Store.history()` / `Store.addHistory()`）。
- **同一个词不重复请求**（`lastKw` 比对），改一个字母才重新发。
- **结果回来时校验词是否还一致**：用户已经改了词就丢弃这批结果，避免旧结果盖新结果。
- 结果区也是惰性取图，4 列（左栏占 760，右栏约 1050）。

### 海报缓存上限（v2.2 从「600 张」改成「64MB 字节硬限」）

老板原话：「虽然要预加载，但是你不能让缓存一直无限增大吧，你想个办法解决缓存一直变大的问题」。

老做法是**按文件数**限制（600 张），问题很实在：一张海报可能 15KB，也可能 400KB，
600 张最坏能到 200MB 以上 —— 在手机上看着就是「缓存无限变大」。而且清理时机是「每写 40 次查一次」，
一次翻页刷 84 张，中间就能涨出十几 MB。

现在改成**按字节**：

| 项 | 值 | 说明 |
| --- | --- | --- |
| 触发阈值 | 64MB | 超过就清理 |
| 清理目标 | 40MB | 一直删到 40MB 以下停 |
| 排序键 | `lastModified` | **读命中会 touch 修改时间**，所以是「最久没被看过的先删」，常看的剧封面留着 |
| 检查时机 | 每写 20 次 + App 启动 | 启动时在后台扫一次，老版本留下的超大目录会被顺手收掉 |
| 手动入口 | 顶栏「清缓存」 | 只删海报，不动播放记录和搜索历史 |

`getCacheDir()` 是**应用私有缓存**，系统存储紧张时也会自己回收，所以不存在「删不掉」的情况。
自检页第 5 步「海报缓存」现在按「实际占用 vs 上限」判定，超了标红（以前是无条件绿色，等于没测）。

### 播放画面比例（v2.2 加「自动」档）

老板在手机上装完反馈「画面正常了但没全屏」。这不是 bug —— 手机屏幕大概 20:9，
16:9 的片子在「适应」模式下只能占中间那一块，左右必然留黑边。

v2.0 让用户手选「适应 / 铺满」，默认适应，所以手机上观感就是「没全屏」。
v2.2 加了默认的**自动**档，按「铺满要裁掉多少」判断：

| 片源 / 屏幕 | 铺满要裁 | 自动选择 |
| --- | --- | --- |
| 16:9 片子 / 20:9 手机 | 约 20% | **铺满**（两侧各裁一条） |
| 16:9 片子 / 16:9 电视 | 0% | 铺满（本来刚好） |
| 2.35:1 宽银幕 / 16:9 | 约 8% | 铺满（上下各裁一条） |
| 4:3 老片 / 16:9 | 约 25% | 铺满 |
| 9:16 竖屏短剧 / 16:9 | 约 68% | **适应**（铺满会裁掉七成，必须留黑） |

阈值是 `MAX_CROP = 0.34`（裁超 34% 就不铺）。遥控器**菜单键**在「自动 → 适应 → 铺满」三档间循环，
播放页顶部提示条上写着。日志会记 `1920x1080 铺满（容器 2400x1080，裁掉 20%）` 这样的实测值，
真机不对时看日志就能分清是判断逻辑错了还是尺寸没读到。

### 海报（v2.1 修「预加载白干」三处真 bug）

老板原话：「预加载完就不管后续封面和简介了，我搜索出来的基本都没封面」「预加载也没用，
数据有 7M 多，每次打开还是要加载，像是没用缓存」。三条都是真 bug，已逐条定位：

| 症状 | 真因 | 改法 |
| --- | --- | --- |
| 搜索出来的条目基本没封面 | `Vod.normPic()` 把任何非绝对地址**一律判空**。bfzy 的 `suggest` 接口返回的 `pic` 100% 是 `/upload/vod/...` 站内路径（实测 20/20），拼上 `https://bfzyapi.com` 就能 200 拿到真图（15892 字节 RIFF/WebP）——却被判空丢掉了 | `normPic(p, base)` 按 base 拼回绝对地址；`Src.root(api)` 取该源的 `scheme://host`；`Vod.baseOf(srcKey)` 从源 key 反查 |
| 预加载下完图，屏幕上的卡还是灰的 | `prefetch` 线程体只写 `CACHE`/`DISK`，**从不通知已经绑好地址的 ImageView**；而卡片在 `WAITING` 里等 `pump`，`pump` 只认视口，用户不滚就永远不触发 | 新增 `Ui.applyToWaiting(url, bitmap)`：预取成功后在主线程扫一遍 `WAITING`，同地址的直接上屏 |
| 每次打开都要重新加载、缓存像没生效 | `pump` 的视口算错了 —— 用的是 container（ScrollView 里那一整块 `rows`，高约 4200px ≈ 屏幕 4 倍）自己的高度，于是判定对任何卡都成立，**一次把 84 张全排进队列**，「滚到哪下哪」彻底失效；另外 `fromDisk` 跑在 4 线程网络池里，磁盘命中也要排队 | `pump` 改用 `getGlobalVisibleRect`（被祖先裁剪后的真实可见区域）+ 一屏预取；新增独立 `DISKPOOL`（2 线程），磁盘命中直接上屏、不走网络池 |

**顺带压盘**：`diskName` 的尺寸量化到 64px 一档（首页 234×351 / 搜索 200×300 / 详情 380×570
以前是同一张图存三份），`cacheStat()` 增加实际占用字节数（「内存 N 张 · 磁盘 N 张/12.3MB · 冷却 N 个」），
自检页能直接看到磁盘到底存了多少。

### 海报（v2.0 加预加载 + 磁盘缓存）

老板原话：「加载不出来或者加载很慢，给人感知很差，为啥不做预加载呢？」——v2.0 就是补这一块。

**三层缓存**：内存 `LruCache` 48MB → 磁盘 `getCacheDir()/poster` → 网络。
第二跳以后才需要 `wsrv.nl` 图片代理，磁盘命中完全是本地 IO。
（磁盘上限 v2.0/v2.1 是「600 张」，v2.2 改成按字节，见下节。）

**预加载**（`Ui.prefetch(List<Vod>, w, h)`）：翻页拿到 84 条后不等滚动，直接丢进
**独立的两线程池** `PREFETCH`，跟屏幕上正在等的那 4 张图互不抢配额；
队列上限 56 张，`QUEUED` 去重，命中内存或已判死就跳过。

**直连自适应**（`Ui.directDown()` / `noteDirect()`）：国内直连图床经常不通，
以前每张图都要先白等 3 秒再跳代理；现在连续失败 3 次就**把直连关掉 2 分钟**，
这期间直接走 `wsrv.nl`，恢复后自动切回。自检页会显示当前状态。

**占位图分两档**：还在下载 → 暗一档的首字色块（`letterBlock(...,0x66FFFFFF)`）；
三次都失败 → 亮一档的首字色块（`letter(...,0xE6FFFFFF)`）。整墙灰块的情况不会再出现。

`Ui.cacheStat()` 会把「内存 N 张 · 磁盘 N 张 · 冷却 N 个」显示在自检页第 5 步。

### 海报（v1.7 三跳兜底的历史说明）

老板反馈「还是没有封面，首页/详情页都说暂无封面灰块」。逐层实测后确认**服务端完全正常**
（`img1.wsyzy.org` 带 App UA 直连 200、`image/jpeg`、28755 字节、魔数 `ffd8`；十个源的首条海报
都能下载），问题全在客户端。v1.7 改成三跳 + 失败可见：

| 跳 | 地址 | 说明 |
| --- | --- | --- |
| 1 | 原始地址（多为 https） | 正常情况 |
| 2 | `http://` 版本 | 老版本只有这一跳反向；保留给不支持 TLS 的图床 |
| 3 | `https://wsrv.nl/?url=<encodeURIComponent(原地址)>` | 图片代理，实测 200 / 50783B / `ffd8` |

关键改动（`Ui.java`）：

- **失败不再静默**：三跳全失败才 `DEAD.add(url)`，并把图换成**片名首字色块**（`Ui.letter()`，
  8 组深色按首字 hashCode 取模），不是灰底「暂无封面」——一眼能分清「没加载完」和「这个地址废了」
- `Trace` 分别记三跳结果，最后一行写「海报三跳全失败（原始/代理/http）」，自检页可直接看到
- **`fetch()` 不再传 `Api.REFERER`**：实测图床不校验 Referer，反而多带一个跨站头更容易被 CDN 拦
- `PLACEHOLDERS` 加**上限 24**（超了 `clear()`）：原来是无界 `HashMap`，播一晚上会因尺寸组合无限增长
- 线程池 4 → 6：一屏 28 张海报，4 线程排不完

另有一条环境事实：家里旁路由把 `img1.wsyzy.org` 解析到 `198.19.1.1`（Tailscale CGNAT 段），
公共 DNS 解析是 Cloudflare 的 `172.67.185.222`。电视走的是自己那套 DNS，若解析不到就会三跳全失败——
这也是必须让「失败可见 + 自检页能测海报」的原因。

实测（`bash run.sh meta`）：https 通道 6/6 全 JPEG（17603–28755 字节），http 兜底通道同样 200。

### 刮削信息（v1.4 补）

接口的 `ac=detail` 其实给了整套刮削字段，早期 `Vod.from()` 只取了 id/name/pic/remarks 就丢了。
现在全解析：`vod_actor` / `vod_director` / `vod_area` / `vod_lang` / `vod_blurb` / `vod_content`。

`vod_content` 是带 `<p>` `<br/>` 的 HTML，`Vod.plain()` 清洗成纯文本（去标签、还原 `&nbsp;` 等实体、
压缩空行）；`Vod.intro()` 优先用长简介，没有才退到一句话 blurb。详情页显示「导演/主演/地区/语言」
一行，简介默认 3 行、**点一下展开全文**。

实测（`bash run.sh meta`）：国产剧第 1 页 20 条，有简介 20/20、有主演 20/20、有导演 15/20，
清洗后无残留标签与实体。

### 播放画面比例（v2.0 修）

老板反馈「部分影片不能正常全屏，画面只显示在左边一小块，右边一大片全黑」。

真因不是遮挡，是**测量 + 对齐**：Android 5.1（API 22）的 `VideoView.onMeasure` 里有一段兼容逻辑——

```java
// AOSP frameworks/base/core/java/android/widget/VideoView.java（API 22）
if (mVideoWidth * height  < width * mVideoHeight)      width  = height * mVideoWidth / mVideoHeight;
else if (mVideoWidth * height  > width * mVideoHeight) height = width  * mVideoHeight / mVideoWidth;
```

**即便父容器给的是 MATCH_PARENT，它也会把控件的一边压成视频比例**，永远有一边小于父容器。
而 `FrameLayout` 的默认 `layout_gravity` 是 `START|TOP`，于是缩小的控件贴在 x=0：

| 片源比例 | 控件实际尺寸（1920×1080 屏） | 表现 |
| --- | --- | --- |
| 16:9 | 1920×1080 | 正好铺满，看不出问题 |
| 4:3 | 1440×1080 | 右边 480px 纯黑 |
| 竖屏 9:16（抖音短剧） | 607×1080 | **右边 1313px 纯黑** ← 老板看到的就是这种 |
| 宽银幕 2.35:1 | 1920×817 | 上下留黑 |

API 23 之后这段兼容逻辑被移除，所以「部分影片」才出问题。

修法（`PlayerActivity.java`）：

1. `root.addView(vv, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, **Gravity.CENTER**))`
   —— 一行治本：缩小的控件居中，两侧对称留黑
2. 自定义 `FitVideoView extends VideoView`，`onMeasure` 里反射读 `mVideoWidth`/`mVideoHeight`，
   按 `min(sx,sy)` 等比缩放后 `setMeasuredDimension`；尺寸无效或超过 8192 时退回 `super.onMeasure`
3. 遥控器**菜单键**切换「适应 / 铺满」：适应 = 完整显示两侧留黑；铺满 = 等比放大盖满，超出部分裁掉。
   竖屏短剧在 16:9 电视上按「适应」只占中间三分之一宽，想要满屏就用「铺满」
4. `onPrepared` 里 `vv.post(() -> vv.requestLayout())` 补一次测量——
   VideoView 是**先回调 listener 再赋 `mVideoWidth`**，不补这一下，4:3 会停在「还没尺寸」时的状态
5. `setOnInfoListener` 在 `MEDIA_INFO_VIDEO_TRACK_LAGGING` / `BUFFERING_START` / `BUFFERING_END`
   时重新 `requestLayout()`：HLS 多码率切换分辨率会变，VideoView 不会主动重新测量
6. 起播日志写 `起播成功 第N集 1440x1080 4:3 1.33 屏幕 1920x1080 模式 适应`，
   下次再有比例问题直接看日志

顺带修掉两个独立小毛病：详情页**不再自动播放**（以前「只有 1 集就自动播」是给电影用的，
结果简介、导演、地区全还没看就跳走了）；顶部标题条改成字段，控制条收起时它会跟着隐藏。

### 应用内更新（v1.4 新增，v1.7 换镜像顺序）

`Update.checkSync()` 调 GitHub `releases/latest`，把 `tag_name` 清洗成版本号后与 `Ver.NAME` 分段比较
（`1.10 > 1.9`，不是字符串比）。有新版本就把标题栏的「检查更新」按钮变成金底「有新版 X」，
点开弹层显示更新说明 + 「下载并安装」：

| 步骤 | 做法 | 注意 |
| --- | --- | --- |
| 选 asset | 优先 `browser_download_url` 以 `/YGTV.apk` 结尾的那个 | GitHub 会把中文名 `YG影视TV.apk` 转码成 `YG.TV.apk`，认名字不可靠 |
| **查元信息** | **`https://gh-proxy.com/` 优先** → 直连兜底 | v1.7 换序：实测本机直连是 `SSL: CERTIFICATE_VERIFY_FAILED`（DNS 劫持到 198.19.x 后证书对不上），先试直连等于白等一个超时。超时同时收到 4 秒连接 / 7 秒读取 |
| **下载** | `gh-proxy.com` → `ghfast.top` → 直连 | 下载地址兜底用 `releases/latest/download/YGTV.apk`，不依赖具体 tag |
| 下载目录 | `Environment.getExternalStorageDirectory()/Download/YG影视TV/` | **不能用 `getCacheDir()`**：系统安装器是另一个 uid，读不到 `/data/data/...` |
| 安装 | `Intent.ACTION_VIEW` + `Uri.fromFile` + `application/vnd.android.package-archive` | targetSdk 22 可以直接 `file://`，不用 FileProvider |
| 权限 | `REQUEST_INSTALL_PACKAGES` + `WRITE_EXTERNAL_STORAGE` | Android 8+ 才要「允许安装未知应用」，5.1 只弹一次系统确认 |

**镜像实测（2026-11-12）**：`gh-proxy.com` 能代理 `api.github.com`（返回 6129 字节、tag 一致）；
`ghfast.top` 对 `api.github.com` 回 403、只代理 Release 下载，所以元信息和下载用两套列表。
两个镜像下载的 `YGTV.apk` 与官方 md5 完全一致（`f919064c03d0a8ab7d0a86fe921d0171`）。
`ghproxy.net`、`hub.gitmirror.com`、`gh.llkk.cc` 当时全部不通，没有采用。

签名是同一把 key（见下），所以新包能原地覆盖升级，不用卸载。

### GitHub 发布与自动出包（v1.4 新增）

仓库：<https://github.com/XACPPP/yg-vodtv>（公开，含源码）

```bash
# 改完代码：改 Ver.NAME 与 AndroidManifest 的 versionCode/versionName，然后
git add -A && git commit -m "v1.6：xxx"
git tag v1.6 && git push origin master v1.6
```

`.github/workflows/build.yml` 在 push tag `v*` 时自动跑：装 JDK 17 + Android SDK（platform 30 /
build-tools 30.0.3）→ 从 Secret `KEYSTORE_B64` 还原 `app/yg.keystore` → **校验 tag 与 `Ver.NAME` 一致**
（不一致直接失败，防止发错版本号）→ `bash app/build-ci.sh` → 上传 artifact → `softprops/action-gh-release`
发 Release，挂 `YG影视TV.apk` 与 `YGTV.apk` 两个文件。

`app/build-ci.sh` 是 `build.sh` 的 Linux 版：同样的 aapt2 → javac → d8 → 打包 → zipalign → apksigner 六步，
只是工具路径来自 `ANDROID_HOME`、没有 `.exe` 后缀、用系统自带的 `javac`/`python3`。

**v1.7 实发记录**：`git tag -a v1.7 && git push origin master v1.7` 后 CI run
[43168907319](https://github.com/XACPPP/yg-vodtv/actions/runs/43168907319) 一次通过（约 1 分钟），
Release <https://github.com/XACPPP/yg-vodtv/releases/tag/v1.7> 已挂 `YG.TV.apk` 与 `YGTV.apk`（均 61905 字节），
与本地构建产物同尺寸。CI 报的 3 条 annotation 都是 action 版本弃用警告（Node.js 20、setup-java@v4、
ubuntu-latest 标签迁移），不影响出包。

**踩坑记录**：仓库刚建、workflow 文件推上去后，`gh api .../actions/workflows` 长时间返回
`total_count: 0`，`gh workflow run build.yml` 报 `HTTP 404: workflow build.yml not found on the default branch`，
空 commit、重写文件、甚至 PUT `actions/permissions` 都没用。**推一个 tag 就好了** —— tag 事件会强制
GitHub 重新注册 workflow，`git tag v1.4 && git push origin v1.4` 之后立刻出现第 1 次 run。

### 出错时怎么定位：`Trace` + 自检页

电视上黑屏、闪退时拿不到 logcat，所以内置了环形错误日志 `Trace`（保留最近 120 条，
`HH:mm:ss [tag] msg` 格式）。`Http` / `Api` / `Proxy` / `PlayerActivity` 每个失败点都会写一条，
自检页下半屏直接显示最近 14 条，**拍照即可反馈**。

自检页 9 个步骤，逐步验证：

| 步骤 | 验证内容 | 失败意味着 |
| --- | --- | --- |
| 1 | 本地代理启动（18765~18800） | 端口全被占用，播放一定失败 |
| 2 | 分类列表 `Api.list(6,1)` | 接口不通或域名变了 |
| 3 | 搜索联想 `suggest("fanhua")` | suggest 接口异常 |
| 4 | 详情与选集 `Api.detail(id)` | `vod_play_url` 解析异常 |
| 5 | 播放列表首跳 `Proxy.wrapPlaylist` | 代理或上游 m3u8 不通 |
| 6 | 往下跳一层媒体列表 | 子列表改写异常 |
| 7 | 分片首包 `0x47` | 分片拿不到或被拦 |

### 播放记忆（v1.3 新增，`Store.java`）

电视上看剧最烦的是「关了再来，要从头拖」。现在的行为：

| 时机 | 动作 |
| --- | --- |
| 播放中每 5 秒 | 记一次当前位置（`updateProgress()` 里 `tickCount % 5`） |
| 按返回 / 切后台 | `onPause()` + `onDestroy()` 各存一次 |
| 切集 | 切之前先把上一集的位置存下来 |
| 一集播完 | 位置清 0，下次点进来从头播 |
| 再次开局 | 起播时若存的位置 > 5 秒且离片尾 > 10 秒，自动 `seekTo` 并提示「接着上次看 mm:ss」 |
| 进详情页 | 焦点自动落在上次看到的那一集，且该集按钮文字是金色（`Ui.ACCENT`），状态行显示「上次看到 第N集」 |

存储用 `SharedPreferences`（文件 `yg_vod`），key 形如 `p_<影片id>_<集号>`，值是毫秒。不需要任何权限，
卸载即清。片头 5 秒内不记录，避免「刚点开就退出」把位置写成 0 又覆盖掉真正的进度。

### 代码结构

| 文件 | 作用 |
| --- | --- |
| `app/src/com/yg/vodtv/MainActivity.java` | **影视板块**首页：双板块胶囊 + 定宽频道条（一屏 6.5 个）+ 海报墙 + 左右分栏搜索 |
| `app/src/com/yg/vodtv/LiveActivity.java` | **v2.8 已删除**（原来是电视板块的频道墙首页，点两下才播，改成直达播放后整页多余） |
| `app/src/com/yg/vodtv/LivePlayerActivity.java` | 直播播放 + 右侧频道抽屉：全屏 VideoView、顶部信息条（台名/清晰度/第几路源/正在直播/时钟）、音量 HUD、OK 呼出抽屉（↑↓ 选台 / ←→ 切分组 / OK 播放）、底部键帽条；看门狗四条判据自动切源 |
| `app/src/com/yg/vodtv/Live.java` | **直播表**：`BUILTIN` 兜底 + `load(json)` 六层容错（含 IPv6 清洗）+ `groups()` 返快照 |
| `app/src/com/yg/vodtv/LiveSync.java` | **直播表热更**：启动同步读缓存 → 后台拉远端 `live.json` → `Live.load`；`status()` 报「内置/当前/缓存时间」 |
| `app/src/com/yg/vodtv/DetailActivity.java` | 详情页：海报 + meta + 选集网格 |
| `app/src/com/yg/vodtv/PlayerActivity.java` | 播放页：VideoView + 自绘进度条 + 选集浮层 |
| `app/src/com/yg/vodtv/SelfTestActivity.java` | 自检页：9 步链路检测（含海报下载、海报缓存）+ 错误日志面板 |
| `app/src/com/yg/vodtv/Store.java` | 播放位置 + 最近观看 + **搜索历史 6 条**（都按「源_id」区分） |
| `app/src/com/yg/vodtv/Proxy.java` | 本地 HLS 代理（见上） |
| `app/src/com/yg/vodtv/Src.java` | **采集源定义**：`BUILTIN` 内置 14 个源的 base/Referer + 统一频道 → 各源 `type_id` 映射；`load(json)` 用远端表整体替换（六层容错）；`all()`/`byKey()` 返快照 |
| `app/src/com/yg/vodtv/SrcSync.java` | **源表热更**：启动同步读缓存 → 后台拉远端 `sources.json` → `Src.load` 换表；`status()` 给自检页报「内置 / 当前 / 远端时间」 |
| `app/src/com/yg/vodtv/Cache.java` | **列表 JSON 落盘**：`cacheDir/list/{ch}_{pg}.json`，16MB 上限 / 12MB 触发清理，tmp→rename 原子替换 |
| `app/src/com/yg/vodtv/Prefetch.java` | **相邻频道预热**：单线程空闲预取 `cur±1` 的首页 JSON，间隔 400ms，`INFLIGHT` 去重 |
| `app/src/com/yg/vodtv/PyIndex.java` | **拼音首字母表**：`lookup(wddcz)` → 按需下 `assets/py/w.txt`（走 raw）→ `pick` 两轮扫描（精确 key 优先，再收前缀）→ 候选中文片名 |
| `app/src/com/yg/vodtv/Multi.java` | **多源聚合**：按频道并行取数、交织去重、搜索合并、简介跨源回填；`Result.raw` + `encode`/`decode` 供落盘；搜索侧 `awaitSearch` 三重判据（凑够 21 条 / 静默 1200ms 就走） |
| `app/src/com/yg/vodtv/Api.java` | 单源接口封装（list / detail / wd / suggest）+ 搜索结果贴合度重排 |
| `app/src/com/yg/vodtv/Vod.java` | 影片模型 + `vod_play_url` 选集解析 |
| `app/src/com/yg/vodtv/Http.java` | HTTP 封装 + 信任全部证书的 SSLContext + 5xx 一次重试 |
| `app/src/com/yg/vodtv/Trace.java` | 环形错误日志（120 条） |
| `app/src/com/yg/vodtv/Ui.java` | 尺寸等比缩放、配色、圆角/渐变/焦点背景、海报三跳加载 + **惰性取图** + **`READY`/`FLUSH` 批量上屏** + LRU 缓存 + 首字色块兜底 |
| `app/src/com/yg/vodtv/Cards.java` | 海报卡片：底部渐变压暗 + 片名压在图上 + 角标 + 焦点放大，支持惰性加载 |
| `app/src/com/yg/vodtv/Ver.java` | 版本号与构建时间（首页右上角显示） |
| `app/src/com/yg/vodtv/Store.java` | SharedPreferences 播放记忆：每剧每集看到哪儿、上次看到哪一集 |
| `app/src/com/yg/vodtv/Update.java` | 应用内升级：查 GitHub Release、下载 APK、拉起系统安装器 |
| `app/src/com/yg/vodtv/Ver.java` | 版本号与构建时间（标题栏显示） |

## 三、构建

不用 Gradle，直接用 aapt2 + javac + d8 + apksigner 手动打包（复用 `../海信电视刷机/toolchain/`）：

```bash
cd app
env -u MSYS_NO_PATHCONV bash build.sh
```

CI（Linux）用同一套步骤，工具路径换成 `ANDROID_HOME`：

```bash
ANDROID_HOME=/path/to/sdk bash app/build-ci.sh
```

产物：`app/YG影视TV.apk`（约 45 KB），`build.sh` 末尾会同时复制一份**纯 ASCII 文件名**的
`app/YGTV.apk`（内容完全相同）—— 有些电视的 U 盘安装器认不出中文文件名，装不上时换这个。

**必须带 `env -u MSYS_NO_PATHCONV`**，否则 Windows 上 aapt2.exe 收到 `/d/...` 形式的路径会报
`failed to open APK: I/O error`。

签名：`app/yg.keystore`，storepass/keypass 都是 `yg123456`，alias `yg`。

APK 信息：`package=com.yg.vodtv`、`versionCode=21`、`versionName=2.9`、`minSdkVersion=21`、
`targetSdkVersion=22`、`screenOrientation=landscape`、`usesCleartextTraffic=true`（海报和本地代理
都是 http，必须开）。签名同时带 v1/v2/v3 —— **v1 是 Android 5.1 能装的前提**。

`build.sh` 里有防假成功的检查：javac 之后逐个确认 **25 个 class** 都产出了（v1.7 补了 `Src`、`Multi`，
v2.4 补了 `Cache`、`Prefetch`、`SrcSync`，v2.5 补了 `PyIndex`，v2.6 补了 `Live`、`LiveSync`、
`LivePlayerActivity`，v2.7 补了 `Chrome`，v2.8 删了 `LiveActivity`），并比对 `MainActivity.class` 与源码时间戳，避免 javac 悄悄失败却打包了旧 class。

## 四、安装

### 方式 A：U 盘（电视 ADB 没开时用这个，最稳）

1. 把 `YG影视TV.apk` 拷到 U 盘根目录（**装不上或电视里看不到这个文件，就改用同目录的 `YGTV.apk`**，
   纯 ASCII 文件名，内容完全一样）
2. U 盘插到电视 USB 口
3. 电视「我的应用」→「U盘安装」/「媒体中心」→ 找到 APK 点击安装

### 方式 A+：手机/其他设备下载

<https://github.com/XACPPP/yg-vodtv/releases/latest> 直接下 `YGTV.apk`（当前 Latest 是 v1.7，61905 字节）。
**装过一次 v1.4 或更新版本之后就不用再手动装了** —— App 里点「检查更新」自己升级。

电视端那条更新链路如果卡住（国内直连 GitHub 的 TLS 会被中间设备打断），从 v1.7 起元信息优先走
`gh-proxy.com` 镜像，只有镜像也失败才回落到直连，所以正常情况下电视自己就能升级。

### 方式 B：ADB（需要电视开启 ADB 调试）

```powershell
D:\platform-tools\adb.exe connect 10.1.1.14:5555
D:\platform-tools\adb.exe devices
D:\platform-tools\adb.exe install -r "D:\Codexspace\做点小玩意\YG影视TV\app\YG影视TV.apk"
D:\platform-tools\adb.exe shell am start -n com.yg.vodtv/.MainActivity
```

装好后第一件事：点标题栏右侧的「自检」，9 项全绿就说明片源链路通；有红的，看下半屏错误日志。

## 五、验证记录

### JVM 端到端验证（`test/harness/`，跑的是 APK 里的真实源码）

这套 harness 直接编译并运行 `app/src/com/yg/vodtv/` 下的**同一份** `Api/Http/Proxy/Vod/Trace/Src/Multi`，
只把 `android.util.Base64` 和 `org.json` 换成桌面替身 —— 证明的是 APK 里那份代码本身正确，
而不是我用 Python 重写的逻辑正确。v2.4 起 `Src`/`Multi` 也进了编译列表（源表解析与列表编解码要测）。

```bash
cd test/harness
env -u MSYS_NO_PATHCONV bash run.sh        # Harness：16 项断言
env -u MSYS_NO_PATHCONV bash run.sh wire   # Wire：用原始 Socket 模拟 NuPlayer，21 项
env -u MSYS_NO_PATHCONV bash run.sh tabs   # Tabs：27 个分类逐项验证，27 项
env -u MSYS_NO_PATHCONV bash run.sh rank   # Rank：搜索结果贴合度排序，4 组
env -u MSYS_NO_PATHCONV bash run.sh meta   # Meta：刮削字段 + 海报双通道，9 项
env -u MSYS_NO_PATHCONV bash run.sh upd    # Upd：版本号 + 镜像兜底 + 真实 Release 下载，19 项
env -u MSYS_NO_PATHCONV bash run.sh srctab # SrcTab：源表热更容错与频道映射，23 项（不联网）
env -u MSYS_NO_PATHCONV bash run.sh cachetab # CacheTab：列表缓存编解码往返 + 落盘，34 项（不联网）
env -u MSYS_NO_PATHCONV bash run.sh pytab   # PyTab：拼音首字母表的精确/前缀两轮扫描，24 项（不联网）
env -u MSYS_NO_PATHCONV bash run.sh dump   # 打印真实改写的播放列表头部
```

**Harness（16/16 PASS）**：代理启动 → 分类列表（动作片 20 条 / 共 289 页）→ 搜索 fanhua 20 条 →
详情选集一一对应 → `/hls/*.m3u8` 路由 → 合法 m3u8 → **不含 master 标签（已就地解开）** →
2677 个分片 → 不含上游域名 → `/ts/` 路由 → 分片 490680 字节、首字节 0x47 →
PMT `0x1B` H.264 → Range 可用 → 反证原始 https 地址也可直取（代理是真转发）。

**Wire（21/21 PASS）**：用原始 Socket 手写 HTTP 请求，逐字节核对线上格式 ——
状态行、`\r\n` 分隔、`Content-Type: application/vnd.apple.mpegurl`、无 chunked、
**Content-Length 与实际 body 一致**、HEAD 不带 body、分片 `video/mp2t`、
Range 回 206 + `Content-Range`、body 恰好 1024 字节、未知路径 404。
（`HttpURLConnection` 太宽容，抓不到这类问题，所以单独写了一套。）

**dump**：确认改写后 `#EXTM3U` / `#EXT-X-VERSION` / `#EXT-X-TARGETDURATION` /
`#EXT-X-MEDIA-SEQUENCE` / `#EXT-X-PLAYLIST-TYPE` / `#EXT-X-ENDLIST` / `#EXT-X-DISCONTINUITY`
全部保留 —— 这些是 NuPlayer 判定 VOD 列表必需的标签。

**Meta（9/9 PASS，v1.7 复测）**：国产剧第 1 页 20 条 → 有简介 20/20、有主演 20/20、有导演 15/20；
样本「今天是太阳雨」导演猫的树、地区中国大陆、语言汉语普通话、年份 2026、简介两段且无 HTML 标签与残留实体；
海报 https 通道 6/6 全 JPEG（17603–28755 字节），http 兜底通道同样 200。

**Upd（19/19 PASS，v1.7 换序后复测）**：`v1.4`/`V1.4`/`1.4`/`release-1.4`/`v1.4-beta` 清洗全对；
`1.10 > 1.9`、`1.4.1 > 1.4` 分段比较正确；真实请求 GitHub Release API 拿到 `/YGTV.apk`；
**镜像顺序断言**（元信息 `gh-proxy` → 直连；下载 `gh-proxy` → `ghfast` → 直连）通过，
并**真的经 `gh-proxy.com` 拉了一次 release API**（6061 字节，含 `tag_name`）；
**真的下载了一次**（53713 字节，落在外部存储 `Download/YG影视TV/YGTV-new.apk`，是合法 zip）。
直连一路在本机是 `SSL: CERTIFICATE_VERIFY_FAILED`，所以测试里显式跳过、App 里只当兜底。

**SrcTab（23/23 PASS，v2.4 新增，不联网）**：源表热更是 v2.4 唯一能「远程把 App 弄坏」的改动，
所以单独配了一套离线断言 —— 喂手写 JSON 给 `Src.load()`，专测失败路径：
内置表仍是 14 个源、单源表整体替换、`types` 逗号串映射到 `CH_MOVIE/CH_TV/CH_VARIETY`、
没写到的频道回 0、推荐频道任何源都支持、`types` 写成 JSON 数组也能解析、
**`types` 缺失补 0 而不是丢源**（空串会让 `Integer.parseInt` 抛异常整条消失）、
坏项（空 key / 无 scheme）只丢这一条、`load(null)`/纯空白/半截 JSON/根是数组/空数组/全坏项
**六种情况一律不改变现有源表**、`loadSeq()` 自增、`all()` 每次返回新副本（`s1 != s2`）。
真正的联网打分不在这套里，由 `test/source_scan.py` 负责。

**CacheTab（34/34 PASS，v2.4 新增，不联网）**：列表缓存是「切频道不转圈」的地基，
但它的失败方式很阴 —— encode 少转义一个引号、decode 读错一个字段，电视上表现为
「有缓存却点进去是空页」，而且只在片名带引号/换行/控制字符时才炸，真机几乎复现不出来。
所以喂一条最脏的样本（`测试"片名\带反斜杠`、多行简介含 `\n\t\r`、简介里夹 `\u0001`、
播放地址带 `$`、海报 URL 带 `?x=1&y=2`）做往返：17 个字段逐个比对、
encode 结果不含裸换行、控制字符被替换成空格、7 组坏输入（半截 JSON、根是数组、
纯文本、items 是数字数组、名字全空）都不抛异常、`v` 字段更高时主动拒读。
Cache 侧验 `put/get` 字节一致、`sameAs` 正负两例、**不同频道与不同页码的文件名不串**、
短于 `MIN_LEN` 的内容拒写、`clear()` 后占用归零。

**PyTab（24/24 PASS，v2.5 新增，不联网）**：`PyIndex.pick` 是纯函数，坏掉的方式是「排序错」
而不是「报错」——精确 key 排到前缀命中后面，就会让「头号候选查不到再退下一个」的兜底彻底失效，
用户看到的是「搜到了别的片」。所以喂一个含 6 行的 `w` 桶（`w` 两条、`wd`/`wdz`/`wddcz` 三条不同长度），
断言 `wddcz` 的两条里**正主（伟大的长征）排第一**、`wd` 命中 4 行、`w` 命中 6 行且首条是精确 key、
cap=3 时只出 3 条、cap=0 用默认上限，再验大写/空白输入、无制表符/制表符在末尾/空行三种脏行、
重复片名去重、null/空/纯空白/查不到全部返回空列表、CRLF 行尾、无结尾换行。

**Tabs（24/24 PASS，v2.0 复测）**：首页 24 个统一频道逐个拉第一页，
要求「有数据 + 有海报」。空分类是最容易被忽略的坑 —— App 能开，点进去一片空白。
实测**共聚合 1694 条，每个频道都有数据且全部带海报**，每个频道 6~9 个源返回
（v2.0 换成 14 个源之后单源返回数比 v1.9 少 1~2 个，因为熔断跳过了 503 的 `ysxq`
和当时顺手冷却的几家，但聚合条数不变、耗时更稳）：

| 频道 | ch | 聚合条 | 源数 | 耗时 | 频道 | ch | 聚合条 | 源数 | 耗时 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 推荐 | 0 | 84 | 6 | 2.5s | 纪录片 | 19 | 84 | 6 | 1.2s |
| 4K电影 | 28 | 40 | 2 | 9.1s | 大陆综艺 | 20 | 69 | 6 | 1.9s |
| 动作 | 12 | 84 | 6 | 2.8s | 日韩综艺 | 21 | 71 | 6 | 1.5s |
| 喜剧 | 13 | 84 | 6 | 2.6s | 港台综艺 | 22 | 55 | 6 | 1.5s |
| 爱情 | 14 | 84 | 6 | 1.6s | 欧美综艺 | 23 | 84 | 7 | 1.4s |
| 科幻 | 15 | 84 | 8 | 1.4s | 国产动漫 | 24 | 66 | 6 | 1.5s |
| 恐怖 | 16 | 84 | 7 | 1.4s | 日韩动漫 | 25 | 50 | 6 | 1.5s |
| 战争 | 18 | 84 | 7 | 0.9s | 欧美动漫 | 26 | 84 | 6 | 1.5s |
| 国产剧 | 5 | 27 | 6 | 1.0s | 悬疑剧 | 29 | 84 | 7 | 1.2s |
| 欧美剧 | 6 | 84 | 6 | 1.2s | 韩剧 | 7 | 59 | 6 | 1.1s |
| 日剧 | 8 | 61 | 6 | 1.2s | 港剧 | 9 | 70 | 6 | 1.7s |
| 台剧 | 10 | 56 | 6 | 1.4s | 泰剧 | 11 | 62 | 6 | 1.4s |

（4K电影只有 2 个源，是因为只有 `ysxq` 和 `wsyzy` 有独立的「4K/蓝光电影」分类；其余源的 4K 片
混在普通电影里。条数不满 84 的两个原因：①该频道本来就没那么多片；②集齐 6 个源就收工，
没等剩下的慢源——翻下一页会拿到另外那一批。v1.7 里港剧/港台综艺要 7~9 秒，v1.8 全部压到 2.3 秒以内。）

（`maoyan` 的欧美综艺是空列表，属源侧数据情况，`Trace` 里有 `空列表 list maoyan ch=23 len=77`。）

### 编码兼容性（`test/parse_codec.py` + `test/codec_survey.py`）

老电视最怕 H.265（Android 5.1 硬解 HEVC 极不稳定）。实测 PMT 流类型：

| 分类 | 样本 | 视频 | 音频 |
| --- | --- | --- | --- |
| 动作片 | 寄生狂潮 | H.264 (0x1B) | AAC (0x0F) |
| 国产动漫 | 大理寺日志第三季 | H.264 | AAC |
| 大陆综艺 | 第三调解室 | H.264 | AAC |
| 纪录片 | 落网之谜 | H.264 | AAC |
| 4K电影 | 美国往事国语4K | H.264 | AAC |

**结论：全部 H.264 + AAC，海信 Android 5.1 可硬解。**

（踩坑记录：最初脚本报「没解析到 PMT」，原因是 PSI 段在 PUSI 包里有 1 字节 `pointer_field`
没跳过，导致把 PAT 读成了 PMT。修好后 PMT PID 正确为 4096，流类型正常解出。）

### 构建验证

`build.sh` 六步全绿（现在由 GitHub Actions 在 tag 上跑）。最近一次（v2.5 本地出包）：APK **111473 字节**，dex **149200 字节**（v2.4 是 107377 / 144144——v2.5 新增 PyIndex 一个类 + Multi 的搜索链路改造）；
dex 内确认含 `MODE_AUTO`、`wantFill`、`describe`、`DISK_MAX_BYTES`、`clearDisk`、`trimDiskAsync`、
`clearCacheConfirm`、`banner`、`diskLimit`，以及 v2.1 的 `applyToWaiting`、`DISKPOOL`、`bucket`、`diskBytes`、`normPic`、`baseOf`，
还有 v2.0 的 `FitVideoView`、`toggleScaleMode`、`letterBlock`、`prefetch`、`cacheStat`、`prune`、`zuidazy`、`dytt`、`jszyapi`、`uku`，
v2.4 的 `pushReady`、`primeNow`、`onDisk`、`decode`、`encode`、`sameAs`、`BUILTIN`、`SrcSync`，
v2.5 的 `PyIndex`、`pick`、`lookup`、`SEARCH_QUIET_MS`、`rankKw`
（每版改动确实打进去了，不是只改源码没进包）。
`aapt2 dump badging` → `versionCode='16' versionName='2.5'`、`sdkVersion:'21'`、`targetSdkVersion:'22'`；
`aapt2 dump permissions` → INTERNET / ACCESS_NETWORK_STATE / WAKE_LOCK / **REQUEST_INSTALL_PACKAGES** / **WRITE_EXTERNAL_STORAGE**。
签名（v2.4 复验）：`apksigner verify -v --min-sdk-version 21` → v1/v2/v3 = true、v4 = false（v1 在，Android 5.1 才能装）；
证书 `CN=YG, OU=TV, O=YG, L=Ganzhou, ST=Jiangxi, C=CN`，
SHA-256 `bbdf5b3916abe4c82f5fbee470e8aad21c2f9b8db8ef5688b85ead25bd967f8c`（与 v2.3 一致，所以能用 App 内更新直接覆盖安装）。

### 未验证

**v2.6 的直播板块还没有真机跑过（这是当前最需要确认的一块）：**
桌面上能验的都验了 —— `live_scan.py` 全量跑通（1757 条探出 721 条可播）、`livetab` 40 项断言全绿、
随机抽 14 条直播地址实测 14/14 返回 `#EXTM3U`（最快 33ms / 最慢 1310ms）、APK 编译打包签名全过。
**但没验的是真机能验的那部分**：
①`raw.githubusercontent.com/.../assets/live.json`（81KB）电视能不能拉到；
②API 22 的 NuPlayer 对这种「无 `#EXTINF` 时长之外的常规 HLS 直播流」能不能起播（点播 m3u8 已经证明可以，
直播流的 `#EXT-X-TARGETDURATION` / `#EXT-X-MEDIA-SEQUENCE` 滚动窗口是另一套）；
③`Proxy.wrapPlaylist` 对直播流的改写会不会把 `#EXT-X-PROGRAM-DATE-TIME` 这类直播特有标签搞坏
（`rewrite()` 只动 `#EXT-X-KEY`/`#EXT-X-MAP` 和分片行，理论上安全，但没实测）；
④遥控器 CH+ / CH- 在海信 VIDAA 上到底映射成 166/167 还是别的码 —— 这两个键做的是**换台**（不是换源），
如果不是这两个码，↑↓ 换台仍然可用，只是那个快捷键不生效。

判定标准：点顶栏「电视」→ 分组条一屏只露 6 个半 → 点「央视」→ 点 CCTV-1 → 能出画面 →
按 ←→ 屏幕上方提示「线路 2/3」并且画面重连。

**v2.5 的拼音索引还没有真机跑过：**`PyIndex` 走 `raw.githubusercontent.com/.../assets/py/{L}.txt` 按需下桶，
本机实测这条路径对 `sources.json` 是通的，但索引文件 4.6MB 分 23 个桶、最大单桶 725KB（`s.txt`），
电视上首次搜某个字母要等这一桶下完（局域网走 Passwall，估计 1~3 秒），下过一次永久缓存。
**需要确认的是**：①电视能不能拉到 `py/*.txt`；②首字母搜索的体感延迟；③`w.txt` 里的《伟大的长征》能否被翻出来。
判定标准：搜索框输 `wddcz`，结果里出现《伟大的长征》。

**v2.2 的两处改动也只验证了逻辑，没上真机：**「自动」比例档的 `MAX_CROP=0.34` 是否合手感（20:9 手机放
21:9 老片会怎么选）、磁盘缓存按字节清理在 `getCacheDir()` 真可写时的实际表现（自检页第 5 步会直接读数）。

**v2.0 的三处改动同样只在桌面上验证过：**播放画面比例（`FitVideoView` 的
`onMeasure` 反射读 `mVideoWidth` 在 VIDAA 的 ROM 上能不能拿到值）、海报预加载与磁盘缓存
（`getCacheDir()` 在已 root 的电视上是否可写）、四个新源的实际播放（m3u8 只在桌面用 python 拉过首包）。

**更早的版本也还没在电视上完整跑过。** 老板在**手机**上装过（v1.3），反馈了两条问题：①没有更新检测 ②海报全是黑框、没有刮削 ——
两条都已按上面的方案改掉，但改动本身仍是「桌面逻辑验证通过」，未经电视实机确认。仍未实测的有：
VideoView 在本机代理上的 HLS 起播、遥控器焦点在 7 列海报墙上的移动手感、选集浮层弹出时的焦点抢占、
新增的 10 列选集网格在 1920 宽下会不会挤、
自检页实际读数、**应用内下载安装这条链路**（`Upd` 里已用真实 Release 跑通下载与落盘，但「拉起系统安装器」
只能在真机上验证）。

**手机上装是什么观感**：这个 App 是给电视做的 —— 强制横屏、自绘遥控器焦点、全程用 DPAD 键导航。
装在手机上会横屏显示，但没有触摸点击支持，只能用返回键。**手机只用来验证「装得上、能起播」，
真正用的是电视。**

排查设备时的**重要教训**：本次开发机开着 Mihomo TUN，它会把**任意地址的任意 TCP 端口**都伪装成
「连得上」（实测 `192.0.2.1:5555`、`203.0.113.9:1234`、`8.8.8.8:9999` 全部 connect 成功，ping 也是 0ms），
所以「端口通 / ping 通」**完全不能证明设备在线**。必须绑物理网卡验证：

```bash
ping -n 2 -S 192.168.1.9 10.1.1.14     # 绑 WiFi 网卡，结果 100% 丢包
```

最终确认：开发机当时在 `192.168.1.0/24`（WiFi），不在 `10.1.1.0/24`，
`10.1.1.14` 只是 TUN 的假象。

真机验证的判定标准（装好后按这个看）：
1. 首页出分类海报墙，**且海报是真的封面图、不是「暂无封面」占位**
2. 点进任意剧集能看到选集，**且标题下面有导演/主演/地区/语言与简介**
3. 点某一集能起播（`Proxy` 三跳改写 + VideoView 都通）
4. 上键弹选集、OK 暂停、返回退出（遥控器映射通）
5. 自检页 9 项全绿（**含「网络参数」「海报下载」「海报缓存」三步**，片源链路整体通）
6. 标题栏「检查更新」显示「已是最新版本 v2.6」
7. 点顶栏「电视」进直播板块，分组条一屏只露 6 个半，右边缘那半个要能被滑出来
8. 直播随便点一个台能出画面；按菜单键能换线路，按 ←→ 能直接切源
7. 搜索框输 `wddcz`，结果里应有《伟大的长征》（v2.5 拼音首字母索引）；输 `weidadechangzheng` 也应命中

## 六、目录

```
YG影视TV/
├── README.md
├── .github/workflows/build.yml  # push tag v* 自动出包 + 发 Release
├── .github/workflows/scan.yml   # v2.4 每天 02:00 巡检源表 → v2.5 加拼音索引 → v2.6 加直播源 → v2.7 加台标 PNG，一起自动 commit
├── .gitignore                   # 排除 build/、*.apk、yg.keystore、__pycache__、test/live_preview.json
├── app/
│   ├── AndroidManifest.xml
│   ├── build.sh                 # 六步构建脚本（Windows / MSYS）；v2.7 起 link 加 -A，只打 assets/logo
│   ├── build-ci.sh              # 六步构建脚本（Linux / GitHub Actions）；-A 必须与 build.sh 同步改，v2.7.1 就是漏了这条
│   ├── yg.keystore              # 签名（yg123456）—— 不进仓库，CI 用 Secret 还原
│   ├── res/values/strings.xml
│   ├── res/drawable/ic_launcher.png          # 192px 兜底（电视 banner 复用）
│   ├── res/drawable-{m,h,xh,xxh,xxxh}dpi/    # v2.3 机器人图标五档密度
│   ├── src/main/assets/sources.json          # v2.4 源表（巡检自动重写；不打包进 APK，供 GitHub raw 与 CI）
│   ├── src/main/assets/py/{a..z}.txt         # v2.5 拼音首字母表（23 桶 4.6MB，CI 每天重建；同样走 raw）
│   ├── src/main/assets/live.json             # v2.6 直播表（428 台 669 条，CI 每天重建；同样走 raw）
│   ├── src/main/assets/logo/*.png            # v2.7 台标（96×54，CI 巡检产出；v2.7 起随 APK 打包，不走 raw）
│   ├── src/com/yg/vodtv/*.java  # 25 个源文件（v2.4 加 Cache/Prefetch/SrcSync，v2.5 加 PyIndex，v2.6 加 Live/LiveSync/LivePlayerActivity，v2.7 加 Chrome，v2.8 删 LiveActivity）
│   ├── build/                   # 中间产物
│   ├── YG影视TV.apk             # 成品
│   └── YGTV.apk                 # 同一份的 ASCII 文件名副本（U 盘安装 / 更新下载都用它）
└── test/
    ├── harness/                 # JVM 端到端验证（跑真实源码）
    │   ├── run.sh               # bash run.sh [wire|dump|tabs|rank|meta|upd|srctab|cachetab|pytab|livetab]
    │   ├── Harness.java         # 16 项链路断言
    │   ├── Wire.java            # 21 项线上字节格式断言（模拟 NuPlayer）
    │   ├── Tabs.java            # 24 个统一频道逐项验证（多源聚合，有数据 + 有海报）
    │   ├── Rank.java            # 搜索结果贴合度排序验证
    │   ├── Meta.java            # 刮削字段 + 海报通道验证（9 项）
    │   ├── Upd.java             # 版本号解析/比较 + 镜像顺序 + 真实 Release 下载（19 项）
    │   ├── SrcTab.java          # v2.4 源表热更：Src.load() 六层容错 + 频道映射（23 项，不联网）
    │   ├── CacheTab.java        # v2.4 列表缓存：Multi.encode/decode 往返 + Cache 落盘（34 项，不联网）
    │   ├── PyTab.java           # v2.5 首字母索引：PyIndex.pick 精确优先/前缀兜底/脏行（24 项，不联网）
    │   ├── LiveTab.java         # v2.6 直播表：Live.load() 六层容错 + IPv6 清洗 + 快照（40 项，不联网）
    │   ├── Dump.java            # 打印改写后的播放列表
    │   ├── android/*.java       # android.util/net/os/content 替身
    │   └── org/json/*.java            # org.json 替身
    ├── proxy_sim.py             # 早期 Python 复刻（已被 harness 取代）
    ├── source_scan.py           # v2.4 源健康巡检 + 100 分制打分 → sources.json
    ├── pinyin_index.py          # v2.5 从 bfzy 全库切首字母 → assets/py/*.txt（8584 页 / 5 分钟）
    ├── live_scan.py             # v2.6 直播源采集+测速+分组 → assets/live.json（1757 条探 157 秒）；v2.7 加台标抓取（iptv-org + MANUAL_LOGO_IDS 硬映射 → assets/logo/*.png）+ TTFB≤3s + 实测吞吐≥40KB/s + 两轮复测
    ├── sources_health.json      # 巡检结果（App 不直接读，供 CI/人看）
    ├── sources_health.md        # 同上，人读表
    ├── tvbox_cfg/               # v2.4 调研时下载的 TVBox 配置样本（type=1 抽取用）
    ├── parse_codec.py           # 离线解析 TS 编码
    ├── codec_survey.py          # 跨分类抽样探测编码
    ├── fetch_sample.py          # 抓样本分片落盘
    ├── probe_sources.py         # 采集源全量实测（241 候选 → 可用性/内容/分类/m3u8）
    ├── candidate_pool.txt       # 候选 base 池（从 GitHub 各采集合集抽取去重）
    ├── verify_final.py          # 入围站二次复核（列表/详情/分类/搜索/m3u8）
    ├── check_suggest.py         # 各站 suggest 搜索链路验证
    ├── make_final_table.py      # 汇总成 sources_recommended.md
    ├── sources_report.txt       # 全量实测报告（UTF-8）
    ├── sources_report.json      # 同上，结构化
    ├── sources_final.txt        # 19 站二次复核明细
    ├── sources_recommended.md   # 最终推荐表
    ├── class_all.json           # 十源完整 class 表（JSON）
    ├── class_wsyzy.json         # 仅 wsyzy 的 60 个分类
    ├── class_view.txt           # 十源 `id=名称` 对照表（UTF-8，Src.BUILTIN 就是照它填的）
    ├── make_icon_robot.py       # v2.3 图标生成器（Pillow 纯代码画，五档密度一起出）
    ├── icon_candidates/         # 图标大图 + 七档尺寸对比图（验小尺寸可读性）
    └── samples/                 # 已抓的 *.ts / *.m3u8
```

## 七、已知限制

- 片源是第三方接口，上游改了域名或加了防盗链就得换源。**v2.4 起改
  `app/src/main/assets/sources.json` 就够了**，推上去电视下次开机自动生效；只有当远端表整体
  不可用时才需要动 `Src.java` 的 `BUILTIN` 数组（编译进 APK 的兜底表，格式同 JSON 的四个字段）
- 各源仍可能单个分类返回空或非 JSON，`Api.parsePage()` 全 catch 容错，聚合时跳过该源
- **直播源衰减比点播源快得多**：公共 IPTV 清单里 59% 是死的（实测 1757 条只有 721 条能播），
  而且今天能播的明天可能就 403。所以 `live.json` 必须靠每天巡检重刷；如果哪天电视上大片频道
  都放不出来，先看 GitHub Actions 的 `scan` 是否还在正常跑，别急着改代码
- **内置的江西电信源只在电信家宽里能用**：`115.153.245.70` 是运营商内网单播地址，换到移动/联通网络、
  或者出门用 4G 就会 302 到一个连不通的地址。而且每台只有 1 条线路（不像公共清单那样有 3 条备选），
  IPTV 平台调整令牌后需要重新抓一遍。码率 8.4Mbps，2.4G WiFi 下若有别的设备抢带宽，
  4K/高清电视仍可能缓冲——真遇到就把电视接到 5G 频段
- **动画/少儿台目前是空的**（`kids` 组 0 个频道）：两个公共清单里动画那 54 条单探 40 条全挂，
  多为 migu token 中转站和已停服的 freetv.fun。等找到可用源再放开 `live_scan.py` 的筛选规则
- **直播表不打包进 APK**：`aapt2 link -A build/assets` 只挂了 `logo/` 一个目录
  （`build.sh` / `build-ci.sh` 里都是 `cp -r src/main/assets/logo build/assets/logo`），
  `live.json` 不进包，运行期走 `raw.githubusercontent.com`，编译期兜底是 `Live.BUILTIN`
  （5 组 17 台占位台名）。这样每天巡检改表不用重发 APK
- **`limit=` 各源都不认**，所以每页条数只能本地截断（当前 84 条）
- 翻页上限写了 400 页（`curPage < 400`），再往后意义不大
- 没有收藏、没有字幕切换（片源自带硬字幕）
- 列表缓存只存**第一页**（`Cache.put` 只处理 `pg == 1`）：翻页是临时行为，存了基本用不上。
  开机首屏走缓存，翻到第 2 页仍要发网络请求
- 个别源偶尔超时，会把整页拖到 7 秒预算上限（结果仍是完整的，只是慢）；
  真机上若觉得「推荐」开页慢，可以调小 `Multi.LIST_BUDGET`
- 代理挑变体时上限 5 Mbps，片源若有更高码率也不会选；老芯片解不动比看不清更糟
- **手机上只能用返回键**：没有触摸点击支持，全程是电视遥控器（DPAD）交互
- **应用内更新依赖 GitHub 可达**：国内网络下 `api.github.com` 可能连不上，此时「检查更新」会提示失败并让你手动去 GitHub 下（下载走 `github.com` 的 Release CDN，通常比 API 好连）

### 搜索能力限制（v1.7 变化）

v1.7 起搜索结果来自两路，覆盖面比单源大得多：

| 输入 | 走哪条路 | 实测结果 |
| --- | --- | --- |
| 中文（如「凡人修仙传」） | 14 个源并发 `?ac=detail&wd=`，按贴合度重排后**再剪掉噪声** | maoyan 2 条、ffzy 3、ysxq 20、bfzy 20、ikun 4、360zy 11、yhzy 3、cmov 7、modu 10（`wsyzy` 不支持 `wd=`，返回纯文本） |
| 纯拼音/全拼（如 `xiaochengliangfang`） | v2.0 起改问各源的 `suggest`（`wd=` 对拼音实测全 0，拼音根本不进库） | 11 个源里 9 个能命中：「小城良方」「兰香如故」「南部档案」全部 1 条精确 |
| 全拼（如 `fanhua`） | 额外一路 `wsyzy` 的 `suggest` | 20 条，首条「繁花错」 |
| 首字母缩写（如 `wddcz`） | **v2.5 起先查本地首字母表翻成中文名，再走 14 源 `wd=`** | `wddcz` → 「伟大的长征」，11 个源命中 1 条精确 |

`suggest` 单源那一路支持中文和**完整拼音**，但**不支持拼音首字母缩写**（这是服务端侧的限制，
v2.5 用离线索引绕过它，见上文「### v2.5：拼音首字母能搜到了」）：

| 输入 | 结果 |
| --- | --- |
| `fanhua` / `fanhu` / `fanh` / `fan` / `Fanhua` | 各 20 条 |
| `繁花` | 20 条 |
| `庆余年` | 8 条 |
| `doupo` / `doupocangqiong` | 19 / 12 条 |
| `qyn`（庆余年首字母） | **0 条**（走 `suggest` 时）；v2.5 起先查索引 |
| `fh`（繁花首字母） | 1 条噪声 |

搜索面板空结果文案 v2.5 起改成「没找到，换个中文名或全拼试试」——首字母已经能用了，
旧文案里「（首字母缩写搜不到）」这句不再成立。

### 搜索结果排序（v1.2 新增）

`suggest` 是**子串模糊匹配**：搜 `fanhua` 会把一堆「万倍返**还**」（`fanhuan` 里正好含 `fanhuan`→`fanhua`）
排在真《繁花》前面，原样展示第一屏全是噪声。

`Api.rank()` 拿到结果后按贴合度重排（`Vod.en` 是 suggest 同时返回的拼音 slug，v1.2 起一并解析）：

| 条件 | 分值 |
| --- | --- |
| 片名或拼音 slug 完全等于关键词 | 1000 |
| 片名以关键词开头 | 800 |
| 拼音 slug 以关键词开头 | 760 |
| 片名包含关键词 | 500 |
| 拼音 slug 包含关键词 | 300 |
| 都没有（纯接口噪声） | 100 |

再按片名长度加成（越短越可能是正主，最多 +60），长度相同时短的排前。重排只在内存里做，
不改接口、不改请求次数。

效果（`bash run.sh rank` 实测）：

| 关键词 | 重排前首条 | 重排后前 3 条 |
| --- | --- | --- |
| `fanhua` | 吃我的饭，还想砸我的碗 | 繁花错 / 繁华落尽 / 繁花照春晚 |
| `qingyunian` | 庆余年之风起沧州 | 庆余年第二季 / 庆余年第一季 / 庆余年之帝王业 |
| `kuangbiao` | 狂飙之浴血玫瑰 | 狂飙1998 / 狂飙之浴血玫瑰 / 狂飙之绝地反击 |
| `sanguo` | 三国第一部：争洛阳 | 三国假刘协 / 三国王道恋爱 / 三国演义3D |

重排**不解决**首字母缩写问题（`qyn` 走 `suggest` 接口本来就返回 0 条）——那是 v2.5 用离线首字母索引解决的，
见上文「### v2.5：拼音首字母能搜到了」。
