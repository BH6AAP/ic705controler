@echo off
REM Git 文件检查脚本 (Windows)
REM 用于检查是否有不应该提交的文件被提交到仓库

echo ======================================
echo Git 文件检查脚本
echo ======================================
echo.

set ISSUES=0

echo 1. 检查 APK 文件...
git ls-files | findstr /R "\.apk$ \.aab$" > nul
if %errorlevel% equ 0 (
    echo [X] 发现 APK/AAB 文件:
    git ls-files | findstr /R "\.apk$ \.aab$"
    set /a ISSUES+=1
) else (
    echo [OK] 没有 APK/AAB 文件
)
echo.

echo 2. 检查构建目录...
git ls-files | findstr /R "^build/ /build/" > nul
if %errorlevel% equ 0 (
    echo [X] 发现构建目录文件
    set /a ISSUES+=1
) else (
    echo [OK] 没有构建目录文件
)
echo.

echo 3. 检查 local.properties...
git ls-files | findstr "local.properties" > nul
if %errorlevel% equ 0 (
    echo [X] 发现 local.properties:
    git ls-files | findstr "local.properties"
    set /a ISSUES+=1
) else (
    echo [OK] 没有 local.properties
)
echo.

echo 4. 检查密钥文件...
git ls-files | findstr /R "\.jks$ \.keystore$" > nul
if %errorlevel% equ 0 (
    echo [X] 发现密钥文件:
    git ls-files | findstr /R "\.jks$ \.keystore$"
    set /a ISSUES+=1
) else (
    echo [OK] 没有密钥文件
)
echo.

echo 5. 检查日志文件...
git ls-files | findstr /R "\.log$" > nul
if %errorlevel% equ 0 (
    echo [!] 发现日志文件:
    git ls-files | findstr /R "\.log$"
    set /a ISSUES+=1
) else (
    echo [OK] 没有日志文件
)
echo.

echo 6. 检查操作系统文件...
git ls-files | findstr /R "\.DS_Store$ Thumbs\.db$ Desktop\.ini$" > nul
if %errorlevel% equ 0 (
    echo [X] 发现操作系统文件:
    git ls-files | findstr /R "\.DS_Store$ Thumbs\.db$ Desktop\.ini$"
    set /a ISSUES+=1
) else (
    echo [OK] 没有操作系统文件
)
echo.

echo 7. 检查 release 目录...
git ls-files | findstr "app/release/" > nul
if %errorlevel% equ 0 (
    echo [X] 发现 release 目录文件:
    git ls-files | findstr "app/release/"
    set /a ISSUES+=1
) else (
    echo [OK] 没有 release 目录文件
)
echo.

echo ======================================
echo 检查完成
echo ======================================

if %ISSUES% equ 0 (
    echo [OK] 所有检查通过！
    exit /b 0
) else (
    echo [X] 发现 %ISSUES% 个问题
    echo.
    echo 建议操作:
    echo 1. 从 Git 中删除这些文件: git rm --cached ^<文件路径^>
    echo 2. 确保 .gitignore 包含这些文件类型
    echo 3. 提交更改: git commit -m "Remove ignored files"
    echo.
    exit /b 1
)
