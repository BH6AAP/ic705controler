package com.bh6aap.ic705Cter.sensor

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import com.bh6aap.ic705Cter.util.LogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.round

/**
 * 手机姿态传感器管理类（参考Look4Sat实现优化）
 * 用于获取方位角（Azimuth）和俯仰角（Pitch）
 * 支持地磁偏角修正，输出真北方位角
 */
class PhoneSensorManager(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "PhoneSensorManager"
        private const val SENSOR_DELAY_MICROS = 16000 // 约60Hz，平衡精度和功耗
        private const val RAD2DEG = 57.29577951308232f // 180/π
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // 姿态数据流
    private val _orientation = MutableStateFlow(OrientationData())
    val orientation: StateFlow<OrientationData> = _orientation

    // 旋转矩阵和方向值
    private val rotationMatrix = FloatArray(9)
    private val remappedRotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)

    // 传感器精度
    private var sensorAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

    // 地磁偏角（根据GPS位置计算）
    private var magneticDeclination = 0f

    data class OrientationData(
        val azimuth: Float = 0f,        // 方位角 0-360°，真北=0°（已修正地磁偏角）
        val pitch: Float = 0f,          // 俯仰角 -90°~90°，水平=0°
        val magneticAzimuth: Float = 0f, // 磁北方位角（原始值）
        val isValid: Boolean = false,   // 数据是否有效
        val magneticDeclination: Float = 0f // 当前地磁偏角
    )

    /**
     * 开始监听传感器
     */
    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SENSOR_DELAY_MICROS)
            LogManager.i(TAG, "传感器监听已启动")
        } ?: run {
            LogManager.w(TAG, "设备不支持旋转矢量传感器")
        }
    }

    /**
     * 停止监听传感器
     */
    fun stop() {
        sensorManager.unregisterListener(this)
        LogManager.i(TAG, "传感器监听已停止")
    }

    /**
     * 更新地磁偏角（根据GPS位置）
     * 应在获取GPS位置后调用
     */
    fun updateMagneticDeclination(location: Location) {
        try {
            val geomagneticField = GeomagneticField(
                location.latitude.toFloat(),
                location.longitude.toFloat(),
                location.altitude.toFloat(),
                System.currentTimeMillis()
            )
            magneticDeclination = geomagneticField.declination
            LogManager.d(TAG, "地磁偏角更新: ${magneticDeclination}°")
        } catch (e: Exception) {
            LogManager.w(TAG, "计算地磁偏角失败", e)
        }
    }

    /**
     * 手动设置地磁偏角（用于校准）
     */
    fun setMagneticDeclination(declination: Float) {
        magneticDeclination = declination
        LogManager.i(TAG, "手动设置地磁偏角: ${declination}°")
    }

    /**
     * 获取当前地磁偏角
     */
    fun getMagneticDeclination(): Float = magneticDeclination

    override fun onSensorChanged(event: SensorEvent) {
        // 检查传感器精度，不可靠时不更新
        if (sensorAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            return
        }

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            // 系统会复用 event.values，必须 clone 后再使用
            updateOrientation(event.values.clone())
        }
    }

    private fun updateOrientation(rotationVector: FloatArray) {
        // 从旋转矢量计算旋转矩阵
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)

        // 卫星跟踪持机姿态是手机竖立指天，需 remap 以避免 gimbal lock，
        // 详见 SensorFusionManager.updateOrientation 的相同修复。
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            SensorManager.AXIS_X,
            SensorManager.AXIS_Z,
            remappedRotationMatrix
        )

        // 从重映射后的旋转矩阵计算方向角
        SensorManager.getOrientation(remappedRotationMatrix, orientationValues)

        // 转换为角度
        val azimuth = orientationValues[0] * RAD2DEG
        val pitch = orientationValues[1] * RAD2DEG

        // 磁北方位角（0-360°）
        val magneticAzimuth = (azimuth + 360f) % 360f

        // 应用地磁偏角修正，得到真北方位角
        val trueAzimuth = (magneticAzimuth + magneticDeclination + 360f) % 360f

        // 四舍五入到小数点后1位，减少抖动
        val roundedTrueAzimuth = round(trueAzimuth * 10) / 10
        val roundedPitch = round(pitch * 10) / 10
        val roundedMagneticAzimuth = round(magneticAzimuth * 10) / 10

        _orientation.value = OrientationData(
            azimuth = roundedTrueAzimuth,
            pitch = roundedPitch,
            magneticAzimuth = roundedMagneticAzimuth,
            isValid = true,
            magneticDeclination = magneticDeclination
        )
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        sensorAccuracy = accuracy
        LogManager.d(TAG, "传感器精度变化: $accuracy")
    }
}
