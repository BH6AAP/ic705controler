package com.bh6aap.ic705Cter.data.radio

import com.bh6aap.ic705Cter.util.LogManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * CIV指令管理器
 * 负责CIV指令的构建、发送和接收
 */
class CivCommandManager(private val sppConnector: BluetoothSppConnector) {

    companion object {
        private const val TAG = "CivCommandManager"

        // CIV指令代码 - 根据IC-705文档修正
        private const val CMD_READ_FREQUENCY: Byte = 0x25  // 查询频率
        private const val CMD_READ_MODE: Byte = 0x26       // 查询模式
        private const val CMD_SET_MODE: Byte = 0x26        // 设置模式
        private const val CMD_SET_SPLIT: Byte = 0x0F       // Split模式控制
        private const val CMD_READ_VFO_STATUS: Byte = 0x26 // VFO状态
        private const val CMD_SEND_CW_MESSAGE: Byte = 0x17 // 发送CW消息
        
        // 模式代码映射 (IC-705 CIV协议)
        private val MODE_MAP = mapOf(
            "LSB" to 0x00,
            "USB" to 0x01,
            "AM" to 0x02,
            "CW" to 0x03,
            "CW-R" to 0x07,
            "FM" to 0x05,
            "DV" to 0x17,
            "RTTY" to 0x04,
            "RTTY-R" to 0x08,
            "DATA" to 0x06,
            "DATA-R" to 0x09
        )

        // VFO标识
        private const val VFO_RX: Byte = 0x00  // 接收VFO (当前VFO)
        private const val VFO_TX: Byte = 0x01  // 发射VFO (另一个VFO)

        // IC-705地址
        private const val IC705_ADDRESS: Byte = 0xA4.toByte()

        // 控制器地址
        private const val CONTROLLER_ADDRESS: Byte = 0xE0.toByte()

        // 响应超时时间（毫秒）
        private const val RESPONSE_TIMEOUT_MS = 1000L
    }

    // 状态管理
    private val _lastCommand = MutableStateFlow<ByteArray?>(null)
    val lastCommand: StateFlow<ByteArray?> = _lastCommand.asStateFlow()

    private val _lastResponse = MutableStateFlow<ByteArray?>(null)
    val lastResponse: StateFlow<ByteArray?> = _lastResponse.asStateFlow()

    // 等待响应的Deferred —— 跨线程读写需要 @Volatile 保证可见性
    @Volatile
    private var pendingResponse: CompletableDeferred<ByteArray>? = null
    @Volatile
    private var pendingCommandCode: Byte? = null
    
    /**
     * 构建CIV指令数据包
     * @param commandCode 指令代码
     * @param data 数据（可选）
     * @return CIV指令数据包
     */
    private fun buildCivCommand(commandCode: Byte, data: ByteArray = ByteArray(0)): ByteArray {
        // 计算数据包长度
        val packetLength = 2 + 1 + 1 + 1 + data.size + 1  // 起始(2) + 目标地址(1) + 源地址(1) + 指令(1) + 数据(n) + 结束(1)
        val packet = ByteArray(packetLength)
        
        // 填充起始字节
        packet[0] = 0xFE.toByte()
        packet[1] = 0xFE.toByte()
        
        // 填充目标地址（IC-705）
        packet[2] = IC705_ADDRESS
        
        // 填充源地址（控制器）
        packet[3] = CONTROLLER_ADDRESS
        
        // 填充指令代码
        packet[4] = commandCode
        
        // 填充数据（如果有）
        if (data.isNotEmpty()) {
            System.arraycopy(data, 0, packet, 5, data.size)
        }
        
        // 填充结束字节
        packet[packetLength - 1] = 0xFD.toByte()
        
        return packet
    }
    
    /**
     * 发送CIV指令
     * @param commandCode 指令代码
     * @param data 数据（可选）
     * @return 是否发送成功
     */
    suspend fun sendCivCommand(commandCode: Int, data: ByteArray = ByteArray(0)): Boolean {
        try {
            val command = buildCivCommand(commandCode.toByte(), data)
            _lastCommand.value = command
            
            LogManager.i(TAG, "【CIV指令】发送指令: ${bytesToHex(command)}")
            
            // 发送指令
            val success = sppConnector.sendData(command)
            
            if (success) {
                LogManager.i(TAG, "【CIV指令】指令发送成功")
            } else {
                LogManager.e(TAG, "【CIV指令】指令发送失败")
            }
            
            return success
        } catch (e: Exception) {
            LogManager.e(TAG, "【CIV指令】发送指令异常", e)
            return false
        }
    }
    
    /**
     * 读取当前频率
     * @return 是否读取成功
     */
    suspend fun readFrequency(): Boolean {
        LogManager.i(TAG, "【CIV指令】读取频率")
        return sendCivCommand(CMD_READ_FREQUENCY.toInt())
    }
    
    /**
     * 发送CIV指令并等待响应
     * @param commandCode 指令代码
     * @param data 数据（可选）
     * @return 响应数据，失败返回null
     */
    private suspend fun sendCommandAndWaitForResponse(commandCode: Int, data: ByteArray = ByteArray(0)): ByteArray? {
        val commandByte = commandCode.toByte()

        // 清除之前可能存在的未完成等待
        if (pendingResponse != null && !pendingResponse!!.isCompleted) {
            LogManager.w(TAG, "【CIV指令】清除之前未完成的等待")
            pendingResponse?.cancel()
        }

        // 创建等待响应的Deferred
        val deferred = CompletableDeferred<ByteArray>()
        pendingResponse = deferred
        pendingCommandCode = commandByte

        LogManager.i(TAG, "【CIV指令】发送指令 0x${String.format("%02X", commandByte)} 并等待响应")

        try {
            // 发送指令
            val sendSuccess = sendCivCommand(commandCode, data)

            if (!sendSuccess) {
                LogManager.e(TAG, "【CIV指令】发送指令 0x${String.format("%02X", commandByte)} 失败")
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
                LogManager.e(TAG, "【CIV指令】等待指令 0x${String.format("%02X", commandByte)} 响应超时")
                return null
            }

            LogManager.i(TAG, "【CIV指令】收到指令 0x${String.format("%02X", commandByte)} 响应")
            return response

        } catch (e: Exception) {
            LogManager.e(TAG, "【CIV指令】发送指令并等待响应异常", e)
            pendingResponse = null
            pendingCommandCode = null
            return null
        }
    }

    /**
     * 设置频率
     * @param frequencyHz 频率（Hz）
     * @param vfo VFO选择（0x00=VFO A/接收, 0x01=VFO B/发射, null=当前VFO）
     * @return 是否设置成功
     */
    suspend fun writeFrequency(frequencyHz: Long, vfo: Byte? = null): Boolean {
        val vfoStr = when(vfo) {
            VFO_RX -> "RX/VFO A"
            VFO_TX -> "TX/VFO B"
            else -> "当前VFO"
        }
        // 与 CivController.setVfoFrequency 保持一致的安全外壳：先拒硬件外频率，
        // 再拒非 IC-705 合规频段，避免通过这条备用路径绕过合规白名单。
        if (frequencyHz <= 0L || frequencyHz > 10_000_000_000L) {
            LogManager.e(TAG, "【CIV指令】频率超出硬件范围 ($frequencyHz Hz)，拒绝写入")
            return false
        }
        if (!isAllowedTxFrequency(frequencyHz)) {
            LogManager.e(TAG, "【CIV指令】频率 ${frequencyHz / 1_000_000.0} MHz 不在合规发射频段，拒绝写入")
            return false
        }
        LogManager.i(TAG, "【CIV指令】设置频率: ${frequencyHz / 1000000.0} MHz, $vfoStr")

        // 编码频率为BCD格式（5字节）
        val freqData = encodeFrequency(frequencyHz)

        // 构建数据：VFO标识 + 频率数据
        // 命令格式: 0x25 <VFO> <5字节频率>
        val data = byteArrayOf(vfo ?: VFO_RX) + freqData

        // 发送并等待响应
        val response = sendCommandAndWaitForResponse(CMD_READ_FREQUENCY.toInt(), data)
        return response != null
    }

    private fun isAllowedTxFrequency(hz: Long): Boolean {
        // 复用 CivController 中的同一份白名单，避免两条 TX 路径独立维护
        // 而漂移；详见 CivController.ALLOWED_TX_RANGES_HZ。
        return CivController.ALLOWED_TX_RANGES_HZ.any { (lo, hi) -> hz in lo..hi }
    }
    
    /**
     * 设置接收频率 (VFO A)
     * 设置后会自动读取频率以更新显示
     */
    suspend fun setRxFrequency(frequencyHz: Long): Boolean {
        val success = writeFrequency(frequencyHz, VFO_RX)
        if (success) {
            // 延迟后读取频率以更新显示
            kotlinx.coroutines.delay(100)
            readFrequency()
        }
        return success
    }
    
    /**
     * 设置发射频率 (VFO B)
     * 设置后会自动读取频率以更新显示
     */
    suspend fun setTxFrequency(frequencyHz: Long): Boolean {
        val success = writeFrequency(frequencyHz, VFO_TX)
        if (success) {
            // 延迟后读取频率以更新显示
            kotlinx.coroutines.delay(100)
            readFrequency()
        }
        return success
    }
    
    /**
     * 开启Split模式
     */
    suspend fun enableSplitMode(): Boolean {
        LogManager.i(TAG, "【CIV指令】开启Split模式")
        return sendCivCommand(CMD_SET_SPLIT.toInt(), byteArrayOf(0x01))
    }
    
    /**
     * 关闭Split模式
     */
    suspend fun disableSplitMode(): Boolean {
        LogManager.i(TAG, "【CIV指令】关闭Split模式")
        return sendCivCommand(CMD_SET_SPLIT.toInt(), byteArrayOf(0x00))
    }
    
    /**
     * 编码频率数据
     * @param frequencyHz 频率（Hz）
     * @return 5字节BCD码
     * 
     * IC-705频率数据直接编码为BCD
     * 例如：145.500 MHz = 145500000 Hz
     * BCD编码：00 50 45 01 00 (低位在前)
     */
    private fun encodeFrequency(frequencyHz: Long): ByteArray {
        val data = ByteArray(5)
        var freq = frequencyHz
        
        // 将频率转换为BCD码，低位在前
        for (i in 0 until 5) {
            val lowDigit = (freq % 10).toInt()
            freq /= 10
            val highDigit = (freq % 10).toInt()
            freq /= 10
            data[i] = ((highDigit shl 4) or lowDigit).toByte()
        }
        
        return data
    }
    
    /**
     * 读取当前模式
     * @return 是否读取成功
     */
    suspend fun readMode(): Boolean {
        LogManager.i(TAG, "【CIV指令】读取模式")
        return sendCivCommand(CMD_READ_MODE.toInt())
    }
    
    /**
     * 读取当前模式
     * @param vfo VFO选择（0x00=VFO A, 0x01=VFO B）
     * @return 是否读取成功
     */
    suspend fun readMode(vfo: Byte): Boolean {
        LogManager.i(TAG, "【CIV指令】读取VFO ${if (vfo == 0x00.toByte()) "A" else "B"} 模式")
        return sendCivCommand(CMD_READ_MODE.toInt(), byteArrayOf(vfo))
    }
    
    /**
     * 设置VFO模式
     * @param mode 模式字符串 (如: "LSB", "USB", "CW", "FM"等)
     * @param vfo VFO选择（0x00=VFO A, 0x01=VFO B）
     * @return 是否设置成功
     */
    suspend fun setMode(mode: String, vfo: Byte = VFO_RX): Boolean {
        val modeCode = MODE_MAP[mode.uppercase()]
        if (modeCode == null) {
            LogManager.w(TAG, "【CIV指令】未知的模式: $mode, 支持的类型: ${MODE_MAP.keys}")
            return false
        }
        
        LogManager.i(TAG, "【CIV指令】设置VFO ${if (vfo == VFO_RX) "A" else "B"} 模式为 $mode (0x${String.format("%02X", modeCode)})")
        
        // 构建数据: [VFO, 模式代码, 滤波器(0x00=默认)]
        val data = byteArrayOf(vfo, modeCode.toByte(), 0x00)
        return sendCivCommand(CMD_SET_MODE.toInt(), data)
    }
    
    /**
     * 设置VFO A和VFO B的模式（用于卫星跟踪）
     * @param rxMode 接收模式 (VFO A)
     * @param txMode 发射模式 (VFO B)
     * @return 是否设置成功
     */
    suspend fun setSatelliteModes(rxMode: String, txMode: String): Boolean {
        LogManager.i(TAG, "【CIV指令】设置卫星模式 - 接收(VFO A): $rxMode, 发射(VFO B): $txMode")
        
        // 设置VFO A模式 (接收)
        val rxSuccess = setMode(rxMode, VFO_RX)
        if (!rxSuccess) {
            LogManager.e(TAG, "【CIV指令】设置接收模式失败")
            return false
        }
        
        kotlinx.coroutines.delay(50) // 短暂延迟
        
        // 设置VFO B模式 (发射)
        val txSuccess = setMode(txMode, VFO_TX)
        if (!txSuccess) {
            LogManager.e(TAG, "【CIV指令】设置发射模式失败")
            return false
        }
        
        LogManager.i(TAG, "【CIV指令】卫星模式设置成功")
        return true
    }
    
    /**
     * 开启Split模式
     * @param enable 是否开启
     * @return 是否操作成功
     */
    suspend fun setSplitMode(enable: Boolean): Boolean {
        LogManager.i(TAG, "【CIV指令】${if (enable) "开启" else "关闭"}Split模式")
        val data = byteArrayOf(if (enable) 0x01.toByte() else 0x00.toByte())
        return sendCivCommand(CMD_SET_SPLIT.toInt(), data)
    }
    
    /**
     * 读取VFO状态
     * @return 是否读取成功
     */
    suspend fun readVfoStatus(): Boolean {
        LogManager.i(TAG, "【CIV指令】读取VFO状态")
        return sendCivCommand(CMD_READ_VFO_STATUS.toInt(), byteArrayOf(0x00))
    }
    
    /**
     * 处理CIV指令响应
     * @param response 响应字节数组
     */
    fun processResponse(response: ByteArray) {
        _lastResponse.value = response
        LogManager.i(TAG, "【CIV指令】收到响应")
        LogManager.d(TAG, "【CIV指令】响应数据: ${bytesToHex(response)}")

        // 检查响应长度，最少需要6字节（起始2字节 + 地址2字节 + 指令1字节 + 结束1字节）
        if (response.size < 6) {
            LogManager.e(TAG, "【CIV指令】响应长度不足，需要至少6字节，实际: ${response.size}字节")
            return
        }

        // 协议中 response[2] 是目的地址、response[3] 是源地址。
        // 之前的代码把字段名搞反了，这里同时纠正命名并做安全检查。
        val targetAddr = response[2]
        val sourceAddr = response[3]
        val commandCode = response[4]

        LogManager.d(TAG, "【CIV指令】响应源地址: 0x${String.format("%02X", sourceAddr)}")
        LogManager.d(TAG, "【CIV指令】响应目标地址: 0x${String.format("%02X", targetAddr)}")
        LogManager.d(TAG, "【CIV指令】响应指令代码: 0x${String.format("%02X", commandCode)}")

        // 拒绝非 IC-705 来源的响应（防伪 SPP 端伪造 0xFB 通配确认污染状态）
        if (sourceAddr != IC705_ADDRESS) {
            LogManager.w(TAG, "【CIV指令】响应源地址非 IC-705，忽略")
            return
        }

        // 如果有等待的Deferred，完成它
        LogManager.d(TAG, "【CIV指令】检查等待状态 - pendingResponse: ${pendingResponse != null}, isCompleted: ${pendingResponse?.isCompleted}, pendingCommandCode: 0x${String.format("%02X", pendingCommandCode ?: 0)}")
        if (pendingResponse != null && !pendingResponse!!.isCompleted) {
            // 0xFB/0xFA 分别是 ACK/NACK；都视为对当前命令的终态回应，
            // 以便调用方尽快拿到结果（而不是等 2s 超时）。
            val commandMatch = pendingCommandCode == commandCode
            val isFbResponse = commandCode == 0xFB.toByte()
            val isFaResponse = commandCode == 0xFA.toByte()
            LogManager.d(TAG, "【CIV指令】命令匹配: $commandMatch, 0xFB响应: $isFbResponse, 0xFA响应: $isFaResponse")
            if (commandMatch || isFbResponse || isFaResponse) {
                if (isFaResponse) {
                    LogManager.w(TAG, "【CIV指令】收到 0xFA 失败确认，完成等待的Deferred")
                } else {
                    LogManager.i(TAG, "【CIV指令】完成等待的Deferred")
                }
                pendingResponse!!.complete(response)
            } else {
                LogManager.w(TAG, "【CIV指令】响应不匹配 - 等待: 0x${String.format("%02X", pendingCommandCode ?: 0)}, 收到: 0x${String.format("%02X", commandCode)}")
            }
        } else {
            LogManager.d(TAG, "【CIV指令】没有等待的Deferred或已 completed")
        }

        // 对于6字节的响应（如0xFB确认），不处理后续频率/模式数据
        if (response.size < 7) {
            LogManager.d(TAG, "【CIV指令】响应长度为${response.size}字节，跳过频率/模式数据解析")
            return
        }

        // 处理频率响应
        if (commandCode == CMD_READ_FREQUENCY) {
            // 提取频率数据
            if (response.size >= 12) {
                val freqData = ByteArray(5)
                System.arraycopy(response, 5, freqData, 0, 5)
                val frequency = decodeFrequency(freqData)
                LogManager.i(TAG, "【CIV指令】频率响应: $frequency Hz")
            }
        }

        // 处理模式响应
        if (commandCode == CMD_READ_MODE) {
            // 提取模式数据
            if (response.size >= 8) {
                val mode = response[5]
                val bandwidth = response[6]
                LogManager.i(TAG, "【CIV指令】模式响应: 0x${String.format("%02X", mode)}, 带宽: 0x${String.format("%02X", bandwidth)}")
            }
        }
    }
    
    /**
     * 解码频率数据
     * @param data 5字节BCD码
     * @return 频率（Hz）
     */
    private fun decodeFrequency(data: ByteArray): Long {
        var frequency: Long = 0
        
        // 反转字节顺序，从最后一个字节开始解码
        // 因为IC-705的频率数据是低位在前，高位在后
        for (i in data.size - 1 downTo 0) {
            val byte = data[i].toInt() and 0xFF
            val highNibble = (byte shr 4).toLong()
            val lowNibble = (byte and 0x0F).toLong()
            
            frequency = frequency * 10 + highNibble
            frequency = frequency * 10 + lowNibble
        }
        
        return frequency
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

    /**
     * 发送CW消息
     * @param message CW消息（最多30个字符）
     * @return 是否发送成功
     */
    suspend fun sendCwMessage(message: String): Boolean {
        if (message.isEmpty()) {
            LogManager.w(TAG, "【CIV指令】CW消息为空")
            return false
        }

        if (message.length > 30) {
            LogManager.w(TAG, "【CIV指令】CW消息超过30字符限制，将被截断")
        }

        // 转换字符为CIV编码
        val cwData = message.take(30).mapNotNull { charToCwCode(it) }.toByteArray()

        if (cwData.isEmpty()) {
            LogManager.w(TAG, "【CIV指令】没有有效的CW字符")
            return false
        }

        LogManager.i(TAG, "【CIV指令】发送CW消息: '$message'")
        return sendCivCommand(CMD_SEND_CW_MESSAGE.toInt(), cwData)
    }

    /**
     * 停止CW发送
     * @return 是否发送成功
     */
    suspend fun stopCwMessage(): Boolean {
        LogManager.i(TAG, "【CIV指令】停止CW发送")
        return sendCivCommand(CMD_SEND_CW_MESSAGE.toInt(), byteArrayOf(0xFF.toByte()))
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
                LogManager.w(TAG, "【CIV指令】不支持的CW字符: '$char'")
                null
            }
        }
    }
}
