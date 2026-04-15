package com.bh6aap.ic705Cter.ui.components

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bh6aap.ic705Cter.R
import com.bh6aap.ic705Cter.SplashActivity
import com.bh6aap.ic705Cter.data.LanguageManager
import kotlinx.coroutines.launch

/**
 * 语言设置对话框
 */
@Composable
fun LanguageSettingsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val languageManager = remember { LanguageManager.getInstance(context) }

    var selectedLanguage by remember { mutableStateOf(LanguageManager.AppLanguage.SYSTEM) }
    var showRestartHint by remember { mutableStateOf(false) }

    // 加载当前语言设置
    LaunchedEffect(Unit) {
        languageManager.currentLanguage.collect { lang ->
            selectedLanguage = lang
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.language_settings_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.common_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 当前语言显示
                Text(
                    text = stringResource(
                        R.string.language_current,
                        when (selectedLanguage) {
                            LanguageManager.AppLanguage.SYSTEM -> stringResource(R.string.language_system)
                            LanguageManager.AppLanguage.CHINESE -> stringResource(R.string.language_chinese)
                            LanguageManager.AppLanguage.ENGLISH -> stringResource(R.string.language_english)
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                // 语言选项列表
                LanguageOption(
                    title = stringResource(R.string.language_system),
                    subtitle = "System Default / 跟随系统",
                    isSelected = selectedLanguage == LanguageManager.AppLanguage.SYSTEM,
                    onClick = {
                        if (selectedLanguage != LanguageManager.AppLanguage.SYSTEM) {
                            scope.launch {
                                languageManager.setLanguage(LanguageManager.AppLanguage.SYSTEM)
                                showRestartHint = true
                            }
                        }
                    }
                )

                LanguageOption(
                    title = stringResource(R.string.language_chinese),
                    subtitle = "简体中文",
                    isSelected = selectedLanguage == LanguageManager.AppLanguage.CHINESE,
                    onClick = {
                        if (selectedLanguage != LanguageManager.AppLanguage.CHINESE) {
                            scope.launch {
                                languageManager.setLanguage(LanguageManager.AppLanguage.CHINESE)
                                showRestartHint = true
                            }
                        }
                    }
                )

                LanguageOption(
                    title = stringResource(R.string.language_english),
                    subtitle = "English",
                    isSelected = selectedLanguage == LanguageManager.AppLanguage.ENGLISH,
                    onClick = {
                        if (selectedLanguage != LanguageManager.AppLanguage.ENGLISH) {
                            scope.launch {
                                languageManager.setLanguage(LanguageManager.AppLanguage.ENGLISH)
                                showRestartHint = true
                            }
                        }
                    }
                )

                // 重启提示和重启按钮
                if (showRestartHint) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.language_restart_required),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            // 立即重启按钮
                            Button(
                                onClick = {
                                    // 重启应用（通过 Intent 清除任务栈，不使用 Runtime.exit
                                    // 以避免中断进行中的数据库写入和蓝牙操作）
                                    val activity = context as? Activity
                                    activity?.let {
                                        val intent = Intent(it, SplashActivity::class.java)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                        it.startActivity(intent)
                                        it.finishAffinity()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("立即重启应用")
                            }
                        }
                    }
                }

                // 关闭按钮
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.common_close))
                }
            }
        }
    }
}

@Composable
private fun LanguageOption(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
