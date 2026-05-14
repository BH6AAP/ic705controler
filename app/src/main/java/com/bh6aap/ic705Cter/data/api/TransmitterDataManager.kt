package com.bh6aap.ic705Cter.data.api

import android.content.Context
import com.bh6aap.ic705Cter.data.database.DatabaseHelper
import com.bh6aap.ic705Cter.data.database.entity.SyncRecordEntity
import com.bh6aap.ic705Cter.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

/**
 * 转发器数据管理器
 * 负责从 SatNOGS API 下载转发器数据并存储到数据库
 */
class TransmitterDataManager(private val context: Context) {
    
    private val database: DatabaseHelper by lazy {
        DatabaseHelper.getInstance(context)
    }
    
    private val gson = Gson()
    private val httpClient = SecureHttp.buildSecureClient(
        connectTimeoutSec = 30L,
        readTimeoutSec = 30L,
        callTimeoutSec = 35L
    )
    
    private val baseApiUrl = "https://db.satnogs.org/api/"
    
    /**
     * 下载并更新转发器数据
     * @return 是否更新成功
     */
    suspend fun updateTransmitters(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                LogManager.i("TransmitterDataManager", "开始下载转发器数据")
                
                // 从白名单读取卫星ID
                val whitelistManager = com.bh6aap.ic705Cter.util.EditableWhitelistManager.getInstance(context)
                // 初始化默认白名单
                whitelistManager.initDefaultWhitelistIfNeeded()
                val entries = whitelistManager.getAllEntries()
                val noradIds = entries.map { it.noradId }
                
                if (noradIds.isEmpty()) {
                    LogManager.e("TransmitterDataManager", "白名单为空")
                    return@withContext false
                }
                
                LogManager.i("TransmitterDataManager", "从白名单加载 ${noradIds.size} 个卫星ID")
                
                val allTransmitters = mutableListOf<Transmitter>()
                
                // 为每个卫星ID获取转发器数据
                noradIds.forEachIndexed { index, noradId ->
                    LogManager.i("TransmitterDataManager", "处理第 ${index + 1}/${noradIds.size} 个卫星: $noradId")

                    try {
                        // 构建请求URL
                        val apiUrl = "${baseApiUrl}transmitters/?satellite__norad_cat_id=$noradId"

                        // 构建请求
                        val request = Request.Builder()
                            .url(apiUrl)
                            .header("Accept", "application/json")
                            .build()

                        httpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                LogManager.e("TransmitterDataManager", "API请求失败: ${response.code} for NORAD ID $noradId")
                                return@forEachIndexed
                            }
                            val responseBody = SecureHttp.readLimitedBody(response)
                            if (responseBody.isNullOrEmpty()) {
                                LogManager.e("TransmitterDataManager", "响应体为空/超限 for NORAD ID $noradId")
                                return@forEachIndexed
                            }
                            val type = object : TypeToken<List<Transmitter>>() {}.type
                            val transmitters: List<Transmitter> = gson.fromJson(responseBody, type)
                            LogManager.i("TransmitterDataManager", "卫星 $noradId 有 ${transmitters.size} 个转发器")
                            allTransmitters.addAll(transmitters)
                        }

                        // 添加短暂延迟，避免请求过快
                        Thread.sleep(500)

                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LogManager.e("TransmitterDataManager", "处理卫星 $noradId 时出错: ${e.message}")
                        // 继续处理下一个卫星
                    }
                }
                
                if (allTransmitters.isEmpty()) {
                    LogManager.e("TransmitterDataManager", "未获取到任何转发器数据")
                    return@withContext false
                }
                
                LogManager.i("TransmitterDataManager", "成功下载 ${allTransmitters.size} 个转发器数据")

                // 清空旧数据并插入新数据
                database.deleteAllTransmitters()
                database.insertTransmitters(allTransmitters)

                // 保存更新时间
                saveLastUpdateTime()
                // 同时写 sync_records，让 onUpgrade 清掉 transmitters 表时
                // "最近更新时间" 和行数能一致被重置，避免 UI 显示 5/12 但
                // 表里 0 行。
                database.insertSyncRecord(
                    SyncRecordEntity(
                        syncType = "transmitters_satnogs",
                        syncTime = System.currentTimeMillis(),
                        recordCount = allTransmitters.size,
                        source = baseApiUrl
                    )
                )

                LogManager.i("TransmitterDataManager", "转发器数据更新完成")
                return@withContext true

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.e("TransmitterDataManager", "更新失败: ${e.message}")
                e.printStackTrace()
                return@withContext false
            }
        }
    }
    
    /**
     * 获取所有转发器数据
     */
    suspend fun getAllTransmitters(): List<Transmitter> {
        return withContext(Dispatchers.IO) {
            val transmitters = mutableListOf<Transmitter>()
            database.getAllTransmitters().collect {list ->
                transmitters.addAll(list)
            }
            transmitters
        }
    }
    
    /**
     * 根据NORAD ID获取转发器数据
     */
    suspend fun getTransmittersByNoradId(noradId: Int): List<Transmitter> {
        return withContext(Dispatchers.IO) {
            val transmitters = mutableListOf<Transmitter>()
            database.getTransmittersByNoradId(noradId).collect {list ->
                transmitters.addAll(list)
            }
            transmitters
        }
    }
    
    /**
     * 获取最后更新时间
     * @return 更新时间字符串，格式：yyyy/MM/dd-HH:mm:ss
     */
    suspend fun getLastUpdateTime(): String {
        return withContext(Dispatchers.IO) {
            // 首选 sync_records：它和 transmitters 表生命周期一致（onUpgrade
            // 若 DROP transmitters 也会 DROP sync_records），所以 UI 不会
            // 出现"有时间戳但 0 行"的假象。
            val record = database.getLastSyncRecord("transmitters_satnogs")
            val timestamp = record?.syncTime ?: run {
                val prefs = context.getSharedPreferences("transmitter_prefs", Context.MODE_PRIVATE)
                prefs.getLong("last_update_time", 0)
            }
            if (timestamp == 0L) {
                "从未更新"
            } else {
                val sdf = SimpleDateFormat("yyyy/MM/dd-HH:mm:ss", Locale.getDefault())
                sdf.format(Date(timestamp))
            }
        }
    }
    
    /**
     * 保存更新时间
     */
    private fun saveLastUpdateTime() {
        val prefs = context.getSharedPreferences("transmitter_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("last_update_time", System.currentTimeMillis())
            .apply()
    }
    
    /**
     * 检查是否有转发器数据
     */
    suspend fun hasTransmitters(): Boolean {
        return withContext(Dispatchers.IO) {
            database.getTransmitterCount() > 0
        }
    }
}
