package app.capsulefocus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * 会话引擎：驱动番茄钟 / 呼吸训练的状态机。
 * 每秒 tick；阶段/进度实时反映到一条"可提升常驻通知"上——
 * Android 16+ 上带 requestPromotedOngoing，被 ROM（荣耀 MagicOS）渲染为灵动胶囊：
 * - 专注/休息：胶囊原生倒计时（chronometer countdown）+ 单色进度段
 * - 呼吸：多段彩色进度条表达 吸气-屏息-呼气-屏息 节奏
 */
object SessionEngine {

    const val ONGOING_ID = 1001
    const val CHANNEL_ONGOING = "session_ongoing"
    const val CHANNEL_EVENTS = "session_events"
    const val EXTRA_PROMOTED = "android.requestPromotedOngoing"

    const val ACTION_PAUSE = "com.demo.capsulefocus.action.PAUSE"
    const val ACTION_RESUME = "com.demo.capsulefocus.action.RESUME"
    const val ACTION_SKIP = "com.demo.capsulefocus.action.SKIP"
    const val ACTION_STOP = "com.demo.capsulefocus.action.STOP"

    /** FGS 的 dataSync 上限是 6 小时，唤醒锁同步给一个带超时的兜底 */
    private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L + 10 * 60 * 1000L
    private const val PREFS_CFG = "cfg"

    enum class Kind { FOCUS, REST, INHALE, HOLD_IN, EXHALE, HOLD_OUT }

    data class Phase(val kind: Kind, val seconds: Int) {
        val label: String = when (kind) {
            Kind.FOCUS -> "专注"
            Kind.REST -> "休息"
            Kind.INHALE -> "吸气"
            Kind.HOLD_IN -> "屏息"
            Kind.EXHALE -> "呼气"
            Kind.HOLD_OUT -> "屏息"
        }
        val color: Int = when (kind) {
            Kind.FOCUS -> 0xFF5C6BC0.toInt()   // 靛蓝
            Kind.REST -> 0xFFFFB74D.toInt()    // 橙
            Kind.INHALE -> 0xFF4FC3F7.toInt()  // 蓝
            Kind.HOLD_IN -> 0xFF26A69A.toInt() // 青
            Kind.EXHALE -> 0xFF81C784.toInt()  // 绿
            Kind.HOLD_OUT -> 0xFFFFD54F.toInt()// 黄
        }
        val isBreath: Boolean get() = kind != Kind.FOCUS && kind != Kind.REST
    }

    data class Pattern(val name: String, val phases: List<Phase>) {
        // [FIX] 下限 1：自定义节奏/异常数据下避免 roundsTotal 与进度计算除零
        val total: Int get() = phases.sumOf { it.seconds }.coerceAtLeast(1)
        companion object {
            val RELAX_478 = Pattern("4-7-8", listOf(
                Phase(Kind.INHALE, 4), Phase(Kind.HOLD_IN, 7), Phase(Kind.EXHALE, 8)))
            val BOX_4444 = Pattern("4-4-4-4", listOf(
                Phase(Kind.INHALE, 4), Phase(Kind.HOLD_IN, 4),
                Phase(Kind.EXHALE, 4), Phase(Kind.HOLD_OUT, 4)))
        }
    }

    sealed interface Session {
        data class Pomodoro(
            val focusMinutes: Int,
            val restBreathing: Boolean,
            val restPattern: Pattern,
            val restMinutes: Int = 5,        // 普通休息分钟数
            val longRestEvery: Int = 0,      // >0 时每 N 个番茄进长休息
            val longRestMinutes: Int = 15,
            val taskName: String? = null,    // 专注任务名（来自待办）
        ) : Session

        data class Breathing(val pattern: Pattern, val rounds: Int) : Session
    }

    interface Listener {
        fun onChanged()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()
    private val logLines = mutableListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dayKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

    private var appContext: Context? = null
    private var service: FocusService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var chirpSeq = 0

    @Volatile private var channelsReady = false
    private var notifyPending = false
    private var cachedLog = ""

    var session: Session? = null
        private set
    var phase: Phase? = null
        private set
    var phaseRemaining = 0
        private set
    var paused = false
        private set
    var pomCount = 0          // 已完成的专注数
        private set
    var breathRound = 0       // 当前呼吸轮（1-based）
        private set
    var roundsTotal = 0
        private set
    var promoted: Boolean? = null   // 系统是否真的提升（FLAG_PROMOTED_ONGOING）
        private set

    /** [FIX] 今日番茄入账水位放在引擎里，Activity 重建也不会重复累计 */
    var accountedPoms = 0
        private set

    private var pattern: Pattern? = null
    private var phaseIndex = 0
    var inBreath = false
        private set
    var inLongRest = false
        private set
    private var startedAt = 0L    // 本次会话开始时间（历史记录用）
    private var activeSec = 0     // 实际进行秒数（暂停不计时）

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    /** [PERF] 日志文本只在写入时拼一次，避免每秒 refresh 反复 joinToString */
    fun logText(): String = cachedLog

    fun creditPomodoros(to: Int) { accountedPoms = to }
    fun todayPomKey(): String = "pom_" + dayKeyFormat.format(Date())

    fun canPromote(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 36 &&
            context.getSystemService(NotificationManager::class.java)
                ?.canPostPromotedNotifications() == true

    // ---------- 配置：MainActivity 与 QS 磁贴共用同一份构造逻辑 ----------

    private fun cfg(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_CFG, Context.MODE_PRIVATE)

    /** [FIX] 索引做钳制，旧版本/异常 prefs 不会数组越界 */
    fun focusMinutesFromPrefs(prefs: SharedPreferences): Int {
        val index = prefs.getInt("focusIndex", 1).coerceIn(0, 3)
        return if (index == 3) prefs.getInt("focusCustom", 45).coerceIn(5, 180)
        else intArrayOf(15, 25, 45)[index]
    }

    fun patternFromPrefs(prefs: SharedPreferences): Pattern =
        when (prefs.getInt("patternIndex", 0).coerceIn(0, 2)) {
            1 -> Pattern.BOX_4444
            2 -> customPattern(prefs)
            else -> Pattern.RELAX_478
        }

    /** 当前呼吸节奏：预设或自定义（吸/屏/呼/屏后，屏息可为 0） */
    private fun customPattern(prefs: SharedPreferences): Pattern {
        val p = prefs.getString("patternCustom", "4,0,4,0").orEmpty()
            .split(",").map { it.trim().toIntOrNull() ?: 0 }
        fun at(i: Int) = p.getOrElse(i) { 0 }.coerceIn(0, 60)
        val phases = mutableListOf(
            Phase(Kind.INHALE, at(0).coerceAtLeast(1)),
            Phase(Kind.EXHALE, at(2).coerceAtLeast(1)))
        if (at(1) > 0) phases.add(1, Phase(Kind.HOLD_IN, at(1)))
        if (at(3) > 0) phases.add(Phase(Kind.HOLD_OUT, at(3)))
        return Pattern("自定义", phases)
    }

    fun pomodoroFromPrefs(context: Context, taskName: String? = null): Session.Pomodoro {
        val prefs = cfg(context)
        return Session.Pomodoro(
            focusMinutes = focusMinutesFromPrefs(prefs),
            restBreathing = prefs.getInt("restIndex", 1) == 1,
            restPattern = patternFromPrefs(prefs),
            restMinutes = prefs.getInt("restCustomMin", 5).coerceIn(1, 60),
            longRestEvery = if (prefs.getBoolean("longRestOn", false))
                prefs.getInt("longRestEveryN", 4).coerceIn(2, 10) else 0,
            longRestMinutes = prefs.getInt("longRestMin", 15).coerceIn(5, 60),
            taskName = taskName)
    }

    fun breathingFromPrefs(context: Context): Session.Breathing {
        val prefs = cfg(context)
        val rounds = intArrayOf(4, 8, 12)[prefs.getInt("roundIndex", 1).coerceIn(0, 2)]
        return Session.Breathing(patternFromPrefs(prefs), rounds)
    }

    // ---------- 生命周期 ----------

    fun start(context: Context, newSession: Session) {
        if (session != null) {
            log("已有会话进行中，先结束再开始")
            notifyListeners()
            return
        }
        val app = context.applicationContext
        appContext = app
        session = newSession
        paused = false
        pomCount = 0
        accountedPoms = 0
        breathRound = 0
        promoted = null
        startedAt = System.currentTimeMillis()
        activeSec = 0
        ensureChannels(app, force = true)
        acquireWakeLock(app)

        when (newSession) {
            is Session.Pomodoro -> {
                inBreath = false
                inLongRest = false
                phaseIndex = 0
                phase = Phase(Kind.FOCUS, newSession.focusMinutes * 60)
                log("番茄钟开始：专注 ${newSession.focusMinutes} 分钟" +
                    (if (newSession.restBreathing) "，休息方式=呼吸(${newSession.restPattern.name})" else "，休息方式=普通 ${newSession.restMinutes} 分钟") +
                    (if (newSession.longRestEvery > 0) "，每 ${newSession.longRestEvery} 个番茄长休息 ${newSession.longRestMinutes} 分钟" else "") +
                    (newSession.taskName?.let { "，任务：$it" } ?: ""))
            }
            is Session.Breathing -> {
                // [FIX] 空节奏兜底，避免 phases[0] 越界
                pattern = if (newSession.pattern.phases.isEmpty()) Pattern.RELAX_478
                else newSession.pattern
                roundsTotal = newSession.rounds.coerceAtLeast(1)
                breathRound = 1
                phaseIndex = 0
                inBreath = true
                phase = pattern!!.phases[0]
                log("呼吸训练开始：${newSession.pattern.name} × ${roundsTotal} 轮")
            }
        }
        phaseRemaining = phase!!.seconds

        // 番茄钟专属增强：摸鱼检测 + 白噪音（呼吸需要安静，不启用）
        if (newSession is Session.Pomodoro) {
            val cfgPrefs = cfg(app)
            if (cfgPrefs.getBoolean("slouchOn", false) && DistractionDetector.hasPermission(app)) {
                DistractionDetector.begin(app)
                log("摸鱼检测已开启（本会话统计切走次数）")
            }
            if (cfgPrefs.getBoolean("noiseOn", false)) {
                NoiseEngine.start(cfgPrefs.getInt("noiseType", NoiseEngine.TYPE_BROWN),
                    cfgPrefs.getInt("noiseVol", 40))
            }
        }

        // 前台服务保活；通知由服务的 startForeground 挂载，同一条即胶囊通知
        try {
            app.startForegroundService(Intent(app, FocusService::class.java))
        } catch (e: Exception) {
            log("前台服务启动失败：${e.message}")
        }
        scheduleTick()
        notifyListeners()
    }

    fun serviceStarted(value: FocusService) {
        service = value
        // 系统在 startForeground 之后才设置 FLAG_PROMOTED_ONGOING，稍后检测
        schedulePromotionCheck(600L)
    }

    fun serviceStopped(value: FocusService) {
        if (service === value) service = null
    }

    fun togglePause(context: Context) {
        if (session == null) return
        paused = !paused
        log(if (paused) "已暂停" else "继续")
        if (paused) {
            // [FIX] 暂停即取消已排队的 tick，杜绝"暂停→继续"产生两条计时链
            handler.removeCallbacks(tickRunnable)
            NoiseEngine.pause()
            DistractionDetector.pause()
            releaseWakeLock()   // [PERF] 暂停期间不占 CPU 唤醒锁
        } else {
            acquireWakeLock(context.applicationContext)
            if (phase?.kind == Kind.FOCUS) {
                NoiseEngine.resume()
                DistractionDetector.resume()
            }
        }
        post(context.applicationContext)
        if (!paused) scheduleTick()
        notifyListeners()
    }

    fun skip(context: Context) {
        if (session == null) return
        log("手动跳过当前阶段")
        advance()
        post(context.applicationContext)
        notifyListeners()
    }

    fun stop(context: Context) {
        if (session == null) return
        when (session) {
            is Session.Pomodoro -> log("会话已手动结束 · 本次完成 $pomCount 个番茄")
            is Session.Breathing -> log("会话已手动结束 · 进行到第 $breathRound/$roundsTotal 轮")
            null -> {}
        }
        recordHistory()
        cleanup()
    }

    /** 会话结束（手动/自然完成/FGS 超时兜底）时写一条历史记录 */
    private fun recordHistory() {
        val s = session ?: return
        val app = appContext ?: return
        DistractionDetector.flush()   // [FIX] 先等后台查询落定，再读摸鱼次数
        val rec = when (s) {
            is Session.Pomodoro -> HistoryStore.Record(
                startAt = startedAt,
                activeSec = activeSec,
                kind = HistoryStore.KIND_POMODORO,
                focusMinutes = s.focusMinutes,
                poms = pomCount,
                restMode = if (s.restBreathing) "呼吸 ${s.restPattern.name}" else "普通 ${s.restMinutes} 分钟",
                pattern = null, roundsDone = 0, roundsTotal = 0, completed = false,
                distractions = DistractionDetector.count,
                taskName = s.taskName)
            is Session.Breathing -> HistoryStore.Record(
                startAt = startedAt,
                activeSec = activeSec,
                kind = HistoryStore.KIND_BREATHING,
                focusMinutes = 0, poms = 0, restMode = null,
                pattern = s.pattern.name,
                roundsDone = if (breathRound > roundsTotal) roundsTotal else breathRound - 1,
                roundsTotal = roundsTotal,
                completed = breathRound > roundsTotal,
                distractions = 0, taskName = null)
        }
        // 进行不足 1 分钟且无任何成果的（误触秒停）不入库
        if (rec.poms == 0 && rec.roundsDone == 0 && rec.activeSec < 60) return
        HistoryStore.add(app, rec)
    }

    // ---------- 每秒 tick ----------

    /** [FIX] 唯一的 tick 实例；调度前先取消，任何路径都不会出现并行的计时链 */
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (session == null || paused) return
            val app = appContext ?: return
            activeSec++
            phaseRemaining--
            if (phaseRemaining <= 0) {
                advance()
                if (session == null) return  // 已完成
            }
            post(app)
            scheduleTick()
        }
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, 1000L)
    }

    private fun advance() {
        val s = session ?: return
        when (s) {
            is Session.Pomodoro -> {
                if (inBreath) {
                    phaseIndex++
                    val p = pattern!!
                    if (phaseIndex < p.phases.size) {
                        phase = p.phases[phaseIndex]
                    } else {
                        inBreath = false
                        inLongRest = false
                        chirp(appContext ?: return, "☕ 休息结束，开始第 ${pomCount + 1} 个番茄")
                        phase = Phase(Kind.FOCUS, s.focusMinutes * 60)
                        NoiseEngine.resume()
                        DistractionDetector.resume()
                    }
                } else if (phase!!.kind == Kind.FOCUS) {
                    pomCount++
                    chirp(appContext ?: return, "🍅 第 $pomCount 个番茄完成！")
                    NoiseEngine.pause()
                    DistractionDetector.pause()
                    val toLongRest = s.longRestEvery > 0 && pomCount % s.longRestEvery == 0
                    inLongRest = toLongRest
                    if (toLongRest) {
                        log("进入长休息 ${s.longRestMinutes} 分钟")
                        phase = Phase(Kind.REST, s.longRestMinutes * 60)
                    } else if (s.restBreathing) {
                        // [FIX] 空节奏兜底 + 轮数上限，避免除零/Integer 溢出
                        val rest = if (s.restPattern.phases.isEmpty()) Pattern.RELAX_478
                        else s.restPattern
                        pattern = rest
                        roundsTotal = ceil(s.restMinutes * 60.0 / rest.total).toInt()
                            .coerceIn(1, 3600)
                        breathRound = 1
                        phaseIndex = 0
                        inBreath = true
                        phase = rest.phases[0]
                    } else {
                        phase = Phase(Kind.REST, s.restMinutes * 60)
                    }
                } else {
                    inLongRest = false
                    chirp(appContext ?: return, "☕ 休息结束，开始第 ${pomCount + 1} 个番茄")
                    phase = Phase(Kind.FOCUS, s.focusMinutes * 60)
                    NoiseEngine.resume()
                    DistractionDetector.resume()
                }
            }
            is Session.Breathing -> {
                val p = pattern!!
                phaseIndex++
                if (phaseIndex >= p.phases.size) {
                    breathRound++
                    if (breathRound > roundsTotal) {
                        chirp(appContext ?: return, "🌿 呼吸完成 · 共 $roundsTotal 轮")
                        log("呼吸训练完成")
                        recordHistory()
                        cleanup()
                        return
                    }
                    phaseIndex = 0
                }
                phase = p.phases[phaseIndex]
            }
        }
        phaseRemaining = phase!!.seconds
        // 呼吸引导：阶段切换轻振动一次，闭眼也能跟上节奏
        if (phase!!.isBreath) maybeVibrateBreath()
    }

    private fun maybeVibrateBreath() {
        val app = appContext ?: return
        if (!app.getSharedPreferences(PREFS_CFG, Context.MODE_PRIVATE)
                .getBoolean("breathVib", true)) return
        try {
            val vib = if (Build.VERSION.SDK_INT >= 31)
                app.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            else
                app.getSystemService(android.os.Vibrator::class.java)
            vib?.vibrate(android.os.VibrationEffect.createOneShot(
                60, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { /* 无振动器则忽略 */ }
    }

    // ---------- 清理 ----------

    private fun cleanup() {
        // [FIX] 只取消自己排的任务，不再 removeCallbacksAndMessages(null)，
        // 避免把已排队的 listener 通知一并清掉导致 notifyPending 卡死
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(promotionCheck)
        NoiseEngine.stop()
        DistractionDetector.stop()
        releaseWakeLock()
        appContext?.getSystemService(NotificationManager::class.java)?.cancel(ONGOING_ID)
        service?.shutdown()
        service = null
        session = null
        phase = null
        paused = false
        promoted = null
        notifyListeners()
    }

    private fun acquireWakeLock(context: Context) {
        if (wakeLock?.isHeld == true) return
        wakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "CapsuleFocus:session"
        ).apply {
            setReferenceCounted(false)
            // [FIX] 带超时，异常路径下不会永久持锁
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: RuntimeException) {}
        wakeLock = null
    }

    // ---------- 通知 ----------

    private fun post(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // [FIX] 会话已结束（如呼吸最后一轮被 skip）时只收起通知，
        // 不再发布那条无法清除的"会话已结束"常驻通知
        if (session == null || phase == null) {
            nm.cancel(ONGOING_ID)
            return
        }
        try {
            nm.notify(ONGOING_ID, buildNotification(context))
        } catch (e: SecurityException) {
            log("发布失败：缺少通知权限")
            return
        }
        // [FIX] 每秒刷新 UI 必须显式做：原实现把它挂在 promotionCheck 的副作用上，
        // API < 36 根本不安排该检查，倒计时会停住。检测链路则改为低频自查。
        notifyListeners()
        // [PERF] 首帧启动检测链，之后由检测自身低频续排，不再每秒重排
        if (promoted == null) schedulePromotionCheck(600L)
    }

    private fun schedulePromotionCheck(delay: Long) {
        if (Build.VERSION.SDK_INT < 36) return
        handler.removeCallbacks(promotionCheck)
        handler.postDelayed(promotionCheck, delay)
    }

    private val promotionCheck = Runnable {
        val app = appContext ?: return@Runnable
        val nm = app.getSystemService(NotificationManager::class.java) ?: return@Runnable
        val now = nm.activeNotifications.any {
            it.id == ONGOING_ID && it.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0
        }
        if (now != promoted) {
            promoted = now
            // [PERF] log 内部已通知监听者，状态未变时不再触发一次全量 UI 刷新
            log(if (now) "✔ 已提升为实时通知（灵动胶囊）" else "✘ 未被提升：仅普通通知（到系统设置开启实时通知）")
        }
        if (session != null) schedulePromotionCheck(2000L)
    }

    fun buildNotification(context: Context): Notification {
        ensureChannels(context)
        val s = session
        val p = phase
        if (s == null || p == null) {
            // 兜底分支：正常流程走不到（post 已拦截），保持可清除，避免残留常驻通知
            return Notification.Builder(context, CHANNEL_ONGOING)
                .setSmallIcon(R.drawable.ic_notif_capsule)
                .setContentTitle("会话已结束")
                .setAutoCancel(true)
                .build()
        }

        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val title: String
        val text: String
        when (s) {
            is Session.Pomodoro -> when {
                inBreath -> {
                    title = "🌿 呼吸放松"
                    text = if (paused) "已暂停 · ${p.label}"
                    else "第 $breathRound/$roundsTotal 轮 · ${p.label} ${phaseRemaining}s"
                }
                p.kind == Kind.FOCUS -> {
                    title = if (!s.taskName.isNullOrBlank()) "🍅 ${s.taskName}"
                    else "🍅 专注 · 第 ${pomCount + 1} 个番茄"
                    text = if (paused) "已暂停 · 剩余 ${mmss(phaseRemaining)}"
                    else "剩余 ${mmss(phaseRemaining)}" +
                        (if (s.taskName.isNullOrBlank()) " · 保持节奏" else " · 第 ${pomCount + 1} 个番茄")
                }
                inLongRest -> {
                    title = "🛋 长休息"
                    text = if (paused) "已暂停 · 剩余 ${mmss(phaseRemaining)}"
                    else "剩余 ${mmss(phaseRemaining)} · 好好放松一下"
                }
                else -> {
                    title = "☕ 休息一下"
                    text = if (paused) "已暂停 · 剩余 ${mmss(phaseRemaining)}"
                    else "剩余 ${mmss(phaseRemaining)} · 喝口水，看看远处"
                }
            }
            is Session.Breathing -> {
                title = "🌿 呼吸 · ${s.pattern.name}"
                text = if (paused) "已暂停 · ${p.label}"
                else "第 $breathRound/$roundsTotal 轮 · ${p.label} ${phaseRemaining}s"
            }
        }

        val b = Notification.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notif_capsule)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(context.getColor(R.color.primary))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)

        // 专注/休息：胶囊原生倒计时；呼吸阶段太短，用文字倒计时
        if (!paused && !p.isBreath) {
            b.setWhen(System.currentTimeMillis() + phaseRemaining * 1000L)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }

        // [FIX] 除零保护
        val phasePct = (p.seconds - phaseRemaining) * 100 / p.seconds.coerceAtLeast(1)
        if (Build.VERSION.SDK_INT >= 36 && canPromote(context)) {
            val style = Notification.ProgressStyle()
                // 品牌胶囊图标骑在进度头上，随进度逐秒移动（配送类实时通知同款）
                .setProgressTrackerIcon(
                    Icon.createWithResource(context, R.drawable.ic_notif_capsule))
            if (inBreath || s is Session.Breathing) {
                // 一轮呼吸 = 按各阶段时长分段的彩色进度条
                val curPattern = pattern ?: s.let {
                    if (it is Session.Breathing) it.pattern else Pattern.RELAX_478
                }
                curPattern.phases.forEach { ph ->
                    style.addProgressSegment(
                        Notification.ProgressStyle.Segment(ph.seconds).apply { setColor(ph.color) })
                }
                val roundElapsed = curPattern.phases.take(phaseIndex).sumOf { it.seconds } +
                    (p.seconds - phaseRemaining)
                style.setStyledByProgress(true)
                style.setProgress(roundElapsed * 100 / curPattern.total)
            } else {
                style.addProgressSegment(
                    Notification.ProgressStyle.Segment(100).apply { setColor(p.color) })
                style.setStyledByProgress(true)
                style.setProgress(phasePct)
            }
            b.setStyle(style)
            b.addExtras(Bundle().apply { putBoolean(EXTRA_PROMOTED, true) })
        } else {
            b.setProgress(100, phasePct, false)
        }

        b.addAction(action(context, if (paused) ACTION_RESUME else ACTION_PAUSE,
            if (paused) "继续" else "暂停",
            if (paused) R.drawable.ic_play else R.drawable.ic_pause, 10))
        if (s is Session.Pomodoro && !inBreath) {
            b.addAction(action(context, ACTION_SKIP, "跳过", R.drawable.ic_skip, 11))
        }
        b.addAction(action(context, ACTION_STOP, "结束", R.drawable.ic_stop, 12))
        return b.build()
    }

    private fun action(context: Context, action: String, title: String, icon: Int, rc: Int): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            context, rc,
            Intent(context, SessionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(context, icon), title, pi).build()
    }

    /** 阶段切换提醒（带声音/振动） */
    private fun chirp(context: Context, message: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(ONGOING_ID + 100 + (chirpSeq++ % 20), Notification.Builder(context, CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_notif_capsule)
                .setContentTitle("胶囊专注")
                .setContentText(message)
                .setContentIntent(PendingIntent.getActivity(
                    context, 2,
                    Intent(context, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build())
        } catch (e: SecurityException) { /* 无通知权限则跳过提醒 */ }
        log(message)
    }

    /** [PERF] 频道定义进程内只需创建一次，不再每秒两次 createNotificationChannel IPC */
    private fun ensureChannels(context: Context, force: Boolean = false) {
        if (channelsReady && !force) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "进行中的会话", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            })
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "阶段提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                enableVibration(true)
            })
        channelsReady = true
    }

    // ---------- 杂项 ----------

    private fun log(message: String) {
        logLines += "${timeFormat.format(Date())}  $message"
        if (logLines.size > 40) logLines.removeAt(0)
        cachedLog = logLines.joinToString("\n")
        notifyListeners()
    }

    /** [PERF] 同一帧内多次 log/tick/检测只触发一次 UI 刷新 */
    private val notifyRunnable = Runnable {
        notifyPending = false
        listeners.toList().forEach { it.onChanged() }
    }

    private fun notifyListeners() {
        if (notifyPending) return
        notifyPending = true
        handler.post(notifyRunnable)
    }

    fun mmss(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)
}
