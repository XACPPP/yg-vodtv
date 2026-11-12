# YG影视TV

自用的电视端影视 App，纯原生 Android（无 Gradle、无第三方库），专为海信 VIDAA 电视
（Android 5.1.1 / API 22，已 root）定制。片源来自 `wsyzy` 系列接口，属于 `88lin/video_vip`
用户脚本所依赖的同一套「无损云解析」链路，但本 App 是原生重写，不依赖浏览器和油猴脚本。

当前版本：**v1.3（versionCode 4）**，首页标题栏右侧会显示版本与构建时间，用来确认电视上跑的是哪一版。

## 一、它是什么

一句话：**遥控器点开就能搜片、选集、播放的电视 App。**

- 首页：27 个分类（推荐、4K 电影、动作、喜剧、国产剧、动漫、综艺、短剧……）海报墙，遥控器方向键浏览，点「加载更多」翻页
- 搜索：自绘拼音/英文键盘，输入片名全拼或中文即可（走 suggest 联想接口）
- 详情：海报 + 简介 + 选集列表，每行 6 个选集按钮
- 播放：全屏 HLS 播放，上下键选集、左右键快进快退 15 秒、OK 暂停、自动连播下一集
- **续看**：每 5 秒记一次播放位置，退出/切集时再存一次；下次进详情页会自动把焦点落在上次看到的那一集上（该集按钮显示为金色），点进去从断开处接着播
- **自检**：标题栏「自检」按钮，7 步串行验证片源链路，全绿就说明能看；下半屏直接显示环形错误日志

## 二、技术方案

### 片源链路

```
搜索联想  GET https://wsyzy.cc/index.php/ajax/suggest?mid=1&wd=<关键词>
          → [{id, name, pic}]
详情      GET https://wsyzy.cc/api.php/provide/vod/?ac=detail&ids=<id>
          → vod_play_url = "第01集$https://.../video/index.m3u8#第02集$..."
分类列表  GET https://wsyzy.cc/api.php/provide/vod/?ac=detail&t=<type_id>&pg=<页码>
          → 20 条/页
```

两点必须记住：

- `wd=` 全量搜索**不被该 API 支持**（返回"暂不支持搜索"），所以搜索必须先走 suggest 拿 id 再查详情。
- 接口域名统一用主站 `wsyzy.cc`，不用 `api.wsyzy.net`。两者返回**完全一致**，但少一个域名就少一次
  TLS 握手与一套证书链，Android 5.1 上这一步最容易出问题（实测 `api.wsyzy.net` 偶发 522）。
- `ac=list` 比 `ac=detail` 小十倍，但**不返回 `vod_pic`/`vod_year`/`vod_area`**，海报墙会全空，所以只能用 `ac=detail`。

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

海报图片则走 `http://img1.wsyzy.org/...`（`Ui.httpize()` 把 https 强降为 http），绕开证书问题。

### 出错时怎么定位：`Trace` + 自检页

电视上黑屏、闪退时拿不到 logcat，所以内置了环形错误日志 `Trace`（保留最近 120 条，
`HH:mm:ss [tag] msg` 格式）。`Http` / `Api` / `Proxy` / `PlayerActivity` 每个失败点都会写一条，
自检页下半屏直接显示最近 14 条，**拍照即可反馈**。

自检页 7 个步骤，逐步验证：

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
| `app/src/com/yg/vodtv/MainActivity.java` | 首页：分类条 + 海报墙 + 拼音键盘搜索 |
| `app/src/com/yg/vodtv/DetailActivity.java` | 详情页：海报 + meta + 选集网格 |
| `app/src/com/yg/vodtv/PlayerActivity.java` | 播放页：VideoView + 自绘进度条 + 选集浮层 |
| `app/src/com/yg/vodtv/SelfTestActivity.java` | 自检页：7 步链路检测 + 错误日志面板 |
| `app/src/com/yg/vodtv/Proxy.java` | 本地 HLS 代理（见上） |
| `app/src/com/yg/vodtv/Api.java` | 片源接口封装（list / detail / suggest）+ 搜索结果贴合度重排 |
| `app/src/com/yg/vodtv/Vod.java` | 影片模型 + `vod_play_url` 选集解析 |
| `app/src/com/yg/vodtv/Http.java` | HTTP 封装 + 信任全部证书的 SSLContext + 5xx 一次重试 |
| `app/src/com/yg/vodtv/Trace.java` | 环形错误日志（120 条） |
| `app/src/com/yg/vodtv/Ui.java` | 尺寸等比缩放、配色、圆角/焦点背景、海报异步加载 LRU 缓存 |
| `app/src/com/yg/vodtv/Cards.java` | 海报卡片组件 |
| `app/src/com/yg/vodtv/Ver.java` | 版本号与构建时间（首页右上角显示） |
| `app/src/com/yg/vodtv/Store.java` | SharedPreferences 播放记忆：每剧每集看到哪儿、上次看到哪一集 |

## 三、构建

不用 Gradle，直接用 aapt2 + javac + d8 + apksigner 手动打包（复用 `../海信电视刷机/toolchain/`）：

```bash
cd app
env -u MSYS_NO_PATHCONV bash build.sh
```

产物：`app/YG影视TV.apk`（约 45 KB），`build.sh` 末尾会同时复制一份**纯 ASCII 文件名**的
`app/YGTV.apk`（内容完全相同）—— 有些电视的 U 盘安装器认不出中文文件名，装不上时换这个。

**必须带 `env -u MSYS_NO_PATHCONV`**，否则 Windows 上 aapt2.exe 收到 `/d/...` 形式的路径会报
`failed to open APK: I/O error`。

签名：`app/yg.keystore`，storepass/keypass 都是 `yg123456`，alias `yg`。

APK 信息：`package=com.yg.vodtv`、`versionCode=2`、`versionName=1.1`、`minSdkVersion=21`、
`targetSdkVersion=22`、`screenOrientation=landscape`、`usesCleartextTraffic=true`（海报和本地代理
都是 http，必须开）。签名同时带 v1/v2/v3 —— **v1 是 Android 5.1 能装的前提**。

`build.sh` 里有防假成功的检查：javac 之后逐个确认 12 个 class 都产出了，并比对
`MainActivity.class` 与源码时间戳，避免 javac 悄悄失败却打包了旧 class。

## 四、安装

### 方式 A：U 盘（电视 ADB 没开时用这个，最稳）

1. 把 `YG影视TV.apk` 拷到 U 盘根目录（**装不上或电视里看不到这个文件，就改用同目录的 `YGTV.apk`**，
   纯 ASCII 文件名，内容完全一样）
2. U 盘插到电视 USB 口
3. 电视「我的应用」→「U盘安装」/「媒体中心」→ 找到 APK 点击安装

### 方式 B：ADB（需要电视开启 ADB 调试）

```powershell
D:\platform-tools\adb.exe connect 10.1.1.14:5555
D:\platform-tools\adb.exe devices
D:\platform-tools\adb.exe install -r "D:\Codexspace\做点小玩意\YG影视TV\app\YG影视TV.apk"
D:\platform-tools\adb.exe shell am start -n com.yg.vodtv/.MainActivity
```

装好后第一件事：点标题栏右侧的「自检」，7 项全绿就说明片源链路通；有红的，看下半屏错误日志。

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

**Tabs（27/27 PASS）**：首页 27 个分类标签逐个拉第一页，要求「有数据 + 有海报」。
空分类是最容易被忽略的坑 —— App 能开，点进去一片空白。实测每个分类都是 20 条、20 张海报：

| 分类 | type | 部数 | 分类 | type | 部数 |
| --- | --- | --- | --- | --- | --- |
| 推荐 | 0 | 127908 | 大陆综艺 | 25 | 2681 |
| 4K电影 | 62 | 848 | 日韩综艺 | 26 | 410 |
| 动作 | 6 | 5763 | 港台综艺 | 27 | 174 |
| 喜剧 | 7 | 9406 | 欧美综艺 | 28 | 504 |
| 爱情 | 8 | 1443 | 国产动漫 | 29 | 1732 |
| 科幻 | 9 | 1264 | 日韩动漫 | 30 | 4594 |
| 恐怖 | 10 | 4865 | 欧美动漫 | 31 | 1070 |
| 战争 | 12 | 425 | 动画片 | 39 | 1436 |
| 国产剧 | 13 | 8869 | 反转爽剧 | 65 | 4690 |
| 欧美剧 | 14 | 3674 | 现代都市 | 69 | 11837 |
| 韩剧 | 15 | 947 | 短剧 | 76 | 341 |
| 日剧 | 16 | 1842 | 港剧 | 17 | 1797 |
| 台剧 | 18 | 355 | 泰剧 | 19 | 971 |
| 纪录片 | 20 | 2481 | | | |

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

`build.sh` 六步全绿。最近一次（v1.3）：APK 45521 字节，md5 `1ff81d8410d2c393d9d407080ae5a1e4`
（`YG影视TV.apk` 与 `YGTV.apk` 同一份）；
`classes.dex` 72004 字节，md5 `9a500d0cfa86b7396f614a3acfa2d32a`；dex 内确认含 `Ver`、
`SelfTestActivity`、`focusFirstKey`、`rank`、`score`、`vod_en`、`Store`、`savePos`、`v1.3`、`wsyzy.cc/api.php`，
且 `api.wsyzy.net` 已彻底消失。
`aapt2 dump badging` → `versionCode='4' versionName='1.3'`、`sdkVersion:'21'`、`targetSdkVersion:'22'`。
签名 `apksigner verify -v --min-sdk-version 21` → v1/v2/v3 = true、v4 = false（v1 在，Android 5.1 才能装）。

签名：`apksigner verify -v --min-sdk-version 21` → v1/v2/v3 = true、v4 = false；
证书 `CN=YG, OU=TV, O=YG, L=Ganzhou, ST=Jiangxi, C=CN`，
SHA-256 `bbdf5b3916abe4c82f5fbee470e8aad21c2f9b8db8ef5688b85ead25bd967f8c`。

### 未验证

**App 没在真机上跑过。** 以下项目前只是「桌面逻辑验证通过」，未经实机确认：VideoView 在本机代理
上的 HLS 起播、遥控器焦点在 7 列海报墙上的移动手感、选集浮层弹出时的焦点抢占、自检页实际读数。

排查设备时的**重要教训**：本次开发机开着 Mihomo TUN，它会把**任意地址的任意 TCP 端口**都伪装成
「连得上」（实测 `192.0.2.1:5555`、`203.0.113.9:1234`、`8.8.8.8:9999` 全部 connect 成功，ping 也是 0ms），
所以「端口通 / ping 通」**完全不能证明设备在线**。必须绑物理网卡验证：

```bash
ping -n 2 -S 192.168.1.9 10.1.1.14     # 绑 WiFi 网卡，结果 100% 丢包
```

最终确认：开发机当时在 `192.168.1.0/24`（WiFi），不在 `10.1.1.0/24`，
`10.1.1.14` 只是 TUN 的假象。

真机验证的判定标准（装好后按这个看）：
1. 首页出分类海报墙（`Api` 与海报加载通）
2. 点进任意剧集能看到选集（`Vod.parseEpisodes` 对真实数据正确）
3. 点某一集能起播（`Proxy` 三跳改写 + VideoView 都通）
4. 上键弹选集、OK 暂停、返回退出（遥控器映射通）
5. 自检页 7 项全绿（片源链路整体通）

## 六、目录

```
YG影视TV/
├── README.md
├── app/
│   ├── AndroidManifest.xml
│   ├── build.sh                 # 六步构建脚本
│   ├── yg.keystore              # 签名（yg123456）
│   ├── res/values/strings.xml
│   ├── res/drawable/ic_launcher.png
│   ├── src/com/yg/vodtv/*.java  # 13 个源文件（含 Store.java 播放记忆）
│   ├── build/                   # 中间产物
│   ├── YG影视TV.apk             # 成品
│   └── YGTV.apk                 # 同一份的 ASCII 文件名副本（U 盘安装备用）
└── test/
    ├── harness/                 # JVM 端到端验证（跑真实源码）
    │   ├── run.sh               # bash run.sh [wire|dump|tabs|rank]
    │   ├── Harness.java         # 16 项链路断言
    │   ├── Wire.java            # 21 项线上字节格式断言（模拟 NuPlayer）
    │   ├── Tabs.java            # 27 个分类逐项验证（有数据 + 有海报）
    │   ├── Rank.java            # 搜索结果贴合度排序验证
    │   ├── Dump.java            # 打印改写后的播放列表
    │   ├── android/util/Base64.java   # android.util.Base64 替身
    │   └── org/json/*.java            # org.json 替身
    ├── proxy_sim.py             # 早期 Python 复刻（已被 harness 取代）
    ├── parse_codec.py           # 离线解析 TS 编码
    ├── codec_survey.py          # 跨分类抽样探测编码
    ├── fetch_sample.py          # 抓样本分片落盘
    └── samples/                 # 已抓的 *.ts / *.m3u8
```

## 七、已知限制

- 片源是第三方接口，上游改了域名或加了防盗链就得跟着改 `Api.java` 的常量
- 分类里有一部分 `type_id` 返回空或非 JSON（如 1/2/3/4/67），`Api.parsePage()` 已做全 catch 容错
- 翻页上限写了 400 页（`curPage < 400`），再往后意义不大
- 没有收藏、没有字幕切换（片源自带硬字幕）
- 首页每次冷启动都重新拉第一页，没有本地缓存
- 代理挑变体时上限 5 Mbps，片源若有更高码率也不会选；老芯片解不动比看不清更糟

### 搜索能力限制（实测）

`suggest` 接口支持中文和**完整拼音**，但**不支持拼音首字母缩写**：

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
