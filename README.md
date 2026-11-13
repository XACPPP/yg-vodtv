# YG影视TV

自用的电视端影视 App，纯原生 Android（无 Gradle、无第三方库），专为海信 VIDAA 电视
（Android 5.1.1 / API 22，已 root）定制。片源来自 **14 个苹果CMS采集源聚合**，App 内部只认
统一频道号，按源查表翻译成各站自己的 `type_id`，所以换源、加源不用改界面代码。

当前版本：**v2.4（versionCode 15）**，首页标题栏右侧会显示版本与构建时间，用来确认电视上跑的是哪一版。
源码与成品都在 <https://github.com/XACPPP/yg-vodtv>，打 tag 自动出包并发 Release，App 里能自己检查更新。

## 一、它是什么

一句话：**遥控器点开就能搜片、选集、播放的电视 App。**

- 首页：**24 个统一频道**（推荐、4K 电影、动作、喜剧、国产剧、动漫、综艺、悬疑剧……）海报墙，
  每页最多 84 条、14 个源并行聚合去重，遥控器方向键浏览，点「加载更多」翻页；
  **第一页列表落盘 + 相邻频道预取**，切频道不转圈，海报整屏一次出（v2.4）
- **源表热更**：源的定义是一份远端 JSON，GitHub Actions 每天自动巡检打分重排，
  换源 / 加源 / 调优先级**不用发版**；远端出任何问题都自动退回内置表（v2.4）
- 搜索：**一屏左右分栏**——左边键盘 + 最近搜过的词，右边实时结果；**停手 2 秒自动搜**，
  不用再按「搜索」；中文关键词并发问 14 个源的 `wd=`，纯拼音改问各源的 `suggest`（实测比 `wd=` 准得多），
  两路结果合并后按贴合度重排，再把「只是碰巧带这几个字」的噪声剪掉
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
| 1 | `bfzy` | 80 | A | list 886ms · 简介 17/20 · 片库 171663 |
| 2 | `jszyapi` | 80 | A | list 846ms · 简介 19/20 · m3u8 TTFB 780ms |
| 3 | `dytt` | 76 | B | 简介 20/20 · 选集 69 集 |
| 4 | `ffzy` | 74 | B | 选集 1313 集（最多） |
| 5 | `ysxq` | 72 | B | **已恢复**，片库 429268 最大 |
| 6 | `modu` | 71 | B | list 904ms |
| 7 | `maoyan` | 68 | B | TTFB 222ms · 分片 1376kbps（最快） |
| 8 | `360zy` | 68 | B | 分片 851kbps |
| 9 | `zuidazy` | 65 | B | 4K 62 / 悬疑 68 |
| 10 | `wsyzy` | 64 | C | **简介已改善到 18/20**（旧记录 3/20），但 `wd=` 仍不支持 |
| 11 | `yhzy` | 63 | C | 首跑 10.4s 超时、复测 459ms → 见下方坑 |
| 12 | `ikun` | 60 | C | 分片 352kbps 偏慢 |
| 13 | `cmov` | 56 | C | list 3683ms 最慢，但支持 `h=` 的例外之外一切正常 |
| 14 | `uku` | 52 | C | 简介 4/20、选集 1 集，纯凑数 |

**两个把好源误判成 DEAD 的脚本 bug（已修）**：

1. **m3u8 变体地址拼错**：源站 master playlist 给的变体是
   `/20261112/xxx/hls/index.m3u8` 这种**根相对**路径，脚本按目录拼接得到
   `.../xxx//20261112/xxx/hls/...` → 404 → 首分片取不到 → 判 DEAD。
   第一版跑出来 `maoyan`/`360zy`/`modu`/`uku`/`ikun` 五个好源全是 0kbps，
   就是这一条造成的。修法：新增 `abs_url(base, ref)`，遇到 `/` 开头的直接拼
   `scheme://host`（和 App 里 `Proxy.resolve()` 的规则一致）。
2. **list 只问一次**：`yhzy` 冷启动首问 10.4s 超时 → 判 DEAD。复测间隔 0.5s
   就是 459ms 正常。修法：第一次非 200 时放宽到 20s 重试一次。

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
   → 硬失败（列表非 JSON / 无数据 / m3u8 拿不到 / 无选集）直接判 DEAD，不参与排序
   → 按分数降序写 app/src/main/assets/sources.json（顺序就是 App 的源优先级）
   → 有变化才 git commit && push
```

两道保险：**全 DEAD 时不写文件**（避免把线上打成空表）；脚本里强制 IPv4
（`socket.getaddrinfo` 打补丁传 `AF_INET`），因为电视端 `Http.java` 设了 `preferIPv4Stack=true`，
巡检必须和电视同视角，否则 IPv6 侧的假 DEAD 会把好源踢掉。

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
和既有的 Harness/Wire/Tabs/Rank/Meta/Upd 一起跑，总共 8 套：

| 套件 | 项数 | 覆盖 |
| --- | --- | --- |
| SrcTab | 23 | `Src.load()` 六层容错、types 两种形态、坏项只丢一条、`all()` 返副本 |
| CacheTab | 34 | `Multi.encode/decode` 17 字段往还原、脏字符转义、7 组坏输入不抛、`Cache` 落盘不串 |

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
| `app/src/com/yg/vodtv/MainActivity.java` | 首页：分类条 + 海报墙 + 左右分栏搜索（自动搜 + 历史词） |
| `app/src/com/yg/vodtv/DetailActivity.java` | 详情页：海报 + meta + 选集网格 |
| `app/src/com/yg/vodtv/PlayerActivity.java` | 播放页：VideoView + 自绘进度条 + 选集浮层 |
| `app/src/com/yg/vodtv/SelfTestActivity.java` | 自检页：9 步链路检测（含海报下载、海报缓存）+ 错误日志面板 |
| `app/src/com/yg/vodtv/Store.java` | 播放位置 + 最近观看 + **搜索历史 6 条**（都按「源_id」区分） |
| `app/src/com/yg/vodtv/Proxy.java` | 本地 HLS 代理（见上） |
| `app/src/com/yg/vodtv/Src.java` | **采集源定义**：`BUILTIN` 内置 14 个源的 base/Referer + 统一频道 → 各源 `type_id` 映射；`load(json)` 用远端表整体替换（六层容错）；`all()`/`byKey()` 返快照 |
| `app/src/com/yg/vodtv/SrcSync.java` | **源表热更**：启动同步读缓存 → 后台拉远端 `sources.json` → `Src.load` 换表；`status()` 给自检页报「内置 / 当前 / 远端时间」 |
| `app/src/com/yg/vodtv/Cache.java` | **列表 JSON 落盘**：`cacheDir/list/{ch}_{pg}.json`，16MB 上限 / 12MB 触发清理，tmp→rename 原子替换 |
| `app/src/com/yg/vodtv/Prefetch.java` | **相邻频道预热**：单线程空闲预取 `cur±1` 的首页 JSON，间隔 400ms，`INFLIGHT` 去重 |
| `app/src/com/yg/vodtv/Multi.java` | **多源聚合**：按频道并行取数、交织去重、搜索合并、简介跨源回填；`Result.raw` + `encode`/`decode` 供落盘 |
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

APK 信息：`package=com.yg.vodtv`、`versionCode=6`、`versionName=1.5`、`minSdkVersion=21`、
`targetSdkVersion=22`、`screenOrientation=landscape`、`usesCleartextTraffic=true`（海报和本地代理
都是 http，必须开）。签名同时带 v1/v2/v3 —— **v1 是 Android 5.1 能装的前提**。

`build.sh` 里有防假成功的检查：javac 之后逐个确认 **20 个 class** 都产出了（v1.7 补了 `Src`、`Multi`，
v2.4 补了 `Cache`、`Prefetch`、`SrcSync`），并比对 `MainActivity.class` 与源码时间戳，避免 javac
悄悄失败却打包了旧 class。

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

`build.sh` 六步全绿（现在由 GitHub Actions 在 tag 上跑）。最近一次（v2.4 本地出包）：APK **107377 字节**，dex **144144 字节**（v2.3 是 99185 / 128176——v2.4 新增 Cache/Prefetch/SrcSync 三个类 + Multi 的 encode/decode + Ui 的批量上屏）；
dex 内确认含 `MODE_AUTO`、`wantFill`、`describe`、`DISK_MAX_BYTES`、`clearDisk`、`trimDiskAsync`、
`clearCacheConfirm`、`banner`、`diskLimit`，以及 v2.1 的 `applyToWaiting`、`DISKPOOL`、`bucket`、`diskBytes`、`normPic`、`baseOf`，
还有 v2.0 的 `FitVideoView`、`toggleScaleMode`、`letterBlock`、`prefetch`、`cacheStat`、`prune`、`zuidazy`、`dytt`、`jszyapi`、`uku`，
v2.4 的 `pushReady`、`primeNow`、`onDisk`、`decode`、`encode`、`sameAs`、`BUILTIN`、`SrcSync`
（每版改动确实打进去了，不是只改源码没进包）。
`aapt2 dump badging` → `versionCode='15' versionName='2.4'`、`sdkVersion:'21'`、`targetSdkVersion:'22'`；
`aapt2 dump permissions` → INTERNET / ACCESS_NETWORK_STATE / WAKE_LOCK / **REQUEST_INSTALL_PACKAGES** / **WRITE_EXTERNAL_STORAGE**。
签名：`apksigner verify -v --min-sdk-version 21` → v1/v2/v3 = true、v4 = false（v1 在，Android 5.1 才能装）；
证书 `CN=YG, OU=TV, O=YG, L=Ganzhou, ST=Jiangxi, C=CN`，
SHA-256 `bbdf5b3916abe4c82f5fbee470e8aad21c2f9b8db8ef5688b85ead25bd967f8c`。

### 未验证

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
6. 标题栏「检查更新」显示「已是最新版本 v2.4」

## 六、目录

```
YG影视TV/
├── README.md
├── .github/workflows/build.yml  # push tag v* 自动出包 + 发 Release
├── .github/workflows/scan.yml   # v2.4 每天 02:00 巡检源表 → 自动 commit sources.json
├── .gitignore                   # 排除 build/、*.apk、yg.keystore
├── app/
│   ├── AndroidManifest.xml
│   ├── build.sh                 # 六步构建脚本（Windows / MSYS）
│   ├── build-ci.sh              # 六步构建脚本（Linux / GitHub Actions）
│   ├── yg.keystore              # 签名（yg123456）—— 不进仓库，CI 用 Secret 还原
│   ├── res/values/strings.xml
│   ├── res/drawable/ic_launcher.png          # 192px 兜底（电视 banner 复用）
│   ├── res/drawable-{m,h,xh,xxh,xxxh}dpi/    # v2.3 机器人图标五档密度
│   ├── src/main/assets/sources.json          # v2.4 源表（巡检自动重写；不打包进 APK，供 GitHub raw 与 CI）
│   ├── src/com/yg/vodtv/*.java  # 19 个源文件（v2.4 新增 Cache/Prefetch/SrcSync）
│   ├── build/                   # 中间产物
│   ├── YG影视TV.apk             # 成品
│   └── YGTV.apk                 # 同一份的 ASCII 文件名副本（U 盘安装 / 更新下载都用它）
└── test/
    ├── harness/                 # JVM 端到端验证（跑真实源码）
    │   ├── run.sh               # bash run.sh [wire|dump|tabs|rank|meta|upd|srctab|cachetab]
    │   ├── Harness.java         # 16 项链路断言
    │   ├── Wire.java            # 21 项线上字节格式断言（模拟 NuPlayer）
    │   ├── Tabs.java            # 24 个统一频道逐项验证（多源聚合，有数据 + 有海报）
    │   ├── Rank.java            # 搜索结果贴合度排序验证
    │   ├── Meta.java            # 刮削字段 + 海报通道验证（9 项）
    │   ├── Upd.java             # 版本号解析/比较 + 镜像顺序 + 真实 Release 下载（19 项）
    │   ├── SrcTab.java          # v2.4 源表热更：Src.load() 六层容错 + 频道映射（23 项，不联网）
    │   ├── CacheTab.java        # v2.4 列表缓存：Multi.encode/decode 往返 + Cache 落盘（34 项，不联网）
    │   ├── Dump.java            # 打印改写后的播放列表
    │   ├── android/*.java       # android.util/net/os/content 替身
    │   └── org/json/*.java            # org.json 替身
    ├── proxy_sim.py             # 早期 Python 复刻（已被 harness 取代）
    ├── source_scan.py           # v2.4 源健康巡检 + 100 分制打分 → sources.json
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
| 首字母缩写（如 `qyn`） | 无 | 各源 `wd=` 与 `suggest` 都返回 0 条，这是片源侧限制 |

`suggest` 单源那一路支持中文和**完整拼音**，但**不支持拼音首字母缩写**：

| 输入 | 结果 |
| --- | --- |
| `fanhua` / `fanhu` / `fanh` / `fan` / `Fanhua` | 各 20 条 |
| `繁花` | 20 条 |
| `庆余年` | 8 条 |
| `doupo` / `doupocangqiong` | 19 / 12 条 |
| `qyn`（庆余年首字母） | **0 条** |
| `fh`（繁花首字母） | 1 条噪声 |

搜索面板的提示文案已按此改成「输入片名全拼或中文，例如 fanhua / 繁花（首字母缩写搜不到）」。

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

重排**不解决**首字母缩写问题（`qyn` 接口本来就返回 0 条），那是片源侧的限制。
