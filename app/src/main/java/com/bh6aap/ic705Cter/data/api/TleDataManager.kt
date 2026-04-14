package com.bh6aap.ic705Cter.data.api

import android.content.Context
import com.bh6aap.ic705Cter.data.database.DatabaseHelper
import com.bh6aap.ic705Cter.data.database.entity.SatelliteEntity
import com.bh6aap.ic705Cter.data.database.entity.SyncRecordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * TLE 数据管理类
 * 从 SatNOGS 数据库获取卫星 TLE 数据
 */
class TleDataManager(private val context: Context) {

    companion object {
        // 默认使用 Celestrak TLE API (业余卫星组)
        private const val CELESTRAK_TLE_URL = "https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=tle"
        // 保留 SatNOGS API 作为备选
        private const val SATNOGS_API_BASE_URL = "https://db.satnogs.org/api/"
        private const val REQUEST_TIMEOUT_SECONDS = 30L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val dbHelper = DatabaseHelper.getInstance(context)

    /**
     * 检查是否需要同步 TLE 数据
     * @param maxAgeHours 数据最大有效时间（小时），默认 24 小时
     * @return 如果需要同步返回 true
     */
    suspend fun needSync(maxAgeHours: Int = 24): Boolean = withContext(Dispatchers.IO) {
        val satelliteCount = dbHelper.getSatelliteCount()
        if (satelliteCount == 0) {
            return@withContext true
        }

        val lastSync = dbHelper.getLastSyncRecord("tle_satnogs")
        if (lastSync == null) {
            return@withContext true
        }

        val maxAgeMillis = maxAgeHours * 60 * 60 * 1000L
        val isExpired = (System.currentTimeMillis() - lastSync.syncTime) > maxAgeMillis

        isExpired
    }

    /**
     * 获取 TLE 数据
     * @param forceRefresh 强制刷新，忽略缓存
     * @return 是否成功
     */
    suspend fun fetchTleData(forceRefresh: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            // 如果不是强制刷新，检查是否需要同步
            if (!forceRefresh && !needSync()) {
                return@withContext true
            }

            val allSatellites = mutableListOf<SatelliteEntity>()

            // 首先尝试从 Celestrak 获取 TLE 数据（纯文本格式）
            try {
                android.util.Log.i("TleDataManager", "从 Celestrak 获取 TLE 数据: $CELESTRAK_TLE_URL")

                val request = Request.Builder()
                    .url(CELESTRAK_TLE_URL)
                    .header("Accept", "text/plain")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }

                    val responseBody = response.body?.string()
                        ?: throw IOException("Empty response")

                    // 解析纯文本 TLE 数据
                    val satellites = parseTleData(responseBody)
                    allSatellites.addAll(satellites)
                    android.util.Log.i("TleDataManager", "从 Celestrak 获取了 ${satellites.size} 颗卫星")
                }
            } catch (e: Exception) {
                android.util.Log.e("TleDataManager", "从 Celestrak 获取失败，尝试 SatNOGS: ${e.message}")

                // Celestrak 失败，回退到 SatNOGS API
                try {
                    // 从白名单读取卫星ID
                    val whitelistManager = com.bh6aap.ic705Cter.util.EditableWhitelistManager.getInstance(context)
                    whitelistManager.initDefaultWhitelistIfNeeded()
                    val entries = whitelistManager.getAllEntries()
                    val noradIds = entries.map { it.noradId }

                    if (noradIds.isEmpty()) {
                        throw IOException("Whitelist is empty")
                    }

                    val noradIdList = noradIds.joinToString(",")
                    val apiUrl = "${SATNOGS_API_BASE_URL}tle/?satellite__norad_cat_id__in=$noradIdList"

                    val request = Request.Builder()
                        .url(apiUrl)
                        .header("Accept", "application/json")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("HTTP ${response.code}")
                        }

                        val responseBody = response.body?.string()
                            ?: throw IOException("Empty response")

                        val satellites = parseTleData(responseBody)
                        allSatellites.addAll(satellites)
                        android.util.Log.i("TleDataManager", "从 SatNOGS 获取了 ${satellites.size} 颗卫星")
                    }
                } catch (e2: Exception) {
                    android.util.Log.e("TleDataManager", "从 SatNOGS 也失败: ${e2.message}")
                    throw e2
                }
            }

            // 保存到数据库（先清空旧数据）
            if (allSatellites.isNotEmpty()) {
                // 清空旧的卫星数据
                dbHelper.deleteAllSatellites()

                // 插入新的卫星数据
                dbHelper.insertSatellites(allSatellites)

                // 记录同步时间
                val syncRecord = SyncRecordEntity(
                    syncType = "tle_celestrak",
                    syncTime = System.currentTimeMillis(),
                    recordCount = allSatellites.size,
                    source = SATNOGS_API_BASE_URL
                )
                dbHelper.insertSyncRecord(syncRecord)
            }

            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    /**
     * 解析 TLE 数据
     * 支持两种格式:
     * 1. JSON格式 (SatNOGS等): tle0, tle1, tle2, norad_cat_id
     * 2. 纯文本格式 (Celestrak): 三行一组 (名称 + TLE Line 1 + TLE Line 2)
     */
    private fun parseTleData(data: String): List<SatelliteEntity> {
        // 首先尝试解析为JSON
        return try {
            if (data.trim().startsWith("[") || data.trim().startsWith("{")) {
                parseJsonTleData(data)
            } else {
                parseTextTleData(data)
            }
        } catch (e: Exception) {
            // JSON解析失败，尝试纯文本格式
            parseTextTleData(data)
        }
    }

    /**
     * 解析 JSON 格式的 TLE 数据
     */
    private fun parseJsonTleData(jsonString: String): List<SatelliteEntity> {
        val satellites = mutableListOf<SatelliteEntity>()

        try {
            val jsonArray = JSONArray(jsonString)

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)

                // 获取NORAD编号
                val noradId = when {
                    obj.has("norad_cat_id") -> obj.optString("norad_cat_id", "")
                    obj.has("noradId") -> obj.optString("noradId", "")
                    else -> ""
                }
                if (noradId.isEmpty()) continue

                // 获取卫星名称
                val name = when {
                    obj.has("tle0") -> obj.optString("tle0", "").removePrefix("0 ").trim()
                    obj.has("name") -> obj.optString("name", "Unknown")
                    else -> "Unknown"
                }

                // 获取TLE数据
                var tleLine1 = ""
                var tleLine2 = ""

                // 首先尝试直接字段
                tleLine1 = obj.optString("tle1", "")
                tleLine2 = obj.optString("tle2", "")

                // 如果直接字段为空，尝试从tle_set获取
                if (tleLine1.isEmpty() || tleLine2.isEmpty()) {
                    if (obj.has("tle_set")) {
                        val tleSet = obj.getJSONArray("tle_set")
                        if (tleSet.length() > 0) {
                            // 获取最新的TLE数据（第一个元素）
                            val latestTle = tleSet.getJSONObject(0)
                            tleLine1 = latestTle.optString("tle1", "")
                            tleLine2 = latestTle.optString("tle2", "")
                        }
                    }
                }

                // 验证TLE数据完整性
                if (tleLine1.isEmpty() || tleLine2.isEmpty()) {
                    android.util.Log.w("TleDataManager", "卫星 $name (NORAD: $noradId) 的TLE数据不完整，跳过")
                    continue
                }

                val satellite = SatelliteEntity(
                    name = name,
                    noradId = noradId,
                    internationalDesignator = obj.optString("international_designator", "").takeIf { it.isNotEmpty() } ?: obj.optString("intl_designator", "").takeIf { it.isNotEmpty() },
                    tleLine1 = tleLine1,
                    tleLine2 = tleLine2,
                    category = obj.optString("status", "").takeIf { it.isNotEmpty() },
                    isFavorite = false,
                    notes = null
                )

                satellites.add(satellite)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return satellites
    }

    /**
     * Validate a single TLE line using the standard modulo-10 checksum.
     * Each digit contributes its face value; each '-' contributes 1; all other
     * characters contribute 0.  The last character of a valid 69-character line
     * is the decimal checksum digit.
     *
     * An invalid checksum indicates either a truncated download, bit corruption,
     * or a tampered response.  Accepting such data would cause TLEPropagator to
     * throw an unchecked exception during propagation, crashing the tracking loop.
     */
    private fun validateTleChecksum(line: String): Boolean {
        if (line.length < 69) return false
        var sum = 0
        for (i in 0 until 68) {
            val c = line[i]
            when {
                c.isDigit() -> sum += c.digitToInt()
                c == '-'    -> sum++
            }
        }
        val expected = sum % 10
        val actual   = line[68].digitToIntOrNull() ?: return false
        return expected == actual
    }

    /**
     * Return true when the string contains only ASCII digits (no leading spaces
     * or signs).  NORAD IDs are purely numeric; a non-numeric value signals a
     * malformed or injected TLE record.
     */
    private fun isNumericNoradId(id: String): Boolean =
        id.isNotEmpty() && id.all { it.isDigit() }

    /**
     * 解析纯文本格式的 TLE 数据 (Celestrak格式)
     * 格式: 三行一组
     *   第0行: 卫星名称
     *   第1行: TLE Line 1 (以"1 "开头)
     *   第2行: TLE Line 2 (以"2 "开头)
     */
    private fun parseTextTleData(text: String): List<SatelliteEntity> {
        val satellites = mutableListOf<SatelliteEntity>()

        try {
            val lines = text.trim().lines()
            var i = 0

            while (i < lines.size) {
                val line = lines[i].trim()

                // 跳过空行
                if (line.isEmpty()) {
                    i++
                    continue
                }

                // 查找TLE第一行 (以"1 "开头)
                if (line.startsWith("1 ") && i > 0) {
                    // 前一行是卫星名称
                    val name = lines[i - 1].trim()

                    // 当前行是TLE Line 1
                    val tleLine1 = line

                    // 下一行应该是TLE Line 2
                    var tleLine2 = ""
                    if (i + 1 < lines.size && lines[i + 1].trim().startsWith("2 ")) {
                        tleLine2 = lines[i + 1].trim()
                    }

                    // 验证TLE数据完整性
                    if (tleLine1.isNotEmpty() && tleLine2.isNotEmpty()) {
                        // 从 TLE Line 1 提取 NORAD ID (第3-7位)
                        val noradId = tleLine1.substring(2, 7).trim()

                        // Reject non-numeric NORAD IDs to prevent SQL injection
                        // or lookup collisions caused by a crafted TLE response.
                        if (!isNumericNoradId(noradId)) {
                            android.util.Log.w("TleDataManager", "NORAD ID 包含非数字字符，跳过: '$noradId'")
                            i++
                            continue
                        }

                        // Validate TLE checksums.  A mismatch means the data was
                        // corrupted in transit or deliberately tampered with;
                        // discard the entry rather than feeding bad orbital data
                        // into the Orekit propagator which would throw an unchecked
                        // exception and crash the tracking coroutine.
                        if (!validateTleChecksum(tleLine1) || !validateTleChecksum(tleLine2)) {
                            android.util.Log.w("TleDataManager", "卫星 $name (NORAD: $noradId) TLE校验和错误，跳过")
                            i++
                            continue
                        }

                        // 从 TLE Line 1 提取国际标识符 (第9-17位)
                        val intlDesignator = tleLine1.substring(9, 17).trim()

                        // Limit name length to guard against OOM when the
                        // database cursor materialises very long strings into UI
                        // widgets.  TLE name fields are at most 24 characters
                        // per NORAD convention; 100 is a generous upper bound.
                        val safeName = name.take(100)

                        val satellite = SatelliteEntity(
                            name = safeName,
                            noradId = noradId,
                            internationalDesignator = intlDesignator.takeIf { it.isNotEmpty() },
                            tleLine1 = tleLine1,
                            tleLine2 = tleLine2,
                            category = "amateur", // Celestrak业余卫星组
                            isFavorite = false,
                            notes = null
                        )

                        satellites.add(satellite)
                        android.util.Log.d("TleDataManager", "解析卫星: $safeName (NORAD: $noradId)")
                    }
                }

                i++
            }

            android.util.Log.i("TleDataManager", "成功解析 ${satellites.size} 颗卫星的TLE数据")
        } catch (e: Exception) {
            android.util.Log.e("TleDataManager", "解析纯文本TLE数据失败", e)
            e.printStackTrace()
        }

        return satellites
    }
}
