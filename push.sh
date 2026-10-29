#!/bin/sh
# 建立 GitHub 远程仓库并推送。
#
#   GITHUB_TOKEN=ghp_xxx sh push.sh [账号名] [仓库名]
#
# 仓库名默认 deepseek-harness-android。仓库设为 public。
# token 需要 repo 权限（经典 token）或 Repository: create/contents: write（细粒度）。
set -e

TOKEN=${GITHUB_TOKEN:-}
[ -n "$TOKEN" ] || { echo "缺少 GITHUB_TOKEN"; exit 1; }

REPO=${2:-deepseek-harness-android}
HERE=$(cd "$(dirname "$0")" && pwd)

# 账号名不传就自己查
OWNER=${1:-}
if [ -z "$OWNER" ]; then
  OWNER=$(curl -sS -H "Authorization: Bearer $TOKEN" https://api.github.com/user \
          | python3 -c 'import json,sys; print(json.load(sys.stdin).get("login",""))')
  [ -n "$OWNER" ] || { echo "token 无效，查不到账号"; exit 1; }
fi
echo "账号: $OWNER"

# 建仓库；已存在则跳过（422 = 重名）
CODE=$(curl -sS -o /tmp/repo_create.json -w '%{http_code}' -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/user/repos \
  -d "{\"name\":\"$REPO\",\"description\":\"DeepSeek Harness for Android — 手机上的 AI Agent 客户端：上下文自动压缩、多文件上传、无障碍控制。零依赖纯 Java。\",\"private\":false,\"has_issues\":true,\"has_wiki\":false}")
echo "建仓库 HTTP $CODE"
if [ "$CODE" != "201" ]; then
  head -c 400 /tmp/repo_create.json; echo
fi

cd "$HERE"
git add -A
git -c user.name="Coco" -c user.email="coco@localhost" commit -qm "0.9 更新" 2>/dev/null || true
git branch -M main

# 用 token 走 https 推送，但不把 token 写进 .git/config
git remote remove origin 2>/dev/null || true
git remote add origin "https://github.com/$OWNER/$REPO.git"
git -c http.extraheader="Authorization: Basic $(printf 'x-access-token:%s' "$TOKEN" | base64 -w0)" \
    push -q -u origin main

echo "已推送: https://github.com/$OWNER/$REPO"
