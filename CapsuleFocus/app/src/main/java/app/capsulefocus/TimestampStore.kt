package app.capsulefocus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 时间戳仓储：现实世界操作（如网络设备插拔）的时间+备注随手记。
 * JSON 序列化存 SharedPreferences，与 [HistoryStore] 同款做法；
 * 列表按时间倒序（新的在前），最多保留 [MAX] 条。
 */
object TimestampStore {

    private const val PREFS = "timestamps"
    private const val KEY = "records"
    private const val MAX = 500

    data class Timestamp(
        val at: Long,     // 事件发生时间 epoch ms
        val note: String, // 备注，允许为空
    )

    fun all(context: Context): MutableList<Timestamp> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return mutableListOf()
        val out = mutableListOf<Timestamp>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Timestamp(o.getLong("t"), o.optString("n"))
            }
        } catch (e: Exception) { /* 内容损坏则视为空，下次写入即覆盖 */ }
        return out
    }

    fun add(context: Context, note: String, at: Long = System.currentTimeMillis()) {
        val list = all(context)
        list.add(0, Timestamp(at, note.trim()))
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(context, list)
    }

    /** 按旧时间定位（第一条匹配），改时间与备注 */
    fun update(context: Context, oldAt: Long, newAt: Long, note: String) {
        val list = all(context)
        val idx = list.indexOfFirst { it.at == oldAt }
        if (idx >= 0) {
            list[idx] = Timestamp(newAt, note.trim())
            // [FIX] 改了发生时间要重排，否则列表与"时间正序导出"顺序错乱
            list.sortByDescending { it.at }
            save(context, list)
        }
    }

    fun delete(context: Context, at: Long) {
        val list = all(context)
        // [FIX] 只删第一条匹配，避免同一毫秒的多条记录被一起删掉
        val idx = list.indexOfFirst { it.at == at }
        if (idx >= 0) {
            list.removeAt(idx)
            save(context, list)
        }
    }

    fun clear(context: Context) = save(context, mutableListOf())

    private fun save(context: Context, list: List<Timestamp>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("t", r.at)
                put("n", r.note)
            })
        }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
