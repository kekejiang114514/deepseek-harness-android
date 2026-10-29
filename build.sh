#!/bin/sh
# DShAndroid 构建脚本 —— 不依赖 aapt2 / Gradle / Android Studio
#
# 需要三样外部文件（见 README「构建依赖」）：
#   ANDROID_JAR      android.jar（任意 API 29+ 的 platform 包）
#   R8_JAR           r8.jar（内含 d8，纯 JVM 工具）
#   APKSIGNER_JAR    apksigner.jar（Android build-tools 里的）
# 可用环境变量覆盖，否则按下面的默认位置查找。
#
# 用法：
#   sh build.sh                 构建并签名（需要 keystore，默认 ./keys/release.p12）
#   SKIP_SIGN=1 sh build.sh     只构建未签名包（没 keystore 时用这个）
set -e

HERE=$(cd "$(dirname "$0")" && pwd)
APP=$HERE/app
OUT=$HERE/out
TOOLS=$HERE/tools

JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}
export JAVA_HOME
PATH=$JAVA_HOME/bin:$PATH
export PATH
unset LD_LIBRARY_PATH PREFIX   # Termux/PRoot 环境里这两个会干扰 JDK

ANDROID_JAR=${ANDROID_JAR:-$HERE/deps/android.jar}
R8_JAR=${R8_JAR:-$HERE/deps/r8.jar}
APKSIGNER_JAR=${APKSIGNER_JAR:-$HERE/deps/apksigner.jar}
KEYSTORE=${KEYSTORE:-$HERE/keys/release.p12}
KS_ALIAS=${KS_ALIAS:-dshandroid}

for f in "$ANDROID_JAR" "$R8_JAR"; do
  [ -f "$f" ] || { echo "缺少依赖: $f（见 README「构建依赖」）"; exit 1; }
done

mkdir -p "$OUT"

echo "[1/6] javac"
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
# 注意：-bootclasspath 指向 android.jar 时没有 LambdaMetafactory，
# 源码里全部用匿名内部类，不要写 lambda。
if ! javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
    -d "$OUT/classes" $(find "$APP/src" -name '*.java') > "$OUT/javac.log" 2>&1; then
  cat "$OUT/javac.log"
  echo "javac 失败"
  exit 1
fi
grep -vE "^Note|bootstrap class path|source value 8|target value 8|warning" "$OUT/javac.log" || true

echo "[2/6] d8"
rm -rf "$OUT/dex" && mkdir -p "$OUT/dex"
java -cp "$R8_JAR" com.android.tools.r8.D8 --min-api 23 --lib "$ANDROID_JAR" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[3/6] resources.arsc"
python3 "$TOOLS/arsc_build.py" "$OUT/resources.arsc"

echo "[4/6] AndroidManifest.xml + 无障碍配置"
python3 "$TOOLS/manifest_gen.py" "$OUT/AndroidManifest.xml"
mkdir -p "$OUT/res/xml"
python3 "$TOOLS/make_a11y_xml.py" "$OUT/res/xml/accessibility_service_config.xml"

echo "[5/6] 打包"
python3 "$TOOLS/pack_apk.py" "$OUT/unsigned.apk" \
  z:AndroidManifest.xml="$OUT/AndroidManifest.xml" \
  z:classes.dex="$OUT/dex/classes.dex" \
  s:resources.arsc="$OUT/resources.arsc" \
  s:res/mipmap/ic_launcher.png="$APP/res/mipmap/ic_launcher.png" \
  z:res/xml/accessibility_service_config.xml="$OUT/res/xml/accessibility_service_config.xml"

if [ -n "$SKIP_SIGN" ]; then
  echo "跳过签名 -> $OUT/unsigned.apk"
  exit 0
fi

echo "[6/6] 签名"
[ -f "$KEYSTORE" ] || { echo "缺少 keystore: $KEYSTORE（或用 SKIP_SIGN=1）"; exit 1; }
if [ -n "$STORE_PASS_FILE" ]; then
  KS_PASS=$(cat "$STORE_PASS_FILE")
else
  KS_PASS=${STORE_PASS:?需要 STORE_PASS 环境变量或 STORE_PASS_FILE}
fi
java -jar "$APKSIGNER_JAR" sign --ks "$KEYSTORE" --ks-pass env:KS_PASS \
  --ks-key-alias "$KS_ALIAS" --key-pass env:KS_PASS \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --min-sdk-version 23 --out "$OUT/DShAndroid.apk" "$OUT/unsigned.apk"

java -jar "$APKSIGNER_JAR" verify --verbose "$OUT/DShAndroid.apk" | grep -E "Verified|scheme" || true
ls -l "$OUT/DShAndroid.apk"
echo "BUILD OK"
