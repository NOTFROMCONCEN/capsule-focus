package app.capsulefocus

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 统计栏内容：累计统计 + 每日番茄柱状图 + 时段分析 + 按天分组的会话列表。
 * 原独立 HistoryActivity，v1.5.0 起并入主界面「统计」分栏。
 */
class HistoryUi(private val activity: AppCompatActivity) {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var barChart: BarChartView
    private lateinit var boxTimeRows: LinearLayout
    private lateinit var textBestTime: TextView
    private var records: List<HistoryStore.Record> = emptyList()
    private var range = 7
    // [FIX] 不能 lazy 缓存：用户改每日目标后，柱状图目标线要跟着变
    private val goal: Int
        get() = activity.getSharedPreferences("cfg", AppCompatActivity.MODE_PRIVATE)
            .getInt("dailyGoal", 8)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayLabelSameYear = SimpleDateFormat("M月d日", Locale.getDefault())
    private val dayLabelOtherYear = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())
    private var loaded = false

    fun wire() {
        recycler = activity.findViewById(R.id.recyclerHistory)
        emptyView = activity.findViewById(R.id.emptyHistory)
        barChart = activity.findViewById(R.id.barChart)
        boxTimeRows = activity.findViewById(R.id.boxTimeRows)
        textBestTime = activity.findViewById(R.id.textBestTime)
        recycler.layoutManager = LinearLayoutManager(activity)
        activity.findViewById<ChipGroup>(R.id.chipGroupRange).setOnCheckedStateChangeListener { _, ids ->
            range = if (ids.contains(R.id.chip30)) 30 else 7
            renderChart()
        }
        activity.findViewById<View>(R.id.btnClearHistory).setOnClickListener { confirmClear() }
    }

    /** [PERF] 切 tab 时不再全量重读 JSON / 重建 adapter；数据变更方传 force = true */
    fun reload(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        records = HistoryStore.all(activity)
        val adapter = recycler.adapter
        if (adapter is HistAdapter) adapter.refresh() else recycler.adapter = HistAdapter()
        emptyView.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        renderSummary()
        renderChart()
        renderTimeAnalysis()
    }

    // ---------- 统计 ----------

    private fun renderSummary() {
        val summaryMain: TextView = activity.findViewById(R.id.summaryMain)
        val summarySub: TextView = activity.findViewById(R.id.summarySub)
        if (records.isEmpty()) {
            summaryMain.text = "完成第一个专注会话后，这里会显示累计数据"
            summarySub.text = ""
            return
        }
        val poms = records.sumOf { it.poms }
        val focusMin = records.sumOf { it.poms * it.focusMinutes }
        val rounds = records.sumOf { it.roundsDone }
        summaryMain.text = "🍅 × $poms · 专注 ${fmtMinutes(focusMin)}"
        summarySub.text = "呼吸 $rounds 轮 · 连续 ${streakDays()} 天 · 共 ${records.size} 次会话"
    }

    private fun confirmDelete(r: HistoryStore.Record) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("删除这条记录？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                HistoryStore.delete(activity, r.startAt)
                reload(force = true)
            }
            .show()
    }

    private fun confirmClear() {
        if (records.isEmpty()) return
        MaterialAlertDialogBuilder(activity)
            .setTitle("清空历史记录")
            .setMessage("将删除全部 ${records.size} 条记录，无法恢复")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                HistoryStore.clear(activity)
                reload(force = true)
            }
            .show()
    }

    /** 连续有记录的天数（今天没有则从昨天起算） */
    private fun streakDays(): Int {
        if (records.isEmpty()) return 0
        val days = records.map { dayOf(it.startAt) }.toSortedSet()
        var cur = dayOf(System.currentTimeMillis())
        if (days.last() != cur) {
            if (days.last() != cur - 1) return 0
            cur--
        }
        var streak = 0
        while (days.contains(cur)) { streak++; cur-- }
        return streak
    }

    /**
     * [FIX] 用 UTC 偏移归一化：夏令时切换的 23/25 小时日不会把同一天算成两天。
     * [PERF] 不再每次 new Calendar 并把 6 个字段清零。
     */
    private fun dayOf(millis: Long): Long =
        (millis + java.util.TimeZone.getDefault().getOffset(millis)) / 86400000L

    private fun fmtMinutes(min: Int): String = when {
        min >= 60 && min % 60 > 0 -> "${min / 60} 小时 ${min % 60} 分"
        min >= 60 -> "${min / 60} 小时"
        else -> "$min 分钟"
    }

    private fun fmtSec(sec: Int): String = when {
        sec >= 3600 && sec % 3600 / 60 > 0 -> "${sec / 3600} 小时 ${sec % 3600 / 60} 分"
        sec >= 3600 -> "${sec / 3600} 小时"
        sec >= 60 && sec % 60 > 0 -> "${sec / 60} 分 ${sec % 60} 秒"
        sec >= 60 -> "${sec / 60} 分钟"
        else -> "$sec 秒"
    }

    private fun dayLabel(millis: Long): String {
        val today = dayOf(System.currentTimeMillis())
        val day = dayOf(millis)
        return when (today - day) {
            0L -> "今天"
            1L -> "昨天"
            else -> {
                val sameYear = Calendar.getInstance().get(Calendar.YEAR) ==
                    Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)
                (if (sameYear) dayLabelSameYear else dayLabelOtherYear).format(Date(millis))
            }
        }
    }

    /** 最近 range 天的每日番茄柱状图（末位=今天） */
    private fun renderChart() {
        val counts = IntArray(range)
        val today = dayOf(System.currentTimeMillis())
        records.forEach { r ->
            val ago = (today - dayOf(r.startAt)).toInt()
            if (ago in 0 until range) counts[range - 1 - ago] += r.poms
        }
        val weekNames = arrayOf("日", "一", "二", "三", "四", "五", "六")
        // [PERF] 复用一个 Calendar，避免 range(7/30) 次构造
        val cal = Calendar.getInstance()
        val now = System.currentTimeMillis()
        val labels = (0 until range).map { i ->
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, -(range - 1 - i))
            if (range <= 7) weekNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
            else cal.get(Calendar.DAY_OF_MONTH).let { d ->
                if (d % 5 == 0 || i == range - 1) d.toString() else ""
            }
        }
        barChart.set(counts.toList(), labels, goal)
    }

    /** 最佳时段分析：按开始时刻分桶统计番茄产出 */
    private fun renderTimeAnalysis() {
        val names = arrayOf("早晨", "上午", "午后", "下午", "晚上", "深夜")
        fun bucketOf(hour: Int) = when (hour) {
            in 6..8 -> 0; in 9..11 -> 1; in 12..14 -> 2
            in 15..17 -> 3; in 18..22 -> 4; else -> 5
        }
        val sums = IntArray(6)
        val cal = Calendar.getInstance()   // [PERF] 复用，最多 500 条记录只建一次
        records.filter { it.kind == HistoryStore.KIND_POMODORO }.forEach { r ->
            cal.timeInMillis = r.startAt
            sums[bucketOf(cal.get(Calendar.HOUR_OF_DAY))] += r.poms
        }
        boxTimeRows.removeAllViews()
        val max = sums.maxOrNull() ?: 0
        val bestIdx = sums.indices.firstOrNull { sums[it] == max }
        val dp = activity.resources.displayMetrics.density

        names.forEachIndexed { i, name ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            }
            row.addView(TextView(activity).apply {
                text = name
                textSize = 12f
                setTextColor(activity.getColor(R.color.dim))
                layoutParams = LinearLayout.LayoutParams(
                    (46 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            val w = if (max == 0) 0.02f else sums[i].toFloat() / max.toFloat()
            val barColor = if (i == bestIdx && max > 0) activity.getColor(R.color.primary)
            else (activity.getColor(R.color.outline) and 0x00FFFFFF) or 0x66000000
            val bar = View(activity).apply {
                background = activity.getDrawable(R.drawable.bg_bar)
                backgroundTintList = android.content.res.ColorStateList.valueOf(barColor)
                layoutParams = LinearLayout.LayoutParams(0, (12 * dp).toInt(), max(w, 0.02f))
            }
            val spacer = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, (12 * dp).toInt(), 1f - max(w, 0.02f))
            }
            row.addView(bar)
            row.addView(spacer)
            row.addView(TextView(activity).apply {
                text = sums[i].toString()
                textSize = 12f
                setTextColor(activity.getColor(R.color.dim))
                layoutParams = LinearLayout.LayoutParams(
                    (26 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            boxTimeRows.addView(row)
        }
        textBestTime.text = if (max == 0) "还没有时段数据，多跑几个番茄就有了"
        else "最出番茄的时段：${names[bestIdx!!]}（累计 $max 个）"
    }

    // ---------- 列表 ----------

    private class Row(val header: Boolean, val record: HistoryStore.Record? = null, val label: String = "")

    private inner class HistAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows = buildRows()

        /** [PERF] 复用 adapter，保留滚动位置，避免整表重新 inflate */
        fun refresh() {
            rows = buildRows()
            notifyDataSetChanged()
        }

        private fun buildRows(): List<Row> {
            val rows = mutableListOf<Row>()
            var lastDay = -1L
            records.forEach { r ->
                val day = dayOf(r.startAt)
                if (day != lastDay) {
                    lastDay = day
                    rows += Row(true, label = dayLabel(r.startAt))
                }
                rows += Row(false, record = r)
            }
            return rows
        }

        override fun getItemViewType(position: Int) = if (rows[position].header) 0 else 1

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) {
                HeaderHolder(LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_hist_header, parent, false))
            } else {
                RecordHolder(LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_hist_record, parent, false))
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (holder is HeaderHolder) {
                holder.label.text = row.label
            } else if (holder is RecordHolder) {
                val r = row.record ?: return
                holder.icon.text = if (r.kind == HistoryStore.KIND_POMODORO) "🍅" else "🌿"
                if (r.kind == HistoryStore.KIND_POMODORO) {
                    holder.title.text = "番茄钟 · ${r.focusMinutes} 分钟"
                    val parts = mutableListOf<String>()
                    r.taskName?.let { parts += it }
                    parts += if (r.poms > 0) "完成 🍅 × ${r.poms}" else "未完成番茄"
                    if (r.distractions > 0) parts += "摸鱼 ${r.distractions} 次"
                    parts += "进行 ${fmtSec(r.activeSec)}"
                    r.restMode?.let { parts += "休息：$it" }
                    holder.detail.text = parts.joinToString(" · ")
                } else {
                    holder.title.text = "呼吸训练 · ${r.pattern}"
                    holder.detail.text =
                        (if (r.completed) "完成 ${r.roundsTotal} 轮" else "${r.roundsDone}/${r.roundsTotal} 轮") +
                            " · 进行 ${fmtSec(r.activeSec)}"
                }
                holder.time.text = timeFormat.format(Date(r.startAt))
                holder.itemView.setOnLongClickListener {
                    confirmDelete(r)
                    true
                }
            }
        }
    }

    private class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.textHeader)
    }

    private class RecordHolder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: TextView = v.findViewById(R.id.iconView)
        val title: TextView = v.findViewById(R.id.titleView)
        val detail: TextView = v.findViewById(R.id.detailView)
        val time: TextView = v.findViewById(R.id.timeView)
    }
}
