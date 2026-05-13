package com.bh6aap.ic705Cter.data.radio

import com.bh6aap.ic705Cter.util.LogManager

/**
 * CIV指令解析器
 * 解析CIV指令响应
 */
class CivCommandParser {
    
    companion object {
        private const val TAG = "CivCommandParser"
        private const val FIXED_IF_FREQUENCY = 38.85 // 中频频率（MHz）
    }
    
    /**
     * 指令解析结果
     */
    sealed class CivCommandResult {
        data class FrequencyResult(val localOscillatorFreqMHz: Double, val actualFreqMHz: Double) : CivCommandResult()
        data class ModeResult(val mode: Byte, val bandwidth: Byte) : CivCommandResult()
        data class VfoStatusResult(val currentVfo: Byte) : CivCommandResult()
    }
    
    /**
     * 解析CIV指令
     * @param command CIV指令字节数组
     * @return 解析结果，失败返回null
     */
    fun parseCommand(command: ByteArray): CivCommandResult? {
        LogManager.i(TAG, "【CIV解析】开始解析CIV指令")

        // 最小帧: FE FE dst src cmd FD = 6 字节
        if (command.size < 6) {
            LogManager.e(TAG, "【CIV解析】指令长度不足: ${command.size}")
            return null
        }
        // 基本帧界校验
        if (command[0] != 0xFE.toByte() || command[1] != 0xFE.toByte() ||
            command[command.size - 1] != 0xFD.toByte()) {
            LogManager.e(TAG, "【CIV解析】帧头/帧尾不合法，丢弃")
            return null
        }

        val targetAddr = command[2]
        val sourceAddr = command[3]
        val commandCode = command[4]

        LogManager.d(TAG, "【CIV解析】目标地址: 0x${String.format("%02X", targetAddr)}")
        LogManager.d(TAG, "【CIV解析】源地址: 0x${String.format("%02X", sourceAddr)}")
        LogManager.d(TAG, "【CIV解析】指令代码: 0x${String.format("%02X", commandCode)}")

        val dataStartIndex = 5
        val dataEndIndex = command.size - 1 // 排除结束字节FD
        val dataLen = dataEndIndex - dataStartIndex

        return when (commandCode) {
            0x00.toByte(), 0x03.toByte() -> {
                // 频率广播/响应必须携带 5 字节 BCD 载荷
                if (dataLen < 5) {
                    LogManager.e(TAG, "【CIV解析】频率帧数据不足: dataLen=$dataLen")
                    return null
                }
                parseFrequencyCommand(command, dataStartIndex, dataEndIndex)
            }
            0x01.toByte(), 0x04.toByte() -> {
                if (dataLen < 1) {
                    LogManager.e(TAG, "【CIV解析】模式帧数据不足: dataLen=$dataLen")
                    return null
                }
                parseModeCommand(command, dataStartIndex, dataEndIndex)
            }
            0x26.toByte() -> {
                if (dataLen < 1) {
                    LogManager.e(TAG, "【CIV解析】VFO状态帧数据不足: dataLen=$dataLen")
                    return null
                }
                parseVfoStatusCommand(command, dataStartIndex, dataEndIndex)
            }
            else -> {
                LogManager.i(TAG, "【CIV解析】收到未处理的指令，代码: 0x${String.format("%02X", commandCode)}")
                null
            }
        }
    }
    
    /**
     * 解析频率指令
     * 目前仅处理电台主动广播 / 查询响应（0x00 / 0x03），它们的载荷
     * 是裸 5 字节 BCD。设置命令 0x25 在协议上虽然存在（VFO 1 字节 +
     * BCD 5 字节），但未在 parseCommand 里派发——电台对 0x25 的回
     * 应是无载荷的 0xFB ACK，由 CivController.processResponse 处理。
     * @return 频率解析结果；载荷无效时返回 null（上层应丢弃整帧）
     */
    private fun parseFrequencyCommand(
        command: ByteArray,
        dataStartIndex: Int,
        dataEndIndex: Int
    ): CivCommandResult? {
        LogManager.i(TAG, "【CIV解析】解析频率指令")

        val dataLen = dataEndIndex - dataStartIndex
        if (dataLen < 5) {
            LogManager.e(TAG, "【CIV解析】频率数据长度不足: $dataLen")
            return null
        }

        // 提取 5 字节 BCD 频率数据
        val freqData = ByteArray(5)
        System.arraycopy(command, dataStartIndex, freqData, 0, 5)
        LogManager.d(TAG, "【CIV解析】频率数据: ${bytesToHex(freqData)}")

        // 解码频率（带半字节校验）
        val decodedFreq = decodeFrequency(freqData)
        if (decodedFreq < 0) {
            LogManager.e(TAG, "【CIV解析】BCD 半字节非法，丢弃频率帧")
            return null
        }
        LogManager.d(TAG, "【CIV解析】解码频率: $decodedFreq Hz")

        val decodedFreqMHz = decodedFreq / 1000000.0
        val actualFreq = if (decodedFreqMHz < 25.0) {
            decodedFreqMHz + FIXED_IF_FREQUENCY
        } else {
            decodedFreqMHz
        }
        LogManager.i(TAG, "【CIV解析】实际射频频率: $actualFreq MHz")

        return CivCommandResult.FrequencyResult(decodedFreqMHz, actualFreq)
    }
    
    /**
     * 解析模式指令
     * @return 模式解析结果；载荷无效返回 null
     */
    private fun parseModeCommand(
        command: ByteArray,
        dataStartIndex: Int,
        dataEndIndex: Int
    ): CivCommandResult? {
        LogManager.i(TAG, "【CIV解析】解析模式指令")

        val dataLen = dataEndIndex - dataStartIndex
        if (dataLen < 1) {
            LogManager.e(TAG, "【CIV解析】模式数据长度不足")
            return null
        }

        val commandCode = command[4]
        var modeIndex = dataStartIndex

        // 0x04 命令格式：VFO标识(1字节) + 模式码(1字节) + 带宽(1字节)
        if (commandCode == 0x04.toByte() && dataLen >= 3) {
            val possibleVfo = command[dataStartIndex]
            if (possibleVfo == 0x00.toByte() || possibleVfo == 0x01.toByte()) {
                modeIndex = dataStartIndex + 1
                LogManager.d(TAG, "【CIV解析】检测到VFO标识: 0x${String.format("%02X", possibleVfo)}")
            }
        }

        if (modeIndex >= dataEndIndex) {
            LogManager.e(TAG, "【CIV解析】模式字节位置越界")
            return null
        }

        val mode = command[modeIndex]
        val bandwidth = if (modeIndex + 1 < dataEndIndex) command[modeIndex + 1] else 0

        // IC-705 合法模式白名单（其余丢弃而非默认 USB）
        val legalModes = setOf<Byte>(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x07, 0x08, 0x17
        )
        if (mode !in legalModes) {
            LogManager.e(TAG, "【CIV解析】非法模式字节 0x${String.format("%02X", mode)}，丢弃")
            return null
        }

        LogManager.d(TAG, "【CIV解析】模式: 0x${String.format("%02X", mode)}, 带宽: 0x${String.format("%02X", bandwidth)}")

        return CivCommandResult.ModeResult(mode, bandwidth)
    }

    /**
     * 解析VFO状态指令
     * @return VFO状态解析结果；载荷无效返回 null
     */
    private fun parseVfoStatusCommand(
        command: ByteArray,
        dataStartIndex: Int,
        dataEndIndex: Int
    ): CivCommandResult? {
        LogManager.i(TAG, "【CIV解析】解析VFO状态指令")

        val dataLen = dataEndIndex - dataStartIndex
        if (dataLen < 1) {
            LogManager.e(TAG, "【CIV解析】VFO状态数据长度不足")
            return null
        }

        val vfoData = command.sliceArray(dataStartIndex until dataEndIndex)
        LogManager.d(TAG, "【CIV解析】VFO状态数据: ${bytesToHex(vfoData)}")

        val currentVfo = if (vfoData.size >= 4) {
            vfoData[1]
        } else {
            vfoData[0]
        }

        LogManager.d(TAG, "【CIV解析】当前VFO: 0x${String.format("%02X", currentVfo)}")
        return CivCommandResult.VfoStatusResult(currentVfo)
    }
    
    /**
     * 解码频率数据
     * @param data 5字节BCD码
     * @return 频率（Hz）；BCD 半字节非法时返回 -1L
     */
    private fun decodeFrequency(data: ByteArray): Long {
        var frequency: Long = 0

        for (i in data.size - 1 downTo 0) {
            val byte = data[i].toInt() and 0xFF
            val highNibble = (byte shr 4)
            val lowNibble = (byte and 0x0F)
            if (highNibble > 9 || lowNibble > 9) {
                return -1L
            }
            frequency = frequency * 10 + highNibble
            frequency = frequency * 10 + lowNibble
        }

        return frequency
    }
    
    /**
     * 获取频段类型
     * @param frequencyMHz 频率（MHz）
     * @return 频段类型
     */
    fun getBandType(frequencyMHz: Double): String {
        return when {
            frequencyMHz < 30 -> "HF"
            frequencyMHz < 1000 -> "VHF/UHF"
            else -> "SHF"
        }
    }
    
    /**
     * 判断是否是V/U段
     * @param frequencyMHz 频率（MHz）
     * @return 是否是V/U段
     */
    fun isVUSegment(frequencyMHz: Double): Boolean {
        return frequencyMHz >= 30 && frequencyMHz < 1000
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
