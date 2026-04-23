# Git 仓库清理总结

## 更新内容

### 1. 更新了 `.gitignore` 文件

#### 根目录 `.gitignore`

新增了以下忽略规则：

- **构建产物**: `*.apk`, `*.aab`, `*.dex`, `build/`, `release/`
- **IDE 配置**: `.idea/`, `*.iml`, `.vscode/`
- **本地配置**: `local.properties`, `*.keystore`, `*.jks`
- **日志文件**: `*.log`, `*.hprof`
- **操作系统文件**: `.DS_Store`, `Thumbs.db`, `Desktop.ini`
- **缓存目录**: `.gradle/`, `.kotlin/`, `.cxx/`
- **AI 助手**: `.trae/`

#### App 模块 `.gitignore`

新增了以下忽略规则：

- **Release 目录**: `/release`
- **APK 文件**: `*.apk`, `*.aab`
- **输出元数据**: `output-metadata.json`
- **Baseline profiles**: `baselineProfiles/`, `*.dm`

### 2. 创建了检查脚本

#### Linux/Mac: `scripts/check-git-files.sh`

功能：
- 检查是否有不应该提交的文件已被提交
- 提供彩色输出和详细报告
- 返回退出码（0=通过，1=有问题）

使用方法：
```bash
chmod +x scripts/check-git-files.sh
./scripts/check-git-files.sh
```

#### Windows: `scripts/check-git-files.bat`

功能：
- 与 Linux 版本相同的检查功能
- 适配 Windows 命令行

使用方法：
```cmd
scripts\check-git-files.bat
```

### 3. 创建了清理脚本

#### `scripts/clean-git-repo.sh`

功能：
- 从 Git 仓库中删除不应该提交的文件
- 保留本地文件（使用 `git rm --cached`）
- 交互式确认，防止误操作

使用方法：
```bash
chmod +x scripts/clean-git-repo.sh
./scripts/clean-git-repo.sh
```

### 4. 创建了文档

- `docs/gitignore-guide.md` - 详细的 .gitignore 配置说明
- `docs/git-cleanup-summary.md` - 本文档

## 需要清理的文件类型

根据项目当前状态，以下文件类型可能已经被提交但应该被忽略：

### 高优先级（必须删除）

1. **APK 文件** (`*.apk`, `*.aab`)
   - 位置: `app/release/app-release.apk`
   - 原因: 构建产物，文件大，每次构建都变化

2. **Release 目录** (`app/release/`)
   - 包含: APK、baseline profiles、output metadata
   - 原因: 所有内容都是构建产物

3. **密钥文件** (`*.jks`, `*.keystore`)
   - 原因: 包含敏感信息，安全风险

4. **local.properties**
   - 原因: 包含本地 SDK 路径，每个开发者不同

### 中优先级（建议删除）

5. **构建目录** (`build/`, `.gradle/`)
   - 原因: 可以重新生成，占用空间大

6. **Kotlin 缓存** (`.kotlin/`)
   - 位置: `.kotlin/errors/`
   - 原因: 编译缓存，可以重新生成

7. **日志文件** (`*.log`)
   - 位置: `.kotlin/errors/*.log`
   - 原因: 运行时生成，不需要版本控制

### 低优先级（可选删除）

8. **IDE 配置** (`.idea/`, `*.iml`)
   - 原因: 个人配置，但某些团队配置可以保留

9. **操作系统文件** (`.DS_Store`, `Thumbs.db`)
   - 原因: 操作系统元数据，无实际作用

## 清理步骤

### 方法 1: 使用自动化脚本（推荐）

```bash
# 1. 检查问题
./scripts/check-git-files.sh

# 2. 清理仓库
./scripts/clean-git-repo.sh

# 3. 检查更改
git status

# 4. 提交更改
git commit -m "chore: remove ignored files from repository"

# 5. 推送更改
git push
```

### 方法 2: 手动清理

```bash
# 1. 删除 APK 文件
git rm --cached app/release/app-release.apk

# 2. 删除 release 目录
git rm -r --cached app/release/

# 3. 删除 .kotlin 目录
git rm -r --cached .kotlin/

# 4. 删除 local.properties（如果存在）
git rm --cached local.properties

# 5. 提交更改
git commit -m "chore: remove ignored files from repository"

# 6. 推送更改
git push
```

## 验证清理结果

### 1. 检查 Git 状态

```bash
git status
```

应该看到类似输出：
```
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

### 2. 检查被忽略的文件

```bash
git status --ignored
```

应该看到之前提交的文件现在被忽略：
```
Ignored files:
  .gradle/
  .kotlin/
  app/build/
  app/release/
  local.properties
```

### 3. 验证文件仍在本地

```bash
ls -la app/release/
```

应该看到文件仍然存在于本地。

## 注意事项

### 1. 团队协作

清理后，通知团队成员：

```
团队通知：

我已经更新了 .gitignore 并清理了仓库中不应该提交的文件。

请执行以下操作：
1. git pull
2. 如果遇到冲突，删除本地的构建产物后重新 pull
3. 重新构建项目: ./gradlew clean build

注意：local.properties 和构建产物已从仓库中删除，
但你的本地文件不会受影响。
```

### 2. CI/CD 配置

如果使用 CI/CD，确保：

- CI 环境会自动生成 `local.properties`
- 签名密钥通过环境变量或密钥管理服务注入
- 不依赖仓库中的构建产物

### 3. 备份

在清理前，建议：

```bash
# 创建备份分支
git checkout -b backup-before-cleanup

# 切回主分支
git checkout main

# 执行清理...
```

### 4. 大文件清理

如果 APK 文件已经提交多次，仓库可能仍然很大。需要清理历史：

```bash
# 使用 BFG Repo-Cleaner（推荐）
java -jar bfg.jar --delete-files "*.apk" .

# 或使用 git filter-branch（较慢）
git filter-branch --force --index-filter \
  "git rm --cached --ignore-unmatch app/release/*.apk" \
  --prune-empty --tag-name-filter cat -- --all
```

**警告**: 清理历史会改写 Git 历史，需要团队协调并强制推送。

## 持续维护

### 1. 提交前检查

在每次提交前运行：
```bash
./scripts/check-git-files.sh
```

### 2. Git Hooks

创建 pre-commit hook 自动检查：

```bash
# .git/hooks/pre-commit
#!/bin/bash
./scripts/check-git-files.sh
if [ $? -ne 0 ]; then
    echo "提交被阻止：发现不应该提交的文件"
    exit 1
fi
```

### 3. CI 检查

在 CI 流程中添加检查：

```yaml
# .github/workflows/check-files.yml
name: Check Git Files
on: [push, pull_request]
jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Check files
        run: ./scripts/check-git-files.sh
```

## 常见问题

### Q: 清理后仓库大小没有变化？

A: `git rm --cached` 只是从索引中删除，历史记录仍然存在。需要使用 `git filter-branch` 或 BFG 清理历史。

### Q: 团队成员 pull 后出现冲突？

A: 让他们删除本地的构建产物，然后重新 pull：
```bash
rm -rf app/build app/release .gradle .kotlin
git pull
```

### Q: 如何恢复误删的文件？

A: 使用 `git checkout` 恢复：
```bash
git checkout HEAD -- <文件路径>
```

### Q: .idea 目录应该提交吗？

A: 通常不建议，但某些团队共享的配置（如代码风格）可以选择性提交。

## 总结

通过更新 `.gitignore` 和清理仓库，我们实现了：

✅ 防止构建产物被提交  
✅ 保护敏感信息（密钥、配置）  
✅ 减小仓库大小  
✅ 避免不必要的合并冲突  
✅ 提供自动化检查工具  

建议定期运行检查脚本，确保仓库保持清洁。

---

**更新日期**: 2025-01-XX  
**维护者**: BH6AAP
