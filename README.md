# YG影视TV

自用的电视端影视 App，纯原生 Android（无 Gradle、无第三方库），专为海信 VIDAA 电视
（Android 5.1.1 / API 22，已 root）定制。片源来自 **10 个苹果CMS采集源聚合**，App 内部只认
统一频道号，按源查表翻译成各站自己的 `type_id`，所以换源、加源不用改界面代码。

当前版本：**v1.9（versionCode 10）**，首页标题栏右侧会显示版本与构建时间，用来确认电视上跑的是哪一版。
源码与成品都在 <https://github.com/XACPPP/yg-vodtv>，打 tag 自动出包并发 Release，App 里能自己检查更新。

## 一、它是什么

一句话：**遥控器点开就能搜片、选集、播放的电视 App。**

- 首页：**24 个统一频道**（推荐、4K 电影、动作、喜剧、国产剧、动漫、综艺、悬疑剧……）海报墙，
  每页最多 84 条、10 个源并行聚合去重，遥控器方向键浏览，点「加载更多」翻页
- 搜索：**一屏左右分栏**——左边键盘 + 最近搜过的词，右边实时结果；**停手 2 秒自动搜**，
  不用再按「搜索」；中文关键词并发问 10 个源的 `wd=`，纯拼音再补一路 wsyzy 联想，
  两路结果合并后按贴合度重排
- 详情：海报 + **导演/主演/地区/语言 + 简介（点一下展开全文，缺失时自动跨源回填）** + 选集列表，每行 10 个选集按钮
- 播放：全屏 HLS 播放，上下键选集、左右键快进快退 15 秒、OK 暂停、自动连播下一集
- **续看**：每 5 秒记一次播放位置，退出/切集时再存一次；下次进详情页会自动把焦点落在上次看到的那一集上（该集按钮显示为金色），点进去从断开处接着播
- **自检**：标题栏「自检」按钮，8 步串行验证片源链路（网络参数 → 代理端口 → 多源分类 → **海报下载** →
  多源搜索 → 详情集数 → m3u8 首跳 → 分片首包），全绿就说明能看；下半屏直接显示环形错误日志
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
          → [{id, name, pic}]，唯一支持拼音的一路
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
Src.java    10 个源的 key / base / Referer + 「频道 → 各源 type_id」映射表
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
每个频道 2~10 个源有返回。`maoyan` 的「欧美综艺」是空列表，属源侧情况，不影响聚合结果。

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

**实际接入 App 的 10 个源**（v1.7，`Src.SPEC` 里就这十行，按优先级排）：

| key | 接口 base | 条/页 | 首条海报 | 简介覆盖 | 页首耗时 |
|---|---|---|---|---|---|
| `maoyan` | `https://api.maoyanapi.top/api.php/provide/vod/` | 30 | ✔ | 30/30 | 0.8s |
| `ffzy` | `https://api.ffzyapi.com/api.php/provide/vod/` | 20 | ✔ | 20/20 | 1.2s |
| `bfzy` | `https://bfzyapi.com/api.php/provide/vod/` | 20 | ✔ | 20/20 | 1.4s |
| `ysxq` | `https://www.ysxq.cc/api.php/provide/vod/` | 20 | ✔ | 20/20 | 2.8s |
| `modu` | `https://caiji.moduapi.cc/api.php/provide/vod/` | 20 | ✔ | 15/20 | 1.3s |
| `ikun` | `https://ikunzyapi.com/api.php/provide/vod/` | 20 | ✔ | 17/20 | 1.0s |
| `360zy` | `https://360zy.com/api.php/provide/vod/` | 20 | ✔ | 18/20 | 1.8s |
| `yhzy` | `https://yhzy.cc/api.php/provide/vod/` | 20 | ✔ | 12/20 | 1.4s |
| `wsyzy` | `https://wsyzy.cc/api.php/provide/vod/` | 20 | ✔ | **3/20** | 0.9s |
| `cmov` | `https://www.cmov.cn/api.php/provide/vod/` | 20 | ✔ | 20/20 | 4.9s |

片库规模（`total`）：`ysxq` 429174 > `bfzy` 171653 > `wsyzy` 127940 > `yhzy` 105335 >
`ffzy` 99083 > `modu` 91655 > `cmov` 89832 > `360zy` 74931 > `ikun` 67663 > `maoyan` 34484。

`wsyzy` 排在倒数第二不是因为它差，而是**它最新页的 `vod_content` 覆盖率只有 3/20**，
这正是老板看到「全是电影、没简介」的根因；但它的 `suggest` 是十站里**唯一支持拼音**的，
所以留着当搜索那一路。`cmov` 最慢（4.9s）且不支持 `h=` 参数，放最后兜底。

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
| 聚合只有 `7/10 个源有数据` | 十个源同一瞬间各发一次 TLS 握手，被中间设备当异常流量 reset | 并发从 8 降到 **6**；再加**源级熔断**（连续失败冷却 3 分钟） |
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

### 海报（v1.7 重做三跳兜底）

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

自检页 8 个步骤，逐步验证：

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
| `app/src/com/yg/vodtv/SelfTestActivity.java` | 自检页：8 步链路检测（含海报下载）+ 错误日志面板 |
| `app/src/com/yg/vodtv/Store.java` | 播放位置 + 最近观看 + **搜索历史 6 条**（都按「源_id」区分） |
| `app/src/com/yg/vodtv/Proxy.java` | 本地 HLS 代理（见上） |
| `app/src/com/yg/vodtv/Src.java` | **采集源定义**：10 个源的 base/Referer + 统一频道 → 各源 `type_id` 映射 |
| `app/src/com/yg/vodtv/Multi.java` | **多源聚合**：按频道并行取数、交织去重、搜索合并、简介跨源回填 |
| `app/src/com/yg/vodtv/Api.java` | 单源接口封装（list / detail / wd / suggest）+ 搜索结果贴合度重排 |
| `app/src/com/yg/vodtv/Vod.java` | 影片模型 + `vod_play_url` 选集解析 |
| `app/src/com/yg/vodtv/Http.java` | HTTP 封装 + 信任全部证书的 SSLContext + 5xx 一次重试 |
| `app/src/com/yg/vodtv/Trace.java` | 环形错误日志（120 条） |
| `app/src/com/yg/vodtv/Ui.java` | 尺寸等比缩放、配色、圆角/渐变/焦点背景、海报三跳加载 + **惰性取图** + LRU 缓存 + 首字色块兜底 |
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

`build.sh` 里有防假成功的检查：javac 之后逐个确认 16 个 class 都产出了（v1.7 把 `Src`、`Multi`
也加进了清单），并比对 `MainActivity.class` 与源码时间戳，避免 javac 悄悄失败却打包了旧 class。

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

装好后第一件事：点标题栏右侧的「自检」，8 项全绿就说明片源链路通；有红的，看下半屏错误日志。

## 五、验证记录

### JVM 端到端验证（`test/harness/`，跑的是 APK 里的真实源码）

这套 harness 直接编译并运行 `app/src/com/yg/vodtv/` 下的**同一份** `Api/Http/Proxy/Vod/Trace`，
只把 `android.util.Base64` 和 `org.json` 换成桌面替身 —— 证明的是 APK 里那份代码本身正确，
而不是我用 Python 重写的逻辑正确。

```bash
cd test/harness
env -u MSYS_NO_PATHCONV bash run.sh        # Harness：16 项断言
env -u MSYS_NO_PATHCONV bash run.sh wire   # Wire：用原始 Socket 模拟 NuPlayer，21 项
env -u MSYS_NO_PATHCONV bash run.sh tabs   # Tabs：27 个分类逐项验证，27 项
env -u MSYS_NO_PATHCONV bash run.sh rank   # Rank：搜索结果贴合度排序，4 组
env -u MSYS_NO_PATHCONV bash run.sh meta   # Meta：刮削字段 + 海报双通道，9 项
env -u MSYS_NO_PATHCONV bash run.sh upd    # Upd：版本号 + 镜像兜底 + 真实 Release 下载，19 项
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

**Tabs（24/24 PASS，v1.9 复测）**：首页 24 个统一频道逐个拉第一页，
要求「有数据 + 有海报」。空分类是最容易被忽略的坑 —— App 能开，点进去一片空白。
实测**共聚合 1721 条，每个频道都有数据且全部带海报**，每个频道 2~8 个源返回：

| 频道 | ch | 聚合条 | 源数 | 耗时 | 频道 | ch | 聚合条 | 源数 | 耗时 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 推荐 | 0 | 84 | 6 | 2.3s | 纪录片 | 19 | 84 | 6 | 0.8s |
| 4K电影 | 28 | 40 | 2 | 0.7s | 大陆综艺 | 20 | 75 | 7 | 1.5s |
| 动作 | 12 | 84 | 6 | 0.8s | 日韩综艺 | 21 | 69 | 6 | 1.2s |
| 喜剧 | 13 | 84 | 6 | 1.1s | 港台综艺 | 22 | 19 | 6 | 2.0s |
| 爱情 | 14 | 84 | 6 | 1.0s | 欧美综艺 | 23 | 84 | 7 | 0.9s |
| 科幻 | 15 | 84 | 7 | 0.7s | 国产动漫 | 24 | 73 | 7 | 1.2s |
| 恐怖 | 16 | 84 | 7 | 0.8s | 日韩动漫 | 25 | 47 | 6 | 1.0s |
| 战争 | 18 | 84 | 6 | 0.7s | 欧美动漫 | 26 | 84 | 6 | 1.2s |
| 国产剧 | 5 | 22 | 6 | 0.8s | 悬疑剧 | 29 | 84 | 5 | 1.5s |
| 欧美剧 | 6 | 84 | 6 | 0.9s | 韩剧 | 7 | 69 | 6 | 1.0s |
| 日剧 | 8 | 76 | 8 | 0.9s | 港剧 | 9 | 82 | 6 | 1.1s |
| 台剧 | 10 | 62 | 6 | 1.1s | 泰剧 | 11 | 62 | 6 | 0.7s |

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

`build.sh` 六步全绿。最近一次（v1.9）：APK **66001 字节**，dex **111944 字节**（v1.8 是 105572）；
dex 内确认含 `Src`、`Multi`、`fillIntro`、`CH_REC`、`hasH`、`letter`、`proxied`、`wsrv.nl`、`preferIPv4Stack`、`attempts`
（新增的多源、三跳海报、IPv4 强制与重试代码确实打进去了，不是只改了源码没进包）。
`aapt2 dump badging` → `versionCode='10' versionName='1.9'`、`sdkVersion:'21'`、`targetSdkVersion:'22'`；
`aapt2 dump permissions` → INTERNET / ACCESS_NETWORK_STATE / WAKE_LOCK / **REQUEST_INSTALL_PACKAGES** / **WRITE_EXTERNAL_STORAGE**。
签名：`apksigner verify -v --min-sdk-version 21` → v1/v2/v3 = true、v4 = false（v1 在，Android 5.1 才能装）；
证书 `CN=YG, OU=TV, O=YG, L=Ganzhou, ST=Jiangxi, C=CN`，
SHA-256 `bbdf5b3916abe4c82f5fbee470e8aad21c2f9b8db8ef5688b85ead25bd967f8c`。

### 未验证

**电视上还没跑过。** 老板在**手机**上装过（v1.3），反馈了两条问题：①没有更新检测 ②海报全是黑框、没有刮削 ——
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
5. 自检页 8 项全绿（**含「网络参数」与「海报下载」两步**，片源链路整体通）
6. 标题栏「检查更新」显示「已是最新版本 v1.9」

## 六、目录

```
YG影视TV/
├── README.md
├── .github/workflows/build.yml  # push tag v* 自动出包 + 发 Release
├── .gitignore                   # 排除 build/、*.apk、yg.keystore
├── app/
│   ├── AndroidManifest.xml
│   ├── build.sh                 # 六步构建脚本（Windows / MSYS）
│   ├── build-ci.sh              # 六步构建脚本（Linux / GitHub Actions）
│   ├── yg.keystore              # 签名（yg123456）—— 不进仓库，CI 用 Secret 还原
│   ├── res/values/strings.xml
│   ├── res/drawable/ic_launcher.png
│   ├── src/com/yg/vodtv/*.java  # 16 个源文件（v1.7 新增 Src.java / Multi.java）
│   ├── build/                   # 中间产物
│   ├── YG影视TV.apk             # 成品
│   └── YGTV.apk                 # 同一份的 ASCII 文件名副本（U 盘安装 / 更新下载都用它）
└── test/
    ├── harness/                 # JVM 端到端验证（跑真实源码）
    │   ├── run.sh               # bash run.sh [wire|dump|tabs|rank|meta|upd]
    │   ├── Harness.java         # 16 项链路断言
    │   ├── Wire.java            # 21 项线上字节格式断言（模拟 NuPlayer）
    │   ├── Tabs.java            # 24 个统一频道逐项验证（多源聚合，有数据 + 有海报）
    │   ├── Rank.java            # 搜索结果贴合度排序验证
    │   ├── Meta.java            # 刮削字段 + 海报通道验证（9 项）
    │   ├── Upd.java             # 版本号解析/比较 + 镜像顺序 + 真实 Release 下载（19 项）
    │   ├── Dump.java            # 打印改写后的播放列表
    │   ├── android/*.java       # android.util/net/os/content 替身
    │   └── org/json/*.java            # org.json 替身
    ├── proxy_sim.py             # 早期 Python 复刻（已被 harness 取代）
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
    ├── class_view.txt           # 十源 `id=名称` 对照表（UTF-8，Src.SPEC 就是照它填的）
    └── samples/                 # 已抓的 *.ts / *.m3u8
```

## 七、已知限制

- 片源是第三方接口，上游改了域名或加了防盗链就得跟着改 `Src.java` 的 `SPEC` 数组
  （每个源一行：`key|base|Referer|频道映射`），改完不用动界面代码
- 各源仍可能单个分类返回空或非 JSON，`Api.parsePage()` 全 catch 容错，聚合时跳过该源
- **`limit=` 各源都不认**，所以每页条数只能本地截断（当前 84 条）
- 翻页上限写了 400 页（`curPage < 400`），再往后意义不大
- 没有收藏、没有字幕切换（片源自带硬字幕）
- 首页每次冷启动都重新拉第一页，没有本地缓存
- 个别源偶尔超时，会把整页拖到 7 秒预算上限（结果仍是完整的，只是慢）；
  真机上若觉得「推荐」开页慢，可以调小 `Multi.LIST_BUDGET`
- 代理挑变体时上限 5 Mbps，片源若有更高码率也不会选；老芯片解不动比看不清更糟
- **手机上只能用返回键**：没有触摸点击支持，全程是电视遥控器（DPAD）交互
- **应用内更新依赖 GitHub 可达**：国内网络下 `api.github.com` 可能连不上，此时「检查更新」会提示失败并让你手动去 GitHub 下（下载走 `github.com` 的 Release CDN，通常比 API 好连）

### 搜索能力限制（v1.7 变化）

v1.7 起搜索结果来自两路，覆盖面比单源大得多：

| 输入 | 走哪条路 | 实测结果 |
| --- | --- | --- |
| 中文（如「凡人修仙传」） | 10 个源并发 `?ac=detail&wd=`，再按贴合度重排 | maoyan 2 条、ffzy 3、ysxq 20、bfzy 20、ikun 4、360zy 11、yhzy 3、cmov 7、modu 10（`wsyzy` 不支持 `wd=`，返回纯文本） |
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
