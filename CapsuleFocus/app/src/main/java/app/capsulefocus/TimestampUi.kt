package app.capsulefocus

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 时间戳栏：现实世界操作的「时间 + 备注」随手记。
 * 快速记录当前时刻；长按条目可补记实际发生时间、改备注或删除；可一键导出到剪贴板。
 */
class TimestampUi(private val activity: AppCompatActivity) {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var input: EditText
    private var entries: List<TimestampStore.Timestamp> = emptyList()
    private var loaded = false
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val exportFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val editFormat = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())

    fun wire() {
        recycler = activity.findViewById(R.id.recyclerTimestamp)
        emptyView = activity.findViewById(R.id.textTsEmpty)
        input = activity.findViewById(R.id.tsInput)
        recycler.layoutManager = LinearLayoutManager(activity)
        activity.findViewById<MaterialButton>(R.id.btnAddTs).setOnClickListener { addFromInput() }
        input.setOnEditorActionListener { v, _, _ ->
            addFromInput(); true
        }
        activity.findViewById<View>(R.id.btnExportTs).setOnClickListener { exportToClipboard() }
        activity.findViewById<View>(R.id.btnClearTs).setOnClickListener { confirmClear() }
    }

    /** [PERF] 切 tab 不重复读 JSON / 重建 adapter；数据变更方传 force = true */
    fun reload(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        entries = TimestampStore.all(activity)
        val adapter = recycler.adapter
        if (adapter is TsAdapter) adapter.refresh() else recycler.adapter = TsAdapter()
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addFromInput() {
        TimestampStore.add(activity, input.text.toString())
        input.setText("")
        reload(force = true)
    }

    // ---------- 编辑 / 删除 ----------

    private fun showEditDialog(entry: TimestampStore.Timestamp) {
        var pickedAt = entry.at
        val dp = activity.resources.displayMetrics.density
        val noteInput = EditText(activity).apply {
            hint = "做了什么？（可留空）"
            setText(entry.note)
            setSelection(text.length)
        }
        val timeRow = TextView(activity).apply {
            textSize = 13f
            setTextColor(activity.getColor(R.color.primary))
            setPadding(0, (10 * dp).toInt(), 0, 0)
        }
        fun renderTime() { timeRow.text = "时间：${editFormat.format(Date(pickedAt))} · 点按修改" }
        renderTime()
        timeRow.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = pickedAt }
            DatePickerDialog(activity, { _, y, m, day ->
                TimePickerDialog(activity, { _, h, min ->
                    cal.set(Calendar.YEAR, y)
                    cal.set(Calendar.MONTH, m)
                    cal.set(Calendar.DAY_OF_MONTH, day)
                    cal.set(Calendar.HOUR_OF_DAY, h)
                    cal.set(Calendar.MINUTE, min)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    pickedAt = cal.timeInMillis
                    renderTime()
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (10 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(noteInput)
            addView(timeRow)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("编辑时间戳")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                TimestampStore.update(activity, entry.at, pickedAt, noteInput.text.toString())
                reload(force = true)
            }
            .setNeutralButton("删除") { _, _ -> confirmDelete(entry) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(entry: TimestampStore.Timestamp) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("删除这条记录？")
            .setMessage(timeFormat.format(Date(entry.at)) + " " + entry.note.ifBlank { "未备注" })
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                TimestampStore.delete(activity, entry.at)
                reload(force = true)
            }
            .show()
    }

    // ---------- 导出 / 清空 ----------

    private fun exportToClipboard() {
        if (entries.isEmpty()) return
        // 时间正序（旧→新），贴进工单/运维日志可直接按行追加
        val text = entries.reversed().joinToString("\n") { e ->
            exportFormat.format(Date(e.at)) + "  " + e.note.ifBlank { "未备注" }
        }
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("时间戳", text))
        Toast.makeText(activity, "已复制 ${entries.size} 条到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun confirmClear() {
        if (entries.isEmpty()) return
        MaterialAlertDialogBuilder(activity)
            .setTitle("清空时间戳")
            .setMessage("将删除全部 ${entries.size} 条记录，无法恢复")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                TimestampStore.clear(activity)
                reload(force = true)
            }
            .show()
    }

    // ---------- 按天分组的列表（与统计栏历史列表同款做法） ----------

    private fun dayOf(millis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 86400000L
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
                SimpleDateFormat(if (sameYear) "M月d日" else "yyyy年M月d日", Locale.getDefault())
                    .format(Date(millis))
            }
        }
    }

    private class Row(val header: Boolean, val entry: TimestampStore.Timestamp? = null, val label: String = "")

    private inner class TsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows = buildRows()

        /** [PERF] 复用 adapter，保留滚动位置 */
        fun refresh() {
            rows = buildRows()
            notifyDataSetChanged()
        }

        private fun buildRows(): List<Row> {
            val rows = mutableListOf<Row>()
            var lastDay = -1L
            entries.forEach { e ->
                val day = dayOf(e.at)
                if (day != lastDay) {
                    lastDay = day
                    rows += Row(true, label = dayLabel(e.at))
                }
                rows += Row(false, entry = e)
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
                EntryHolder(LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_timestamp, parent, false))
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (holder is HeaderHolder) {
                holder.label.text = row.label
            } else if (holder is EntryHolder) {
                val e = row.entry ?: return
                holder.note.text = e.note.ifBlank { "未备注" }
                holder.note.setTextColor(activity.getColor(if (e.note.isBlank()) R.color.dim else R.color.on_surface))
                holder.time.text = timeFormat.format(Date(e.at))
                holder.itemView.setOnLongClickListener {
                    showEditDialog(e)
                    true
                }
            }
        }
    }

    private class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.textHeader)
    }

    private class EntryHolder(v: View) : RecyclerView.ViewHolder(v) {
        val note: TextView = v.findViewById(R.id.noteView)
        val time: TextView = v.findViewById(R.id.timeView)
    }
}
