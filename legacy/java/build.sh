#!/bin/bash
# YG影视TV 构建脚本：aapt2 -> javac -> d8 -> 打包 -> zipalign -> apksigner
# 用法： cd app && env -u MSYS_NO_PATHCONV ./build.sh
set -e
cd "$(dirname "$0")"
APP="$(pwd)"
ROOT="$(cd ../.. && pwd)"                        # D:/Codexspace/做点小玩意
TOOLS="$ROOT/海信电视刷机/toolchain"              # 复用海信电视刷机项目的工具链
BT="$TOOLS/android-sdk/build-tools/30.0.3"
PLAT="$TOOLS/android-sdk/platforms/android-30/android.jar"
JAVA="$TOOLS/jdk-17.0.21+9/bin/java"
JAVAC="$TOOLS/jdk-17.0.21+9/bin/javac"
KEYTOOL="$TOOLS/jdk-17.0.21+9/bin/keytool"
OUT="YG影视TV.apk"

for f in "$BT/aapt2.exe" "$PLAT" "$JAVA" "$JAVAC"; do
  [ -f "$f" ] || { echo "[build] 缺少工具: $f" >&2; exit 1; }
done

rm -rf build
mkdir -p build/gen build/classes build/dex

if [ ! -f yg.keystore ]; then
  "$KEYTOOL" -genkeypair -keystore yg.keystore -storepass yg123456 -keypass yg123456 \
    -alias yg -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=YG, OU=TV, O=YG, L=Ganzhou, S=Jiangxi, C=CN" 2>/dev/null
  echo "[build] keystore created"
fi

echo "[1/6] aapt2 compile"
"$BT/aapt2.exe" compile --dir res -o build/res.zip

echo "[2/6] aapt2 link"
# 台标要打进 APK：直播卡片一屏 18 张，走网络首屏必等 2~3 秒，图床还可能挂。
# 但 assets 目录下还有 py/*.txt（4.6MB）等只给 CI 用的原始数据，不能整个塞进 APK，
# 所以只把 assets/logo 拷到一个干净的临时 assets 根，-A 指向它。
rm -rf build/assets
mkdir -p build/assets
if [ -d src/main/assets/logo ]; then
  cp -r src/main/assets/logo build/assets/logo
fi
"$BT/aapt2.exe" link -o build/base.apk \
  --manifest AndroidManifest.xml \
  -I "$PLAT" \
  -R build/res.zip \
  -A build/assets \
  --java build/gen \
  --auto-add-overlay

echo "[3/6] javac"
find src build/gen -name '*.java' > build/sources.txt
"$JAVAC" -source 8 -target 8 -nowarn -encoding UTF-8 \
  -bootclasspath "$PLAT" \
  -classpath "$PLAT" \
  -d build/classes \
  @build/sources.txt 2>&1 | grep -v 'bootstrap class path' || true
# javac 失败必须中止，否则 d8 会打包旧 class 造成假成功
for c in MainActivity DetailActivity PlayerActivity SelfTestActivity Proxy Trace Api Http Vod Ui Cards Chrome Ver Store Update Src Multi Cache Prefetch SrcSync PyIndex Live LiveSync LivePlayerActivity; do
  if [ ! -f "build/classes/com/yg/vodtv/$c.class" ]; then
    echo "[build] FAILED: javac 未产出 $c.class" >&2
    exit 1
  fi
done
if [ build/classes/com/yg/vodtv/MainActivity.class -ot src/com/yg/vodtv/MainActivity.java ]; then
  echo "[build] FAILED: MainActivity.class 比源码旧，javac 未成功" >&2
  exit 1
fi
if [ build/classes/com/yg/vodtv/LivePlayerActivity.class -ot src/com/yg/vodtv/LivePlayerActivity.java ]; then
  echo "[build] FAILED: LivePlayerActivity.class 比源码旧，javac 未成功" >&2
  exit 1
fi

echo "[4/6] d8"
find build/classes -name '*.class' > build/classes.txt
"$JAVA" -Xmx1024M -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$PLAT" --min-api 21 --output build/dex @build/classes.txt

echo "[5/6] package"
cp build/base.apk build/unsigned.apk
python - <<'PY'
import zipfile, shutil
shutil.copy('build/base.apk', 'build/unsigned.apk')
with zipfile.ZipFile('build/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex', 'classes.dex')
print('[build] classes.dex injected')
PY

"$BT/zipalign.exe" -f -p 4 build/unsigned.apk build/aligned.apk

echo "[6/6] sign"
export JAVA_HOME="$TOOLS/jdk-17.0.21+9"
"$BT/apksigner.bat" sign --ks yg.keystore --ks-pass pass:yg123456 --key-pass pass:yg123456 \
  --ks-key-alias yg --min-sdk-version 21 --out "$OUT" build/aligned.apk
"$BT/apksigner.bat" verify "$OUT"

echo "[build] OK -> $APP/$OUT"
# 电视机的 U 盘安装器对中文文件名支持不一，另存一份纯 ASCII 名字，装不上时换这个试
cp -f "$OUT" YGTV.apk
ls -l "$OUT" YGTV.apk
md5sum "$OUT" YGTV.apk
