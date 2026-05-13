package com.bh6aap.ic705Cter.data.api

import com.bh6aap.ic705Cter.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * API类型验证器
 * 用于检测用户提供的API链接是否返回正确的数据类型
 */
object ApiTypeValidator {

    private const val TAG = "ApiTypeValidator"

    // API类型枚举
    enum class ApiType {
        SATELLITE,      // 卫星数据API
        TRANSMITTER,    // 转发器数据API
        TLE,            // TLE数据API
        UNKNOWN,        // 未知类型
        INVALID         // 无效/无法访问
    }

    // 验证结果
    data class ValidationResult(
        val isValid: Boolean,
        val apiType: ApiType,
        val message: String,
        val sampleData: String? = null
    )

    // HTTP客户端：使用全局安全工厂（关闭重定向、callTimeout、https-only）
    private val client: OkHttpClient by lazy {
        SecureHttp.buildSecureClient(
            connectTimeoutSec = 10L,
            readTimeoutSec = 10L,
            callTimeoutSec = 15L
        )
    }

    /**
     * 验证API链接
     * @param url API链接
     * @param expectedType 期望的API类型（可选）
     * @return 验证结果
     */
    suspend fun validateApi(
        url: String,
        expectedType: ApiType? = null
    ): ValidationResult = withContext(Dispatchers.IO) {
        try {
            LogManager.i(TAG, "开始验证API")

            // SSRF / URL 护栏：只允许 https、禁 userinfo、禁内网/回环/元数据
            val request = SecureHttp.buildValidatedRequest(
                url,
                headers = mapOf("Accept" to "application/json")
            ) ?: return@withContext ValidationResult(
                isValid = false,
                apiType = ApiType.INVALID,
                message = "URL 校验未通过（只允许 https，禁止内网/回环/元数据地址与 userinfo）"
            )

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ValidationResult(
                        isValid = false,
                        apiType = ApiType.INVALID,
                        message = "HTTP错误: ${response.code}"
                    )
                }

                val body = SecureHttp.readLimitedBody(response)
                    ?: return@withContext ValidationResult(
                        isValid = false,
                        apiType = ApiType.INVALID,
                        message = "API返回空或过大数据"
                    )

                val detectedType = detectApiType(body, url)

                if (expectedType != null && detectedType != expectedType) {
                    return@withContext ValidationResult(
                        isValid = false,
                        apiType = detectedType,
                        message = "API类型不匹配。期望: ${getTypeName(expectedType)}, 实际: ${getTypeName(detectedType)}",
                        sampleData = body.take(200)
                    )
                }

                if (detectedType == ApiType.UNKNOWN) {
                    // 之前 UNKNOWN 被当作硬错误，但很多实际源（AMSAT / 自建
                    // mirror / 非严格三行 TLE）会落到这里。让用户能在"警告"
                    // 下继续保存：isValid=true + message 明确提示；
                    // expectedType 匹配不上时仍然走 INVALID 分支。
                    return@withContext ValidationResult(
                        isValid = true,
                        apiType = ApiType.UNKNOWN,
                        message = "API 响应可访问，但无法自动识别格式 (警告)",
                        sampleData = body.take(200)
                    )
                }

                if (detectedType == ApiType.INVALID) {
                    return@withContext ValidationResult(
                        isValid = false,
                        apiType = ApiType.INVALID,
                        message = "API返回的数据格式无效",
                        sampleData = body.take(200)
                    )
                }

                ValidationResult(
                    isValid = true,
                    apiType = detectedType,
                    message = "API验证成功: ${getTypeName(detectedType)}",
                    sampleData = body.take(200)
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IOException) {
            LogManager.e(TAG, "API验证网络错误", e)
            ValidationResult(
                isValid = false,
                apiType = ApiType.INVALID,
                message = "网络错误: ${e.message ?: "无法连接到API"}"
            )
        } catch (e: Exception) {
            LogManager.e(TAG, "API验证错误", e)
            ValidationResult(
                isValid = false,
                apiType = ApiType.INVALID,
                message = "验证错误: ${e.message ?: "未知错误"}"
            )
        }
    }

    /**
     * 检测API数据类型
     * 支持JSON格式和纯文本格式
     */
    private fun detectApiType(data: String, url: String? = null): ApiType {
        // 检查是否是Celestrak TLE格式 (纯文本)
        if (isCelestrakTleFormat(data)) {
            return ApiType.TLE
        }

        // 尝试解析为JSON
        return try {
            // 尝试解析为JSON数组
            val jsonArray = JSONArray(data)
            if (jsonArray.length() == 0) {
                return ApiType.UNKNOWN
            }

            // 获取第一个元素
            val firstItem = jsonArray.getJSONObject(0)
            analyzeJsonStructure(firstItem)
        } catch (e: Exception) {
            // 尝试解析为单个JSON对象
            try {
                val jsonObject = JSONObject(data)
                analyzeJsonStructure(jsonObject)
            } catch (e: Exception) {
                ApiType.INVALID
            }
        }
    }

    /**
     * 检查是否是Celestrak TLE格式
     */
    private fun isCelestrakTleFormat(data: String): Boolean {
        val lines = data.trim().lines()
        if (lines.size < 3) return false

        // 查找至少一组有效的TLE数据
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            // TLE Line 1 以 "1 " 开头，长度为69字符
            // TLE Line 2 以 "2 " 开头，长度为69字符
            if (line.startsWith("1 ") && line.length >= 69) {
                // 检查下一行是否是TLE Line 2
                if (i + 1 < lines.size) {
                    val nextLine = lines[i + 1].trim()
                    if (nextLine.startsWith("2 ") && nextLine.length >= 69) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * 分析JSON结构来确定API类型
     */
    private fun analyzeJsonStructure(json: JSONObject): ApiType {
        val keys = json.keys().asSequence().toList()

        // 检查卫星数据特征
        if (keys.contains("norad_cat_id") && keys.contains("name")) {
            return ApiType.SATELLITE
        }
        if (keys.contains("satellite_id") && keys.contains("name")) {
            return ApiType.SATELLITE
        }

        // 检查转发器数据特征
        if (keys.contains("uuid") && keys.contains("norad_cat_id") &&
            (keys.contains("downlink_low") || keys.contains("uplink_low"))) {
            return ApiType.TRANSMITTER
        }
        if (keys.contains("description") && keys.contains("satellite_id") &&
            (keys.contains("downlink_low") || keys.contains("uplink_low"))) {
            return ApiType.TRANSMITTER
        }

        // 检查TLE数据特征
        if (keys.contains("tle_line1") && keys.contains("tle_line2")) {
            return ApiType.TLE
        }
        if (keys.contains("line1") && keys.contains("line2")) {
            return ApiType.TLE
        }
        if (keys.contains("tle") && keys.contains("satellite_id")) {
            return ApiType.TLE
        }

        // 检查其他可能的字段组合
        if (keys.contains("satelliteId") || keys.contains("satellite_id")) {
            // 可能是卫星相关数据，进一步判断
            if (keys.contains("frequency") || keys.contains("downlink") || keys.contains("uplink")) {
                return ApiType.TRANSMITTER
            }
            if (keys.contains("tleLine1") || keys.contains("tleLine2")) {
                return ApiType.TLE
            }
            return ApiType.SATELLITE
        }

        return ApiType.UNKNOWN
    }

    /**
     * 获取API类型的显示名称
     */
    fun getTypeName(type: ApiType): String {
        return when (type) {
            ApiType.SATELLITE -> "卫星数据API"
            ApiType.TRANSMITTER -> "转发器数据API"
            ApiType.TLE -> "TLE数据API"
            ApiType.UNKNOWN -> "未知类型"
            ApiType.INVALID -> "无效API"
        }
    }

    /**
     * 获取API类型的建议字段
     */
    fun getSuggestedFields(type: ApiType): String {
        return when (type) {
            ApiType.SATELLITE -> "建议字段: norad_cat_id, name, international_designator"
            ApiType.TRANSMITTER -> "建议字段: uuid, norad_cat_id, description, downlink_low, uplink_low"
            ApiType.TLE -> "建议字段: norad_cat_id, tle_line1, tle_line2, epoch"
            else -> ""
        }
    }
}
