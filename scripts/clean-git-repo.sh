#!/bin/bash

# Git 仓库清理脚本
# 用于从 Git 仓库中删除不应该提交的文件（保留本地文件）

echo "======================================"
echo "Git 仓库清理脚本"
echo "======================================"
echo ""
echo "警告: 此脚本将从 Git 仓库中删除文件"
echo "      但会保留本地文件"
echo ""
read -p "是否继续? (y/n) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]
then
    echo "已取消"
    exit 1
fi

echo ""
echo "开始清理..."
echo ""

# 1. 删除 APK 文件
echo "1. 删除 APK/AAB 文件..."
git ls-files | grep -E '\.apk$|\.aab$' | xargs -r git rm --cached
echo ""

# 2. 删除构建目录
echo "2. 删除构建目录..."
git ls-files | grep -E '^build/|/build/' | xargs -r git rm --cached
echo ""

# 3. 删除 local.properties
echo "3. 删除 local.properties..."
git ls-files | grep 'local.properties' | xargs -r git rm --cached
echo ""

# 4. 删除密钥文件
echo "4. 删除密钥文件..."
git ls-files | grep -E '\.jks$|\.keystore$' | xargs -r git rm --cached
echo ""

# 5. 删除日志文件
echo "5. 删除日志文件..."
git ls-files | grep -E '\.log$' | xargs -r git rm --cached
echo ""

# 6. 删除 IDE 配置文件
echo "6. 删除 IDE 配置文件..."
git ls-files | grep -E '\.iml$|\.iws$|\.ipr$' | xargs -r git rm --cached
echo ""

# 7. 删除操作系统文件
echo "7. 删除操作系统文件..."
git ls-files | grep -E '\.DS_Store$|Thumbs\.db$|Desktop\.ini$' | xargs -r git rm --cached
echo ""

# 8. 删除 release 目录
echo "8. 删除 release 目录..."
git ls-files | grep -E 'app/release/' | xargs -r git rm --cached
echo ""

# 9. 删除 .kotlin 目录
echo "9. 删除 .kotlin 目录..."
git ls-files | grep -E '\.kotlin/' | xargs -r git rm --cached
echo ""

# 10. 删除 .gradle 目录
echo "10. 删除 .gradle 目录..."
git ls-files | grep -E '\.gradle/' | xargs -r git rm --cached
echo ""

echo "======================================"
echo "清理完成"
echo "======================================"
echo ""
echo "下一步操作:"
echo "1. 检查更改: git status"
echo "2. 提交更改: git commit -m 'Remove ignored files from repository'"
echo "3. 推送更改: git push"
echo ""
echo "注意: 这些文件已从 Git 中删除，但仍保留在本地"
echo ""
