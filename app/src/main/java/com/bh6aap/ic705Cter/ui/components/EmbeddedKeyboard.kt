package com.bh6aap.ic705Cter.ui.components

import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 内嵌式软键盘组件
 * 专为呼号输入设计，避免系统输入法遮挡搜索建议
 * 支持大写字母A-Z和数字0-9
 * 支持触觉反馈和按键音效
 */
@Composable
fun EmbeddedKeyboard(
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager }
    
    // 播放按键音效
    fun playKeySound() {
        try {
            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK)
        } catch (e: Exception) {
            // 忽略音效播放错误
        }
    }
    
    // 触觉反馈
    fun performHapticFeedback() {
        try {
            view.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } catch (e: Exception) {
            // 忽略触觉反馈错误
        }
    }
    
    // 按键点击处理（带反馈）
    fun handleKeyClick(action: () -> Unit) {
        playKeySound()
        performHapticFeedback()
        action()
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 第一行: 数字 1-0
            KeyboardRow(
                keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                onKeyPress = { key -> handleKeyClick { onKeyPress(key) } }
            )

            // 第二行: Q W E R T Y U I O P
            KeyboardRow(
                keys = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
                onKeyPress = { key -> handleKeyClick { onKeyPress(key) } }
            )

            // 第三行: A S D F G H J K L
            KeyboardRow(
                keys = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
                onKeyPress = { key -> handleKeyClick { onKeyPress(key) } },
                startPadding = 20.dp
            )

            // 第四行: Z X C V B N M 和退格键
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Spacer(modifier = Modifier.width(40.dp))
                
                listOf("Z", "X", "C", "V", "B", "N", "M").forEach { key ->
                    KeyButton(
                        text = key,
                        onClick = { handleKeyClick { onKeyPress(key) } },
                        modifier = Modifier.weight(1f)
                    )
                }
                
                // 退格键
                KeyButton(
                    text = "←",
                    onClick = { handleKeyClick { onBackspace() } },
                    modifier = Modifier.weight(1.5f),
                    backgroundColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            // 第五行: 完成键
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 完成键
                KeyButton(
                    text = "完成",
                    onClick = { handleKeyClick { onDone() } },
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/**
 * 键盘行组件
 */
@Composable
private fun KeyboardRow(
    keys: List<String>,
    onKeyPress: (String) -> Unit,
    startPadding: androidx.compose.ui.unit.Dp = 0.dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startPadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        keys.forEach { key ->
            KeyButton(
                text = key,
                onClick = { onKeyPress(key) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 按键按钮组件
 */
@Composable
private fun KeyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(
        modifier = modifier
            .height(42.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = backgroundColor,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )
        }
    }
}
