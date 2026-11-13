#!/usr/bin/env bash
# 在桌面 JVM 上直接运行 App 里真实的 Api/Http/Proxy/Trace/Vod/Update，
# 验证「分类 → 搜索 → 详情 → 代理三层跳转 → 分片」全链路。
# 与 APK 里的代码是同一份文件，只把 android.util/org.json 换成桌面替身。
#
# 用法：
#   bash run.sh        跑 Harness（16 项断言，全绿才 exit 0）
#   bash run.sh wire   跑 Wire（用原始 Socket 模拟 NuPlayer，检查线上字节格式）
#   bash run.sh dump   打印真实改写的播放列表头部（观察保留的 HLS 标签）
#   bash run.sh tabs   跑 Tabs（27 个分类逐个查，要求有数据且有海报）
#   bash run.sh rank   跑 Rank（检查搜索结果的贴合度排序）
#   bash run.sh meta   跑 Meta（刮削字段 + 海报双通道）
#   bash run.sh upd    跑 Upd（版本号解析 + GitHub Release 检查）
#   bash run.sh search 跑 Search（用真实 Multi.search 查当前热播剧）
set -e
cd "$(dirname "$0")"
ROOT="$(cd ../../.. && pwd)"
JDK="$ROOT/海信电视刷机/toolchain/jdk-17.0.21+9"
JAVAC="$JDK/bin/javac"
JAVA="$JDK/bin/java"
SRC="$ROOT/YG影视TV/app/src"
OUT="build"

MAIN="Harness"
if [ "$1" = "dump" ]; then
    MAIN="Dump"
elif [ "$1" = "wire" ]; then
    MAIN="Wire"
elif [ "$1" = "tabs" ]; then
    MAIN="Tabs"
elif [ "$1" = "rank" ]; then
    MAIN="Rank"
elif [ "$1" = "meta" ]; then
    MAIN="Meta"
elif [ "$1" = "upd" ]; then
    MAIN="Upd"
elif [ "$1" = "search" ]; then
    MAIN="Search"
elif [ "$1" = "srctab" ]; then
    MAIN="SrcTab"
elif [ "$1" = "cachetab" ]; then
    MAIN="CacheTab"
elif [ "$1" = "pytab" ]; then
    MAIN="PyTab"
elif [ "$1" = "livetab" ]; then
    MAIN="LiveTab"
fi

rm -rf "$OUT"
mkdir -p "$OUT"

"$JAVAC" -encoding UTF-8 -nowarn -d "$OUT" \
    android/util/Base64.java \
    android/content/Context.java \
    android/content/Intent.java \
    android/net/Uri.java \
    android/os/Environment.java \
    android/os/Handler.java \
    android/os/Looper.java \
    org/json/JSONObject.java \
    org/json/JSONArray.java \
    org/json/JSONException.java \
    "$SRC/com/yg/vodtv/Trace.java" \
    "$SRC/com/yg/vodtv/Http.java" \
    "$SRC/com/yg/vodtv/Vod.java" \
    "$SRC/com/yg/vodtv/Api.java" \
    "$SRC/com/yg/vodtv/Proxy.java" \
    "$SRC/com/yg/vodtv/Ver.java" \
    "$SRC/com/yg/vodtv/Update.java" \
    "$SRC/com/yg/vodtv/Src.java" \
    "$SRC/com/yg/vodtv/Multi.java" \
    "$SRC/com/yg/vodtv/Cache.java" \
    "$SRC/com/yg/vodtv/PyIndex.java" \
    "$SRC/com/yg/vodtv/Live.java" \
    Harness.java \
    Wire.java \
    Tabs.java \
    Dump.java \
    Rank.java \
    Meta.java \
    Upd.java \
    Search.java \
    SrcTab.java \
    CacheTab.java \
    PyTab.java \
    LiveTab.java

[ -f "$OUT/$MAIN.class" ] || { echo "FAILED: 编译未产出 $MAIN.class"; exit 1; }

exec "$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT" "$MAIN"
