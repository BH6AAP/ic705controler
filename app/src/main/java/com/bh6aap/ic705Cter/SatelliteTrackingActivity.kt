package com.bh6aap.ic705Cter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import java.util.Locale
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bh6aap.ic705Cter.data.api.Transmitter
import com.bh6aap.ic705Cter.data.api.applyCustomData
import com.bh6aap.ic705Cter.data.database.DatabaseHelper
import com.bh6aap.ic705Cter.data.database.entity.CwPresetEntity
import com.bh6aap.ic705Cter.data.database.entity.FrequencyPresetEntity

import com.bh6aap.ic705Cter.data.CallsignDataStore
import com.bh6aap.ic705Cter.data.CallsignRecord
import com.bh6aap.ic705Cter.data.radio.BluetoothConnectionManager
import com.bh6aap.ic705Cter.sensor.SensorFusionManager
import com.bh6aap.ic705Cter.tracking.DopplerDataCache
import com.bh6aap.ic705Cter.tracking.SatelliteTracker
import com.bh6aap.ic705Cter.tracking.SatelliteTrackingController

import com.bh6aap.ic705Cter.ui.components.FrequencyDisplayPanel
import com.bh6aap.ic705Cter.ui.components.SatelliteRadarView
import com.bh6aap.ic705Cter.ui.components.TransmitterEditDialog
import com.bh6aap.ic705Cter.ui.theme.Ic705controlerTheme
import com.bh6aap.ic705Cter.util.FrequencyFormatter
import com.bh6aap.ic705Cter.util.CallsignMatcher
import com.bh6aap.ic705Cter.util.LogManager
import com.bh6aap.ic705Cter.util.toFrequencyString
import com.bh6aap.ic705Cter.util.MaidenheadConverter
import com.bh6aap.ic705Cter.util.formatFrequencyWithoutUnit
import com.bh6aap.ic705Cter.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date

private const val MAX_HISTORY_ITEMS = 5

/**
 * 卫星跟踪Activity
 * 显示雷达图，实时跟踪卫星位置
 */
class SatelliteTrackingActivity : BaseActivity() {

    private lateinit var sensorManager: SensorFusionManager
    private lateinit var satelliteTracker: SatelliteTracker
    private val bluetoothConnectionManager = BluetoothConnectionManager.getInstance()
    private lateinit var trackingController: SatelliteTrackingController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始化传感器融合管理器、跟踪器和跟踪控制器
        sensorManager = SensorFusionManager(this)
        satelliteTracker = SatelliteTracker.getInstance(this)
        trackingController = SatelliteTrackingController(bluetoothConnectionManager, this)

        // 获取传入的卫星 ID（可选，如果传入则高亮显示该卫星）
        val targetSatelliteId = intent.getStringExtra(EXTRA_SATELLITE_ID)
        val targetSatelliteName = intent.getStringExtra(EXTRA_SATELLITE_NAME) ?: this.getString(R.string.tracking_title)
        val aosTime = intent.getLongExtra(EXTRA_AOS_TIME, System.currentTimeMillis())
        val losTime = intent.getLongExtra(EXTRA_LOS_TIME, System.currentTimeMillis() + 15 * 60 * 1000)

        setContent {
            // 使用State来存储标题，支持异步更新
            var displayTitle by remember { mutableStateOf(targetSatelliteName) }
            
            // 查询卫星别名
            LaunchedEffect(targetSatelliteId) {
                if (targetSatelliteId != null) {
                    try {
                        val dbHelper = DatabaseHelper.getInstance(this@SatelliteTrackingActivity)
                        val satInfo = dbHelper.getSatelliteInfoByNoradId(targetSatelliteId.toIntOrNull() ?: 0)
                        if (satInfo != null && !satInfo.names.isNullOrBlank()) {
                            displayTitle = "$targetSatelliteName (${satInfo.names})"
                        }
                    } catch (e: Exception) {
                        LogManager.e("SatelliteTracking", "获取卫星别名失败", e)
                    }
                }
            }
            
            Ic705controlerTheme {
                SatelliteTrackingScreen(
                    title = displayTitle,
                    targetSatelliteId = targetSatelliteId,
                    aosTime = aosTime,
                    losTime = losTime,
                    sensorManager = sensorManager,
                    satelliteTracker = satelliteTracker,
                    bluetoothConnectionManager = bluetoothConnectionManager,
                    trackingController = trackingController,
                    onBackClick = {
                        // 退出时停止跟踪
                        trackingController.stopTracking()
                        finish()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager.start()
        // 卫星跟踪界面保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        LogManager.i("SatelliteTracking", "界面恢复，启用屏幕常亮")
    }

    override fun onPause() {
        super.onPause()
        sensorManager.stop()
        // 界面不可见时清除屏幕常亮标志
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        LogManager.i("SatelliteTracking", "界面暂停，清除屏幕常亮")
    }

    override fun onDestroy() {
        super.onDestroy()
        // 界面销毁时停止跟踪
        // 检查蓝牙连接状态，只在连接时才重置电台模式
        val isBluetoothConnected = bluetoothConnectionManager.getConnectionState() == com.bh6aap.ic705Cter.data.radio.BluetoothSppConnector.ConnectionState.CONNECTED
        if (isBluetoothConnected) {
            trackingController.stopTracking()
            LogManager.i("SatelliteTracking", "界面销毁，蓝牙已连接，停止跟踪并重置电台模式")
        } else {
            // 蓝牙未连接，只停止跟踪逻辑，不重置电台
            trackingController.stopTrackingWithoutReset()
            LogManager.i("SatelliteTracking", "界面销毁，蓝牙未连接，停止跟踪但不重置电台模式")
        }
    }

    companion object {
        private const val EXTRA_SATELLITE_ID = "extra_satellite_id"
        private const val EXTRA_SATELLITE_NAME = "extra_satellite_name"
        private const val EXTRA_AOS_TIME = "extra_aos_time"
        private const val EXTRA_LOS_TIME = "extra_los_time"

        /**
         * 启动跟踪Activity
         * @param aosTime 卫星升起时间（毫秒），用于计算轨迹起点
         * @param losTime 卫星落下时间（毫秒），用于计算轨迹终点
         */
        fun start(
            context: Context,
            satelliteId: String? = null,
            satelliteName: String? = null,
            aosTime: Long? = null,
            losTime: Long? = null
        ) {
            val intent = Intent(context, SatelliteTrackingActivity::class.java).apply {
                putExtra(EXTRA_SATELLITE_ID, satelliteId)
                putExtra(EXTRA_SATELLITE_NAME, satelliteName)
                aosTime?.let { putExtra(EXTRA_AOS_TIME, it) }
                losTime?.let { putExtra(EXTRA_LOS_TIME, it) }
            }
            context.startActivity(intent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SatelliteTrackingScreen(
    title: String,
    targetSatelliteId: String?,
    aosTime: Long,
    losTime: Long,
    sensorManager: SensorFusionManager,
    satelliteTracker: SatelliteTracker,
    bluetoothConnectionManager: BluetoothConnectionManager,
    trackingController: SatelliteTrackingController,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val dbHelper = remember { DatabaseHelper.getInstance(context) }

    // 传感器数据
    val orientation by sensorManager.orientation.collectAsState()

    // 蓝牙连接状态和频率数据
    val connectionState by bluetoothConnectionManager.connectionState.collectAsState()
    val vfoAFrequency by bluetoothConnectionManager.vfoAFrequency.collectAsState()
    val vfoAMode by bluetoothConnectionManager.vfoAMode.collectAsState()
    val isConnected = connectionState == com.bh6aap.ic705Cter.data.radio.BluetoothSppConnector.ConnectionState.CONNECTED

    // 卫星位置数据
    var satellitePositions by remember { mutableStateOf<List<SatelliteTracker.SatellitePosition>>(emptyList()) }
    var satelliteTrajectories by remember { mutableStateOf<Map<String, List<com.bh6aap.ic705Cter.tracking.TrajectoryPoint>>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 转发器数据
    var transmitters by remember { mutableStateOf<List<Transmitter>>(emptyList()) }
    var selectedTransmitter by remember { mutableStateOf<Transmitter?>(null) }

    // 跟踪状态
    val isTracking by trackingController.isTracking.collectAsState()

    // 网格信息（从数据库获取地面站信息并转换为4位网格）
    var grid4 by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            val station = dbHelper.getDefaultStation()
            if (station != null) {
                val grid8 = MaidenheadConverter.latLonToMaidenhead(station.latitude, station.longitude, 8)
                grid4 = if (grid8.length >= 4) grid8.substring(0, 4) else ""
            }
        } catch (e: Exception) {
            LogManager.e("SatelliteTracking", "获取网格信息失败", e)
        }
    }

    // 初始化并定期更新卫星位置
    LaunchedEffect(Unit) {
        try {
            // 初始化地面站
            val initialized = satelliteTracker.initializeStation()
            if (!initialized) {
                errorMessage = context.getString(R.string.tracking_ground_station_init_failed)
                isLoading = false
                return@LaunchedEffect
            }

            // 获取地面站位置并更新地磁偏角
            try {
                val station = dbHelper.getDefaultStation()
                if (station != null) {
                    val location = android.location.Location("").apply {
                        latitude = station.latitude
                        longitude = station.longitude
                        altitude = station.altitude
                    }
                    sensorManager.updateMagneticDeclination(location)
                    LogManager.i("SatelliteTracking", "地磁偏角已更新: ${sensorManager.getMagneticDeclination()}°")
                }
            } catch (e: Exception) {
                LogManager.w("SatelliteTracking", "更新地磁偏角失败", e)
            }

            // 获取白名单卫星
            val whitelistManager = com.bh6aap.ic705Cter.util.EditableWhitelistManager.getInstance(context)
            // 初始化默认白名单
            whitelistManager.initDefaultWhitelistIfNeeded()
            val allSatellites = dbHelper.getAllSatellites().first()
            val filteredSatellites = whitelistManager.filterSatellites(allSatellites)

            // 只为选中的卫星计算过境轨迹
            if (targetSatelliteId != null) {
                val targetSatellite = filteredSatellites.find { it.noradId == targetSatelliteId }
                if (targetSatellite != null) {
                    // 计算轨迹时长：从AOS到LOS，或者至少15分钟
                    val durationMinutes = ((losTime - aosTime) / (60 * 1000)).toInt().coerceAtLeast(15)

                    val trajectory = satelliteTracker.calculateSatelliteTrajectory(
                        satellite = targetSatellite,
                        startTime = aosTime,  // 从AOS时间开始计算
                        durationMinutes = durationMinutes,
                        intervalSeconds = 30   // 每30秒一个点
                    )
                    satelliteTrajectories = mapOf(targetSatelliteId to trajectory)
                    LogManager.i("SatelliteTracking", "计算轨迹: ${targetSatellite.name}, AOS=${Date(aosTime)}, LOS=${Date(losTime)}, 时长=${durationMinutes}分钟, ${trajectory.size}个点")
                }
            }

            // 核心数据加载完成，先显示界面
            isLoading = false

            // 延迟加载转发器数据（非关键数据）
            launch {
                try {
                    val noradId = targetSatelliteId?.toIntOrNull()
                    if (noradId != null) {
                        dbHelper.getTransmittersByNoradId(noradId).first().let { transmitterList ->
                            // 加载用户自定义转发器数据并应用到API数据
                            val customTransmitters = dbHelper.getCustomTransmittersByNoradId(noradId).first()
                            val customMap = customTransmitters.associateBy { it.uuid }
                            
                            // 应用用户自定义数据到转发器列表
                            val mergedTransmitters = transmitterList.map { transmitter ->
                                val customEntity = customMap[transmitter.uuid]
                                transmitter.applyCustomData(customEntity)
                            }
                            
                            // 判断卫星类型（基于第一个转发器的特征）
                            val isLinearSatellite = mergedTransmitters.any { 
                                it.downlinkHigh != null && it.downlinkHigh != it.downlinkLow 
                            }
                            
                            // 对转发器进行排序：根据卫星类型优先选择匹配的转发器
                            val sortedTransmitters = mergedTransmitters.sortedWith { t1, t2 ->
                                val priority1 = getTransmitterPriority(t1, isLinearSatellite)
                                val priority2 = getTransmitterPriority(t2, isLinearSatellite)
                                priority2.compareTo(priority1) // 降序排列，优先级高的在前
                            }
                            transmitters = sortedTransmitters
                            if (sortedTransmitters.isNotEmpty()) {
                                selectedTransmitter = sortedTransmitters.first()
                                // 设置目标转发器（用于频率显示）
                                trackingController.setTargetTransmitter(sortedTransmitters.first())
                                val satType = if (isLinearSatellite) context.getString(R.string.tracking_linear_satellite) else context.getString(R.string.tracking_transmitter_type_fm)
                                val transType = if (isLinearTransmitter(sortedTransmitters.first())) context.getString(R.string.tracking_linear_satellite) else context.getString(R.string.tracking_transmitter_type_fm)
                                val hasCustomData = sortedTransmitters.any { customMap[it.uuid] != null }
                                LogManager.i("SatelliteTracking", "卫星类型: $satType, 加载了 ${sortedTransmitters.size} 个转发器${if (hasCustomData) "（已应用用户自定义数据）" else ""}，默认选择$transType 转发器: ${sortedTransmitters.first().description}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    LogManager.e("SatelliteTracking", "加载转发器数据失败", e)
                }
            }

            // 每500ms更新一次卫星位置（优化：从100ms增加到500ms，减少CPU和UI重组）
            while (isActive) {
                // 获取当前选中的下行频率（Hz），默认使用435MHz
                val downlinkFreqHz = selectedTransmitter?.downlinkLow?.toDouble() ?: 435e6
                val positions = satelliteTracker.calculateMultiplePositions(
                    satellites = filteredSatellites,
                    targetSatelliteId = targetSatelliteId,
                    downlinkFreqHz = downlinkFreqHz
                )
                satellitePositions = positions
                delay(500) // 优化：降低更新频率以减少CPU占用和UI重组
            }
        } catch (e: Exception) {
            LogManager.e("SatelliteTracking", "初始化失败", e)
            errorMessage = "初始化失败: ${e.message}"
            isLoading = false
        }
    }

    // 转发器选择弹窗状态
    var showTransmitterDialog by remember { mutableStateOf(false) }
    // 转发器编辑弹窗状态
    var showTransmitterEditDialog by remember { mutableStateOf(false) }
    var editingTransmitter by remember { mutableStateOf<Transmitter?>(null) }
    // 布局模式：true=紧凑模式（频率卡+雷达图并排），false=默认模式
    var isCompactLayout by remember { mutableStateOf(false) }
    // 雷达图手动旋转偏移量（度）
    var radarManualOffset by remember { mutableFloatStateOf(0f) }
    // 自定义频率输入弹窗状态
    var showFrequencyInputDialog by remember { mutableStateOf(false) }
    // 频率数据（用于自定义频率输入弹窗）
    var frequencyData by remember { mutableStateOf(trackingController.getCurrentFrequencyData()) }

    // 定期更新频率数据
    LaunchedEffect(Unit) {
        while (isActive) {
            frequencyData = trackingController.getCurrentFrequencyData()
            delay(500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // 操作按钮区域
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 自定义频率输入弹窗
            val currentTransmitter = selectedTransmitter
            if (showFrequencyInputDialog && currentTransmitter != null) {
                FrequencyInputDialog(
                    transmitter = currentTransmitter,
                    currentFrequencyKhz = (frequencyData.satelliteDownlink / 1000.0).toInt(),
                    onDismiss = { showFrequencyInputDialog = false },
                    onConfirm = { uplinkFreqHz, downlinkFreqHz, enableCustomMode ->
                        trackingController.setCustomFrequencies(uplinkFreqHz.toDouble(), downlinkFreqHz.toDouble())
                        if (enableCustomMode) {
                            trackingController.setCustomMode(true)
                        }
                        showFrequencyInputDialog = false
                    }
                )
            }

            // 转发器选择弹窗
            if (showTransmitterDialog && transmitters.isNotEmpty()) {
                AlertDialog(
                    onDismissRequest = { showTransmitterDialog = false },
                    title = {
                        Text(
                            text = stringResource(R.string.tracking_select_transmitter),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(transmitters) { transmitter ->
                                val isSelected = transmitter == selectedTransmitter

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 左侧：转发器信息（可点击选择）
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    // 如果正在跟踪，先停止当前跟踪
                                                    if (trackingController.isTracking.value) {
                                                        LogManager.i("SatelliteTracking", "切换转发器，停止当前跟踪")
                                                        trackingController.stopTracking()
                                                    }
                                                    selectedTransmitter = transmitter
                                                    // 设置目标转发器（用于频率显示）
                                                    trackingController.setTargetTransmitter(transmitter)
                                                    showTransmitterDialog = false
                                                    LogManager.i("SatelliteTracking", "选择转发器: ${transmitter.description}")
                                                }
                                                .padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            // 第一行：描述和类型标签
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = transmitter.description,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                                                )
                                                // 类型标签
                                                val typeLabel = getTransmitterTypeLabel(transmitter, LocalContext.current)
                                                val typeColor = when (typeLabel) {
                                                    stringResource(R.string.tracking_transmitter_type_linear) -> MaterialTheme.colorScheme.primary
                                                    stringResource(R.string.tracking_transmitter_type_fm) -> MaterialTheme.colorScheme.secondary
                                                    "CW" -> MaterialTheme.colorScheme.tertiary
                                                    else -> MaterialTheme.colorScheme.outline
                                                }
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = typeColor.copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        text = typeLabel,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = typeColor,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }

                                            // 第二行：下行频率
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "▼",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(end = 4.dp)
                                                )
                                                Text(
                                                    text = formatFrequencyRange(transmitter.downlinkLow, transmitter.downlinkHigh),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }

                                            // 第三行：上行频率
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "▲",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier.padding(end = 4.dp)
                                                )
                                                Text(
                                                    text = formatFrequencyRange(transmitter.uplinkLow, transmitter.uplinkHigh),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }

                                            // 第四行：模式信息
                                            val rxMode = transmitter.mode
                                            val txMode = transmitter.uplinkMode ?: transmitter.mode
                                            val modeText = if (rxMode == txMode) {
                                                "模式: $rxMode"
                                            } else {
                                                "模式: $rxMode / $txMode"
                                            }
                                            Text(
                                                text = modeText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                            )
                                        }

                                        // 右侧：编辑按钮
                                        IconButton(
                                            onClick = {
                                                editingTransmitter = transmitter
                                                showTransmitterEditDialog = true
                                            },
                                            modifier = Modifier.padding(end = 8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = stringResource(R.string.tracking_edit_transmitter),
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showTransmitterDialog = false }) {
                            Text(stringResource(R.string.tracking_cancel))
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shape = RoundedCornerShape(16.dp)
                )
            }

            // 转发器编辑弹窗
            if (showTransmitterEditDialog && editingTransmitter != null) {
                TransmitterEditDialog(
                    transmitter = editingTransmitter!!,
                    dbHelper = dbHelper,
                    onDismiss = { showTransmitterEditDialog = false },
                    onSave = { updatedTransmitter ->
                        // 更新当前选中的转发器（如果是同一个）
                        if (selectedTransmitter?.uuid == updatedTransmitter.uuid) {
                            selectedTransmitter = updatedTransmitter
                            trackingController.setTargetTransmitter(updatedTransmitter)
                        }
                        // 更新转发器列表
                        transmitters = transmitters.map { 
                            if (it.uuid == updatedTransmitter.uuid) updatedTransmitter else it 
                        }
                        showTransmitterEditDialog = false
                        LogManager.i("SatelliteTracking", "转发器已更新: ${updatedTransmitter.description}")
                    }
                )
            }

            if (isCompactLayout) {
                // 紧凑布局：频率卡+雷达图并排
                CompactLayout(
                    selectedTransmitter = selectedTransmitter,
                    trackingController = trackingController,
                    isTracking = isTracking,
                    transmitters = transmitters,
                    isConnected = isConnected,
                    bluetoothConnectionManager = bluetoothConnectionManager,
                    onShowTransmitterDialog = { showTransmitterDialog = true },
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    targetSatelliteId = targetSatelliteId,
                    satellitePositions = satellitePositions,
                    satelliteTrajectories = satelliteTrajectories,
                    orientation = orientation,
                    onToggleLayout = { isCompactLayout = !isCompactLayout },
                    radarManualOffset = radarManualOffset,
                    onRadarOffsetChange = { newOffset ->
                        radarManualOffset = newOffset
                    },
                    vfoAFrequency = vfoAFrequency,
                    vfoAMode = vfoAMode,
                    grid4 = grid4,
                    satelliteName = title
                )
            } else {
                // 默认布局：频率卡在上，雷达图在下
                DefaultLayout(
                    selectedTransmitter = selectedTransmitter,
                    trackingController = trackingController,
                    isTracking = isTracking,
                    transmitters = transmitters,
                    isConnected = isConnected,
                    bluetoothConnectionManager = bluetoothConnectionManager,
                    onShowTransmitterDialog = { showTransmitterDialog = true },
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    targetSatelliteId = targetSatelliteId,
                    satellitePositions = satellitePositions,
                    satelliteTrajectories = satelliteTrajectories,
                    orientation = orientation,
                    onToggleLayout = { isCompactLayout = !isCompactLayout },
                    radarManualOffset = radarManualOffset,
                    onRadarOffsetChange = { newOffset ->
                        radarManualOffset = newOffset
                    },
                    onDoubleTap = {
                        val transmitter = selectedTransmitter
                        val isLinearTransmitter = transmitter?.downlinkHigh != null &&
                                transmitter.downlinkHigh != transmitter.downlinkLow
                        if (isLinearTransmitter) {
                            LogManager.i("SatelliteTracking", "双击频率显示卡，打开自定义频率输入")
                            showFrequencyInputDialog = true
                        } else {
                            LogManager.w("SatelliteTracking", "双击频率显示卡无效：非线性卫星")
                        }
                    }
                )
            }
        }
    }
}

/**
 * 获取转发器的优先级
 * 根据转发器描述中的关键词计算优先级
 * 优先级：FM/Linear/U/V/V/U > 其他
 */
private fun getTransmitterPriority(transmitter: Transmitter, isLinearSatellite: Boolean): Int {
    val description = transmitter.description.uppercase()
    var priority = 0
    
    // 判断转发器类型
    val isLinearTransmitter = transmitter.downlinkHigh != null && transmitter.downlinkHigh != transmitter.downlinkLow
    
    // 根据卫星类型优先选择匹配的转发器类型（最高优先级）
    if (isLinearSatellite && isLinearTransmitter) {
        priority += 100  // 线性卫星优先选择线性转发器
    } else if (!isLinearSatellite && !isLinearTransmitter) {
        priority += 100  // FM卫星优先选择FM转发器
    }
    
    // 高优先级关键词
    if (description.contains("FM")) priority += 10
    if (description.contains("LINEAR")) priority += 10
    if (description.contains("U/V")) priority += 8
    if (description.contains("V/U")) priority += 8
    if (description.contains("UHF")) priority += 5
    if (description.contains("VHF")) priority += 5
    
    return priority
}

/**
 * 判断是否为线性转发器
 */
private fun isLinearTransmitter(transmitter: Transmitter): Boolean {
    return transmitter.downlinkHigh != null && transmitter.downlinkHigh != transmitter.downlinkLow
}

/**
 * 获取转发器类型标签
 */
private fun getTransmitterTypeLabel(transmitter: Transmitter, context: Context? = null): String {
    return when {
        transmitter.downlinkHigh != null && transmitter.downlinkHigh != transmitter.downlinkLow &&
        transmitter.uplinkHigh != null && transmitter.uplinkHigh != transmitter.uplinkLow -> context?.getString(R.string.tracking_transmitter_type_linear) ?: "Linear"
        transmitter.mode.contains("FM", ignoreCase = true) -> context?.getString(R.string.tracking_transmitter_type_fm) ?: "FM"
        transmitter.mode.contains("CW", ignoreCase = true) -> "CW"
        transmitter.mode.contains("SSB", ignoreCase = true) ||
        transmitter.mode.contains("USB", ignoreCase = true) ||
        transmitter.mode.contains("LSB", ignoreCase = true) -> "SSB"
        else -> transmitter.mode
    }
}

/**
 * 格式化频率范围显示
 * 使用FrequencyFormatter工具类
 */
private fun formatFrequencyRange(lowHz: Long?, highHz: Long?): String = 
    FrequencyFormatter.formatRange(lowHz, highHz)

/**
 * 频率输入弹窗
 * 用于线性卫星自定义上行和下行频率
 * 支持四种输入情况和预设管理
 */
@Composable
private fun FrequencyInputDialog(
    transmitter: Transmitter,
    currentFrequencyKhz: Int,
    onDismiss: () -> Unit,
    onConfirm: (uplinkFreqHz: Long, downlinkFreqHz: Long, enableCustomMode: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dbHelper = remember { DatabaseHelper.getInstance(context) }
    
    // 获取卫星NORAD ID
    val noradId = transmitter.noradCatId.toString()
    
    // 将Hz转换为MHz，保留6位小数
    val uplinkLowMhz = (transmitter.uplinkLow?.toDouble() ?: 0.0) / 1_000_000.0
    val uplinkHighMhz = (transmitter.uplinkHigh?.toDouble() ?: 0.0) / 1_000_000.0
    val downlinkLowMhz = (transmitter.downlinkLow?.toDouble() ?: 0.0) / 1_000_000.0
    val downlinkHighMhz = (transmitter.downlinkHigh?.toDouble() ?: 0.0) / 1_000_000.0

    // 输入状态（可以为空）
    var uplinkInput by remember { mutableStateOf("") }
    var downlinkInput by remember { mutableStateOf("") }
    
    // 错误提示
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // 预设列表
    var presets by remember { mutableStateOf<List<FrequencyPresetEntity>>(emptyList()) }
    
    // 加载预设
    LaunchedEffect(Unit) {
        dbHelper.getFrequencyPresetsByNoradId(noradId).collect { presetList ->
            presets = presetList
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 标题栏 - 使用卡片样式
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.tracking_custom_frequency),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        // 关闭按钮
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.callsign_library_close),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 内容区域
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 频率范围卡片
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.tracking_frequency),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "↑ ${String.format(Locale.US, "%.3f", uplinkLowMhz)}-${String.format(Locale.US, "%.3f", uplinkHighMhz)} MHz",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = "↓ ${String.format(Locale.US, "%.3f", downlinkLowMhz)}-${String.format(Locale.US, "%.3f", downlinkHighMhz)} MHz",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // 频率输入框（上行 - 下行）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 上行频率输入
                        OutlinedTextField(
                            value = uplinkInput,
                            onValueChange = { 
                                uplinkInput = it.filter { char -> char.isDigit() || char == '.' }
                                errorMessage = null
                            },
                            label = { 
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("↑ ", color = MaterialTheme.colorScheme.secondary)
                                    Text("上行 MHz")
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.secondary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                        )
                        
                        // 下行频率输入
                        OutlinedTextField(
                            value = downlinkInput,
                            onValueChange = { 
                                downlinkInput = it.filter { char -> char.isDigit() || char == '.' }
                                errorMessage = null
                            },
                            label = { 
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("↓ ", color = MaterialTheme.colorScheme.primary)
                                    Text("下行 MHz")
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                        )
                    }

                    // 错误信息
                    if (errorMessage != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        ) {
                            Text(
                                text = errorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 按钮行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    // 添加预设按钮（最多5组）
                    if (presets.size < 5) {
                        OutlinedButton(
                            onClick = {
                                // 保存预设逻辑
                                val uplinkMhz = uplinkInput.toDoubleOrNull()
                                val downlinkMhz = downlinkInput.toDoubleOrNull()
                                
                                // 判断输入情况
                                when {
                                    // 情况4：全空
                                    uplinkMhz == null && downlinkMhz == null -> {
                                        errorMessage = "请输入频率后再保存预设"
                                        return@OutlinedButton
                                    }
                                    // 情况1：仅输入下行
                                    uplinkMhz == null && downlinkMhz != null -> {
                                        // 根据下行计算上行（使用Loop反推）
                                        val loopMhz = ((transmitter.uplinkLow!! + transmitter.uplinkHigh!!) / 2_000_000.0) +
                                                ((transmitter.downlinkLow!! + transmitter.downlinkHigh!!) / 2_000_000.0)
                                        val calculatedUplinkMhz = loopMhz - downlinkMhz
                                        savePreset(scope, dbHelper, noradId, calculatedUplinkMhz, downlinkMhz) { presets = it }
                                    }
                                    // 情况2：仅输入上行
                                    uplinkMhz != null && downlinkMhz == null -> {
                                        // 根据上行计算下行
                                        val loopMhz = ((transmitter.uplinkLow!! + transmitter.uplinkHigh!!) / 2_000_000.0) +
                                                ((transmitter.downlinkLow!! + transmitter.downlinkHigh!!) / 2_000_000.0)
                                        val calculatedDownlinkMhz = loopMhz - uplinkMhz
                                        savePreset(scope, dbHelper, noradId, uplinkMhz, calculatedDownlinkMhz) { presets = it }
                                    }
                                    // 情况3：都输入了
                                    uplinkMhz != null && downlinkMhz != null -> {
                                        savePreset(scope, dbHelper, noradId, uplinkMhz, downlinkMhz) { presets = it }
                                    }
                                    // 其他情况（理论上不会发生）
                                    else -> {
                                        errorMessage = "请输入有效的频率"
                                        return@OutlinedButton
                                    }
                                }
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("保存预设")
                        }
                    }
                    
                    Button(
                        onClick = {
                            val uplinkMhz = uplinkInput.toDoubleOrNull()
                            val downlinkMhz = downlinkInput.toDoubleOrNull()

                            // 判断输入情况
                            when {
                                // 情况4：全空
                                uplinkMhz == null && downlinkMhz == null -> {
                                    errorMessage = "请输入频率"
                                }
                                // 情况1：仅输入下行
                                uplinkMhz == null && downlinkMhz != null -> {
                                    if (downlinkMhz in downlinkLowMhz..downlinkHighMhz) {
                                        // 根据下行计算上行（使用Loop反推）
                                        val loopMhz = ((transmitter.uplinkLow!! + transmitter.uplinkHigh!!) / 2_000_000.0) +
                                                ((transmitter.downlinkLow!! + transmitter.downlinkHigh!!) / 2_000_000.0)
                                        val calculatedUplinkMhz = loopMhz - downlinkMhz
                                        val uplinkHz = (calculatedUplinkMhz * 1_000_000).toLong()
                                        val downlinkHz = (downlinkMhz * 1_000_000).toLong()
                                        // 仅输入一个频率，不启用自定义模式（使用loop方法）
                                        onConfirm(uplinkHz, downlinkHz, false)
                                        onDismiss()
                                    } else {
                                        errorMessage = "下行频率超出范围"
                                    }
                                }
                                // 情况2：仅输入上行
                                uplinkMhz != null && downlinkMhz == null -> {
                                    if (uplinkMhz in uplinkLowMhz..uplinkHighMhz) {
                                        // 根据上行计算下行
                                        val loopMhz = ((transmitter.uplinkLow!! + transmitter.uplinkHigh!!) / 2_000_000.0) +
                                                ((transmitter.downlinkLow!! + transmitter.downlinkHigh!!) / 2_000_000.0)
                                        val calculatedDownlinkMhz = loopMhz - uplinkMhz
                                        val uplinkHz = (uplinkMhz * 1_000_000).toLong()
                                        val downlinkHz = (calculatedDownlinkMhz * 1_000_000).toLong()
                                        // 仅输入一个频率，不启用自定义模式（使用loop方法）
                                        onConfirm(uplinkHz, downlinkHz, false)
                                        onDismiss()
                                    } else {
                                        errorMessage = "上行频率超出范围"
                                    }
                                }
                                // 情况3：都输入了
                                uplinkMhz != null && downlinkMhz != null -> {
                                    if (uplinkMhz !in uplinkLowMhz..uplinkHighMhz) {
                                        errorMessage = "上行频率超出范围"
                                    } else if (downlinkMhz !in downlinkLowMhz..downlinkHighMhz) {
                                        errorMessage = "下行频率超出范围"
                                    } else {
                                        val uplinkHz = (uplinkMhz * 1_000_000).toLong()
                                        val downlinkHz = (downlinkMhz * 1_000_000).toLong()
                                        // 上下行都输入了，启用自定义模式
                                        onConfirm(uplinkHz, downlinkHz, true)
                                        onDismiss()
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("确定")
                    }
                }
                
                // 预设列表
                if (presets.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.tracking_saved_presets, presets.size),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 预设列表UI（支持置顶）
                    Text(
                        text = stringResource(R.string.tracking_pin_to_top_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(presets.size, key = { presets[it].id }) { index ->
                            val preset = presets[index]
                            val isFirst = index == 0

                            PresetItem(
                                preset = preset,
                                isFirst = isFirst,
                                onMoveToTop = if (!isFirst) {
                                    {
                                        val newList = presets.toMutableList()
                                        val item = newList.removeAt(index)
                                        newList.add(0, item)
                                        presets = newList
                                        // 保存排序到数据库
                                        scope.launch {
                                            dbHelper.updateFrequencyPresetsSortOrder(presets)
                                            LogManager.i("FrequencyInputDialog", "预设已置顶")
                                        }
                                    }
                                } else null,
                                onDelete = {
                                    scope.launch {
                                        dbHelper.deleteFrequencyPreset(preset.id)
                                        // 刷新列表
                                        dbHelper.getFrequencyPresetsByNoradId(noradId).collect { presetList ->
                                            presets = presetList
                                        }
                                    }
                                },
                                onApply = {
                                    uplinkInput = String.format(Locale.US, "%.6f", preset.getUplinkMhz())
                                    downlinkInput = String.format(Locale.US, "%.6f", preset.getDownlinkMhz())
                                },
                                onNameEdit = { newName ->
                                    scope.launch {
                                        val updatedPreset = preset.copy(name = newName)
                                        dbHelper.updateFrequencyPreset(updatedPreset)
                                        // 刷新列表
                                        dbHelper.getFrequencyPresetsByNoradId(noradId).collect { presetList ->
                                            presets = presetList
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 保存预设
 */
private fun savePreset(
    scope: kotlinx.coroutines.CoroutineScope,
    dbHelper: DatabaseHelper,
    noradId: String,
    uplinkMhz: Double,
    downlinkMhz: Double,
    onPresetsUpdated: (List<FrequencyPresetEntity>) -> Unit
) {
    scope.launch {
        val presetCount = dbHelper.getFrequencyPresetCountByNoradId(noradId)
        if (presetCount >= 5) {
            return@launch
        }
        
        val preset = FrequencyPresetEntity.fromMhz(
            noradId = noradId,
            name = "预设${presetCount + 1}",
            uplinkMhz = uplinkMhz,
            downlinkMhz = downlinkMhz
        )
        dbHelper.insertFrequencyPreset(preset)
        
        // 刷新列表
        dbHelper.getFrequencyPresetsByNoradId(noradId).collect { presetList ->
            onPresetsUpdated(presetList)
        }
    }
}

/**
 * 预设项UI组件（支持置顶）
 * 显示格式：{置顶}|{名称}|上行|下行|删除|应用
 */
@Composable
private fun PresetItem(
    preset: FrequencyPresetEntity,
    isFirst: Boolean = false,
    onMoveToTop: (() -> Unit)? = null,
    onDelete: () -> Unit,
    onApply: () -> Unit,
    onNameEdit: (String) -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(preset.name) }

    // 名称编辑弹窗
    if (showEditDialog) {
        Dialog(
            onDismissRequest = { showEditDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .wrapContentHeight(),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tracking_edit_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("预设名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(onClick = { showEditDialog = false }) {
                            Text("取消")
                        }
                        Button(
                            onClick = {
                                onNameEdit(editName)
                                showEditDialog = false
                            }
                        ) {
                            Text("确定")
                        }
                    }
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (isFirst) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            width = if (isFirst) 2.dp else 1.dp,
            color = if (isFirst) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 置顶按钮
            if (onMoveToTop != null) {
                IconButton(
                    onClick = onMoveToTop,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "置顶",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else {
                // 第一个位置显示"默认"标签
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text = stringResource(R.string.tracking_default),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // 名称（可点击编辑）
            Surface(
                modifier = Modifier.weight(1.2f),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            ) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clickable { 
                            editName = preset.name
                            showEditDialog = true 
                        }
                )
            }
            
            // 频率信息
            Column(
                modifier = Modifier.weight(1.3f),
                horizontalAlignment = Alignment.End
            ) {
                // 上行频率
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "↑",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = String.format(Locale.US, "%.3f", preset.getUplinkMhz()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 下行频率
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "↓",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = String.format(Locale.US, "%.3f", preset.getDownlinkMhz()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // 操作按钮
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 删除按钮
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                
                // 应用按钮
                Button(
                    onClick = onApply,
                    modifier = Modifier.height(32.dp),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tracking_apply),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

/**
 * 跟踪控制面板
 */
@Composable
private fun TrackingControlPanel(
    trackingController: SatelliteTrackingController,
    selectedTransmitter: Transmitter,
    isConnected: Boolean,
    bluetoothConnectionManager: BluetoothConnectionManager,
    onShowTransmitterDialog: () -> Unit,
    draggedCallsign: String?,
    onDragCallsign: (String?) -> Unit,
    isCwModuleExpanded: Boolean,
    onCwModuleExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 跟踪状态
    val isTracking by trackingController.isTracking.collectAsState()
    val trackingState by trackingController.trackingState.collectAsState()

    // 多普勒数据
    val dopplerShift by DopplerDataCache.dopplerShift.collectAsState()

    // 模式切换状态：0=默认模式, 1=CW全模式, 2=USB/CW分离模式
    val activeModeType by trackingController.activeModeType.collectAsState()

    // 判断是否为线性卫星
    val isLinearTransmitter = selectedTransmitter.downlinkHigh != null &&
            selectedTransmitter.downlinkHigh != selectedTransmitter.downlinkLow

    // 自定义模式状态
    val isCustomMode by trackingController.isCustomMode.collectAsState()
    // CW预设列表（6个按钮）
    var cwPresets by remember { mutableStateOf(CwPresetEntity.createDefaults()) }
    // CW预设是否已加载
    var isCwPresetsLoaded by remember { mutableStateOf(false) }
    // CW输入框内容
    var cwInputText by remember { mutableStateOf("") }
    // 编辑预设弹窗状态
    var editingPresetIndex by remember { mutableStateOf<Int?>(null) }
    var editingPresetName by remember { mutableStateOf("") }
    var editingPresetMessage by remember { mutableStateOf("") }
    // CW历史输入栏（最多5个呼号，使用固定大小List，null表示空位）
    var cwHistory by remember { mutableStateOf<List<String?>>(List(MAX_HISTORY_ITEMS) { null }) }

    // 加载CW预设的函数（在点击CW键入按钮时调用）
    val loadCwPresets = suspend {
        if (!isCwPresetsLoaded) {
            val dbHelper = DatabaseHelper.getInstance(context)
            try {
                val savedPresets = dbHelper.getAllCwPresets().first()
                if (savedPresets.isNotEmpty()) {
                    // 合并数据库预设和默认值，确保6个按钮都有值
                    val mergedPresets = CwPresetEntity.createDefaults().toMutableList()
                    savedPresets.forEach { saved ->
                        if (saved.presetIndex in 0..5) {
                            mergedPresets[saved.presetIndex] = saved
                        }
                    }
                    cwPresets = mergedPresets
                    LogManager.i("SatelliteTracking", "从数据库加载CW预设: ${savedPresets.size}条")
                } else {
                    // 数据库为空，使用默认值并保存到数据库
                    cwPresets = CwPresetEntity.createDefaults()
                    dbHelper.insertCwPresets(cwPresets)
                    LogManager.i("SatelliteTracking", "初始化CW预设到数据库")
                }
                isCwPresetsLoaded = true
            } catch (e: Exception) {
                LogManager.e("SatelliteTracking", "加载CW预设失败", e)
                cwPresets = CwPresetEntity.createDefaults()
            }
        }
    }

    Column(
        modifier = modifier
    ) {
        // 获取屏幕尺寸用于适配
        val configuration = LocalConfiguration.current
        val screenWidth = configuration.screenWidthDp.dp
        val isSmallScreen = screenWidth < 360.dp
        val isLargeScreen = screenWidth >= 420.dp
        
        // 主控制按钮行 - 使用BoxWithConstraints实现自适应方形按钮
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            tonalElevation = 2.dp
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(if (isSmallScreen) 8.dp else 12.dp)
            ) {
                // 计算按钮大小：根据可用宽度和按钮数量(6个)计算，保持方形
                val buttonCount = 6
                val spacing = if (isSmallScreen) 4.dp else 8.dp
                val totalSpacing = spacing * (buttonCount - 1)
                val availableWidth = maxWidth - totalSpacing
                // 根据屏幕尺寸调整按钮最大尺寸
                val maxButtonSize = when {
                    isSmallScreen -> 56.dp
                    isLargeScreen -> 80.dp
                    else -> 72.dp
                }
                val buttonSize = (availableWidth / buttonCount).coerceIn(48.dp, maxButtonSize)

                // 根据按钮尺寸选择合适的文字样式
                val buttonTextStyle = when {
                    buttonSize <= 52.dp -> MaterialTheme.typography.labelSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit.Unspecified)
                    buttonSize >= 76.dp -> MaterialTheme.typography.labelMedium
                    else -> MaterialTheme.typography.labelSmall
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally)
                ) {
                    // 1. 自定义模式按钮（方形）- 仅线性卫星显示
                    if (isLinearTransmitter) {
                        Surface(
                            modifier = Modifier
                                .size(buttonSize)
                                .clickable {
                                    trackingController.toggleCustomMode()
                                },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            color = if (isCustomMode) {
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                            }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.tracking_custom),
                                        style = buttonTextStyle,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCustomMode) {
                                            MaterialTheme.colorScheme.onTertiary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    Text(
                                        text = if (isCustomMode) stringResource(R.string.common_on) else stringResource(R.string.common_off),
                                        style = buttonTextStyle,
                                        color = if (isCustomMode) {
                                            MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.8f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 2. 转发器选择按钮（方形）
                    Surface(
                        modifier = Modifier
                            .size(buttonSize)
                            .clickable {
                                onShowTransmitterDialog()
                            },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.tracking_transmitter_label),
                                    style = buttonTextStyle,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiary
                                )
                                Text(
                                    text = stringResource(R.string.tracking_select_label),
                                    style = buttonTextStyle,
                                    color = MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    // 3. USB/CW分离模式按钮（方形）- 仅线性卫星显示
                    if (isLinearTransmitter) {
                        // USB/CW按钮只有在开始跟踪且CW键入激活时才能激活
                        val isUsbCwEnabled = isTracking && isCwModuleExpanded
                        Surface(
                            modifier = Modifier
                                .size(buttonSize)
                                .clickable(enabled = isUsbCwEnabled) {
                                    if (!isTracking) {
                                        LogManager.w("SatelliteTracking", "未开始跟踪，无法使用USB/CW模式")
                                        return@clickable
                                    }
                                    if (!isCwModuleExpanded) {
                                        LogManager.w("SatelliteTracking", "CW键入未激活，无法使用USB/CW模式")
                                        return@clickable
                                    }
                                    scope.launch {
                                        // 切换到USB/CW分离模式（如果当前不是）
                                        if (activeModeType != 2) {
                                            trackingController.setModeType(2)
                                            LogManager.i("SatelliteTracking", "切换到USB/CW分离模式")
                                        } else {
                                            // 如果已经是USB/CW模式，则切换回CW/CW模式（不是默认模式）
                                            trackingController.setModeType(1)
                                            LogManager.i("SatelliteTracking", "USB/CW模式关闭，恢复CW/CW模式")
                                        }
                                        trackingController.sendModeCommand()
                                    }
                                },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            color = when {
                                activeModeType == 2 -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f)
                                !isUsbCwEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
                            }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "USB",
                                        style = buttonTextStyle,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            activeModeType == 2 -> MaterialTheme.colorScheme.onTertiary
                                            !isUsbCwEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                            else -> MaterialTheme.colorScheme.onSecondary
                                        }
                                    )
                                    Text(
                                        text = "/CW",
                                        style = buttonTextStyle,
                                        color = when {
                                            activeModeType == 2 -> MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.8f)
                                            !isUsbCwEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                            else -> MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.8f)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 4. Data模式切换按钮（方形）- 仅线性卫星显示
                    if (isLinearTransmitter) {
                        // Data模式只有在开始跟踪后才能激活，且CW键入未激活时
                        val isDataEnabled = isTracking && !isCwModuleExpanded
                        Surface(
                            modifier = Modifier
                                .size(buttonSize)
                                .clickable(enabled = isDataEnabled) {
                                    if (!isTracking) {
                                        LogManager.w("SatelliteTracking", "未开始跟踪，无法使用Data模式")
                                        return@clickable
                                    }
                                    if (isCwModuleExpanded) {
                                        LogManager.w("SatelliteTracking", "CW键入已激活，无法使用Data模式")
                                        return@clickable
                                    }
                                    scope.launch {
                                        // 切换到Data模式（如果当前不是）
                                        if (activeModeType != 3) {
                                            trackingController.setModeType(3)
                                            LogManager.i("SatelliteTracking", "切换到Data模式")
                                        } else {
                                            // 如果已经是Data模式，则切换回默认
                                            trackingController.setModeType(0)
                                            LogManager.i("SatelliteTracking", "Data模式关闭，恢复转发器默认模式")
                                        }
                                        trackingController.sendModeCommand()
                                    }
                                },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            color = when {
                                activeModeType == 3 -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f)
                                !isDataEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
                            }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "Data",
                                        style = buttonTextStyle,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            activeModeType == 3 -> MaterialTheme.colorScheme.onTertiary
                                            !isDataEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                            else -> MaterialTheme.colorScheme.onSecondary
                                        }
                                    )
                                    Text(
                                        text = stringResource(R.string.tracking_mode_label),
                                        style = buttonTextStyle,
                                        color = when {
                                            activeModeType == 3 -> MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.8f)
                                            !isDataEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                            else -> MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.8f)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 5. CW键入按钮（方形）- 仅线性卫星显示
                    if (isLinearTransmitter) {
                        Surface(
                            modifier = Modifier
                                .size(buttonSize)
                                .clickable {
                                    val newExpanded = !isCwModuleExpanded
                                    onCwModuleExpandedChange(newExpanded)
                                    LogManager.i("SatelliteTracking", "切换CW键入状态: $newExpanded")

                                    scope.launch {
                                        if (newExpanded) {
                                            // 加载CW预设（仅在第一次展开时）
                                            loadCwPresets()
                                            // 只有在开始跟踪后才切换模式为CW/CW
                                            if (isTracking) {
                                                trackingController.setModeType(1)
                                                LogManager.i("SatelliteTracking", "CW键入打开，切换到CW/CW模式")
                                                trackingController.sendModeCommand()
                                            }
                                        } else {
                                            // 收起时切换回转发器默认模式（只有在开始跟踪后）
                                            if (isTracking) {
                                                trackingController.setModeType(0)
                                                LogManager.i("SatelliteTracking", "CW键入关闭，恢复转发器默认模式")
                                                trackingController.sendModeCommand()
                                            }
                                        }
                                    }
                                },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            color = when {
                                isCwModuleExpanded -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f)
                                else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
                            }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "CW",
                                        style = buttonTextStyle,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            isCwModuleExpanded -> MaterialTheme.colorScheme.onTertiary
                                            else -> MaterialTheme.colorScheme.onSecondary
                                        }
                                    )
                                    Text(
                                        text = stringResource(R.string.tracking_key_in),
                                        style = buttonTextStyle,
                                        color = when {
                                            isCwModuleExpanded -> MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.8f)
                                            else -> MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.8f)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 6. 开始/停止跟踪按钮（方形）
                    Surface(
                        modifier = Modifier
                            .size(buttonSize)
                            .clickable(
                                enabled = isConnected || isTracking,
                                onClick = {
                                    LogManager.i("SatelliteTracking", "按钮点击 - isTracking: $isTracking, isConnected: $isConnected")
                                    if (isTracking) {
                                        LogManager.i("SatelliteTracking", "点击停止跟踪按钮")
                                        scope.launch {
                                            // 停止跟踪前，关闭其他按钮并恢复转发器默认模式
                                            if (isCwModuleExpanded) {
                                                onCwModuleExpandedChange(false)
                                                LogManager.i("SatelliteTracking", "停止跟踪，关闭CW键入")
                                            }
                                            // 恢复转发器默认模式
                                            trackingController.setModeType(0)
                                            trackingController.sendModeCommand()
                                            LogManager.i("SatelliteTracking", "停止跟踪，恢复转发器默认模式")
                                            trackingController.stopTracking()
                                        }
                                    } else {
                                        if (isConnected) {
                                            LogManager.i("SatelliteTracking", "点击开始跟踪按钮")
                                            scope.launch {
                                                // 开始跟踪时，初始化电台模式为转发器上下行模式
                                                trackingController.setModeType(0)
                                                LogManager.i("SatelliteTracking", "开始跟踪，初始化为转发器上下行模式")
                                                trackingController.startTracking(selectedTransmitter)
                                            }
                                        }
                                    }
                                }
                            ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        color = if (isTracking) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                        } else if (isConnected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isTracking) Icons.Default.Close else Icons.Default.PlayArrow,
                                contentDescription = if (isTracking) "停止跟踪" else "开始跟踪",
                                modifier = Modifier.size(buttonSize * 0.55f),
                                tint = if (isTracking || isConnected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                }
                            )
                        }
                    }
                }
            }
        }

        // CW模块展开卡片
        if (isCwModuleExpanded && isLinearTransmitter) {
            CwModuleCard(
                presets = cwPresets,
                inputText = cwInputText,
                onInputTextChange = { cwInputText = it },
                onPresetClick = { index ->
                    val preset = cwPresets.getOrNull(index)
                    if (preset != null && preset.message.isNotEmpty()) {
                        scope.launch {
                            val civController = bluetoothConnectionManager.civController.value
                            if (civController != null) {
                                // 先执行强制停止命令
                                LogManager.i("SatelliteTracking", "执行强制停止命令")
                                civController.stopCwTransmission()
                                
                                // 延迟0.5秒
                                delay(500)
                                
                                // 处理 <cl> 替换为呼号
                                val dbHelper = DatabaseHelper.getInstance(context)
                                val callsign = dbHelper.getCallsign()
                                val message = if (callsign != null && preset.message.contains("<cl>")) {
                                    preset.message.replace("<cl>", callsign)
                                } else {
                                    preset.message
                                }
                                
                                val success = civController.sendCwMessage(message)
                                if (success) {
                                    LogManager.i("SatelliteTracking", "CW预设发送成功: ${preset.name} - $message")
                                } else {
                                    LogManager.e("SatelliteTracking", "CW预设发送失败")
                                }
                            } else {
                                LogManager.w("SatelliteTracking", "CIV控制器未初始化")
                            }
                        }
                    }
                },
                onPresetLongClick = { index ->
                    val preset = cwPresets.getOrNull(index)
                    if (preset != null) {
                        editingPresetIndex = index
                        editingPresetName = preset.name
                        editingPresetMessage = preset.message
                    }
                },
                onSendClick = {
                    if (cwInputText.isNotEmpty()) {
                        scope.launch {
                            val civController = bluetoothConnectionManager.civController.value
                            if (civController != null) {
                                // 先发送强制停止命令
                                LogManager.i("SatelliteTracking", "【CW发射】先发送强制停止命令")
                                civController.stopCwTransmission()
                                
                                // 延迟300ms
                                delay(300)
                                
                                // 处理 <cl> 替换为呼号
                                val dbHelper = DatabaseHelper.getInstance(context)
                                val callsign = dbHelper.getCallsign()
                                val message = if (callsign != null && cwInputText.contains("<cl>")) {
                                    cwInputText.replace("<cl>", callsign)
                                } else {
                                    cwInputText
                                }
                                
                                LogManager.i("SatelliteTracking", "【CW发射】延迟300ms后发送消息: $message")
                                val success = civController.sendCwMessage(message)
                                if (success) {
                                    LogManager.i("SatelliteTracking", "CW消息发送成功: $message")
                                    
                                    // 管理历史输入栏
                                    val callsignToAdd = if (callsign != null && cwInputText.contains("<cl>")) {
                                        callsign
                                    } else {
                                        cwInputText
                                    }
                                    
                                    // 检查历史中是否已存在（过滤null后检查）
                                    if (!cwHistory.filterNotNull().contains(callsignToAdd)) {
                                        // 找到第一个空位（null）
                                        val emptyIndex = cwHistory.indexOfFirst { it == null }
                                        val newHistory = if (emptyIndex != -1) {
                                            // 有空位，填入第一个空位
                                            cwHistory.toMutableList().apply {
                                                set(emptyIndex, callsignToAdd)
                                            }
                                        } else {
                                            // 没有空位，移除第一个并添加到末尾（循环移位）
                                            cwHistory.drop(1) + callsignToAdd
                                        }
                                        cwHistory = newHistory
                                    }
                                    
                                    cwInputText = "" // 发送后清空输入框
                                } else {
                                    LogManager.e("SatelliteTracking", "CW消息发送失败")
                                }
                            } else {
                                LogManager.w("SatelliteTracking", "CIV控制器未初始化")
                            }
                        }
                    }
                },
                onStopClick = {
                    cwInputText = "" // 清空输入框
                    scope.launch {
                        val civController = bluetoothConnectionManager.civController.value
                        if (civController != null) {
                            LogManager.i("SatelliteTracking", "执行停止CW发射命令")
                            civController.stopCwTransmission()
                        } else {
                            LogManager.w("SatelliteTracking", "CIV控制器未初始化")
                        }
                    }
                },
                cwHistory = cwHistory,
                onHistoryClick = { historyItem ->
                    cwInputText = historyItem
                    LogManager.i("SatelliteTracking", "从历史中选择: $historyItem")
                },
                onHistoryLongClick = { historyItem ->
                    // 长按删除历史记录，将该位置设为null（空位）
                    val index = cwHistory.indexOf(historyItem)
                    if (index != -1) {
                        cwHistory = cwHistory.toMutableList().apply {
                            set(index, null)
                        }
                        LogManager.i("SatelliteTracking", "长按删除历史记录: $historyItem，位置: $index")
                    }
                },
                maxHistoryItems = MAX_HISTORY_ITEMS,
                onDragCallsign = onDragCallsign,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 编辑预设弹窗
        if (editingPresetIndex != null) {
            EditPresetDialog(
                name = editingPresetName,
                message = editingPresetMessage,
                onNameChange = { editingPresetName = it },
                onMessageChange = { editingPresetMessage = it },
                onDismiss = { editingPresetIndex = null },
                onSave = {
                    val index = editingPresetIndex
                    if (index != null) {
                        // 更新预设列表
                        val updatedPresets = cwPresets.toMutableList()
                        val oldPreset = updatedPresets[index]
                        val newPreset = oldPreset.copy(
                            name = editingPresetName,
                            message = editingPresetMessage
                        )
                        updatedPresets[index] = newPreset
                        cwPresets = updatedPresets
                        // 保存到数据库
                        scope.launch {
                            try {
                                val dbHelper = DatabaseHelper.getInstance(context)
                                dbHelper.insertCwPreset(newPreset)
                                LogManager.i("SatelliteTracking", "保存CW预设到数据库 $index: ${editingPresetName}")
                            } catch (e: Exception) {
                                LogManager.e("SatelliteTracking", "保存CW预设失败", e)
                            }
                        }
                    }
                    editingPresetIndex = null
                }
            )
        }
    }
}

/**
 * CW模块卡片
 * 包含6个预设按钮和输入框
 * 布局：第一行6个预设按钮，第二行输入框+取消+发射
 * 支持从历史呼号拖拽到呼号记录器
 */
@Composable
private fun CwModuleCard(
        presets: List<CwPresetEntity>,
        inputText: String,
        onInputTextChange: (String) -> Unit,
        onPresetClick: (Int) -> Unit,
        onPresetLongClick: (Int) -> Unit,
        onSendClick: () -> Unit,
        onStopClick: () -> Unit,
        cwHistory: List<String?>,
        onHistoryClick: (String) -> Unit,
        onHistoryLongClick: (String) -> Unit,
        @Suppress("UNUSED_PARAMETER") maxHistoryItems: Int,
        onDragCallsign: (String?) -> Unit,  // 拖拽呼号回调
        modifier: Modifier = Modifier
    ) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // 本地拖拽状态
    var isDragging by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.padding(top = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 6个预设按钮（单行排列）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (0..5).forEach { index ->
                    val preset = presets.getOrNull(index)
                    if (preset != null) {
                        PresetButton(
                            name = preset.name,
                            message = preset.message,
                            onClick = { onPresetClick(index) },
                            onLongClick = { onPresetLongClick(index) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            HorizontalDivider()

            // 输入框、取消按钮和发射按钮
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 输入框、取消按钮和发射按钮并排
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 输入框
                        BasicTextField(
                            value = inputText,
                            onValueChange = { newValue ->
                                if (newValue.length <= 30) {
                                    onInputTextChange(newValue)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .background(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (inputText.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.tracking_input_cw_hint),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )

                        // 停止按钮
                        OutlinedButton(
                            onClick = {
                                focusManager.clearFocus()
                                onStopClick()
                            },
                            modifier = Modifier.width(80.dp).height(40.dp)
                        ) {
                            Text("停止")
                        }

                        // 发射按钮
                        Button(
                            onClick = onSendClick,
                            enabled = inputText.isNotEmpty(),
                            modifier = Modifier.width(80.dp).height(40.dp)
                        ) {
                            Text("发射")
                        }
                    }

                    // 历史输入栏（支持拖拽）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.tracking_history_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // 使用for循环并为每个item创建独立的Composable，避免lambda捕获问题
                            // 使用key确保item变化时重新创建组件
                            cwHistory.forEachIndexed { index, item ->
                                key("history_${index}_${item ?: "null"}") {
                                    HistoryItemWidget(
                                        item = item,
                                        index = index,
                                        onDragStart = { draggedItem ->
                                            isDragging = true
                                            onDragCallsign(draggedItem)
                                        },
                                        onDragEnd = {
                                            isDragging = false
                                            onDragCallsign(null)
                                        },
                                        onClick = onHistoryClick,
                                        onLongClick = onHistoryLongClick,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }
}

/**
 * 可拖拽的呼号项
 */
@Composable
private fun DraggableCallsignItem(
    callsign: String?,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .height(32.dp)
            .pointerInput(callsign) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            }
            .pointerInput(callsign) {
                detectDragGestures(
                    onDragStart = {
                        if (callsign != null) {
                            isDragging = true
                            onDragStart()
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        offsetX = 0f
                        offsetY = 0f
                        onDragEnd()
                    },
                    onDragCancel = {
                        isDragging = false
                        offsetX = 0f
                        offsetY = 0f
                        onDragEnd()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                )
            }
            .graphicsLayer {
                translationX = offsetX
                translationY = offsetY
                alpha = if (isDragging) 0.7f else 1f
                scaleX = if (isDragging) 1.1f else 1f
                scaleY = if (isDragging) 1.1f else 1f
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(6.dp),
            color = if (callsign != null) {
                if (isDragging) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                }
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            }
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (callsign != null) {
                    Text(
                        text = callsign,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDragging) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                }
            }
        }
    }
}

/**
 * 历史呼号项包装组件
 * 独立组件以避免lambda捕获问题
 * 使用key确保item变化时重新创建
 */
@Composable
private fun HistoryItemWidget(
    item: String?,
    index: Int,
    onDragStart: (String?) -> Unit,
    onDragEnd: () -> Unit,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 使用remember来确保item变化时更新
    val currentItem by remember(item, index) { mutableStateOf(item) }

    DraggableCallsignItem(
        callsign = currentItem,
        onDragStart = { onDragStart(currentItem) },
        onDragEnd = onDragEnd,
        onClick = { currentItem?.let { onClick(it) } },
        onLongClick = { currentItem?.let { onLongClick(it) } },
        modifier = modifier
    )
}

/**
 * 预设按钮组件
 */
@Composable
private fun PresetButton(
    name: String,
    @Suppress("UNUSED_PARAMETER") message: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(48.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * 编辑预设弹窗
 */
@Composable
private fun EditPresetDialog(
    name: String,
    message: String,
    onNameChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.tracking_edit_preset),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.morse_code_preset_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = { newValue ->
                        if (newValue.length <= 30) {
                            onMessageChange(newValue)
                        }
                    },
                    label = { Text("CW消息 (最多30字符)") },
                    placeholder = { Text("例如: CQ CQ DE BG0ABC") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text("${message.length}/30")
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = name.isNotEmpty() && message.isNotEmpty()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 默认布局：频率卡在上，雷达图在下
 */
@Composable
private fun DefaultLayout(
    selectedTransmitter: Transmitter?,
    trackingController: SatelliteTrackingController,
    @Suppress("UNUSED_PARAMETER") isTracking: Boolean,
    transmitters: List<Transmitter>,
    isConnected: Boolean,
    bluetoothConnectionManager: BluetoothConnectionManager,
    onShowTransmitterDialog: () -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    targetSatelliteId: String?,
    satellitePositions: List<SatelliteTracker.SatellitePosition>,
    satelliteTrajectories: Map<String, List<com.bh6aap.ic705Cter.tracking.TrajectoryPoint>>,
    orientation: SensorFusionManager.FusedOrientationData,
    onToggleLayout: () -> Unit,
    radarManualOffset: Float = 0f,
    onRadarOffsetChange: ((Float) -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 实时频率显示面板
        if (selectedTransmitter != null) {
            FrequencyDisplayPanel(
                trackingController = trackingController,
                isTracking = isTracking,
                selectedTransmitter = selectedTransmitter,
                onToggleLayout = onToggleLayout,
                isCompactLayout = false,
                onDoubleTap = onDoubleTap
            )
        }
        
        // 拖拽呼号状态（用于CW历史呼号拖拽到呼号记录器）
        var draggedCallsign by remember { mutableStateOf<String?>(null) }

        // CW模块展开状态（DefaultLayout不需要呼号记录器，但仍需传递）
        var isCwModuleExpanded by remember { mutableStateOf(false) }

        // 跟踪控制区域
        if (transmitters.isNotEmpty() && selectedTransmitter != null) {
            TrackingControlPanel(
                trackingController = trackingController,
                selectedTransmitter = selectedTransmitter,
                isConnected = isConnected,
                bluetoothConnectionManager = bluetoothConnectionManager,
                onShowTransmitterDialog = onShowTransmitterDialog,
                draggedCallsign = draggedCallsign,
                onDragCallsign = { draggedCallsign = it },
                isCwModuleExpanded = isCwModuleExpanded,
                onCwModuleExpandedChange = { isCwModuleExpanded = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // 占位TextView
        Text(
            text = "",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // 雷达图区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                errorMessage != null -> {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    val targetSatellitePositions = if (targetSatelliteId != null) {
                        satellitePositions.filter { it.satelliteId == targetSatelliteId }
                    } else {
                        satellitePositions
                    }

                    SatelliteRadarView(
                        satellitePositions = targetSatellitePositions,
                        phoneAzimuth = orientation.azimuth,
                        phonePitch = orientation.pitch,
                        satelliteTrajectories = satelliteTrajectories,
                        modifier = Modifier
                            .fillMaxHeight()
                            .wrapContentWidth()
                            .padding(16.dp),
                        onSatelliteClick = { sat ->
                            LogManager.i("SatelliteTracking", "点击卫星: ${sat.name}")
                        },
                        manualOffset = radarManualOffset,
                        onManualOffsetChange = onRadarOffsetChange,
                        enableManualRotate = true
                    )

                    // 显示传感器信息
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.tracking_azimuth, orientation.azimuth.toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.tracking_pitch, -orientation.pitch.toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (targetSatellitePositions.isNotEmpty()) {
                            val sat = targetSatellitePositions.first()
                            Text(
                                text = stringResource(R.string.tracking_distance, sat.distance.toInt()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 紧凑布局：频率卡+雷达图并排
 * 频率卡占宽度一半，右侧是雷达图（与频率卡等高）
 * 下方是跟踪控制栏，控制栏下方是占位TextView
 */
@Composable
private fun CompactLayout(
    selectedTransmitter: Transmitter?,
    trackingController: SatelliteTrackingController,
    isTracking: Boolean,
    transmitters: List<Transmitter>,
    isConnected: Boolean,
    bluetoothConnectionManager: BluetoothConnectionManager,
    onShowTransmitterDialog: () -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    targetSatelliteId: String?,
    satellitePositions: List<SatelliteTracker.SatellitePosition>,
    satelliteTrajectories: Map<String, List<com.bh6aap.ic705Cter.tracking.TrajectoryPoint>>,
    orientation: SensorFusionManager.FusedOrientationData,
    onToggleLayout: () -> Unit,
    radarManualOffset: Float = 0f,
    onRadarOffsetChange: ((Float) -> Unit)? = null,
    vfoAFrequency: String = "-",
    vfoAMode: String = "-",
    grid4: String = "",
    satelliteName: String = ""
) {
    // 获取屏幕尺寸
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val isSmallScreen = screenWidth < 360.dp || screenHeight < 640.dp
    val isLargeScreen = screenWidth >= 420.dp && screenHeight >= 800.dp
    
    // 根据屏幕尺寸调整间距
    val cardPadding = if (isSmallScreen) 4.dp else 8.dp

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部固定区域：频率卡+雷达图并排（高度自适应内容）
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            val halfWidth = maxWidth / 2
            // 频率卡为正方形，高度等于宽度，根据屏幕尺寸调整
            val cardSize = (halfWidth - cardPadding * 2).coerceIn(
                if (isSmallScreen) 120.dp else 140.dp,
                if (isLargeScreen) 200.dp else 180.dp
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // 左侧：频率显示卡（正方形，宽度=高度=屏幕宽度的一半）
                Box(
                    modifier = Modifier
                        .width(halfWidth)
                        .padding(cardPadding),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (selectedTransmitter != null) {
                        CompactFrequencyDisplayPanel(
                            trackingController = trackingController,
                            selectedTransmitter = selectedTransmitter,
                            onToggleLayout = onToggleLayout,
                            isCompactLayout = true,
                            modifier = Modifier.size(cardSize)
                        )
                    }
                }
                
                // 右侧：雷达图（与频率卡等高，即高度等于宽度的一半）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(cardPadding),
                    contentAlignment = Alignment.TopCenter
                ) {
                    when {
                        isLoading -> {
                            CircularProgressIndicator()
                        }
                        errorMessage != null -> {
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {
                            val targetSatellitePositions = if (targetSatelliteId != null) {
                                satellitePositions.filter { it.satelliteId == targetSatelliteId }
                            } else {
                                satellitePositions
                            }

                            SatelliteRadarView(
                                satellitePositions = targetSatellitePositions,
                                phoneAzimuth = orientation.azimuth,
                                phonePitch = orientation.pitch,
                                satelliteTrajectories = satelliteTrajectories,
                                modifier = Modifier.size(cardSize),
                                onSatelliteClick = { sat ->
                                    LogManager.i("SatelliteTracking", "点击卫星: ${sat.name}")
                                },
                                manualOffset = radarManualOffset,
                                onManualOffsetChange = onRadarOffsetChange,
                                enableManualRotate = true
                            )
                        }
                    }
                }
            }
        }
        
        // 拖拽呼号状态（用于CW历史呼号拖拽到呼号记录器）
        var draggedCallsign by remember { mutableStateOf<String?>(null) }

        // CW模块展开状态（提升到CompactLayout级别，用于判断当前模式）
        var isCwModuleExpanded by remember { mutableStateOf(false) }

        // 判断是否为线性卫星
        val isLinearTransmitter = selectedTransmitter?.downlinkHigh != null &&
                selectedTransmitter.downlinkHigh != selectedTransmitter.downlinkLow

        // 下半部分：跟踪控制栏
        if (transmitters.isNotEmpty() && selectedTransmitter != null) {
            TrackingControlPanel(
                trackingController = trackingController,
                selectedTransmitter = selectedTransmitter,
                isConnected = isConnected,
                bluetoothConnectionManager = bluetoothConnectionManager,
                onShowTransmitterDialog = onShowTransmitterDialog,
                draggedCallsign = draggedCallsign,
                onDragCallsign = { draggedCallsign = it },
                isCwModuleExpanded = isCwModuleExpanded,
                onCwModuleExpandedChange = { isCwModuleExpanded = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // 呼号记录器（使用DataStore存储）
        // 计算卫星转发器频率（MHz）
        val satelliteFreq = selectedTransmitter?.let { trans ->
            val freqHz = trans.downlinkLow ?: trans.uplinkLow
            freqHz?.let { String.format(Locale.US, "%.3f", it / 1_000_000.0) } ?: "-"
        } ?: "-"
        SimpleCallsignRecorder(
            satelliteName = satelliteName,
            currentFrequency = vfoAFrequency,
            currentMode = vfoAMode,
            grid4 = grid4,
            satelliteFrequency = satelliteFreq,
            draggedCallsign = draggedCallsign,
            onDragAccept = { draggedCallsign = null },
            isCwModuleExpanded = isCwModuleExpanded,
            isLinearTransmitter = isLinearTransmitter,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * 简单呼号记录器组件
 * 使用DataStore存储呼号记录，支持呼号模糊搜索
 * 支持接收拖拽的呼号
 */
@Composable
private fun SimpleCallsignRecorder(
    satelliteName: String,
    @Suppress("UNUSED_PARAMETER") currentFrequency: String,
    @Suppress("UNUSED_PARAMETER") currentMode: String,
    grid4: String,
    satelliteFrequency: String,
    draggedCallsign: String?,
    onDragAccept: () -> Unit,
    isCwModuleExpanded: Boolean = false,
    isLinearTransmitter: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataStore = remember { CallsignDataStore(context) }
    val callsignMatcher = remember { CallsignMatcher.getInstance(context) }

    var inputText by remember { mutableStateOf("") }
    var searchSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }
    val records by dataStore.getCallsigns().collectAsState(initial = emptyList())

    // 时间格式化
    val timeFormatter = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }
    val utcFormatter = remember {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
    
    // 获取当前UTC时间字符串
    fun getCurrentUtcTime(): String {
        val utcFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        return utcFormat.format(java.util.Date())
    }
    
    // 保存呼号记录的函数
    fun saveCallsign() {
        if (inputText.isNotBlank()) {
            scope.launch {
                val recordMode = when {
                    isLinearTransmitter && isCwModuleExpanded -> "CW"
                    isLinearTransmitter && !isCwModuleExpanded -> "USB"
                    else -> "FM"
                }
                LogManager.i("SimpleCallsignRecorder", "保存呼号记录，模式: $recordMode")
                dataStore.saveCallsign(
                    CallsignRecord(
                        callsign = inputText.trim(),
                        satelliteName = satelliteName,
                        frequency = satelliteFrequency,
                        mode = recordMode,
                        grid = grid4,
                        utcTime = getCurrentUtcTime()
                    )
                )
                inputText = ""
                showSuggestions = false
            }
        }
    }
    
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // 检测是否有拖拽的呼号，自动填入
            LaunchedEffect(draggedCallsign) {
                if (draggedCallsign != null) {
                    inputText = draggedCallsign
                    onDragAccept()
                    LogManager.i("SatelliteTracking", "呼号记录器接收拖拽呼号: $draggedCallsign")
                }
            }

            // 主内容区域（标题、输入框、记录列表）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.tracking_callsign_records, records.size),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 清空按钮
                    if (records.isNotEmpty()) {
                        TextButton(
                            onClick = { scope.launch { dataStore.clearAll() } },
                            modifier = Modifier.height(32.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.tracking_clear),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // 呼号输入框（支持系统键盘输入和拖拽）
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { newText ->
                        inputText = newText
                        if (newText.length >= 2) {
                            searchSuggestions = callsignMatcher.search(newText, limit = 10)
                            showSuggestions = searchSuggestions.isNotEmpty()
                        } else {
                            showSuggestions = false
                        }
                    },
                    placeholder = {
                        Text(
                            "输入或拖拽呼号...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .border(
                            width = if (draggedCallsign != null) 2.dp else 1.dp,
                            color = if (draggedCallsign != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            },
                            shape = RoundedCornerShape(6.dp)
                        ),
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        unfocusedContainerColor = if (draggedCallsign != null) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                        },
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(6.dp),
                    trailingIcon = {
                        if (inputText.isNotBlank()) {
                            IconButton(
                                onClick = { saveCallsign() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "保存",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                )

                // 搜索建议下拉列表（2行x5列网格布局）
                if (showSuggestions && searchSuggestions.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        shadowElevation = 2.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp)
                        ) {
                            // 将建议分成每行5个
                            val rows = searchSuggestions.chunked(5)
                            rows.forEach { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    rowItems.forEach { suggestion ->
                                        Surface(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    inputText = suggestion
                                                    showSuggestions = false
                                                },
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 6.dp, horizontal = 2.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = suggestion,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                    // 填充剩余空间（如果一行不足5个）
                                    repeat(5 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                                // 行间距
                                if (rowItems !== rows.lastOrNull()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }

                // 记录列表
                if (records.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(records) { record ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                // 呼号 - 增大字体
                                Text(
                                    text = record.callsign,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                // 时间、频率、模式、网格信息
                                // 优先使用记录中保存的数据
                                val freqText = when {
                                    record.frequency.isNotBlank() && record.frequency != "-" -> "${record.frequency}MHz"
                                    else -> ""
                                }
                                val modeText = when {
                                    record.mode.isNotBlank() && record.mode != "-" -> record.mode
                                    else -> ""
                                }
                                val gridText = when {
                                    record.grid.isNotBlank() -> record.grid
                                    else -> ""
                                }
                                // 显示本地时间和UTC时间
                                val utcTimeText = if (record.utcTime.isNotBlank()) {
                                    "UTC ${record.utcTime.substring(11, 16)}" // 提取HH:mm部分
                                } else {
                                    "UTC${utcFormatter.format(java.util.Date(record.timestamp))}"
                                }
                                Text(
                                    text = "${timeFormatter.format(java.util.Date(record.timestamp))} $utcTimeText · ${record.satelliteName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                                // 显示频率、模式、网格
                                if (freqText.isNotBlank() || modeText.isNotBlank() || gridText.isNotBlank()) {
                                    Text(
                                        text = buildString {
                                            if (freqText.isNotBlank()) append(freqText)
                                            if (modeText.isNotBlank()) {
                                                if (isNotEmpty()) append(" · ")
                                                append(modeText)
                                            }
                                            if (gridText.isNotBlank()) {
                                                if (isNotEmpty()) append(" · ")
                                                append(gridText)
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    scope.launch {
                                        dataStore.deleteCallsign(record.timestamp)
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

    } // Box 关闭
} // Surface 关闭
} // SimpleCallsignRecorder 函数关闭

/**
 * 紧凑频率显示面板（用于紧凑布局）
 * 正方形显示，简化布局
 */
@Composable
private fun CompactFrequencyDisplayPanel(
    trackingController: SatelliteTrackingController,
    selectedTransmitter: Transmitter?,
    onToggleLayout: () -> Unit,
    isCompactLayout: Boolean = true,
    modifier: Modifier = Modifier
) {
    var frequencyData by remember { mutableStateOf(trackingController.getCurrentFrequencyData()) }
    var showFrequencyInputDialog by remember { mutableStateOf(false) }
    val isCustomMode by trackingController.isCustomMode.collectAsState()

    // 折叠动画 - 箭头旋转180度
    val rotationAngle by animateFloatAsState(
        targetValue = if (isCompactLayout) 0f else 180f,
        animationSpec = tween(durationMillis = 300)
    )

    LaunchedEffect(Unit) {
        while (isActive) {
            frequencyData = trackingController.getCurrentFrequencyData()
            delay(500)
        }
    }

    if (showFrequencyInputDialog && selectedTransmitter != null) {
        FrequencyInputDialog(
            transmitter = selectedTransmitter,
            currentFrequencyKhz = (frequencyData.satelliteDownlink / 1000.0).toInt(),
            onDismiss = { showFrequencyInputDialog = false },
            onConfirm = { uplinkFreqHz, downlinkFreqHz, enableCustomMode ->
                trackingController.setCustomFrequencies(uplinkFreqHz.toDouble(), downlinkFreqHz.toDouble())
                if (enableCustomMode) {
                    trackingController.setCustomMode(true)
                }
                showFrequencyInputDialog = false
            }
        )
    }

    val isLinearTransmitter = selectedTransmitter?.downlinkHigh != null &&
            selectedTransmitter.downlinkHigh != selectedTransmitter.downlinkLow

    Surface(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (isLinearTransmitter) {
                            LogManager.i("SatelliteTracking", "双击频率显示卡，打开自定义频率输入")
                            showFrequencyInputDialog = true
                        } else {
                            LogManager.w("SatelliteTracking", "双击频率显示卡无效：非线性卫星")
                        }
                    }
                )
            }
            .then(
                if (isCustomMode) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(12.dp),
        color = if (isCustomMode) {
            Color.White
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        tonalElevation = if (isCustomMode) 0.dp else 4.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // 主要内容
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 标题
                Text(
                    text = stringResource(R.string.tracking_frequency_display),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 卫星频率组
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.tracking_satellite_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "↓",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatFrequencyWithoutUnit(frequencyData.satelliteDownlink),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "↑",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatFrequencyWithoutUnit(frequencyData.satelliteUplink),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                
                // 分隔线
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(1.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                
                // 电台频率组
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.tracking_radio_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "↓",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatFrequencyWithoutUnit(frequencyData.groundDownlink),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "↑",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatFrequencyWithoutUnit(frequencyData.groundUplink),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            
            // 右下角切换布局按钮（向左的三角）
            IconButton(
                onClick = {
                    LogManager.i("SatelliteTracking", "点击切换布局按钮")
                    onToggleLayout()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "切换布局",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            rotationZ = rotationAngle
                        }
                )
            }
        }
    }
}