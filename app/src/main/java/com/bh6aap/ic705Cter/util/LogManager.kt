package com.bh6aap.ic705Cter.util

import android.util.Log
import com.bh6aap.ic705Cter.BuildConfig

/**
 * 日志管理类
 * 提供统一的中文日志输出，方便调试和问题定位
 */
object LogManager {

    private const val DEFAULT_TAG = "IC705"

    // In release builds (BuildConfig.DEBUG == false) verbose debug output is
    // suppressed to avoid leaking radio frequencies, GPS coordinates and raw
    // CI-V byte streams to logcat, which is readable by apps holding the
    // READ_LOGS permission or via ADB on unrooted devices.  Info/warning/error
    // levels remain active because they are needed for diagnosing field issues.
    var isDebugEnabled = BuildConfig.DEBUG
    var isInfoEnabled = true
    var isWarningEnabled = true
    var isErrorEnabled = true

    /**
     * 调试日志
     */
    fun d(tag: String, message: String) {
        if (isDebugEnabled) {
            Log.d("$DEFAULT_TAG-$tag", message)
        }
    }

    /**
     * 信息日志
     */
    fun i(tag: String, message: String) {
        if (isInfoEnabled) {
            Log.i("$DEFAULT_TAG-$tag", message)
        }
    }

    /**
     * 警告日志
     */
    fun w(tag: String, message: String) {
        if (isWarningEnabled) {
            Log.w("$DEFAULT_TAG-$tag", message)
        }
    }

    /**
     * 警告日志（带异常）
     */
    fun w(tag: String, message: String, throwable: Throwable?) {
        if (isWarningEnabled) {
            Log.w("$DEFAULT_TAG-$tag", message, throwable)
        }
    }

    /**
     * 错误日志
     */
    fun e(tag: String, message: String) {
        if (isErrorEnabled) {
            Log.e("$DEFAULT_TAG-$tag", message)
        }
    }

    /**
     * 错误日志（带异常）
     */
    fun e(tag: String, message: String, throwable: Throwable?) {
        if (isErrorEnabled) {
            Log.e("$DEFAULT_TAG-$tag", message, throwable)
        }
    }

    // ========== 预定义的日志标签 ==========
    const val TAG_SPLASH = "启动页"
    const val TAG_PERMISSION = "权限"
    const val TAG_GPS = "GPS定位"
    const val TAG_NTP = "时间同步"
    const val TAG_TLE = "卫星数据"
    const val TAG_SATELLITE = "卫星信息"
    const val TAG_DATABASE = "数据库"
    const val TAG_OREKIT = "Orekit"
    const val TAG_BLUETOOTH = "蓝牙"
    const val TAG_CIV = "CIV协议"

    // ========== 常用日志模板方法 ==========

    /**
     * 记录方法进入
     */
    fun enterMethod(tag: String, methodName: String) {
        d(tag, "【进入方法】$methodName")
    }

    /**
     * 记录方法退出
     */
    fun exitMethod(tag: String, methodName: String) {
        d(tag, "【退出方法】$methodName")
    }

    /**
     * 记录步骤开始
     */
    fun stepStart(tag: String, stepName: String) {
        i(tag, "【步骤开始】$stepName")
    }

    /**
     * 记录步骤完成
     */
    fun stepComplete(tag: String, stepName: String) {
        i(tag, "【步骤完成】$stepName ✓")
    }

    /**
     * 记录步骤失败
     */
    fun stepFailed(tag: String, stepName: String, reason: String) {
        e(tag, "【步骤失败】$stepName ✗ 原因: $reason")
    }

    /**
     * 记录数据加载
     */
    fun dataLoaded(tag: String, dataType: String, count: Int) {
        i(tag, "【数据加载】$dataType: $count 条记录")
    }

    /**
     * 记录网络请求
     */
    fun networkRequest(tag: String, url: String) {
        i(tag, "【网络请求】URL: $url")
    }

    /**
     * 记录网络响应
     */
    fun networkResponse(tag: String, url: String, success: Boolean) {
        if (success) {
            i(tag, "【网络响应】URL: $url 成功")
        } else {
            e(tag, "【网络响应】URL: $url 失败")
        }
    }

    /**
     * 记录数据库操作
     */
    fun databaseOperation(tag: String, operation: String, table: String) {
        d(tag, "【数据库】$operation 表: $table")
    }

    /**
     * 记录权限状态
     */
    fun permissionStatus(tag: String, permission: String, granted: Boolean) {
        if (granted) {
            i(tag, "【权限】$permission 已授予 ✓")
        } else {
            w(tag, "【权限】$permission 未授予 ✗")
        }
    }

    /**
     * 记录位置信息
     */
    fun locationInfo(tag: String, latitude: Double, longitude: Double, accuracy: Float) {
        if (isDebugEnabled) {
            // Full coordinates only in debug builds to protect user home location
            i(tag, "【位置信息】纬度: $latitude, 经度: $longitude, 精度: ${accuracy}米")
        } else {
            i(tag, "【位置信息】已获取, 精度: ${accuracy}米")
        }
    }

    /**
     * 记录时间同步信息
     */
    fun timeSyncInfo(tag: String, ntpTime: Long, offset: Long) {
        i(tag, "【时间同步】NTP时间: $ntpTime, 偏移: ${offset}ms")
    }

    /**
     * 记录蓝牙连接状态
     */
    fun bluetoothStatus(tag: String, status: String) {
        i(tag, "【蓝牙状态】$status")
    }

    /**
     * 记录CIV命令
     */
    fun civCommand(tag: String, command: String, data: String = "") {
        if (data.isEmpty()) {
            d(tag, "【CIV命令】$command")
        } else {
            d(tag, "【CIV命令】$command 数据: $data")
        }
    }
}
