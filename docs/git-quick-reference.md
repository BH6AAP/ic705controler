# Git 快速参考

## 常用命令

### 检查文件状态

```bash
# 查看当前状态
git status

# 查看被忽略的文件
git status --ignored

# 查看所有被跟踪的文件
git ls-files

# 检查特定文件是否被忽略
git check-ignore -v <文件路径>
```

### 删除文件

```bash
# 从 Git 删除但保留本地文件
git rm --cached <文件路径>

# 从 Git 删除目录但保留本地
git rm -r --cached <目录路径>

# 从 Git 和本地都删除
git rm <文件路径>
```

### 清理操作

```bash
# 清理未跟踪的文件（谨慎使用）
git clean -n  # 预览
git clean -f  # 执行

# 清理未跟踪的文件和目录
git clean -fd

# 清理所有（包括被忽略的文件）
git clean -fdx
```

## 项目特定命令

### 检查不应该提交的文件

```bash
# Linux/Mac
./scripts/check-git-files.sh

# Windows
scripts\check-git-files.bat
```

### 清理仓库

```bash
# 自动清理（推荐）
./scripts/clean-git-repo.sh

# 手动清理 APK
git rm --cached app/release/*.apk

# 手动清理 release 目录
git rm -r --cached app/release/

# 手动清理 .kotlin 目录
git rm -r --cached .kotlin/
```

### 提交清理结果

```bash
git commit -m "chore: remove ignored files from repository"
git push
```

## 应该忽略的文件

### ❌ 绝对不要提交

- `*.apk` - APK 文件
- `*.aab` - Android App Bundle
- `*.jks` - 密钥文件
- `*.keystore` - 密钥库
- `local.properties` - 本地配置
- `google-services.json` - Google 服务配置（如包含密钥）

### ⚠️ 建议不要提交

- `build/` - 构建目录
- `.gradle/` - Gradle 缓存
- `.kotlin/` - Kotlin 缓存
- `*.log` - 日志文件
- `.DS_Store` - macOS 文件
- `Thumbs.db` - Windows 文件

### ✅ 可以提交

- `README.md` - 项目说明
- `docs/` - 文档目录
- `CHANGELOG.md` - 更新日志
- `gradle/wrapper/` - Gradle Wrapper
- `src/` - 源代码

## 紧急情况

### 不小心提交了敏感文件

```bash
# 1. 立即更改密钥/密码

# 2. 从最新提交中删除
git rm --cached <敏感文件>
git commit --amend -m "Remove sensitive file"

# 3. 如果已经推送，需要强制推送（谨慎！）
git push --force

# 4. 清理历史（如果文件在多个提交中）
# 使用 BFG Repo-Cleaner 或 git filter-branch
```

### 恢复误删的文件

```bash
# 恢复到最后一次提交的状态
git checkout HEAD -- <文件路径>

# 恢复到特定提交
git checkout <commit-hash> -- <文件路径>
```

### 撤销最后一次提交

```bash
# 保留更改
git reset --soft HEAD~1

# 丢弃更改
git reset --hard HEAD~1
```

## 团队协作

### 更新 .gitignore 后

```bash
# 1. 提交 .gitignore
git add .gitignore
git commit -m "chore: update .gitignore"

# 2. 清理已提交的文件
./scripts/clean-git-repo.sh

# 3. 提交清理结果
git commit -m "chore: remove ignored files"

# 4. 推送
git push

# 5. 通知团队成员 pull
```

### 团队成员更新

```bash
# 1. Pull 最新代码
git pull

# 2. 如果有冲突，删除本地构建产物
rm -rf app/build app/release .gradle .kotlin

# 3. 重新 pull
git pull

# 4. 重新构建
./gradlew clean build
```

## 检查清单

### 提交前检查

- [ ] 运行 `./scripts/check-git-files.sh`
- [ ] 检查 `git status` 输出
- [ ] 确认没有 `.apk` 文件
- [ ] 确认没有密钥文件
- [ ] 确认没有 `local.properties`
- [ ] 确认没有构建目录

### 推送前检查

- [ ] 代码已测试
- [ ] 提交信息清晰
- [ ] 没有敏感信息
- [ ] 没有大文件（>1MB）
- [ ] 符合团队规范

## 有用的别名

添加到 `~/.gitconfig`:

```ini
[alias]
    # 查看被忽略的文件
    ignored = status --ignored
    
    # 查看所有被跟踪的文件
    tracked = ls-files
    
    # 删除文件但保留本地
    untrack = rm --cached
    
    # 检查文件是否被忽略
    check-ignore = check-ignore -v
    
    # 清理未跟踪的文件（预览）
    clean-preview = clean -n
```

使用：
```bash
git ignored
git tracked
git untrack <文件>
git check-ignore <文件>
```

## 相关文档

- [详细配置说明](./gitignore-guide.md)
- [清理总结](./git-cleanup-summary.md)
- [内嵌键盘功能](./embedded-keyboard-feature.md)

---

**快速帮助**: 运行 `./scripts/check-git-files.sh` 检查问题
