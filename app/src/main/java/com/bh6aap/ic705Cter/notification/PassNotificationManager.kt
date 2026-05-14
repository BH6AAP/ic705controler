package com.bh6aap.ic705Cter.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.bh6aap.ic705Cter.MainActivity
import com.bh6aap.ic705Cter.R
import com.bh6aap.ic705Cter.util.LogManager
import com.bh6aap.ic705Cter.util.SatellitePassCalculator

/**
 * 卫星过境通知管理器
 * 管理过境提醒通知的创建、调度和取消
 */
object PassNotificationManager {

    private const val CHANNEL_ID = "satellite_pass_channel"
    private const val CHANNEL_NAME = "卫星过境提醒"
    private const val CHANNEL_DESCRIPTION = "在卫星过境前5分钟发送提醒通知"

    private const val NOTIFICATION_ID_BASE = 1000

    /**
     * 创建通知渠道（Android 8.0+需要）
     * 使用 IMPORTANCE_HIGH 确保 Heads-up 通知显示
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESCRIPTION
                // 启用震动以支持 Heads-up 通知
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)

                // 设置系统默认通知声音
                val defaultSoundUri = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
                setSound(defaultSoundUri, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())

                // 允许锁屏显示
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            LogManager.i("PassNotification", "创建通知渠道成功（Heads-up 悬浮通知）")
        }
    }

    /**
     * 检查是否有精确闹钟权限
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /**
     * 打开精确闹钟权限设置页面
     */
    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            context.startActivity(intent)
        }
    }

    /**
     * schedulePassNotification 的返回结果。
     * 之前一律用 Boolean，"过境已过期" 被 UI 误解成 "调度成功（非精确）"。
     */
    sealed class ScheduleResult {
        /** 已成功用精确闹钟调度 */
        object Exact : ScheduleResult()
        /** 已成功调度，但用的是非精确闹钟（无 SCHEDULE_EXACT_ALARM 权限） */
        object Inexact : ScheduleResult()
        /** 过境时刻已经在 reminderMinutes 之内，无法提前 N 分钟提醒 */
        object PassTooSoon : ScheduleResult()
        /** AlarmManager 抛异常，e.g. SecurityException、SDK bug */
        data class Failed(val reason: String) : ScheduleResult()
    }

    /**
     * 调度过境提醒
     * @param context 上下文
     * @param pass 过境信息
     * @param satelliteName 卫星名称
     * @param reminderMinutes 提前提醒时间（分钟），默认5分钟
     */
    fun schedulePassNotification(
        context: Context,
        pass: SatellitePassCalculator.SatellitePass,
        satelliteName: String,
        reminderMinutes: Int = 5
    ): ScheduleResult {
        // Android 13+ 用户可能拒绝了 POST_NOTIFICATIONS；通知通道也可能被
        // 用户禁用。提前判断让 UI 能给出 actionable 反馈，而不是悄悄注册一
        // 个发不出来的闹钟。
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            LogManager.w("PassNotification", "通知被系统禁用，无法调度: $satelliteName")
            return ScheduleResult.Failed("通知权限被禁用，请在系统设置开启")
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val notificationTime = pass.aosTime - reminderMinutes * 60 * 1000 // 过境前 reminderMinutes 分钟

        // 如果应当通知的时刻已经过去，告诉调用方"来不及了"，让 UI 用 toast 提示
        // 而不是把 false 当作"已设置（非精确）"。
        if (notificationTime < System.currentTimeMillis()) {
            LogManager.w("PassNotification", "过境时间已过，不调度通知: $satelliteName")
            return ScheduleResult.PassTooSoon
        }

        val intent = Intent(context, PassNotificationReceiver::class.java).apply {
            putExtra(EXTRA_NORAD_ID, pass.noradId)
            putExtra(EXTRA_SATELLITE_NAME, satelliteName)
            putExtra(EXTRA_AOS_TIME, pass.aosTime)
            putExtra(EXTRA_MAX_ELEVATION, pass.maxElevation)
        }

        val requestCode = generateRequestCode(pass.noradId, pass.aosTime)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        notificationTime,
                        pendingIntent
                    )
                    LogManager.i("PassNotification", "已调度精确过境提醒: $satelliteName 在 ${formatTime(notificationTime)}")
                    ScheduleResult.Exact
                } else {
                    // 没有精确闹钟权限，使用非精确闹钟（可能延迟几分钟）
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        notificationTime,
                        pendingIntent
                    )
                    LogManager.w("PassNotification", "无精确闹钟权限，使用非精确提醒: $satelliteName")
                    ScheduleResult.Inexact
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    notificationTime,
                    pendingIntent
                )
                LogManager.i("PassNotification", "已调度过境提醒: $satelliteName 在 ${formatTime(notificationTime)}")
                ScheduleResult.Exact
            }
        } catch (e: Exception) {
            LogManager.e("PassNotification", "调度过境提醒失败: $satelliteName", e)
            ScheduleResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 取消过境提醒
     */
    fun cancelPassNotification(
        context: Context,
        noradId: String,
        aosTime: Long
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, PassNotificationReceiver::class.java)
        val requestCode = generateRequestCode(noradId, aosTime)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        LogManager.i("PassNotification", "已取消过境提醒: noradId=$noradId")
    }

    /**
     * 显示过境通知（强制悬浮横幅 Heads-up）
     * 使用 CALL 类别确保悬浮通知显示
     */
    fun showPassNotification(
        context: Context,
        noradId: String,
        satelliteName: String,
        aosTime: Long,
        maxElevation: Double
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 创建点击通知打开应用的Intent
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val aosTimeStr = sdf.format(java.util.Date(aosTime))

        // 构建大文本内容
        val bigText = """
            $satelliteName 将在5分钟后过境
            入境时间: $aosTimeStr
            最大仰角: ${String.format("%.1f°", maxElevation)}
        """.trimIndent()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("🛰️ 卫星即将过境")
            .setContentText("$satelliteName 将在5分钟后过境")
            .setPriority(NotificationCompat.PRIORITY_MAX)  // 最高优先级
            // 使用 CALL 类别强制触发 Heads-up 通知
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            // 允许锁屏显示
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 震动模式（必须设置才能触发 Heads-up）
            .setVibrate(longArrayOf(0, 500, 200, 500))
            // 声音和震动
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            // 大文本样式
            .setStyle(NotificationCompat.BigTextStyle()
                .setBigContentTitle("🛰️ $satelliteName")
                .bigText(bigText)
            )
            // 添加操作按钮
            .addAction(0, "查看", pendingIntent)
            .build()

        val notificationId = generateRequestCode(noradId, aosTime)
        notificationManager.notify(notificationId, notification)
        LogManager.i("PassNotification", "显示 Heads-up 过境通知: $satelliteName")
    }

    /**
     * 生成唯一的请求码
     */
    private fun generateRequestCode(noradId: String, aosTime: Long): Int {
        return NOTIFICATION_ID_BASE + (noradId.hashCode() + aosTime.hashCode()).and(0x7FFFFFFF)
    }

    private fun formatTime(timeMillis: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timeMillis))
    }

    // Intent extras
    const val EXTRA_NORAD_ID = "extra_norad_id"
    const val EXTRA_SATELLITE_NAME = "extra_satellite_name"
    const val EXTRA_AOS_TIME = "extra_aos_time"
    const val EXTRA_MAX_ELEVATION = "extra_max_elevation"
}

/**
 * 过境通知广播接收器
 */
class PassNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val noradId = intent.getStringExtra(PassNotificationManager.EXTRA_NORAD_ID) ?: return
        val satelliteName = intent.getStringExtra(PassNotificationManager.EXTRA_SATELLITE_NAME) ?: return
        val aosTime = intent.getLongExtra(PassNotificationManager.EXTRA_AOS_TIME, 0)
        val maxElevation = intent.getDoubleExtra(PassNotificationManager.EXTRA_MAX_ELEVATION, 0.0)

        LogManager.i("PassNotification", "收到过境提醒广播: $satelliteName")
        PassNotificationManager.showPassNotification(
            context,
            noradId,
            satelliteName,
            aosTime,
            maxElevation
        )
    }
}
