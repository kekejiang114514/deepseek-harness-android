#!/bin/sh
# 跑纯 JVM 单元测试（不需要设备，也不需要 android.jar）。
#
# 说明：被测的类（HistoryCompactor / AskLog / Version）刻意只依赖 org.json
# 或纯 JDK，不碰任何 Android API，所以能直接在桌面 JDK 上跑。
set -e

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
OUT=$ROOT/out-test
JSON_JAR=${JSON_JAR:-$ROOT/deps/json.jar}

JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}
PATH=$JAVA_HOME/bin:$PATH
export PATH
unset LD_LIBRARY_PATH PREFIX

[ -f "$JSON_JAR" ] || {
  echo "缺少 org.json: $JSON_JAR"
  echo "下载：curl -Lo deps/json.jar https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar"
  exit 1
}

rm -rf "$OUT"
mkdir -p "$OUT"

echo "[1/2] 编译"
javac -encoding UTF-8 -cp "$JSON_JAR" -d "$OUT" \
    "$ROOT/app/src/com/dsh/android/llm/HistoryCompactor.java" \
    "$ROOT/app/src/com/dsh/android/ui/AskLog.java" \
    "$ROOT/app/src/com/dsh/android/kernel/Version.java" \
    "$ROOT/app/src/com/dsh/android/plugins/Template.java" \
    "$ROOT/app/src/com/dsh/android/plugins/JsonPath.java" \
    "$ROOT/app/src/com/dsh/android/plugins/PluginToolSpec.java" \
    "$ROOT/app/src/com/dsh/android/plugins/PluginSpec.java" \
    "$HERE/CompactTest.java" \
    "$HERE/AskLogTest.java" \
    "$HERE/VersionTest.java" \
    "$HERE/PluginTest.java"

echo "[2/2] 运行"
java -cp "$OUT:$JSON_JAR" CompactTest
java -cp "$OUT:$JSON_JAR" AskLogTest
java -cp "$OUT:$JSON_JAR" VersionTest
java -cp "$OUT:$JSON_JAR" PluginTest
