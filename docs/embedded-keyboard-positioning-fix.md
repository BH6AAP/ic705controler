# 内嵌键盘定位修复记录

## 问题描述

### 问题1：键盘随搜索建议移动
内嵌键盘在显示时会吸附在呼号搜索预选菜单底部，随着搜索建议的显示/隐藏而上下移动。

### 问题2：键盘被挤压在中间
记录了呼号后，键盘显示不完整，被挤压在呼号记录列表和输入框之间，无法正常使用。

### 问题3：记录列表位置错误
记录列表没有紧贴着输入框底部，布局出现错位。

## 期望行为
- 数字键应该放置在字符键盘的上部（第一行）
- 软键盘应始终保持在页面底部，覆盖在所有内容之上
- 记录列表应该紧贴输入框下方
- 键盘显示时，内容区域应该有足够的底部间距避免被遮挡

## 解决方案

### 1. 键盘布局调整
已将数字键从底部移至顶部（第一行），键盘布局顺序：
1. 第一行：数字 1-0
2. 第二行：Q W E R T Y U I O P
3. 第三行：A S D F G H J K L
4. 第四行：Z X C V B N M
5. 第五行：退格和完成按钮

### 2. 布局结构重构
**问题根源：** 
- 第一次修复时，键盘被放在 Box 内部，与输入框/搜索建议在同一层级
- 记录列表也使用了 `weight(1f)`，导致键盘被挤在中间
- 键盘没有覆盖在所有内容之上

**最终方案：** 使用 Box 作为最外层容器，键盘作为覆盖层
```
Surface
└── Box (最外层)
    ├── Column (主内容区域)
    │   ├── Row (标题栏)
    │   ├── OutlinedTextField (输入框)
    │   ├── Surface (搜索建议，条件显示)
    │   ├── HorizontalDivider
    │   ├── LazyColumn (记录列表)
    │   └── Spacer (键盘显示时的底部间距)
    └── EmbeddedKeyboard (覆盖层，固定在底部)
```

### 3. 关键技术点

#### 3.1 Box 作为最外层容器
```kotlin
Surface(...) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 主内容
        Column(...) { /* 所有内容 */ }
        
        // 键盘覆盖层
        if (showKeyboard) {
            EmbeddedKeyboard(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            )
        }
    }
}
```

#### 3.2 动态底部间距
当键盘显示时，在 Column 底部添加 Spacer，避免内容被键盘遮挡：
```kotlin
if (showKeyboard) {
    Spacer(modifier = Modifier.height(260.dp))
}
```

#### 3.3 记录列表正常布局
移除了之前的 `weight(1f)`，让记录列表自然排列在输入框下方：
```kotlin
// 记录列表
if (records.isNotEmpty()) {
    HorizontalDivider(...)
    LazyColumn(...) { /* 记录项 */ }
}
```

## 修复效果
- ✅ 数字键位于键盘顶部
- ✅ 键盘固定在页面底部，覆盖在所有内容之上
- ✅ 搜索建议显示/隐藏时，键盘位置不变
- ✅ 记录列表紧贴输入框下方，布局正确
- ✅ 键盘显示时，内容区域有足够的底部间距
- ✅ 键盘不会被挤压或显示不完整
- ✅ 用户体验流畅自然

## 代码变更

### 修改的文件
**文件**: `app/src/main/java/com/bh6aap/ic705Cter/SatelliteTrackingActivity.kt`

**位置**: `SimpleCallsignRecorder` 函数

**主要变更**:
1. 将 `Surface` 内部的 `Column` 改为 `Box`
2. 在 `Box` 内部放置 `Column`（包含所有主内容）
3. 移除输入框区域的 `Box` 和 `weight(1f)`
4. 将输入框、搜索建议、记录列表都放在同一个 `Column` 中
5. 在 `Column` 底部添加条件 `Spacer`（键盘显示时）
6. 将键盘移到 `Box` 的最外层，作为覆盖层
7. 为键盘添加 `align(Alignment.BottomCenter)` 固定在底部

### 布局层级对比

#### 修复前（错误）
```
Surface
└── Column
    ├── Row (标题)
    ├── Box (weight=1f) ❌ 问题：占据空间，挤压键盘
    │   ├── Column
    │   │   ├── TextField
    │   │   └── Suggestions
    │   └── Keyboard ❌ 问题：在 Box 内部
    └── LazyColumn (weight=1f) ❌ 问题：也占据空间
```

#### 修复后（正确）
```
Surface
└── Box ✅ 最外层容器
    ├── Column ✅ 主内容区域
    │   ├── Row (标题)
    │   ├── TextField
    │   ├── Suggestions
    │   ├── HorizontalDivider
    │   ├── LazyColumn (记录列表)
    │   └── Spacer (条件显示) ✅ 避免被键盘遮挡
    └── Keyboard ✅ 覆盖层，固定底部
```

## 测试验证

### 测试步骤
1. 点击呼号输入框，验证键盘显示在页面底部
2. 输入2个以上字符，验证搜索建议显示时键盘位置不变
3. 保存一个呼号，验证记录列表正常显示
4. 再次点击输入框，验证键盘完整显示，不被挤压
5. 滚动记录列表，验证键盘保持固定
6. 点击"完成"按钮，验证键盘隐藏且内容恢复正常

### 预期结果
- ✅ 键盘始终在页面底部，完整显示
- ✅ 搜索建议在输入框下方正常显示
- ✅ 记录列表紧贴输入框，无错位
- ✅ 键盘显示时，内容有足够间距不被遮挡
- ✅ 布局响应流畅，无闪烁或跳动
- ✅ 有记录时键盘仍然完整显示

## 相关文件
- `app/src/main/java/com/bh6aap/ic705Cter/ui/components/EmbeddedKeyboard.kt` - 键盘组件
- `app/src/main/java/com/bh6aap/ic705Cter/SatelliteTrackingActivity.kt` - SimpleCallsignRecorder 函数

## 相关资源

### Compose 布局文档
- [Box Layout](https://developer.android.com/jetpack/compose/layouts/box)
- [Layout Modifiers](https://developer.android.com/jetpack/compose/modifiers)
- [Alignment](https://developer.android.com/reference/kotlin/androidx/compose/ui/Alignment)

## 总结

通过将键盘从内容区域分离出来，作为 Box 的覆盖层固定在底部，成功解决了键盘被挤压和布局错位的问题。同时添加动态底部间距，确保内容不被键盘遮挡。这种布局方式更符合移动应用的标准做法，提供了最佳的用户体验。

---

**修复日期**: 2026-04-23  
**修复版本**: v3.5.6  
**影响范围**: 呼号记录器内嵌键盘定位和布局
