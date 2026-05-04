package com.bh6aap.ic705Cter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import com.bh6aap.ic705Cter.ui.components.LanguageIcon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bh6aap.ic705Cter.ui.components.ApiSettingsDialog
import com.bh6aap.ic705Cter.ui.components.CallsignLibraryEditDialog
import com.bh6aap.ic705Cter.ui.components.CallsignRecordsDialog
import com.bh6aap.ic705Cter.ui.components.DataManagementDialog
import com.bh6aap.ic705Cter.ui.components.LanguageSettingsDialog
import com.bh6aap.ic705Cter.ui.components.StationSettingsDialog
import com.bh6aap.ic705Cter.ui.components.WhitelistEditDialog
import com.bh6aap.ic705Cter.ui.theme.Ic705controlerTheme
import com.bh6aap.ic705Cter.util.LogManager


/**
 * 设置界面 Activity
 * 将设置功能从 MainActivity 中分离出来
 */
class SettingsActivity : BaseActivity() {

    companion object {
        const val EXTRA_REFRESH_STATION = "refresh_station"

        /**
         * 启动设置界面
         * @param context 上下文
         */
        fun start(context: Context) {
            val intent = Intent(context, SettingsActivity::class.java)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        LogManager.i("SettingsActivity", "设置界面启动")

        setContent {
            Ic705controlerTheme {
                SettingsScreen(
                    onFinish = { refreshStation ->
                        if (refreshStation) {
                            setResult(RESULT_OK, Intent().putExtra(EXTRA_REFRESH_STATION, true))
                        }
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    onFinish: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var showWhitelistDialog by remember { mutableStateOf(false) }
    var showCallsignLibraryDialog by remember { mutableStateOf(false) }
    var showCallsignRecordsDialog by remember { mutableStateOf(false) }
    var showDataManagementDialog by remember { mutableStateOf(false) }
    var showStationDialog by remember { mutableStateOf(false) }
    var showApiSettingsDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var needRefreshStation by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 4.dp
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { onFinish(needRefreshStation) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 设置项列表
            SettingsItem(
                title = stringResource(R.string.settings_station),
                description = stringResource(R.string.settings_station_desc),
                icon = Icons.Default.LocationOn,
                onClick = {
                    showStationDialog = true
                }
            )

            SettingsItem(
                title = stringResource(R.string.settings_whitelist),
                description = stringResource(R.string.settings_whitelist_desc),
                icon = Icons.Default.List,
                onClick = {
                    showWhitelistDialog = true
                }
            )

            SettingsItem(
                title = stringResource(R.string.settings_callsign_library),
                description = stringResource(R.string.settings_callsign_library_desc),
                icon = Icons.Default.Person,
                onClick = {
                    showCallsignLibraryDialog = true
                }
            )

            // 数据管理入口
            SettingsItem(
                title = stringResource(R.string.settings_data_management),
                description = stringResource(R.string.settings_data_management_desc),
                icon = Icons.Default.Menu,
                onClick = {
                    showDataManagementDialog = true
                }
            )

            // 呼号记录入口
            SettingsItem(
                title = stringResource(R.string.settings_callsign_records),
                description = stringResource(R.string.settings_callsign_records_desc),
                icon = Icons.Default.List,
                onClick = {
                    showCallsignRecordsDialog = true
                }
            )

            // API设置入口
            SettingsItem(
                title = stringResource(R.string.settings_api),
                description = stringResource(R.string.settings_api_desc),
                icon = Icons.Default.Settings,
                onClick = {
                    showApiSettingsDialog = true
                }
            )

            // 语言设置入口
            SettingsItemWithCustomIcon(
                title = stringResource(R.string.settings_language),
                description = stringResource(R.string.settings_language_desc),
                iconContent = {
                    LanguageIcon(
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                onClick = {
                    showLanguageDialog = true
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            // 底部设计者信息
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.settings_designer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // 地面站设置对话框
    if (showStationDialog) {
        StationSettingsDialog(
            onDismiss = { showStationDialog = false },
            onStationSaved = {
                needRefreshStation = true
                Toast.makeText(context, context.getString(R.string.settings_station_saved), Toast.LENGTH_SHORT).show()
            }
        )
    }

    // 白名单编辑对话框
    if (showWhitelistDialog) {
        WhitelistEditDialog(
            onDismiss = { showWhitelistDialog = false }
        )
    }

    // 呼号库编辑对话框
    if (showCallsignLibraryDialog) {
        CallsignLibraryEditDialog(
            onDismiss = { showCallsignLibraryDialog = false }
        )
    }

    // 数据管理对话框
    if (showDataManagementDialog) {
        DataManagementDialog(
            onDismiss = { showDataManagementDialog = false }
        )
    }

    // 呼号记录对话框
    if (showCallsignRecordsDialog) {
        CallsignRecordsDialog(
            onDismiss = { showCallsignRecordsDialog = false }
        )
    }

    // API设置对话框
    if (showApiSettingsDialog) {
        ApiSettingsDialog(
            onDismiss = { showApiSettingsDialog = false },
            onApiSaved = {
                Toast.makeText(context, context.getString(R.string.settings_api_saved), Toast.LENGTH_SHORT).show()
            }
        )
    }

    // 语言设置对话框
    if (showLanguageDialog) {
        LanguageSettingsDialog(
            onDismiss = { showLanguageDialog = false }
        )
    }
}

@Composable
private fun SettingsItem(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 图标
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // 文字内容
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }

            // 箭头图标
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(180f),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun SettingsItemWithCustomIcon(
    title: String,
    description: String,
    iconContent: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 图标
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    iconContent()
                }
            }

            // 文字内容
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }

            // 箭头图标
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(180f),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
            )
        }
    }
}
