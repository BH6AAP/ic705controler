package com.bh6aap.ic705Cter.util

import kotlin.math.*

/**
 * 梅登海德网格定位系统 (Maidenhead Locator System) 转换工具
 * 用于在梅登海德网格坐标和经纬度之间转换
 */
object MaidenheadConverter {

    /**
     * 将梅登海德网格转换为经纬度
     * @param maidenhead 梅登海德网格字符串（支持 6位、8位、10位）
     * @return 经纬度数组 [纬度, 经度]，如果转换失败返回 null
     */
    fun maidenheadToLatLon(maidenhead: String): DoubleArray? {
        val locator = maidenhead.uppercase().trim()

        // 验证长度（必须是偶数且 >= 6）
        if (locator.length < 6 || locator.length % 2 != 0) {
            LogManager.w(LogManager.TAG_GPS, "【梅登海德】网格格式错误，长度必须是 >= 6 的偶数: $maidenhead")
            return null
        }

        // 验证字符
        if (!isValidMaidenhead(locator)) {
            LogManager.w(LogManager.TAG_GPS, "【梅登海德】网格包含无效字符: $maidenhead")
            return null
        }

        return try {
            var lat = -90.0
            var lon = -180.0
            var latDiv = 180.0
            var lonDiv = 360.0

            // 第1对：字段（A-R）
            lonDiv /= 18
            latDiv /= 18
            lon += (locator[0] - 'A') * lonDiv
            lat += (locator[1] - 'A') * latDiv

            // 第2对：方块（0-9）
            lonDiv /= 10
            latDiv /= 10
            lon += (locator[2] - '0') * lonDiv
            lat += (locator[3] - '0') * latDiv

            // 第3对：子方块（A-X）
            lonDiv /= 24
            latDiv /= 24
            lon += (locator[4] - 'A') * lonDiv
            lat += (locator[5] - 'A') * latDiv

            // 第4对（可选）：扩展（0-9）
            if (locator.length >= 8) {
                lonDiv /= 10
                latDiv /= 10
                lon += (locator[6] - '0') * lonDiv
                lat += (locator[7] - '0') * latDiv
            }

            // 第5对（可选）：进一步扩展（A-X）
            if (locator.length >= 10) {
                lonDiv /= 24
                latDiv /= 24
                lon += (locator[8] - 'A') * lonDiv
                lat += (locator[9] - 'A') * latDiv
            }

            // 计算中心点
            lat += latDiv / 2
            lon += lonDiv / 2

            LogManager.d(LogManager.TAG_GPS, "【梅登海德】转换成功: $maidenhead -> 纬度: $lat, 经度: $lon")
            doubleArrayOf(lat, lon)
        } catch (e: Exception) {
            LogManager.e(LogManager.TAG_GPS, "【梅登海德】转换失败: $maidenhead", e)
            null
        }
    }

    /**
     * 将经纬度转换为梅登海德网格
     * @param latitude 纬度（-90 到 90）
     * @param longitude 经度（-180 到 180）
     * @param precision 精度（6、8、10位，默认6位）
     * @return 梅登海德网格字符串
     */
    fun latLonToMaidenhead(latitude: Double, longitude: Double, precision: Int = 6): String {
        if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
            LogManager.w(LogManager.TAG_GPS, "【梅登海德】经纬度超出范围: 纬度=$latitude, 经度=$longitude")
            return ""
        }

        val sb = StringBuilder()
        var lat = latitude + 90.0
        var lon = longitude + 180.0

        // 第1对：字段（A-R）
        sb.append((lon / 20).toInt().plus('A'.code).toChar())
        sb.append((lat / 10).toInt().plus('A'.code).toChar())
        lon %= 20
        lat %= 10

        // 第2对：方块（0-9）
        sb.append((lon / 2).toInt().plus('0'.code).toChar())
        sb.append(lat.toInt().plus('0'.code).toChar())
        lon %= 2
        lat %= 1

        // 第3对：子方块（A-X）
        if (precision >= 6) {
            sb.append((lon * 12).toInt().plus('A'.code).toChar())
            sb.append((lat * 24).toInt().plus('A'.code).toChar())
            lon -= (lon * 12).toInt() / 12.0
            lat -= (lat * 24).toInt() / 24.0
        }

        // 第4对：扩展（0-9）
        if (precision >= 8) {
            sb.append((lon * 120).toInt().plus('0'.code).toChar())
            sb.append((lat * 240).toInt().plus('0'.code).toChar())
            lon -= (lon * 120).toInt() / 120.0
            lat -= (lat * 240).toInt() / 240.0
        }

        // 第5对：进一步扩展（A-X）
        if (precision >= 10) {
            sb.append((lon * 2880).toInt().plus('A'.code).toChar())
            sb.append((lat * 5760).toInt().plus('A'.code).toChar())
        }

        val result = sb.toString()
        LogManager.d(LogManager.TAG_GPS, "【梅登海德】转换成功: 纬度=$latitude, 经度=$longitude -> $result")
        return result
    }

    /**
     * 验证梅登海德网格格式
     */
    fun isValidMaidenhead(maidenhead: String): Boolean {
        val locator = maidenhead.uppercase().trim()

        if (locator.length < 6 || locator.length % 2 != 0) {
            return false
        }

        // 第1对：必须是 A-R
        if (locator[0] !in 'A'..'R' || locator[1] !in 'A'..'R') {
            return false
        }

        // 第2对：必须是 0-9
        if (locator[2] !in '0'..'9' || locator[3] !in '0'..'9') {
            return false
        }

        // 第3对：必须是 A-X
        if (locator[4] !in 'A'..'X' || locator[5] !in 'A'..'X') {
            return false
        }

        // 第4对（可选）：必须是 0-9
        if (locator.length >= 8) {
            if (locator[6] !in '0'..'9' || locator[7] !in '0'..'9') {
                return false
            }
        }

        // 第5对（可选）：必须是 A-X
        if (locator.length >= 10) {
            if (locator[8] !in 'A'..'X' || locator[9] !in 'A'..'X') {
                return false
            }
        }

        return true
    }

    /**
     * 获取梅登海德网格的精度描述
     */
    fun getPrecisionDescription(length: Int): String {
        return when (length) {
            6 -> "约 4.6km × 2.3km"
            8 -> "约 460m × 230m"
            10 -> "约 19m × 9.6m"
            else -> "未知精度"
        }
    }

    /**
     * 计算两个梅登海德网格之间的距离（公里）
     */
    fun calculateDistance(maidenhead1: String, maidenhead2: String): Double? {
        val coord1 = maidenheadToLatLon(maidenhead1) ?: return null
        val coord2 = maidenheadToLatLon(maidenhead2) ?: return null

        return calculateDistance(coord1[0], coord1[1], coord2[0], coord2[1])
    }

    /**
     * 计算两点之间的距离（使用 Haversine 公式）
     */
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // 地球半径（公里）

        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLon = Math.toRadians(lon2 - lon1)

        val a = sin(deltaLat / 2).pow(2) +
                cos(lat1Rad) * cos(lat2Rad) * sin(deltaLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return R * c
    }
}
