package com.bh6aap.ic705Cter.ui.components

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.bh6aap.ic705Cter.util.LogManager
import com.bh6aap.ic705Cter.data.radio.BluetoothDevicePreference
import com.bh6aap.ic705Cter.R
import androidx.compose.ui.res.stringResource

/**
 * 蓝牙设备连接抽屉
 * 从底部导航栏上方弹出，展示已配对的蓝牙设备
 */
@Composable
fun BluetoothDeviceDrawer(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit = {},
    onSetDefaultDevice: ((BluetoothDevice) -> Unit)? = null
) {
    val context = LocalContext.current
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

    // 当抽屉显示时加载设备
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

    // 自定义底部抽屉，从底部按钮上方弹出，高度50%
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })
    ) {
        // 使用 Box 配合 LocalConfiguration 获取屏幕高度
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val screenHeight = configuration.screenHeightDp.dp

        Box(
            modifier = Modifier
                .fillMaxSize()

        ) {


            // 抽屉内容 - 固定在底部，高度50%
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(screenHeight * 0.5f)  // 50% 屏幕高度
                    .align(Alignment.BottomCenter)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize(),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // 标题栏
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.bluetooth_drawer_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.bluetooth_drawer_close)
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        // 设备列表
                        if (!hasPermission) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.bluetooth_drawer_permission_required),
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
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(devices) { device ->
                                    val isDefaultDevice = device.address == defaultDeviceAddress
                                    BluetoothDeviceItem(
                                        device = device,
                                        isDefaultDevice = isDefaultDevice,
                                        onClick = {
                                            onDeviceSelected(device)
                                            onDismiss()
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
}

@Composable
fun BluetoothDeviceItem(
    device: BluetoothDevice,
    isDefaultDevice: Boolean = false,
    onClick: () -> Unit,
    onSetDefault: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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
        shape = RoundedCornerShape(8.dp),
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
                    .size(40.dp)
                    .background(
                        color = if (isIcomDevice) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = RoundedCornerShape(8.dp)
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

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isIcomDevice) FontWeight.Bold else FontWeight.Normal,
                    color = if (isIcomDevice) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    text = deviceAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 默认设备标识或设为默认按钮
            if (isDefaultDevice) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        text = stringResource(R.string.bluetooth_device_default_label),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                TextButton(
                    onClick = onSetDefault,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.bluetooth_drawer_set_default),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (isIcomDevice) {
                Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                    Text(
                        text = stringResource(R.string.bluetooth_device_icom_label),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * 获取已配对的蓝牙设备，并按优先级排序
 * ICOM/705设备排在最前
 */
fun getPairedDevices(context: android.content.Context): List<BluetoothDevice> {
    val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        ?: return emptyList()

    if (!bluetoothAdapter.isEnabled) {
        LogManager.w(LogManager.TAG_PERMISSION, "【蓝牙】蓝牙未启用")
        return emptyList()
    }

    return try {
        val pairedDevices = bluetoothAdapter.bondedDevices?.toList() ?: emptyList()

        // 排序：ICOM/705设备排在最前
        val sortedDevices = pairedDevices.sortedWith { device1, device2 ->
            val name1 = device1.name?.uppercase() ?: ""
            val name2 = device2.name?.uppercase() ?: ""

            val isIcom1 = name1.contains("ICOM") || name1.contains("705")
            val isIcom2 = name2.contains("ICOM") || name2.contains("705")

            when {
                isIcom1 && !isIcom2 -> -1  // device1是ICOM，排在前面
                !isIcom1 && isIcom2 -> 1   // device2是ICOM，排在前面
                else -> name1.compareTo(name2)  // 按名称字母顺序
            }
        }

        LogManager.i(LogManager.TAG_PERMISSION, "【蓝牙】已配对设备: ${sortedDevices.size} 个")
        sortedDevices.forEach { device ->
            val name = device.name ?: "未知"
            val isIcom = name.uppercase().contains("ICOM") || name.uppercase().contains("705")
            LogManager.d(LogManager.TAG_PERMISSION, "【蓝牙】设备: $name, ICOM: $isIcom")
        }

        sortedDevices
    } catch (e: SecurityException) {
        LogManager.e(LogManager.TAG_PERMISSION, "【蓝牙】获取配对设备失败", e)
        emptyList()
    }
}
