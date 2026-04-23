#!/bin/bash

# Git 文件检查脚本
# 用于检查是否有不应该提交的文件被提交到仓库

echo "======================================"
echo "Git 文件检查脚本"
echo "======================================"
echo ""

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 检查计数
ISSUES=0

echo "1. 检查 APK 文件..."
APK_FILES=$(git ls-files | grep -E '\.apk$|\.aab$')
if [ -n "$APK_FILES" ]; then
    echo -e "${RED}❌ 发现 APK/AAB 文件:${NC}"
    echo "$APK_FILES"
    ISSUES=$((ISSUES+1))
else
    echo -e "${GREEN}✓ 没有 APK/AAB 文件${NC}"
fi
echo ""

echo "2. 检查构建目录..."
BUILD_DIRS=$(git ls-files | grep -E '^build/|/build/')
if [ -n "$BUILD_DIRS" ]; then
    echo -e "${RED}❌ 发现构建目录文件:${NC}"
    echo "$BUILD_DIRS" | head -10
    echo "..."
    ISSUES=$((ISSUES+1))
else
    echo -e "${GREEN}✓ 没有构建目录文件${NC}"
fi
echo ""

echo "3. 检查 local.properties..."
LOCAL_PROPS=$(git ls-files | grep 'local.properties')
if [ -n "$LOCAL_PROPS" ]; then
    echo -e "${RED}❌ 发现 local.properties:${NC}"
    echo "$LOCAL_PROPS"
    ISSUES=$((ISSUES+1))
else
    echo -e "${GREEN}✓ 没有 local.properties${NC}"
fi
echo ""

echo "4. 检查密钥文件..."
KEY_FILES=$(git ls-files | grep -E '\.jks$|\.keystore$')
if [ -n "$KEY_FILES" ]; then
    echo -e "${RED}❌ 发现密钥文件:${NC}"
    echo "$KEY_FILES"
    ISSUES=$((ISSUES+1))
else
    echo -e "${GREEN}✓ 没有密钥文件${NC}"
fi
echo ""

echo "5. 检查日志文件..."
LOG_FILES=$(git ls-files | grep -E '\.log$')
if [ -n "$LOG_FILES" ]; then
    echo -e "${YELLOW}⚠ 发现日志文件:${NC}"
    echo "$LOG_FILES"
    ISSUES=$((ISSUES+1))
else
    echo -e "${GREEN}✓ 没有日志文件${NC}"
fi
echo ""

echo "6. 检查 IDE 配置..."
IDE_FILES=$(git ls-files | grep -E '\.iml$|\.iws$|\.ipr$')
if [ -n "$IDE_FILES" ]; then
    echo -e "${YELLOW}⚠ 发现 IDE 配置文件:${NC}"
    echo "$IDE_FILES"
    echo -e "${YELLOW}注意: 某些 IDE 配置可能是团队共享的${NC}"
else
    echo -e "${GREEN}✓ 没有 IDE 配置文件${NC}"
fi
echo ""

echo "7. 检查操作系统文件..."
OS_FILES=$(git ls-files | grep -E '\.DS_Store$|Thumbs\.db$|Desktop\.ini$')
if [ -n "$OS_FILES" ]; then
    echo -e "${RED}❌ 发现操作系统文件:${NC}"
    echo "$OS_FILES"
    ISSUES=$((ISSUES+1))
else
    echo -e "${GREEN}✓ 没有操作系统文件${NC}"
fi
echo ""

echo "8. 检查 release 目录..."
RELEASE_FILES=$(git ls-files | grep -E 'app/release/')
if [ -n "$RELEASE_FILES" ]; then
    echo -e "${RED}❌ 发现 release 目录文件:${NC}"
    echo "$RELEASE_FILES"
    ISSUES=$((ISSUES+1))
else
    echo -e "${GREEN}✓ 没有 release 目录文件${NC}"
fi
echo ""

echo "======================================"
echo "检查完成"
echo "======================================"

if [ $ISSUES -eq 0 ]; then
    echo -e "${GREEN}✓ 所有检查通过！${NC}"
    exit 0
else
    echo -e "${RED}❌ 发现 $ISSUES 个问题${NC}"
    echo ""
    echo "建议操作:"
    echo "1. 从 Git 中删除这些文件: git rm --cached <文件路径>"
    echo "2. 确保 .gitignore 包含这些文件类型"
    echo "3. 提交更改: git commit -m 'Remove ignored files'"
    echo ""
    exit 1
fi
