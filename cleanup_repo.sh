#!/bin/bash
set -e

cd /root/workspace/lsp模块开发/TGClean

echo "=== 当前分支 ==="
git branch
echo ""

# 1. 删除本地旧 main，重命名 clean_main 为 main
echo ">>> 步骤1: 删除本地旧 main 分支"
git branch -D main
echo ""

echo ">>> 步骤1b: 重命名 clean_main -> main"
git branch -m clean_main main
echo ""

# 2. 删除远端所有旧 tag
echo ">>> 步骤2: 删除远端所有旧 tag"
for tag in $(git tag -l); do
    echo "  删除远端 tag: $tag"
    git push origin ":refs/tags/$tag"
done
echo ""

# 3. 给新提交打 v1.0.0 tag
echo ">>> 步骤3: 创建 v1.0.0 tag"
git tag v1.0.0
echo ""

# 4. Force push 覆盖远端 main
echo ">>> 步骤4: Force push main 到远端"
git push origin main --force
echo ""

# 5. 推送新 tag
echo ">>> 步骤5: 推送 v1.0.0 tag"
git push origin v1.0.0
echo ""

# 6. 清理远程引用
echo ">>> 步骤6: 清理远程引用"
git fetch --prune origin
echo ""

echo "=== 完成 ==="
echo "分支: $(git branch)"
echo "Tag: $(git tag -l)"
echo "历史: $(git log --oneline)"
echo ""
