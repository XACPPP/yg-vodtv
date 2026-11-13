#!/usr/bin/env bash
# CI（Linux）构建脚本：aapt2 -> javac -> d8 -> 打包 -> zipalign -> apksigner
# 与本机 build.sh 等价，只是工具路径来自 ANDROID_HOME，且没有 .exe 后缀。
# 用法： bash app/build-ci.sh
set -e
cd "$(dirname "$0")"
APP="$(pwd)"
: "${ANDROID_HOME:?需要 ANDROID_HOME（GitHub Actions 的 ubuntu 镜像自带）}"
BT="$ANDROID_HOME/build-tools/30.0.3"
PLAT="$ANDROID_HOME/platforms/android-30/android.jar"
OUT="YG影视TV.apk"

for f in "$BT/aapt2" "$PLAT"; do
  [ -f "$f" ] || { echo "[build] 缺少工具: $f" >&2; exit 1; }
done
[ -f yg.keystore ] || { echo "[build] 缺少签名文件 app/yg.keystore（CI 里由 secret 还原）" >&2; exit 1; }

rm -rf build
mkdir -p build/gen build/classes build/dex

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o build/base.apk \
  --manifest AndroidManifest.xml \
  -I "$PLAT" \
  -R build/res.zip \
  --java build/gen \
  --auto-add-overlay

echo "[3/6] javac"
find src build/gen -name '*.java' > build/sources.txt
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
  -bootclasspath "$PLAT" \
  -classpath "$PLAT" \
  -d build/classes \
  @build/sources.txt 2>&1 | grep -v 'bootstrap class path' || true
# javac 失败必须中止，否则 d8 会打包旧 class 造成假成功
for c in MainActivity DetailActivity PlayerActivity SelfTestActivity Proxy Trace Api Http Vod Ui Cards Ver Store Update Src Multi Cache Prefetch SrcSync; do
  if [ ! -f "build/classes/com/yg/vodtv/$c.class" ]; then
    echo "[build] FAILED: javac 未产出 $c.class" >&2
    exit 1
  fi
done

echo "[4/6] d8"
find build/classes -name '*.class' > build/classes.txt
java -Xmx1024M -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$PLAT" --min-api 21 --output build/dex @build/classes.txt

echo "[5/6] package"
cp build/base.apk build/unsigned.apk
python3 - <<'PY'
import zipfile, shutil
shutil.copy('build/base.apk', 'build/unsigned.apk')
with zipfile.ZipFile('build/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex', 'classes.dex')
print('[build] classes.dex injected')
PY

"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

echo "[6/6] sign"
"$BT/apksigner" sign --ks yg.keystore --ks-pass pass:yg123456 --key-pass pass:yg123456 \
  --ks-key-alias yg --min-sdk-version 21 --out "$OUT" build/aligned.apk
"$BT/apksigner" verify "$OUT"

echo "[build] OK -> $APP/$OUT"
# 电视机的 U 盘安装器对中文文件名支持不一，另存一份纯 ASCII 名字
cp -f "$OUT" YGTV.apk
ls -l "$OUT" YGTV.apk
