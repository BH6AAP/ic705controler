package com.bh6aap.ic705Cter.tracking

import com.bh6aap.ic705Cter.util.LogManager

/**
 * 统一的多普勒计算器
 * 基于径向速度计算多普勒频移和频率补偿
 *
 * 公式说明（基于ISS示例）：
 * - 下行链路（卫星→地面）：f_r,down = f_0,down * (c - v_r) / c
 * - 上行链路（地面→卫星）：f_t,up = f_0,up * c / (c - v_r)
 *
 * 其中：
 * - v_r 是径向速度（Orekit定义：正值表示远离，负值表示靠近）
 * - c 是光速
 * - f_0 是卫星标称频率
 * - f_r 是地面接收频率
 * - f_t 是地面发射频率
 */
object DopplerCalculator {

    private const val TAG = "DopplerCalculator"
    private const val SPEED_OF_LIGHT = 299792458.0  // 光速（m/s）

    /**
     * 计算地面接收频率（下行）
     * @param satelliteFreqHz 卫星发射频率（Hz）
     * @param rangeRateMps 径向速度（m/s），正值表示远离，负值表示靠近
     * @return 地面接收频率（Hz），输入包含 NaN/Infinity 时返回 0.0
     */
    fun calculateGroundDownlink(satelliteFreqHz: Double, rangeRateMps: Double): Double {
        // Guard against NaN or Infinity that may originate from an invalid TLE
        // propagation result or a corrupted Doppler cache entry; propagating either
        // into the frequency command sent to the IC-705 causes undefined radio
        // behaviour (observed: radio ignores the command but firmware may freeze).
        if (satelliteFreqHz.isNaN() || satelliteFreqHz.isInfinite() ||
            rangeRateMps.isNaN() || rangeRateMps.isInfinite()) {
            LogManager.w(TAG, "【Doppler下行】输入包含 NaN/Infinity: sat=$satelliteFreqHz rr=$rangeRateMps，返回 0")
            return 0.0
        }
        return satelliteFreqHz * (SPEED_OF_LIGHT - rangeRateMps) / SPEED_OF_LIGHT
    }

    /**
     * 计算地面发射频率（上行）
     * @param satelliteFreqHz 卫星接收频率（Hz）
     * @param rangeRateMps 径向速度（m/s），正值表示远离，负值表示靠近
     * @return 地面发射频率（Hz），输入包含 NaN/Infinity 时返回 0.0
     */
    fun calculateGroundUplink(satelliteFreqHz: Double, rangeRateMps: Double): Double {
        if (satelliteFreqHz.isNaN() || satelliteFreqHz.isInfinite() ||
            rangeRateMps.isNaN() || rangeRateMps.isInfinite()) {
            LogManager.w(TAG, "【Doppler上行】输入包含 NaN/Infinity: sat=$satelliteFreqHz rr=$rangeRateMps，返回 0")
            return 0.0
        }
        return satelliteFreqHz * (SPEED_OF_LIGHT + rangeRateMps) / SPEED_OF_LIGHT
    }

    /**
     * 从地面接收频率反推卫星发射频率（下行）
     * @param groundFreqHz 地面接收频率（Hz）
     * @param rangeRateMps 径向速度（m/s）
     * @return 卧星发射频率（Hz），除数为零或输入无效时返回 0.0
     */
    fun calculateSatelliteDownlink(groundFreqHz: Double, rangeRateMps: Double): Double {
        if (groundFreqHz.isNaN() || groundFreqHz.isInfinite() ||
            rangeRateMps.isNaN() || rangeRateMps.isInfinite()) {
            LogManager.w(TAG, "【Doppler反推】输入包含 NaN/Infinity: ground=$groundFreqHz rr=$rangeRateMps，返回 0")
            return 0.0
        }
        val denominator = SPEED_OF_LIGHT - rangeRateMps
        // denominator == 0 requires rangeRateMps == speed of light, physically
        // impossible for any satellite; guard anyway to prevent Infinity from
        // leaking into frequency commands.
        if (denominator == 0.0) {
            LogManager.e(TAG, "【Doppler反推】除数为零（rangeRate=c），返回 0")
            return 0.0
        }
        return groundFreqHz * SPEED_OF_LIGHT / denominator
    }

    /**
     * 计算多普勒频移
     * @param frequencyHz 频率（Hz）
     * @param rangeRateMps 径向速度（m/s）
     * @return 多普勒频移（Hz）
     */
    fun calculateDopplerShift(frequencyHz: Double, rangeRateMps: Double): Double {
        // Δf = -f * v_r / c
        // 卫星靠近时(rangeRate<0)：多普勒为正（频率升高）
        // 卫星远离时(rangeRate>0)：多普勒为负（频率降低）
        return -frequencyHz * rangeRateMps / SPEED_OF_LIGHT
    }

    /**
     * 计算线性卫星的地面频率对
     * @param satelliteDownlinkHz 卫星下行频率（Hz）
     * @param satelliteUplinkHz 卫星上行频率（Hz）
     * @param rangeRateMps 径向速度（m/s）
     * @return Pair(地面下行频率, 地面上行频率)
     */
    fun calculateLinearSatelliteFrequencies(
        satelliteDownlinkHz: Double,
        satelliteUplinkHz: Double,
        rangeRateMps: Double
    ): Pair<Double, Double> {
        val groundDownlink = calculateGroundDownlink(satelliteDownlinkHz, rangeRateMps)
        val groundUplink = calculateGroundUplink(satelliteUplinkHz, rangeRateMps)
        return Pair(groundDownlink, groundUplink)
    }

    /**
     * 计算FM卫星的地面频率对
     * @param satelliteDownlinkHz 卫星下行频率（Hz）
     * @param satelliteUplinkHz 卫星上行频率（Hz）
     * @param rangeRateMps 径向速度（m/s）
     * @return Pair(地面下行频率, 地面上行频率)
     */
    fun calculateFMSatelliteFrequencies(
        satelliteDownlinkHz: Double,
        satelliteUplinkHz: Double,
        rangeRateMps: Double
    ): Pair<Double, Double> {
        // FM卫星使用与线性卫星相同的计算公式
        return calculateLinearSatelliteFrequencies(satelliteDownlinkHz, satelliteUplinkHz, rangeRateMps)
    }

    /**
     * 从地面频率反推线性卫星的卫星频率
     * @param groundDownlinkHz 地面下行频率（Hz）
     * @param groundUplinkHz 地面上行频率（Hz）
     * @param rangeRateMps 径向速度（m/s）
     * @param loopHz Loop值（Hz）
     * @return Triple(卫星下行频率, 卫星上行频率, 实际Loop值)
     */
    fun calculateLinearSatelliteFrequenciesFromGround(
        groundDownlinkHz: Double,
        groundUplinkHz: Double,
        rangeRateMps: Double,
        loopHz: Double
    ): Triple<Double, Double, Double> {
        // 从地面下行反推卫星下行
        val satelliteDownlink = calculateSatelliteDownlink(groundDownlinkHz, rangeRateMps)
        // 从Loop计算卫星上行
        val satelliteUplink = loopHz - satelliteDownlink
        // 从地面上行反推实际Loop值（用于验证）
        val actualLoop = satelliteDownlink + satelliteUplink
        return Triple(satelliteDownlink, satelliteUplink, actualLoop)
    }

    /**
     * 格式化多普勒频移显示
     * @param dopplerShiftHz 多普勒频移（Hz）
     * @return 格式化后的字符串
     */
    fun formatDopplerShift(dopplerShiftHz: Double): String {
        return when {
            kotlin.math.abs(dopplerShiftHz) >= 1000 -> String.format("%+.3f kHz", dopplerShiftHz / 1000)
            else -> String.format("%+.1f Hz", dopplerShiftHz)
        }
    }
}
