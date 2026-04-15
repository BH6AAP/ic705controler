package com.bh6aap.ic705Cter.tracking

import android.content.Context
import com.bh6aap.ic705Cter.data.api.Transmitter
import com.bh6aap.ic705Cter.data.database.DatabaseHelper
import com.bh6aap.ic705Cter.data.radio.BluetoothConnectionManager
import com.bh6aap.ic705Cter.util.LogManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.roundToLong

/**
 * 卫星跟踪控制器
 * 实现自动避让波轮、多普勒频率调整等核心跟踪逻辑
 */
class SatelliteTrackingController(
    private val bluetoothConnectionManager: BluetoothConnectionManager,
    private val context: Context? = null
) {
    companion object {
        private const val TAG = "SatelliteTrackingController"
        private const val VFO_SETTLE_TIME_MS = 500L // 波轮避让后重新接管的时间（500ms，更快接管）
        private const val FREQUENCY_CHANGE_THRESHOLD = 3 // 短时间内频率变化次数阈值
        private const val FREQUENCY_CHANGE_WINDOW_MS = 500L // 频率变化检测窗口（500ms）
        private const val FREQUENCY_UPDATE_INTERVAL_MS = 100L // 频率更新间隔（100ms，提高响应速度）
        private const val DOPPLER_THRESHOLD_HZ = 1.0 // 多普勒变化阈值（1Hz），频率变化超过1Hz才更新

        private const val USER_ACTIVITY_TIMEOUT_MS = 150L // 用户活动超时时间（150ms无广播则认为用户停止操作，更快接管）

        // 频率变化检测阈值
        private const val FREQUENCY_CHANGE_THRESHOLD_HZ = 8.0 // 频率变化幅度阈值（Hz），从20Hz改为4Hz
        private const val FREQUENCY_CHANGE_MAX_HZ = 100_000_000.0 // 最大频率变化阈值（100MHz），超过此值不做避让判断
    }

    // 跟踪状态
    enum class TrackingState {
        IDLE,           // 空闲
        INITIALIZING,   // 初始化中（发送初始频率）
        WAITING_USER,   // 等待用户调整（避让波轮）
        TRACKING        // 正常跟踪中
    }

    // 卫星类型
    enum class SatelliteType {
        LINEAR,     // 线性卫星
        FM          // FM卫星
    }

    // 状态流
    private val _trackingState = MutableStateFlow(TrackingState.IDLE)
    val trackingState: StateFlow<TrackingState> = _trackingState.asStateFlow()

    private val _satelliteType = MutableStateFlow<SatelliteType?>(null)
    val satelliteType: StateFlow<SatelliteType?> = _satelliteType.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    // 频率控制相关
    private var targetTransmitter: Transmitter? = null
    private var loopFixedValue: Long? = null // 线性卫星的loop固定值

    // 基准频率（用户调整后的值）
    private var baseDownlinkFreq: Double = 0.0
    private var baseUplinkFreq: Double = 0.0
    private var baseDopplerShift: Double = 0.0

    // 卫星基准频率（线性卫星使用，消除多普勒后的标称频率）
    private var baseSatelliteDownlink: Double = 0.0
    private var baseSatelliteUplink: Double = 0.0

    // 上次发送的频率（避免重复发送相同频率）
    private var lastSentDownlinkFreq: Double = 0.0
    private var lastSentUplinkFreq: Double = 0.0
    private var lastSentDoppler: Double = 0.0

    // 避让波轮检测 - 使用独立的FrequencyControlAvoidance类
    private val frequencyControlAvoidance = FrequencyControlAvoidance()

    // 保留旧变量以保持兼容性（逐步迁移中）
    private var isAvoidingVfo = false
    private var avoidVfoJob: Job? = null
    private var lastCommandSentTime = 0L
    private var lastBroadcastTime = 0L
    private var userActivityJob: Job? = null
    private var lastVfoAFrequency: Double = 0.0
    private var lastVfoBFrequency: Double = 0.0
    private var wasInPttTransmit: Boolean = false

    // PTT相关变量
    private var prePttBaseSatelliteDownlink: Double = 0.0 // PTT发射前的卫星下行频率基准
    private var prePttRangeRate: Double = 0.0 // PTT发射前的多普勒频移率

    init {
        // 设置避让监听器
        frequencyControlAvoidance.listener = object : FrequencyControlAvoidance.AvoidanceListener {
            override fun onAvoidanceStarted() {
                _trackingState.value = TrackingState.WAITING_USER
                LogManager.i(TAG, "【避让回调】用户开始调整频率，暂停自动跟踪")
            }

            override fun onAvoidanceEnded(userAdjustedFrequency: Double) {
                _trackingState.value = TrackingState.TRACKING
                
                // 根据当前模式选择不同的处理方法
                if (_isCustomMode.value) {
                    // 自定义模式：使用反向关系计算
                    controllerScope.launch {
                        val rangeRate = PredictiveDopplerCalculator.getPredictedRangeRate()
                        handleCustomModeVfoAdjustment(userAdjustedFrequency, rangeRate)
                        LogManager.i(TAG, "【避让回调-自定义模式】用户调整完成，使用反向关系计算，新基准: ${userAdjustedFrequency/1e6} MHz")
                    }
                } else {
                    // 默认模式：使用loop计算
                    handleUserAdjustment(userAdjustedFrequency)
                    LogManager.i(TAG, "【避让回调-默认模式】用户调整完成，使用loop计算，新基准: ${userAdjustedFrequency/1e6} MHz")
                }
            }

            override fun onPttStateChanged(isTransmitting: Boolean) {
                if (isTransmitting) {
                    // PTT开始发射，保存当前基准
                    prePttBaseSatelliteDownlink = baseSatelliteDownlink
                    prePttRangeRate = PredictiveDopplerCalculator.getPredictedRangeRate()
                    LogManager.i(TAG, "【PTT回调】PTT开始发射，保存基准频率")
                } else {
                    // PTT停止发射，恢复基准
                    controllerScope.launch {
                        restoreFrequenciesAfterPtt()
                    }
                }
            }
        }
    }

    // 跟踪任务
    private var trackingJob: Job? = null
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 模式覆盖（用户手动切换的模式）
    private var modeOverride: String? = null
    
    // 分离模式设置（接收/发射使用不同模式）
    private var rxModeOverride: String? = null
    private var txModeOverride: String? = null
    
    // 当前激活的模式类型：0=默认, 1=CW全模式, 2=USB/CW分离模式
    private val _activeModeType = MutableStateFlow(0)
    val activeModeType: StateFlow<Int> = _activeModeType.asStateFlow()

    // 用户自定义下行频率（线性卫星）
    private var customDownlinkFrequency: Double? = null

    // 自定义模式状态：true=使用用户自定义频率，false=使用默认loop计算
    private val _isCustomMode = MutableStateFlow(false)
    val isCustomMode: StateFlow<Boolean> = _isCustomMode.asStateFlow()

    // 用户自定义频率（Hz）
    private var customUplinkFreqHz: Double = 0.0
    private var customDownlinkFreqHz: Double = 0.0

    // ========== 自定义模式专用变量 ==========
    // 自定义模式下的基准频率（用户设定的卫星频率）
    private var customModeBaseSatelliteDownlink: Double = 0.0
    private var customModeBaseSatelliteUplink: Double = 0.0
    // 自定义模式下的多普勒基准频率（地面频率）
    private var customModeBaseGroundDownlink: Double = 0.0
    private var customModeBaseGroundUplink: Double = 0.0
    // 波轮避让后反推的卫星频率
    private var customModeAdjustedSatelliteDownlink: Double = 0.0
    private var customModeAdjustedSatelliteUplink: Double = 0.0

    /**
     * 判断卫星类型
     */
    fun determineSatelliteType(transmitter: Transmitter): SatelliteType {
        return if (transmitter.uplinkLow != null && transmitter.uplinkHigh != null &&
                   transmitter.downlinkLow != null && transmitter.downlinkHigh != null) {
            SatelliteType.LINEAR
        } else {
            SatelliteType.FM
        }
    }

    /**
     * 设置目标转发器（不启动跟踪，用于频率显示）
     * @param transmitter 选中的转发器
     * @param loopValue 线性卫星的loop固定值（可选）
     */
    fun setTargetTransmitter(transmitter: Transmitter?, loopValue: Long? = null) {
        targetTransmitter = transmitter
        loopFixedValue = loopValue
        if (transmitter != null) {
            val type = determineSatelliteType(transmitter)
            _satelliteType.value = type
            LogManager.i(TAG, "设置目标转发器: ${transmitter.description}, 类型: $type")
        } else {
            _satelliteType.value = null
            LogManager.i(TAG, "清除目标转发器")
        }
    }

    /**
     * 开始跟踪
     * @param transmitter 选中的转发器
     * @param loopValue 线性卫星的loop固定值（可选）
     * @param forceReinit 强制重新初始化频率（默认false，如果之前已初始化则只恢复CIV发送）
     */
    fun startTracking(transmitter: Transmitter, loopValue: Long? = null, forceReinit: Boolean = false) {
        // 如果已经在跟踪，先停止CIV发送
        if (_isTracking.value) {
            stopTracking()
        }

        targetTransmitter = transmitter
        loopFixedValue = loopValue
        
        // 生成唯一的卫星跟踪ID
        currentSatelliteId = System.currentTimeMillis().toString()
        val trackingId = currentSatelliteId
        
        val type = determineSatelliteType(transmitter)
        _satelliteType.value = type
        _isTracking.value = true

        // 检查是否已经初始化过频率基准值
        val alreadyInitialized = !forceReinit && 
                                 ((type == SatelliteType.LINEAR && baseSatelliteDownlink > 0 && baseSatelliteUplink > 0) ||
                                  (type == SatelliteType.FM && baseDownlinkFreq > 0))

        if (alreadyInitialized) {
            // 已经初始化过，直接恢复CIV发送
            LogManager.i(TAG, "恢复跟踪卫星: ${transmitter.description}, 类型: $type, 使用已有频率基准")
            _trackingState.value = TrackingState.TRACKING
            // 立即发送当前频率到电台
            controllerScope.launch {
                val freqData = getCurrentFrequencyData()
                if (freqData.groundDownlink > 0 && freqData.groundUplink > 0) {
                    sendFrequencyCommand(freqData.groundDownlink, freqData.groundUplink)
                    LogManager.i(TAG, "恢复跟踪时发送频率 - 下行: ${freqData.groundDownlink/1e6} MHz, 上行: ${freqData.groundUplink/1e6} MHz")
                }
                
                // 启动避让波轮检测（仅线性卫星）
                if (type == SatelliteType.LINEAR) {
                    startVfoAvoidanceDetection()
                }
                // 启动自动跟踪
                startAutoTracking()
            }
        } else {
            // 需要初始化频率
            _trackingState.value = TrackingState.INITIALIZING
            LogManager.i(TAG, "开始跟踪卫星: ${transmitter.description}, 类型: $type, 跟踪ID: $trackingId")

            controllerScope.launch {
                when (type) {
                    SatelliteType.LINEAR -> startLinearTracking(transmitter, trackingId!!)
                    SatelliteType.FM -> startFMTracking(transmitter, trackingId!!)
                }
            }
        }
    }

    // 当前跟踪的卫星ID，用于防止旧命令干扰
    private var currentSatelliteId: String? = null

    /**
     * 停止跟踪（停止发送CIV命令，重置电台模式，停止CW键入）
     */
    fun stopTracking() {
        trackingJob?.cancel()
        avoidVfoJob?.cancel()
        userActivityJob?.cancel()
        _isTracking.value = false
        _trackingState.value = TrackingState.IDLE
        isAvoidingVfo = false
        lastBroadcastTime = 0L

        // 重置频率变化检测
        lastVfoAFrequency = 0.0
        lastVfoBFrequency = 0.0
        wasInPttTransmit = false
        prePttBaseSatelliteDownlink = 0.0
        prePttRangeRate = 0.0
        currentSatelliteId = null

        // 重置避让控制器
        frequencyControlAvoidance.reset()

        // 停止跟踪时：重置电台模式为转发器的上下行模式，并停止CW键入
        controllerScope.launch {
            try {
                val civController = bluetoothConnectionManager.civController.value
                val transmitter = targetTransmitter

                if (civController != null && transmitter != null) {
                    // 1. 停止CW键入（如果正在发射）
                    LogManager.i(TAG, "停止跟踪，停止CW键入")
                    civController.stopCwTransmission()
                    delay(100) // 短暂延迟确保命令发送

                    // 2. 重置电台模式为转发器的上下行模式（不使用用户覆盖，反向转发器翻转Data边带）
                    val downlinkMode = parseMode(transmitter.mode)
                    val uplinkMode = resolveNativeUplinkMode(transmitter)

                    LogManager.i(TAG, "停止跟踪，重置电台模式 - 下行: $downlinkMode, 上行: $uplinkMode")

                    // 设置VFO A模式（接收/下行）使用setVfoModeString以支持USB-D等Data模式
                    val vfoASuccess = civController.setVfoModeString(0x00, downlinkMode)
                    if (vfoASuccess) {
                        LogManager.i(TAG, "VFO A模式已重置为: $downlinkMode")
                    } else {
                        LogManager.w(TAG, "VFO A模式重置失败")
                    }

                    delay(50) // 短暂延迟

                    // 设置VFO B模式（发射/上行）使用setVfoModeString以支持USB-D等Data模式
                    val vfoBSuccess = civController.setVfoModeString(0x01, uplinkMode)
                    if (vfoBSuccess) {
                        LogManager.i(TAG, "VFO B模式已重置为: $uplinkMode")
                    } else {
                        LogManager.w(TAG, "VFO B模式重置失败")
                    }
                } else {
                    LogManager.w(TAG, "无法重置电台模式: CivController=${civController != null}, Transmitter=${transmitter != null}")
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "停止跟踪时重置电台模式失败", e)
            }
        }

        // 清除预测性多普勒计算器的历史数据
        PredictiveDopplerCalculator.clear()

        // 注意：不清除targetTransmitter和频率基准值，以便停止跟踪后仍能显示理论频率
        LogManager.i(TAG, "停止跟踪（重置电台模式，停止CW键入）")
    }

    /**
     * 停止跟踪但不重置电台模式（用于蓝牙未连接时）
     */
    fun stopTrackingWithoutReset() {
        trackingJob?.cancel()
        avoidVfoJob?.cancel()
        userActivityJob?.cancel()
        _isTracking.value = false
        _trackingState.value = TrackingState.IDLE
        isAvoidingVfo = false
        lastBroadcastTime = 0L

        // 重置频率变化检测
        lastVfoAFrequency = 0.0
        lastVfoBFrequency = 0.0
        wasInPttTransmit = false
        prePttBaseSatelliteDownlink = 0.0
        prePttRangeRate = 0.0
        currentSatelliteId = null

        // 重置避让控制器
        frequencyControlAvoidance.reset()

        // 清除预测性多普勒计算器的历史数据
        PredictiveDopplerCalculator.clear()

        // 注意：不清除targetTransmitter和频率基准值，不重置电台模式
        LogManager.i(TAG, "停止跟踪（蓝牙未连接，不重置电台模式）")
    }

    /**
     * 解析模式字符串为标准模式名称
     */
    private fun parseMode(mode: String): String {
        return when (mode.uppercase()) {
            "USB", "LSB", "CW", "FM", "AM", "RTTY", "CW-R", "RTTY-R" -> mode.uppercase()
            "CW-R" -> "CW-R"
            "RTTY-R" -> "RTTY-R"
            // Data模式 (USB-D/LSB-D) 及FT4/FT8/FT2别名，映射到USB-D
            "USB-D" -> "USB-D"
            "LSB-D" -> "LSB-D"
            "FT8", "FT4", "FT2", "FT4/FT8", "MSK144", "WSPR" -> "USB-D"
            else -> "USB" // 默认模式
        }
    }

    /**
     * 将模式名称转换为CIV模式代码
     */
    private fun modeToCivCode(mode: String): Byte {
        return when (mode.uppercase()) {
            "LSB" -> 0x00
            "USB" -> 0x01
            "AM" -> 0x02
            "CW" -> 0x03
            "RTTY" -> 0x04
            "FM" -> 0x05
            "CW-R" -> 0x07
            "RTTY-R" -> 0x08
            else -> 0x01 // 默认USB
        }
    }

    /**
     * 获取当前实时频率数据（用于UI显示）
     * 支持跟踪中和未跟踪两种状态
     * 优化：使用我们计算的值，而不是从电台读取
     * @return FrequencyData 包含卫星频率和电台频率的数据类
     */
    fun getCurrentFrequencyData(): FrequencyData {
        val transmitter = targetTransmitter

        if (transmitter == null) {
            // 没有选中转发器，返回零值
            return FrequencyData(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }

        // 获取预测性径向速度（考虑通信延迟）
        val rangeRate = PredictiveDopplerCalculator.getPredictedRangeRate()
        val currentDoppler = PredictiveDopplerCalculator.getPredictedDopplerShift()

        return when (_satelliteType.value) {
            SatelliteType.LINEAR -> {
                // 线性卫星：使用基准值计算频率
                // 优先级：1.自定义模式（使用调整后或初始的上下行）2.用户自定义下行频率 3.已设置的基准值 4.标称频率
                val (satelliteDownlink, satelliteUplink) = when {
                    // 1. 自定义模式：使用调整后（或初始）的卫星频率
                    _isCustomMode.value -> {
                        // 使用波轮避让后调整的频率，如果没有则使用初始频率
                        val satDown = if (customModeAdjustedSatelliteDownlink > 0) customModeAdjustedSatelliteDownlink else customModeBaseSatelliteDownlink
                        val satUp = if (customModeAdjustedSatelliteUplink > 0) customModeAdjustedSatelliteUplink else customModeBaseSatelliteUplink
                        Pair(satDown, satUp)
                    }
                    // 2. 用户自定义下行频率（使用loop计算上行）
                    customDownlinkFrequency != null && customDownlinkFrequency!! > 0 -> {
                        val downlinkLow = transmitter.downlinkLow?.toDouble() ?: 0.0
                        val downlinkHigh = transmitter.downlinkHigh?.toDouble() ?: 0.0
                        val uplinkLow = transmitter.uplinkLow?.toDouble() ?: 0.0
                        val uplinkHigh = transmitter.uplinkHigh?.toDouble() ?: 0.0
                        val loop = ((uplinkLow + uplinkHigh) / 2.0 + (downlinkLow + downlinkHigh) / 2.0)
                        val satUp = loop - customDownlinkFrequency!!
                        Pair(customDownlinkFrequency!!, satUp)
                    }
                    // 3. 已设置的基准值
                    baseSatelliteDownlink > 0 && baseSatelliteUplink > 0 -> {
                        Pair(baseSatelliteDownlink, baseSatelliteUplink)
                    }
                    // 4. 标称频率（默认）
                    else -> {
                        val downlinkLow = transmitter.downlinkLow?.toDouble() ?: 0.0
                        val downlinkHigh = transmitter.downlinkHigh?.toDouble() ?: 0.0
                        val uplinkLow = transmitter.uplinkLow?.toDouble() ?: 0.0
                        val uplinkHigh = transmitter.uplinkHigh?.toDouble() ?: 0.0
                        val centerDownlink = (downlinkLow + downlinkHigh) / 2.0
                        val centerUplink = (uplinkLow + uplinkHigh) / 2.0
                        val loop = loopFixedValue?.toDouble() ?: (centerUplink + centerDownlink)
                        val satDown = centerDownlink
                        val satUp = loop - satDown
                        Pair(satDown, satUp)
                    }
                }

                // 地面频率使用多普勒计算器基于当前径向速度计算
                val (groundDownlink, groundUplink) = if (satelliteDownlink > 0 && satelliteUplink > 0) {
                    DopplerCalculator.calculateLinearSatelliteFrequencies(
                        satelliteDownlinkHz = satelliteDownlink,
                        satelliteUplinkHz = satelliteUplink,
                        rangeRateMps = rangeRate
                    )
                } else {
                    Pair(0.0, 0.0)
                }

                FrequencyData(
                    satelliteDownlink = satelliteDownlink,
                    satelliteUplink = satelliteUplink,
                    groundDownlink = groundDownlink,
                    groundUplink = groundUplink,
                    dopplerShift = currentDoppler,
                    baseDownlink = if (baseDownlinkFreq > 0) baseDownlinkFreq else groundDownlink,
                    baseUplink = if (baseUplinkFreq > 0) baseUplinkFreq else groundUplink
                )
            }
            SatelliteType.FM -> {
                // FM卫星：使用预设频率
                val downlinkLow = transmitter.downlinkLow?.toDouble() ?: 0.0
                val uplinkLow = transmitter.uplinkLow?.toDouble() ?: 0.0
                
                val (groundDownlink, groundUplink) = DopplerCalculator.calculateFMSatelliteFrequencies(
                    satelliteDownlinkHz = downlinkLow,
                    satelliteUplinkHz = uplinkLow,
                    rangeRateMps = rangeRate
                )

                FrequencyData(
                    satelliteDownlink = downlinkLow,
                    satelliteUplink = uplinkLow,
                    groundDownlink = groundDownlink,
                    groundUplink = groundUplink,
                    dopplerShift = currentDoppler,
                    baseDownlink = downlinkLow,
                    baseUplink = uplinkLow
                )
            }
            else -> {
                // 未知类型，返回零值
                FrequencyData(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }

    /**
     * 频率数据类
     */
    data class FrequencyData(
        val satelliteDownlink: Double,  // 卫星下行频率 tA₀
        val satelliteUplink: Double,    // 卫星上行频率 tB₀
        val groundDownlink: Double,     // 电台下行频率 dA
        val groundUplink: Double,       // 电台上行频率 dB
        val dopplerShift: Double,       // 当前多普勒频移
        val baseDownlink: Double,       // 基准下行频率
        val baseUplink: Double          // 基准上行频率
    )

    /**
     * 线性卫星跟踪逻辑
     * 流程：设置下行频率 -> 计算对应上行 -> 开始跟踪
     * 支持用户自定义频率
     */
    private suspend fun startLinearTracking(transmitter: Transmitter, trackingId: String) {
        val downlinkLow = transmitter.downlinkLow ?: return
        val downlinkHigh = transmitter.downlinkHigh ?: return
        val uplinkLow = transmitter.uplinkLow ?: return
        val uplinkHigh = transmitter.uplinkHigh ?: return

        // 计算loop值
        val loop = loopFixedValue ?: ((uplinkLow + uplinkHigh) / 2 + (downlinkLow + downlinkHigh) / 2)

        // 确定卫星下行频率：优先使用用户自定义频率，否则使用中心频率
        val satelliteDownlink = customDownlinkFrequency ?: ((downlinkLow + downlinkHigh) / 2.0)
        // 根据loop计算对应的上行频率
        val satelliteUplink = loop - satelliteDownlink

        // 获取预测性径向速度（考虑通信延迟），计算多普勒补偿后的频率
        val rangeRate = PredictiveDopplerCalculator.getPredictedRangeRate()
        val (groundDownlink, groundUplink) = DopplerCalculator.calculateLinearSatelliteFrequencies(
            satelliteDownlinkHz = satelliteDownlink,
            satelliteUplinkHz = satelliteUplink,
            rangeRateMps = rangeRate
        )

        // 检查跟踪ID是否仍然有效（防止切换卫星后的旧命令干扰）
        if (currentSatelliteId != trackingId) {
            LogManager.w(TAG, "跟踪ID已改变，取消当前频率设置")
            return
        }

        // 发送多普勒补偿后的频率到电台，并设置模式
        // parseMode 将 FT8/FT4 等映射为 USB-D；resolveUplinkMode 对反向转发器翻转 Data 边带
        val rxMode = getEffectiveRxMode(parseMode(transmitter.mode))
        val txMode = resolveUplinkMode(transmitter)
        LogManager.i(TAG, "线性跟踪模式 - 下行(RX): $rxMode, 上行(TX): $txMode, 反向转发器: ${transmitter.invert}")
        sendInitialFrequencies(groundDownlink, groundUplink, rxMode, txMode)

        // 初始化基准值
        baseSatelliteDownlink = satelliteDownlink
        baseSatelliteUplink = satelliteUplink
        baseDownlinkFreq = groundDownlink
        baseUplinkFreq = groundUplink
        baseDopplerShift = 0.0
        lastSentDownlinkFreq = groundDownlink
        lastSentUplinkFreq = groundUplink
        lastSentDoppler = 0.0
        
        // 初始化VFO A频率基准值（用于PTT跳频检测）
        // 启动避让波轮检测
        startVfoAvoidanceDetection()

        // 直接进入跟踪状态（跳过用户调整阶段）
        _trackingState.value = TrackingState.TRACKING
        startAutoTracking()
    }

    /**
     * FM卫星跟踪逻辑
     */
    private suspend fun startFMTracking(transmitter: Transmitter, trackingId: String) {
        val downlinkFreq = transmitter.downlinkLow?.toDouble() ?: return
        val uplinkFreq = transmitter.uplinkLow?.toDouble() ?: return

        // 获取预测性径向速度（考虑通信延迟），计算多普勒补偿后的频率
        val rangeRate = PredictiveDopplerCalculator.getPredictedRangeRate()
        val (groundDownlink, groundUplink) = DopplerCalculator.calculateFMSatelliteFrequencies(
            satelliteDownlinkHz = downlinkFreq,
            satelliteUplinkHz = uplinkFreq,
            rangeRateMps = rangeRate
        )

        // 检查跟踪ID是否仍然有效（防止切换卫星后的旧命令干扰）
        if (currentSatelliteId != trackingId) {
            LogManager.w(TAG, "跟踪ID已改变，取消当前频率设置")
            return
        }

        // 发送多普勒补偿后的频率到电台，并设置模式
        // parseMode 将 FT8/FT4 等映射为 USB-D；resolveUplinkMode 对反向转发器翻转 Data 边带
        val rxMode = getEffectiveRxMode(parseMode(transmitter.mode))
        val txMode = resolveUplinkMode(transmitter)
        LogManager.i(TAG, "FM跟踪模式 - 下行(RX): $rxMode, 上行(TX): $txMode, 反向转发器: ${transmitter.invert}")
        sendInitialFrequencies(groundDownlink, groundUplink, rxMode, txMode)
        
        // 初始化基准值
        baseDownlinkFreq = downlinkFreq
        baseUplinkFreq = uplinkFreq
        baseSatelliteDownlink = downlinkFreq  // FM卫星使用预设频率作为卫星基准频率
        baseSatelliteUplink = uplinkFreq
        baseDopplerShift = 0.0
        lastSentDownlinkFreq = downlinkFreq
        lastSentUplinkFreq = uplinkFreq
        lastSentDoppler = 0.0

        _trackingState.value = TrackingState.TRACKING
        startAutoTracking()
    }

    /**
     * 发送初始频率（带重试机制）
     * @param downlinkHz 下行频率 (Hz)
     * @param uplinkHz 上行频率 (Hz)
     * @param rxMode 接收模式 (VFO A)
     * @param txMode 发射模式 (VFO B)
     */
    private suspend fun sendInitialFrequencies(downlinkHz: Double, uplinkHz: Double, rxMode: String, txMode: String) {
        // 将频率对齐到1Hz精度（IC-705支持1Hz精度）
        val alignedDownlinkHz = alignFrequencyTo1Hz(downlinkHz)
        val alignedUplinkHz = alignFrequencyTo1Hz(uplinkHz)

        val civController = bluetoothConnectionManager.civController.value
        if (civController == null) {
            LogManager.e(TAG, "CIV控制器未初始化，无法发送频率")
            return
        }

        try {
            // 设置VFO A频率（接收），带重试
            val downlinkSuccess = setFrequencyWithRetry(
                frequencyHz = alignedDownlinkHz,
                setFrequency = { civController.setVfoAFrequency(it) },
                label = "下行"
            )

            // 设置VFO B频率（发射），带重试
            val uplinkSuccess = setFrequencyWithRetry(
                frequencyHz = alignedUplinkHz,
                setFrequency = { civController.setVfoBFrequency(it) },
                label = "上行"
            )

            // 设置模式（分别设置上下行模式）
            if (downlinkSuccess || uplinkSuccess) {
                delay(100) // 等待频率设置稳定后再设置模式
                setSatelliteModes(rxMode, txMode)
            }

        } catch (e: Exception) {
            LogManager.e(TAG, "发送初始频率失败", e)
        }
    }

    /**
     * 带重试的频率设置
     * @param frequencyHz 频率（Hz）
     * @param setFrequency 设置频率的函数
     * @param label 标签（用于日志）
     * @param maxRetries 最大重试次数
     * @return 是否成功
     */
    private suspend fun setFrequencyWithRetry(
        frequencyHz: Long,
        setFrequency: suspend (Long) -> Boolean,
        label: String,
        maxRetries: Int = 3
    ): Boolean {
        repeat(maxRetries) { attempt ->
            if (attempt > 0) {
                delay(200) // 重试前等待
            }

            val success = setFrequency(frequencyHz)
            if (success) {
                // 成功后等待一小段时间，让电台处理
                delay(100)
                return true
            }
        }

        LogManager.e(TAG, "$label 频率设置最终失败，已重试$maxRetries 次")
        return false
    }
    
    /**
     * 将频率对齐到1Hz精度
     * IC-705支持1Hz精度显示和设置
     */
    private fun alignFrequencyTo1Hz(frequencyHz: Double): Long {
        // 四舍五入到最接近的1Hz
        return frequencyHz.roundToLong()
    }
    
    /**
     * 设置卫星跟踪模式
     * 分别设置VFO A（接收）和VFO B（发射）的模式
     * @param rxMode 接收模式 (VFO A)
     * @param txMode 发射模式 (VFO B)
     */
    private suspend fun setSatelliteModes(rxMode: String, txMode: String) {
        val civController = bluetoothConnectionManager.civController.value
        if (civController == null) {
            LogManager.e(TAG, "CIV控制器未初始化，无法设置模式")
            return
        }

        try {
            // 设置VFO A模式（接收）
            civController.setVfoModeString(0x00, rxMode)

            delay(50) // 短暂延迟

            // 设置VFO B模式（发射）
            civController.setVfoModeString(0x01, txMode)

        } catch (e: Exception) {
            LogManager.e(TAG, "设置卫星模式失败", e)
        }
    }

    /**
     * 启动避让波轮检测 - 基于广播计数器的新逻辑
     * 原理：每次发送CIV命令后重置计数器为1，监听电台广播
     * 当收到超过5次广播时，说明用户在手动调整频率
     * 
     * 注意：只监听VFO A（下行/接收）的频率变化，VFO B（上行/发射）的变化不计入避让逻辑
     */
    private fun startVfoAvoidanceDetection() {
        // 监听VFO A频率变化（用于避让逻辑和PTT状态检测）
        // LINEAR模式和自定义模式需要避让，FM模式不需要避让
        controllerScope.launch {
            bluetoothConnectionManager.vfoAFrequency.collect { freqStr ->
                if (_isTracking.value && _satelliteType.value != SatelliteType.FM) {
                    val currentFrequency = parseFrequencyString(freqStr)
                    if (currentFrequency > 0) {
                        // 使用新的避让控制器处理频率广播
                        frequencyControlAvoidance.onFrequencyBroadcast(currentFrequency, isVfoA = true)
                    }
                }
            }
        }

        // 监听VFO B频率变化（仅用于显示和记录，不计入避让逻辑）
        controllerScope.launch {
            bluetoothConnectionManager.vfoBFrequency.collect { freqStr ->
                if (_isTracking.value && _satelliteType.value != SatelliteType.FM) {
                    val currentFrequency = parseFrequencyString(freqStr)
                    if (currentFrequency > 0) {
                        // VFO B仅用于显示，不触发避让
                        LogManager.d(TAG, "【VFO B】频率: ${currentFrequency/1e6}MHz")
                    }
                }
            }
        }
    }

    /**
     * VFO A频率变化处理（下行/接收）
     * 用于波轮避让逻辑和PTT发射期间频率跟踪
     */
    private fun onVfoAFrequencyChanged() {
        val now = System.currentTimeMillis()

        // 如果距离上次发送命令太近（<200ms），忽略这次广播
        if (now - lastCommandSentTime < 200) {
            return
        }

        // 更新上次收到广播的时间
        lastBroadcastTime = now

        // 获取当前VFO A频率
        val freqStr = bluetoothConnectionManager.vfoAFrequency.value
        val currentFrequency = parseFrequencyString(freqStr)

        // VFO A频率变化检测
        if (currentFrequency <= 0) {
            LogManager.d(TAG, "【VFO A频率检测】频率无效: $freqStr")
            return
        }

        // 首次收到频率，只更新基准值，不触发避让
        if (lastVfoAFrequency <= 0) {
            LogManager.d(TAG, "【VFO A频率检测】首次收到频率，设置基准值: ${freqStr}MHz")
            lastVfoAFrequency = currentFrequency
            return
        }

        // 计算频率变化幅度
        val frequencyChangeDelta = kotlin.math.abs(currentFrequency - lastVfoAFrequency)
        LogManager.d(TAG, "【VFO A频率检测】幅度: ${frequencyChangeDelta/1_000_000.0}MHz, 当前频率: ${currentFrequency/1_000_000.0}MHz, 上次频率: ${lastVfoAFrequency/1_000_000.0}MHz, PTT状态: $wasInPttTransmit")

        // ========== 跳频检测（用于PTT状态判断）==========
        // 频率变化超过100MHz，认为是PTT状态切换
        if (frequencyChangeDelta > FREQUENCY_CHANGE_MAX_HZ) {
            if (!wasInPttTransmit) {
                // 频率从下行跳到上行，PTT开始发射
                LogManager.i(TAG, "【跳频检测】频率跳变 ${frequencyChangeDelta/1_000_000.0}MHz，检测到PTT开始发射")
                // 保存PTT前的基准频率和多普勒
                prePttBaseSatelliteDownlink = baseSatelliteDownlink
                prePttRangeRate = PredictiveDopplerCalculator.getPredictedRangeRate()
                wasInPttTransmit = true
                
                // PTT开始发射时，如果正在避让波轮，立即停止避让并恢复跟踪
                if (isAvoidingVfo) {
                    LogManager.i(TAG, "【PTT发射】PTT开始发射，立即停止波轮避让")
                    completeVfoAvoidance()
                }
                
                // 更新lastVfoAFrequency为当前频率（上行频率），但不触发避让
                lastVfoAFrequency = currentFrequency
                return
            } else {
                // 频率从上行跳回下行，PTT停止发射
                LogManager.i(TAG, "【跳频检测】频率跳变 ${frequencyChangeDelta/1_000_000.0}MHz，检测到PTT停止发射")
                wasInPttTransmit = false
                // 使用PTT前保存的基准频率计算当前多普勒并发送频率
                if (prePttBaseSatelliteDownlink > 0) {
                    restoreAndSendFrequencyAfterPtt()
                }
                // 更新lastVfoAFrequency为当前频率（下行频率）
                lastVfoAFrequency = currentFrequency
                return
            }
        }

        // ========== PTT发射期间：完全关闭波轮避让检测 ==========
        if (wasInPttTransmit) {
            // PTT发射期间，完全关闭波轮避让检测逻辑
            // 不更新lastVfoAFrequency，不检测避让，不启动避让
            LogManager.d(TAG, "【VFO A频率检测】PTT发射期间，波轮避让检测已关闭")
            return
        }

        // ========== 正常避让逻辑（接收状态）==========
        // 检查频率变化是否超过阈值（4Hz）
        if (frequencyChangeDelta >= FREQUENCY_CHANGE_THRESHOLD_HZ) {
            if (!isAvoidingVfo) {
                LogManager.i(TAG, "【波轮避让】检测到VFO A用户手动调整频率（幅度: ${frequencyChangeDelta}Hz, 当前频率: ${freqStr}MHz），启动避让")
                startVfoAvoidance()
            }
        } else {
            LogManager.d(TAG, "【VFO A频率检测】变化 ${frequencyChangeDelta}Hz 未达阈值（4Hz），不启动避让")
        }

        // 更新VFO A上次频率
        lastVfoAFrequency = currentFrequency

        // 如果正在避让波轮，记录收到的广播
        if (isAvoidingVfo) {
            LogManager.i(TAG, "【避让波轮】收到广播 | VFO A频率: ${freqStr}MHz")
            // 重置用户活动检测
            startUserActivityDetection()
        }
    }

    /**
     * 处理用户调整后的频率
     * 从地面下行反推卫星下行，计算新的基准频率
     */
    private fun handleUserAdjustment(userAdjustedFrequency: Double) {
        val transmitter = targetTransmitter ?: return

        controllerScope.launch {
            try {
                // 获取当前多普勒频移率
                val currentRangeRate = PredictiveDopplerCalculator.getPredictedRangeRate()

                // 从地面下行反推卫星下行频率
                val satelliteDownlink = DopplerCalculator.calculateSatelliteDownlink(userAdjustedFrequency, currentRangeRate)

                // 根据loop计算卫星上行频率
                val downlinkLow = transmitter.downlinkLow?.toDouble() ?: 0.0
                val downlinkHigh = transmitter.downlinkHigh?.toDouble() ?: 0.0
                val uplinkLow = transmitter.uplinkLow?.toDouble() ?: 0.0
                val uplinkHigh = transmitter.uplinkHigh?.toDouble() ?: 0.0
                val loop = ((uplinkLow + uplinkHigh) / 2.0 + (downlinkLow + downlinkHigh) / 2.0)
                val satelliteUplink = loop - satelliteDownlink

                // 计算地面上行频率
                val groundUplink = DopplerCalculator.calculateGroundUplink(satelliteUplink, currentRangeRate)

                LogManager.i(TAG, "【用户调整】从地面频率反推卫星频率 - 地面下行: ${userAdjustedFrequency/1e6}MHz, 卫星下行: ${satelliteDownlink/1e6}MHz, 卫星上行: ${satelliteUplink/1e6}MHz, 地面上行: ${groundUplink/1e6}MHz")

                // 更新基准值
                baseSatelliteDownlink = satelliteDownlink
                baseSatelliteUplink = satelliteUplink
                baseDownlinkFreq = userAdjustedFrequency
                baseUplinkFreq = groundUplink

                // 发送频率命令
                sendFrequencyCommand(userAdjustedFrequency, groundUplink)
            } catch (e: Exception) {
                LogManager.e(TAG, "【用户调整】处理用户调整频率时出错", e)
            }
        }
    }

    /**
     * PTT停止后恢复频率
     * 使用PTT前保存的卫星下行基准频率和当前多普勒频移计算上下行频率
     */
    private suspend fun restoreFrequenciesAfterPtt() {
        restoreAndSendFrequencyAfterPtt()
    }

    /**
     * PTT停止后恢复频率并发送CIV命令
     * 使用PTT前保存的卫星下行基准频率和当前多普勒频移计算上下行频率
     */
    private fun restoreAndSendFrequencyAfterPtt() {
        val transmitter = targetTransmitter ?: return

        controllerScope.launch {
            try {
                // 获取当前多普勒频移率
                val currentRangeRate = PredictiveDopplerCalculator.getPredictedRangeRate()

                // 使用PTT前保存的卫星下行基准频率
                val satelliteDownlink = prePttBaseSatelliteDownlink
                val satelliteUplink = baseSatelliteUplink

                // 计算地面频率（应用当前多普勒补偿）
                val groundDownlink = DopplerCalculator.calculateGroundDownlink(satelliteDownlink, currentRangeRate)
                val groundUplink = DopplerCalculator.calculateGroundUplink(satelliteUplink, currentRangeRate)

                LogManager.i(TAG, "【PTT恢复】使用PTT前基准频率计算多普勒并发送 - 下行: ${groundDownlink/1e6}MHz, 上行: ${groundUplink/1e6}MHz")

                // 更新基准值
                baseDownlinkFreq = groundDownlink
                baseUplinkFreq = groundUplink

                // 发送频率命令
                sendFrequencyCommand(groundDownlink, groundUplink)
            } catch (e: Exception) {
                LogManager.e(TAG, "【PTT恢复】恢复频率并发送命令时出错", e)
            }
        }
    }

    /**
     * VFO B频率变化处理（上行/发射）
     * 仅用于显示和记录，不计入波轮避让逻辑
     */
    private fun onVfoBFrequencyChanged() {
        val now = System.currentTimeMillis()

        // 如果距离上次发送命令太近（<200ms），忽略这次广播
        if (now - lastCommandSentTime < 200) {
            return
        }

        // 获取当前VFO B频率
        val freqStr = bluetoothConnectionManager.vfoBFrequency.value
        val currentFrequency = parseFrequencyString(freqStr)

        // VFO B频率变化检测（仅用于记录，不计入避让逻辑）
        if (currentFrequency > 0) {
            if (lastVfoBFrequency > 0) {
                // 计算频率变化幅度
                val frequencyChangeDelta = kotlin.math.abs(currentFrequency - lastVfoBFrequency)
                LogManager.d(TAG, "【VFO B频率检测】幅度: ${frequencyChangeDelta/1_000_000.0}MHz, 当前频率: ${currentFrequency/1_000_000.0}MHz, 上次频率: ${lastVfoBFrequency/1_000_000.0}MHz")
                
                // VFO B的变化不计入避让逻辑，只记录日志
                if (frequencyChangeDelta >= FREQUENCY_CHANGE_THRESHOLD_HZ) {
                    LogManager.i(TAG, "【VFO B频率变化】检测到发射频率变化（幅度: ${frequencyChangeDelta}Hz, 当前频率: ${freqStr}MHz），不计入避让逻辑")
                }
            } else {
                LogManager.d(TAG, "【VFO B频率检测】首次收到频率，设置基准值: ${freqStr}MHz")
            }

            // 更新VFO B上次频率
            lastVfoBFrequency = currentFrequency
        }
    }

    /**
     * 处理PTT发射期间的频率调整
     * 当用户在PTT发射期间转动波轮调整频率时，根据下行频率变化重新计算上行频率
     *
     * 重要：在PTT发射期间，VFO A和VFO B都显示上行频率
     * 需要使用之前保存的baseSatelliteDownlink作为下行频率基准
     */
    private fun handlePttFrequencyAdjustment(vfoAFreqHz: Double) {
        val transmitter = targetTransmitter ?: return
        
        // 获取转发器频率范围
        val downlinkLow = (transmitter.downlinkLow ?: return).toDouble()
        val downlinkHigh = (transmitter.downlinkHigh ?: return).toDouble()
        val uplinkLow = (transmitter.uplinkLow ?: return).toDouble()
        val uplinkHigh = (transmitter.uplinkHigh ?: return).toDouble()
        
        // 读取VFO B频率
        val vfoBFreqStr = bluetoothConnectionManager.vfoBFrequency.value
        val vfoBFreq = parseFrequencyString(vfoBFreqStr)
        
        // 判断当前是否在PTT发射状态
        // 在PTT发射期间：VFO A和VFO B都显示上行频率
        // 在接收状态：VFO A显示下行频率，VFO B显示上行频率
        val isInPttTransmit = vfoAFreqHz >= uplinkLow && vfoAFreqHz <= uplinkHigh &&
                              vfoBFreq >= uplinkLow && vfoBFreq <= uplinkHigh
        
        // 确定实际的下行频率
        val actualDownlinkHz = if (isInPttTransmit) {
            // PTT发射状态：VFO A和VFO B都显示上行频率，无法直接读取下行频率
            // 使用之前保存的baseSatelliteDownlink作为下行频率基准
            LogManager.d(TAG, "【PTT频率调整】检测到PTT发射状态，VFO A=${vfoAFreqHz/1e6}MHz, VFO B=${vfoBFreq/1e6}MHz，使用保存的下行频率基准: ${baseSatelliteDownlink/1e6}MHz")
            if (baseSatelliteDownlink > 0) baseSatelliteDownlink else {
                LogManager.w(TAG, "【PTT频率调整】没有保存的下行频率基准，跳过")
                return
            }
        } else {
            // 接收状态：VFO A显示的是下行频率
            LogManager.d(TAG, "【PTT频率调整】接收状态，VFO A=${vfoAFreqHz/1e6}MHz(下行)")
            vfoAFreqHz
        }
        
        // 如果当前频率超出下行范围，限制到边界
        val clampedDownlink = when {
            actualDownlinkHz < downlinkLow -> downlinkLow
            actualDownlinkHz > downlinkHigh -> downlinkHigh
            else -> actualDownlinkHz
        }
        
        // 如果频率被限制，记录日志
        if (clampedDownlink != actualDownlinkHz) {
            LogManager.w(TAG, "【PTT频率调整】下行频率 ${actualDownlinkHz/1e6}MHz 超出范围，限制到 ${clampedDownlink/1e6}MHz")
        }
        
        controllerScope.launch {
            try {
                val rangeRate = PredictiveDopplerCalculator.getPredictedRangeRate()
                
                // 根据卫星类型计算上行频率
                when (_satelliteType.value) {
                    SatelliteType.LINEAR -> {
                        // 线性卫星：使用loop计算
                        val loop = ((uplinkLow + uplinkHigh) / 2.0 + (downlinkLow + downlinkHigh) / 2.0)
                        val satelliteDownlink = clampedDownlink
                        val satelliteUplink = loop - satelliteDownlink
                        
                        // 检查计算出的上行频率是否在有效范围内
                        val clampedUplink = when {
                            satelliteUplink < uplinkLow -> uplinkLow
                            satelliteUplink > uplinkHigh -> uplinkHigh
                            else -> satelliteUplink
                        }
                        
                        // 计算地面频率（应用多普勒补偿）
                        val groundDownlink = DopplerCalculator.calculateGroundDownlink(satelliteDownlink, rangeRate)
                        val groundUplink = DopplerCalculator.calculateGroundUplink(clampedUplink, rangeRate)
                        
                        LogManager.i(TAG, "【PTT频率调整】线性卫星 - 下行: ${groundDownlink/1e6}MHz, 上行: ${groundUplink/1e6}MHz")
                        
                        // 更新基准值
                        baseSatelliteDownlink = satelliteDownlink
                        baseSatelliteUplink = clampedUplink
                        baseDownlinkFreq = groundDownlink
                        baseUplinkFreq = groundUplink
                        
                        // 发送频率命令（只发送上行，下行由用户控制）
                        sendFrequencyCommand(groundDownlink, groundUplink)
                    }
                    SatelliteType.FM -> {
                        // FM卫星：使用固定频率
                        val satelliteDownlink = clampedDownlink
                        val satelliteUplink = uplinkLow
                        
                        val groundDownlink = DopplerCalculator.calculateGroundDownlink(satelliteDownlink, rangeRate)
                        val groundUplink = DopplerCalculator.calculateGroundUplink(satelliteUplink, rangeRate)
                        
                        LogManager.i(TAG, "【PTT频率调整】FM卫星 - 下行: ${groundDownlink/1e6}MHz, 上行: ${groundUplink/1e6}MHz")
                        
                        baseSatelliteDownlink = satelliteDownlink
                        baseSatelliteUplink = satelliteUplink
                        baseDownlinkFreq = groundDownlink
                        baseUplinkFreq = groundUplink
                        
                        sendFrequencyCommand(groundDownlink, groundUplink)
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "【PTT频率调整】处理频率调整时出错", e)
            }
        }
    }

    /**
     * 解析频率字符串为Hz
     * @param freqStr 频率字符串，如 "145.800000"
     * @return 频率（Hz），解析失败返回0
     */
    private fun parseFrequencyString(freqStr: String): Double {
        try {
            val freqMHz = freqStr.toDoubleOrNull() ?: return 0.0
            return freqMHz * 1_000_000.0 // 转换为Hz
        } catch (e: Exception) {
            LogManager.e(TAG, "解析频率字符串失败: $freqStr", e)
            return 0.0
        }
    }
    
    /**
     * 启动用户活动检测
     * 检测用户是否停止操作，停止后快速接管
     */
    private fun startUserActivityDetection() {
        userActivityJob?.cancel()
        userActivityJob = controllerScope.launch {
            // 等待一段时间，如果没有新的广播则认为用户停止操作
            delay(USER_ACTIVITY_TIMEOUT_MS)
            
            // 检查是否还有新的广播
            val timeSinceLastBroadcast = System.currentTimeMillis() - lastBroadcastTime
            if (timeSinceLastBroadcast >= USER_ACTIVITY_TIMEOUT_MS && isAvoidingVfo) {
                LogManager.i(TAG, "【快速接管】用户停止操作（${timeSinceLastBroadcast}ms无活动），立即接管")
                completeVfoAvoidance()
            }
        }
    }

    /**
     * 重置广播计数器
     * 在每次发送CIV命令后调用
     */
    private fun resetBroadcastCounter() {
        lastCommandSentTime = System.currentTimeMillis()
    }

    /**
     * 启动避让波轮
     * 优化：使用快速接管机制，用户停止操作后立即接管
     */
    private fun startVfoAvoidance() {
        isAvoidingVfo = true
        _trackingState.value = TrackingState.WAITING_USER

        // 取消自动跟踪任务
        trackingJob?.cancel()

        LogManager.i(TAG, "【避让波轮】已暂停自动跟踪，等待用户调整完成...")

        // 启动用户活动检测（快速接管机制）
        startUserActivityDetection()
        
        // 同时启动一个最大等待时间计时器（防止用户一直不停止操作）
        avoidVfoJob?.cancel()
        avoidVfoJob = controllerScope.launch {
            // 最长等待2秒
            delay(2000)
            
            if (isAvoidingVfo) {
                LogManager.i(TAG, "【避让波轮】达到最大等待时间，强制接管频率控制")
                completeVfoAvoidance()
            }
        }
    }

    /**
     * 完成避让波轮，接管控制
     */
    private fun completeVfoAvoidance() {
        // 取消所有检测任务
        userActivityJob?.cancel()
        avoidVfoJob?.cancel()
        
        // 用户调整完成
        LogManager.i(TAG, "【避让波轮】用户调整完成，重新接管频率控制")
        isAvoidingVfo = false
        
        // 在协程中调用用户调整完成回调
        controllerScope.launch {
            // 调用用户调整完成回调
            onUserAdjustmentComplete()
            
            // 设置状态为TRACKING
            _trackingState.value = TrackingState.TRACKING
            
            // 重新启动自动跟踪
            startAutoTracking()
        }
    }

    /**
     * 用户调整完成回调
     * 注意：此方法设置基准值并发送初始频率，但不启动自动跟踪
     * 自动跟踪由调用方（waitForUserAdjustment退出后）启动
     *
     * 重要：在PTT发射期间，VFO A和VFO B都显示上行频率
     * 需要使用之前保存的baseSatelliteDownlink作为下行频率基准
     */
    private suspend fun onUserAdjustmentComplete() {
        val transmitter = targetTransmitter ?: return

        // 获取转发器下行频率范围
        val downlinkLowHz = transmitter.downlinkLow ?: return
        val downlinkHighHz = transmitter.downlinkHigh ?: return
        val downlinkLow = downlinkLowHz.toDouble() - 10_000.0  // 下边界扩展10kHz
        val downlinkHigh = downlinkHighHz.toDouble() + 10_000.0  // 上边界扩展10kHz
        
        // 获取上行频率范围（用于判断是否在PTT发射状态）
        val uplinkLowHz = transmitter.uplinkLow ?: return
        val uplinkHighHz = transmitter.uplinkHigh ?: return
        val uplinkLow = uplinkLowHz.toDouble() - 10_000.0
        val uplinkHigh = uplinkHighHz.toDouble() + 10_000.0

        // 读取VFO A频率
        val vfoAFreqStr = bluetoothConnectionManager.vfoAFrequency.value
        val vfoAFreq = parseFrequency(vfoAFreqStr)
        
        // 读取VFO B频率
        val vfoBFreqStr = bluetoothConnectionManager.vfoBFrequency.value
        val vfoBFreq = parseFrequency(vfoBFreqStr)
        
        LogManager.i(TAG, "【接管】VFO A频率: '$vfoAFreqStr', VFO B频率: '$vfoBFreqStr'")

        // 判断当前是否在PTT发射状态
        // 在PTT发射期间：VFO A和VFO B都显示上行频率
        // 在接收状态：VFO A显示下行频率，VFO B显示上行频率
        val isInPttTransmit = vfoAFreq != null && vfoAFreq >= uplinkLow && vfoAFreq <= uplinkHigh &&
                              vfoBFreq != null && vfoBFreq >= uplinkLow && vfoBFreq <= uplinkHigh
        
        var currentDownlink: Double
        
        if (isInPttTransmit) {
            // PTT发射状态：VFO A和VFO B都显示上行频率，无法直接读取下行频率
            // 使用之前保存的baseSatelliteDownlink作为下行频率基准
            LogManager.i(TAG, "【接管】检测到PTT发射状态，VFO A=${vfoAFreqStr}MHz, VFO B=${vfoBFreqStr}MHz，使用保存的下行频率基准: ${baseSatelliteDownlink/1e6}MHz")
            currentDownlink = if (baseSatelliteDownlink > 0) baseSatelliteDownlink else return
        } else {
            // 接收状态：VFO A显示的是下行频率
            if (vfoAFreq == null) {
                LogManager.e(TAG, "【接管】解析VFO A频率失败: '$vfoAFreqStr'")
                return
            }
            currentDownlink = vfoAFreq
            LogManager.i(TAG, "【接管】接收状态，从VFO A读取下行频率: ${vfoAFreqStr}MHz")
        }

        // 检查频率是否在转发器下行范围内（上下各扩展10kHz），如果超出则限制到边缘频率
        if (currentDownlink < downlinkLow) {
            currentDownlink = downlinkLow
        } else if (currentDownlink > downlinkHigh) {
            currentDownlink = downlinkHigh
        }

        // 获取当前多普勒频移
        val currentDoppler = getCurrentDopplerShift()

        // 获取径向速度用于反推卫星频率
        val rangeRate = PredictiveDopplerCalculator.getPredictedRangeRate()

        if (_satelliteType.value == SatelliteType.LINEAR) {
            // 判断是否为自定义模式
            if (_isCustomMode.value) {
                // ========== 自定义模式下的频率反推 ==========
                handleCustomModeVfoAdjustment(currentDownlink, rangeRate)
            } else {
                // ========== 默认模式下的频率反推（使用loop） ==========
                handleDefaultModeVfoAdjustment(currentDownlink, rangeRate, downlinkLow, downlinkHigh)
            }
        } else {
            // FM卫星：使用预设频率
            baseDownlinkFreq = currentDownlink
            baseDopplerShift = currentDoppler
            baseSatelliteDownlink = (transmitter.downlinkLow ?: 0).toDouble()
            baseSatelliteUplink = (transmitter.uplinkLow ?: 0).toDouble()
            baseUplinkFreq = baseSatelliteUplink
            
            // 初始化上次发送的频率
            lastSentDownlinkFreq = baseDownlinkFreq
            lastSentUplinkFreq = baseUplinkFreq
            lastSentDoppler = currentDoppler

            // 发送初始频率到电台
            sendFrequencyCommand(baseDownlinkFreq, baseUplinkFreq)
        }

        // 注意：不在这里设置_trackingState和启动autoTracking
        // 这些由调用方（startVfoAvoidance中的循环退出后）处理
    }
    
    /**
     * 处理自定义模式下的波轮避让后频率反推
     * 核心逻辑：下行和上行是反向关系
     * 公式：DFr2 = 用户调整后的地面下行频率
     *      DFr2（卫星下行）= 从地面下行反推
     *      TFr2（卫星上行）= TFr - (DFr2 - DFr)  【注意：下行增加，上行减少】
     *      多TFr2（地面上行）= 由TFr2计算多普勒
     */
    private suspend fun handleCustomModeVfoAdjustment(groundDownlink2: Double, rangeRate: Double) {
        LogManager.i(TAG, "【自定义模式接管】用户调整后的地面下行频率: ${groundDownlink2/1e6} MHz")
        
        // 1. 从地面下行频率反推卫星下行频率 DFr2
        val satelliteDownlink2 = DopplerCalculator.calculateSatelliteDownlink(groundDownlink2, rangeRate)
        
        // 2. 计算卫星上行频率 TFr2 = TFr - (DFr2 - DFr)
        // 核心：下行和上行是反向关系！卫星下行频率增加 -> 卫星上行频率减少
        val deltaSatelliteDownlink = satelliteDownlink2 - customModeBaseSatelliteDownlink
        val satelliteUplink2 = customModeBaseSatelliteUplink - deltaSatelliteDownlink
        
        // 3. 计算地面上行频率（应用多普勒补偿）
        val groundUplink2 = DopplerCalculator.calculateGroundUplink(satelliteUplink2, rangeRate)
        
        LogManager.i(TAG, "【自定义模式接管】反推结果 - " +
            "卫星下行 DFr2: ${satelliteDownlink2/1e6} MHz, " +
            "卫星上行 TFr2: ${satelliteUplink2/1e6} MHz, " +
            "地面上行 多TFr2: ${groundUplink2/1e6} MHz")
        
        // 4. 更新自定义模式的调整后频率（作为后续多普勒计算的基准）
        customModeAdjustedSatelliteDownlink = satelliteDownlink2
        customModeAdjustedSatelliteUplink = satelliteUplink2
        
        // 5. 更新基准值（用于UI显示）
        baseDownlinkFreq = groundDownlink2
        baseUplinkFreq = groundUplink2
        baseSatelliteDownlink = satelliteDownlink2
        baseSatelliteUplink = satelliteUplink2
        
        // 6. 初始化上次发送的频率
        lastSentDownlinkFreq = groundDownlink2
        lastSentUplinkFreq = groundUplink2
        lastSentDoppler = getCurrentDopplerShift()
        
        // 7. 发送频率到电台（只发送上行频率，下行是用户手动调整的）
        sendFrequencyCommand(groundDownlink2, groundUplink2)
    }
    
    /**
     * 处理默认模式下的波轮避让后频率反推（使用loop计算）
     */
    private suspend fun handleDefaultModeVfoAdjustment(
        currentDownlink: Double, 
        rangeRate: Double,
        downlinkLow: Double,
        downlinkHigh: Double
    ) {
        // 获取当前多普勒频移
        val currentDoppler = getCurrentDopplerShift()
        
        // 计算基准值
        baseDownlinkFreq = currentDownlink
        baseDopplerShift = currentDoppler

        // 线性卫星：使用径向速度反推卫星基准频率
        // 从地面接收频率反推卫星发射频率：f_sat = f_ground * c / (c - v_r)
        val transmitter = targetTransmitter ?: return
        val uplinkLow = (transmitter.uplinkLow ?: return).toDouble()
        val uplinkHigh = (transmitter.uplinkHigh ?: return).toDouble()

        // 如果没有传入loopFixedValue，使用默认计算
        val loop = loopFixedValue?.toDouble() ?: ((uplinkLow + uplinkHigh) / 2.0 + (downlinkLow + downlinkHigh) / 2.0)

        // 使用DopplerCalculator从地面接收频率反推卫星频率
        val (satelliteDownlink, satelliteUplink, _) = DopplerCalculator.calculateLinearSatelliteFrequenciesFromGround(
            groundDownlinkHz = currentDownlink,
            groundUplinkHz = 0.0,  // 不需要，因为我们用loop计算
            rangeRateMps = rangeRate,
            loopHz = loop
        )
        baseSatelliteDownlink = satelliteDownlink
        baseSatelliteUplink = satelliteUplink

        // 计算地面上行频率（应用多普勒补偿）
        val groundUplink = DopplerCalculator.calculateGroundUplink(satelliteUplink, rangeRate)

        // 检查计算结果是否有效
        if (groundUplink <= 0) {
            LogManager.e(TAG, "【接管】计算出的上行频率无效: ${groundUplink/1e6} MHz")
            return
        }

        baseUplinkFreq = groundUplink
        
        // 初始化上次发送的频率
        lastSentDownlinkFreq = baseDownlinkFreq
        lastSentUplinkFreq = baseUplinkFreq
        lastSentDoppler = currentDoppler

        // 发送初始频率到电台（关键：确保上行频率被正确设置）
        sendFrequencyCommand(baseDownlinkFreq, baseUplinkFreq)
    }

    /**
     * 开始自动跟踪
     * 修改：PTT发射期间保持多普勒跟踪，即使正在避让波轮
     */
    private fun startAutoTracking() {
        trackingJob?.cancel()
        trackingJob = controllerScope.launch {
            while (isActive && _isTracking.value) {
                // 修改条件：PTT发射期间保持多普勒跟踪
                // 正常情况：不在避让状态且在跟踪状态
                // PTT期间：在PTT发射状态且在跟踪状态（即使正在避让也继续跟踪）
                val inTrackingState = _trackingState.value == TrackingState.TRACKING
                val shouldUpdate = inTrackingState && (!isAvoidingVfo || wasInPttTransmit)
                if (shouldUpdate) {
                    updateFrequencies()
                }
                delay(FREQUENCY_UPDATE_INTERVAL_MS) // 1秒更新一次
            }
        }
    }

    /**
     * 更新频率（多普勒补偿）
     */
    private suspend fun updateFrequencies() {
        val currentDoppler = getCurrentDopplerShift()
        
        // 检查多普勒变化是否超过阈值
        val dopplerDelta = currentDoppler - baseDopplerShift
        val dopplerChange = kotlin.math.abs(currentDoppler - lastSentDoppler)
        
        if (dopplerChange < DOPPLER_THRESHOLD_HZ) {
            // 多普勒变化太小，不更新
            return
        }

        // 获取径向速度
        val rangeRate = PredictiveDopplerCalculator.getPredictedRangeRate()

        when (_satelliteType.value) {
            SatelliteType.LINEAR -> {
                // 判断是否使用自定义模式
                if (_isCustomMode.value) {
                    // 自定义模式：使用调整后（或初始）的卫星频率
                    val satelliteDownlink = customModeAdjustedSatelliteDownlink
                    val satelliteUplink = customModeAdjustedSatelliteUplink
                    
                    if (satelliteDownlink > 0 && satelliteUplink > 0) {
                        val (newDownlink, newUplink) = DopplerCalculator.calculateLinearSatelliteFrequencies(
                            satelliteDownlinkHz = satelliteDownlink,
                            satelliteUplinkHz = satelliteUplink,
                            rangeRateMps = rangeRate
                        )
                        
                        // 检查频率是否有实际变化
                        if (kotlin.math.abs(newDownlink - lastSentDownlinkFreq) > DOPPLER_THRESHOLD_HZ ||
                            kotlin.math.abs(newUplink - lastSentUplinkFreq) > DOPPLER_THRESHOLD_HZ) {
                            sendFrequencyCommand(newDownlink, newUplink)
                            lastSentDoppler = currentDoppler
                        }
                    }
                } else {
                    // 默认模式：使用统一的多普勒计算器
                    val (newDownlink, newUplink) = DopplerCalculator.calculateLinearSatelliteFrequencies(
                        satelliteDownlinkHz = baseSatelliteDownlink,
                        satelliteUplinkHz = baseSatelliteUplink,
                        rangeRateMps = rangeRate
                    )

                    // 检查频率是否有实际变化
                    if (kotlin.math.abs(newDownlink - lastSentDownlinkFreq) > DOPPLER_THRESHOLD_HZ ||
                        kotlin.math.abs(newUplink - lastSentUplinkFreq) > DOPPLER_THRESHOLD_HZ) {
                        sendFrequencyCommand(newDownlink, newUplink)
                        lastSentDoppler = currentDoppler
                    }
                }
            }
            SatelliteType.FM -> {
                // FM卫星：使用统一的多普勒计算器
                val (newDownlink, newUplink) = DopplerCalculator.calculateFMSatelliteFrequencies(
                    satelliteDownlinkHz = baseSatelliteDownlink,
                    satelliteUplinkHz = baseSatelliteUplink,
                    rangeRateMps = rangeRate
                )

                // 检查频率是否有实际变化
                if (kotlin.math.abs(newDownlink - lastSentDownlinkFreq) > DOPPLER_THRESHOLD_HZ ||
                    kotlin.math.abs(newUplink - lastSentUplinkFreq) > DOPPLER_THRESHOLD_HZ) {
                    sendFrequencyCommand(newDownlink, newUplink)
                    lastSentDoppler = currentDoppler
                }
            }
            else -> {}
        }
    }

    /**
     * 设置用户自定义下行频率（线性卫星）
     * @param frequencyHz 用户输入的卫星下行频率（Hz）
     */
    fun setCustomDownlinkFrequency(frequencyHz: Double) {
        customDownlinkFrequency = frequencyHz
        LogManager.i(TAG, "设置自定义卫星下行频率: ${frequencyHz/1e6} MHz")

        // 如果正在跟踪，立即重新计算并发送频率
        if (isTracking.value && targetTransmitter != null) {
            controllerScope.launch {
                recalculateAndSendFrequency()
            }
        }
    }

    /**
     * 设置用户自定义频率（上行和下行）
     * @param uplinkFreqHz 卫星上行频率（Hz）
     * @param downlinkFreqHz 卫星下行频率（Hz）
     */
    fun setCustomFrequencies(uplinkFreqHz: Double, downlinkFreqHz: Double) {
        // 设置自定义下行频率（用于兼容性）
        customDownlinkFrequency = downlinkFreqHz
        
        // 设置自定义模式频率值
        customUplinkFreqHz = uplinkFreqHz
        customDownlinkFreqHz = downlinkFreqHz
        
        // 更新基准频率
        baseSatelliteDownlink = downlinkFreqHz
        baseSatelliteUplink = uplinkFreqHz
        
        LogManager.i(TAG, "设置自定义频率 - 上行: ${uplinkFreqHz/1e6} MHz, 下行: ${downlinkFreqHz/1e6} MHz")

        // 如果正在跟踪，立即重新计算并发送频率
        if (isTracking.value && targetTransmitter != null) {
            controllerScope.launch {
                recalculateAndSendFrequency()
            }
        }
    }

    /**
     * 设置自定义模式
     * @param enabled true=启用自定义模式（使用用户定义的上下行频率），false=使用默认loop计算
     */
    fun setCustomMode(enabled: Boolean) {
        val wasEnabled = _isCustomMode.value
        _isCustomMode.value = enabled
        LogManager.i(TAG, "设置自定义模式: $enabled")

        if (enabled && !wasEnabled) {
            // 启用自定义模式
            controllerScope.launch {
                // 每次启用自定义模式时，都从数据库重新加载第一条预设
                // 这样用户置顶新频率后可以立即生效
                loadFirstPresetFromDatabase()
                // 初始化自定义模式基准频率
                initializeCustomModeBaseFrequencies()

                // 立即刷新频率显示和发送（无论是否在跟踪状态）
                recalculateAndSendCustomModeFrequency()
            }
        } else if (!enabled && wasEnabled) {
            // 停用自定义模式，恢复默认计算
            controllerScope.launch {
                recalculateAndSendFrequency()
            }
        }
    }

    /**
     * 从数据库加载第一条预设频率（挂起函数）
     * 如果没有预设，则使用当前转发器的默认频率
     */
    private suspend fun loadFirstPresetFromDatabase() {
        val ctx = context ?: return
        val transmitter = targetTransmitter ?: return
        val noradId = transmitter.noradCatId.toString()

        try {
            val dbHelper = DatabaseHelper.getInstance(ctx)
            val presets = dbHelper.getFrequencyPresetsByNoradId(noradId).first()

            if (presets.isNotEmpty()) {
                val firstPreset = presets.first()
                customUplinkFreqHz = firstPreset.uplinkFreqHz.toDouble()
                customDownlinkFreqHz = firstPreset.downlinkFreqHz.toDouble()
                LogManager.i(TAG, "从数据库加载第一条预设频率 - " +
                    "名称: ${firstPreset.name}, " +
                    "上行: ${customUplinkFreqHz/1e6} MHz, " +
                    "下行: ${customDownlinkFreqHz/1e6} MHz")
            } else {
                // 数据库中没有预设，使用当前转发器的默认频率
                val uplink = transmitter.uplinkLow?.toDouble() ?: 0.0
                val downlink = transmitter.downlinkLow?.toDouble() ?: 0.0
                if (uplink > 0 && downlink > 0) {
                    customUplinkFreqHz = uplink
                    customDownlinkFreqHz = downlink
                    LogManager.i(TAG, "数据库中没有预设，使用转发器默认频率 - " +
                        "上行: ${customUplinkFreqHz/1e6} MHz, " +
                        "下行: ${customDownlinkFreqHz/1e6} MHz")
                } else {
                    LogManager.w(TAG, "数据库中没有预设，且转发器频率无效")
                }
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "从数据库加载预设频率失败", e)
        }
    }

    /**
     * 初始化自定义模式基准频率
     * 使用用户设定的频率作为基准，计算初始多普勒频率
     */
    private fun initializeCustomModeBaseFrequencies() {
        if (customUplinkFreqHz <= 0 || customDownlinkFreqHz <= 0) {
            LogManager.w(TAG, "自定义模式初始化失败：频率未设置")
            return
        }

        // 保存用户设定的卫星频率作为基准
        customModeBaseSatelliteDownlink = customDownlinkFreqHz
        customModeBaseSatelliteUplink = customUplinkFreqHz
        customModeAdjustedSatelliteDownlink = customDownlinkFreqHz
        customModeAdjustedSatelliteUplink = customUplinkFreqHz

        // 获取当前径向速度，计算初始地面频率（多普勒频率）
        val rangeRate = PredictiveDopplerCalculator.getPredictedRangeRate()
        customModeBaseGroundDownlink = DopplerCalculator.calculateGroundDownlink(customModeBaseSatelliteDownlink, rangeRate)
        customModeBaseGroundUplink = DopplerCalculator.calculateGroundUplink(customModeBaseSatelliteUplink, rangeRate)

        LogManager.i(TAG, "自定义模式基准频率初始化 - " +
            "卫星下行: ${customModeBaseSatelliteDownlink/1e6} MHz, " +
            "卫星上行: ${customModeBaseSatelliteUplink/1e6} MHz, " +
            "地面下行: ${customModeBaseGroundDownlink/1e6} MHz, " +
            "地面上行: ${customModeBaseGroundUplink/1e6} MHz")
    }

    /**
     * 切换自定义模式开关
     */
    fun toggleCustomMode() {
        setCustomMode(!_isCustomMode.value)
    }

    /**
     * 重新计算并发送频率（用于用户自定义频率后）- 默认模式
     */
    private suspend fun recalculateAndSendFrequency() {
        val transmitter = targetTransmitter ?: return

        // 获取当前径向速度
        val rangeRate = PredictiveDopplerCalculator.getPredictedRangeRate()

        // 默认模式：使用loop计算
        val customSatelliteDownlink = customDownlinkFrequency ?: return
        val uplinkLow = (transmitter.uplinkLow ?: return).toDouble()
        val uplinkHigh = (transmitter.uplinkHigh ?: return).toDouble()
        val downlinkLow = (transmitter.downlinkLow ?: return).toDouble()
        val downlinkHigh = (transmitter.downlinkHigh ?: return).toDouble()
        val loop = ((uplinkLow + uplinkHigh) / 2.0 + (downlinkLow + downlinkHigh) / 2.0)
        val satUplink = loop - customSatelliteDownlink
        LogManager.i(TAG, "重新计算频率 - 使用loop计算: 上行=${satUplink/1e6} MHz, 下行=${customSatelliteDownlink/1e6} MHz")
        
        val satelliteDownlink = customSatelliteDownlink
        val satelliteUplink = satUplink

        // 计算地面频率（应用多普勒补偿）
        val groundDownlink = DopplerCalculator.calculateGroundDownlink(satelliteDownlink, rangeRate)
        val groundUplink = DopplerCalculator.calculateGroundUplink(satelliteUplink, rangeRate)

        // 更新基准值
        baseDownlinkFreq = groundDownlink
        baseSatelliteDownlink = satelliteDownlink
        baseSatelliteUplink = satelliteUplink
        baseUplinkFreq = groundUplink

        // 发送频率到电台
        sendFrequencyCommand(groundDownlink, groundUplink)
    }
    
    /**
     * 重新计算并发送频率 - 自定义模式
     * 使用用户设定的卫星频率计算多普勒频率
     */
    private suspend fun recalculateAndSendCustomModeFrequency() {
        // 获取当前径向速度
        val rangeRate = PredictiveDopplerCalculator.getPredictedRangeRate()
        
        // 使用调整后（或初始）的卫星频率
        val satelliteDownlink = customModeAdjustedSatelliteDownlink
        val satelliteUplink = customModeAdjustedSatelliteUplink
        
        if (satelliteDownlink <= 0 || satelliteUplink <= 0) {
            LogManager.w(TAG, "自定义模式频率计算失败：卫星频率未初始化")
            return
        }
        
        // 计算地面频率（应用多普勒补偿）
        val groundDownlink = DopplerCalculator.calculateGroundDownlink(satelliteDownlink, rangeRate)
        val groundUplink = DopplerCalculator.calculateGroundUplink(satelliteUplink, rangeRate)
        
        LogManager.i(TAG, "自定义模式重新计算 - " +
            "卫星下行: ${satelliteDownlink/1e6} MHz, " +
            "卫星上行: ${satelliteUplink/1e6} MHz, " +
            "地面下行: ${groundDownlink/1e6} MHz, " +
            "地面上行: ${groundUplink/1e6} MHz")
        
        // 更新基准值
        customModeBaseGroundDownlink = groundDownlink
        customModeBaseGroundUplink = groundUplink
        
        // 发送频率到电台
        sendFrequencyCommand(groundDownlink, groundUplink)
    }

    /**
     * 发送频率命令
     * 使用新的CivController API
     * 新增：频率变化检查，如果下行频率变化超过5MHz则拒绝发送
     */
    private suspend fun sendFrequencyCommand(downlinkHz: Double, uplinkHz: Double) {
        val civController = bluetoothConnectionManager.civController.value ?: return

        // 检查下行频率变化是否超过5MHz（仅在已开始跟踪后检查）
        if (_isTracking.value && lastSentDownlinkFreq > 0) {
            val downlinkChangeHz = kotlin.math.abs(downlinkHz - lastSentDownlinkFreq)
            val maxAllowedChangeHz = 5_000_000.0 // 5MHz
            
            if (downlinkChangeHz > maxAllowedChangeHz) {
                LogManager.w(TAG, "【频率过滤】下行频率变化 ${downlinkChangeHz/1e6}MHz 超过5MHz限制，拒绝发送命令。" +
                        "目标: ${downlinkHz/1e6}MHz, 上次: ${lastSentDownlinkFreq/1e6}MHz")
                return
            }
        }

        // 将频率对齐到1Hz精度（IC-705支持1Hz精度）
        val alignedDownlinkHz = alignFrequencyTo1Hz(downlinkHz)
        val alignedUplinkHz = alignFrequencyTo1Hz(uplinkHz)

        try {
            // 重置广播计数器（在发送命令前）
            resetBroadcastCounter()

            // 设置VFO A频率（接收）
            val downlinkSuccess = civController.setVfoAFrequency(alignedDownlinkHz)

            // 设置VFO B频率（发射）
            val uplinkSuccess = civController.setVfoBFrequency(alignedUplinkHz)

            if (downlinkSuccess && uplinkSuccess) {
                // 记录成功发送的频率（使用对齐后的值）
                lastSentDownlinkFreq = alignedDownlinkHz.toDouble()
                lastSentUplinkFreq = alignedUplinkHz.toDouble()
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "发送频率命令失败", e)
        }
    }

    /**
     * 获取当前多普勒频移
     * 从全局缓存读取Orekit计算结果
     */
    private fun getCurrentDopplerShift(): Double {
        return PredictiveDopplerCalculator.getPredictedDopplerShift()
    }

    /**
     * 解析频率字符串
     */
    private fun parseFrequency(freqStr: String): Double? {
        return try {
            freqStr.replace(" MHz", "").toDouble() * 1e6
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 设置模式覆盖（旧方法，用于CW全模式）
     * @param mode 模式名称（"CW"或null表示使用默认模式）
     */
    fun setModeOverride(mode: String?) {
        modeOverride = mode
        // 清除分离模式设置
        rxModeOverride = null
        txModeOverride = null
        _activeModeType.value = if (mode == "CW") 1 else 0
        LogManager.i(TAG, "设置模式覆盖: ${mode ?: "默认"}")
    }
    
    /**
     * 设置分离模式（USB/CW）
     * @param enabled true=启用USB/CW分离模式, false=清除分离模式
     */
    fun setSplitMode(enabled: Boolean) {
        if (enabled) {
            rxModeOverride = "USB"
            txModeOverride = "CW"
            modeOverride = null
            _activeModeType.value = 2
            LogManager.i(TAG, "设置分离模式: 接收=USB, 发射=CW")
        } else {
            rxModeOverride = null
            txModeOverride = null
            _activeModeType.value = 0
            LogManager.i(TAG, "清除分离模式")
        }
    }
    
    /**
     * 设置模式类型（0=默认, 1=CW全模式, 2=USB/CW分离模式, 3=Data模式）
     * @param modeType 模式类型
     */
    fun setModeType(modeType: Int) {
        when (modeType) {
            0 -> {
                // 默认模式
                modeOverride = null
                rxModeOverride = null
                txModeOverride = null
                _activeModeType.value = 0
                LogManager.i(TAG, "切换到默认模式")
            }
            1 -> {
                // CW全模式
                modeOverride = "CW"
                rxModeOverride = null
                txModeOverride = null
                _activeModeType.value = 1
                LogManager.i(TAG, "切换到CW全模式")
            }
            2 -> {
                // USB/CW分离模式
                modeOverride = null
                rxModeOverride = "USB"
                txModeOverride = "CW"
                _activeModeType.value = 2
                LogManager.i(TAG, "切换到USB/CW分离模式")
            }
            3 -> {
                // Data模式：下行USB-D，上行LSB-D
                modeOverride = null
                rxModeOverride = "USB-D"
                txModeOverride = "LSB-D"
                _activeModeType.value = 3
                LogManager.i(TAG, "切换到Data模式: 下行USB-D, 上行LSB-D")
            }
        }
    }

    /**
     * 获取实际使用的模式
     * @param defaultMode 转发器默认模式
     * @return 实际使用的模式（考虑模式覆盖）
     */
    private fun getEffectiveMode(defaultMode: String): String {
        return modeOverride ?: defaultMode
    }
    
    /**
     * 获取实际使用的接收模式
     * @param defaultMode 转发器默认模式
     * @return 实际使用的接收模式
     */
    private fun getEffectiveRxMode(defaultMode: String): String {
        return rxModeOverride ?: modeOverride ?: defaultMode
    }
    
    /**
     * 获取实际使用的发射模式
     * @param defaultMode 转发器默认模式
     * @return 实际使用的发射模式
     */
    private fun getEffectiveTxMode(defaultMode: String): String {
        return txModeOverride ?: modeOverride ?: defaultMode
    }

    /**
     * 翻转 Data 模式边带：USB-D ↔ LSB-D（用于反向线性转发器）
     * 对非 Data 模式（FM、CW 等）无影响。
     * IC-705 是单工机器，反向转发器上行 LSB-D 对应下行 USB-D，
     * 若两个 VFO 均设 USB-D，发射边带将错误。
     */
    private fun flipDataMode(mode: String): String = when (mode) {
        "USB-D" -> "LSB-D"
        "LSB-D" -> "USB-D"
        else -> mode
    }

    /**
     * 解析上行（发射）模式，考虑反向转发器的边带翻转。
     *
     * 优先级：
     *   1. 用户手动覆盖（txModeOverride / modeOverride）→ 直接使用，不翻转
     *   2. SatNOGS 明确提供 uplinkMode → parseMode 后直接使用
     *   3. SatNOGS 未提供 uplinkMode + 反向转发器 → parseMode(downlink) 后翻转 Data 边带
     *   4. SatNOGS 未提供 uplinkMode + 同向转发器 → parseMode(downlink)，与下行相同
     *
     * 对非 Data 模式（USB、FM、CW 等），flipDataMode 不做任何改变。
     */
    private fun resolveUplinkMode(transmitter: Transmitter): String {
        val userOverride = txModeOverride ?: modeOverride
        if (userOverride != null) return userOverride
        if (transmitter.uplinkMode != null) return parseMode(transmitter.uplinkMode)
        val rxMode = parseMode(transmitter.mode)
        return if (transmitter.invert) flipDataMode(rxMode) else rxMode
    }

    /**
     * 解析上行模式（不考虑用户覆盖，用于停止跟踪时重置电台至卫星本机模式）。
     */
    private fun resolveNativeUplinkMode(transmitter: Transmitter): String {
        if (transmitter.uplinkMode != null) return parseMode(transmitter.uplinkMode)
        val rxMode = parseMode(transmitter.mode)
        return if (transmitter.invert) flipDataMode(rxMode) else rxMode
    }

    /**
     * 发送当前模式命令到电台
     * 用于跟踪时切换模式，或未跟踪时直接设置卫星模式
     */
    suspend fun sendModeCommand() {
        val transmitter = targetTransmitter

        // 获取接收和发射模式
        val rxMode: String
        val txMode: String

        if (transmitter != null) {
            // 有转发器数据时，使用转发器默认模式结合覆盖设置
            // parseMode 将 FT8/FT4 等映射为 USB-D；resolveUplinkMode 对反向转发器翻转 Data 边带
            rxMode = getEffectiveRxMode(parseMode(transmitter.mode))
            txMode = resolveUplinkMode(transmitter)
        } else {
            // 无转发器数据时，直接使用覆盖设置或默认值
            rxMode = rxModeOverride ?: modeOverride ?: "USB"
            txMode = txModeOverride ?: modeOverride ?: "USB"
        }

        LogManager.i(TAG, "发送模式命令 - 接收: $rxMode, 发射: $txMode")
        setSatelliteModes(rxMode, txMode)
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        stopTracking()
        controllerScope.cancel()
    }
}
