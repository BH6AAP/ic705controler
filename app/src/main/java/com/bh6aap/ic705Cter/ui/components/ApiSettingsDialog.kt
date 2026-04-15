package com.bh6aap.ic705Cter.ui.components

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bh6aap.ic705Cter.data.api.ApiTypeValidator
import com.bh6aap.ic705Cter.data.api.TleDataManager
import com.bh6aap.ic705Cter.data.database.CustomApiDatabaseManager
import com.bh6aap.ic705Cter.util.LogManager
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.bh6aap.ic705Cter.R

/**
 * API设置对话框
 * 用于配置用户自定义API地址
 */
@Composable
fun ApiSettingsDialog(
    onDismiss: () -> Unit,
    onApiSaved: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { ApiSettingsPreferences.getInstance(context) }
    val scope = rememberCoroutineScope()

    var satelliteApiUrl by remember { mutableStateOf(prefs.getSatelliteApiUrl()) }
    var transmitterApiUrl by remember { mutableStateOf(prefs.getTransmitterApiUrl()) }
    var tleApiUrl by remember { mutableStateOf(prefs.getTleApiUrl()) }

    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }

    // 测试单个API
    fun testSingleApi(url: String, expectedType: ApiTypeValidator.ApiType, context: Context) {
        if (url.isBlank()) {
            testResult = TestResult(
                isSuccess = true,
                message = context.getString(R.string.api_test_use_default),
                details = null
            )
            return
        }

        scope.launch {
            isTesting = true
            testResult = null

            val result = ApiTypeValidator.validateApi(url, expectedType)

            isTesting = false
            testResult = TestResult(
                isSuccess = result.isValid,
                message = result.message,
                details = result.sampleData?.let { context.getString(R.string.api_sample_data, it) }
                        )
        }
    }

    // 测试所有API
    fun testAllApis() {
        scope.launch {
            isTesting = true
            testResult = null

            val results = mutableListOf<String>()
            var allValid = true

            // 测试卫星API
            if (satelliteApiUrl.isNotBlank()) {
                val result = ApiTypeValidator.validateApi(
                    satelliteApiUrl,
                    ApiTypeValidator.ApiType.SATELLITE
                )
                results.add(context.getString(R.string.api_result_satellite, result.message))
                if (!result.isValid) allValid = false
            }

            // 测试转发器API
            if (transmitterApiUrl.isNotBlank()) {
                val result = ApiTypeValidator.validateApi(
                    transmitterApiUrl,
                    ApiTypeValidator.ApiType.TRANSMITTER
                )
                results.add(context.getString(R.string.api_result_transmitter, result.message))
                if (!result.isValid) allValid = false
            }

            // 测试TLE API
            if (tleApiUrl.isNotBlank()) {
                val result = ApiTypeValidator.validateApi(
                    tleApiUrl,
                    ApiTypeValidator.ApiType.TLE
                )
                results.add(context.getString(R.string.api_result_tle, result.message))
                if (!result.isValid) allValid = false
            }

            if (results.isEmpty()) {
                results.add(context.getString(R.string.api_result_no_custom))
            }

            isTesting = false
            testResult = TestResult(
                isSuccess = allValid,
                message = results.joinToString("\n"),
                details = null
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 标题
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.api_settings_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.common_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.api_settings_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 卫星数据API
                OutlinedTextField(
                    value = satelliteApiUrl,
                    onValueChange = { satelliteApiUrl = it },
                    label = { Text(stringResource(R.string.api_satellite)) },
                    placeholder = { Text(stringResource(R.string.api_satellite_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )

                // 转发器数据API
                OutlinedTextField(
                    value = transmitterApiUrl,
                    onValueChange = { transmitterApiUrl = it },
                    label = { Text(stringResource(R.string.api_transmitter)) },
                    placeholder = { Text(stringResource(R.string.api_transmitter_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )

                // TLE数据API
                OutlinedTextField(
                    value = tleApiUrl,
                    onValueChange = { tleApiUrl = it },
                    label = { Text(stringResource(R.string.api_tle)) },
                    placeholder = { Text(stringResource(R.string.api_tle_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )

                // 测试结果提示
                testResult?.let { result ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (result.isSuccess)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text(
                                text = result.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (result.isSuccess)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onErrorContainer
                            )
                            result.details?.let { details ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = details,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // 按钮行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 测试连接按钮
                    OutlinedButton(
                        onClick = { testAllApis() },
                        modifier = Modifier.weight(1f),
                        enabled = !isTesting,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isTesting) stringResource(R.string.api_testing) else stringResource(R.string.api_test))
                    }

                    // 保存按钮
                    Button(
                        onClick = {
                            scope.launch {
                                isTesting = true
                                testResult = TestResult(isSuccess = true, message = context.getString(R.string.api_saving), details = null)

                                // 保存API设置
                                prefs.saveApiUrls(
                                    satelliteApiUrl = satelliteApiUrl.trim(),
                                    transmitterApiUrl = transmitterApiUrl.trim(),
                                    tleApiUrl = tleApiUrl.trim()
                                )
                                LogManager.i("ApiSettings", "API设置已保存")

                                // 如果设置了自定义TLE API，自动获取数据
                                val tleUrl = tleApiUrl.trim()
                                if (tleUrl.isNotBlank()) {
                                    testResult = TestResult(isSuccess = true, message = context.getString(R.string.api_fetching_custom), details = null)

                                    try {
                                        // 初始化自定义数据库
                                        val customDbManager = CustomApiDatabaseManager.getInstance(context)
                                        customDbManager.initializeDatabases()

                                        // 创建临时的TLE数据管理器使用自定义API
                                        val tleDataManager = TleDataManager(context)

                                        // 获取TLE数据
                                        val success = fetchCustomTleData(context, tleUrl)

                                        if (success) {
                                            testResult = TestResult(
                                                isSuccess = true,
                                                message = context.getString(R.string.api_save_success),
                                                details = context.getString(R.string.api_save_success_details)
                                            )
                                        } else {
                                            testResult = TestResult(
                                                isSuccess = false,
                                                message = context.getString(R.string.api_save_failed),
                                                details = context.getString(R.string.api_save_failed_check)
                                            )
                                        }
                                    } catch (e: Exception) {
                                        LogManager.e("ApiSettings", "获取自定义TLE数据失败", e)
                                        testResult = TestResult(
                                            isSuccess = false,
                                            message = context.getString(R.string.api_save_failed_error, e.message ?: ""),
                                            details = null
                                        )
                                    }
                                } else {
                                    testResult = TestResult(isSuccess = true, message = context.getString(R.string.api_save_success_default), details = context.getString(R.string.api_save_success_default_details))
                                }

                                isTesting = false
                                onApiSaved()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.common_save))
                    }
                }

                // 重置为默认按钮
                TextButton(
                    onClick = {
                        satelliteApiUrl = ""
                        transmitterApiUrl = ""
                        tleApiUrl = ""
                        prefs.clearApiUrls()
                        testResult = TestResult(isSuccess = true, message = context.getString(R.string.api_reset_success), details = null)
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        stringResource(R.string.api_reset_default),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 从自定义API获取TLE数据
 */
suspend fun fetchCustomTleData(context: Context, url: String): Boolean {
    return try {
        LogManager.i("ApiSettings", "从自定义API获取TLE数据: $url")

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Accept", "text/plain, */*")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                LogManager.e("ApiSettings", "HTTP错误: ${response.code}")
                return false
            }

            val body = response.body?.string()
                ?: return false

            // 解析TLE数据
            val satellites = parseTleTextData(body)

            if (satellites.isNotEmpty()) {
                // 保存到自定义数据库
                val customDbManager = CustomApiDatabaseManager.getInstance(context)
                customDbManager.saveSatellites(satellites)
                LogManager.i("ApiSettings", "成功保存 ${satellites.size} 颗卫星到自定义数据库")
                return true
            } else {
                LogManager.w("ApiSettings", "未解析到任何卫星数据")
                return false
            }
        }
    } catch (e: Exception) {
        LogManager.e("ApiSettings", "获取自定义TLE数据失败", e)
        return false
    }
}

/**
 * 解析纯文本TLE数据
 */
private fun parseTleTextData(text: String): List<com.bh6aap.ic705Cter.data.database.entity.SatelliteEntity> {
    val satellites = mutableListOf<com.bh6aap.ic705Cter.data.database.entity.SatelliteEntity>()

    try {
        val lines = text.trim().lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()

            if (line.isEmpty()) {
                i++
                continue
            }

            // 查找TLE第一行
            if (line.startsWith("1 ") && i > 0) {
                val name = lines[i - 1].trim()
                val tleLine1 = line
                var tleLine2 = ""

                if (i + 1 < lines.size && lines[i + 1].trim().startsWith("2 ")) {
                    tleLine2 = lines[i + 1].trim()
                }

                if (tleLine1.isNotEmpty() && tleLine2.isNotEmpty()) {
                    val noradId = tleLine1.substring(2, 7).trim()

                    // Reject non-numeric NORAD IDs (same hardening as TleDataManager)
                    if (noradId.isEmpty() || !noradId.all { it.isDigit() }) {
                        i++
                        continue
                    }

                    // Validate TLE checksums (mod-10) to reject corrupted data
                    if (!validateTleChecksumLocal(tleLine1) || !validateTleChecksumLocal(tleLine2)) {
                        i++
                        continue
                    }

                    val intlDesignator = tleLine1.substring(9, 17).trim()

                    satellites.add(
                        com.bh6aap.ic705Cter.data.database.entity.SatelliteEntity(
                            name = name.take(100),
                            noradId = noradId,
                            internationalDesignator = intlDesignator.takeIf { it.isNotEmpty() },
                            tleLine1 = tleLine1,
                            tleLine2 = tleLine2,
                            category = "amateur",
                            isFavorite = false,
                            notes = null
                        )
                    )
                }
            }
            i++
        }
    } catch (e: Exception) {
        LogManager.e("ApiSettings", "解析TLE数据失败", e)
    }

    return satellites
}

/**
 * Validate a TLE line using the standard modulo-10 checksum.
 * Mirrors TleDataManager.validateTleChecksum.
 */
private fun validateTleChecksumLocal(line: String): Boolean {
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
    val actual = line[68].digitToIntOrNull() ?: return false
    return expected == actual
}

/**
 * 测试结果数据类
 */
data class TestResult(
    val isSuccess: Boolean,
    val message: String,
    val details: String?
)

/**
 * API设置偏好存储
 */
class ApiSettingsPreferences private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "api_settings"
        private const val KEY_SATELLITE_API = "satellite_api_url"
        private const val KEY_TRANSMITTER_API = "transmitter_api_url"
        private const val KEY_TLE_API = "tle_api_url"

        // 默认API地址
        const val DEFAULT_SATELLITE_API = "https://db.satnogs.org/api/satellites/"
        const val DEFAULT_TRANSMITTER_API = "https://db.satnogs.org/api/transmitters/"
        const val DEFAULT_TLE_API = "https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=tle"

        @Volatile
        private var instance: ApiSettingsPreferences? = null

        fun getInstance(context: Context): ApiSettingsPreferences {
            return instance ?: synchronized(this) {
                instance ?: ApiSettingsPreferences(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    fun getSatelliteApiUrl(): String {
        return prefs.getString(KEY_SATELLITE_API, "") ?: ""
    }

    fun getTransmitterApiUrl(): String {
        return prefs.getString(KEY_TRANSMITTER_API, "") ?: ""
    }

    fun getTleApiUrl(): String {
        return prefs.getString(KEY_TLE_API, "") ?: ""
    }

    fun saveApiUrls(
        satelliteApiUrl: String,
        transmitterApiUrl: String,
        tleApiUrl: String
    ) {
        prefs.edit().apply {
            putString(KEY_SATELLITE_API, satelliteApiUrl)
            putString(KEY_TRANSMITTER_API, transmitterApiUrl)
            putString(KEY_TLE_API, tleApiUrl)
            apply()
        }
    }

    fun clearApiUrls() {
        prefs.edit().clear().apply()
    }

    /**
     * 获取实际使用的API地址（如果用户设置了则使用用户的，否则使用默认）
     */
    fun getEffectiveSatelliteApiUrl(): String {
        return getSatelliteApiUrl().takeIf { it.isNotBlank() } ?: DEFAULT_SATELLITE_API
    }

    fun getEffectiveTransmitterApiUrl(): String {
        return getTransmitterApiUrl().takeIf { it.isNotBlank() } ?: DEFAULT_TRANSMITTER_API
    }

    fun getEffectiveTleApiUrl(): String {
        return getTleApiUrl().takeIf { it.isNotBlank() } ?: DEFAULT_TLE_API
    }
}
