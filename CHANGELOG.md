# 更新日志

## [未发布] - 2025-01-XX

### 新增功能

#### 内嵌式软键盘 ✨

在卫星跟踪界面的呼号记录器中添加了专用的内嵌式软键盘，提供更好的输入体验。

**主要特性**:
- 🎹 专用QWERTY键盘布局，包含A-Z字母和0-9数字
- 🔍 实时呼号搜索建议，不会被键盘遮挡
- 🎯 大按键设计，适合快速点击输入
- 🚫 禁用系统输入法，避免界面遮挡
- ✅ 一键保存功能，快速记录呼号
- 📍 键盘固定在页面底部，不随搜索建议移动

**技术实现**:
- 新增 `EmbeddedKeyboard.kt` 组件
- 修改 `SimpleCallsignRecorder` 支持内嵌键盘
- 输入框设置为只读模式，禁用系统键盘
- 使用 `InteractionSource` 监听点击事件
- 使用 `Box` 布局实现键盘固定定位
- 保持拖拽功能兼容性

**用户体验改进**:
- 搜索建议始终可见，不被键盘遮挡
- 键盘固定在底部，不随内容移动
- 数字键位于键盘顶部，符合使用习惯
- 输入响应更快，无系统输入法延迟
- 所有设备上键盘布局完全一致
- 自动大写输入，符合呼号规范

**相关文件**:
- `app/src/main/java/com/bh6aap/ic705Cter/ui/components/EmbeddedKeyboard.kt` (新增)
- `app/src/main/java/com/bh6aap/ic705Cter/SatelliteTrackingActivity.kt` (修改)
- `docs/embedded-keyboard-feature.md` (功能文档)
- `docs/embedded-keyboard-usage.md` (使用说明)
- `docs/embedded-keyboard-fix.md` (点击事件修复)
- `docs/embedded-keyboard-positioning-fix.md` (定位修复)

### Bug修复

- 🐛 修复内嵌键盘点击事件不触发的问题（使用 InteractionSource 替代 clickable）
- 🐛 修复键盘随搜索建议移动的问题（使用 Box 布局固定定位）
- 🐛 修复数字键位置不符合习惯的问题（移至键盘顶部）

### 优化改进

- 📱 优化呼号输入体验，提高输入效率
- 🎨 改进键盘视觉设计，遵循Material Design 3规范
- ⚡ 减少输入延迟，提升响应速度
- 🎯 改进键盘布局，数字键置于顶部

### 向后兼容

- ✅ 保持所有现有功能正常工作
- ✅ 支持从CW历史拖拽呼号
- ✅ 保留原有保存按钮功能

---

## [3.5.5] - 2025-01-XX

### 核心功能

- 实时卫星跟踪
- 自动多普勒补偿
- VFO智能避让
- PTT状态检测
- 双TLE数据源支持
- 自定义API支持
- 离线数据支持
- 摩尔斯电码功能

### 已知问题

1. 部分Android设备蓝牙连接不稳定
2. GPS定位在室内可能失败
3. TLE数据更新可能超时
4. 某些卫星转发器数据不完整

---

## 版本说明

### 版本号规则

采用语义化版本号: `主版本.次版本.修订号`

- **主版本**: 重大架构变更或不兼容更新
- **次版本**: 新功能添加，向后兼容
- **修订号**: Bug修复和小优化

### 发布周期

- **稳定版**: 每月发布一次
- **测试版**: 每周发布一次
- **开发版**: 持续集成

---

## 贡献指南

欢迎提交Issue和Pull Request！

### 提交规范

- `feat`: 新功能
- `fix`: Bug修复
- `docs`: 文档更新
- `style`: 代码格式调整
- `refactor`: 代码重构
- `perf`: 性能优化
- `test`: 测试相关
- `chore`: 构建/工具相关

### 示例

```
feat: 添加内嵌式软键盘用于呼号输入

- 新增EmbeddedKeyboard组件
- 修改SimpleCallsignRecorder支持内嵌键盘
- 禁用系统输入法避免遮挡搜索建议
- 添加使用文档和说明
```

---

## 联系方式

- GitHub: https://github.com/bh6aap/ic705controler
- Email: 1065147896@qq.com
- 呼号: BH6AAP
