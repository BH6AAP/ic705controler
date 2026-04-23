# .gitignore 配置说明

## 概述

本文档说明项目中 `.gitignore` 文件的配置，确保不必要的文件不会被提交到 Git 仓库。

## 忽略的文件类型

### 1. 构建产物

这些文件是编译和构建过程中生成的，不应该提交到版本控制：

```
# 构建目录
build/
.gradle/
out/
bin/
gen/

# APK 和 AAB 文件
*.apk
*.ap_
*.aab

# DEX 文件
*.dex

# Java class 文件
*.class

# Release 目录
release/
app/release/
```

**原因**: 这些文件可以通过构建过程重新生成，提交它们会：
- 增加仓库大小
- 造成不必要的合并冲突
- 包含可能的敏感信息

### 2. IDE 配置文件

不同开发者可能使用不同的 IDE 配置，这些文件不应该共享：

```
# IntelliJ IDEA / Android Studio
.idea/
*.iml
*.iws
*.ipr

# VSCode
.vscode/

# 临时文件
*~
*.swp
```

**原因**: 
- IDE 配置因人而异
- 可能包含本地路径信息
- 会造成不必要的冲突

**例外**: 某些团队共享的 IDE 配置可以提交，如代码风格配置。

### 3. 本地配置文件

包含本地环境特定信息的文件：

```
# SDK 路径等本地配置
local.properties

# 密钥文件
*.jks
*.keystore

# Google Services 配置
google-services.json
```

**原因**:
- 包含本地路径（如 SDK 位置）
- 可能包含敏感信息（API 密钥、签名密钥）
- 每个开发者的配置不同

### 4. 日志和调试文件

运行时生成的日志和调试文件：

```
# 日志文件
*.log

# Android Profiling
*.hprof

# Captures
captures/

# Lint 输出
lint/
lint-results*.html
lint-results*.xml
```

**原因**:
- 文件可能很大
- 包含运行时信息，不需要版本控制
- 每次运行都会变化

### 5. 操作系统文件

不同操作系统生成的元数据文件：

```
# macOS
.DS_Store
.AppleDouble
.LSOverride
._*

# Windows
Thumbs.db
Desktop.ini
$RECYCLE.BIN/

# Linux
*~
```

**原因**:
- 这些是操作系统特定的元数据
- 对项目没有实际作用
- 会造成跨平台协作的困扰

### 6. 依赖和缓存

包管理器和构建工具的缓存：

```
# Gradle 缓存
.gradle/

# Kotlin 编译缓存
.kotlin/

# External native build
.externalNativeBuild
.cxx/
```

**原因**:
- 可以通过构建过程重新生成
- 文件可能很大
- 包含本地路径信息

### 7. AI 助手文件

AI 编程助手生成的临时文件：

```
# Trae AI 助手
.trae/
```

**原因**:
- 这些是 AI 助手的工作文件
- 不是项目源代码的一部分
- 可能包含大量临时数据

### 8. 文档文件（可选）

根据项目需求，可以选择忽略某些文档：

```
# 如果不想提交这些文档，取消注释
# preread.md
# CHANGELOG.md
# docs/
```

**建议**: 
- 项目文档（README.md）应该提交
- 开发文档（如本文档）建议提交
- 临时笔记可以忽略

## 文件结构

项目中有两个 `.gitignore` 文件：

### 根目录 `.gitignore`

位置: `/.gitignore`

作用: 忽略整个项目级别的文件

包含:
- IDE 配置
- 构建目录
- 操作系统文件
- 全局配置文件

### App 模块 `.gitignore`

位置: `/app/.gitignore`

作用: 忽略 app 模块特定的文件

包含:
- app 模块的构建产物
- APK 文件
- Release 目录

## 检查被忽略的文件

### 查看当前被忽略的文件

```bash
git status --ignored
```

### 查看特定文件是否被忽略

```bash
git check-ignore -v <文件路径>
```

例如:
```bash
git check-ignore -v app/release/app-release.apk
```

### 强制添加被忽略的文件

如果确实需要提交某个被忽略的文件：

```bash
git add -f <文件路径>
```

**警告**: 谨慎使用，确保不会提交敏感信息。

## 清理已提交的文件

如果某些文件已经被提交，但现在想要忽略它们：

### 1. 从 Git 中删除但保留本地文件

```bash
git rm --cached <文件路径>
```

例如:
```bash
git rm --cached app/release/app-release.apk
```

### 2. 删除整个目录

```bash
git rm -r --cached <目录路径>
```

例如:
```bash
git rm -r --cached app/release/
```

### 3. 提交更改

```bash
git commit -m "Remove ignored files from repository"
```

## 常见问题

### Q1: 为什么 `.idea/` 目录被忽略？

A: `.idea/` 包含 IntelliJ IDEA / Android Studio 的项目配置，这些配置因开发者而异。但某些团队共享的配置（如代码风格）可以选择性提交。

### Q2: 为什么 `local.properties` 被忽略？

A: 这个文件包含本地 SDK 路径，每个开发者的路径可能不同。Android Studio 会自动生成这个文件。

### Q3: 为什么 APK 文件被忽略？

A: APK 是构建产物，可以通过源代码重新构建。提交 APK 会：
- 大幅增加仓库大小
- 每次构建都会产生新的 APK，造成大量提交
- Release APK 应该通过 CI/CD 或 Release 流程发布

### Q4: 我需要提交 `google-services.json` 吗？

A: 通常不需要，因为它可能包含 API 密钥。如果团队需要共享，可以：
- 使用环境变量
- 使用 CI/CD 注入
- 提交模板文件（移除敏感信息）

### Q5: 文档文件应该提交吗？

A: 建议提交：
- README.md - 项目说明
- docs/ - 开发文档
- CHANGELOG.md - 更新日志

可以忽略：
- 个人笔记
- 临时文档
- 自动生成的文档

## 最佳实践

### 1. 提交前检查

```bash
# 查看将要提交的文件
git status

# 查看被忽略的文件
git status --ignored
```

### 2. 使用 .gitignore 模板

Android 项目可以使用 GitHub 提供的模板：
https://github.com/github/gitignore/blob/main/Android.gitignore

### 3. 团队协作

- 在项目开始时就配置好 `.gitignore`
- 定期审查和更新忽略规则
- 文档化特殊的忽略规则

### 4. 敏感信息

永远不要提交：
- API 密钥
- 密码
- 签名密钥
- 个人信息

如果不小心提交了敏感信息：
1. 立即更改密钥/密码
2. 使用 `git filter-branch` 或 `BFG Repo-Cleaner` 清理历史
3. 强制推送（需要团队协调）

## 验证清单

在提交代码前，检查以下内容：

- [ ] 没有 `.apk` 或 `.aab` 文件
- [ ] 没有 `build/` 目录
- [ ] 没有 `local.properties`
- [ ] 没有密钥文件 (`.jks`, `.keystore`)
- [ ] 没有日志文件 (`.log`)
- [ ] 没有 IDE 特定配置（除非团队共享）
- [ ] 没有操作系统文件 (`.DS_Store`, `Thumbs.db`)
- [ ] 没有敏感信息（API 密钥、密码）

## 相关命令

```bash
# 查看所有被跟踪的文件
git ls-files

# 查看被忽略的文件
git ls-files --others --ignored --exclude-standard

# 清理未跟踪的文件（谨慎使用）
git clean -fdx

# 查看 .gitignore 规则
cat .gitignore
```

## 参考资料

- [Git 官方文档 - gitignore](https://git-scm.com/docs/gitignore)
- [GitHub gitignore 模板](https://github.com/github/gitignore)
- [Android 开发者指南](https://developer.android.com/studio/build)

---

**最后更新**: 2025-01-XX  
**维护者**: BH6AAP
