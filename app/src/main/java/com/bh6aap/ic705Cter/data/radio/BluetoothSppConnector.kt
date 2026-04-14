package com.bh6aap.ic705Cter.data.radio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.bh6aap.ic705Cter.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 蓝牙SPP连接器
 * 负责与ICOM电台的蓝牙SPP连接管理
 */
class BluetoothSppConnector(private val bluetoothDevice: BluetoothDevice) {
    
    companion object {
        private const val TAG = "BluetoothSppConnector"
        private const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
        private const val DEFAULT_BAUD_RATE = 19200
    }
    
    // 连接状态
    enum class ConnectionState {
        DISCONNECTED,  // 未连接
        CONNECTING,    // 连接中
        CONNECTED,     // 已连接
        ERROR          // 错误
    }
    
    // 回调接口
    interface Callback {
        fun onDataReceived(data: ByteArray)
        fun onCivCommandReceived(command: ByteArray)
        fun onConnectionStateChanged(state: ConnectionState)
    }
    
    // 回调
    private var callback: Callback? = null
    
    // 状态管理
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // 内部组件
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var isConnected = false
    private var receiveThread: Thread? = null
    
    /**
     * 连接到电台
     * @return 是否连接成功
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        _connectionState.value = ConnectionState.CONNECTING
        callback?.onConnectionStateChanged(ConnectionState.CONNECTING)
        
        try {
            LogManager.i(TAG, "【蓝牙SPP】开始连接到设备: ${bluetoothDevice.name} (${bluetoothDevice.address})")
            LogManager.i(TAG, "【蓝牙SPP】SPP UUID: $SPP_UUID")
            
            // 关闭已有的连接（不触发回调，避免清理资源）
            closeConnectionQuietly()
            
            // 创建RFCOMM套接字
            val uuid = UUID.fromString(SPP_UUID)
            bluetoothSocket = bluetoothDevice.createRfcommSocketToServiceRecord(uuid)
            
            LogManager.d(TAG, "【蓝牙SPP】正在连接到RFCOMM服务...")
            
            // 连接到设备
            bluetoothSocket?.connect()
            
            LogManager.d(TAG, "【蓝牙SPP】连接成功，获取输入输出流")
            
            // 获取输入输出流
            inputStream = bluetoothSocket?.inputStream
            outputStream = bluetoothSocket?.outputStream
            
            isConnected = true
            _connectionState.value = ConnectionState.CONNECTED
            callback?.onConnectionStateChanged(ConnectionState.CONNECTED)
            
            // 启动接收线程
            startReceiveThread()
            
            LogManager.i(TAG, "【蓝牙SPP】连接成功，已启动接收线程")
            true
        } catch (e: Exception) {
            LogManager.e(TAG, "【蓝牙SPP】连接失败", e)
            _connectionState.value = ConnectionState.ERROR
            callback?.onConnectionStateChanged(ConnectionState.ERROR)
            disconnect()
            false
        }
    }
    
    /**
     * 静默关闭连接（不触发回调）
     * 用于连接前清理旧连接
     */
    private fun closeConnectionQuietly() {
        try {
            LogManager.i(TAG, "【蓝牙SPP】静默关闭旧连接")
            
            // 停止接收线程
            receiveThread?.interrupt()
            receiveThread = null
            
            // 关闭流和套接字
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()
            
            isConnected = false
            // 注意：不触发回调，不修改_connectionState
            
            LogManager.i(TAG, "【蓝牙SPP】旧连接已关闭")
        } catch (e: Exception) {
            LogManager.e(TAG, "【蓝牙SPP】静默关闭连接失败", e)
        } finally {
            inputStream = null
            outputStream = null
            bluetoothSocket = null
        }
    }
    
    /**
     * 断开与电台的连接
     */
    fun disconnect() {
        try {
            LogManager.i(TAG, "【蓝牙SPP】断开连接")
            
            // 停止接收线程
            receiveThread?.interrupt()
            receiveThread = null
            
            // 关闭流和套接字
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()
            
            isConnected = false
            _connectionState.value = ConnectionState.DISCONNECTED
            callback?.onConnectionStateChanged(ConnectionState.DISCONNECTED)
            
            LogManager.i(TAG, "【蓝牙SPP】连接已断开")
        } catch (e: Exception) {
            LogManager.e(TAG, "【蓝牙SPP】断开连接失败", e)
        } finally {
            inputStream = null
            outputStream = null
            bluetoothSocket = null
        }
    }
    
    /**
     * 检查连接状态
     * @return 是否已连接
     */
    fun isConnected(): Boolean = isConnected
    
    /**
     * 发送数据
     * @param data 要发送的数据
     * @return 是否发送成功
     */
    suspend fun sendData(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected) {
            LogManager.e(TAG, "【蓝牙SPP】未连接，无法发送数据")
            return@withContext false
        }
        
        try {
            LogManager.d(TAG, "【蓝牙SPP】发送数据: ${bytesToHex(data)}")
            outputStream?.write(data)
            outputStream?.flush()
            LogManager.d(TAG, "【蓝牙SPP】数据发送成功")
            true
        } catch (e: Exception) {
            LogManager.e(TAG, "【蓝牙SPP】发送数据失败", e)
            false
        }
    }
    
    /**
     * 发送CIV指令
     * @param civCommand CIV指令字节数组
     * @return 是否发送成功
     */
    suspend fun sendCivCommand(civCommand: ByteArray): Boolean {
        return sendData(civCommand)
    }
    
    /**
     * 启动接收线程
     */
    private fun startReceiveThread() {
        receiveThread = Thread {
            val buffer = ByteArray(256)
            var bufferSize = 0
            var lastDataReceivedTime = System.currentTimeMillis()
            val heartbeatInterval = 5000L // 5秒心跳检测
            
            LogManager.i(TAG, "【蓝牙SPP】接收线程已启动")
            
            try {
                while (!Thread.currentThread().isInterrupted && isConnected) {
                    try {
                        // 检查输入流是否可用
                        if (inputStream != null) {
                            // 使用available()检查是否有数据，避免长时间阻塞
                            val availableBytes = inputStream?.available() ?: 0
                            if (availableBytes > 0) {
                                // 读取可用的数据
                                val bytesToRead = min(availableBytes, buffer.size - bufferSize)
                                val bytesRead = inputStream?.read(buffer, bufferSize, bytesToRead) ?: -1
                                
                                if (bytesRead > 0) {
                                    bufferSize += bytesRead
                                    lastDataReceivedTime = System.currentTimeMillis()
                                    LogManager.d(TAG, "【蓝牙SPP】读取到 $bytesRead 字节数据")
                                    
                                    // Process complete CIV frames and learn how many bytes
                                    // were consumed.  Partial frames (no 0xFD terminator
                                    // seen yet) are retained at the front of the buffer so
                                    // they can be completed on the next read, which fixes
                                    // silent loss of commands that span two read() calls.
                                    val consumed = processReceivedData(buffer, bufferSize)
                                    if (consumed < bufferSize) {
                                        // Slide unconsumed bytes to the front of the buffer.
                                        System.arraycopy(buffer, consumed, buffer, 0, bufferSize - consumed)
                                        bufferSize -= consumed
                                    } else {
                                        bufferSize = 0
                                    }

                                    // Safety reset: if the buffer is full and we still
                                    // could not extract a complete frame the incoming data
                                    // is either corrupt or a rogue device is flooding us
                                    // with bytes that never contain 0xFD.  Discard
                                    // everything to unblock the receive loop and prevent
                                    // 100 % CPU spin (read(buf,256,0) always returns 0).
                                    if (bufferSize >= buffer.size) {
                                        LogManager.w(TAG, "【蓝牙SPP】接收缓冲区满且无完整CIV帧，丢弃所有数据")
                                        bufferSize = 0
                                    }
                                } else if (bytesRead == -1) {
                                    // 流已关闭
                                    LogManager.e(TAG, "【蓝牙SPP】输入流已关闭，连接断开")
                                    handleDisconnection()
                                    break
                                }
                            } else {
                                // 检查是否长时间未收到数据（可能连接已断开）
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastDataReceivedTime > heartbeatInterval) {
                                    // 尝试检查socket连接状态
                                    if (bluetoothSocket?.isConnected != true) {
                                        LogManager.e(TAG, "【蓝牙SPP】检测到蓝牙连接已断开")
                                        handleDisconnection()
                                        break
                                    }
                                    // 更新最后数据时间，避免频繁检查
                                    lastDataReceivedTime = currentTime
                                }
                                // 没有数据，短暂休眠避免CPU占用过高
                                Thread.sleep(10)
                            }
                        } else {
                            // 输入流为null，退出循环
                            LogManager.e(TAG, "【蓝牙SPP】输入流为null，连接断开")
                            handleDisconnection()
                            break
                        }
                    } catch (e: IOException) {
                        // IO异常，连接可能已断开
                        LogManager.e(TAG, "【蓝牙SPP】IO异常，连接断开", e)
                        handleDisconnection()
                        break
                    } catch (e: Exception) {
                        // 捕获读取异常，避免线程崩溃
                        if (!Thread.currentThread().isInterrupted) {
                            LogManager.e(TAG, "【蓝牙SPP】读取数据异常", e)
                        }
                        // 短暂休眠后继续
                        Thread.sleep(100)
                    }
                }
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    LogManager.e(TAG, "【蓝牙SPP】接收线程异常", e)
                    handleDisconnection()
                }
            } finally {
                LogManager.i(TAG, "【蓝牙SPP】接收线程已停止")
            }
        }
        receiveThread?.start()
    }
    
    /**
     * 处理连接断开
     */
    private fun handleDisconnection() {
        if (isConnected) {
            LogManager.i(TAG, "【蓝牙SPP】处理连接断开")
            isConnected = false
            _connectionState.value = ConnectionState.DISCONNECTED
            callback?.onConnectionStateChanged(ConnectionState.DISCONNECTED)
            
            // 关闭资源
            try {
                inputStream?.close()
                outputStream?.close()
                bluetoothSocket?.close()
            } catch (e: Exception) {
                LogManager.e(TAG, "【蓝牙SPP】关闭资源失败", e)
            } finally {
                inputStream = null
                outputStream = null
                bluetoothSocket = null
            }
        }
    }
    
    /**
     * 处理接收到的数据
     * @param data 数据缓冲区
     * @param length 数据长度
     * @return number of bytes consumed (complete frames extracted)
     */
    private fun processReceivedData(data: ByteArray, length: Int): Int {
        val receivedData = ByteArray(length)
        System.arraycopy(data, 0, receivedData, 0, length)
        
        LogManager.i(TAG, "【蓝牙SPP】收到数据，长度: $length 字节")
        LogManager.d(TAG, "【蓝牙SPP】接收到的数据: ${bytesToHex(receivedData)}")
        
        // 调用数据接收回调
        callback?.onDataReceived(receivedData)
        
        // 分割并处理多个连续的CIV指令，返回消费字节数
        return processMultipleCivCommands(receivedData)
    }
    
    /**
     * 处理多个连续的CIV指令
     * @param data 数据缓冲区
     * @return number of bytes consumed; bytes from this index onward are a
     *         partial (incomplete) frame that must be retained for the next read
     */
    private fun processMultipleCivCommands(data: ByteArray): Int {
        var index = 0
        while (index < data.size) {
            // 寻找CIV指令的起始字节
            if (index + 1 < data.size && data[index] == 0xFE.toByte() && data[index + 1] == 0xFE.toByte()) {
                // 找到起始字节，寻找结束字节
                var endIndex = index + 2
                while (endIndex < data.size && data[endIndex] != 0xFD.toByte()) {
                    endIndex++
                }
                
                // 如果找到结束字节，提取并处理CIV指令
                if (endIndex < data.size) {
                    val civCommand = ByteArray(endIndex - index + 1)
                    System.arraycopy(data, index, civCommand, 0, civCommand.size)
                    
                    LogManager.i(TAG, "【蓝牙SPP】提取CIV指令，长度: ${civCommand.size} 字节")
                    LogManager.d(TAG, "【蓝牙SPP】提取的CIV指令: ${bytesToHex(civCommand)}")
                    
                    // 处理CIV指令
                    processCivCommand(civCommand)
                    
                    // 移动到下一个指令的起始位置
                    index = endIndex + 1
                } else {
                    // No terminator found yet — this is a partial frame.  Stop
                    // here; the caller will retain bytes from 'index' onward
                    // so they are prepended to the next read.
                    break
                }
            } else {
                // 不是CIV指令的起始字节，移动到下一个字节
                index++
            }
        }
        return index
    }
    
    /**
     * 检查是否是CIV指令
     * @param data 数据
     * @return 是否是CIV指令
     */
    private fun isCivCommand(data: ByteArray): Boolean {
        // CIV指令以0xFE 0xFE开始，以0xFD结束
        if (data.size < 3) return false
        
        // 检查起始字节
        if (data[0] != 0xFE.toByte() || data[1] != 0xFE.toByte()) return false
        
        // 检查结束字节
        if (data.last() != 0xFD.toByte()) return false
        
        return true
    }
    
    /**
     * 处理CIV指令
     * @param data CIV指令数据
     */
    private fun processCivCommand(data: ByteArray) {
        LogManager.i(TAG, "【CIV指令】处理CIV指令")
        
        // 解析CIV指令结构
        val startIndex = 0
        val targetAddress = data[startIndex + 2]
        val sourceAddress = data[startIndex + 3]
        val commandCode = data[startIndex + 4]
        val dataStartIndex = startIndex + 5
        val dataEndIndex = data.size - 1  // 排除结束字节
        
        LogManager.d(TAG, "【CIV指令】目标地址: 0x${String.format("%02X", targetAddress)}")
        LogManager.d(TAG, "【CIV指令】源地址: 0x${String.format("%02X", sourceAddress)}")
        LogManager.d(TAG, "【CIV指令】命令代码: 0x${String.format("%02X", commandCode)}")
        
        if (dataStartIndex < dataEndIndex) {
            val payloadLength = dataEndIndex - dataStartIndex
            val payload = ByteArray(payloadLength)
            System.arraycopy(data, dataStartIndex, payload, 0, payloadLength)
            LogManager.d(TAG, "【CIV指令】数据长度: $payloadLength 字节")
            LogManager.d(TAG, "【CIV指令】数据内容: ${bytesToHex(payload)}")
        }
        
        // 检查是否是电台广播指令
        if (targetAddress == 0x00.toByte()) {
            LogManager.i(TAG, "【CIV指令】这是一个广播指令")
        }
        
        // 检查是否是IC-705发送的指令
        if (sourceAddress == 0xA4.toByte()) {
            LogManager.i(TAG, "【CIV指令】指令来自IC-705电台")
        }
        
        // 调用CIV指令接收回调
        callback?.onCivCommandReceived(data)
    }
    
    /**
     * 将字节数组转换为十六进制字符串
     * @param bytes 字节数组
     * @return 十六进制字符串
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
     * 获取当前连接状态
     * @return 连接状态
     */
    fun getConnectionState(): ConnectionState = _connectionState.value
    
    /**
     * 设置回调
     * @param callback 回调接口
     */
    fun setCallback(callback: Callback) {
        this.callback = callback
    }
    
    /**
     * 获取连接的设备
     * @return 蓝牙设备
     */
    fun getConnectedDevice(): BluetoothDevice = bluetoothDevice
}
