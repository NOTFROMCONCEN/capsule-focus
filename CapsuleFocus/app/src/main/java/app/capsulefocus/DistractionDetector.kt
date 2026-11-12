package app.capsulefocus

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 摸鱼检测：专注期间轮询 UsageEvents，统计切到其它应用的次数
 * （系统 UI 除外，避免拉通知栏/调音量误计）。
 * 需要"使用情况访问"特殊权限，由调用方判断；只在专注阶段计数。
 *
 * [PERF] queryEvents 是跨进程调用，原先每 2 秒跑在主线程；现改到独立
 * HandlerThread 串行执行，主线程只负责调度与读取结果。
 * [FIX] 查询窗口无论成败都推进，异常时不再重复累计已处理过的事件。
 */
object DistractionDetector {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workThread = HandlerThread("DistractionDetector").apply { start() }
    private val workHandler = Handler(workThread.looper)

    private var appContext: Context? = null

    @Volatile private var counting = false
    @Volatile private var lastQueryTime = 0L
    @Volatile private var generation = 0L
    @Volatile private var countValue = 0

    val count: Int get() = countValue

    fun hasPermission(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = try {
            if (android.os.Build.VERSION.SDK_INT >= 29)
                ops.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            else
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } catch (e: Exception) { return false }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 会话开始：计数清零并开始轮询 */
    fun begin(context: Context) {
        appContext = context.applicationContext
        generation++          // 使上一个会话在途的查询失效
        countValue = 0
        counting = true
        lastQueryTime = System.currentTimeMillis()
        mainHandler.removeCallbacks(poll)
        mainHandler.postDelayed(poll, 2000L)
    }

    /** 恢复计数（休息结束回到专注）：丢弃休息期间的事件 */
    fun resume() {
        if (appContext == null) return
        counting = true
        lastQueryTime = System.currentTimeMillis()
        mainHandler.removeCallbacks(poll)
        mainHandler.postDelayed(poll, 2000L)
    }

    /** 暂停计数（进入休息/会话暂停）：把专注最后一段事件结算掉 */
    fun pause() {
        counting = false
        mainHandler.removeCallbacks(poll)
        drain()
    }

    /** 会话结束：等待在途查询落定后归零 */
    fun stop() {
        counting = false
        mainHandler.removeCallbacks(poll)
        flush()
        appContext = null
        generation++
        countValue = 0
    }

    /** 会话落历史前调用：保证后台已提交的查询全部结算，主线程最多等 300ms */
    fun flush() {
        val latch = CountDownLatch(1)
        if (!workHandler.post { latch.countDown() }) return
        try {
            latch.await(300, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!counting) return
            drain()
            mainHandler.postDelayed(this, 2000L)
        }
    }

    /**
     * 提交一次查询。窗口 [from, to] 在**提交时**捕获，避免 pause→resume 竞态：
     * resume 重置窗口不会影响 pause 已提交的最后一段结算。
     */
    private fun drain() {
        val app = appContext ?: return
        val gen = generation
        val from = lastQueryTime
        val to = System.currentTimeMillis()
        if (to <= from) return   // 系统时间回拨：不倒查，避免重复计数
        workHandler.post {
            var delta = 0
            try {
                val usm = app.getSystemService(UsageStatsManager::class.java) ?: return@post
                val events = usm.queryEvents(from, to)
                val ev = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(ev)
                    if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                        ev.packageName != app.packageName &&
                        ev.packageName != "com.android.systemui"
                    ) {
                        delta++
                    }
                }
            } catch (e: Exception) {
                // 无权限或系统拒绝：丢弃本窗口
            } finally {
                // [FIX] 成败都推进窗口，避免异常时同一区间被重复计数
                if (lastQueryTime < to) lastQueryTime = to
            }
            if (gen == generation && delta > 0) countValue += delta
        }
    }
}
