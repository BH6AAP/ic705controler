# 内嵌式软键盘功能说明

## 功能概述

在卫星跟踪界面的呼号记录器中添加了内嵌式软键盘，替代系统输入法，避免输入法弹出时遮挡呼号模糊搜索的预选建议。

## 实现细节

### 1. 新增组件

**文件**: `app/src/main/java/com/bh6aap/ic705Cter/ui/components/EmbeddedKeyboard.kt`

内嵌式软键盘组件，专为呼号输入设计，包含以下功能：

- **字母键盘**: 3行QWERTY布局，包含A-Z所有大写字母
- **数字键盘**: 1行数字键，包含0-9
- **功能键**: 退格键和完成键
- **视觉反馈**: 使用Material Design 3风格，带有圆角和阴影效果

#### 键盘布局

```
第一行: Q W E R T Y U I O P
第二行:  A S D F G H J K L
第三行:   Z X C V B N M
第四行: 1 2 3 4 5 6 7 8 9 0
第五行: [退格键] [完成键]
```

### 2. 修改的组件

**文件**: `app/src/main/java/com/bh6aap/ic705Cter/SatelliteTrackingActivity.kt`

修改了 `SimpleCallsignRecorder` 组件：

#### 主要变更

1. **禁用系统输入法**
   - 将 `OutlinedTextField` 设置为 `readOnly = true`
   - `onValueChange` 设置为空函数
   - 移除了 `keyboardOptions` 和 `keyboardActions`

2. **添加键盘显示状态**
   ```kotlin
   var showKeyboard by remember { mutableStateOf(false) }
   ```

3. **点击输入框显示/隐藏键盘**
   ```kotlin
   .clickable { showKeyboard = !showKeyboard }
   ```

4. **新增键盘处理函数**
   - `handleKeyPress(key: String)`: 处理字母和数字输入，自动触发呼号搜索
   - `handleBackspace()`: 处理退格操作，更新搜索建议
   - `saveCallsign()`: 保存呼号记录并关闭键盘

5. **集成内嵌键盘**
   ```kotlin
   if (showKeyboard) {
       EmbeddedKeyboard(
           onKeyPress = { key -> handleKeyPress(key) },
           onBackspace = { handleBackspace() },
           onDone = { saveCallsign() }
       )
   }
   ```

## 用户体验改进

### 使用流程

1. **打开键盘**: 点击呼号输入框，内嵌键盘从下方展开
2. **输入呼号**: 使用内嵌键盘输入字母和数字
3. **实时搜索**: 输入2个字符后自动显示匹配的呼号建议（2行×5列网格）
4. **选择建议**: 点击建议直接填入，键盘自动关闭
5. **保存记录**: 点击"完成"按钮或输入框右侧的"✓"按钮保存

### 优势

1. **无遮挡**: 搜索建议始终显示在输入框下方，不会被键盘遮挡
2. **专用设计**: 只包含呼号所需的字母和数字，无多余按键
3. **快速输入**: 大按键设计，适合快速点击
4. **一致体验**: 所有设备上键盘布局完全一致
5. **自动大写**: 所有输入自动转为大写，符合呼号规范

## 技术特点

### 1. 状态管理

- 使用 `remember` 和 `mutableStateOf` 管理键盘显示状态
- 输入文本和搜索建议状态独立管理
- 键盘状态与搜索建议状态联动

### 2. 性能优化

- 键盘组件使用 `@Composable` 函数，支持重组优化
- 搜索建议仅在输入≥2个字符时触发
- 点击建议后立即关闭键盘，减少UI层级

### 3. 视觉设计

- 遵循 Material Design 3 设计规范
- 使用主题颜色系统，支持深色/浅色模式
- 按键带有圆角和阴影，提供触觉反馈
- 退格键使用红色容器，完成键使用主色容器

### 4. 兼容性

- 保留拖拽功能：从CW历史拖拽呼号仍然可用
- 保留保存按钮：输入框右侧的"✓"按钮仍然可用
- 向后兼容：不影响其他输入方式

## 代码示例

### 使用内嵌键盘组件

```kotlin
EmbeddedKeyboard(
    onKeyPress = { key ->
        // 处理按键输入
        inputText += key
    },
    onBackspace = {
        // 处理退格
        if (inputText.isNotEmpty()) {
            inputText = inputText.dropLast(1)
        }
    },
    onDone = {
        // 处理完成
        saveData(inputText)
    },
    modifier = Modifier.fillMaxWidth()
)
```

### 禁用系统输入法

```kotlin
OutlinedTextField(
    value = inputText,
    onValueChange = { }, // 空函数，禁用输入
    readOnly = true, // 只读模式
    modifier = Modifier.clickable {
        showKeyboard = !showKeyboard
    }
)
```

## 未来改进方向

1. **键盘布局优化**: 可考虑添加常用呼号前缀快捷键
2. **手势支持**: 支持滑动输入或手势操作
3. **自定义布局**: 允许用户自定义键盘布局
4. **振动反馈**: 添加触觉反馈增强体验
5. **快捷输入**: 支持常用呼号的快捷输入

## 测试建议

1. **功能测试**
   - 测试所有字母和数字按键
   - 测试退格和完成功能
   - 测试搜索建议显示和选择
   - 测试拖拽功能兼容性

2. **UI测试**
   - 测试不同屏幕尺寸的显示效果
   - 测试深色/浅色模式
   - 测试键盘展开/收起动画

3. **性能测试**
   - 测试快速连续输入的响应速度
   - 测试搜索建议的更新延迟
   - 测试内存占用情况

## 版本信息

- **添加日期**: 2025-01-XX
- **修改文件**: 
  - `app/src/main/java/com/bh6aap/ic705Cter/ui/components/EmbeddedKeyboard.kt` (新增)
  - `app/src/main/java/com/bh6aap/ic705Cter/SatelliteTrackingActivity.kt` (修改)
- **影响范围**: 卫星跟踪界面的呼号记录器
- **向后兼容**: 是
