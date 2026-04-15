package com.bh6aap.ic705Cter

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.bh6aap.ic705Cter.data.api.SatelliteDataManager
import com.bh6aap.ic705Cter.data.api.TleDataManager
import com.bh6aap.ic705Cter.data.database.DatabaseHelper
import com.bh6aap.ic705Cter.data.database.entity.CustomTransmitterEntity
import com.bh6aap.ic705Cter.data.database.entity.SatelliteEntity
import com.bh6aap.ic705Cter.data.database.entity.StationEntity
import com.bh6aap.ic705Cter.data.database.entity.TransmitterEntity
import com.bh6aap.ic705Cter.data.location.GpsManager
import com.bh6aap.ic705Cter.data.location.LocationData
import com.bh6aap.ic705Cter.data.time.NtpTimeManager
import com.bh6aap.ic705Cter.ui.theme.Ic705controlerTheme
import com.bh6aap.ic705Cter.util.LogManager
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class SplashActivity : BaseActivity() {

    private lateinit var permissionManager: PermissionManager
    private lateinit var gpsManager: GpsManager
    private lateinit var ntpTimeManager: NtpTimeManager

    // 用于UI状态同步的状态
    private var currentStepState = mutableStateOf(InitStep.PERMISSION_CHECK)
    private var stepMessageState = mutableStateOf("")

    companion object {
        private const val TAG = "SplashActivity"
        private const val GPS_TIMEOUT_MS = 10000L // GPS超时10秒
        private const val NETWORK_TIMEOUT_MS = 10000L // 网络定位超时10秒

        // 数据有效期常量
        private const val TLE_DATA_VALIDITY_HOURS = 12L      // TLE数据有效期：12小时
        private const val SATELLITE_INFO_VALIDITY_DAYS = 30L  // 卫星信息有效期：30天
        private const val TRANSMITTER_VALIDITY_DAYS = 30L     // 转发器数据有效期：30天
    }

    /**
     * 初始化步骤枚举
     */
    enum class InitStep {
        PERMISSION_CHECK,      // 权限检查
        NTP_SYNC,             // NTP时间同步
        GPS_LOCATION,         // GPS位置获取（GPS与GMS同步）
        SATELLITE_INFO_SYNC,  // 卫星信息同步（包含别名）
        TLE_SYNC,             // TLE数据同步
        OREKIT_INIT,          // Orekit初始化
        COMPLETE              // 完成
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        LogManager.enterMethod(LogManager.TAG_SPLASH, "onCreate")
        super.onCreate(savedInstanceState)
        LogManager.i(LogManager.TAG_SPLASH, "【应用启动】IC-705 卫星控制器启动中...")

        enableEdgeToEdge()
        LogManager.d(LogManager.TAG_SPLASH, "【UI设置】EdgeToEdge 已启用")

        // 初始化管理器（轻量级初始化）
        LogManager.stepStart(LogManager.TAG_SPLASH, "初始化管理器")
        permissionManager = PermissionManager(this)
        gpsManager = GpsManager(this)
        ntpTimeManager = NtpTimeManager(this)
        LogManager.stepComplete(LogManager.TAG_SPLASH, "初始化管理器")

        setContent {
            Ic705controlerTheme {
                // 直接使用类级别的状态，不使用 remember
                val currentStep = currentStepState.value
                val stepMessage = stepMessageState.value

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SplashScreen(
                        modifier = Modifier.padding(innerPadding),
                        permissionManager = permissionManager,
                        currentStep = currentStep,
                        stepMessage = stepMessage,
                        onPermissionsGranted = {
                            // 权限被授予后重新启动初始化
                            LogManager.i(LogManager.TAG_PERMISSION, "【权限回调】用户授予权限，重新启动初始化")
                            initializeApp()
                        },
                        onLoadingComplete = {
                            navigateToMainActivity()
                        }
                    )
                }
            }
        }
        LogManager.d(LogManager.TAG_SPLASH, "【UI设置】Compose 界面已设置")

        // 启动初始化流程
        LogManager.stepStart(LogManager.TAG_SPLASH, "应用初始化流程")
        initializeApp()
    }

    private fun initializeApp() {
        lifecycleScope.launch {
            LogManager.i(LogManager.TAG_SPLASH, "【初始化开始】============================")

            // 1. 检查权限
            updateStep(InitStep.PERMISSION_CHECK, getString(R.string.splash_permission_checking))
            LogManager.stepStart(LogManager.TAG_PERMISSION, "检查应用权限")
            val hasPermissions = permissionManager.hasAllPermissions()
            LogManager.permissionStatus(LogManager.TAG_PERMISSION, "所有必要权限", hasPermissions)

            if (!hasPermissions) {
                LogManager.w(LogManager.TAG_PERMISSION, "【权限不足】等待用户授予权限")
                updateStep(InitStep.PERMISSION_CHECK, getString(R.string.splash_permission_required))
                return@launch
            }
            LogManager.stepComplete(LogManager.TAG_PERMISSION, "权限检查")

            // 检查是否首次运行
            val sharedPreferences = getSharedPreferences("app_settings", MODE_PRIVATE)
            val isFirstRun = sharedPreferences.getBoolean("is_first_run", true)
            
            // 仅在首次运行时请求电池策略
            if (isFirstRun) {
                updateStep(InitStep.PERMISSION_CHECK, getString(R.string.splash_battery_optimization))
                LogManager.stepStart(LogManager.TAG_PERMISSION, "请求忽略电池优化")
                requestIgnoreBatteryOptimizations()
                // 添加延迟，确保用户有时间返回应用
                delay(2000)
                LogManager.stepComplete(LogManager.TAG_PERMISSION, "请求忽略电池优化")
                
                // 标记为非首次运行
                sharedPreferences.edit().putBoolean("is_first_run", false).apply()
                LogManager.i(LogManager.TAG_PERMISSION, "【权限】标记为非首次运行")
            } else {
                LogManager.i(LogManager.TAG_PERMISSION, "【权限】非首次运行，跳过电池优化请求")
            }

            // ========== 步骤1: NTP时间同步 ==========
            updateStep(InitStep.NTP_SYNC, getString(R.string.splash_ntp_syncing))
            LogManager.i(LogManager.TAG_SPLASH, "【步骤1/3】开始NTP时间同步")
            val ntpSuccess = performNtpSync()
            if (!ntpSuccess) {
                LogManager.w(LogManager.TAG_NTP, "【NTP警告】时间同步失败，但继续执行")
                // NTP失败不终止，继续执行
            }
            LogManager.i(LogManager.TAG_SPLASH, "【步骤1/3】NTP时间同步完成")

            // ========== 步骤2: GPS位置获取（GPS与GMS同步进行）==========
            updateStep(InitStep.GPS_LOCATION, getString(R.string.splash_gps_locating))
            LogManager.i(LogManager.TAG_SPLASH, "【步骤2/5】开始GPS位置获取（GPS与网络同步）")
            val locationData = getLocationParallel()

            if (locationData != null) {
                // 保存位置到数据库
                withContext(Dispatchers.IO) {
                    val dbHelper = DatabaseHelper.getInstance(this@SplashActivity)
                    saveLocationToDatabase(dbHelper, locationData)
                }
                LogManager.i(LogManager.TAG_SPLASH, "【步骤2/5】GPS位置获取完成")
            } else {
                // GPS获取失败或超时，检查是否已有地面站数据
                val hasStationData = withContext(Dispatchers.IO) {
                    val dbHelper = DatabaseHelper.getInstance(this@SplashActivity)
                    dbHelper.hasStationData()
                }
                if (hasStationData) {
                    LogManager.i(LogManager.TAG_GPS, "【GPS失败】使用现有地面站数据继续")
                    updateStep(InitStep.GPS_LOCATION, getString(R.string.splash_gps_using_existing))
                } else {
                    LogManager.w(LogManager.TAG_GPS, "【GPS失败】无法获取位置且无历史数据，跳过GPS步骤继续初始化")
                    updateStep(InitStep.GPS_LOCATION, getString(R.string.splash_gps_failed))
                }
                delay(500)
                // 无论是否有历史数据，都继续后续初始化步骤
            }

            // ========== 步骤3: TLE卫星数据（从API获取，不使用内置数据）==========
            updateStep(InitStep.SATELLITE_INFO_SYNC, getString(R.string.splash_satellite_syncing))
            LogManager.i(LogManager.TAG_SPLASH, "【步骤3/5】开始TLE卫星数据获取")
            val tleSuccess = performTleSync()
            if (!tleSuccess) {
                LogManager.w(LogManager.TAG_TLE, "【TLE警告】卫星数据获取失败，但继续执行")
            }
            LogManager.i(LogManager.TAG_SPLASH, "【步骤3/5】TLE卫星数据获取完成")

            // ========== 步骤4: 加载转发器数据（优先使用内置数据）==========
            updateStep(InitStep.TLE_SYNC, getString(R.string.splash_transmitter_loading))
            LogManager.i(LogManager.TAG_SPLASH, "【步骤4/5】开始加载转发器数据")
            val transmitterDataSuccess = loadBuiltinTransmitterData()
            if (!transmitterDataSuccess) {
                LogManager.w(LogManager.TAG_SATELLITE, "【转发器数据警告】加载失败，尝试从API获取...")
                // 内置数据加载失败，尝试从API获取
                val satelliteInfoSuccess = performSatelliteInfoSync()
                if (!satelliteInfoSuccess) {
                    LogManager.w(LogManager.TAG_SATELLITE, "【卫星信息警告】API获取也失败")
                }
            }
            LogManager.i(LogManager.TAG_SPLASH, "【步骤4/5】转发器数据加载完成")

            // ========== 步骤5: 初始化 Orekit ==========
            updateStep(InitStep.OREKIT_INIT, getString(R.string.splash_orekit_init))
            LogManager.i(LogManager.TAG_SPLASH, "【步骤5/5】开始Orekit初始化")
            performOrekitInit()
            LogManager.i(LogManager.TAG_SPLASH, "【步骤5/5】Orekit初始化完成")

            // 所有步骤完成，显示完成状态
            updateStep(InitStep.COMPLETE, getString(R.string.splash_init_complete))

            // 延迟确保用户看到完成状态
            LogManager.d(LogManager.TAG_SPLASH, "【UI延迟】显示完成状态 1.5 秒")
            delay(1500)

            // 进入主界面
            LogManager.i(LogManager.TAG_SPLASH, "【初始化完成】准备进入主界面")
            LogManager.i(LogManager.TAG_SPLASH, "【初始化结束】============================")
            navigateToMainActivity()
        }
    }

    /**
     * 更新当前步骤状态（在主线程执行）
     */
    private fun updateStep(step: InitStep, message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            currentStepState.value = step
            stepMessageState.value = message
            LogManager.d(LogManager.TAG_SPLASH, "【UI状态】$message")
        }
    }

    /**
     * 并行获取位置（GPS和网络定位同时进行，谁先返回用谁）
     */
    private suspend fun getLocationParallel(): LocationData? {
        LogManager.stepStart(LogManager.TAG_GPS, "并行获取位置（GPS与网络同步，超时: ${GPS_TIMEOUT_MS/1000}秒）")

        var locationData: LocationData? = null

        try {
            // 同时启动GPS和网络定位，谁先成功返回就用谁
            val gpsDeferred = lifecycleScope.async(Dispatchers.IO) {
                try {
                    gpsManager.getCurrentLocationModern(timeoutMs = GPS_TIMEOUT_MS)
                } catch (e: Exception) {
                    LogManager.w(LogManager.TAG_GPS, "【GPS】获取失败: ${e.message}")
                    null
                }
            }

            val networkDeferred = lifecycleScope.async(Dispatchers.IO) {
                try {
                    gpsManager.getNetworkLocation(timeoutMs = NETWORK_TIMEOUT_MS)
                } catch (e: Exception) {
                    LogManager.w(LogManager.TAG_GPS, "【网络定位】获取失败: ${e.message}")
                    null
                }
            }

            // 等待任意一个完成
            val location = withTimeoutOrNull(GPS_TIMEOUT_MS) {
                select {
                    gpsDeferred.onAwait { location ->
                        if (location != null) {
                            LogManager.i(LogManager.TAG_GPS, "【GPS优先】GPS位置先返回")
                            location
                        } else {
                            // GPS返回null，继续等待网络定位
                            networkDeferred.await()
                        }
                    }
                    networkDeferred.onAwait { location ->
                        if (location != null) {
                            LogManager.i(LogManager.TAG_GPS, "【网络优先】网络位置先返回")
                            location
                        } else {
                            // 网络返回null，继续等待GPS
                            gpsDeferred.await()
                        }
                    }
                }
            }

            if (location != null) {
                locationData = LocationData.fromLocation(location)
                val providerName = if (location.provider == android.location.LocationManager.GPS_PROVIDER) "GPS" else "网络定位"
                LogManager.i(LogManager.TAG_GPS, "========================================")
                LogManager.i(LogManager.TAG_GPS, "【$providerName 位置获取成功】精度: ${locationData.accuracy}米, 提供者: ${locationData.provider}")
                LogManager.d(LogManager.TAG_GPS, "  纬度: ${locationData.latitude}")
                LogManager.d(LogManager.TAG_GPS, "  经度: ${locationData.longitude}")
                LogManager.d(LogManager.TAG_GPS, "  海拔: ${locationData.altitude} 米")
                LogManager.i(LogManager.TAG_GPS, "========================================")
            } else {
                LogManager.w(LogManager.TAG_GPS, "【位置获取超时】GPS和网络定位均未在${GPS_TIMEOUT_MS/1000}秒内返回")
            }
        } catch (e: Exception) {
            LogManager.e(LogManager.TAG_GPS, "【位置获取异常】", e)
        }

        LogManager.stepComplete(LogManager.TAG_GPS, "并行位置获取")
        return locationData
    }

    /**
     * NTP时间同步 - 返回是否成功
     */
    private suspend fun performNtpSync(): Boolean {
        LogManager.stepStart(LogManager.TAG_NTP, "NTP网络时间同步")
        var ntpSuccess = false

        try {
            val ntpResult = withContext(Dispatchers.IO) {
                ntpTimeManager.syncTime()
            }
            ntpSuccess = ntpResult.success
            if (ntpSuccess) {
                LogManager.timeSyncInfo(LogManager.TAG_NTP, ntpResult.ntpTime ?: 0, ntpResult.offset)
                LogManager.i(LogManager.TAG_NTP, "【NTP成功】服务器: ntp1.aliyun.com, 延迟: ${ntpResult.roundTripDelay}ms")
            } else {
                LogManager.w(LogManager.TAG_NTP, "【NTP失败】${ntpResult.errorMessage}")
            }
        } catch (e: CancellationException) {
            LogManager.d(LogManager.TAG_NTP, "【NTP取消】时间同步被取消")
        } catch (e: Exception) {
            LogManager.e(LogManager.TAG_NTP, "【NTP异常】时间同步过程发生错误", e)
        }
        LogManager.stepComplete(LogManager.TAG_NTP, "NTP时间同步")
        return ntpSuccess
    }

    /**
     * 卫星信息同步（包含别名）- 返回是否成功
     */
    private suspend fun performSatelliteInfoSync(): Boolean {
        LogManager.stepStart(LogManager.TAG_SATELLITE, "检查卫星信息数据")
        var needSync = false
        var syncSuccess = false
        var infoCount = 0

        try {
            needSync = withContext(Dispatchers.IO) {
                val satelliteDataManager = SatelliteDataManager(this@SplashActivity)
                satelliteDataManager.needSync(maxAgeHours = 168) // 7天有效期
            }
            LogManager.i(LogManager.TAG_SATELLITE, "【卫星信息检查】是否需要同步: $needSync")

            if (needSync) {
                LogManager.stepStart(LogManager.TAG_SATELLITE, "从SatNOGS下载卫星信息")
                LogManager.networkRequest(LogManager.TAG_SATELLITE, "https://db.satnogs.org/api/satellites/")

                syncSuccess = withContext(Dispatchers.IO) {
                    val satelliteDataManager = SatelliteDataManager(this@SplashActivity)
                    satelliteDataManager.fetchSatelliteInfo()
                }

                LogManager.networkResponse(LogManager.TAG_SATELLITE, "https://db.satnogs.org/api/satellites/", syncSuccess)

                if (syncSuccess) {
                    infoCount = withContext(Dispatchers.IO) {
                        val dbHelper = DatabaseHelper.getInstance(this@SplashActivity)
                        dbHelper.getSatelliteInfoCount()
                    }
                    LogManager.dataLoaded(LogManager.TAG_SATELLITE, "卫星信息", infoCount)
                } else {
                    LogManager.w(LogManager.TAG_SATELLITE, "【卫星信息下载失败】将继续使用现有数据")
                }
            } else {
                infoCount = withContext(Dispatchers.IO) {
                    val dbHelper = DatabaseHelper.getInstance(this@SplashActivity)
                    dbHelper.getSatelliteInfoCount()
                }
                LogManager.i(LogManager.TAG_SATELLITE, "【卫星信息跳过】数据在有效期内，现有: $infoCount 颗卫星")
                syncSuccess = true // 不需要同步也算成功
            }
        } catch (e: CancellationException) {
            LogManager.d(LogManager.TAG_SATELLITE, "【卫星信息取消】同步被取消")
        } catch (e: Exception) {
            LogManager.e(LogManager.TAG_SATELLITE, "【卫星信息异常】同步过程发生错误", e)
        }
        LogManager.stepComplete(LogManager.TAG_SATELLITE, "卫星信息数据检查")
        return syncSuccess
    }

    /**
     * TLE卫星数据同步 - 返回是否成功
     * 添加10秒超时，超时后跳过并提示
     */
    private suspend fun performTleSync(): Boolean {
        LogManager.stepStart(LogManager.TAG_TLE, "检查TLE卫星数据")
        var tleNeedSync = false
        var tleSyncSuccess = false
        var satelliteCount = 0

        try {
            tleNeedSync = withContext(Dispatchers.IO) {
                val tleDataManager = TleDataManager(this@SplashActivity)
                tleDataManager.needSync(maxAgeHours = 24)
            }
            LogManager.i(LogManager.TAG_TLE, "【TLE检查】是否需要同步: $tleNeedSync")

            if (tleNeedSync) {
                LogManager.stepStart(LogManager.TAG_TLE, "从Celestrak下载卫星数据")
                LogManager.networkRequest(LogManager.TAG_TLE, "https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=tle")

                // 添加10秒超时
                val tleResult = withTimeoutOrNull(10000L) {
                    withContext(Dispatchers.IO) {
                        val tleDataManager = TleDataManager(this@SplashActivity)
                        tleDataManager.fetchTleData()
                    }
                }

                if (tleResult == null) {
                    // 超时
                    LogManager.w(LogManager.TAG_TLE, "【TLE超时】10秒内未获取完整数据，跳过")
                    updateStep(InitStep.TLE_SYNC, getString(R.string.splash_tle_timeout))
                    tleSyncSuccess = false
                } else {
                    tleSyncSuccess = tleResult
                    LogManager.networkResponse(LogManager.TAG_TLE, "https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=tle", tleSyncSuccess)

                    if (tleSyncSuccess) {
                        satelliteCount = withContext(Dispatchers.IO) {
                            val dbHelper = DatabaseHelper.getInstance(this@SplashActivity)
                            dbHelper.getSatelliteCount()
                        }
                        LogManager.dataLoaded(LogManager.TAG_TLE, "卫星数据", satelliteCount)
                    } else {
                        LogManager.w(LogManager.TAG_TLE, "【TLE下载失败】将继续使用现有数据")
                    }
                }
            } else {
                satelliteCount = withContext(Dispatchers.IO) {
                    val dbHelper = DatabaseHelper.getInstance(this@SplashActivity)
                    dbHelper.getSatelliteCount()
                }
                LogManager.i(LogManager.TAG_TLE, "【TLE跳过】数据在有效期内，现有卫星: $satelliteCount 颗")
                tleSyncSuccess = true // 不需要同步也算成功
            }
        } catch (e: CancellationException) {
            LogManager.d(LogManager.TAG_TLE, "【TLE取消】卫星数据获取被取消")
        } catch (e: Exception) {
            LogManager.e(LogManager.TAG_TLE, "【TLE异常】卫星数据获取过程发生错误", e)
        }
        LogManager.stepComplete(LogManager.TAG_TLE, "TLE卫星数据检查")
        return tleSyncSuccess
    }

    private suspend fun performOrekitInit() {
        LogManager.stepStart(LogManager.TAG_OREKIT, "初始化Orekit卫星库")
        try {
            withContext(Dispatchers.IO) {
                OrekitInitializer.initialize(this@SplashActivity)
            }
            LogManager.i(LogManager.TAG_OREKIT, "【Orekit成功】卫星轨道计算库已初始化")
        } catch (e: CancellationException) {
            LogManager.d(LogManager.TAG_OREKIT, "【Orekit取消】初始化被取消")
        } catch (e: Exception) {
            LogManager.e(LogManager.TAG_OREKIT, "【Orekit异常】初始化过程发生错误", e)
        }
        LogManager.stepComplete(LogManager.TAG_OREKIT, "Orekit初始化")
    }

    private suspend fun saveLocationToDatabase(dbHelper: DatabaseHelper, locationData: LocationData) {
        LogManager.databaseOperation(LogManager.TAG_DATABASE, "保存地面站位置", "stations")

        val station = StationEntity(
            name = getString(R.string.main_current_location),
            latitude = locationData.latitude,
            longitude = locationData.longitude,
            altitude = locationData.altitude,
            isDefault = true,
            notes = getString(R.string.main_gps_location_saved, locationData.accuracy, locationData.provider)
        )

        try {
            dbHelper.clearDefaultStation()
            LogManager.d(LogManager.TAG_DATABASE, "【数据库】已清除之前的默认地面站")

            val stationId = dbHelper.insertStation(station)
            LogManager.i(LogManager.TAG_DATABASE, "【数据库】地面站已保存，ID: $stationId")
            LogManager.d(LogManager.TAG_DATABASE, "【数据库】位置详情: 纬度=${station.latitude}, 经度=${station.longitude}, 海拔=${station.altitude}")
        } catch (e: Exception) {
            LogManager.e(LogManager.TAG_DATABASE, "【数据库异常】保存地面站失败", e)
            throw e
        }
    }

    private fun navigateToMainActivity() {
        LogManager.enterMethod(LogManager.TAG_SPLASH, "navigateToMainActivity")
        LogManager.i(LogManager.TAG_SPLASH, "【页面跳转】从Splash跳转到MainActivity")

        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()

        LogManager.exitMethod(LogManager.TAG_SPLASH, "navigateToMainActivity")
    }

    // ==================== 内置数据加载方法 ====================

    /**
     * 检查数据是否过期
     * @param lastSyncTime 上次同步时间（毫秒）
     * @param validityHours 有效期（小时）
     * @return true 表示数据已过期或从未同步
     */
    private fun isDataExpired(lastSyncTime: Long, validityHours: Long): Boolean {
        if (lastSyncTime == 0L) return true
        val validityMillis = validityHours * 60 * 60 * 1000
        return (System.currentTimeMillis() - lastSyncTime) > validityMillis
    }

    /**
     * 从assets加载内置卫星数据
     * 优先使用本地数据库数据，检查数据新鲜度（12小时）
     */
    private suspend fun loadBuiltinSatelliteData(): Boolean {
        LogManager.stepStart(LogManager.TAG_TLE, "加载卫星数据")
        return try {
            withContext(Dispatchers.IO) {
                val dbHelper = DatabaseHelper.getInstance(this@SplashActivity)
                val existingCount = dbHelper.getSatelliteCount()

                // 获取上次同步时间
                val lastSyncRecord = dbHelper.getLastSyncRecord("tle_celestrak")
                    ?: dbHelper.getLastSyncRecord("tle_satnogs")
                val lastSyncTime = lastSyncRecord?.syncTime ?: 0L
                val isExpired = isDataExpired(lastSyncTime, TLE_DATA_VALIDITY_HOURS)

                // 如果数据库已有数据且未过期，直接使用
                if (existingCount > 0 && !isExpired) {
                    val hoursAgo = (System.currentTimeMillis() - lastSyncTime) / (60 * 60 * 1000)
                    LogManager.i(LogManager.TAG_TLE, "【卫星数据】数据库已有 $existingCount 颗卫星，数据新鲜（${hoursAgo}小时前），使用现有数据")
                    LogManager.stepComplete(LogManager.TAG_TLE, "卫星数据加载")
                    return@withContext true
                }

                // 数据过期或为空，需要更新
                if (existingCount > 0 && isExpired) {
                    LogManager.i(LogManager.TAG_TLE, "【卫星数据】数据已过期（超过${TLE_DATA_VALIDITY_HOURS}小时），尝试从API更新...")
                    return@withContext false // 返回false，让调用方尝试从API获取
                }

                // 数据库为空，加载内置数据
                LogManager.i(LogManager.TAG_TLE, "【卫星数据】数据库为空，加载内置数据...")
                val satellites = parseBuiltinSatellites()

                if (satellites.isNotEmpty()) {
                    dbHelper.insertSatellites(satellites)
                    LogManager.i(LogManager.TAG_TLE, "【卫星数据】内置数据已加载: ${satellites.size} 颗卫星")
                    LogManager.stepComplete(LogManager.TAG_TLE, "卫星数据加载")
                    true
                } else {
                    LogManager.w(LogManager.TAG_TLE, "【卫星数据】内置数据为空")
                    false
                }
            }
        } catch (e: Exception) {
            LogManager.e(LogManager.TAG_TLE, "【卫星数据】加载内置数据失败", e)
            false
        }
    }

    /**
     * 从assets加载内置转发器数据
     * 优先使用本地数据库数据，检查数据新鲜度（30天）
     */
    private suspend fun loadBuiltinTransmitterData(): Boolean {
        LogManager.stepStart(LogManager.TAG_SATELLITE, "加载转发器数据")
        return try {
            withContext(Dispatchers.IO) {
                val dbHelper = DatabaseHelper.getInstance(this@SplashActivity)
                val existingTransmitters = dbHelper.getAllCustomTransmitters().first()

                // 获取上次更新时间（使用第一个转发器的updatedAt作为参考）
                val lastUpdateTime = existingTransmitters.firstOrNull()?.updatedAt ?: 0L
                val isExpired = isDataExpired(lastUpdateTime, TRANSMITTER_VALIDITY_DAYS * 24)

                // 如果数据库已有数据且未过期，直接使用
                if (existingTransmitters.isNotEmpty() && !isExpired) {
                    val daysAgo = (System.currentTimeMillis() - lastUpdateTime) / (24 * 60 * 60 * 1000)
                    LogManager.i(LogManager.TAG_SATELLITE, "【转发器数据】数据库已有 ${existingTransmitters.size} 个转发器，数据新鲜（${daysAgo}天前），使用现有数据")
                    LogManager.stepComplete(LogManager.TAG_SATELLITE, "转发器数据加载")
                    return@withContext true
                }

                // 数据过期或为空
                if (existingTransmitters.isNotEmpty() && isExpired) {
                    LogManager.i(LogManager.TAG_SATELLITE, "【转发器数据】数据已过期（超过${TRANSMITTER_VALIDITY_DAYS}天），尝试从API更新...")
                    return@withContext false // 返回false，让调用方尝试从API获取
                }

                // 数据库为空，加载内置数据
                LogManager.i(LogManager.TAG_SATELLITE, "【转发器数据】数据库为空，加载内置数据...")
                val transmitters = parseBuiltinTransmitters()

                if (transmitters.isNotEmpty()) {
                    // 同时保存到 transmitters 表（API表）和 custom_transmitters 表
                    for (transmitter in transmitters) {
                        // 1. 保存到 transmitters 表（供 SatelliteTrackingActivity 使用）
                        val apiTransmitter = com.bh6aap.ic705Cter.data.api.Transmitter(
                            uuid = transmitter.uuid,
                            description = transmitter.description ?: "",
                            alive = true,
                            type = "Transmitter",
                            uplinkLow = transmitter.uplinkLow,
                            uplinkHigh = transmitter.uplinkHigh,
                            uplinkDrift = null,
                            downlinkLow = transmitter.downlinkLow,
                            downlinkHigh = transmitter.downlinkHigh,
                            downlinkDrift = null,
                            mode = transmitter.mode ?: "FM",
                            modeId = 0,
                            uplinkMode = transmitter.uplinkMode,
                            invert = false,
                            baud = null,
                            satId = transmitter.noradId.toString(),
                            noradCatId = transmitter.noradId,
                            noradFollowId = null,
                            status = "active",
                            updated = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault()).format(java.util.Date()),
                            citation = "",
                            service = "Amateur",
                            iaruCoordination = "",
                            iaruCoordinationUrl = "",
                            frequencyViolation = false,
                            unconfirmed = false
                        )
                        dbHelper.insertTransmitter(apiTransmitter)

                        // 2. 保存到 custom_transmitters 表（用户自定义数据）
                        val customEntity = CustomTransmitterEntity(
                            uuid = transmitter.uuid,
                            noradCatId = transmitter.noradId,
                            description = transmitter.description,
                            uplinkLow = transmitter.uplinkLow,
                            uplinkHigh = transmitter.uplinkHigh,
                            downlinkLow = transmitter.downlinkLow,
                            downlinkHigh = transmitter.downlinkHigh,
                            downlinkMode = transmitter.mode,
                            uplinkMode = transmitter.uplinkMode
                        )
                        dbHelper.insertOrUpdateCustomTransmitter(customEntity)
                    }
                    LogManager.i(LogManager.TAG_SATELLITE, "【转发器数据】内置数据已加载: ${transmitters.size} 个转发器")
                    LogManager.stepComplete(LogManager.TAG_SATELLITE, "转发器数据加载")
                    true
                } else {
                    LogManager.w(LogManager.TAG_SATELLITE, "【转发器数据】内置数据为空")
                    false
                }
            }
        } catch (e: Exception) {
            LogManager.e(LogManager.TAG_SATELLITE, "【转发器数据】加载内置数据失败", e)
            false
        }
    }

    /**
     * 解析内置卫星数据JSON
     */
    private fun parseBuiltinSatellites(): List<SatelliteEntity> {
        return try {
            val inputStream = assets.open("satellites_builtin.json")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonString = reader.use { it.readText() }

            val jsonObject = JSONObject(jsonString)
            val satellitesArray = jsonObject.getJSONArray("satellites")
            val satellites = mutableListOf<SatelliteEntity>()

            for (i in 0 until satellitesArray.length()) {
                val obj = satellitesArray.getJSONObject(i)
                satellites.add(
                    SatelliteEntity(
                        noradId = obj.getString("noradId"),
                        name = obj.getString("name"),
                        internationalDesignator = obj.optString("internationalDesignator", "").takeIf { it.isNotEmpty() },
                        tleLine1 = obj.getString("tleLine1"),
                        tleLine2 = obj.getString("tleLine2"),
                        category = obj.optString("category", "amateur").takeIf { it.isNotEmpty() },
                        isFavorite = obj.optBoolean("isFavorite", false),
                        notes = null
                    )
                )
            }
            satellites
        } catch (e: Exception) {
            LogManager.e(LogManager.TAG_TLE, "【卫星数据】解析内置数据失败", e)
            emptyList()
        }
    }

    /**
     * 解析内置转发器数据JSON
     */
    private fun parseBuiltinTransmitters(): List<TransmitterEntity> {
        return try {
            val inputStream = assets.open("transmitters_builtin.json")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonString = reader.use { it.readText() }

            val jsonObject = JSONObject(jsonString)
            val transmittersArray = jsonObject.getJSONArray("transmitters")
            val transmitters = mutableListOf<TransmitterEntity>()

            for (i in 0 until transmittersArray.length()) {
                val obj = transmittersArray.getJSONObject(i)
                transmitters.add(
                    TransmitterEntity(
                        uuid = obj.getString("uuid"),
                        noradId = obj.getInt("noradId"),
                        description = obj.optString("description", "").takeIf { it.isNotEmpty() },
                        uplinkLow = obj.optLong("uplinkLow", 0).takeIf { it > 0 },
                        uplinkHigh = obj.optLong("uplinkHigh", 0).takeIf { it > 0 },
                        downlinkLow = obj.optLong("downlinkLow", 0).takeIf { it > 0 },
                        downlinkHigh = obj.optLong("downlinkHigh", 0).takeIf { it > 0 },
                        mode = obj.optString("mode", "").takeIf { it.isNotEmpty() },
                        uplinkMode = obj.optString("uplinkMode", "").takeIf { it.isNotEmpty() },
                        invert = obj.optBoolean("invert", false)
                    )
                )
            }
            transmitters
        } catch (e: Exception) {
            LogManager.e(LogManager.TAG_SATELLITE, "【转发器数据】解析内置数据失败", e)
            emptyList()
        }
    }

    /**
     * 请求忽略电池优化
     */
    private fun requestIgnoreBatteryOptimizations() {
        try {
            val packageName = packageName
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            )
            intent.data = android.net.Uri.parse("package:$packageName")
            startActivity(intent)
            LogManager.i(LogManager.TAG_PERMISSION, "【电池优化】请求忽略电池优化")
        } catch (e: Exception) {
            LogManager.e(LogManager.TAG_PERMISSION, "【电池优化】请求忽略电池优化失败", e)
        }
    }
}

@Composable
fun SplashScreen(
    modifier: Modifier = Modifier,
    permissionManager: PermissionManager? = null,
    currentStep: SplashActivity.InitStep = SplashActivity.InitStep.PERMISSION_CHECK,
    stepMessage: String = "初始化中...",
    onPermissionsGranted: () -> Unit = {},
    onLoadingComplete: () -> Unit = {}
) {
    val context = LocalContext.current
    var permissionRationaleText by remember { mutableStateOf("") }
    var hasRequestedPermissions by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        permissionRationaleText = permissionManager?.getPermissionRationaleText()
            ?: context.getString(R.string.splash_permission_rationale_title) + "\n\n" +
               context.getString(R.string.splash_permission_location) + "\n" +
               context.getString(R.string.splash_permission_storage) + "\n" +
               context.getString(R.string.splash_permission_bluetooth)
    }

    // 监听步骤变化，当完成时跳转
    LaunchedEffect(currentStep) {
        if (currentStep == SplashActivity.InitStep.COMPLETE) {
            onLoadingComplete()
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
            contentDescription = stringResource(R.string.splash_logo_desc),
            modifier = Modifier.size(240.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.splash_app_name),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )

        Text(
            text = stringResource(R.string.splash_app_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(48.dp))

        // 根据当前步骤显示不同UI
        when (currentStep) {
            SplashActivity.InitStep.PERMISSION_CHECK -> {
                if (permissionManager?.hasAllPermissions() == true) {
                    // 已有权限，显示加载中
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stepMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                } else {
                    // 需要请求权限
                    Text(
                        text = permissionRationaleText,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            hasRequestedPermissions = true
                            permissionManager?.requestPermissions { result ->
                                // 权限请求回调
                                if (result.allGranted) {
                                    onPermissionsGranted()
                                } else {
                                    hasRequestedPermissions = false
                                }
                            }
                        },
                        enabled = !hasRequestedPermissions
                    ) {
                        Text(if (hasRequestedPermissions) stringResource(R.string.splash_permission_waiting) else stringResource(R.string.splash_permission_grant))
                    }
                }
            }
            SplashActivity.InitStep.NTP_SYNC,
            SplashActivity.InitStep.GPS_LOCATION,
            SplashActivity.InitStep.SATELLITE_INFO_SYNC,
            SplashActivity.InitStep.TLE_SYNC,
            SplashActivity.InitStep.OREKIT_INIT -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stepMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 显示进度指示
                Text(
                    text = getStepProgressText(currentStep),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            SplashActivity.InitStep.COMPLETE -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stepMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(64.dp))

        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/**
 * 获取步骤进度文本
 */
@Composable
private fun getStepProgressText(step: SplashActivity.InitStep): String {
    return when (step) {
        SplashActivity.InitStep.NTP_SYNC -> stringResource(R.string.splash_step_progress, 1)
        SplashActivity.InitStep.GPS_LOCATION -> stringResource(R.string.splash_step_progress, 2)
        SplashActivity.InitStep.SATELLITE_INFO_SYNC -> stringResource(R.string.splash_step_progress, 3)
        SplashActivity.InitStep.TLE_SYNC -> stringResource(R.string.splash_step_progress, 4)
        SplashActivity.InitStep.OREKIT_INIT -> stringResource(R.string.splash_step_progress, 5)
        SplashActivity.InitStep.COMPLETE -> stringResource(R.string.splash_step_progress, 6)
        else -> ""
    }
}

@Preview(showBackground = true)
@Composable
fun SplashScreenPreview() {
    Ic705controlerTheme {
        SplashScreen()
    }
}
