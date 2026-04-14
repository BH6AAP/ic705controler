package com.bh6aap.ic705Cter.data.radio

import android.bluetooth.BluetoothDevice
import com.bh6aap.ic705Cter.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 蓝牙连接管理器
 * 管理与ICOM电台的蓝牙连接状态
 */
class BluetoothConnectionManager private constructor() {

    companion object {
        private const val TAG = "BluetoothConnectionManager"

        // Volatile ensures the JVM memory model guarantees visibility of the write
        // across all threads; double-checked locking removes the lock overhead on
        // the hot path after initialisation.
        @Volatile
        private var instance: BluetoothConnectionManager? = null

        fun getInstance(): BluetoothConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: BluetoothConnectionManager().also { instance = it }
            }
        }
    }

    // 协程作用域
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 状态管理
    private val _currentConnector = MutableStateFlow<BluetoothSppConnector?>(null)
    val currentConnector: StateFlow<BluetoothSppConnector?> = _currentConnector.asStateFlow()

    private val _connectionState = MutableStateFlow<BluetoothSppConnector.ConnectionState>(
        BluetoothSppConnector.ConnectionState.DISCONNECTED
    )
    val connectionState: StateFlow<BluetoothSppConnector.ConnectionState> = _connectionState.asStateFlow()

    // 统一的CIV控制器
    private val _civController = MutableStateFlow<CivController?>(null)
    val civController: StateFlow<CivController?> = _civController.asStateFlow()

    // 频率和模式状态
    private val _vfoAFrequency = MutableStateFlow("-")
    val vfoAFrequency: StateFlow<String> = _vfoAFrequency.asStateFlow()

    private val _vfoAMode = MutableStateFlow("-")
    val vfoAMode: StateFlow<String> = _vfoAMode.asStateFlow()

    private val _vfoBFrequency = MutableStateFlow("-")
    val vfoBFrequency: StateFlow<String> = _vfoBFrequency.asStateFlow()

    private val _vfoBMode = MutableStateFlow("-")
    val vfoBMode: StateFlow<String> = _vfoBMode.asStateFlow()

    // VFO状态跟踪
    var currentVfo = "A"

    /**
     * 连接到蓝牙设备
     * @param device 蓝牙设备
     * @return 是否开始连接
     */
    suspend fun connectToDevice(device: BluetoothDevice): Boolean {
        LogManager.i(TAG, "【蓝牙连接】开始连接到设备: ${device.name} (${device.address})")

        // 断开现有连接
        disconnect()

        try {
            // 创建新的SPP连接器
            val connector = BluetoothSppConnector(device)

            // 创建统一的CIV控制器
            val civController = CivController(connector)
            _civController.value = civController

            // 启动状态同步
            startStateSync(civController)

            // 设置回调
            connector.setCallback(object : BluetoothSppConnector.Callback {
                override fun onDataReceived(data: ByteArray) {
                    LogManager.d(TAG, "【蓝牙连接】收到数据: ${bytesToHex(data)}")
                }

                override fun onCivCommandReceived(command: ByteArray) {
                    LogManager.d(TAG, "【蓝牙连接】收到CIV指令: ${bytesToHex(command)}")

                    // 统一交给CivController处理响应
                    civController.processResponse(command)
                }

                override fun onConnectionStateChanged(state: BluetoothSppConnector.ConnectionState) {
                    LogManager.i(TAG, "【蓝牙连接】连接状态变化: $state")
                    _connectionState.value = state
                    
                    // 如果检测到断开连接，清理资源
                    if (state == BluetoothSppConnector.ConnectionState.DISCONNECTED) {
                        LogManager.i(TAG, "【蓝牙连接】检测到连接断开，清理资源")
                        coroutineScope.launch {
                            cleanupConnection()
                        }
                    }
                }
            })

            // 连接到设备
            val success = connector.connect()

            if (success) {
                LogManager.i(TAG, "【蓝牙连接】连接成功")
                _currentConnector.value = connector

                // 连接成功后，检查并设置Split模式
                coroutineScope.launch {
                    delay(500) // 等待连接稳定
                    ensureSplitModeEnabled()
                }
            } else {
                LogManager.e(TAG, "【蓝牙连接】连接失败")
                _civController.value = null
            }

            return success
        } catch (e: Exception) {
            LogManager.e(TAG, "【蓝牙连接】连接异常", e)
            _civController.value = null
            return false
        }
    }

    /**
     * 启动状态同步，将CivController的状态同步到BluetoothConnectionManager
     * 优化：合并到一个coroutine中，减少资源占用
     */
    private fun startStateSync(civController: CivController) {
        coroutineScope.launch {
            // 使用combine合并多个flow，减少同时运行的coroutine数量
            kotlinx.coroutines.flow.combine(
                civController.vfoAFrequency,
                civController.vfoAMode,
                civController.vfoBFrequency,
                civController.vfoBMode
            ) { vfoAFreq, vfoAMode, vfoBFreq, vfoBMode ->
                // 批量更新状态
                if (_vfoAFrequency.value != vfoAFreq) {
                    _vfoAFrequency.value = vfoAFreq
                }
                if (_vfoAMode.value != vfoAMode) {
                    _vfoAMode.value = vfoAMode
                }
                if (_vfoBFrequency.value != vfoBFreq) {
                    _vfoBFrequency.value = vfoBFreq
                }
                if (_vfoBMode.value != vfoBMode) {
                    _vfoBMode.value = vfoBMode
                }
            }.collect { }
        }
    }

    /**
     * 断开与当前设备的连接
     * 先关闭Split模式，等待确认后再断开蓝牙连接
     */
    suspend fun disconnect() {
        LogManager.i(TAG, "【蓝牙连接】开始断开连接流程")

        try {
            // 先关闭Split模式
            val controller = _civController.value
            if (controller != null) {
                LogManager.i(TAG, "【蓝牙连接】正在关闭Split模式...")
                val splitClosed = controller.ensureSplitModeOff()
                if (splitClosed) {
                    LogManager.i(TAG, "【蓝牙连接】Split模式已关闭")
                } else {
                    LogManager.w(TAG, "【蓝牙连接】Split模式关闭失败或超时，继续断开连接")
                }
                // 短暂延迟确保命令发送完成
                delay(200)
            }

            // 断开蓝牙连接
            _currentConnector.value?.disconnect()
            cleanupConnection()
            LogManager.i(TAG, "【蓝牙连接】断开成功")
        } catch (e: Exception) {
            LogManager.e(TAG, "【蓝牙连接】断开连接失败", e)
        }
    }

    /**
     * 断开与当前设备的连接（非挂起版本，用于不需要等待Split关闭的场景）
     */
    fun disconnectSync() {
        LogManager.i(TAG, "【蓝牙连接】立即断开连接")

        try {
            _currentConnector.value?.disconnect()
            cleanupConnection()
            LogManager.i(TAG, "【蓝牙连接】断开成功")
        } catch (e: Exception) {
            LogManager.e(TAG, "【蓝牙连接】断开连接失败", e)
        }
    }

    /**
     * 清理连接资源
     * 当检测到连接断开时调用
     */
    private fun cleanupConnection() {
        LogManager.i(TAG, "【蓝牙连接】清理连接资源")

        // 清理连接器引用
        _currentConnector.value = null
        _civController.value = null

        // 重置频率和模式状态
        _vfoAFrequency.value = "-"
        _vfoAMode.value = "-"
        _vfoBFrequency.value = "-"
        _vfoBMode.value = "-"

        LogManager.i(TAG, "【蓝牙连接】资源清理完成")
    }

    /**
     * 发送CIV指令
     * @param commandCode 指令代码
     * @param data 数据（可选）
     * @return 是否发送成功
     */
    suspend fun sendCivCommand(commandCode: Int, data: ByteArray = ByteArray(0)): Boolean {
        return _civController.value?.sendCivCommand(commandCode, data) ?: false
    }

    /**
     * 设置Split模式
     * @param enable 是否开启
     * @return 是否操作成功
     */
    suspend fun setSplitMode(enable: Boolean): Boolean {
        return _civController.value?.setSplitMode(enable) ?: false
    }

    /**
     * 读取Split模式状态
     * @return true=Split ON, false=Split OFF, null=读取失败
     */
    suspend fun readSplitMode(): Boolean? {
        return _civController.value?.readSplitMode()
    }

    /**
     * 确保Split模式已开启
     * 连接成功后调用，读取当前状态，如果未开启则自动开启
     * @return true=Split已开启(或成功开启), false=操作失败
     */
    suspend fun ensureSplitModeEnabled(): Boolean {
        LogManager.i(TAG, "【蓝牙连接】检查并确保Split模式开启")

        val controller = _civController.value
        if (controller == null) {
            LogManager.e(TAG, "【蓝牙连接】CivController未初始化，无法检查Split状态")
            return false
        }

        return controller.ensureSplitModeOn()
    }

    /**
     * 查询VFO A和VFO B的完整数据
     * @return Pair<VfoData(A), VfoData(B)>
     */
    suspend fun queryBothVfos(): Pair<CivController.VfoData, CivController.VfoData>? {
        return _civController.value?.queryBothVfos()
    }

    /**
     * 设置卫星跟踪模式
     * @param rxMode 接收模式 (VFO A) - 如: "USB", "LSB", "CW", "FM"
     * @param txMode 发射模式 (VFO B) - 如: "USB", "LSB", "CW", "FM"
     * @return 是否设置成功
     */
    suspend fun setSatelliteModes(rxMode: String, txMode: String): Boolean {
        val controller = _civController.value
        if (controller == null) {
            LogManager.e(TAG, "【蓝牙连接】设置卫星模式失败: CivController未初始化")
            return false
        }
        
        LogManager.i(TAG, "【蓝牙连接】设置卫星模式 - 接收: $rxMode, 发射: $txMode")
        
        // 设置VFO A模式 (接收)
        val rxSuccess = controller.setVfoModeString(CivController.VFO_A, rxMode)
        if (!rxSuccess) {
            LogManager.e(TAG, "【蓝牙连接】设置接收模式失败: $rxMode")
            return false
        }
        
        kotlinx.coroutines.delay(50) // 短暂延迟
        
        // 设置VFO B模式 (发射)
        val txSuccess = controller.setVfoModeString(CivController.VFO_B, txMode)
        if (!txSuccess) {
            LogManager.e(TAG, "【蓝牙连接】设置发射模式失败: $txMode")
            return false
        }
        
        LogManager.i(TAG, "【蓝牙连接】卫星模式设置成功")
        return true
    }

    /**
     * 检查是否已连接
     * @return 是否已连接
     */
    fun isConnected(): Boolean {
        return _currentConnector.value?.isConnected() ?: false
    }

    /**
     * 获取当前连接状态
     * @return 连接状态
     */
    fun getConnectionState(): BluetoothSppConnector.ConnectionState {
        return _connectionState.value
    }

    /**
     * 将字节数组转换为十六进制字符串
     */
    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = "0123456789ABCDEF"[v ushr 4]
            hexChars[i * 2 + 1] = "0123456789ABCDEF"[v and 0x0F]
        }
        return String(hexChars)
    }
}
