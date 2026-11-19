#!/bin/sh
# 依赖校验与本地缓存：r8.jar / apksigner.jar / json.jar
#
# 以前这些 jar 是「构建时能拿到就用」，换台机器或断网就莫名其妙失败。
# 现在改为：仓库里记录每个文件的 sha256，构建前校验；缺失时报明确错误，
# 由本脚本按需下载并核对校验和，下载后缓存在 deps/ 下，之后不再联网。
#
# 用法：
#   sh tools/deps.sh check    # 只校验（build.sh 自动调用）
#   sh tools/deps.sh fetch    # 缺什么下什么（需要网络）

set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
DEPS=$HERE/deps
SUMS=$HERE/deps/SHA256SUMS

check_one() {
    name=$1
    f="$DEPS/$name"
    want=$(awk -v n="$name" '$2 == n {print $1}' "$SUMS" 2>/dev/null | head -1)
    if [ -z "$want" ]; then
        echo "deps: $name 没有记录校验和（$SUMS）" >&2
        return 1
    fi
    if [ ! -f "$f" ]; then
        echo "deps: 缺少 $name" >&2
        return 1
    fi
    got=$(sha256sum "$f" | awk '{print $1}')
    if [ "$got" != "$want" ]; then
        echo "deps: $name 校验和不符" >&2
        echo "  期望 $want" >&2
        echo "  实际 $got" >&2
        return 1
    fi
    echo "deps: $name ok"
}

cmd=${1:-check}
case "$cmd" in
  check)
    rc=0
    for n in r8.jar apksigner.jar json.jar; do
        check_one "$n" || rc=1
    done
    exit $rc
    ;;
  fetch)
    for n in r8.jar apksigner.jar json.jar; do
        if ! check_one "$n" >/dev/null 2>&1; then
            url=$(awk -v n="$n" '$2 == n {print $3}' "$SUMS" | head -1)
            if [ -z "$url" ]; then
                echo "deps: $n 没有下载地址，请手动放入 deps/" >&2
                exit 1
            fi
            echo "deps: 下载 $n ..."
            curl -fsSL "$url" -o "$DEPS/$n.tmp"
            mv "$DEPS/$n.tmp" "$DEPS/$n"
            check_one "$n"
        fi
    done
    ;;
  *)
    echo "用法: sh tools/deps.sh [check|fetch]" >&2
    exit 2
    ;;
esac
