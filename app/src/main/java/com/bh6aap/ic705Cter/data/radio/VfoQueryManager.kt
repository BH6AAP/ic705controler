package com.bh6aap.ic705Cter.data.radio

import com.bh6aap.ic705Cter.util.LogManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * VFO查询管理器
 * 简化逻辑：
 * 1. 默认开启后手动设置为信道A
 * 2. 监听广播FEFE00A4010501FD用来切换前台频率更新的位置是A还是B
 * 3. 直接查询两个VFO的数据，不再查询当前VFO状态
 */
class VfoQueryManager(private val connectionManager: BluetoothConnectionManager) {

    companion object {
        private const val TAG = "VfoQueryManager"
        private const val RESPONSE_TIMEOUT_MS = 5000L // 响应超时时间
    }

    /**
     * VFO数据类
     */
    data class VfoData(
        val vfo: String, // "A" 或 "B"
        val frequency: String, // 频率字符串，如 "145.800 MHz"
        val mode: String // 模式字符串，如 "USB"
    )

    // 当前等待的响应 —— 蓝牙接收线程与协程并发访问，需 @Volatile
    @Volatile
    private var pendingResponse: CompletableDeferred<ByteArray>? = null
    @Volatile
    private var pendingCommandCode: Byte? = null

    /**
     * 查询VFO A和VFO B的完整数据
     * 简化逻辑：直接查询VFO A和VFO B的数据
     * @return Pair<VfoData(A), VfoData(B)>
     */
    suspend fun queryBothVfos(): Pair<VfoData, VfoData> {
        LogManager.i(TAG, "【VFO查询】开始查询VFO A和VFO B数据")

        // 步骤1：查询VFO A频率（0x25 0x00）
        LogManager.i(TAG, "【VFO查询】步骤1：查询VFO A频率（0x25 0x00）")
        val freqA = queryFrequency(0x00)
        LogManager.i(TAG, "【VFO查询】VFO A频率: $freqA")

        // 步骤2：查询VFO A模式（0x26 0x00）
        LogManager.i(TAG, "【VFO查询】步骤2：查询VFO A模式（0x26 0x00）")
        val modeA = queryMode(0x00)
        LogManager.i(TAG, "【VFO查询】VFO A模式: $modeA")

        val vfoAData = VfoData("A", freqA, modeA)
        LogManager.i(TAG, "【VFO查询】VFO A数据: $vfoAData")

        // 步骤3：查询VFO B频率（0x25 0x01）
        LogManager.i(TAG, "【VFO查询】步骤3：查询VFO B频率（0x25 0x01）")
        val freqB = queryFrequency(0x01)
        LogManager.i(TAG, "【VFO查询】VFO B频率: $freqB")

        // 步骤4：查询VFO B模式（0x26 0x01）
        LogManager.i(TAG, "【VFO查询】步骤4：查询VFO B模式（0x26 0x01）")
        val modeB = queryMode(0x01)
        LogManager.i(TAG, "【VFO查询】VFO B模式: $modeB")

        val vfoBData = VfoData("B", freqB, modeB)
        LogManager.i(TAG, "【VFO查询】VFO B数据: $vfoBData")

        LogManager.i(TAG, "【VFO查询】VFO A和VFO B数据查询完成")
        return Pair(vfoAData, vfoBData)
    }

    /**
     * 查询频率
     * 发送0x25指令，等待响应，解析频率
     * @param vfoSelector 0x00=VFO A，0x01=VFO B
     * @return 频率字符串
     */
    private suspend fun queryFrequency(vfoSelector: Byte): String {
        LogManager.i(TAG, "【VFO查询】发送0x25 ${String.format("0x%02X", vfoSelector)}查询频率")

        // 发送0x25指令并等待响应
        val response = sendCommandAndWaitForResponse(0x25, byteArrayOf(vfoSelector))

        if (response == null) {
            LogManager.e(TAG, "【VFO查询】查询频率失败，未收到响应")
            return "-"
        }

        // 解析频率
        // 0x25响应格式：FEFEE0A425 XX YY ZZ WW VV FD
        // XX是VFO标识（0x00=VFO A，0x01=VFO B）
        // YY ZZ WW VV是4字节频率数据（BCD码）
        val frequency = if (response.size >= 11) {
            val freqData = response.sliceArray(6 until 11)
            decodeFrequency(freqData)
        } else {
            LogManager.w(TAG, "【VFO查询】响应数据长度不足")
            "-"
        }

        LogManager.i(TAG, "【VFO查询】频率: $frequency")
        return frequency
    }

    /**
     * 查询模式
     * 发送0x26指令，等待响应，解析模式
     * @param vfoSelector 0x00=VFO A，0x01=VFO B
     * @return 模式字符串
     */
    private suspend fun queryMode(vfoSelector: Byte): String {
        LogManager.i(TAG, "【VFO查询】发送0x26 ${String.format("0x%02X", vfoSelector)}查询模式")

        // 发送0x26指令并等待响应
        val response = sendCommandAndWaitForResponse(0x26, byteArrayOf(vfoSelector))

        if (response == null) {
            LogManager.e(TAG, "【VFO查询】查询模式失败，未收到响应")
            return "-"
        }

        // 解析模式
        // 0x26响应格式：FEFEE0A426 XX YY ZZ FD
        // XX是VFO标识（0x00=VFO A，0x01=VFO B）
        // YY是模式代码
        // ZZ是带宽代码
        val mode = if (response.size >= 8) {
            val modeCode = response[6]
            getModeName(modeCode)
        } else {
            LogManager.w(TAG, "【VFO查询】响应数据长度不足")
            "-"
        }

        LogManager.i(TAG, "【VFO查询】模式: $mode")
        return mode
    }

    /**
     * 解码频率数据
     * @param data 5字节BCD码
     * @return 频率字符串（如 "145.800000"）
     */
    private fun decodeFrequency(data: ByteArray): String {
        if (data.size != 5) {
            return "-"
        }

        // 参考Python库的实现：反向读取字节，然后转成16进制字符串
        // 例如：bytes [0x00, 0x30, 0x96, 0x45, 0x01] 反向读取后变成 "0145963000"
        // 实际频率 = 145963000 Hz = 145.963000 MHz
        val freqString = StringBuilder()
        for (i in data.size - 1 downTo 0) {
            freqString.append(String.format("%02X", data[i]))
        }

        // 将16进制字符串转为长整数（频率以Hz为单位）
        val frequencyHz = freqString.toString().toLongOrNull() ?: return "-"

        // 转换为MHz
        val freqMHz = frequencyHz / 1000000.0
        return String.format("%.6f", freqMHz)
    }

    /**
     * 将模式代码转换为模式名称
     * @param modeCode 模式代码
     * @return 模式名称
     */
    private fun getModeName(modeCode: Byte): String {
        return when (modeCode.toInt() and 0xFF) {
            0x00 -> "LSB"
            0x01 -> "USB"
            0x02 -> "AM"
            0x03 -> "CW"
            0x04 -> "RTTY"
            0x05 -> "FM"
            0x06 -> "WFM"
            0x07 -> "CW-R"
            0x08 -> "RTTY-R"
            0x09 -> "DATA-L"
            0x0A -> "DATA-U"
            0x0B -> "PKT-L"
            0x0C -> "PKT-U"
            else -> "未知模式"
        }
    }

    /**
     * 发送指令并等待响应
     * @param commandCode 指令代码
     * @param data 数据（可选）
     * @return 响应数据，失败返回null
     */
    private suspend fun sendCommandAndWaitForResponse(commandCode: Int, data: ByteArray = ByteArray(0)): ByteArray? {
        val commandByte = commandCode.toByte()

        // 清除之前可能存在的未完成等待
        if (pendingResponse != null && !pendingResponse!!.isCompleted) {
            LogManager.w(TAG, "【VFO查询】清除之前未完成的等待")
            pendingResponse?.cancel()
        }

        // 创建等待响应的Deferred
        val deferred = CompletableDeferred<ByteArray>()
        pendingResponse = deferred
        pendingCommandCode = commandByte

        LogManager.i(TAG, "【VFO查询】发送指令 0x${String.format("%02X", commandByte)} 并等待响应")

        try {
            // 发送指令
            val sendSuccess = connectionManager.sendCivCommand(commandCode, data)

            if (!sendSuccess) {
                LogManager.e(TAG, "【VFO查询】发送指令 0x${String.format("%02X", commandByte)} 失败")
                pendingResponse = null
                pendingCommandCode = null
                return null
            }

            LogManager.i(TAG, "【VFO查询】指令 0x${String.format("%02X", commandByte)} 发送成功，等待响应...")

            // 等待响应，带有超时
            val response = withTimeout(RESPONSE_TIMEOUT_MS) {
                deferred.await()
            }

            LogManager.i(TAG, "【VFO查询】收到指令 0x${String.format("%02X", commandByte)} 的响应")

            return response

        } catch (e: TimeoutCancellationException) {
            LogManager.e(TAG, "【VFO查询】等待指令 0x${String.format("%02X", commandByte)} 响应超时")
            pendingResponse = null
            pendingCommandCode = null
            return null
        } catch (e: Exception) {
            LogManager.e(TAG, "【VFO查询】发送指令 0x${String.format("%02X", commandByte)} 异常", e)
            pendingResponse = null
            pendingCommandCode = null
            return null
        }
    }

    /**
     * 处理接收到的响应
     * @param response 响应数据
     */
    fun onResponseReceived(response: ByteArray) {
        if (response.size < 6) {
            LogManager.w(TAG, "【VFO查询】响应数据长度不足")
            return
        }

        // 协议: response[2]=dst, response[3]=src。仅接受来自 IC-705 (0xA4) 的帧
        val sourceAddr = response[3]
        if (sourceAddr != 0xA4.toByte()) {
            LogManager.w(TAG, "【VFO查询】响应源地址非 IC-705，忽略")
            return
        }
        val commandCode = response[4]
        val pendingCode = pendingCommandCode

        LogManager.d(TAG, "【VFO查询】收到响应，指令代码: 0x${String.format("%02X", commandCode)}, 等待的指令: ${if (pendingCode != null) "0x${String.format("%02X", pendingCode)}" else "null"}")

        // 检查是否是我们正在等待的响应。0xFB/0xFA 分别是 IC-705 的
        // ACK/NACK；两种都要算作终态以免调用方一直等到 2s 超时。
        val isFbResponse = commandCode == 0xFB.toByte()
        val isFaResponse = commandCode == 0xFA.toByte()
        if (pendingCode != null && (commandCode == pendingCode || isFbResponse || isFaResponse)) {
            val deferred = pendingResponse
            if (deferred != null && !deferred.isCompleted) {
                when {
                    isFbResponse -> LogManager.i(TAG, "【VFO查询】收到 0xFB 成功确认，完成等待的指令 0x${String.format("%02X", pendingCode)}")
                    isFaResponse -> LogManager.w(TAG, "【VFO查询】收到 0xFA 失败确认，完成等待的指令 0x${String.format("%02X", pendingCode)}")
                    else -> LogManager.i(TAG, "【VFO查询】完成等待，指令 0x${String.format("%02X", commandCode)}")
                }
                try {
                    deferred.complete(response)
                } catch (e: Exception) {
                    LogManager.e(TAG, "【VFO查询】完成Deferred时异常", e)
                }
                // 注意：这里不重置pendingResponse和pendingCommandCode
                // 让它们保持在已完成状态，直到下一次发送指令时再清除
            }
        } else {
            if (pendingCode == null) {
                LogManager.w(TAG, "【VFO查询】收到响应但没有等待的指令，指令代码: 0x${String.format("%02X", commandCode)}")
            } else {
                LogManager.w(TAG, "【VFO查询】收到非预期的响应，指令代码: 0x${String.format("%02X", commandCode)}, 等待的指令: 0x${String.format("%02X", pendingCode)}")
            }
        }
    }
}
