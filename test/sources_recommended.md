# YG影视TV 可用采集源（实测）

测试时间 2026-11-12 12:15–12:18（北京时间），UA 固定 Android 5.1.1 VIDAA TV / Chrome 78，超时 12s。
判定标准：`ac=detail&pg=1` 返回 200 + 合法 JSON + `list` 非空 + 含 `vod_id/vod_name/vod_pic/vod_play_url`，
且抓取首条 `vod_play_url` 里的 m3u8 真能 200 返回 `#EXTM3U`。

| # | 接口 base | 条/页 | 国产剧 t= | 剧2026 | 综艺 t= | 动漫 t= | m3u8 | suggest | 备注 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `https://api.maoyanapi.top/api.php/provide/vod/` | 30 | 13 | 30/30 | 34(大陆综艺) | 39(欧美动漫) | LIVE | OK | 30 条/页，全部 2026；三分类都满；搜索正常 |
| 2 | `https://api.guangsuapi.com/api.php/provide/vod/` | 20 | 13 | 20/20 | 37(大陆综艺) | 41(中国动漫) | LIVE | OK | 同源镜像最多（21 个域名同一内容库），容灾能力最强 |
| 3 | `https://caiji.moduapi.cc/api.php/provide/vod/` | 20 | 26 | 18/20 | 34(大陆综艺) | 1(国产动漫) | LIVE | OK | moduapi 同源三域名之一；动漫/综艺更新快 |
| 4 | `https://api.ffzyapi.com/api.php/provide/vod/` | 20 | 13 | 18/20 | 25(大陆综艺) | 29(国产动漫) | LIVE | OK | ffzy 主站；老片排序问题已消失，首条即 2026 |
| 5 | `https://www.ysxq.cc/api.php/provide/vod/` | 20 | 13 | 20/20 | 30(国内综艺) | 33(大陆动漫) | LIVE | OK | 自有内容库；国产剧 20/20 全是 2026；会 301 到 ysxq.cc |
| 6 | `https://yhzy.cc/api.php/provide/vod/` | 20 | 13 | 20/20 | 26(大陆综艺) | 30(国产动漫) | LIVE | OK | 自有内容库；国产剧 20/20 全是 2026 |
| 7 | `https://bfzyapi.com/api.php/provide/vod/` | 20 | 31 | 18/20 | 46(大陆综艺) | 40(国产动漫) | LIVE | OK | 自有内容库；综艺分类号 t=46 较特殊，搜索一次返 20 条 |
| 8 | `https://ikunzyapi.com/api.php/provide/vod/` | 20 | 23 | 20/20 | 31(大陆综艺) | 35(国产动漫) | LIVE | OK | 自有内容库；动漫更新快（转生成为魔剑了 第二季） |
| 9 | `https://www.cmov.cn/api.php/provide/vod/` | 20 | 13 | 19/20 | 20(大陆综艺) | 24(国产动漫) | LIVE | OK | 备用：国产剧 19/20 是 2026，搜索用真实片名可命中 |
| 10 | `https://360zy.com/api.php/provide/vod/` | 20 | 13 | 20/20 | 34(大陆综艺) | 38(国产动漫) | LIVE | OK | 备用：与 caiji.moduapi.cc 同内容库，二选一即可 |

## 每个源的首条实证

| 接口 base | 首页首条 | 年份 | vod_time | 国产剧首条 | 年份 | m3u8 样例 |
|---|---|---|---|---|---|---|
| `https://api.maoyanapi.top/api.php/provide/vod/` | 聊斋问心 | 2026 | 2026-11-12 11:19:49 | 今天是太阳雨 | 2026 | `https://play.maoyanplay.top/20261030/PMfjQHIn/index.m3u8` |
| `https://api.guangsuapi.com/api.php/provide/vod/` | 末世后的酒店 | 2026 | 2026-11-12 10:38:02 | 谷雨 | 2026 | `https://v.gsuus.com/play/e0R2wlyb/index.m3u8` |
| `https://caiji.moduapi.cc/api.php/provide/vod/` | 金牌调解2026 | 2026 | 2026-11-12 12:12:41 | 尬先生 | 2026 | `https://play.modujx11.com/20260513/FtsDJS7y/index.m3u8` |
| `https://api.ffzyapi.com/api.php/provide/vod/` | 今天是太阳雨 | 2026 | 2026-11-12 12:09:31 | 今天是太阳雨 | 2026 | `https://super.ffzy-online6.com/20261106/45022_ddb7775a/index.m3u8` |
| `https://www.ysxq.cc/api.php/provide/vod/` | 难得读叔 | 2026 | 2026-11-12 12:01:06 | 走马上任 | 2026 | `https://svip.yzzy33-play.vip/20261111/22559_246ba26b/index.m3u8` |
| `https://yhzy.cc/api.php/provide/vod/` | 救命病栋24小时第6季 | 2026 | 2026-11-12 12:00:22 | 以神之名，对话万物 | 2026 | `https://vod12.wgslsw.com/20261014/hPEMAhc1cT87/index.m3u8` |
| `https://bfzyapi.com/api.php/provide/vod/` | 音乐缘计划3 | 2026 | 2026-11-12 12:14:24 | 鉴定 | 2026 | `https://v.fengbao14.com/video/yinleyuanjihua3/6f735a823138/index.m3u8` |
| `https://ikunzyapi.com/api.php/provide/vod/` | 转生成为魔剑了第二季 | 2026 | 2026-11-12 00:09:41 | 谷雨 | 2026 | `https://bfikuncdn.com/20261001/KhgbNd0r/index.m3u8` |
| `https://www.cmov.cn/api.php/provide/vod/` | 诡秘之主特别篇猎物 | 2026 | 2026-06-21 09:45:44 | 炽夏 | 2026 | `https://vip.ffzy-plays.com/20260620/54978_7207dfcc/index.m3u8` |
| `https://360zy.com/api.php/provide/vod/` | 欢乐集结号2026 | 2026 | 2026-11-12 11:36:02 | 天命·成凰 | 2026 | `https://vod1.maowushi.com/20260514/ZgPdr9n8/index.m3u8` |

## 排除说明

| 接口 | 原因 |
|---|---|
| `https://api.juliang.live/api.php/provide/vod/` | 首轮探测正常，复核时全路径 404（站点已下线或改路径），不可用 |
| `https://api.1080zyku.com/api.php/provide/vod/` | 返回 RSS XML 而非 JSON，`at/json` 也一样，不是标准 JSON 接口 |
| `https://ys.cxkjg.cn/api.php/provide/vod/` | 308 跳转后落回 404 的 HTML 页面（站点前端路由），无 JSON |
| `https://api.ukuapi.com/api.php/provide/vod/` | 列表接口可用，但 `suggest` 301 到 `api.ukuapi88.com` 后返回 HTML，搜索链路断 |
| `https://api.wujinapi.me/api.php/provide/vod/` | 列表接口可用，但 `suggest` 被 Cloudflare 挡成 403，搜索链路断 |
| `cj.lziapi.com` / `lzizy*.com` 全族 | 列表 JSON 正常，但所有 m3u8 直连取不到（DEAD），播放必失败 |
| `caiji.maotaizy.cc` / `mtzy5.com` / `tyyszy*` / `hong.hgyx.vip` / `api.zeqaht.com` | 同上，m3u8 DEAD |
| `cdn.dzzyapi.com` | 可用但国产剧首页 2026 数为 0（最新只到 2025），内容偏旧 |
| 成人向站点（seep.eu.org、ckzy.me、api.heiapi.cc 等） | 接口可用但内容不适合家庭 TV，已单列在 `sources_report.txt` [B] 段 |
