#!/bin/bash
set -e

cd /root/workspace/lsp模块开发/TGClean

echo ">>> 1. 删除本地全部旧 tag"
for tag in $(git tag -l); do
    git tag -d "$tag"
done
echo ""

echo ">>> 2. 创建新 v1.0.0 tag"
git tag v1.0.0
echo ""

echo ">>> 3. Force push main"
git push origin main --force
echo ""

echo ">>> 4. 推送 v1.0.0 tag"
git push origin v1.0.0
echo ""

echo ">>> 5. 清理"
git fetch --prune origin
echo ""

echo "=== 完成 ==="
echo "分支: $(git branch)"
echo "Tag: $(git tag -l)"
echo "远程Tag: $(git ls-remote --tags origin)"
echo "历史: $(git log --oneline)"
