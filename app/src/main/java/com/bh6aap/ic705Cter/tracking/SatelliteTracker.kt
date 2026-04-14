package com.bh6aap.ic705Cter.tracking

import android.content.Context
import com.bh6aap.ic705Cter.data.database.DatabaseHelper
import com.bh6aap.ic705Cter.data.database.entity.SatelliteEntity
import com.bh6aap.ic705Cter.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.orekit.bodies.GeodeticPoint
import org.orekit.bodies.OneAxisEllipsoid
import org.orekit.frames.Frame
import org.orekit.frames.FramesFactory
import org.orekit.frames.TopocentricFrame
import org.orekit.propagation.analytical.tle.TLE
import org.orekit.propagation.analytical.tle.TLEPropagator
import org.orekit.time.AbsoluteDate
import org.orekit.time.TimeScalesFactory
import org.orekit.utils.Constants
import org.orekit.utils.IERSConventions
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * 卫星跟踪器
 * 用于计算卫星相对地面站的实时方位角和仰角
 * 使用单例模式缓存地球模型和地面站框架，避免重复初始化
 */
class SatelliteTracker private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: SatelliteTracker? = null

        fun getInstance(context: Context): SatelliteTracker {
            return instance ?: synchronized(this) {
                instance ?: SatelliteTracker(context.applicationContext).also {
                    instance = it
                }
            }
        }

        // 缓存的地球模型（全局共享）
        // @Volatile ensures that writes performed on one Dispatchers.IO thread are
        // visible to other IO threads that may subsequently read the cached values;
        // without this the JVM memory model offers no visibility guarantee.
        @Volatile
        private var cachedEarth: OneAxisEllipsoid? = null
        // 缓存的地面站框架
        @Volatile
        private var cachedTopocentricFrame: TopocentricFrame? = null
        // 缓存的地面站位置
        @Volatile
        private var cachedStationLocation: Triple<Double, Double, Double>? = null
    }

    private var topocentricFrame: TopocentricFrame? = null
    private var earth: OneAxisEllipsoid? = null
    private var utc = TimeScalesFactory.getUTC()

    // 缓存的卫星传播器
    // ConcurrentHashMap is used instead of mutableMapOf() (which returns a plain
    // LinkedHashMap) because calculateMultiplePositions() and
    // calculateSatelliteTrajectory() may execute concurrently on different
    // Dispatchers.IO threads.  Concurrent structural modification of a plain
    // HashMap can cause an infinite loop in the resize path (JDK < 8) or a
    // ConcurrentModificationException, both of which would silently stop Doppler
    // updates and freeze the radar display.
    private val propagatorCache = ConcurrentHashMap<String, TLEPropagator>()

    data class SatellitePosition(
        val satelliteId: String,
        val name: String,
        val azimuth: Double,      // 方位角 0-360°
        val elevation: Double,    // 仰角 0-90°
        val distance: Double,     // 距离（km）
        val isVisible: Boolean,   // 是否在视野内（仰角>0）
        val rangeRate: Double = 0.0  // 距离变化率（m/s），用于多普勒计算
    )

    /**
     * 初始化地面站
     * 使用缓存机制避免重复创建昂贵的地球模型和框架对象
     */
    suspend fun initializeStation() = withContext(Dispatchers.IO) {
        try {
            val dbHelper = DatabaseHelper.getInstance(context)
            val station = dbHelper.getDefaultStation()

            if (station == null) {
                LogManager.e("SatelliteTracker", "数据库中没有默认地面站")
                return@withContext false
            }

            // 检查缓存是否有效（位置是否变化）
            val currentLocation = Triple(station.latitude, station.longitude, station.altitude)
            LogManager.d("SatelliteTracker", "缓存检查: cachedEarth=${cachedEarth != null}, cachedFrame=${cachedTopocentricFrame != null}, cachedLocation=$cachedStationLocation, currentLocation=$currentLocation")
            
            if (cachedEarth != null && cachedTopocentricFrame != null && 
                cachedStationLocation == currentLocation) {
                // 使用缓存
                earth = cachedEarth
                topocentricFrame = cachedTopocentricFrame
                LogManager.i("SatelliteTracker", "地面站使用缓存: ${station.name}, 实例=${System.identityHashCode(this@SatelliteTracker)}")
                return@withContext true
            }

            // 初始化地球模型（只创建一次）
            LogManager.i("SatelliteTracker", "开始初始化地球模型...")
            val startTime = System.currentTimeMillis()
            val itrfFrame: Frame = FramesFactory.getITRF(IERSConventions.IERS_2010, true)
            earth = OneAxisEllipsoid(
                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING,
                itrfFrame
            )

            // 构建地面站
            val observerPoint = GeodeticPoint(
                Math.toRadians(station.latitude),
                Math.toRadians(station.longitude),
                station.altitude
            )
            topocentricFrame = TopocentricFrame(earth, observerPoint, "GroundStation")

            // 更新缓存
            cachedEarth = earth
            cachedTopocentricFrame = topocentricFrame
            cachedStationLocation = currentLocation

            val elapsed = System.currentTimeMillis() - startTime
            LogManager.i("SatelliteTracker", "地面站初始化完成: ${station.name}, 耗时=${elapsed}ms, 实例=${System.identityHashCode(this@SatelliteTracker)}")
            return@withContext true
        } catch (e: Exception) {
            LogManager.e("SatelliteTracker", "地面站初始化失败", e)
            return@withContext false
        }
    }

    /**
     * 计算卫星当前位置
     * @param satellite 要计算的卫星
     * @param currentTime 当前时间
     * @param targetSatelliteId 目标卫星ID（可选），如果指定则只有该卫星会更新多普勒缓存
     * @param downlinkFreqHz 下行频率（Hz），用于计算多普勒频移，默认435MHz
     */
    suspend fun calculateSatellitePosition(
        satellite: SatelliteEntity,
        currentTime: Long = System.currentTimeMillis(),
        targetSatelliteId: String? = null,
        downlinkFreqHz: Double = 435e6
    ): SatellitePosition? = withContext(Dispatchers.IO) {
        try {
            val frame = topocentricFrame ?: return@withContext null

            // 获取或创建传播器
            val propagator = propagatorCache.getOrPut(satellite.noradId) {
                val tle = TLE(satellite.tleLine1, satellite.tleLine2)
                TLEPropagator.selectExtrapolator(tle)
            }

            // 计算当前时间的位置
            val date = AbsoluteDate(Date(currentTime), utc)
            val state = propagator.propagate(date)

            // 转换为站心坐标
            val coords = frame.getTrackingCoordinates(
                state.position,
                state.frame,
                state.date
            )

            // 计算方位角和仰角
            val azimuth = Math.toDegrees(coords.azimuth)
            val elevation = Math.toDegrees(coords.elevation)

            // 计算距离（km）
            val distance = coords.range / 1000.0 // 转换为km

            // 计算距离变化率（多普勒）
            val rangeRate = frame.getRangeRate(
                state.pvCoordinates,
                state.frame,
                state.date
            )

            // 只有目标卫星才更新多普勒缓存
            if (targetSatelliteId == null || satellite.noradId == targetSatelliteId) {
                // 计算多普勒频移：Δf = -f * v_r / c
                val dopplerShiftHz = -downlinkFreqHz * rangeRate / 299792458.0
                DopplerDataCache.updateData(
                    dopplerShiftHz = dopplerShiftHz,
                    rangeRateMps = rangeRate,
                    distanceKm = distance,
                    azimuthDeg = (azimuth + 360) % 360,
                    elevationDeg = elevation,
                    satelliteId = satellite.noradId
                )
            }

            SatellitePosition(
                satelliteId = satellite.noradId,
                name = satellite.name,
                azimuth = (azimuth + 360) % 360, // 规范化到 0-360°
                elevation = elevation,
                distance = distance,
                isVisible = elevation > 0,
                rangeRate = rangeRate
            )
        } catch (e: Exception) {
            LogManager.e("SatelliteTracker", "计算卫星位置失败: ${satellite.name}", e)
            null
        }
    }

    /**
     * 计算多颗卫星的位置
     * @param satellites 要计算的卫星列表
     * @param currentTime 当前时间
     * @param targetSatelliteId 目标卫星ID（可选），如果指定则只有该卫星会更新多普勒缓存
     * @param downlinkFreqHz 下行频率（Hz），用于计算多普勒频移，默认435MHz
     */
    suspend fun calculateMultiplePositions(
        satellites: List<SatelliteEntity>,
        currentTime: Long = System.currentTimeMillis(),
        targetSatelliteId: String? = null,
        downlinkFreqHz: Double = 435e6
    ): List<SatellitePosition> = withContext(Dispatchers.IO) {
        satellites.mapNotNull { satellite ->
            calculateSatellitePosition(satellite, currentTime, targetSatelliteId, downlinkFreqHz)
        }.filter { it.isVisible } // 只返回可见的卫星
    }

    /**
     * 计算卫星过境轨迹（用于雷达图显示）
     * @param satellite 卫星实体
     * @param startTime 开始时间
     * @param durationMinutes 轨迹时长（分钟）
     * @param intervalSeconds 采样间隔（秒）
     * @return 轨迹点列表
     */
    suspend fun calculateSatelliteTrajectory(
        satellite: SatelliteEntity,
        startTime: Long = System.currentTimeMillis(),
        durationMinutes: Int = 10,
        intervalSeconds: Int = 10  // 改为10秒间隔，增加轨迹点密度
    ): List<TrajectoryPoint> = withContext(Dispatchers.IO) {
        val frame = topocentricFrame ?: return@withContext emptyList()

        try {
            val propagator = propagatorCache.getOrPut(satellite.noradId) {
                val tle = TLE(satellite.tleLine1, satellite.tleLine2)
                TLEPropagator.selectExtrapolator(tle)
            }

            val trajectory = mutableListOf<TrajectoryPoint>()
            val endTime = startTime + durationMinutes * 60 * 1000
            var currentTimeMs = startTime

            while (currentTimeMs <= endTime) {
                val date = AbsoluteDate(Date(currentTimeMs), utc)
                val state = propagator.propagate(date)
                val coords = frame.getTrackingCoordinates(
                    state.position,
                    state.frame,
                    state.date
                )

                val azimuth = Math.toDegrees(coords.azimuth)
                val elevation = Math.toDegrees(coords.elevation)

                // 计算距离变化率（多普勒）
                val rangeRate = frame.getRangeRate(
                    state.pvCoordinates,
                    state.frame,
                    state.date
                )

                // 调试日志
                LogManager.d("SatelliteTracker", "轨迹点: ${satellite.name}, 时间=${Date(currentTimeMs)}, 方位=${azimuth.toInt()}°, 仰角=${elevation.toInt()}°, 多普勒=${rangeRate.toInt()} m/s")

                // 添加所有点（不过滤），让雷达图自己决定如何显示
                trajectory.add(
                    TrajectoryPoint(
                        azimuth = (azimuth + 360) % 360,
                        elevation = elevation,
                        timestamp = currentTimeMs,
                        rangeRate = rangeRate
                    )
                )

                currentTimeMs += intervalSeconds * 1000
            }

            trajectory
        } catch (e: Exception) {
            LogManager.e("SatelliteTracker", "计算轨迹失败: ${satellite.name}", e)
            emptyList()
        }
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        propagatorCache.clear()
    }
}

/**
 * 轨迹点数据类
 */
data class TrajectoryPoint(
    val azimuth: Double,
    val elevation: Double,
    val timestamp: Long,
    val rangeRate: Double = 0.0  // 距离变化率（m/s），用于多普勒计算
)


