package com.bh6aap.ic705Cter.util

import android.content.Context
import com.bh6aap.ic705Cter.data.database.DatabaseHelper
import com.bh6aap.ic705Cter.data.database.entity.SatelliteEntity
import com.bh6aap.ic705Cter.data.time.NtpTimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hipparchus.ode.events.Action
import org.orekit.bodies.GeodeticPoint
import org.orekit.bodies.OneAxisEllipsoid
import org.orekit.frames.Frame
import org.orekit.frames.FramesFactory
import org.orekit.frames.TopocentricFrame
import org.orekit.propagation.SpacecraftState
import org.orekit.propagation.analytical.tle.TLE
import org.orekit.propagation.analytical.tle.TLEPropagator
import org.orekit.propagation.events.ElevationDetector
import org.orekit.propagation.events.EventDetector
import org.orekit.propagation.events.EventsLogger
import org.orekit.propagation.events.handlers.EventHandler
import org.orekit.time.AbsoluteDate
import org.orekit.time.TimeScalesFactory
import org.orekit.utils.Constants
import org.orekit.utils.IERSConventions
import java.util.Date

/**
 * 卫星过境计算器
 * 使用Orekit计算卫星过境信息
 *
 * 参考orekit文档，使用事件检测器（ElevationDetector）精确捕获过境事件
 */
object SatellitePassCalculator {

    /**
     * 过境信息数据类
     */
    data class SatellitePass(
        val noradId: String,
        val name: String,
        val aosTime: Long,      // 入境时间（毫秒）
        val losTime: Long,      // 出境时间（毫秒）
        val maxElevation: Double, // 最大仰角（度）
        val aosAzimuth: Double,   // 入境方位角（度）
        val losAzimuth: Double,   // 出境方位角（度）
        val duration: Long,       // 过境时长（秒）
        val maxElevationTime: Long, // 最大仰角时间（毫秒）
        val isVisible: Boolean = true, // 是否在列表中显示
        val isInProgress: Boolean = false // 是否正在过境中
    )

    /**
     * 计算卫星过境信息
     *
     * 使用Orekit的ElevationDetector事件检测器精确捕获过境事件
     *
     * @param context 上下文
     * @param satellite 卫星实体（包含TLE数据）
     * @param observerLat 观测者纬度（度）
     * @param observerLon 观测者经度（度）
     * @param observerAlt 观测者高度（米）
     * @param minElevation 最小仰角（度，默认0度，即地平线以上）
     * @param hoursAhead 预测未来几小时（默认24小时）
     * @return 过境信息列表
     */
    suspend fun calculatePasses(
        context: Context,
        satellite: SatelliteEntity,
        observerLat: Double,
        observerLon: Double,
        observerAlt: Double = 0.0,
        minElevation: Double = 0.0,
        hoursAhead: Int = 24
    ): List<SatellitePass> = withContext(Dispatchers.IO) {
        try {
            // 验证TLE数据
            if (satellite.tleLine1.isBlank() || satellite.tleLine2.isBlank()) {
                LogManager.w("SatellitePassCalculator", "卫星 ${satellite.name} 的TLE数据为空")
                return@withContext emptyList()
            }

            // 获取NTP校准后的时间作为起始时间
            val ntpManager = NtpTimeManager(context)
            val accurateTime = ntpManager.getAccurateTime()
            LogManager.i("SatellitePassCalculator", "使用NTP时间: ${Date(accurateTime)}")

            // 检查缓存
            val dbHelper = DatabaseHelper.getInstance(context)
            val cachedPasses = dbHelper.getPassCache(satellite.noradId, accurateTime, hoursAhead)

            // 检查位置变化（超过20公里需要重新计算）
            val shouldRecalculate = if (cachedPasses != null && cachedPasses.isNotEmpty()) {
                val cacheLocation = dbHelper.getCacheStationLocation(satellite.noradId)
                if (cacheLocation != null) {
                    val (cacheLat, cacheLon, cacheAlt) = cacheLocation
                    val distance = calculateDistance(observerLat, observerLon, cacheLat, cacheLon)
                    LogManager.i("SatellitePassCalculator", "位置变化: ${String.format("%.1f", distance)} 公里")
                    distance > 20.0 // 超过20公里需要重新计算
                } else {
                    true
                }
            } else {
                true
            }

            if (!shouldRecalculate && cachedPasses != null) {
                // 使用缓存数据，过滤掉已过去的过境
                val validPasses = cachedPasses
                    .filter { it.losTime > accurateTime }
                    .map { it.toSatellitePass() }
                LogManager.i("SatellitePassCalculator", "使用缓存数据: ${satellite.name}, ${validPasses.size} 个有效过境")
                return@withContext validPasses
            }

            // 1. 初始化地球模型 + 基准坐标系（ITRF）
            val utc = TimeScalesFactory.getUTC()
            val itrfFrame: Frame = FramesFactory.getITRF(IERSConventions.IERS_2010, true)
            val earth = OneAxisEllipsoid(
                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING,
                itrfFrame
            )
            LogManager.d("SatellitePassCalculator", "地球模型初始化完成: WGS84")

            // 2. 构建地面站站心坐标系（TopocentricFrame）
            val observerPoint = GeodeticPoint(
                Math.toRadians(observerLat),
                Math.toRadians(observerLon),
                observerAlt
            )
            val observerFrame = TopocentricFrame(earth, observerPoint, "GroundStation")
            LogManager.d("SatellitePassCalculator", "地面站坐标系初始化完成: 纬度=$observerLat°, 经度=$observerLon°, 高度=${observerAlt}m")

            // 3. 初始化卫星轨道传播器（TLEPropagator）
            val tle = TLE(satellite.tleLine1, satellite.tleLine2)
            val propagator = TLEPropagator.selectExtrapolator(tle)
            LogManager.d("SatellitePassCalculator", "卫星轨道传播器初始化完成: ${satellite.name} (NORAD: ${satellite.noradId})")

            // 4. 创建仰角检测器（ElevationDetector）
            // 使用事件检测器精确捕获过境事件
            val elevationDetector = ElevationDetector(observerFrame)
                .withConstantElevation(Math.toRadians(minElevation))
                .withHandler(object : EventHandler {
                    override fun eventOccurred(s: SpacecraftState, detector: EventDetector, increasing: Boolean): Action {
                        // 事件发生时记录日志
                        val trackingCoords = observerFrame.getTrackingCoordinates(s.position, s.frame, s.date)
                        val elevation = Math.toDegrees(trackingCoords.elevation)
                        val azimuth = Math.toDegrees(trackingCoords.azimuth)
                        val time = s.date.toDate(utc).time

                        if (increasing) {
                            LogManager.d("SatellitePassCalculator", "${satellite.name} AOS事件: ${Date(time)}, 仰角: ${String.format("%.1f", elevation)}°, 方位角: ${String.format("%.1f", azimuth)}°")
                        } else {
                            LogManager.d("SatellitePassCalculator", "${satellite.name} LOS事件: ${Date(time)}, 仰角: ${String.format("%.1f", elevation)}°, 方位角: ${String.format("%.1f", azimuth)}°")
                        }
                        return Action.CONTINUE
                    }

                    override fun init(initialState: SpacecraftState, targetDate: AbsoluteDate, detector: EventDetector) {
                        // 初始化
                    }

                    override fun resetState(detector: EventDetector, oldState: SpacecraftState): SpacecraftState {
                        return oldState
                    }
                })

            // 5. 使用EventsLogger记录所有过境事件
            val eventsLogger = EventsLogger()
            propagator.addEventDetector(eventsLogger.monitorDetector(elevationDetector))

            // 6. 执行轨道传播，触发事件检测
            val startDate = AbsoluteDate(Date(accurateTime), utc)
            val endDate = startDate.shiftedBy(hoursAhead * 3600.0)

            LogManager.d("SatellitePassCalculator", "开始计算过境: ${satellite.name}, 时间范围: ${startDate.toDate(utc)} 到 ${endDate.toDate(utc)}")

            // 传播轨道，触发事件检测
            propagator.propagate(startDate, endDate)

            // 7. 从事件日志中提取过境信息并计算最大仰角
            val passes = mutableListOf<SatellitePass>()
            val loggedEvents = eventsLogger.loggedEvents

            var i = 0
            while (i < loggedEvents.size) {
                val aosEvent = loggedEvents[i]
                if (!aosEvent.isIncreasing) {
                    i++
                    continue
                }

                // 找到对应的LOS事件
                var losEvent: EventsLogger.LoggedEvent? = null
                for (j in i + 1 until loggedEvents.size) {
                    if (!loggedEvents[j].isIncreasing) {
                        losEvent = loggedEvents[j]
                        break
                    }
                }

                if (losEvent == null) {
                    i++
                    continue
                }

                // 获取AOS信息
                val aosState = aosEvent.state
                val aosCoords = observerFrame.getTrackingCoordinates(aosState.position, aosState.frame, aosState.date)
                val aosTime = aosState.date.toDate(utc).time
                val aosAzimuth = Math.toDegrees(aosCoords.azimuth)

                // 获取LOS信息
                val losState = losEvent.state
                val losCoords = observerFrame.getTrackingCoordinates(losState.position, losState.frame, losState.date)
                val losTime = losState.date.toDate(utc).time
                val losAzimuth = Math.toDegrees(losCoords.azimuth)

                // 计算过境期间的最大仰角
                var maxElevation = Math.toDegrees(aosCoords.elevation)
                var maxElevationTime = aosTime

                // 在AOS和LOS之间以30秒步长搜索最大仰角
                var currentDate = aosState.date
                val losDate = losState.date
                while (currentDate.isBefore(losDate)) {
                    val state = propagator.propagate(currentDate)
                    val coords = observerFrame.getTrackingCoordinates(state.position, state.frame, state.date)
                    val elevation = Math.toDegrees(coords.elevation)

                    if (elevation > maxElevation) {
                        maxElevation = elevation
                        maxElevationTime = state.date.toDate(utc).time
                    }

                    currentDate = currentDate.shiftedBy(30.0)
                }

                val duration = (losTime - aosTime) / 1000

                // 只保存有效的过境（时长至少30秒）
                if (duration >= 30) {
                    passes.add(
                        SatellitePass(
                            noradId = satellite.noradId,
                            name = satellite.name,
                            aosTime = aosTime,
                            losTime = losTime,
                            maxElevation = maxElevation,
                            aosAzimuth = aosAzimuth,
                            losAzimuth = losAzimuth,
                            duration = duration,
                            maxElevationTime = maxElevationTime
                        )
                    )
                    LogManager.i("SatellitePassCalculator", "${satellite.name} 过境完成: AOS=${Date(aosTime)}, LOS=${Date(losTime)}, 最大仰角=${String.format("%.1f", maxElevation)}°, 时长=${duration}秒")
                }

                i = loggedEvents.indexOf(losEvent) + 1
            }

            LogManager.d("SatellitePassCalculator", "${satellite.name} 计算完成: 找到 ${passes.size} 个过境")

            // 保存到缓存
            if (passes.isNotEmpty()) {
                val cacheEntities = passes.map { pass ->
                    com.bh6aap.ic705Cter.data.database.entity.PassCacheEntity(
                        noradId = pass.noradId,
                        name = pass.name,
                        aosTime = pass.aosTime,
                        losTime = pass.losTime,
                        maxElevation = pass.maxElevation,
                        aosAzimuth = pass.aosAzimuth,
                        losAzimuth = pass.losAzimuth,
                        duration = pass.duration,
                        maxElevationTime = pass.maxElevationTime,
                        stationLat = observerLat,
                        stationLon = observerLon,
                        stationAlt = observerAlt,
                        minElevation = minElevation,
                        calculatedAt = accurateTime,
                        hoursAhead = hoursAhead
                    )
                }
                dbHelper.savePassCache(cacheEntities)
            }

            passes
        } catch (e: Exception) {
            LogManager.e("SatellitePassCalculator", "计算过境信息失败: ${satellite.name}", e)
            emptyList()
        }
    }

    /**
     * 计算两点之间的距离（使用Haversine公式）
     * @return 距离（公里）
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // 地球半径（公里）
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLon = Math.toRadians(lon2 - lon1)

        val a = kotlin.math.sin(deltaLat / 2) * kotlin.math.sin(deltaLat / 2) +
                kotlin.math.cos(lat1Rad) * kotlin.math.cos(lat2Rad) *
                kotlin.math.sin(deltaLon / 2) * kotlin.math.sin(deltaLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))

        return R * c
    }

    /**
     * 计算多个卫星的过境信息（优化版，只创建一次坐标系，从数据库读取GPS）
     *
     * @param context 上下文
     * @param satellites 卫星列表
     * @param minElevation 最小仰角（度）
     * @param hoursAhead 预测未来几小时
     * @return 按时间排序的过境信息列表
     */
    suspend fun calculatePassesForMultipleSatellites(
        context: Context,
        satellites: List<SatelliteEntity>,
        minElevation: Double = 0.0,
        hoursAhead: Int = 24
    ): List<SatellitePass> = withContext(Dispatchers.IO) {
        val allPasses = mutableListOf<SatellitePass>()

        // 从数据库读取默认地面站（GPS位置）
        val dbHelper = DatabaseHelper.getInstance(context)
        val station = dbHelper.getDefaultStation()

        if (station == null) {
            LogManager.e("SatellitePassCalculator", "数据库中没有默认地面站，无法计算过境")
            return@withContext emptyList()
        }

        val observerLat = station.latitude
        val observerLon = station.longitude
        val observerAlt = station.altitude

        LogManager.i("SatellitePassCalculator", "开始计算 ${satellites.size} 颗卫星的过境信息, 地面站: ${station.name}")
        LogManager.d("SatellitePassCalculator", "地面站坐标: 纬度=$observerLat°, 经度=$observerLon°")

        try {
            // 获取NTP校准后的时间作为起始时间（使用缓存，5分钟内不重复同步）
            val ntpManager = NtpTimeManager(context)
            val accurateTime = ntpManager.getCachedAccurateTime()
            LogManager.i("SatellitePassCalculator", "使用NTP时间: ${Date(accurateTime)}")

            // 1. 初始化地球模型 + 基准坐标系（ITRF）（只创建一次）
            val utc = TimeScalesFactory.getUTC()
            val itrfFrame: Frame = FramesFactory.getITRF(IERSConventions.IERS_2010, true)
            val earth = OneAxisEllipsoid(
                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING,
                itrfFrame
            )
            LogManager.d("SatellitePassCalculator", "地球模型初始化完成: WGS84")

            // 2. 构建地面站站心坐标系（TopocentricFrame）（只创建一次）
            val observerPoint = GeodeticPoint(
                Math.toRadians(observerLat),
                Math.toRadians(observerLon),
                observerAlt
            )
            val observerFrame = TopocentricFrame(earth, observerPoint, "GroundStation")
            LogManager.d("SatellitePassCalculator", "地面站坐标系初始化完成: ${station.name}, 纬度=$observerLat°, 经度=$observerLon°, 高度=${observerAlt}m")

            // 3. 计算时间范围（只计算一次）
            val startDate = AbsoluteDate(Date(accurateTime), utc)
            val endDate = startDate.shiftedBy(hoursAhead * 3600.0)
            LogManager.d("SatellitePassCalculator", "时间范围: ${startDate.toDate(utc)} 到 ${endDate.toDate(utc)}")

            // 4. 为每颗卫星计算过境
            satellites.forEach { satellite ->
                try {
                    // 验证TLE数据
                    if (satellite.tleLine1.isBlank() || satellite.tleLine2.isBlank()) {
                        LogManager.w("SatellitePassCalculator", "卫星 ${satellite.name} 的TLE数据为空，跳过")
                        return@forEach
                    }

                    // 初始化卫星轨道传播器
                    val tle = TLE(satellite.tleLine1, satellite.tleLine2)
                    val propagator = TLEPropagator.selectExtrapolator(tle)
                    LogManager.d("SatellitePassCalculator", "计算过境: ${satellite.name} (NORAD: ${satellite.noradId})")

                    // 创建仰角检测器
                    val elevationDetector = ElevationDetector(observerFrame)
                        .withConstantElevation(Math.toRadians(minElevation))
                        .withHandler(object : EventHandler {
                            override fun eventOccurred(s: SpacecraftState, detector: EventDetector, increasing: Boolean): Action {
                                return Action.CONTINUE
                            }
                            override fun init(initialState: SpacecraftState, targetDate: AbsoluteDate, detector: EventDetector) {}
                            override fun resetState(detector: EventDetector, oldState: SpacecraftState): SpacecraftState = oldState
                        })

                    // 使用EventsLogger记录所有过境事件
                    val eventsLogger = EventsLogger()
                    propagator.addEventDetector(eventsLogger.monitorDetector(elevationDetector))

                    // 执行轨道传播，触发事件检测
                    val finalState = propagator.propagate(startDate, endDate)

                    // 从事件日志中提取过境信息并计算最大仰角
                    val loggedEvents = eventsLogger.loggedEvents

                    // 检查卫星在startDate时是否已经在过境中（仰角>0）
                    val startState = propagator.propagate(startDate)
                    val startCoords = observerFrame.getTrackingCoordinates(startState.position, startState.frame, startState.date)
                    val startElevation = Math.toDegrees(startCoords.elevation)
                    val isInPassAtStart = startElevation > minElevation

                    // 处理所有过境情况
                    var i = 0

                    // 情况1: 卫星在startDate时已经在过境中（第一个事件是LOS）
                    if (isInPassAtStart && loggedEvents.isNotEmpty() && !loggedEvents[0].isIncreasing) {
                        // 第一个LOS作为LOS
                        val losEvent = loggedEvents[0]
                        val losState = losEvent.state
                        val losCoords = observerFrame.getTrackingCoordinates(losState.position, losState.frame, losState.date)
                        val losTime = losState.date.toDate(utc).time
                        val losAzimuth = Math.toDegrees(losCoords.azimuth)

                        // 使用额外的传播器向后搜索找到真实的AOS
                        // 从startDate向后传播（时间倒退），找到AOS事件
                        val backwardPropagator = TLEPropagator.selectExtrapolator(tle)
                        val backwardEventsLogger = EventsLogger()
                        backwardPropagator.addEventDetector(backwardEventsLogger.monitorDetector(elevationDetector))

                        // 向后传播1小时（从startDate到1小时前）
                        val oneHourAgo = startDate.shiftedBy(-3600.0)
                        backwardPropagator.propagate(startDate, oneHourAgo)

                        // 从后向事件日志中找到AOS（仰角从低到高的事件，即isIncreasing=true）
                        val backwardEvents = backwardEventsLogger.loggedEvents
                        var aosTime = startDate.shiftedBy(-900.0).toDate(utc).time // 默认使用15分钟前
                        var aosAzimuth = Math.toDegrees(startCoords.azimuth)
                        var foundRealAos = false

                        // 后向事件中，最后一个isIncreasing=true的事件就是AOS
                        for (j in backwardEvents.size - 1 downTo 0) {
                            if (backwardEvents[j].isIncreasing) {
                                val aosState = backwardEvents[j].state
                                val aosCoords = observerFrame.getTrackingCoordinates(aosState.position, aosState.frame, aosState.date)
                                aosTime = aosState.date.toDate(utc).time
                                aosAzimuth = Math.toDegrees(aosCoords.azimuth)
                                foundRealAos = true
                                LogManager.d("SatellitePassCalculator", "${satellite.name} 找到真实AOS: ${Date(aosTime)}")
                                break
                            }
                        }

                        // 如果没有找到真实AOS，使用估计值
                        if (!foundRealAos) {
                            LogManager.w("SatellitePassCalculator", "${satellite.name} 未找到真实AOS，使用估计值: ${Date(aosTime)}")
                        }

                        // 计算过境期间的最大仰角（从AOS到LOS）
                        val aosStateForMax = propagator.propagate(AbsoluteDate(Date(aosTime), utc))
                        val aosCoordsForMax = observerFrame.getTrackingCoordinates(aosStateForMax.position, aosStateForMax.frame, aosStateForMax.date)
                        var maxElevation = Math.toDegrees(aosCoordsForMax.elevation)
                        var maxElevationTime = aosTime

                        var currentDate = AbsoluteDate(Date(aosTime), utc)
                        val losDate = losState.date
                        while (currentDate.isBefore(losDate)) {
                            val state = propagator.propagate(currentDate)
                            val coords = observerFrame.getTrackingCoordinates(state.position, state.frame, state.date)
                            val elevation = Math.toDegrees(coords.elevation)

                            if (elevation > maxElevation) {
                                maxElevation = elevation
                                maxElevationTime = state.date.toDate(utc).time
                            }

                            currentDate = currentDate.shiftedBy(30.0)
                        }

                        val duration = (losTime - aosTime) / 1000

                        // 检查是否在时间范围内
                        val endTime = accurateTime + hoursAhead * 3600 * 1000
                        val isInTimeRange = losTime >= accurateTime

                        if (isInTimeRange) {
                            // 判断是否可见：AOS在当前时间之后，或者正在过境中
                            val isAosAfterNow = aosTime >= accurateTime
                            val isInProgressNow = aosTime < accurateTime && losTime > accurateTime
                            val isVisible = isAosAfterNow || isInProgressNow

                            // 详细日志用于调试
                            LogManager.d("SatellitePassCalculator", "${satellite.name} 过滤检查(已开始): aosTime=${Date(aosTime)}, accurateTime=${Date(accurateTime)}, losTime=${Date(losTime)}, endTime=${Date(endTime)}, isInTimeRange=$isInTimeRange, isAosAfterNow=$isAosAfterNow, isInProgressNow=$isInProgressNow, isVisible=$isVisible")

                            // 保存正在进行的过境
                            allPasses.add(
                                SatellitePass(
                                    noradId = satellite.noradId,
                                    name = satellite.name,
                                    aosTime = aosTime,
                                    losTime = losTime,
                                    maxElevation = maxElevation,
                                    aosAzimuth = aosAzimuth,
                                    losAzimuth = losAzimuth,
                                    duration = duration,
                                    maxElevationTime = maxElevationTime,
                                    isVisible = isVisible
                                )
                            )
                            LogManager.i("SatellitePassCalculator", "${satellite.name} 过境中(已开始): AOS=${Date(aosTime)}, LOS=${Date(losTime)}, 最大仰角=${String.format("%.1f", maxElevation)}°, 可见=$isVisible")
                        } else {
                            LogManager.d("SatellitePassCalculator", "${satellite.name} 跳过过境(已开始): losTime=${Date(losTime)} < accurateTime=${Date(accurateTime)}, 已结束")
                        }

                        i = 1 // 跳过第一个LOS事件
                    }

                    // 情况2: 处理完整的过境事件对（AOS+LOS）
                    while (i < loggedEvents.size) {
                        val aosEvent = loggedEvents[i]
                        if (!aosEvent.isIncreasing) {
                            i++
                            continue
                        }

                        // 找到对应的LOS事件
                        var losEvent: EventsLogger.LoggedEvent? = null
                        for (j in i + 1 until loggedEvents.size) {
                            if (!loggedEvents[j].isIncreasing) {
                                losEvent = loggedEvents[j]
                                break
                            }
                        }

                        // 如果没有找到LOS事件，说明这是最后一个未完成的过境（情况3）
                        if (losEvent == null) {
                            // 情况3: 卫星在endDate时还在过境中（最后一个事件是AOS）
                            val aosState = aosEvent.state
                            val aosCoords = observerFrame.getTrackingCoordinates(aosState.position, aosState.frame, aosState.date)
                            val aosTime = aosState.date.toDate(utc).time
                            val aosAzimuth = Math.toDegrees(aosCoords.azimuth)

                            // 使用endDate作为虚拟LOS时间
                            val losTime = endDate.toDate(utc).time
                            val finalCoords = observerFrame.getTrackingCoordinates(finalState.position, finalState.frame, finalState.date)
                            val losAzimuth = Math.toDegrees(finalCoords.azimuth)

                            // 计算过境期间的最大仰角
                            var maxElevation = Math.toDegrees(aosCoords.elevation)
                            var maxElevationTime = aosTime

                            // 在AOS和endDate之间以30秒步长搜索最大仰角
                            var currentDate = aosState.date
                            while (currentDate.isBefore(endDate)) {
                                val state = propagator.propagate(currentDate)
                                val coords = observerFrame.getTrackingCoordinates(state.position, state.frame, state.date)
                                val elevation = Math.toDegrees(coords.elevation)

                                if (elevation > maxElevation) {
                                    maxElevation = elevation
                                    maxElevationTime = state.date.toDate(utc).time
                                }

                                currentDate = currentDate.shiftedBy(30.0)
                            }

                            val duration = (losTime - aosTime) / 1000

                            // 检查是否在时间范围内
                            val endTime = accurateTime + hoursAhead * 3600 * 1000
                            val isInTimeRange = aosTime <= endTime

                            if (isInTimeRange) {
                                // 判断是否可见：AOS在当前时间之后，或者正在过境中
                                val isAosAfterNow = aosTime >= accurateTime
                                val isInProgressNow = aosTime < accurateTime && losTime > accurateTime
                                val isVisible = isAosAfterNow || isInProgressNow

                                // 详细日志用于调试
                                LogManager.d("SatellitePassCalculator", "${satellite.name} 过滤检查(未结束): aosTime=${Date(aosTime)}, accurateTime=${Date(accurateTime)}, losTime=${Date(losTime)}, endTime=${Date(endTime)}, isInTimeRange=$isInTimeRange, isAosAfterNow=$isAosAfterNow, isInProgressNow=$isInProgressNow, isVisible=$isVisible")

                                // 保存正在进行的过境
                                allPasses.add(
                                    SatellitePass(
                                        noradId = satellite.noradId,
                                        name = satellite.name,
                                        aosTime = aosTime,
                                        losTime = losTime,
                                        maxElevation = maxElevation,
                                        aosAzimuth = aosAzimuth,
                                        losAzimuth = losAzimuth,
                                        duration = duration,
                                        maxElevationTime = maxElevationTime,
                                        isVisible = isVisible
                                    )
                                )
                                LogManager.i("SatellitePassCalculator", "${satellite.name} 过境中(未结束): AOS=${Date(aosTime)}, 预计LOS=${Date(losTime)}, 当前最大仰角=${String.format("%.1f", maxElevation)}°, 可见=$isVisible")
                            } else {
                                LogManager.d("SatellitePassCalculator", "${satellite.name} 跳过过境(未结束): aosTime=${Date(aosTime)} > endTime=${Date(endTime)}, 超出时间范围")
                            }
                            break
                        }

                        // 获取AOS信息
                        val aosState = aosEvent.state
                        val aosCoords = observerFrame.getTrackingCoordinates(aosState.position, aosState.frame, aosState.date)
                        val aosTime = aosState.date.toDate(utc).time
                        val aosAzimuth = Math.toDegrees(aosCoords.azimuth)

                        // 获取LOS信息
                        val losState = losEvent.state
                        val losCoords = observerFrame.getTrackingCoordinates(losState.position, losState.frame, losState.date)
                        val losTime = losState.date.toDate(utc).time
                        val losAzimuth = Math.toDegrees(losCoords.azimuth)

                        // 计算过境期间的最大仰角
                        var maxElevation = Math.toDegrees(aosCoords.elevation)
                        var maxElevationTime = aosTime

                        // 在AOS和LOS之间以30秒步长搜索最大仰角
                        var currentDate = aosState.date
                        val losDate = losState.date
                        while (currentDate.isBefore(losDate)) {
                            val state = propagator.propagate(currentDate)
                            val coords = observerFrame.getTrackingCoordinates(state.position, state.frame, state.date)
                            val elevation = Math.toDegrees(coords.elevation)

                            if (elevation > maxElevation) {
                                maxElevation = elevation
                                maxElevationTime = state.date.toDate(utc).time
                            }

                            currentDate = currentDate.shiftedBy(30.0)
                        }

                        val duration = (losTime - aosTime) / 1000

                        // 只保存有效的过境（时长至少30秒，且在时间范围内）
                        val endTime = accurateTime + hoursAhead * 3600 * 1000
                        val isInTimeRange = aosTime <= endTime && losTime >= accurateTime

                        if (duration >= 30 && isInTimeRange) {
                            // 判断是否可见：AOS在当前时间之后，或者正在过境中
                            val isAosAfterNow = aosTime >= accurateTime
                            val isInProgressNow = aosTime < accurateTime && losTime > accurateTime
                            val isVisible = isAosAfterNow || isInProgressNow

                            // 详细日志用于调试
                            LogManager.d("SatellitePassCalculator", "${satellite.name} 过滤检查(完整): aosTime=${Date(aosTime)}, accurateTime=${Date(accurateTime)}, losTime=${Date(losTime)}, endTime=${Date(endTime)}, isInTimeRange=$isInTimeRange, isAosAfterNow=$isAosAfterNow, isInProgressNow=$isInProgressNow, isVisible=$isVisible, duration=${duration}s")

                            allPasses.add(
                                SatellitePass(
                                    noradId = satellite.noradId,
                                    name = satellite.name,
                                    aosTime = aosTime,
                                    losTime = losTime,
                                    maxElevation = maxElevation,
                                    aosAzimuth = aosAzimuth,
                                    losAzimuth = losAzimuth,
                                    duration = duration,
                                    maxElevationTime = maxElevationTime,
                                    isVisible = isVisible
                                )
                            )
                        } else {
                            LogManager.d("SatellitePassCalculator", "${satellite.name} 跳过过境: aosTime=${Date(aosTime)}, losTime=${Date(losTime)}, duration=${duration}s, isInTimeRange=$isInTimeRange")
                        }

                        i = loggedEvents.indexOf(losEvent) + 1
                    }

                    LogManager.d("SatellitePassCalculator", "${satellite.name} 计算完成: 找到 ${allPasses.count { it.noradId == satellite.noradId }} 个过境")

                } catch (e: Exception) {
                    LogManager.e("SatellitePassCalculator", "计算过境信息失败: ${satellite.name}", e)
                }
            }

        } catch (e: Exception) {
            LogManager.e("SatellitePassCalculator", "计算过境信息失败", e)
        }

        // 按入境时间排序
        val sortedPasses = allPasses.sortedBy { it.aosTime }
        val visibleCount = sortedPasses.count { it.isVisible }
        val hiddenCount = sortedPasses.size - visibleCount
        LogManager.i("SatellitePassCalculator", "所有卫星过境计算完成: 共 ${sortedPasses.size} 个过境, 可见=$visibleCount, 隐藏=$hiddenCount")

        // 详细列出所有不可见的过境用于调试
        sortedPasses.filter { !it.isVisible }.forEach { pass ->
            LogManager.d("SatellitePassCalculator", "隐藏过境: ${pass.name}, AOS=${Date(pass.aosTime)}, LOS=${Date(pass.losTime)}")
        }

        sortedPasses
    }

    /**
     * 格式化过境时长
     * @param durationSeconds 时长（秒）
     * @return 格式化字符串（如 "05:30"）
     */
    fun formatDuration(durationSeconds: Long): String {
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    /**
     * 格式化方位角
     * @param azimuth 方位角（度）
     * @return 方位名称（如 "东北"）
     */
    fun getAzimuthDirection(azimuth: Double): String {
        return when {
            azimuth >= 337.5 || azimuth < 22.5 -> "北"
            azimuth >= 22.5 && azimuth < 67.5 -> "东北"
            azimuth >= 67.5 && azimuth < 112.5 -> "东"
            azimuth >= 112.5 && azimuth < 157.5 -> "东南"
            azimuth >= 157.5 && azimuth < 202.5 -> "南"
            azimuth >= 202.5 && azimuth < 247.5 -> "西南"
            azimuth >= 247.5 && azimuth < 292.5 -> "西"
            azimuth >= 292.5 && azimuth < 337.5 -> "西北"
            else -> "未知"
        }
    }
}
