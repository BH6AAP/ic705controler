package com.bh6aap.ic705Cter.ui.components

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import com.bh6aap.ic705Cter.data.radio.BluetoothDevicePreference
import androidx.compose.ui.res.stringResource
import com.bh6aap.ic705Cter.R

/**
 * 蓝牙设备选择弹窗
 * 用于选择默认蓝牙设备（点击设为默认，不直接连接）
 */
@Composable
fun BluetoothDeviceDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit = {},
    onSetDefaultDevice: ((BluetoothDevice) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val devicePreference = remember { BluetoothDevicePreference.getInstance(context) }

    // 蓝牙设备列表
    var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var hasPermission by remember { mutableStateOf(false) }
    var defaultDeviceAddress by remember { mutableStateOf(devicePreference.getDefaultDeviceAddress()) }

    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = permissions.values.all { it }
        if (hasPermission) {
            devices = getPairedDevices(context)
        }
    }

    // 当弹窗显示时加载设备
    LaunchedEffect(isVisible) {
        if (isVisible) {
            // 检查权限
            val bluetoothConnect = ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            if (bluetoothConnect) {
                hasPermission = true
                devices = getPairedDevices(context)
            } else {
                // 请求权限
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    )
                )
            }
        }
    }

    if (isVisible) {
        Dialog(
            onDismissRequest = onDismiss
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // 标题栏
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.bluetooth_device_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.bluetooth_device_done))
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // 设备列表
                    if (!hasPermission) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.bluetooth_permission_required),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else if (devices.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.bluetooth_no_paired_devices),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 无配对设备时，列表本身没有任何下一步可走的入口
                            // (项目从不调 startDiscovery)。把"去系统蓝牙设置"
                            // 抬到主流程里，让用户在那里完成配对再回来。
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            ) {
                                Text(stringResource(R.string.bluetooth_open_system_settings))
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(devices) { device ->
                                val isDefaultDevice = device.address == defaultDeviceAddress
                                BluetoothDeviceDialogItem(
                                    device = device,
                                    isDefaultDevice = isDefaultDevice,
                                    onClick = {
                                        // 点击设为默认设备，不直接连接
                                        devicePreference.saveDefaultDevice(device)
                                        defaultDeviceAddress = device.address
                                        onSetDefaultDevice?.invoke(device)
                                    },
                                    onSetDefault = {
                                        devicePreference.saveDefaultDevice(device)
                                        defaultDeviceAddress = device.address
                                        onSetDefaultDevice?.invoke(device)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BluetoothDeviceDialogItem(
    device: BluetoothDevice,
    isDefaultDevice: Boolean = false,
    onClick: () -> Unit,
    onSetDefault: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val deviceName = remember {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            device.name ?: context.getString(R.string.bluetooth_device_unknown)
        } else {
            context.getString(R.string.bluetooth_device_unknown)
        }
    }
    val deviceAddress = device.address

    // 判断是否是ICOM设备
    val isIcomDevice = remember(deviceName) {
        val upperName = deviceName.uppercase()
        upperName.contains("ICOM") || upperName.contains("705")
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isIcomDevice) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 蓝牙图标
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = if (isIcomDevice) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "BT",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isIcomDevice) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.Bold
                )
            }

            // 设备信息区域（使用weight占据剩余空间，但设置最小宽度）
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isIcomDevice) FontWeight.Bold else FontWeight.Normal,
                    color = if (isIcomDevice) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = deviceAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 标签区域（使用固定宽度，不挤压中间内容）
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 默认设备标识
                if (isDefaultDevice) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = stringResource(R.string.bluetooth_device_default_label),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // ICOM标识
                if (isIcomDevice) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = stringResource(R.string.bluetooth_device_icom_label),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
