package com.bh6aap.ic705Cter

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import com.bh6aap.ic705Cter.data.api.TleDataManager
import com.bh6aap.ic705Cter.data.api.TransmitterDataManager
import com.bh6aap.ic705Cter.data.database.DatabaseHelper
import com.bh6aap.ic705Cter.data.database.DatabaseRouter
import com.bh6aap.ic705Cter.data.database.entity.SatelliteEntity
import com.bh6aap.ic705Cter.data.database.entity.StationEntity
import com.bh6aap.ic705Cter.data.location.GpsManager
import com.bh6aap.ic705Cter.data.location.LocationData
import com.bh6aap.ic705Cter.data.radio.BluetoothConnectionManager
import com.bh6aap.ic705Cter.data.radio.BluetoothSppConnector
import com.bh6aap.ic705Cter.data.radio.CivController
import com.bh6aap.ic705Cter.data.radio.BluetoothDevicePreference
import com.bh6aap.ic705Cter.service.BluetoothForegroundService
import com.bh6aap.ic705Cter.notification.PassNotificationManager
import com.bh6aap.ic705Cter.data.PassNotificationDataStore
import com.bh6aap.ic705Cter.ui.components.BluetoothDeviceDialog
import com.bh6aap.ic705Cter.ui.components.StationListDialog
import com.bh6aap.ic705Cter.R
import com.bh6aap.ic705Cter.ui.components.getPairedDevices
import com.bh6aap.ic705Cter.ui.theme.Ic705controlerTheme
import android.content.Intent
import com.bh6aap.ic705Cter.util.LogManager
import com.bh6aap.ic705Cter.util.MaidenheadConverter
import com.bh6aap.ic705Cter.util.SatelliteCalculator
import com.bh6aap.ic705Cter.util.EditableWhitelistManager
import com.bh6aap.ic705Cter.util.SatellitePassCalculator
import com.bh6aap.ic705Cter.util.OptimizedPassCalculator
import com.bh6aap.ic705Cter.tracking.SatelliteTracker
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull


class MainActivity : BaseActivity() {

    // 蓝牙连接管理器
    private val bluetoothConnectionManager = BluetoothConnectionManager.getInstance()
    
    // GPS管理器
    private lateinit var gpsManager: GpsManager

    // TLE数据管理器
    private lateinit var tleDataManager: TleDataManager

    // 转发器数据管理器
    private lateinit var transmitterDataManager: TransmitterDataManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化 Orekit
        OrekitInitializer.initialize(this)

        // 初始化GPS管理器
        gpsManager = GpsManager(this)

        // 初始化TLE数据管理器
        tleDataManager = TleDataManager(this)

        // 初始化转发器数据管理器
        transmitterDataManager = TransmitterDataManager(this)

        // 初始化数据库路由器（根据用户设置选择默认或自定义数据库）
        val dbRouter = DatabaseRouter.getInstance(this)
        dbRouter.refreshDatabaseType()
        LogManager.i("MainActivity", "【数据库】使用${if (dbRouter.isUsingCustomDatabase()) "自定义" else "默认"}数据库")

        // 预初始化 SatelliteTracker（后台线程，缓存地球模型和地面站框架）
        lifecycleScope.launch(Dispatchers.IO) {
            LogManager.i("MainActivity", "【预初始化】开始 SatelliteTracker 预初始化...")
            try {
                val tracker = SatelliteTracker.getInstance(this@MainActivity)
                LogManager.i("MainActivity", "【预初始化】获取 SatelliteTracker 实例成功")
                val result = tracker.initializeStation()
                LogManager.i("MainActivity", "【预初始化】SatelliteTracker 预初始化完成，结果=$result")
            } catch (e: Exception) {
                LogManager.e("MainActivity", "【预初始化】SatelliteTracker 预初始化失败", e)
            }
        }

        // 启动前台服务
        LogManager.i(LogManager.TAG_PERMISSION, "【前台服务】启动蓝牙前台服务")
        BluetoothForegroundService.startService(this)

        enableEdgeToEdge()
        setContent {
            Ic705controlerTheme {
                var stationData by remember { mutableStateOf<StationEntity?>(null) }
                var maidenheadGrid by remember { mutableStateOf("") }
                var showBluetoothDialog by remember { mutableStateOf(false) }
                var connectionState by remember { mutableStateOf(bluetoothConnectionManager.getConnectionState()) }
                val devicePreference = remember { BluetoothDevicePreference.getInstance(this@MainActivity) }
                
                // 退出确认对话框状态
                var showExitDialog by remember { mutableStateOf(false) }
                
                // 处理返回键 - 显示退出确认对话框
                BackHandler {
                    showExitDialog = true
                }
                
                // 退出确认对话框
                if (showExitDialog) {
                    AlertDialog(
                        onDismissRequest = { showExitDialog = false },
                        title = { Text(stringResource(R.string.main_exit_title)) },
                        text = { Text(stringResource(R.string.main_exit_message)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showExitDialog = false
                                    finish()
                                }
                            ) {
                                Text(stringResource(R.string.common_ok))
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showExitDialog = false }
                            ) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                    )
                }
                var isConnecting by remember { mutableStateOf(false) }
                // 注意：VFO频率和模式不在主界面显示，由SatelliteTrackingActivity管理
                var tleUpdateTime by remember { mutableStateOf(getString(R.string.main_tle_not_updated)) }
                var transmitterUpdateTime by remember { mutableStateOf(getString(R.string.main_tle_not_updated)) }
                var showSatelliteDialog by remember { mutableStateOf(false) }
                var refreshStationTrigger by remember { mutableStateOf(0) }
                var showStationListDialog by remember { mutableStateOf(false) }

                // 从数据库加载TLE更新时间（支持 celestrak 和 satnogs）
                LaunchedEffect(Unit) {
                    val lastSyncTime = withContext(Dispatchers.IO) {
                        val dbHelper = DatabaseHelper.getInstance(this@MainActivity)
                        // 首先尝试获取 celestrak 的同步记录，如果没有则尝试 satnogs
                        val record = dbHelper.getLastSyncRecord("tle_celestrak")
                            ?: dbHelper.getLastSyncRecord("tle_satnogs")
                        record?.let {
                            java.text.SimpleDateFormat(
                                "yyyy/MM/dd-HH:mm:ss",
                                java.util.Locale.getDefault()
                            ).format(java.util.Date(it.syncTime))
                        } ?: "尚未更新"
                    }
                    tleUpdateTime = lastSyncTime
                }

                // 从数据库加载转发器更新时间
                LaunchedEffect(Unit) {
                    val lastSyncTime = withContext(Dispatchers.IO) {
                        transmitterDataManager.getLastUpdateTime()
                    }
                    transmitterUpdateTime = lastSyncTime
                }

                // 监听连接状态变化
                LaunchedEffect(Unit) {
                    bluetoothConnectionManager.connectionState.collect {
                        connectionState = it
                    }
                }

                // 注意：VFO频率查询由CivController全权负责
                // MainActivity不监听VFO频率和模式，避免干扰卫星跟踪的电台控制
                // 所有VFO相关操作应在SatelliteTrackingActivity中进行

                // 加载GPS数据
                LaunchedEffect(refreshStationTrigger) {
                    loadStationData { station, grid ->
                        stationData = station
                        maidenheadGrid = grid
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        // GPS数据展示栏
                        GpsInfoBar(
                            station = stationData,
                            maidenheadGrid = maidenheadGrid,
                            onRefresh = {
                                // 显示Toast提示
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.main_gps_waiting),
                                    Toast.LENGTH_SHORT
                                ).show()
                                // 刷新GPS信息
                                refreshGpsInfo {
                                    stationData = it
                                    maidenheadGrid = if (it != null) {
                                        MaidenheadConverter.latLonToMaidenhead(
                                            it.latitude,
                                            it.longitude,
                                            precision = 8
                                        )
                                    } else {
                                        ""
                                    }
                                }
                            },
                            onSettingsClick = {
                                LogManager.i("MainActivity", "点击设置按钮，打开设置界面")
                                SettingsActivity.start(this@MainActivity)
                            },
                            onLongPress = {
                                LogManager.i("MainActivity", "长按GPS栏，打开地面站列表")
                                showStationListDialog = true
                            }
                        )
                    }
                ) { innerPadding ->
                    // 获取屏幕尺寸用于适配
                    val configuration = LocalConfiguration.current
                    val screenWidth = configuration.screenWidthDp.dp
                    val screenHeight = configuration.screenHeightDp.dp
                    val isSmallScreen = screenWidth < 360.dp || screenHeight < 640.dp
                    val isLargeScreen = screenWidth >= 420.dp && screenHeight >= 800.dp

                    // 根据屏幕尺寸调整间距和高度
                    val horizontalPadding = if (isSmallScreen) 12.dp else 16.dp
                    val verticalPadding = if (isSmallScreen) 6.dp else 8.dp
                    val elementSpacing = if (isSmallScreen) 6.dp else 8.dp
                    val connectionCardHeight = if (isSmallScreen) 48.dp else if (isLargeScreen) 64.dp else 56.dp

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                        verticalArrangement = Arrangement.spacedBy(elementSpacing)
                    ) {
                        // 标题区域已清空

                        // 连接按钮行：蓝牙连接按钮（扩展宽度）+ CW按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(elementSpacing),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isBtConnected = connectionState == BluetoothSppConnector.ConnectionState.CONNECTED
                            val buttonTextStyle = when {
                                isSmallScreen -> MaterialTheme.typography.bodySmall
                                isLargeScreen -> MaterialTheme.typography.titleSmall
                                else -> MaterialTheme.typography.bodyMedium
                            }
                            val detailTextStyle = MaterialTheme.typography.labelSmall

                            // 获取已连接设备名称
                            val connectedDeviceName by remember {
                                derivedStateOf {
                                    if (isBtConnected) {
                                        bluetoothConnectionManager.currentConnector.value?.getConnectedDevice()?.name ?: getString(R.string.bluetooth_device_unknown)
                                    } else {
                                        ""
                                    }
                                }
                            }

                            // 获取默认设备名称（用于显示在未连接状态）
                            val defaultDeviceName = remember { devicePreference.getDefaultDeviceName() }

                            // 蓝牙连接按钮（扩展宽度，显示连接详情）
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(connectionCardHeight)
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onTap = {
                                                if (connectionState == BluetoothSppConnector.ConnectionState.CONNECTED) {
                                                    // 已连接，断开连接
                                                    LogManager.i(LogManager.TAG_PERMISSION, "【主界面】断开蓝牙连接")
                                                    lifecycleScope.launch {
                                                        bluetoothConnectionManager.disconnect()
                                                    }
                                                    lastConnectedDevice = null
                                                } else {
                                                    // 未连接，尝试连接默认设备
                                                    val defaultAddress = devicePreference.getDefaultDeviceAddress()
                                                    if (defaultAddress != null) {
                                                        // 有默认设备，尝试直接连接
                                                        lifecycleScope.launch {
                                                            val pairedDevices = getPairedDevices(this@MainActivity)
                                                            val defaultDevice = devicePreference.findDefaultDevice(pairedDevices)
                                                            if (defaultDevice != null) {
                                                                LogManager.i(LogManager.TAG_PERMISSION, "【主界面】单击连接默认设备: ${defaultDevice.name}")
                                                                val success = bluetoothConnectionManager.connectToDevice(defaultDevice)
                                                                if (success) {
                                                                    lastConnectedDevice = defaultDevice
                                                                    Toast.makeText(this@MainActivity, getString(R.string.main_bluetooth_connected_to, defaultDevice.name), Toast.LENGTH_SHORT).show()
                                                                } else {
                                                                    Toast.makeText(this@MainActivity, getString(R.string.main_bluetooth_connection_failed), Toast.LENGTH_SHORT).show()
                                                                }
                                                            } else {
                                                                // 找不到默认设备，显示设备列表
                                                                LogManager.i(LogManager.TAG_PERMISSION, "【主界面】默认设备未找到，显示设备列表")
                                                                showBluetoothDialog = true
                                                            }
                                                        }
                                                    } else {
                                                        // 没有默认设备，显示设备列表
                                                        LogManager.i(LogManager.TAG_PERMISSION, "【主界面】无默认设备，显示设备列表")
                                                        showBluetoothDialog = true
                                                    }
                                                }
                                            },
                                            onLongPress = {
                                                // 长按显示设备列表弹窗（用于修改默认设备）
                                                LogManager.i(LogManager.TAG_PERMISSION, "【主界面】长按蓝牙按钮，显示设备列表")
                                                showBluetoothDialog = true
                                            }
                                        )
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = when {
                                    isBtConnected -> MaterialTheme.colorScheme.primaryContainer
                                    isConnecting -> MaterialTheme.colorScheme.tertiaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 左侧：蓝牙图标和状态
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Column {
                                            Text(
                                                text = stringResource(R.string.main_bluetooth),
                                                style = buttonTextStyle,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                            Text(
                                                text = when {
                                                    isBtConnected -> stringResource(R.string.main_bluetooth_connected)
                                                    isConnecting -> stringResource(R.string.main_bluetooth_connecting)
                                                    else -> stringResource(R.string.main_bluetooth_disconnected)
                                                },
                                                style = detailTextStyle,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                            )
                                        }
                                    }

                                    // 右侧：连接详情
                                    Column(
                                        horizontalAlignment = Alignment.End
                                    ) {
                                        if (isBtConnected) {
                                            // 已连接：显示设备名称和VFO频率
                                            Text(
                                                text = connectedDeviceName,
                                                style = buttonTextStyle,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            // 显示VFO A频率
                                            val vfoAFreq by bluetoothConnectionManager.vfoAFrequency.collectAsState()
                                            if (vfoAFreq != "-") {
                                                Text(
                                                    text = stringResource(R.string.main_vfo_a, vfoAFreq),
                                                    style = detailTextStyle,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        } else if (!defaultDeviceName.isNullOrEmpty()) {
                                            // 未连接但有默认设备：显示默认设备名称
                                            Text(
                                                text = defaultDeviceName,
                                                style = buttonTextStyle,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = stringResource(R.string.main_bluetooth_long_press_modify),
                                                style = detailTextStyle,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        } else {
                                            // 未连接且无默认设备
                                            Text(
                                                text = stringResource(R.string.main_bluetooth_long_press_select),
                                                style = detailTextStyle,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            // CW按钮（正方形，保持原大小）
                            Surface(
                                modifier = Modifier
                                    .size(connectionCardHeight)
                                    .clickable {
                                        // 先设置电台模式为CW，然后跳转到摩尔斯页面
                                        lifecycleScope.launch {
                                            val civController = bluetoothConnectionManager.civController.value
                                            if (civController != null) {
                                                // 设置VFO A和VFO B都为CW模式
                                                val vfoASuccess = civController.setVfoAMode(CivController.MODE_CW)
                                                val vfoBSuccess = civController.setVfoBMode(CivController.MODE_CW)
                                                if (vfoASuccess && vfoBSuccess) {
                                                    LogManager.i("MainActivity", "【主界面】设置电台模式为CW成功")
                                                } else {
                                                    LogManager.w("MainActivity", "【主界面】设置电台模式为CW失败或电台未连接")
                                                }
                                            } else {
                                                LogManager.w("MainActivity", "【主界面】CIV控制器未初始化，无法设置CW模式")
                                            }
                                            // 跳转到摩尔斯页面
                                            val intent = Intent(this@MainActivity, MorseCodeActivity::class.java)
                                            startActivity(intent)
                                            LogManager.i("MainActivity", "【主界面】点击进入CW摩尔斯页面")
                                        }
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isBtConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary.copy(alpha = 0.9f)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.main_cw_button),
                                        style = buttonTextStyle,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isBtConnected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary
                                    )
                                }
                            }
                        }

                        // TLE和转发器更新时间显示（点击左侧更新TLE，点击右侧更新ZFQ）
                        val timeRowPadding = if (isSmallScreen) 8.dp else 12.dp
                        val timeRowVerticalPadding = if (isSmallScreen) 4.dp else 6.dp

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = timeRowPadding, vertical = timeRowVerticalPadding),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // TLE时间（点击更新）
                                Text(
                                    text = stringResource(R.string.main_tle_time, tleUpdateTime),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.clickable {
                                        LogManager.i(LogManager.TAG_TLE, "【主界面】点击TLE时间戳，开始更新")
                                        Toast.makeText(this@MainActivity, getString(R.string.main_tle_updating), Toast.LENGTH_SHORT).show()
                                        lifecycleScope.launch {
                                            val success = tleDataManager.fetchTleData(forceRefresh = true)
                                            if (success) {
                                                val lastSyncTime = withContext(Dispatchers.IO) {
                                                    val dbHelper = DatabaseHelper.getInstance(this@MainActivity)
                                                    // 首先尝试获取 celestrak 的同步记录，如果没有则尝试 satnogs
                                                    val record = dbHelper.getLastSyncRecord("tle_celestrak")
                                                        ?: dbHelper.getLastSyncRecord("tle_satnogs")
                                                    record?.let {
                                                        java.text.SimpleDateFormat(
                                                            "yyyy/MM/dd-HH:mm:ss",
                                                            java.util.Locale.getDefault()
                                                        ).format(java.util.Date(it.syncTime))
                                                    } ?: getString(R.string.common_success)
                                                }
                                                tleUpdateTime = lastSyncTime
                                                Toast.makeText(this@MainActivity, getString(R.string.main_tle_update_success), Toast.LENGTH_SHORT).show()
                                                LogManager.i(LogManager.TAG_TLE, "【主界面】TLE更新成功: $tleUpdateTime")
                                            } else {
                                                Toast.makeText(this@MainActivity, getString(R.string.main_tle_update_failed), Toast.LENGTH_LONG).show()
                                                LogManager.e(LogManager.TAG_TLE, "【主界面】TLE更新失败")
                                            }
                                        }
                                    },
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip
                                )
                                // 分隔符
                                Text(
                                    text = " | ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                                // ZFQ时间（点击更新）
                                Text(
                                    text = stringResource(R.string.main_transmitter_time, transmitterUpdateTime),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        LogManager.i(LogManager.TAG_TLE, "【主界面】点击ZFQ时间戳，开始更新")
                                        Toast.makeText(this@MainActivity, getString(R.string.main_transmitter_updating), Toast.LENGTH_SHORT).show()
                                        lifecycleScope.launch {
                                            val success = transmitterDataManager.updateTransmitters()
                                            if (success) {
                                                val lastSyncTime = withContext(Dispatchers.IO) {
                                                    transmitterDataManager.getLastUpdateTime()
                                                }
                                                transmitterUpdateTime = lastSyncTime
                                                Toast.makeText(this@MainActivity, getString(R.string.main_transmitter_update_success), Toast.LENGTH_SHORT).show()
                                                LogManager.i(LogManager.TAG_TLE, "【主界面】转发器更新成功: $transmitterUpdateTime")
                                            } else {
                                                Toast.makeText(this@MainActivity, getString(R.string.main_transmitter_update_failed), Toast.LENGTH_LONG).show()
                                                LogManager.e(LogManager.TAG_TLE, "【主界面】转发器更新失败")
                                            }
                                        }
                                    },
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip
                                )
                            }
                        }

                        // 卫星过境列表
                        SatellitePassList(
                            stationData = stationData,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 蓝牙设备选择弹窗
                BluetoothDeviceDialog(
                    isVisible = showBluetoothDialog,
                    onDismiss = { showBluetoothDialog = false },
                    onDeviceSelected = { device ->
                        handleBluetoothDeviceSelected(device)
                    },
                    onSetDefaultDevice = { device ->
                        Toast.makeText(this@MainActivity, getString(R.string.bluetooth_device_set_default, device.name), Toast.LENGTH_SHORT).show()
                    }
                )

                // 卫星列表弹窗
                if (showSatelliteDialog) {
                    SatelliteListDialog(
                        onDismiss = { showSatelliteDialog = false },
                        stationData = stationData
                    )
                }

                // 地面站列表弹窗
                if (showStationListDialog) {
                    StationListDialog(
                        onDismiss = { showStationListDialog = false },
                        onStationSelected = { selectedStation ->
                            // 更新当前显示的地面站数据
                            stationData = selectedStation
                            maidenheadGrid = MaidenheadConverter.latLonToMaidenhead(
                                selectedStation.latitude,
                                selectedStation.longitude,
                                precision = 8
                            )
                            Toast.makeText(this@MainActivity, getString(R.string.main_switched_to, selectedStation.name), Toast.LENGTH_SHORT).show()
                            LogManager.i("MainActivity", "切换到地面站: ${selectedStation.name}")
                        },
                        currentStationId = stationData?.id
                    )
                }

            }
        }
    }

    override fun onResume() {
        super.onResume()
        LogManager.i("MainActivity", "【主界面】onResume")

        // 注意：移除了从卫星跟踪界面返回时的频率查询操作
        // 避免干扰卫星跟踪的电台控制
    }

    private fun loadStationData(onDataLoaded: (StationEntity?, String) -> Unit) {
        lifecycleScope.launch {
            try {
                val station = withContext(Dispatchers.IO) {
                    val dbHelper = DatabaseHelper.getInstance(this@MainActivity)
                    dbHelper.getDefaultStation()
                }

                val grid = if (station != null) {
                    // 转换为8位梅登海德网格
                    MaidenheadConverter.latLonToMaidenhead(
                        station.latitude,
                        station.longitude,
                        precision = 8
                    )
                } else {
                    ""
                }

                LogManager.i(LogManager.TAG_GPS, "【主界面】加载地面站数据: ${station?.name ?: "无数据"}")
                LogManager.i(LogManager.TAG_GPS, "【主界面】梅登海德网格(8位): $grid")

                onDataLoaded(station, grid)
            } catch (e: Exception) {
                LogManager.e(LogManager.TAG_GPS, "【主界面】加载地面站数据失败", e)
                onDataLoaded(null, "")
            }
        }
    }

    private fun refreshGpsInfo(onRefreshComplete: (StationEntity?) -> Unit) {
        lifecycleScope.launch {
            try {
                LogManager.i(LogManager.TAG_GPS, "【主界面】开始刷新GPS信息")
                
                // 获取GPS位置（10秒超时）
                val locationData = getLocationWithTimeout()
                
                if (locationData != null) {
                    // 保存位置到数据库
                    val station = withContext(Dispatchers.IO) {
                        val dbHelper = DatabaseHelper.getInstance(this@MainActivity)
                        saveLocationToDatabase(dbHelper, locationData)
                        dbHelper.getDefaultStation()
                    }
                    
                    LogManager.i(LogManager.TAG_GPS, "【主界面】GPS信息刷新成功")
                    Toast.makeText(this@MainActivity, getString(R.string.main_gps_success), Toast.LENGTH_SHORT).show()
                    onRefreshComplete(station)
                } else {
                    LogManager.e(LogManager.TAG_GPS, "【主界面】GPS信息刷新失败: 无法获取位置")
                    Toast.makeText(this@MainActivity, getString(R.string.main_gps_failed), Toast.LENGTH_LONG).show()
                    onRefreshComplete(null)
                }
            } catch (e: Exception) {
                LogManager.e(LogManager.TAG_GPS, "【主界面】GPS信息刷新失败", e)
                Toast.makeText(this@MainActivity, getString(R.string.main_gps_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                onRefreshComplete(null)
            }
        }
    }

    private suspend fun getLocationWithTimeout(): LocationData? {
        val GPS_TIMEOUT_MS = 5000L // GPS超时5秒

        LogManager.i(LogManager.TAG_GPS, "【主界面】同时获取GPS和GMS位置（超时: ${GPS_TIMEOUT_MS/1000}秒）")

        return try {
            coroutineScope {
                // 同时启动GPS和GMS定位
                val gpsDeferred = async(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(GPS_TIMEOUT_MS) {
                            gpsManager.getCurrentLocationModern(timeoutMs = GPS_TIMEOUT_MS)
                        }
                    } catch (e: Exception) {
                        LogManager.w(LogManager.TAG_GPS, "【主界面】GPS定位失败: ${e.message}")
                        null
                    }
                }

                val gmsDeferred = async(Dispatchers.IO) {
                    try {
                        gpsManager.getLastKnownLocation()
                    } catch (e: Exception) {
                        LogManager.w(LogManager.TAG_GPS, "【主界面】GMS定位失败: ${e.message}")
                        null
                    }
                }

                // 等待两个定位结果
                val gpsLocation = gpsDeferred.await()
                val gmsLocation = gmsDeferred.await()

                // 选择精度高的位置
                val selectedLocation = when {
                    gpsLocation != null && gmsLocation != null -> {
                        // 两者都可用，选择精度高的（accuracy值越小越精确）
                        val gpsAccuracy = gpsLocation.accuracy
                        val gmsAccuracy = gmsLocation.accuracy
                        if (gpsAccuracy <= gmsAccuracy) {
                            LogManager.i(LogManager.TAG_GPS, "【主界面】GPS和GMS都可用，选择GPS（精度更高: ${gpsAccuracy}m < ${gmsAccuracy}m）")
                            gpsLocation
                        } else {
                            LogManager.i(LogManager.TAG_GPS, "【主界面】GPS和GMS都可用，选择GMS（精度更高: ${gmsAccuracy}m < ${gpsAccuracy}m）")
                            gmsLocation
                        }
                    }
                    gpsLocation != null -> {
                        LogManager.i(LogManager.TAG_GPS, "【主界面】仅GPS可用，精度: ${gpsLocation.accuracy}m")
                        gpsLocation
                    }
                    gmsLocation != null -> {
                        LogManager.i(LogManager.TAG_GPS, "【主界面】仅GMS可用，精度: ${gmsLocation.accuracy}m")
                        gmsLocation
                    }
                    else -> {
                        LogManager.e(LogManager.TAG_GPS, "【主界面】GPS和GMS都获取失败")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.error_gps_disabled),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        return@coroutineScope null
                    }
                }

                val locationData = LocationData.fromLocation(selectedLocation)
                LogManager.d(LogManager.TAG_GPS, "【主界面】位置获取成功: 纬度=${locationData.latitude}, 经度=${locationData.longitude}, 提供者=${locationData.provider}")
                LogManager.i(LogManager.TAG_GPS, "【主界面】位置获取成功: 提供者=${locationData.provider}")
                locationData
            }
        } catch (e: Exception) {
            LogManager.e(LogManager.TAG_GPS, "【主界面】位置获取失败", e)
            null
        }
    }

    private suspend fun saveLocationToDatabase(dbHelper: DatabaseHelper, locationData: LocationData) {
        LogManager.i(LogManager.TAG_GPS, "【主界面】保存GPS位置到数据库")

        // 先准备好新的地面站数据
        val station = StationEntity(
            name = getString(R.string.main_current_location),
            latitude = locationData.latitude,
            longitude = locationData.longitude,
            altitude = locationData.altitude,
            isDefault = true,
            notes = getString(R.string.main_gps_location_saved, locationData.accuracy, locationData.provider)
        )

        try {
            // 先清除旧数据，再插入新数据，确保数据库中始终有有效的位置信息
            dbHelper.clearDefaultStation()
            LogManager.d(LogManager.TAG_GPS, "【主界面】已清除之前的默认地面站")

            val stationId = dbHelper.insertStation(station)
            LogManager.d(LogManager.TAG_GPS, "【主界面】地面站已保存，ID: $stationId, 位置: ${locationData.latitude}, ${locationData.longitude}")
            LogManager.i(LogManager.TAG_GPS, "【主界面】地面站已保存，ID: $stationId")
        } catch (e: Exception) {
            LogManager.e(LogManager.TAG_GPS, "【主界面】保存地面站失败", e)
            throw e
        }
    }

    private var lastConnectedDevice: BluetoothDevice? = null

    override fun onDestroy() {
        super.onDestroy()
        // 停止前台服务
        LogManager.i(LogManager.TAG_PERMISSION, "【前台服务】停止蓝牙前台服务")
        BluetoothForegroundService.stopService(this)
        // 断开蓝牙连接
        lifecycleScope.launch {
            bluetoothConnectionManager.disconnect()
        }
    }



    private fun handleBluetoothDeviceSelected(device: BluetoothDevice) {
        val deviceName = device.name ?: getString(R.string.bluetooth_device_unknown)
        val deviceAddress = device.address
        LogManager.i(LogManager.TAG_PERMISSION, "【蓝牙】选择设备: $deviceName ($deviceAddress)")

        // 检查是否是同一设备
        if (lastConnectedDevice != null && lastConnectedDevice?.address == device.address) {
            // 同一设备，断开连接
            LogManager.i(LogManager.TAG_PERMISSION, "【蓝牙】断开与设备的连接: $deviceName ($deviceAddress)")
            lifecycleScope.launch {
                bluetoothConnectionManager.disconnect()
            }
            lastConnectedDevice = null
            Toast.makeText(this, getString(R.string.main_bluetooth_disconnected_from, deviceName), Toast.LENGTH_SHORT).show()
        } else {
            // 不同设备，连接新设备
            lifecycleScope.launch {
                val success = bluetoothConnectionManager.connectToDevice(device)
                LogManager.i(LogManager.TAG_PERMISSION, "【蓝牙】连接结果: $success")

                if (success) {
                    lastConnectedDevice = device
                    Toast.makeText(this@MainActivity, getString(R.string.main_bluetooth_connected_to, deviceName), Toast.LENGTH_SHORT).show()
                    // 注意：移除了连接成功后的VFO查询操作
                    // 避免干扰卫星跟踪的电台控制
                    LogManager.i(LogManager.TAG_PERMISSION, "【蓝牙】连接成功")
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.main_bluetooth_connection_error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpsInfoBar(
    station: StationEntity?,
    maidenheadGrid: String,
    onRefresh: () -> Unit,
    onSettingsClick: () -> Unit,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 获取状态栏高度和屏幕尺寸
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val isSmallScreen = screenWidth < 360.dp
    val isLargeScreen = screenWidth >= 420.dp

    // 根据屏幕尺寸调整padding和图标大小
    val horizontalPadding = if (isSmallScreen) 12.dp else 16.dp
    val contentPadding = if (isSmallScreen) 8.dp else 12.dp
    val iconSize = if (isSmallScreen) 20.dp else if (isLargeScreen) 28.dp else 24.dp
    val titleStyle = when {
        isSmallScreen -> MaterialTheme.typography.bodyMedium
        isLargeScreen -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    val dataStyle = when {
        isSmallScreen -> MaterialTheme.typography.bodySmall
        isLargeScreen -> MaterialTheme.typography.bodyLarge
        else -> MaterialTheme.typography.bodyMedium
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = statusBarHeight, start = horizontalPadding, end = horizontalPadding, bottom = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：设置按钮（高度与GPS信息栏相等）
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .clickable { onSettingsClick() },
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.settings_title),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(iconSize)
                    )
                }
            }

            // 中间：GPS信息（可点击刷新，长按弹出地面站列表）
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onRefresh() }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { onLongPress() }
                        )
                    }
                    .padding(horizontal = contentPadding, vertical = contentPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 左侧：位置图标
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = stringResource(R.string.main_current_location),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(iconSize)
                )

                // 右侧：数据展示
                if (station != null) {
                    Column(
                        horizontalAlignment = Alignment.End
                    ) {
                        // 经纬度
                        Text(
                            text = String.format("%.6f°, %.6f°", station.latitude, station.longitude),
                            style = dataStyle,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                        Spacer(modifier = Modifier.height(if (isSmallScreen) 1.dp else 2.dp))
                        // 海拔和网格
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(if (isSmallScreen) 8.dp else 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.main_altitude, station.altitude),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip
                            )
                            if (maidenheadGrid.isNotEmpty()) {
                                // 梅登海德网格标签
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Text(
                                        text = maidenheadGrid,
                                        modifier = Modifier.padding(horizontal = if (isSmallScreen) 6.dp else 8.dp, vertical = if (isSmallScreen) 1.dp else 2.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Clip
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // 无数据状态
                    Text(
                        text = stringResource(R.string.main_gps_no_data),
                        style = dataStyle,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

/**
 * 电台连接卡片
 * 仅显示连接状态和控制按钮，不显示VFO频率
 * VFO频率显示已移至SatelliteTrackingActivity
 */
@Composable
fun RadioConnectionCard(
    isConnected: Boolean,
    isConnecting: Boolean = false,
    defaultDeviceName: String? = null,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 获取屏幕尺寸用于适配
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val isSmallScreen = screenWidth < 360.dp
    val isLargeScreen = screenWidth >= 420.dp

    // 根据屏幕尺寸调整padding和文字样式
    val horizontalPadding = if (isSmallScreen) 12.dp else 16.dp
    val verticalPadding = if (isSmallScreen) 6.dp else 8.dp
    val titleStyle = when {
        isSmallScreen -> MaterialTheme.typography.bodyMedium
        isLargeScreen -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    val statusStyle = when {
        isSmallScreen -> MaterialTheme.typography.labelSmall
        else -> MaterialTheme.typography.labelMedium
    }
    val statusHorizontalPadding = if (isSmallScreen) 8.dp else 12.dp
    val statusVerticalPadding = if (isSmallScreen) 4.dp else 6.dp

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            },
        shape = RoundedCornerShape(12.dp),
        color = when {
            isConnected -> MaterialTheme.colorScheme.primaryContainer
            isConnecting -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val titleText = when {
                isConnected -> stringResource(R.string.main_bluetooth_connected)
                isConnecting -> stringResource(R.string.main_bluetooth_connecting)
                defaultDeviceName != null -> stringResource(R.string.main_bluetooth_long_press_select)
                else -> stringResource(R.string.main_bluetooth_long_press_modify)
            }
            Text(
                text = titleText,
                style = titleStyle,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )

            // 连接状态指示器
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = when {
                    isConnected -> MaterialTheme.colorScheme.primary
                    isConnecting -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
            ) {
                Text(
                    text = when {
                        isConnected -> stringResource(R.string.main_bluetooth_connected)
                        isConnecting -> stringResource(R.string.main_bluetooth_connecting)
                        else -> stringResource(R.string.main_bluetooth_disconnected)
                    },
                    modifier = Modifier.padding(horizontal = statusHorizontalPadding, vertical = statusVerticalPadding),
                    style = statusStyle,
                    fontWeight = FontWeight.Medium,
                    color = if (isConnected || isConnecting) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatelliteListDialog(
    onDismiss: () -> Unit,
    stationData: StationEntity?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var satellites by remember { mutableStateOf<List<SatelliteEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedSatellite by remember { mutableStateOf<SatelliteEntity?>(null) }
    var satellitePosition by remember { mutableStateOf<SatelliteCalculator.SatellitePosition?>(null) }
    var whitelistStats by remember { mutableStateOf("") }
    var whitelistNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // 加载卫星列表（使用白名单过滤）
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val dbHelper = DatabaseHelper.getInstance(context)
                val whitelistManager = EditableWhitelistManager.getInstance(context)
                // 初始化默认白名单
                whitelistManager.initDefaultWhitelistIfNeeded()
                val allSatellites = dbHelper.getAllSatellites().first()
                // 使用白名单过滤卫星
                satellites = whitelistManager.filterSatellites(allSatellites)
                // 获取白名单名称映射
                val nameMap = mutableMapOf<String, String>()
                satellites.forEach { satellite ->
                    val info = whitelistManager.getSatelliteInfo(satellite.noradId)
                    if (info != null) {
                        nameMap[satellite.noradId] = info.name
                    }
                }
                whitelistNames = nameMap
                whitelistStats = whitelistManager.getStatistics()
                LogManager.i("SatelliteListDialog", "【白名单】${whitelistStats}")
                isLoading = false
            } catch (e: Exception) {
                LogManager.e("SatelliteListDialog", "加载卫星列表失败", e)
                isLoading = false
            }
        }
    }

    // 计算选中卫星的位置
    LaunchedEffect(selectedSatellite) {
        selectedSatellite?.let { satellite ->
            scope.launch(Dispatchers.IO) {
                val position = if (stationData != null) {
                    SatelliteCalculator.calculatePosition(
                        satellite,
                        stationData.latitude,
                        stationData.longitude,
                        stationData.altitude
                    )
                } else {
                    // 使用默认位置（北京）
                    SatelliteCalculator.calculatePosition(
                        satellite,
                        39.9042,
                        116.4074,
                        0.0
                    )
                }
                satellitePosition = position
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.main_satellite_list),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                if (whitelistStats.isNotEmpty()) {
                    Text(
                        text = whitelistStats,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (satellites.isEmpty()) {
                Text(
                    text = stringResource(R.string.main_satellite_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 卫星选择下拉菜单
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        // 获取显示名称（优先使用白名单中的名称）
                        val displayName = selectedSatellite?.let { 
                            whitelistNames[it.noradId] ?: it.name 
                        } ?: stringResource(R.string.main_select_satellite)
                        
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.main_satellite_list)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            satellites.forEach { satellite ->
                                // 使用白名单中的名称或数据库中的名称
                                val satelliteName = whitelistNames[satellite.noradId] ?: satellite.name
                                DropdownMenuItem(
                                    text = { Text(satelliteName) },
                                    onClick = {
                                        selectedSatellite = satellite
                                        satellitePosition = null
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 显示卫星信息
                    selectedSatellite?.let { satellite ->
                        // 使用白名单中的名称或数据库中的名称
                        val satelliteDisplayName = whitelistNames[satellite.noradId] ?: satellite.name
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = satelliteDisplayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.main_satellite_info_norad_id, satellite.noradId),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                                if (satellitePosition != null) {
                                    val pos = satellitePosition!!
                                    SatelliteInfoRow(stringResource(R.string.main_satellite_info_latitude), String.format("%.4f°", pos.latitude))
                                    SatelliteInfoRow(stringResource(R.string.main_satellite_info_longitude), String.format("%.4f°", pos.longitude))
                                    SatelliteInfoRow(stringResource(R.string.main_satellite_info_altitude), String.format("%.1f km", pos.altitude))
                                    SatelliteInfoRow(stringResource(R.string.main_satellite_info_velocity), String.format("%.2f km/s", pos.velocity))
                                    SatelliteInfoRow(stringResource(R.string.main_satellite_info_azimuth), String.format("%.1f°", pos.azimuth))
                                    SatelliteInfoRow(stringResource(R.string.main_satellite_info_elevation), String.format("%.1f°", pos.elevation))
                                    SatelliteInfoRow(stringResource(R.string.main_satellite_info_range), String.format("%.0f km", pos.range))
                                    SatelliteInfoRow(
                                        stringResource(R.string.main_satellite_info_orbit_type),
                                        SatelliteCalculator.getAltitudeCategory(pos.altitude)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}

@Composable
fun SatelliteInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun SatellitePassList(
    stationData: StationEntity?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var passes by remember { mutableStateOf<List<SatellitePassCalculator.SatellitePass>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var satelliteNameMap by remember { mutableStateOf<Map<String, Pair<String, String?>>>(emptyMap()) }

    // 通知数据存储
    val notificationDataStore = remember { PassNotificationDataStore(context) }
    // 存储每个过境的通知状态
    var notificationStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    // NTP时间管理器，用于获取准确时间
    val ntpTimeManager = remember { com.bh6aap.ic705Cter.data.time.NtpTimeManager(context) }
    // 当前时间，用于过滤已结束的过境（使用NTP校准时间）
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            // 使用NTP校准时间，与过境计算使用的时间基准一致
            currentTime = ntpTimeManager.getCachedAccurateTime()
            delay(1000L) // 每秒更新一次
        }
    }

    // 创建通知渠道
    LaunchedEffect(Unit) {
        PassNotificationManager.createNotificationChannel(context)
    }

    // 24小时/12小时切换
    var is24Hours by remember { mutableStateOf(true) }
    // 仰角过滤器
    var minElevation by remember { mutableStateOf(0.0) }
    // 滑块临时值（用于拖动时显示）
    var sliderValue by remember { mutableStateOf(0f) }
    // 同步滑块值与仰角值
    LaunchedEffect(minElevation) {
        sliderValue = minElevation.toFloat()
    }
    // 加载保存的仰角阈值
    LaunchedEffect(Unit) {
        notificationDataStore.getMinElevation().collect { elevation ->
            minElevation = elevation.toDouble()
            LogManager.i("SatellitePassList", "加载仰角阈值: ${elevation}°")
        }
    }
    // 提醒提前时间（分钟）
    var reminderMinutes by remember { mutableStateOf(PassNotificationDataStore.DEFAULT_REMINDER_MINUTES) }
    // 加载保存的提醒时间
    LaunchedEffect(Unit) {
        notificationDataStore.getReminderMinutes().collect { minutes ->
            reminderMinutes = minutes
        }
    }

    // 加载过境信息（当stationData变化时或手动下拉刷新）
    val refreshTrigger = remember { mutableStateOf(0) }
    
    // LazyColumn的滚动状态
    val listState = rememberLazyListState()

    fun loadPasses(forceRefresh: Boolean = false, elevation: Double = minElevation) {
        scope.launch(Dispatchers.IO) {
            try {
                isLoading = true
                
                val dbHelper = DatabaseHelper.getInstance(context)
                val whitelistManager = EditableWhitelistManager.getInstance(context)
                // 初始化默认白名单
                whitelistManager.initDefaultWhitelistIfNeeded()
                val allSatellites = dbHelper.getAllSatellites().first()
                val filteredSatellites = whitelistManager.filterSatellites(allSatellites)

                // 获取卫星名称和别名映射
                val nameMap = mutableMapOf<String, Pair<String, String?>>()
                filteredSatellites.forEach { satellite ->
                    // 优先从卫星信息表获取别名
                    val satInfo = dbHelper.getSatelliteInfoByNoradId(satellite.noradId.toIntOrNull() ?: 0)
                    val aliases = satInfo?.names
                    nameMap[satellite.noradId] = Pair(satellite.name, aliases)
                }
                satelliteNameMap = nameMap

                // 直接计算完整时间范围的过境
                val totalHours = if (is24Hours) 24 else 12
                
                val allPasses = OptimizedPassCalculator.calculatePassesForMultipleSatellites(
                    context = context,
                    satellites = filteredSatellites,
                    minElevation = elevation,
                    hoursAhead = totalHours,
                    forceRefresh = forceRefresh
                )
                
                passes = allPasses
                isLoading = false
                LogManager.i("SatellitePassList", "【过境】过境计算完成: ${allPasses.size} 个, 仰角阈值: ${elevation}°")
            } catch (e: Exception) {
                LogManager.e("SatellitePassList", "【过境】计算失败", e)
                isLoading = false
            }
        }
    }

    // 初始加载和stationData变化时加载（非强制刷新）
    LaunchedEffect(stationData, refreshTrigger.value, minElevation) {
        loadPasses(forceRefresh = false, elevation = minElevation)
    }
    
    // 当过境数据变化时，滚动到列表顶部
    LaunchedEffect(passes) {
        if (passes.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 标题和控制栏
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 24小时/12小时切换
                    TextButton(
                        onClick = {
                            is24Hours = !is24Hours
                            refreshTrigger.value++
                        },
                        enabled = !isLoading
                    ) {
                        Text(
                            text = if (is24Hours) stringResource(R.string.main_pass_list_title_24h) else stringResource(R.string.main_pass_list_title_12h),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 加载状态指示器
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = stringResource(R.string.main_pass_list_calculating),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.main_pass_list_count, passes.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        // 刷新按钮（强制刷新，跳过缓存）
                        IconButton(
                            onClick = {
                                LogManager.i("SatellitePassList", "【过境】手动强制刷新")
                                loadPasses(forceRefresh = true)
                            },
                            enabled = !isLoading
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.common_refresh),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                
                // 仰角过滤器（滑块）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.main_pass_list_min_elevation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = sliderValue,
                        onValueChange = { newValue ->
                            // 将值四舍五入到最接近的整数，只更新显示值
                            sliderValue = kotlin.math.round(newValue)
                        },
                        onValueChangeFinished = {
                            // 拖动完成后更新仰角值并触发刷新
                            val newElevation = sliderValue.toInt().toDouble()
                            if (newElevation != minElevation) {
                                minElevation = newElevation
                                LogManager.i("SatellitePassList", "【仰角过滤】设置仰角阈值: ${minElevation}°")
                                // 保存仰角设置
                                scope.launch {
                                    notificationDataStore.setMinElevation(newElevation.toInt())
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(20.dp),
                        valueRange = 0f..60f,
                        steps = 59 // 59步，共60个值（0-60）
                    )
                    Text(
                        text = "${sliderValue.toInt()}°",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(40.dp)
                    )
                }

                // 提醒时间切换（类似24小时/12小时切换样式）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.main_pass_list_reminder_time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 获取下一个提醒时间选项
                    val nextReminderMinutes = when (reminderMinutes) {
                        3 -> 5
                        5 -> 10
                        10 -> 3
                        else -> 5
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                notificationDataStore.setReminderMinutes(nextReminderMinutes)
                            }
                        },
                        enabled = !isLoading
                    ) {
                        Text(
                            text = stringResource(R.string.main_pass_list_reminder_minutes, reminderMinutes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 过境列表 - 使用LazyColumn实现滑动，填满剩余空间
            if (passes.isEmpty() && !isLoading) {
                Text(
                    text = stringResource(R.string.main_pass_list_no_passes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 只显示可见且未结束的过境
                    val visiblePasses = passes.filter { it.isVisible && it.losTime > currentTime }
                    items(
                        items = visiblePasses,
                        key = { pass -> "${pass.noradId}_${pass.aosTime}" }
                    ) { pass ->
                        val (mainName, aliases) = satelliteNameMap[pass.noradId] ?: Pair(pass.name, null)
                        // 构建显示名称：主名称 + 别名
                        val displayName = if (!aliases.isNullOrBlank()) {
                            "$mainName ($aliases)"
                        } else {
                            mainName
                        }
                        val context = LocalContext.current

                        // 获取此过境的通知状态
                        val passKey = "${pass.noradId}_${pass.aosTime}"
                        val isNotificationEnabled by notificationDataStore.isNotificationEnabled(
                            pass.noradId,
                            pass.aosTime
                        ).collectAsState(initial = false)

                        PassItem(
                            pass = pass,
                            satelliteName = displayName,
                            onClick = {
                                SatelliteTrackingActivity.start(
                                    context = context,
                                    satelliteId = pass.noradId,
                                    satelliteName = mainName,
                                    aosTime = pass.aosTime,
                                    losTime = pass.losTime
                                )
                            },
                            onNotificationToggle = { enabled ->
                                scope.launch {
                                    if (enabled) {
                                        // 检查精确闹钟权限（Android 12+）
                                        if (!PassNotificationManager.canScheduleExactAlarms(context)) {
                                            // 提示用户需要开启权限
                                            Toast.makeText(
                                                context,
                                                "需要精确闹钟权限才能准时提醒，请在设置中开启",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            // 打开权限设置页面
                                            PassNotificationManager.openExactAlarmSettings(context)
                                        }

                                        // 启用通知
                                        notificationDataStore.enableNotification(
                                            pass.noradId,
                                            pass.aosTime
                                        )
                                        // 调度通知
                                        val scheduled = PassNotificationManager.schedulePassNotification(
                                            context,
                                            pass,
                                            displayName,
                                            reminderMinutes
                                        )
                                        if (scheduled) {
                                            Toast.makeText(
                                                context,
                                                "已设置提醒：${displayName} 过境前${reminderMinutes}分钟通知",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "已设置提醒（非精确）：${displayName} 过境前${reminderMinutes}分钟通知",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } else {
                                        // 禁用通知
                                        notificationDataStore.disableNotification(
                                            pass.noradId,
                                            pass.aosTime
                                        )
                                        // 取消调度
                                        PassNotificationManager.cancelPassNotification(
                                            context,
                                            pass.noradId,
                                            pass.aosTime
                                        )
                                        Toast.makeText(
                                            context,
                                            "已取消提醒",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            },
                            isNotificationEnabled = isNotificationEnabled
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PassItem(
    pass: SatellitePassCalculator.SatellitePass,
    satelliteName: String,
    onClick: (() -> Unit)? = null,
    onNotificationToggle: ((Boolean) -> Unit)? = null,
    isNotificationEnabled: Boolean = false
) {
    val sdfTime = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
    val sdfDate = remember { java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()) }

    // 每秒更新一次进度条
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            currentTime = System.currentTimeMillis()
            delay(1000L) // 每秒更新一次
        }
    }

    // 计算过境进度
    val progress = when {
        currentTime < pass.aosTime -> 0f // 还未开始
        currentTime > pass.losTime -> 1f // 已结束
        else -> (currentTime - pass.aosTime).toFloat() / (pass.losTime - pass.aosTime).toFloat()
    }
    val isInProgress = currentTime in pass.aosTime..pass.losTime

    // 计算是否即将过境（5分钟内）
    val secondsUntilAos = ((pass.aosTime - currentTime) / 1000).toInt()
    val isUpcomingSoon = !isInProgress && secondsUntilAos in 1..300 // 5分钟 = 300秒

    // 正在过境或即将过境时显示提醒样式
    val isAlertStatus = isInProgress || isUpcomingSoon

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 0.dp,
        color = if (isAlertStatus) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 卫星名称 - 字体加大
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = satelliteName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 时间信息 - 字体加大
                    Text(
                        text = "${sdfDate.format(java.util.Date(pass.aosTime))} ${sdfTime.format(java.util.Date(pass.aosTime))} - ${sdfTime.format(java.util.Date(pass.losTime))}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 右侧：铃铛按钮 + 最大仰角
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 铃铛按钮（仅在未过境时显示）
                    if (!isInProgress && currentTime < pass.aosTime) {
                        IconButton(
                            onClick = { onNotificationToggle?.invoke(!isNotificationEnabled) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isNotificationEnabled) {
                                    Icons.Default.Notifications
                                } else {
                                    Icons.Outlined.Notifications
                                },
                                contentDescription = if (isNotificationEnabled) "已启用提醒" else "点击启用提醒",
                                tint = if (isNotificationEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // 最大仰角 - 字体加大
                    Column(horizontalAlignment = Alignment.End) {
                        // 过境状态提醒标签
                        if (isUpcomingSoon) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.error
                            ) {
                                Text(
                                    text = stringResource(R.string.main_pass_status_upcoming_soon),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onError
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        } else if (isInProgress) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.error
                            ) {
                                Text(
                                    text = stringResource(R.string.main_pass_status_in_progress),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onError
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Text(
                            text = String.format("%.1f°", pass.maxElevation),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isAlertStatus) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        // 对于正在过境的卫星，显示剩余时间（倒计时）
                        val durationText = if (isInProgress) {
                            val remainingSeconds = ((pass.losTime - currentTime) / 1000).coerceAtLeast(0)
                            SatellitePassCalculator.formatDuration(remainingSeconds)
                        } else {
                            SatellitePassCalculator.formatDuration(pass.duration)
                        }
                        Text(
                            text = durationText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 过境进度条（类似look4sat的实时进度）
            if (isInProgress) {
                // 直接使用progress值，不添加动画效果
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    drawStopIndicator = {}
                )
            } else if (currentTime < pass.aosTime) {
                // 显示倒计时
                val secondsUntil = ((pass.aosTime - currentTime) / 1000).toInt()
                val timeText = when {
                    secondsUntil >= 3600 -> {
                        val hours = secondsUntil / 3600
                        val minutes = (secondsUntil % 3600) / 60
                        stringResource(R.string.main_pass_time_until_hours, hours, minutes)
                    }
                    secondsUntil >= 300 -> stringResource(R.string.main_pass_time_until_minutes, secondsUntil / 60)
                    secondsUntil >= 60 -> { // 1-5 分钟显示分秒
                        val minutes = secondsUntil / 60
                        val seconds = secondsUntil % 60
                        stringResource(R.string.main_pass_time_until_minutes_seconds, minutes, seconds)
                    }
                    secondsUntil > 0 -> stringResource(R.string.main_pass_time_until_seconds, secondsUntil)
                    else -> stringResource(R.string.main_pass_status_in_progress)
                }
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isUpcomingSoon) FontWeight.Bold else FontWeight.Normal,
                    color = if (isUpcomingSoon) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
