package com.bh6aap.ic705Cter.data.radio

import com.bh6aap.ic705Cter.util.LogManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * CIV控制器 - 统一的CIV指令管理器
 * 参考 Look4Sat 实现，使用正确的CIV命令格式
 */
class CivController(private val sppConnector: BluetoothSppConnector) {

    companion object {
        private const val TAG = "CivController"

        // CIV指令代码
        private const val CMD_READ_FREQUENCY: Byte = 0x03  // 查询频率
        private const val CMD_READ_MODE: Byte = 0x04       // 查询模式
        private const val CMD_SET_FREQUENCY: Byte = 0x25   // 设置频率 (带VFO选择)
        private const val CMD_SET_MODE: Byte = 0x26        // 设置模式 (带VFO选择)
        private const val CMD_SET_SPLIT: Byte = 0x0F       // Split模式控制
        private const val CMD_SELECT_VFO: Byte = 0x07      // 选择VFO
        private const val CMD_SEND_CW_MESSAGE: Byte = 0x17 // 发送CW消息
        private const val CMD_SET_DATA_MODE: Byte = 0x1A   // 设置Data模式 (子命令 0x06)
        private const val CMD_STOP_CW: Byte = 0x11          // 停止CW发射

        // 广播命令（电台主动发送）
        private const val CMD_FREQ_BROADCAST_VFO_A: Byte = 0x00  // VFO A频率广播
        private const val CMD_MODE_BROADCAST_VFO_A: Byte = 0x01  // VFO A模式广播
        private const val CMD_FREQ_BROADCAST_VFO_B: Byte = 0x03  // VFO B频率广播
        private const val CMD_MODE_BROADCAST_VFO_B: Byte = 0x04  // VFO B模式广播

        // VFO标识
        const val VFO_A: Byte = 0x00  // VFO A (接收)
        const val VFO_B: Byte = 0x01  // VFO B (发射)

        // IC-705地址
        private const val IC705_ADDRESS: Byte = 0xA4.toByte()

        // 控制器地址
        private const val CONTROLLER_ADDRESS: Byte = 0xE0.toByte()

        // 响应超时时间（毫秒）
        private const val RESPONSE_TIMEOUT_MS = 2000L

        // 模式代码
        const val MODE_LSB: Byte = 0x00
        const val MODE_USB: Byte = 0x01
        const val MODE_AM: Byte = 0x02
        const val MODE_CW: Byte = 0x03
        const val MODE_RTTY: Byte = 0x04
        const val MODE_FM: Byte = 0x05
        const val MODE_CW_R: Byte = 0x07
        const val MODE_RTTY_R: Byte = 0x08
    }

    // 状态管理
    private val _vfoAFrequency = MutableStateFlow("-")
    val vfoAFrequency: StateFlow<String> = _vfoAFrequency.asStateFlow()

    private val _vfoAMode = MutableStateFlow("-")
    val vfoAMode: StateFlow<String> = _vfoAMode.asStateFlow()

    private val _vfoBFrequency = MutableStateFlow("-")
    val vfoBFrequency: StateFlow<String> = _vfoBFrequency.asStateFlow()

    private val _vfoBMode = MutableStateFlow("-")
    val vfoBMode: StateFlow<String> = _vfoBMode.asStateFlow()

    // 等待响应机制
    // @Volatile ensures that writes from the coroutine are visible to the Bluetooth
    // receive thread (a raw Java Thread) that calls processResponse(), and vice versa.
    @Volatile
    private var pendingResponse: CompletableDeferred<ByteArray>? = null
    @Volatile
    private var pendingCommandCode: Byte? = null

    // 响应监听器（用于外部处理CIV响应，如PTT状态管理器）
    private var responseListener: ((ByteArray) -> Unit)? = null

    /**
     * 设置响应监听器
     * @param listener 响应监听器回调
     */
    fun setResponseListener(listener: (ByteArray) -> Unit) {
        responseListener = listener
        LogManager.d(TAG, "设置CIV响应监听器")
    }

    /**
     * 清除响应监听器
     */
    fun clearResponseListener() {
        responseListener = null
        LogManager.d(TAG, "清除CIV响应监听器")
    }

    /**
     * VFO数据类
     */
    data class VfoData(
        val vfo: String,
        val frequency: String,
        val mode: String
    )

    // ==================== 指令构建和发送 ====================

    /**
     * 构建CIV指令数据包
     * 格式: FE FE [电台地址] [控制器地址] [命令] [数据 ...] FD
     */
    private fun buildCivCommand(commandCode: Byte, data: ByteArray = ByteArray(0)): ByteArray {
        val packetLength = 2 + 1 + 1 + 1 + data.size + 1
        val packet = ByteArray(packetLength)

        packet[0] = 0xFE.toByte()
        packet[1] = 0xFE.toByte()
        packet[2] = IC705_ADDRESS
        packet[3] = CONTROLLER_ADDRESS
        packet[4] = commandCode

        if (data.isNotEmpty()) {
            System.arraycopy(data, 0, packet, 5, data.size)
        }

        packet[packetLength - 1] = 0xFD.toByte()

        return packet
    }

    /**
     * 发送CIV指令（不等待响应）
     */
    suspend fun sendCivCommand(commandCode: Int, data: ByteArray = ByteArray(0)): Boolean {
        return try {
            val command = buildCivCommand(commandCode.toByte(), data)
            LogManager.i(TAG, "【CIV】发送指令: ${bytesToHex(command)}")

            val success = sppConnector.sendData(command)
            if (success) {
                LogManager.i(TAG, "【CIV】指令发送成功")
            } else {
                LogManager.e(TAG, "【CIV】指令发送失败")
            }
            success
        } catch (e: Exception) {
            LogManager.e(TAG, "【CIV】发送指令异常", e)
            false
        }
    }

    /**
     * 发送指令并等待响应（同步方式，参考Look4Sat）
     */
    private suspend fun sendCommandAndWaitForResponse(
        commandCode: Byte,
        data: ByteArray = ByteArray(0)
    ): ByteArray? {
        // Cancel any prior in-flight wait; capture the reference locally before
        // calling cancel() to avoid a TOCTOU null-dereference between the read
        // of pendingResponse and the cancel() call.
        pendingResponse?.let { prev ->
            if (!prev.isCompleted) {
                LogManager.w(TAG, "【CIV】清除之前未完成的等待")
                prev.cancel()
            }
        }

        // 创建等待响应的Deferred
        val deferred = CompletableDeferred<ByteArray>()
        pendingResponse = deferred
        pendingCommandCode = commandCode

        LogManager.i(TAG, "【CIV】发送指令 0x${String.format("%02X", commandCode)} 并等待响应")

        return try {
            val command = buildCivCommand(commandCode, data)
            val sendSuccess = sppConnector.sendData(command)

            if (!sendSuccess) {
                LogManager.e(TAG, "【CIV】发送指令失败")
                pendingResponse = null
                pendingCommandCode = null
                return null
            }

            // 等待响应（带超时）
            val response = withTimeoutOrNull(RESPONSE_TIMEOUT_MS) {
                deferred.await()
            }

            pendingResponse = null
            pendingCommandCode = null

            if (response == null) {
                LogManager.e(TAG, "【CIV】等待响应超时")
                return null
            }

            LogManager.i(TAG, "【CIV】收到响应")
            response

        } catch (e: Exception) {
            LogManager.e(TAG, "【CIV】发送指令异常", e)
            pendingResponse = null
            pendingCommandCode = null
            null
        }
    }

    /**
     * 调用过程（发送命令并检查成功响应）
     * 参考 Look4Sat 的 callProcedure 实现
     */
    private suspend fun callProcedure(commandCode: Byte, subCommand: Byte? = null, data: ByteArray? = null): Boolean {
        val fullData = if (subCommand != null) {
            if (data != null) byteArrayOf(subCommand) + data else byteArrayOf(subCommand)
        } else {
            data ?: ByteArray(0)
        }

        val response = sendCommandAndWaitForResponse(commandCode, fullData)
        return response != null && response.size >= 6 && response[4] == 0xFB.toByte()
    }

    /**
     * 调用函数（发送命令并返回数据）
     * 参考 Look4Sat 的 callFunction 实现
     */
    private suspend fun callFunction(commandCode: Byte, subCommand: Byte? = null): ByteArray? {
        val data = subCommand?.let { byteArrayOf(it) } ?: ByteArray(0)
        val response = sendCommandAndWaitForResponse(commandCode, data)

        return if (response != null && response.size > 6) {
            // 提取数据部分（去掉头部5字节和尾部1字节）
            response.copyOfRange(5, response.size - 1)
        } else null
    }

    /**
     * 发送原始CIV命令
     * 用于发送完整的CIV帧（包含帧头、地址、命令、数据、结束符）
     * @param command 完整的CIV命令字节数组
     * @return 是否发送成功
     */
    suspend fun sendRawCommand(command: ByteArray): Boolean {
        return try {
            LogManager.i(TAG, "【CIV】发送原始命令: ${command.joinToString(" ") { String.format("%02X", it) }}")
            sppConnector.sendData(command)
        } catch (e: Exception) {
            LogManager.e(TAG, "【CIV】发送原始命令失败", e)
            false
        }
    }

    // ==================== 频率设置（参考Look4Sat） ====================

    /**
     * 设置指定VFO的频率
     * 参考 Look4Sat: setFrequency(Main: Boolean, Frequency: Long)
     * 命令: 0x25, 子命令: 0x00=VFO A, 0x01=VFO B, 数据: BCD频率(5字节,小端序)
     */
    suspend fun setVfoFrequency(vfo: Byte, frequencyHz: Long): Boolean {
        val vfoName = if (vfo == VFO_A) "VFO A" else "VFO B"
        // Reject obviously out-of-range values that may originate from a NaN/Infinity
        // Doppler calculation converting to Long (yields Long.MAX_VALUE) or from a
        // malformed BCD response.  IC-705 covers 30 kHz to 199.999999 MHz on most
        // bands; the absolute hardware limit is below 2 GHz.  Reject anything above
        // 10 GHz as physically impossible.
        if (frequencyHz <= 0L || frequencyHz > 10_000_000_000L) {
            LogManager.e(TAG, "【CIV】频率超出有效范围 ($frequencyHz Hz)，拒绝发送到电台")
            return false
        }
        LogManager.i(TAG, "【CIV】设置 $vfoName 频率: ${frequencyHz / 1000000.0} MHz")

        val freqBcd = frequencyToBcd(frequencyHz)
        return callProcedure(CMD_SET_FREQUENCY, vfo, freqBcd.reversedArray())
    }

    /**
     * 设置VFO A频率（接收）
     */
    suspend fun setVfoAFrequency(frequencyHz: Long): Boolean {
        return setVfoFrequency(VFO_A, frequencyHz)
    }

    /**
     * 设置VFO B频率（发射）
     */
    suspend fun setVfoBFrequency(frequencyHz: Long): Boolean {
        return setVfoFrequency(VFO_B, frequencyHz)
    }

    /**
     * 获取当前VFO的频率
     * 使用 0x03 命令查询频率（不带VFO选择，查询当前选中的VFO）
     * 命令格式: FE FE A4 E0 03 FD
     */
    suspend fun getCurrentVfoFrequency(): Long {
        val data = callFunction(CMD_READ_FREQUENCY, null)  // 不传递子命令
        return if (data != null && data.size >= 5) {
            bcdToFrequency(data.reversedArray())
        } else -1
    }

    /**
     * 获取指定VFO的频率
     * 先选择VFO，再查询频率
     */
    suspend fun getVfoFrequency(vfo: Byte): Long {
        // 先选择VFO
        if (!selectVfo(vfo)) {
            LogManager.w(TAG, "选择VFO ${if (vfo == VFO_A) "A" else "B"} 失败")
            return -1
        }
        delay(50) // 等待VFO切换
        // 查询当前VFO频率
        return getCurrentVfoFrequency()
    }

    /**
     * 查询VFO A和VFO B的完整数据
     * 兼容旧API
     */
    suspend fun queryBothVfos(): Pair<VfoData, VfoData> {
        LogManager.i(TAG, "【CIV】开始查询VFO A和VFO B数据")

        // 查询VFO A频率和模式
        val freqAHz = getVfoFrequency(VFO_A)
        val freqA = if (freqAHz > 0) String.format("%.6f", freqAHz / 1000000.0) else "-"
        val modeA = _vfoAMode.value
        val vfoAData = VfoData("A", freqA, modeA)
        LogManager.i(TAG, "【CIV】VFO A数据: $vfoAData")

        // 查询VFO B频率和模式
        val freqBHz = getVfoFrequency(VFO_B)
        val freqB = if (freqBHz > 0) String.format("%.6f", freqBHz / 1000000.0) else "-"
        val modeB = _vfoBMode.value
        val vfoBData = VfoData("B", freqB, modeB)
        LogManager.i(TAG, "【CIV】VFO B数据: $vfoBData")

        LogManager.i(TAG, "【CIV】VFO查询完成")
        return Pair(vfoAData, vfoBData)
    }

    // ==================== 模式设置（参考Look4Sat） ====================

    /**
     * 设置指定VFO的模式
     * 参考 Look4Sat: setMode(Main: Boolean, mode: String)
     * 命令: 0x26, 子命令: 0x00=VFO A, 0x01=VFO B
     * 数据: [模式码, 滤波器, 数据模式]
     */
    suspend fun setVfoMode(vfo: Byte, mode: Byte, filter: Byte = 0x00, dataMode: Byte = 0x01): Boolean {
        val vfoName = if (vfo == VFO_A) "VFO A" else "VFO B"
        val modeName = getModeName(mode)
        LogManager.i(TAG, "【CIV】设置 $vfoName 模式: $modeName")

        val modeData = byteArrayOf(mode, filter, dataMode)
        return callProcedure(CMD_SET_MODE, vfo, modeData)
    }

    /**
     * 设置VFO A模式
     */
    suspend fun setVfoAMode(mode: Byte): Boolean {
        return setVfoMode(VFO_A, mode)
    }

    /**
     * 设置VFO B模式
     */
    suspend fun setVfoBMode(mode: Byte): Boolean {
        return setVfoMode(VFO_B, mode)
    }

    /**
     * 根据字符串设置模式
     * 支持带 -D 后缀的 Data 模式 (如 USB-D, LSB-D)
     */
    suspend fun setVfoModeString(vfo: Byte, modeStr: String): Boolean {
        val upperMode = modeStr.uppercase()
        val isDataMode = upperMode.endsWith("-D")
        val baseMode = if (isDataMode) upperMode.substring(0, upperMode.length - 2) else upperMode

        val modeCode = when (baseMode) {
            "LSB" -> MODE_LSB
            "USB" -> MODE_USB
            "AM" -> MODE_AM
            "CW" -> MODE_CW
            "RTTY" -> MODE_RTTY
            "FM" -> MODE_FM
            "CW-R" -> MODE_CW_R
            "RTTY-R" -> MODE_RTTY_R
            else -> MODE_USB
        }

        // 先设置基本模式
        val result = setVfoMode(vfo, modeCode)

        // 如果是 Data 模式，使用 0x26 命令设置 Data 模式
        // USB-D: FE FE A4 E0 26 00 01 01 01 FD (VFO A, USB + Data ON + FIL1)
        // LSB-D: FE FE A4 E0 26 01 00 01 01 FD (VFO B, LSB + Data ON + FIL1)
        if (isDataMode) {
            delay(50)
            setDataMode(vfo, modeCode, true)
        }

        return result
    }

    /**
     * 设置 Data 模式 (使用 0x26 命令)
     * 格式: FE FE A4 E0 26 [VFO] [模式] [Data] [滤波器] FD
     * USB-D: FE FE A4 E0 26 00 01 01 01 FD (VFO A, USB + Data ON + FIL1)
     * LSB-D: FE FE A4 E0 26 01 00 01 01 FD (VFO B, LSB + Data ON + FIL1)
     * @param vfo VFO选择 (0x00=VFO A, 0x01=VFO B)
     * @param mode 模式代码 (0x00=LSB, 0x01=USB)
     * @param enabled true=开启Data模式(0x01), false=关闭Data模式(0x00)
     */
    suspend fun setDataMode(vfo: Byte, mode: Byte, enabled: Boolean): Boolean {
        val vfoName = if (vfo == VFO_A) "VFO A" else "VFO B"
        val modeName = if (mode == MODE_USB) "USB" else "LSB"
        val state = if (enabled) "开启" else "关闭"
        LogManager.i(TAG, "【CIV】设置 $vfoName $modeName-D Data模式: $state")

        // Data 模式命令: 0x26 [VFO] [模式] [DataOn/Off] [滤波器]
        // Data: 0x01=ON, 0x00=OFF
        // 滤波器: 0x01=FIL1, 0x02=FIL2, 0x03=FIL3
        val dataState = if (enabled) 0x01.toByte() else 0x00.toByte()
        val filter: Byte = 0x01  // FIL1
        val data = byteArrayOf(vfo, mode, dataState, filter)

        return callProcedure(CMD_SET_MODE, null, data)
    }

    // ==================== VFO选择和Split模式 ====================

    /**
     * 选择VFO
     * 参考 Look4Sat: setVFO(A: Boolean)
     * 命令: 0x07, 数据: 0x00=VFO A, 0x01=VFO B
     */
    suspend fun selectVfo(vfo: Byte): Boolean {
        LogManager.i(TAG, "【CIV】选择 VFO ${if (vfo == VFO_A) "A" else "B"}")
        return callProcedure(CMD_SELECT_VFO, vfo)
    }

    /**
     * 设置Split模式
     * 参考 Look4Sat: setSplit(On: Boolean)
     * 命令: 0x0F, 数据: 0x00=关闭, 0x01=开启
     */
    suspend fun setSplitMode(enable: Boolean): Boolean {
        LogManager.i(TAG, "【CIV】设置 Split 模式: ${if (enable) "开启" else "关闭"}")
        return callProcedure(CMD_SET_SPLIT, if (enable) 0x01 else 0x00)
    }

    /**
     * 读取Split模式状态
     * 命令: 0x0F, 子命令: 0x00=读取Split OFF状态, 0x01=读取Split ON状态
     * 响应: FE FE E0 A4 0F 00 FD (Split OFF) 或 FE FE E0 A4 0F 01 FD (Split ON)
     * @return true=Split ON, false=Split OFF, null=读取失败
     */
    suspend fun readSplitMode(): Boolean? {
        LogManager.i(TAG, "【CIV】读取 Split 模式状态")

        // 发送读取Split ON状态的命令 (子命令 0x01)
        val response = sendCommandAndWaitForResponse(CMD_SET_SPLIT, byteArrayOf(0x01))

        return if (response != null && response.size >= 6) {













            // 检查响应是否包含Split状态数据
            // 响应格式: FE FE E0 A4 0F [状态] FD
            // 状态字节: 0x00=OFF, 0x01=ON
            val statusByte = response.getOrNull(5)
            val isSplitOn = when (statusByte?.toInt()) {
                0x01 -> {
                    LogManager.i(TAG, "【CIV】Split 模式状态: ON")
                    true
                }
                0x00 -> {
                    LogManager.i(TAG, "【CIV】Split 模式状态: OFF")
                    false
                }
                else -> {
                    // 如果读取Split ON返回失败，尝试读取Split OFF确认 (子命令 0x00)
                    val offResponse = sendCommandAndWaitForResponse(CMD_SET_SPLIT, byteArrayOf(0x00))
                    if (offResponse != null && offResponse.size >= 6) {
                        val offStatus = offResponse.getOrNull(5)?.toInt()
                        if (offStatus == 0x00) {
                            LogManager.i(TAG, "【CIV】Split 模式状态: OFF (通过OFF命令确认)")
                            false
                        } else {
                            LogManager.w(TAG, "【CIV】无法确定Split状态，返回null")
                            null
                        }
                    } else {
                        LogManager.w(TAG, "【CIV】读取Split状态失败，返回null")
                        null
                    }
                }
            }
            isSplitOn
        } else {
            LogManager.e(TAG, "【CIV】读取Split状态无响应或响应格式错误")
            null
        }
    }

    /**
     * 确保Split模式开启
     * 先读取当前状态，如果未开启则发送开启命令
     * @return true=Split已开启(或成功开启), false=操作失败
     */
    suspend fun ensureSplitModeOn(): Boolean {
        LogManager.i(TAG, "【CIV】确保Split模式开启")

        // 首先读取当前Split状态
        val currentStatus = readSplitMode()

        return when (currentStatus) {
            true -> {
                // Split已经开启
                LogManager.i(TAG, "【CIV】Split模式已经是开启状态")
                true
            }
            false -> {
                // Split未开启，发送开启命令
                LogManager.i(TAG, "【CIV】Split模式未开启，正在发送开启命令...")
                val success = setSplitMode(true)
                if (success) {
                    LogManager.i(TAG, "【CIV】Split模式开启成功")
                } else {
                    LogManager.e(TAG, "【CIV】Split模式开启失败")
                }
                success
            }
            null -> {
                // 读取失败，尝试直接开启
                LogManager.w(TAG, "【CIV】无法读取Split状态，尝试直接开启...")
                val success = setSplitMode(true)
                if (success) {
                    LogManager.i(TAG, "【CIV】Split模式开启命令已发送")
                } else {
                    LogManager.e(TAG, "【CIV】Split模式开启失败")
                }
                success
            }
        }
    }

    /**
     * 确保Split模式关闭
     * 断开连接前调用，先读取当前状态，如果已开启则发送关闭命令
     * @return true=Split已关闭(或成功关闭), false=操作失败
     */
    suspend fun ensureSplitModeOff(): Boolean {
        LogManager.i(TAG, "【CIV】确保Split模式关闭")

        // 首先读取当前Split状态
        val currentStatus = readSplitMode()

        return when (currentStatus) {
            false -> {
                // Split已经关闭
                LogManager.i(TAG, "【CIV】Split模式已经是关闭状态")
                true
            }
            true -> {
                // Split已开启，发送关闭命令
                LogManager.i(TAG, "【CIV】Split模式已开启，正在发送关闭命令...")
                val success = setSplitMode(false)
                if (success) {
                    LogManager.i(TAG, "【CIV】Split模式关闭成功")
                } else {
                    LogManager.e(TAG, "【CIV】Split模式关闭失败")
                }
                success
            }
            null -> {
                // 读取失败，尝试直接关闭
                LogManager.w(TAG, "【CIV】无法读取Split状态，尝试直接关闭...")
                val success = setSplitMode(false)
                if (success) {
                    LogManager.i(TAG, "【CIV】Split模式关闭命令已发送")
                } else {
                    LogManager.e(TAG, "【CIV】Split模式关闭失败")
                }
                success
            }
        }
    }

    // ==================== 响应处理 ====================

    /**
     * 处理从电台接收到的CIV指令
     */
    fun processResponse(response: ByteArray) {
        if (response.size < 6) return

        val targetAddress = response[2]
        val sourceAddress = response[3]
        val commandCode = response[4]

        // 解码并记录CIV响应
        val responseType = when (commandCode) {
            0xFB.toByte() -> "成功响应"
            0xFA.toByte() -> "失败响应"
            CMD_FREQ_BROADCAST_VFO_A -> "VFO A频率广播"
            CMD_MODE_BROADCAST_VFO_A -> "VFO A模式广播"
            CMD_FREQ_BROADCAST_VFO_B -> "VFO B频率广播"
            CMD_MODE_BROADCAST_VFO_B -> "VFO B模式广播"
            CMD_READ_FREQUENCY -> "频率查询响应"
            CMD_READ_MODE -> "模式查询响应"
            CMD_SET_FREQUENCY -> "频率设置响应"
            CMD_SET_MODE -> "模式设置响应"
            CMD_SET_SPLIT -> "Split设置响应"
            CMD_SELECT_VFO -> "VFO选择响应"
            else -> "未知命令(0x${String.format("%02X", commandCode)})"
        }

        val sourceName = when (sourceAddress) {
            IC705_ADDRESS -> "IC-705"
            CONTROLLER_ADDRESS -> "控制器"
            else -> "未知(0x${String.format("%02X", sourceAddress)})"
        }

        val targetName = when (targetAddress) {
            CONTROLLER_ADDRESS -> "控制器"
            0x00.toByte() -> "广播"
            else -> "未知(0x${String.format("%02X", targetAddress)})"
        }

        LogManager.i(TAG, "【CIV解码】$responseType | 源: $sourceName -> 目标: $targetName | 数据: ${bytesToHex(response)}")

        // 检查是否是发给控制器的响应
        if (targetAddress != CONTROLLER_ADDRESS && targetAddress != 0x00.toByte()) {
            return
        }

        // Capture both volatile fields into local variables so we do a single
        // consistent read of each; avoids the TOCTOU window between the null
        // check and the .complete() call when the receive thread and a coroutine
        // access these fields concurrently.
        val pending = pendingResponse
        val expected = pendingCommandCode
        if (pending != null && !pending.isCompleted && expected != null) {
            if (commandCode == 0xFB.toByte() || commandCode == expected) {
                pending.complete(response)
                return
            }
        }

        // 处理广播消息
        when (commandCode) {
            CMD_FREQ_BROADCAST_VFO_A -> updateVfoAFrequency(response)
            CMD_MODE_BROADCAST_VFO_A -> updateVfoAMode(response)
            CMD_FREQ_BROADCAST_VFO_B -> updateVfoBFrequency(response)
            CMD_MODE_BROADCAST_VFO_B -> updateVfoBMode(response)
        }

        // 调用响应监听器（用于外部处理，如PTT状态管理器）
        responseListener?.invoke(response)
    }

    // ==================== 内部更新方法 ====================

    private fun updateVfoAFrequency(response: ByteArray) {
        if (response.size >= 11) {
            val freqData = response.copyOfRange(5, 10)
            val freqHz = bcdToFrequency(freqData.reversedArray())
            if (freqHz < 0L) {
                LogManager.w(TAG, "【CIV解码】VFO A频率BCD数据无效，忽略")
                return
            }
            val freqStr = String.format("%.6f", freqHz / 1000000.0)
            _vfoAFrequency.value = freqStr
            LogManager.i(TAG, "【CIV解码】VFO A频率更新: ${freqStr}MHz")
        }
    }

    private fun updateVfoAMode(response: ByteArray) {
        if (response.size >= 7) {
            val modeCode = response[5]
            val modeName = getModeName(modeCode)
            _vfoAMode.value = modeName
            LogManager.i(TAG, "【CIV解码】VFO A模式更新: $modeName (0x${String.format("%02X", modeCode)})")
        }
    }

    private fun updateVfoBFrequency(response: ByteArray) {
        if (response.size >= 11) {
            val freqData = response.copyOfRange(5, 10)
            val freqHz = bcdToFrequency(freqData.reversedArray())
            if (freqHz < 0L) {
                LogManager.w(TAG, "【CIV解码】VFO B频率BCD数据无效，忽略")
                return
            }
            val freqStr = String.format("%.6f", freqHz / 1000000.0)
            _vfoBFrequency.value = freqStr
            LogManager.i(TAG, "【CIV解码】VFO B频率更新: ${freqStr}MHz")
        }
    }

    private fun updateVfoBMode(response: ByteArray) {
        if (response.size >= 7) {
            val modeCode = response[5]
            val modeName = getModeName(modeCode)
            _vfoBMode.value = modeName
            LogManager.i(TAG, "【CIV解码】VFO B模式更新: $modeName (0x${String.format("%02X", modeCode)})")
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 将频率转换为BCD格式（5字节）
     * 参考 Look4Sat: frequencyToBCD
     */
    private fun frequencyToBcd(frequencyHz: Long): ByteArray {
        var n = frequencyHz
        val bcd = ByteArray(5)
        for (i in 4 downTo 0) {
            val twoDigits = (n % 100).toInt()
            bcd[i] = (((twoDigits / 10) shl 4) or (twoDigits % 10)).toByte()
            n /= 100
        }
        return bcd
    }

    /**
     * 将BCD格式转换为频率
     * 参考 Look4Sat: bcdToFrequency
     */
    /**
     * Decode a 5-byte little-endian BCD frequency value.
     * Returns -1L when any nibble is outside 0-9, which signals the caller to
     * discard the frame rather than propagate a garbage frequency value into the
     * UI or back to the radio.
     *
     * Trigger scenario: a rogue Bluetooth device (or bit-corrupted SPP stream)
     * sends 0xFF bytes in the frequency field; without this check the result
     * would be 9_999_999_999 Hz (≈ 10 GHz) which setVfoFrequency() would then
     * attempt to encode back into BCD and transmit to the IC-705.
     */
    private fun bcdToFrequency(bcd: ByteArray): Long {
        var frequency = 0L
        for (byte in bcd) {
            val high = (byte.toInt() shr 4) and 0x0F
            val low = byte.toInt() and 0x0F
            if (high > 9 || low > 9) {
                LogManager.w(TAG, "【CIV】BCD解析错误: 无效数字 high=$high low=$low (0x${String.format("%02X", byte)})")
                return -1L
            }
            frequency = frequency * 100 + high * 10 + low
        }
        return frequency
    }

    /**
     * 获取模式名称
     */
    private fun getModeName(mode: Byte): String {
        return when (mode) {
            MODE_LSB -> "LSB"
            MODE_USB -> "USB"
            MODE_AM -> "AM"
            MODE_CW -> "CW"
            MODE_RTTY -> "RTTY"
            MODE_FM -> "FM"
            MODE_CW_R -> "CW-R"
            MODE_RTTY_R -> "RTTY-R"
            else -> "未知"
        }
    }

    /**
     * 字节数组转十六进制字符串
     */
    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { String.format("%02X", it) }
    }

    // ==================== CW消息发送 ====================

    /**
     * 发送CW消息
     * @param message CW消息（最多30个字符）
     * @return 是否发送成功
     */
    suspend fun sendCwMessage(message: String): Boolean {
        if (message.isEmpty()) {
            LogManager.w(TAG, "【CIV】CW消息为空")
            return false
        }

        if (message.length > 30) {
            LogManager.w(TAG, "【CIV】CW消息超过30字符限制，将被截断")
        }

        // 转换字符为CIV编码
        val cwData = message.take(30).mapNotNull { charToCwCode(it) }.toByteArray()

        if (cwData.isEmpty()) {
            LogManager.w(TAG, "【CIV】没有有效的CW字符")
            return false
        }

        LogManager.i(TAG, "【CIV】发送CW消息: '$message'")
        return sendCivCommand(CMD_SEND_CW_MESSAGE.toInt(), cwData)
    }

    /**
     * 停止CW发送
     * @return 是否发送成功
     */
    suspend fun stopCwMessage(): Boolean {
        LogManager.i(TAG, "【CIV】停止CW发送")
        return sendCivCommand(CMD_SEND_CW_MESSAGE.toInt(), byteArrayOf(0xFF.toByte()))
    }

    /**
     * 停止CW发射（使用固定停止发射帧）
     * 帧内容：FE FE E0 A4 17 FF FD
     * 强制执行停止CW发送
     * @return 是否发送成功
     */
    suspend fun stopCwTransmission(): Boolean {
        LogManager.i(TAG, "【CIV】停止CW发射（固定帧）")
        // 构建固定停止发射帧
        val stopFrame = byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), // 前缀
            0xA4.toByte(), 0xE0.toByte(), // 电台地址 -> 控制器地址
            0x17.toByte(),              // 发送CW消息命令
            0xFF.toByte(),              // 停止发送CW消息
            0xFD.toByte()               // 后缀
        )
        
        return try {
            LogManager.i(TAG, "【CIV】发送停止发射帧: ${bytesToHex(stopFrame)}")
            val success = sppConnector.sendData(stopFrame)
            if (success) {
                LogManager.i(TAG, "【CIV】停止发射帧发送成功")
            } else {
                LogManager.e(TAG, "【CIV】停止发射帧发送失败")
            }
            success
        } catch (e: Exception) {
            LogManager.e(TAG, "【CIV】发送停止发射帧异常", e)
            false
        }
    }

    /**
     * 将CW字符转换为CIV协议编码
     * @param char 字符
     * @return 编码字节，不支持则返回null
     */
    private fun charToCwCode(char: Char): Byte? {
        return when (char) {
            in '0'..'9' -> (0x30 + (char - '0')).toByte()
            in 'A'..'Z' -> (0x41 + (char - 'A')).toByte()
            in 'a'..'z' -> (0x61 + (char - 'a')).toByte()
            '/' -> 0x2F.toByte()
            '?' -> 0x3F.toByte()
            '.' -> 0x2E.toByte()
            '-' -> 0x2D.toByte()
            ',' -> 0x2C.toByte()
            ';' -> 0x3A.toByte()
            '\'' -> 0x27.toByte()
            '(' -> 0x28.toByte()
            ')' -> 0x29.toByte()
            '=' -> 0x3D.toByte()
            '+' -> 0x2B.toByte()
            '"' -> 0x22.toByte()
            '@' -> 0x40.toByte()
            ' ' -> 0x20.toByte()
            '^' -> 0x5E.toByte() // 连续发送标记
            else -> {
                LogManager.w(TAG, "【CIV】不支持的CW字符: '$char'")
                null
            }
        }
    }
}
