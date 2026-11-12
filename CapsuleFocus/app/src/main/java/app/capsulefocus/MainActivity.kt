package app.capsulefocus

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_START_POMODORO = "app.capsulefocus.action.START_POMODORO"
        const val ACTION_START_BREATHING = "app.capsulefocus.action.START_BREATHING"
    }

    private val roundOptions = intArrayOf(4, 8, 12)

    private var mode = 0            // 0=番茄钟 1=呼吸
    private var focusIndex = 1
    private var restIndex = 1       // 0=普通 1=呼吸
    private var patternIndex = 0
    private var roundIndex = 1
    private var syncingChips = false   // [FIX] 初始化回写 chip 时抑制监听器

    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var statusView: TextView
    private lateinit var statsText: TextView
    private lateinit var logView: TextView
    private lateinit var bannerPromote: View
    private lateinit var viewMain: View
    private lateinit var viewFocus: View
    private lateinit var viewTodo: View
    private lateinit var viewTimestamp: View
    private lateinit var viewStats: View
    private lateinit var viewSettings: View
    private lateinit var viewRunning: View
    private lateinit var textGoalRow: TextView
    private lateinit var historyUi: HistoryUi
    private lateinit var timestampUi: TimestampUi
    private var currentTab = 0
    private var wasRunning = false
    private lateinit var configTomato: View
    private lateinit var configBreath: View
    private lateinit var cardTomato: MaterialCardView
    private lateinit var cardBreath: MaterialCardView
    private lateinit var tomatoSummary: TextView
    private lateinit var breathSummary: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var phaseView: TextView
    private lateinit var countdownView: TextView
    private lateinit var infoView: TextView
    private lateinit var capsuleState: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var pauseResumeBtn: MaterialButton
    private lateinit var breathCircle: BreathCircleView
    private lateinit var textRestLen: TextView
    private lateinit var textLongRestDesc: TextView
    private lateinit var textNoiseDesc: TextView
    private lateinit var textSlouchDesc: TextView
    private lateinit var textPatternCustomDesc: TextView
    private lateinit var switchLongRest: MaterialSwitch
    private lateinit var switchNoise: MaterialSwitch
    private lateinit var switchSlouch: MaterialSwitch
    private lateinit var switchBreathVib: MaterialSwitch
    private lateinit var todoList: LinearLayout
    private lateinit var textTodoEmpty: TextView
    private lateinit var todoInput: EditText

    private val listener = object : SessionEngine.Listener {
        override fun onChanged() = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        // [FIX] 钳制索引：旧版本/异常 prefs 不会让数组越界
        mode = prefs.getInt("mode", 0).coerceIn(0, 1)
        focusIndex = prefs.getInt("focusIndex", 1).coerceIn(0, 3)
        restIndex = prefs.getInt("restIndex", 1).coerceIn(0, 1)
        patternIndex = prefs.getInt("patternIndex", 0).coerceIn(0, 2)
        roundIndex = prefs.getInt("roundIndex", 1).coerceIn(0, 2)

        statusView = findViewById(R.id.status)
        statsText = findViewById(R.id.statsText)
        logView = findViewById(R.id.log)
        bannerPromote = findViewById(R.id.bannerPromote)
        viewMain = findViewById(R.id.viewMain)
        viewFocus = findViewById(R.id.viewFocus)
        viewTodo = findViewById(R.id.viewTodo)
        viewTimestamp = findViewById(R.id.viewTimestamp)
        viewStats = findViewById(R.id.viewStats)
        viewSettings = findViewById(R.id.viewSettings)
        viewRunning = findViewById(R.id.viewRunning)
        textGoalRow = findViewById(R.id.textGoalRow)
        historyUi = HistoryUi(this).also { it.wire() }
        timestampUi = TimestampUi(this).also { it.wire() }
        configTomato = findViewById(R.id.configTomato)
        configBreath = findViewById(R.id.configBreath)
        cardTomato = findViewById(R.id.cardTomato)
        cardBreath = findViewById(R.id.cardBreath)
        tomatoSummary = findViewById(R.id.tomatoSummary)
        breathSummary = findViewById(R.id.breathSummary)
        btnStart = findViewById(R.id.btnStart)
        phaseView = findViewById(R.id.phaseLabel)
        countdownView = findViewById(R.id.countdown)
        infoView = findViewById(R.id.roundInfo)
        capsuleState = findViewById(R.id.capsuleState)
        progress = findViewById(R.id.phaseProgress)
        pauseResumeBtn = findViewById(R.id.btnPauseResume)
        breathCircle = findViewById(R.id.breathCircle)

        // 点顶栏「今日 🍅」设置每日目标
        statsText.setOnClickListener { showGoalDialog() }

        // 自定义/开关行
        textRestLen = findViewById(R.id.textRestLen)
        textLongRestDesc = findViewById(R.id.textLongRestDesc)
        textNoiseDesc = findViewById(R.id.textNoiseDesc)
        textSlouchDesc = findViewById(R.id.textSlouchDesc)
        textPatternCustomDesc = findViewById(R.id.textPatternCustomDesc)
        switchLongRest = findViewById(R.id.switchLongRest)
        switchNoise = findViewById(R.id.switchNoise)
        switchSlouch = findViewById(R.id.switchSlouch)
        switchBreathVib = findViewById(R.id.switchBreathVib)
        textRestLen.setOnClickListener { showRestLenDialog() }
        textLongRestDesc.setOnClickListener { showLongRestDialog() }
        textNoiseDesc.setOnClickListener { showNoiseDialog() }
        textSlouchDesc.setOnClickListener { maybeOpenUsageAccess() }
        switchLongRest.isChecked = prefs.getBoolean("longRestOn", false)
        switchLongRest.setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean("longRestOn", c).apply(); renderConfigDescs()
        }
        switchNoise.isChecked = prefs.getBoolean("noiseOn", false)
        switchNoise.setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean("noiseOn", c).apply(); renderConfigDescs()
        }
        switchSlouch.isChecked = prefs.getBoolean("slouchOn", false)
        switchSlouch.setOnCheckedChangeListener { _, c ->
            if (c && !DistractionDetector.hasPermission(this)) {
                switchSlouch.isChecked = false
                maybeOpenUsageAccess()
            } else {
                prefs.edit().putBoolean("slouchOn", c).apply(); renderConfigDescs()
            }
        }
        switchBreathVib.isChecked = prefs.getBoolean("breathVib", true)
        switchBreathVib.setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean("breathVib", c).apply()
        }

        // 待办清单
        todoList = findViewById(R.id.todoList)
        textTodoEmpty = findViewById(R.id.textTodoEmpty)
        todoInput = findViewById(R.id.todoInput)
        findViewById<MaterialButton>(R.id.btnAddTodo).setOnClickListener { addTodoFromInput() }
        todoInput.setOnEditorActionListener { v, _, _ ->
            addTodoFromInput(); v.text = null; true
        }
        renderTodos()

        // 日志区藏在底部（复用状态页），正式 UI 里保留排障入口
        logView.visibility = View.VISIBLE

        cardTomato.setOnClickListener { mode = 0; persistConfig(); applyModeUi() }
        cardBreath.setOnClickListener { mode = 1; persistConfig(); applyModeUi() }

        // [FIX] syncChips() 会触发这些回调；加抑制，避免启动时重复渲染、
        // 以及"上次选了自定义"就每次冷启动自动弹出输入对话框
        findViewById<ChipGroup>(R.id.chipGroupFocus).setOnCheckedStateChangeListener { _, c ->
            if (syncingChips) return@setOnCheckedStateChangeListener
            focusIndex = when (c.firstOrNull()) {
                R.id.chipFocus15 -> 0; R.id.chipFocus45 -> 2
                R.id.chipFocusCustom -> 3; else -> 1
            }
            if (focusIndex == 3) showFocusCustomDialog()
            persistConfig(); applyModeUi()
        }
        findViewById<ChipGroup>(R.id.chipGroupRest).setOnCheckedStateChangeListener { _, c ->
            if (syncingChips) return@setOnCheckedStateChangeListener
            restIndex = if (c.firstOrNull() == R.id.chipRestPlain) 0 else 1
            persistConfig(); applyModeUi()
        }
        findViewById<ChipGroup>(R.id.chipGroupPattern).setOnCheckedStateChangeListener { _, c ->
            if (syncingChips) return@setOnCheckedStateChangeListener
            patternIndex = when (c.firstOrNull()) {
                R.id.chipBox -> 1; R.id.chipPatternCustom -> 2; else -> 0
            }
            if (patternIndex == 2) showPatternCustomDialog()
            persistConfig(); applyModeUi()
        }
        findViewById<ChipGroup>(R.id.chipGroupRounds).setOnCheckedStateChangeListener { _, c ->
            if (syncingChips) return@setOnCheckedStateChangeListener
            roundIndex = when (c.firstOrNull()) {
                R.id.chipR4 -> 0; R.id.chipR12 -> 2; else -> 1
            }
            persistConfig(); applyModeUi()
        }
        syncChips()

        btnStart = findViewById<MaterialButton>(R.id.btnStart).also {
            it.setOnClickListener { startSelected() }
        }

        findViewById<MaterialButton>(R.id.btnPauseResume).setOnClickListener { SessionEngine.togglePause(this) }
        findViewById<MaterialButton>(R.id.btnSkip).setOnClickListener { SessionEngine.skip(this) }
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener { SessionEngine.stop(this) }

        // 分栏导航 + FAB
        findViewById<View>(R.id.navFocus).setOnClickListener { selectTab(0) }
        findViewById<View>(R.id.navTodo).setOnClickListener { selectTab(1) }
        findViewById<View>(R.id.navTimestamp).setOnClickListener { selectTab(2) }
        findViewById<View>(R.id.navStats).setOnClickListener { selectTab(3) }
        findViewById<View>(R.id.navSettings).setOnClickListener { selectTab(4) }
        findViewById<View>(R.id.fabStart).setOnClickListener { startSelected() }
        textGoalRow.setOnClickListener { showGoalDialog() }

        val chipNavStyle = findViewById<ChipGroup>(R.id.chipGroupNavStyle)
        chipNavStyle.check(if (prefs.getBoolean("navFloating", true))
            R.id.chipNavFloat else R.id.chipNavFull)
        chipNavStyle.setOnCheckedStateChangeListener { _, ids ->
            prefs.edit().putBoolean("navFloating", ids.contains(R.id.chipNavFloat)).apply()
            applyNavStyle()
        }
        findViewById<View>(R.id.navCard).post { applyNavStyle() }
        selectTab(0)

        // [FIX] 非 API36 / 厂商 ROM 无该设置页时降级到通知设置，避免崩溃
        findViewById<MaterialButton>(R.id.bannerAction).setOnClickListener { openNotificationSettingsPage() }
        findViewById<MaterialButton>(R.id.btnLiveSettings).setOnClickListener { openNotificationSettingsPage() }
        findViewById<MaterialButton>(R.id.btnNotifSettings).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (_: Exception) { }
        }

        applyModeUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 长按图标 shortcuts：直接以上次配置开始对应模式的会话 */
    private fun handleIntent(intent: Intent?) {
        if (SessionEngine.session != null) return   // 已在跑，展示运行页即可
        when (intent?.action) {
            ACTION_START_POMODORO -> requestAndStart { SessionEngine.pomodoroFromPrefs(this) }
            ACTION_START_BREATHING -> requestAndStart { SessionEngine.breathingFromPrefs(this) }
        }
    }

    override fun onStart() {
        super.onStart()
        SessionEngine.addListener(listener)
        refresh()
    }

    override fun onStop() {
        super.onStop()
        SessionEngine.removeListener(listener)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val pending = pendingAction
        pendingAction = null
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            // [FIX] 执行原始启动意图：shortcut 指定的模式 / 待办携带的任务名不再丢失
            if (pending != null) pending() else startSelected()
        }
    }

    // ---------- 启动 ----------

    private fun startSelected() = requestAndStart {
        if (mode == 0) SessionEngine.pomodoroFromPrefs(this)
        else SessionEngine.breathingFromPrefs(this)
    }

    /** 统一启动入口：无通知权限先请求（授权回调后 pendingAction 自动执行） */
    private fun requestAndStart(builder: () -> SessionEngine.Session) {
        val action = { SessionEngine.start(this, builder()) }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAction = action
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            pendingAction = null
            action()
        }
    }

    private var pendingAction: (() -> Unit)? = null

    /** [FIX] 系统/厂商 ROM 可能没有实时通知设置页，逐级降级避免 ActivityNotFoundException */
    private fun openNotificationSettingsPage() {
        val promoted = Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS")
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        val fallback = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        val target = if (promoted.resolveActivity(packageManager) != null) promoted else fallback
        try {
            startActivity(target)
        } catch (_: Exception) {
            try { startActivity(fallback) } catch (_: Exception) { }
        }
    }

    // ---------- 配置 ----------

    private fun persistConfig() {
        prefs.edit()
            .putInt("mode", mode)
            .putInt("focusIndex", focusIndex)
            .putInt("restIndex", restIndex)
            .putInt("patternIndex", patternIndex)
            .putInt("roundIndex", roundIndex)
            .apply()
    }

    private fun syncChips() {
        syncingChips = true
        try {
            findViewById<ChipGroup>(R.id.chipGroupFocus).check(
                when (focusIndex) { 0 -> R.id.chipFocus15; 2 -> R.id.chipFocus45
                    3 -> R.id.chipFocusCustom; else -> R.id.chipFocus25 })
            findViewById<ChipGroup>(R.id.chipGroupRest).check(
                if (restIndex == 0) R.id.chipRestPlain else R.id.chipRestBreath)
            findViewById<ChipGroup>(R.id.chipGroupPattern).check(
                when (patternIndex) { 1 -> R.id.chipBox; 2 -> R.id.chipPatternCustom; else -> R.id.chip478 })
            findViewById<ChipGroup>(R.id.chipGroupRounds).check(
                when (roundIndex) { 0 -> R.id.chipR4; 2 -> R.id.chipR12; else -> R.id.chipR8 })
        } finally {
            syncingChips = false
        }
    }

    // ---------- 配置取值 ----------

    // [FIX] 配置构造统一委托给引擎，MainActivity 与 QS 磁贴不会再各自漂移
    /** 当前专注分钟数：预设或自定义 */
    private fun focusMinutes(): Int = SessionEngine.focusMinutesFromPrefs(prefs)

    /** 当前呼吸节奏：预设或自定义（吸/屏/呼/屏后，屏息可为 0） */
    private fun currentPattern(): SessionEngine.Pattern = SessionEngine.patternFromPrefs(prefs)

    private fun buildPomodoro(task: String? = null) =
        SessionEngine.pomodoroFromPrefs(this, task)

    private fun applyModeUi() {
        val tomato = mode == 0
        configTomato.visibility = if (tomato) View.VISIBLE else View.GONE
        configBreath.visibility = if (tomato) View.GONE else View.VISIBLE
        btnStart.text = if (tomato) "开始专注" else "开始呼吸"

        val stroke = dp(if (tomato) 3 else 0)
        cardTomato.strokeWidth = stroke
        cardBreath.strokeWidth = dp(if (tomato) 0 else 3)

        tomatoSummary.text = "${focusMinutes()} 分钟 · " +
            if (restIndex == 1) "呼吸放松" else "普通休息"
        val pat = currentPattern()
        breathSummary.text = "${pat.name} · ${roundOptions[roundIndex]} 轮"
        textPatternCustomDesc.visibility = if (patternIndex == 2) View.VISIBLE else View.GONE
        if (patternIndex == 2) {
            fun secOf(kind: SessionEngine.Kind) =
                pat.phases.firstOrNull { it.kind == kind }?.seconds ?: 0
            textPatternCustomDesc.text =
                "吸 ${secOf(SessionEngine.Kind.INHALE)} · 屏 ${secOf(SessionEngine.Kind.HOLD_IN)}" +
                " · 呼 ${secOf(SessionEngine.Kind.EXHALE)} · 屏后 ${secOf(SessionEngine.Kind.HOLD_OUT)} · 点「自定义」修改"
        }
        renderConfigDescs()
    }

    /** 开关行下的说明文字 */
    private fun renderConfigDescs() {
        textRestLen.text = "休息时长 ${prefs.getInt("restCustomMin", 5)} 分钟 · 点按修改"
        textLongRestDesc.text = if (prefs.getBoolean("longRestOn", false))
            "每 ${prefs.getInt("longRestEveryN", 4)} 个番茄 → ${prefs.getInt("longRestMin", 15)} 分钟长休息 · 点按修改"
        else "未开启 · 点按配置"
        textNoiseDesc.text = if (prefs.getBoolean("noiseOn", false))
            "${NoiseEngine.typeName(prefs.getInt("noiseType", NoiseEngine.TYPE_BROWN))} · 音量 ${prefs.getInt("noiseVol", 40)}% · 点按修改"
        else "未开启 · 点按选择音色"
        val granted = DistractionDetector.hasPermission(this)
        textSlouchDesc.text = when {
            !prefs.getBoolean("slouchOn", false) -> "未开启"
            !granted -> "已开启但未授权——点按去授权"
            else -> "专注时统计切走应用的次数，计入历史"
        }
    }

    // ---------- 刷新 ----------

    private fun refresh() {
        val nm = getSystemService(NotificationManager::class.java)
        val engine = SessionEngine
        val s = engine.session
        // [PERF] 资格查询与日期格式化每秒只做一次，日志只取一次
        val canPromote = engine.canPromote(this)
        val todayKey = engine.todayPomKey()
        val logText = engine.logText()

        // [FIX] 入账水位放在引擎里：Activity 重建/重复 start 都不会重复累计今日番茄
        val cur = if (s is SessionEngine.Session.Pomodoro) engine.pomCount else 0
        if (cur > engine.accountedPoms) {
            prefs.edit().putInt(todayKey,
                prefs.getInt(todayKey, 0) + (cur - engine.accountedPoms)).apply()
            engine.creditPomodoros(cur)
        }
        val today = prefs.getInt(todayKey, 0)
        val goal = prefs.getInt("dailyGoal", 8)
        statsText.text = "今日 🍅 $today/$goal"
        statsText.setTextColor(getColor(if (today >= goal) R.color.ok_green else R.color.dim))
        textGoalRow.text = "每日番茄目标 $goal · 点按修改"

        // 实时通知提示条
        bannerPromote.visibility =
            if (Build.VERSION.SDK_INT >= 36 && !canPromote) View.VISIBLE else View.GONE

        if (s == null) {
            if (wasRunning) { wasRunning = false; historyUi.reload(force = true) }
            viewMain.visibility = View.VISIBLE
            viewRunning.visibility = View.GONE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            wasRunning = true
            viewMain.visibility = View.GONE
            viewRunning.visibility = View.VISIBLE
            val p = engine.phase
            if (p != null && p.isBreath) {
                breathCircle.visibility = View.VISIBLE
                breathCircle.breathe(p, engine.phaseRemaining, engine.paused,
                    "${engine.breathRound}:${p.kind}")
            } else {
                breathCircle.visibility = View.GONE
                breathCircle.breathe(null, 0, false, null)
            }
            phaseView.text = p?.label ?: "—"
            countdownView.text = if (p != null && p.isBreath) "${engine.phaseRemaining}s"
            else SessionEngine.mmss(engine.phaseRemaining)
            infoView.text = when (s) {
                is SessionEngine.Session.Pomodoro -> when {
                    engine.inBreath -> "休息 · 呼吸 第 ${engine.breathRound}/${engine.roundsTotal} 轮"
                    else -> buildString {
                        if (!s.taskName.isNullOrBlank()) append("「${s.taskName}」 ")
                        append("第 ${engine.pomCount + 1} 个番茄 · 已完成 ×${engine.pomCount}")
                        if (DistractionDetector.count > 0) append(" · 摸鱼 ${DistractionDetector.count}")
                    }
                }
                is SessionEngine.Session.Breathing ->
                    "${s.pattern.name} · 第 ${engine.breathRound}/${engine.roundsTotal} 轮"
            }
            if (p != null) {
                progress.setIndicatorColor(p.color)
                // [FIX] 除零保护
                progress.setProgress(
                    (p.seconds - engine.phaseRemaining) * 100 / p.seconds.coerceAtLeast(1))
            }
            capsuleState.text = when (engine.promoted) {
                true -> "● 已在灵动胶囊上显示"
                false -> "● 未上胶囊——检查系统实时通知开关"
                null -> "● 检测中…"
            }
            capsuleState.setTextColor(getColor(when (engine.promoted) {
                true -> R.color.ok_green
                false -> R.color.primary
                else -> R.color.dim
            }))
            pauseResumeBtn.text = if (engine.paused) "继续" else "暂停"

            // 呼吸阶段保持亮屏（看着节奏做），专注/休息不干预
            if (p?.isBreath == true && !engine.paused) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        statusView.text = buildString {
            appendLine("系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）· 通知："
                + if (nm?.areNotificationsEnabled() == true) "已允许" else "未允许")
            if (Build.VERSION.SDK_INT >= 36) {
                appendLine("实时通知资格：" + if (canPromote) "已允许" else "未开启")
            }
            append("日志：")
            append(if (logText.isEmpty()) "（空）" else "见下")
        }
        logView.text = logText
    }

    // ---------- 分栏 ----------

    private fun selectTab(index: Int) {
        currentTab = index
        viewFocus.visibility = if (index == 0) View.VISIBLE else View.GONE
        viewTodo.visibility = if (index == 1) View.VISIBLE else View.GONE
        viewTimestamp.visibility = if (index == 2) View.VISIBLE else View.GONE
        viewStats.visibility = if (index == 3) View.VISIBLE else View.GONE
        viewSettings.visibility = if (index == 4) View.VISIBLE else View.GONE
        styleNav(R.id.navFocus, R.id.navFocusIcon, R.id.navFocusText, index == 0)
        styleNav(R.id.navTodo, R.id.navTodoIcon, R.id.navTodoText, index == 1)
        styleNav(R.id.navTimestamp, R.id.navTimestampIcon, R.id.navTimestampText, index == 2)
        styleNav(R.id.navStats, R.id.navStatsIcon, R.id.navStatsText, index == 3)
        styleNav(R.id.navSettings, R.id.navSettingsIcon, R.id.navSettingsText, index == 4)
        if (index == 2) timestampUi.reload()
        if (index == 3) historyUi.reload()
    }

    /** 底部导航样式：悬浮胶囊 / 常规通栏 */
    private fun applyNavStyle() {
        val floating = prefs.getBoolean("navFloating", true)
        val card = findViewById<com.google.android.material.card.MaterialCardView>(R.id.navCard)
        val fab = findViewById<View>(R.id.fabStart)
        val cardLp = card.layoutParams as android.widget.FrameLayout.LayoutParams
        val fabLp = fab.layoutParams as android.widget.FrameLayout.LayoutParams
        if (floating) {
            card.radius = dp(28).toFloat()
            card.cardElevation = dp(4).toFloat()
            cardLp.marginStart = dp(14); cardLp.topMargin = dp(4)
            // 右侧让出 FAB 宽度（FAB 56dp + 14 边距 + 8 间隙），5 个导航项不被开始按钮盖住
            cardLp.marginEnd = dp(78); cardLp.bottomMargin = dp(10)
            fabLp.marginEnd = dp(14)
            fabLp.bottomMargin = dp(7)
        } else {
            card.radius = 0f
            card.cardElevation = 0f
            cardLp.marginStart = 0; cardLp.topMargin = 0
            cardLp.marginEnd = 0; cardLp.bottomMargin = 0
            fabLp.marginEnd = dp(16)
            fabLp.bottomMargin = (if (card.height > 0) card.height else dp(80)) + dp(12)
        }
        card.requestLayout()
        fab.requestLayout()
    }

    private fun styleNav(itemId: Int, iconId: Int, textId: Int, selected: Boolean) {
        val item: View = findViewById(itemId)
        val icon: android.widget.ImageView = findViewById(iconId)
        val label: TextView = findViewById(textId)
        item.backgroundTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (selected) R.color.secondary_container else R.color.surface_container))
        icon.setColorFilter(getColor(if (selected) R.color.on_secondary_container else R.color.dim))
        label.setTextColor(getColor(if (selected) R.color.on_secondary_container else R.color.dim))
    }

    // ---------- 设置对话框 ----------

    /** 单个数字输入框的对话框骨架 */
    private fun numberField(default: Int): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(default.toString())
        setSelection(text.length)
    }

    private fun wrapDialog(vararg views: View): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
            views.forEach { addView(it) }
        }

    private fun showFocusCustomDialog() {
        val input = numberField(prefs.getInt("focusCustom", 45))
        MaterialAlertDialogBuilder(this)
            .setTitle("自定义专注时长（5–180 分钟）")
            .setView(wrapDialog(input))
            .setPositiveButton("保存") { _, _ ->
                input.text.toString().toIntOrNull()?.coerceIn(5, 180)?.let {
                    prefs.edit().putInt("focusCustom", it).apply()
                }
                applyModeUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRestLenDialog() {
        val input = numberField(prefs.getInt("restCustomMin", 5))
        MaterialAlertDialogBuilder(this)
            .setTitle("普通休息时长（1–60 分钟）")
            .setView(wrapDialog(input))
            .setPositiveButton("保存") { _, _ ->
                input.text.toString().toIntOrNull()?.coerceIn(1, 60)?.let {
                    prefs.edit().putInt("restCustomMin", it).apply()
                }
                renderConfigDescs()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLongRestDialog() {
        val every = numberField(prefs.getInt("longRestEveryN", 4))
        val minutes = numberField(prefs.getInt("longRestMin", 15))
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "每几个番茄"; textSize = 13f
                layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(every, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "长休息分钟"; textSize = 13f
                layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(minutes, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("长休息")
            .setView(wrapDialog(row1, row2))
            .setPositiveButton("保存") { _, _ ->
                val e = every.text.toString().toIntOrNull()?.coerceIn(2, 10) ?: 4
                val m = minutes.text.toString().toIntOrNull()?.coerceIn(5, 60) ?: 15
                prefs.edit().putInt("longRestEveryN", e).putInt("longRestMin", m).apply()
                renderConfigDescs()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPatternCustomDialog() {
        val cur = prefs.getString("patternCustom", "4,0,4,0")!!.split(",")
            .map { it.trim().toIntOrNull() ?: 0 }
        val labels = listOf("吸气 (1–60)", "屏息 (0–60)", "呼气 (1–60)", "屏息后 (0–60)")
        val inputs = labels.mapIndexed { i, label ->
            val et = numberField(cur.getOrElse(i) { 0 })
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = label; textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            } to et
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("自定义呼吸节奏（秒）")
            .setView(wrapDialog(*inputs.map { it.first }.toTypedArray()))
            .setPositiveButton("保存") { _, _ ->
                val vals = inputs.map { it.second.text.toString().toIntOrNull() ?: 0 }
                val fixed = listOf(
                    vals[0].coerceIn(1, 60), vals[1].coerceIn(0, 60),
                    vals[2].coerceIn(1, 60), vals[3].coerceIn(0, 60))
                prefs.edit().putString("patternCustom", fixed.joinToString(",")).apply()
                applyModeUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showNoiseDialog() {
        val radio = RadioGroup(this)
        val types = listOf(NoiseEngine.TYPE_BROWN to "棕色噪音（低沉，似雨声）",
            NoiseEngine.TYPE_PINK to "粉色噪音（温和，似风声）",
            NoiseEngine.TYPE_WHITE to "白色噪音（明亮，似沙沙声）")
        // [FIX] 不能把 type(0/1/2) 直接当 View id：id=0 会让 RadioGroup
        // 无法取消上一项，出现双选中或 checkedRadioButtonId=-1
        val idToType = mutableMapOf<Int, Int>()
        types.forEach { (t, label) ->
            val rb = RadioButton(this).apply {
                text = label
                id = View.generateViewId()
                isChecked = t == prefs.getInt("noiseType", NoiseEngine.TYPE_BROWN)
            }
            idToType[rb.id] = t
            radio.addView(rb)
        }
        val volText = TextView(this).apply {
            text = "音量 ${prefs.getInt("noiseVol", 40)}%"
            textSize = 13f; setPadding(0, dp(16), 0, dp(6))
        }
        val seek = SeekBar(this).apply {
            max = 100; progress = prefs.getInt("noiseVol", 40)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    volText.text = "音量 $p%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("专注白噪音")
            .setView(wrapDialog(radio, volText, seek))
            .setPositiveButton("保存") { _, _ ->
                val chosen = idToType[radio.checkedRadioButtonId]
                    ?: prefs.getInt("noiseType", NoiseEngine.TYPE_BROWN)
                prefs.edit()
                    .putInt("noiseType", chosen)
                    .putInt("noiseVol", seek.progress)
                    .putBoolean("noiseOn", true)
                    .apply()
                switchNoise.isChecked = true
                renderConfigDescs()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun maybeOpenUsageAccess() {
        val granted = DistractionDetector.hasPermission(this)
        if (!granted) {
            MaterialAlertDialogBuilder(this)
                .setTitle("摸鱼检测需要「使用情况访问」权限")
                .setMessage("用于统计专注期间切走应用的次数，数据只存在本机。\n点「去授权」打开系统设置，找到「胶囊专注」并允许。")
                .setPositiveButton("去授权") { _, _ ->
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            prefs.edit().putBoolean("slouchOn", true).apply()
            switchSlouch.isChecked = true
            renderConfigDescs()
        }
    }

    // ---------- 待办清单 ----------

    private fun addTodoFromInput() {
        val text = todoInput.text.toString().trim()
        if (text.isEmpty()) return
        TodoStore.add(this, text)
        todoInput.setText("")
        renderTodos()
    }

    private fun renderTodos() {
        todoList.removeAllViews()
        val items = TodoStore.all(this)
        textTodoEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        items.forEachIndexed { index, todo ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            row.addView(MaterialCheckBox(this).apply {
                text = todo.text
                isChecked = todo.done
                textSize = 14f
                if (todo.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                setOnCheckedChangeListener { _, _ ->
                    TodoStore.toggle(this@MainActivity, index); renderTodos()
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (!todo.done) {
                row.addView(ImageButton(this).apply {
                    setImageResource(R.drawable.ic_play)
                    setColorFilter(getColor(R.color.dim))
                    background = null
                    contentDescription = "以此任务开始专注"
                    setOnClickListener {
                        requestAndStart { buildPomodoro(task = todo.text) }
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        gravity = Gravity.CENTER_VERTICAL
                    }
                })
            }
            row.addView(ImageButton(this).apply {
                setImageResource(R.drawable.ic_delete)
                setColorFilter(getColor(R.color.dim))
                background = null
                contentDescription = "删除"
                setOnClickListener { TodoStore.delete(this@MainActivity, index); renderTodos() }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
            })
            todoList.addView(row)
        }
    }

    private fun showGoalDialog() {
        if (SessionEngine.session != null) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("dailyGoal", 8).toString())
            setSelection(text.length)
        }
        val wrap = FrameLayout(this).apply {
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("每日番茄目标")
            .setMessage("达到目标后顶栏计数变绿（范围 1–50）")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                input.text.toString().toIntOrNull()?.coerceIn(1, 50)?.let {
                    prefs.edit().putInt("dailyGoal", it).apply()
                    refresh()
                    // [FIX] 目标改了要重画柱状图目标线
                    historyUi.reload(force = true)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
