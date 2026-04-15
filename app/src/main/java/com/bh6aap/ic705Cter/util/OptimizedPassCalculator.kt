package com.bh6aap.ic705Cter.util

import android.content.Context
import com.bh6aap.ic705Cter.data.database.DatabaseHelper
import com.bh6aap.ic705Cter.data.database.entity.SatelliteEntity
import com.bh6aap.ic705Cter.data.time.NtpTimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 优化的卫星过境计算器
 * 支持并行计算，提升加载速度
 */
object OptimizedPassCalculator {

    // 并行计算的最大并发数（避免过多线程导致性能下降）
    private const val MAX_CONCURRENT_CALCULATIONS = 4
    
    // 批量计算时的批处理大小
    private const val BATCH_SIZE = 5

    /**
     * 计算多个卫星的过境信息（优化版）
     * 
     * 优化点：
     * 1. 并行计算多颗卫星
     * 2. 共享坐标系和地球模型（只创建一次）
     * 3. 批量处理减少协程开销
     * 
     * @param context 上下文
     * @param satellites 卫星列表
     * @param minElevation 最小仰角（度）
     * @param hoursAhead 预测未来几小时
     * @param forceRefresh 强制刷新，跳过缓存检查
     * @return 按时间排序的过境信息列表
     */
    suspend fun calculatePassesForMultipleSatellites(
        context: Context,
        satellites: List<SatelliteEntity>,
        minElevation: Double = 0.0,
        hoursAhead: Int = 24,
        forceRefresh: Boolean = false
    ): List<SatellitePassCalculator.SatellitePass> = withContext(Dispatchers.IO) {

        val startTime = System.currentTimeMillis()

        // 从数据库读取默认地面站（GPS位置）
        val dbHelper = DatabaseHelper.getInstance(context)
        val station = dbHelper.getDefaultStation()

        if (station == null) {
            LogManager.e("OptimizedPassCalculator", "数据库中没有默认地面站，无法计算过境")
            return@withContext emptyList()
        }

        val observerLat = station.latitude
        val observerLon = station.longitude
        val observerAlt = station.altitude

        LogManager.i("OptimizedPassCalculator", "开始计算 ${satellites.size} 颗卫星的过境信息, 地面站: ${station.name}")
        LogManager.d("OptimizedPassCalculator", "地面站坐标: 纬度=$observerLat°, 经度=$observerLon°")

        try {
            // 获取NTP校准后的时间作为起始时间
            val ntpManager = NtpTimeManager(context)
            val accurateTime = ntpManager.getCachedAccurateTime()
            LogManager.i("OptimizedPassCalculator", "使用NTP时间: ${Date(accurateTime)}")

            // 检查缓存 - 过滤掉已过去的过境（强制刷新时跳过缓存）
            val cachedPasses = mutableListOf<SatellitePassCalculator.SatellitePass>()
            val satellitesToCalculate = mutableListOf<SatelliteEntity>()

            if (forceRefresh) {
                // 强制刷新模式：所有卫星都重新计算
                satellitesToCalculate.addAll(satellites)
                LogManager.i("OptimizedPassCalculator", "强制刷新模式：跳过缓存，计算所有 ${satellites.size} 颗卫星")
            } else {
                satellites.forEach { satellite ->
                    val cache = dbHelper.getPassCache(satellite.noradId, accurateTime, hoursAhead)
                    if (cache != null && cache.isNotEmpty()) {
                        // 检查位置变化
                        val cacheLocation = dbHelper.getCacheStationLocation(satellite.noradId)
                        val shouldUseCache = if (cacheLocation != null) {
                            val (cacheLat, cacheLon, _) = cacheLocation
                            val distance = calculateDistance(observerLat, observerLon, cacheLat, cacheLon)
                            distance <= 20.0 // 位置变化不超过20公里可以使用缓存
                        } else {
                            false
                        }

                        if (shouldUseCache) {
                            // 使用缓存数据，过滤掉已过去的过境
                            val validPasses = cache
                                .filter { it.losTime > accurateTime }
                                .map { it.toSatellitePass() }
                            cachedPasses.addAll(validPasses)
                            LogManager.d("OptimizedPassCalculator", "使用缓存: ${satellite.name}, ${validPasses.size} 个有效过境")
                        } else {
                            satellitesToCalculate.add(satellite)
                        }
                    } else {
                        satellitesToCalculate.add(satellite)
                    }
                }

                LogManager.i("OptimizedPassCalculator", "缓存命中: ${satellites.size - satellitesToCalculate.size}/${satellites.size} 颗卫星, 缓存过境数: ${cachedPasses.size}")
            }

            // 如果有卫星需要计算
            val calculatedPasses = if (satellitesToCalculate.isNotEmpty()) {
                // 1. 初始化共享资源（只创建一次）
                val sharedResources = initializeSharedResources(observerLat, observerLon, observerAlt, accurateTime, hoursAhead)
                LogManager.d("OptimizedPassCalculator", "共享资源初始化完成")

                // 2. 并行计算所有卫星的过境
                val allPasses = ConcurrentLinkedQueue<SatellitePassCalculator.SatellitePass>()
                val semaphore = Semaphore(MAX_CONCURRENT_CALCULATIONS)

                // 将卫星分批处理，减少协程开销
                val satelliteBatches = satellitesToCalculate.chunked(BATCH_SIZE)

                val deferredResults = coroutineScope {
                    satelliteBatches.mapIndexed { batchIndex, batch ->
                        async {
                            batch.forEach { satellite ->
                                semaphore.withPermit {
                                    try {
                                        val passes = calculateSingleSatellitePassesOptimized(
                                            satellite = satellite,
                                            sharedResources = sharedResources,
                                            minElevation = minElevation,
                                            accurateTime = accurateTime,
                                            hoursAhead = hoursAhead
                                        )
                                        allPasses.addAll(passes)

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
                                    } catch (e: Exception) {
                                        LogManager.e("OptimizedPassCalculator", "计算过境失败: ${satellite.name}", e)
                                    }
                                }
                            }
                            LogManager.d("OptimizedPassCalculator", "批次 $batchIndex/${satelliteBatches.size} 完成，处理了 ${batch.size} 颗卫星")
                        }
                    }
                }

                // 等待所有批次完成
                deferredResults.awaitAll()
                allPasses.toList()
            } else {
                emptyList()
            }

            // 3. 合并缓存和计算结果，按入境时间排序，并过滤掉低于最小仰角的过境
            val allPasses = (cachedPasses + calculatedPasses)
                .sortedBy { it.aosTime }
                .filter { it.maxElevation >= minElevation }
            
            val visibleCount = allPasses.count { it.isVisible }
            val hiddenCount = allPasses.size - visibleCount

            val calculationTime = System.currentTimeMillis() - startTime
            LogManager.i("OptimizedPassCalculator", "计算完成: 共 ${allPasses.size} 个过境 (缓存=${cachedPasses.size}, 计算=${calculatedPasses.size}), 可见=$visibleCount, 隐藏=$hiddenCount, 耗时: ${calculationTime}ms")

            return@withContext allPasses

        } catch (e: Exception) {
            LogManager.e("OptimizedPassCalculator", "计算过境信息失败", e)
            return@withContext emptyList()
        }
    }

    /**
     * 分阶段计算卫星过境信息
     * 先计算3小时内的过境并立即回调显示，再计算剩余的时间
     *
     * @param context 上下文
     * @param satellites 卫星列表
     * @param minElevation 最小仰角（度）
     * @param hoursAhead 预测未来几小时
     * @param onFirstBatchReady 3小时内过境计算完成回调
     * @param onComplete 全部计算完成回调
     */
    suspend fun calculatePassesInStages(
        context: Context,
        satellites: List<SatelliteEntity>,
        minElevation: Double = 0.0,
        hoursAhead: Int = 24,
        onFirstBatchReady: (List<SatellitePassCalculator.SatellitePass>) -> Unit,
        onComplete: (List<SatellitePassCalculator.SatellitePass>) -> Unit
    ) = withContext(Dispatchers.IO) {

        val totalStartTime = System.currentTimeMillis()

        // 从数据库读取默认地面站（GPS位置）
        val dbHelper = DatabaseHelper.getInstance(context)
        val station = dbHelper.getDefaultStation()

        if (station == null) {
            LogManager.e("OptimizedPassCalculator", "数据库中没有默认地面站，无法计算过境")
            onFirstBatchReady(emptyList())
            onComplete(emptyList())
            return@withContext
        }

        val observerLat = station.latitude
        val observerLon = station.longitude
        val observerAlt = station.altitude

        LogManager.i("OptimizedPassCalculator", "【分阶段】开始计算 ${satellites.size} 颗卫星的过境信息，预测未来 ${hoursAhead} 小时")

        try {
            // 获取NTP校准后的时间作为起始时间
            val ntpManager = NtpTimeManager(context)
            val accurateTime = ntpManager.getCachedAccurateTime()

            // ========== 第一阶段：计算3小时内的过境 ==========
            LogManager.i("OptimizedPassCalculator", "【分阶段】开始计算3小时内的过境...")
            val stage1StartTime = System.currentTimeMillis()

            val sharedResources3h = initializeSharedResources(observerLat, observerLon, observerAlt, accurateTime, 3)
            val firstBatchPasses = ConcurrentLinkedQueue<SatellitePassCalculator.SatellitePass>()
            val semaphore3h = Semaphore(MAX_CONCURRENT_CALCULATIONS)

            val satelliteBatches = satellites.chunked(BATCH_SIZE)

            val deferredResults3h = coroutineScope {
                satelliteBatches.mapIndexed { batchIndex, batch ->
                    async {
                        batch.forEach { satellite ->
                            semaphore3h.withPermit {
                                try {
                                    val passes = calculateSingleSatellitePassesOptimized(
                                        satellite = satellite,
                                        sharedResources = sharedResources3h,
                                        minElevation = minElevation,
                                        accurateTime = accurateTime,
                                        hoursAhead = 3
                                    )
                                    firstBatchPasses.addAll(passes)
                                } catch (e: Exception) {
                                    LogManager.e("OptimizedPassCalculator", "【分阶段-3h】计算过境失败: ${satellite.name}", e)
                                }
                            }
                        }
                    }
                }
            }
            deferredResults3h.awaitAll()

            val sortedFirstBatchPasses = firstBatchPasses.sortedBy { it.aosTime }
            val stage1Time = System.currentTimeMillis() - stage1StartTime

            LogManager.i("OptimizedPassCalculator", "【分阶段】3小时内过境计算完成: ${sortedFirstBatchPasses.size} 个, 耗时: ${stage1Time}ms")

            // 立即回调显示3小时内的过境
            withContext(Dispatchers.Main) {
                onFirstBatchReady(sortedFirstBatchPasses)
            }

            // ========== 第二阶段：计算剩余时间的过境 ==========
            val remainingHours = hoursAhead - 3
            LogManager.i("OptimizedPassCalculator", "【分阶段】开始计算剩余 ${remainingHours} 小时的过境...")
            val stage2StartTime = System.currentTimeMillis()

            // 计算3小时到指定时间的过境（从3小时后开始）
            val sharedResourcesFull = initializeSharedResources(observerLat, observerLon, observerAlt, accurateTime, hoursAhead)
            val remainingPasses = ConcurrentLinkedQueue<SatellitePassCalculator.SatellitePass>()
            val semaphoreFull = Semaphore(MAX_CONCURRENT_CALCULATIONS)

            val deferredResultsFull = coroutineScope {
                satelliteBatches.mapIndexed { batchIndex, batch ->
                    async {
                        batch.forEach { satellite ->
                            semaphoreFull.withPermit {
                                try {
                                    // 计算完整的过境
                                    val allPasses = calculateSingleSatellitePassesOptimized(
                                        satellite = satellite,
                                        sharedResources = sharedResourcesFull,
                                        minElevation = minElevation,
                                        accurateTime = accurateTime,
                                        hoursAhead = hoursAhead
                                    )
                                    // 只保留3小时后的过境（即AOS时间 > accurateTime + 3小时）
                                    val threeHoursLater = accurateTime + 3 * 60 * 60 * 1000
                                    val filteredPasses = allPasses.filter { it.aosTime > threeHoursLater }
                                    remainingPasses.addAll(filteredPasses)
                                } catch (e: Exception) {
                                    LogManager.e("OptimizedPassCalculator", "【分阶段-${hoursAhead}h】计算过境失败: ${satellite.name}", e)
                                }
                            }
                        }
                    }
                }
            }
            deferredResultsFull.awaitAll()

            val sortedRemainingPasses = remainingPasses.sortedBy { it.aosTime }
            val stage2Time = System.currentTimeMillis() - stage2StartTime

            // 合并结果
            val allPasses = (sortedFirstBatchPasses + sortedRemainingPasses).sortedBy { it.aosTime }
            val totalTime = System.currentTimeMillis() - totalStartTime

            LogManager.i("OptimizedPassCalculator", "【分阶段】全部计算完成: 3h内=${sortedFirstBatchPasses.size}个, 剩余=${sortedRemainingPasses.size}个, 总耗时: ${totalTime}ms")

            // 回调完整的过境列表
            withContext(Dispatchers.Main) {
                onComplete(allPasses)
            }

        } catch (e: Exception) {
            LogManager.e("OptimizedPassCalculator", "【分阶段】计算过境信息失败", e)
            withContext(Dispatchers.Main) {
                onFirstBatchReady(emptyList())
                onComplete(emptyList())
            }
        }
    }

    /**
     * 共享资源类
     * 避免重复创建昂贵的对象
     */
    data class SharedResources(
        val utc: org.orekit.time.TimeScale,
        val itrfFrame: Frame,
        val earth: OneAxisEllipsoid,
        val observerFrame: TopocentricFrame,
        val startDate: AbsoluteDate,
        val endDate: AbsoluteDate
    )

    /**
     * 初始化共享资源
     */
    private fun initializeSharedResources(
        observerLat: Double,
        observerLon: Double,
        observerAlt: Double,
        accurateTime: Long,
        hoursAhead: Int
    ): SharedResources {
        val utc = TimeScalesFactory.getUTC()
        val itrfFrame: Frame = FramesFactory.getITRF(IERSConventions.IERS_2010, true)
        val earth = OneAxisEllipsoid(
            Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
            Constants.WGS84_EARTH_FLATTENING,
            itrfFrame
        )

        val observerPoint = GeodeticPoint(
            Math.toRadians(observerLat),
            Math.toRadians(observerLon),
            observerAlt
        )
        val observerFrame = TopocentricFrame(earth, observerPoint, "GroundStation")

        val startDate = AbsoluteDate(Date(accurateTime), utc)
        val endDate = startDate.shiftedBy(hoursAhead * 3600.0)

        return SharedResources(utc, itrfFrame, earth, observerFrame, startDate, endDate)
    }

    /**
     * 优化后的单卫星过境计算
     */
    private fun calculateSingleSatellitePassesOptimized(
        satellite: SatelliteEntity,
        sharedResources: SharedResources,
        minElevation: Double,
        accurateTime: Long,
        hoursAhead: Int
    ): List<SatellitePassCalculator.SatellitePass> {
        
        // 验证TLE数据
        if (satellite.tleLine1.isBlank() || satellite.tleLine2.isBlank()) {
            LogManager.w("OptimizedPassCalculator", "卫星 ${satellite.name} 的TLE数据为空，跳过")
            return emptyList()
        }

        val passes = mutableListOf<SatellitePassCalculator.SatellitePass>()
        val utc = sharedResources.utc
        val observerFrame = sharedResources.observerFrame

        // 初始化卫星轨道传播器
        val tle = TLE(satellite.tleLine1, satellite.tleLine2)
        val propagator = TLEPropagator.selectExtrapolator(tle)

        // 创建仰角检测器（使用0度作为阈值，确保捕获所有过境事件）
        // 注意：这里使用0度而不是minElevation，以确保捕获所有过境
        // 最后会根据用户设置的minElevation过滤最大仰角
        val elevationDetector = ElevationDetector(observerFrame)
            .withConstantElevation(Math.toRadians(0.0))
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
        val finalState = propagator.propagate(sharedResources.startDate, sharedResources.endDate)

        // 从事件日志中提取过境信息
        val loggedEvents = eventsLogger.loggedEvents

        // 检查卫星在startDate时是否已经在过境中
        // 注意：使用0度作为阈值，确保捕获所有正在过境的卫星（即使当前仰角低于minElevation）
        val startState = propagator.propagate(sharedResources.startDate)
        val startCoords = observerFrame.getTrackingCoordinates(startState.position, startState.frame, startState.date)
        val startElevation = Math.toDegrees(startCoords.elevation)
        val isInPassAtStart = startElevation > 0.0

        // 处理所有过境情况
        var i = 0

        // 情况1: 卫星在startDate时已经在过境中
        if (isInPassAtStart && loggedEvents.isNotEmpty() && !loggedEvents[0].isIncreasing) {
            val losEvent = loggedEvents[0]
            val losState = losEvent.state
            val losCoords = observerFrame.getTrackingCoordinates(losState.position, losState.frame, losState.date)
            val losTime = losState.date.toDate(utc).time
            val losAzimuth = Math.toDegrees(losCoords.azimuth)

            // 向后搜索找到真实的AOS
            val backwardPropagator = TLEPropagator.selectExtrapolator(tle)
            val backwardEventsLogger = EventsLogger()
            backwardPropagator.addEventDetector(backwardEventsLogger.monitorDetector(elevationDetector))
            
            val oneHourAgo = sharedResources.startDate.shiftedBy(-3600.0)
            backwardPropagator.propagate(sharedResources.startDate, oneHourAgo)

            val backwardEvents = backwardEventsLogger.loggedEvents
            var aosTime = sharedResources.startDate.shiftedBy(-900.0).toDate(utc).time
            var aosAzimuth = Math.toDegrees(startCoords.azimuth)
            var foundRealAos = false

            for (j in backwardEvents.size - 1 downTo 0) {
                if (backwardEvents[j].isIncreasing) {
                    val aosState = backwardEvents[j].state
                    val aosCoords = observerFrame.getTrackingCoordinates(aosState.position, aosState.frame, aosState.date)
                    aosTime = aosState.date.toDate(utc).time
                    aosAzimuth = Math.toDegrees(aosCoords.azimuth)
                    foundRealAos = true
                    break
                }
            }

            // 计算最大仰角（使用二分查找优化）
            val (maxElevation, maxElevationTime) = calculateMaxElevationOptimized(
                propagator, observerFrame, utc, AbsoluteDate(Date(aosTime), utc), losState.date
            )

            val duration = (losTime - aosTime) / 1000
            val endTime = accurateTime + hoursAhead * 3600 * 1000
            val isInTimeRange = losTime >= accurateTime

            if (isInTimeRange) {
                val isAosAfterNow = aosTime >= accurateTime
                val isInProgressNow = aosTime < accurateTime && losTime > accurateTime
                val isVisible = isAosAfterNow || isInProgressNow

                passes.add(
                    SatellitePassCalculator.SatellitePass(
                        noradId = satellite.noradId,
                        name = satellite.name,
                        aosTime = aosTime,
                        losTime = losTime,
                        maxElevation = maxElevation,
                        aosAzimuth = aosAzimuth,
                        losAzimuth = losAzimuth,
                        duration = duration,
                        maxElevationTime = maxElevationTime,
                        isVisible = isVisible,
                        isInProgress = isInProgressNow
                    )
                )
            }

            i = 1
        }

        // 情况2: 处理完整的过境事件对
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

            // 情况3: 卫星在endDate时还在过境中
            if (losEvent == null) {
                val aosState = aosEvent.state
                val aosCoords = observerFrame.getTrackingCoordinates(aosState.position, aosState.frame, aosState.date)
                val aosTime = aosState.date.toDate(utc).time
                val aosAzimuth = Math.toDegrees(aosCoords.azimuth)

                val losTime = sharedResources.endDate.toDate(utc).time
                val finalCoords = observerFrame.getTrackingCoordinates(finalState.position, finalState.frame, finalState.date)
                val losAzimuth = Math.toDegrees(finalCoords.azimuth)

                val (maxElevation, maxElevationTime) = calculateMaxElevationOptimized(
                    propagator, observerFrame, utc, aosState.date, sharedResources.endDate
                )

                val duration = (losTime - aosTime) / 1000
                val endTime = accurateTime + hoursAhead * 3600 * 1000
                val isInTimeRange = aosTime <= endTime

                if (isInTimeRange) {
                    val isAosAfterNow = aosTime >= accurateTime
                    val isInProgressNow = aosTime < accurateTime && losTime > accurateTime
                    val isVisible = isAosAfterNow || isInProgressNow

                    passes.add(
                        SatellitePassCalculator.SatellitePass(
                            noradId = satellite.noradId,
                            name = satellite.name,
                            aosTime = aosTime,
                            losTime = losTime,
                            maxElevation = maxElevation,
                            aosAzimuth = aosAzimuth,
                            losAzimuth = losAzimuth,
                            duration = duration,
                            maxElevationTime = maxElevationTime,
                            isVisible = isVisible,
                            isInProgress = isInProgressNow
                        )
                    )
                }
                break
            }

            // 处理完整的过境
            val aosState = aosEvent.state
            val aosCoords = observerFrame.getTrackingCoordinates(aosState.position, aosState.frame, aosState.date)
            val aosTime = aosState.date.toDate(utc).time
            val aosAzimuth = Math.toDegrees(aosCoords.azimuth)

            val losState = losEvent.state
            val losCoords = observerFrame.getTrackingCoordinates(losState.position, losState.frame, losState.date)
            val losTime = losState.date.toDate(utc).time
            val losAzimuth = Math.toDegrees(losCoords.azimuth)

            val (maxElevation, maxElevationTime) = calculateMaxElevationOptimized(
                propagator, observerFrame, utc, aosState.date, losState.date
            )

            val duration = (losTime - aosTime) / 1000
            val endTime = accurateTime + hoursAhead * 3600 * 1000
            val isInTimeRange = aosTime <= endTime && losTime >= accurateTime

            if (duration >= 30 && isInTimeRange) {
                val isAosAfterNow = aosTime >= accurateTime
                val isInProgressNow = aosTime < accurateTime && losTime > accurateTime
                val isVisible = isAosAfterNow || isInProgressNow

                passes.add(
                    SatellitePassCalculator.SatellitePass(
                        noradId = satellite.noradId,
                        name = satellite.name,
                        aosTime = aosTime,
                        losTime = losTime,
                        maxElevation = maxElevation,
                        aosAzimuth = aosAzimuth,
                        losAzimuth = losAzimuth,
                        duration = duration,
                        maxElevationTime = maxElevationTime,
                        isVisible = isVisible,
                        isInProgress = isInProgressNow
                    )
                )
            }

            i = loggedEvents.indexOf(losEvent) + 1
        }

        // 过滤掉最大仰角低于指定角度的卫星
        // 纯粹基于最大仰角过滤，无论是否正在过境
        val filteredPasses = passes.filter { pass ->
            pass.maxElevation >= minElevation
        }
        LogManager.d("OptimizedPassCalculator", "【过滤】原始过境数: ${passes.size}, 过滤后: ${filteredPasses.size}, 最小仰角: ${minElevation}°")
        passes.forEach { pass ->
            if (pass.maxElevation < minElevation) {
                LogManager.d("OptimizedPassCalculator", "【过滤】移除卫星: ${pass.name}, 最大仰角: ${pass.maxElevation}° < ${minElevation}°")
            }
        }
        return filteredPasses
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
     * 优化后的最大仰角计算（使用粗搜+细搜）
     */
    private fun calculateMaxElevationOptimized(
        propagator: TLEPropagator,
        observerFrame: TopocentricFrame,
        utc: org.orekit.time.TimeScale,
        startDate: AbsoluteDate,
        endDate: AbsoluteDate
    ): Pair<Double, Long> {
        
        // 首先粗略搜索找到最大仰角的大致位置
        var maxElevation = 0.0
        var maxElevationTime = startDate.toDate(utc).time
        
        // 粗略搜索（60秒步长）
        var currentDate = startDate
        while (currentDate.isBefore(endDate)) {
            val state = propagator.propagate(currentDate)
            val coords = observerFrame.getTrackingCoordinates(state.position, state.frame, state.date)
            val elevation = Math.toDegrees(coords.elevation)

            if (elevation > maxElevation) {
                maxElevation = elevation
                maxElevationTime = state.date.toDate(utc).time
            }
            currentDate = currentDate.shiftedBy(60.0)
        }

        // 在最大仰角附近精细搜索（5秒步长，前后各2分钟）
        val fineStart = AbsoluteDate(Date(maxElevationTime - 120000), utc)
        val fineEnd = AbsoluteDate(Date(maxElevationTime + 120000), utc)
        
        var fineDate = if (fineStart.isBefore(startDate)) startDate else fineStart
        val actualFineEnd = if (fineEnd.isAfter(endDate)) endDate else fineEnd
        
        while (fineDate.isBefore(actualFineEnd)) {
            val state = propagator.propagate(fineDate)
            val coords = observerFrame.getTrackingCoordinates(state.position, state.frame, state.date)
            val elevation = Math.toDegrees(coords.elevation)

            if (elevation > maxElevation) {
                maxElevation = elevation
                maxElevationTime = state.date.toDate(utc).time
            }
            fineDate = fineDate.shiftedBy(5.0)
        }

        return Pair(maxElevation, maxElevationTime)
    }
}
