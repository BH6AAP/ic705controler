# 内嵌键盘点击事件修复说明

## 问题描述

点击呼号输入框后，内嵌键盘没有弹出。

## 问题原因

在 `OutlinedTextField` 的 `modifier` 中使用 `.clickable()` 修饰符会被 `OutlinedTextField` 自身的点击事件拦截，导致自定义的点击事件无法触发。

### 错误的实现方式

```kotlin
OutlinedTextField(
    value = inputText,
    onValueChange = { },
    readOnly = true,
    modifier = Modifier
        .fillMaxWidth()
        .clickable { showKeyboard = !showKeyboard } // ❌ 不会触发
)
```

## 解决方案

使用 `interactionSource` 来监听 `OutlinedTextField` 的交互事件，特别是 `PressInteraction.Release` 事件。

### 正确的实现方式

```kotlin
// 1. 创建 InteractionSource
val interactionSource = remember { MutableInteractionSource() }

// 2. 监听点击事件
LaunchedEffect(interactionSource) {
    interactionSource.interactions.collect { interaction ->
        if (interaction is PressInteraction.Release) {
            showKeyboard = !showKeyboard
        }
    }
}

// 3. 将 InteractionSource 传递给 TextField
OutlinedTextField(
    value = inputText,
    onValueChange = { },
    readOnly = true,
    interactionSource = interactionSource, // ✅ 正确方式
    modifier = Modifier.fillMaxWidth()
)
```

## 技术细节

### InteractionSource 是什么？

`InteractionSource` 是 Compose 中用于跟踪组件交互状态的接口。它可以监听以下交互事件：

- `PressInteraction.Press` - 按下
- `PressInteraction.Release` - 释放
- `PressInteraction.Cancel` - 取消
- `FocusInteraction.Focus` - 获得焦点
- `FocusInteraction.Unfocus` - 失去焦点
- `DragInteraction.Start` - 开始拖拽
- `DragInteraction.Stop` - 停止拖拽

### 为什么使用 PressInteraction.Release？

- `Press` 事件在手指按下时触发
- `Release` 事件在手指抬起时触发（完整的点击）
- `Cancel` 事件在点击被取消时触发（如滑出区域）

使用 `Release` 事件可以确保只有在完整点击后才触发键盘显示/隐藏。

## 代码变更

### 添加的导入

```kotlin
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
```

### 修改的代码

**文件**: `app/src/main/java/com/bh6aap/ic705Cter/SatelliteTrackingActivity.kt`

**位置**: `SimpleCallsignRecorder` 函数中的输入框部分

**变更内容**:
1. 创建 `MutableInteractionSource` 实例
2. 使用 `LaunchedEffect` 监听交互事件
3. 将 `interactionSource` 传递给 `OutlinedTextField`
4. 移除 `modifier` 中的 `.clickable()` 修饰符

## 测试验证

### 测试步骤

1. 启动应用并进入卫星跟踪界面
2. 找到右侧的"呼号记录"区域
3. 点击输入框
4. 验证内嵌键盘是否从下方展开

### 预期结果

- ✅ 点击输入框后，内嵌键盘立即展开
- ✅ 再次点击输入框，键盘收起
- ✅ 输入框边框颜色变为主题色（表示激活状态）
- ✅ 不会弹出系统输入法

### 可能的问题

如果键盘仍然不显示，检查：

1. **状态管理**: 确认 `showKeyboard` 状态正确更新
   ```kotlin
   // 添加日志验证
   LaunchedEffect(showKeyboard) {
       LogManager.d("Keyboard", "showKeyboard = $showKeyboard")
   }
   ```

2. **事件监听**: 确认 `PressInteraction.Release` 事件被触发
   ```kotlin
   LaunchedEffect(interactionSource) {
       interactionSource.interactions.collect { interaction ->
           LogManager.d("Interaction", "Type: ${interaction::class.simpleName}")
           if (interaction is PressInteraction.Release) {
               showKeyboard = !showKeyboard
           }
       }
   }
   ```

3. **布局层级**: 确认键盘组件在正确的位置渲染
   ```kotlin
   if (showKeyboard) {
       LogManager.d("Keyboard", "Rendering keyboard")
       EmbeddedKeyboard(...)
   }
   ```

## 相关资源

### Compose 官方文档

- [InteractionSource](https://developer.android.com/reference/kotlin/androidx/compose/foundation/interaction/InteractionSource)
- [MutableInteractionSource](https://developer.android.com/reference/kotlin/androidx/compose/foundation/interaction/MutableInteractionSource)
- [TextField Interactions](https://developer.android.com/jetpack/compose/text#interactions)

### 示例代码

完整的可运行示例：

```kotlin
@Composable
fun CustomClickableTextField() {
    var text by remember { mutableStateOf("") }
    var showKeyboard by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) {
                showKeyboard = !showKeyboard
            }
        }
    }
    
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { },
            readOnly = true,
            interactionSource = interactionSource,
            modifier = Modifier.fillMaxWidth()
        )
        
        if (showKeyboard) {
            Text("Keyboard is visible!")
        }
    }
}
```

## 总结

通过使用 `InteractionSource` 而不是 `clickable` 修饰符，我们可以正确地监听 `OutlinedTextField` 的点击事件，从而实现自定义的键盘显示/隐藏逻辑。这是 Compose 中处理组件交互的标准方式。

---

**修复日期**: 2025-01-XX  
**修复版本**: v3.5.6  
**影响范围**: 呼号记录器输入框点击事件
