package com.demo.livecapsule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模拟后台任务：发布一条 0→100 进度的常驻通知。
 * 在 Android 16+（API 36）上，通过 Notification.ProgressStyle +
 * android.requestPromotedOngoing 请求系统将其"提升"为实时通知（Live Updates），
 * 支持的 ROM（如荣耀 MagicOS）会把它渲染成灵动胶囊。
 */
object TaskNotifier {

    const val CHANNEL_ID = "live_task"
    const val NOTIFICATION_ID = 1001
    const val ACTION_STOP = "com.demo.livecapsule.action.STOP_TASK"

    // 与 Kelivo 一致：直接写 extra 等价于 API 36 的
    // Notification.EXTRA_REQUEST_PROMOTED_ONGOING / setRequestPromotedOngoing(true)
    const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    interface Listener {
        fun onStateChanged()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()
    private val logLines = mutableListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var appContext: Context? = null
    private var finishRunnable: Runnable? = null   // [FIX] 完成后的延迟收起任务

    var running = false
        private set
    var progress = 0
        private set

    /** 最近一次发布后系统是否真的提升（FLAG_PROMOTED_ONGOING）；null 表示尚未检测 */
    var promoted: Boolean? = null
        private set

    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    fun logText(): String = logLines.joinToString("\n")

    /** 本机是否具备发布实时通知的资格（API 36 + 用户在系统设置中允许） */
    fun canPromote(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 36 &&
            context.getSystemService(NotificationManager::class.java)
                ?.canPostPromotedNotifications() == true

    fun start(context: Context) {
        if (running) return
        // [FIX] 上一轮"3 秒后收起通知"的延迟任务不能误清新任务的通知
        finishRunnable?.let { handler.removeCallbacks(it) }
        finishRunnable = null
        val app = context.applicationContext
        appContext = app
        running = true
        progress = 0
        promoted = null
        ensureChannel(app)
        post(app)
        log("任务开始，发布常驻通知" + if (Build.VERSION.SDK_INT >= 36) "（请求提升为实时通知）" else "（本机 API<36，仅普通通知）")
        scheduleTick()
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        running = false
        finishRunnable?.let { handler.removeCallbacks(it) }
        finishRunnable = null
        handler.removeCallbacksAndMessages(null)
        app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        log("任务已取消，通知收起")
        notifyListeners()
    }

    private fun scheduleTick() {
        handler.postDelayed({
            if (!running) return@postDelayed
            val app = appContext ?: return@postDelayed
            progress = (progress + 2).coerceAtMost(100)
            post(app)
            if (progress >= 100) {
                log("任务完成（100%），3 秒后收起通知")
                finish(app)
            } else {
                scheduleTick()
            }
        }, 1000L)
    }

    private fun finish(context: Context) {
        running = false
        notifyListeners()
        val r = Runnable {
            finishRunnable = null
            // [FIX] 期间若用户又开了新任务，不再把新通知收起
            if (running) return@Runnable
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            log("通知已收起")
            notifyListeners()
        }
        finishRunnable = r
        handler.postDelayed(r, 3000L)
    }

    private fun post(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(NOTIFICATION_ID, build(context))
        } catch (e: SecurityException) {
            running = false
            log("发布失败：缺少通知权限")
            notifyListeners()
            return
        }
        // [FIX] 进度刷新不能只靠 promotionCheck 的副作用：
        // API < 36 根本不安排该检查，界面上的进度会一直停住
        notifyListeners()
        if (Build.VERSION.SDK_INT >= 36) {
            // 系统在 notify 之后才设置 FLAG_PROMOTED_ONGOING，稍等再查（Kelivo 同款做法）
            handler.removeCallbacks(promotionCheck)
            handler.postDelayed(promotionCheck, 600L)
        }
    }

    private val promotionCheck = Runnable {
        val app = appContext ?: return@Runnable
        val nm = app.getSystemService(NotificationManager::class.java) ?: return@Runnable
        val now = nm.activeNotifications.any {
            it.id == NOTIFICATION_ID &&
                it.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0
        }
        if (now != promoted) {
            promoted = now
            log(
                if (now) "✔ 系统已提升为实时通知（灵动胶囊）"
                else "✘ 未被提升：仅普通通知（可到系统设置开启实时通知）"
            )
        }
        // 状态未变时不再触发一次多余的全量 UI 刷新（log 内部已通知）
    }

    fun build(context: Context): Notification {
        val openIntent = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, StopReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "模拟下载任务"
        val text = if (running || progress == 0) "正在下载… $progress%" else "下载完成"

        val builder =
            if (Build.VERSION.SDK_INT >= 36 && canPromote(context)) {
                val style = Notification.ProgressStyle()
                    .addProgressSegment(Notification.ProgressStyle.Segment(100))
                    .setStyledByProgress(true)
                    .setProgress(progress)
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_task)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(style)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(openIntent)
                    .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            } else {
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_task)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setProgress(100, progress, false)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(openIntent)
            }

        return builder
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stop),
                    "停止", stopIntent
                ).build()
            )
            .build()
    }

    private fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台任务", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    private fun log(message: String) {
        logLines += "${timeFormat.format(Date())}  $message"
        if (logLines.size > 30) logLines.removeAt(0)
        notifyListeners()
    }

    private fun notifyListeners() {
        handler.post {
            listeners.toList().forEach { it.onStateChanged() }
        }
    }
}
