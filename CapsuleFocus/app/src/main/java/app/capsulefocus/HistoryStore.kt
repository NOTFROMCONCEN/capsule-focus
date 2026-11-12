package app.capsulefocus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 历史记录仓储：会话结束时由 SessionEngine 落一条记录。
 * JSON 序列化存 SharedPreferences——量级（单次一条、上限几百条）不值得引数据库。
 * 列表按开始时间倒序（新的在前），最多保留 [MAX] 条。
 */
object HistoryStore {

    private const val PREFS = "history"
    private const val KEY = "records"
    private const val MAX = 500

    const val KIND_POMODORO = 0
    const val KIND_BREATHING = 1

    data class Record(
        val startAt: Long,        // 开始时间 epoch ms
        val activeSec: Int,       // 实际进行时长（秒，不含暂停）
        val kind: Int,            // KIND_POMODORO / KIND_BREATHING
        val focusMinutes: Int,    // 番茄钟：单番茄分钟数
        val poms: Int,            // 番茄钟：完成的番茄数
        val restMode: String?,    // 番茄钟：休息方式描述
        val pattern: String?,     // 呼吸：节奏名
        val roundsDone: Int,      // 呼吸：完成轮数
        val roundsTotal: Int,     // 呼吸：目标轮数
        val completed: Boolean,   // 呼吸：是否跑完全部轮数自然完成
        val distractions: Int = 0,   // 番茄钟：专注期间切走应用的次数
        val taskName: String? = null,// 番茄钟：本次专注的待办任务
    )

    fun all(context: Context): MutableList<Record> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return mutableListOf()
        val out = mutableListOf<Record>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Record(
                    startAt = o.getLong("t"),
                    activeSec = o.getInt("dur"),
                    kind = o.getInt("k"),
                    focusMinutes = o.optInt("fm", 0),
                    poms = o.optInt("p", 0),
                    restMode = o.optString("rm").ifEmpty { null },
                    pattern = o.optString("pn").ifEmpty { null },
                    roundsDone = o.optInt("rd", 0),
                    roundsTotal = o.optInt("rt", 0),
                    completed = o.optBoolean("ok", false),
                    distractions = o.optInt("dt", 0),
                    taskName = o.optString("tn").ifEmpty { null },
                )
            }
        } catch (e: Exception) { /* 内容损坏则视为空，下次写入即覆盖 */ }
        return out
    }

    fun add(context: Context, r: Record) {
        val list = all(context)
        list.add(0, r)
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(context, list)
    }

    fun delete(context: Context, startAt: Long) {
        val list = all(context)
        // [FIX] 只删用户长按的那一条（同一毫秒的会话记录不再被连带删除）
        val idx = list.indexOfFirst { it.startAt == startAt }
        if (idx >= 0) {
            list.removeAt(idx)
            save(context, list)
        }
    }

    fun clear(context: Context) = save(context, mutableListOf())

    private fun save(context: Context, list: List<Record>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("t", r.startAt)
                put("dur", r.activeSec)
                put("k", r.kind)
                if (r.kind == KIND_POMODORO) {
                    put("fm", r.focusMinutes)
                    put("p", r.poms)
                    put("rm", r.restMode ?: "")
                    put("dt", r.distractions)
                    put("tn", r.taskName ?: "")
                } else {
                    put("pn", r.pattern ?: "")
                    put("rd", r.roundsDone)
                    put("rt", r.roundsTotal)
                    put("ok", r.completed)
                }
            })
        }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
