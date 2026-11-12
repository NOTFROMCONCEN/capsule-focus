package app.capsulefocus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 待办清单：JSON 存 SharedPreferences；可附带任务名开始番茄钟 */
object TodoStore {

    private const val PREFS = "cfg"
    private const val KEY = "todos"

    data class Todo(val text: String, val done: Boolean)

    fun all(context: Context): MutableList<Todo> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return mutableListOf()
        val out = mutableListOf<Todo>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Todo(o.getString("t"), o.optBoolean("d", false))
            }
        } catch (e: Exception) { /* 损坏视为空 */ }
        return out
    }

    fun add(context: Context, text: String) {
        val list = all(context)
        list.add(0, Todo(text.trim(), false))
        save(context, list)
    }

    fun toggle(context: Context, index: Int) = mutate(context, index) { it.copy(done = !it.done) }

    fun delete(context: Context, index: Int) {
        val list = all(context)
        if (index in list.indices) {
            list.removeAt(index)
            save(context, list)
        }
    }

    private fun mutate(context: Context, index: Int, op: (Todo) -> Todo) {
        val list = all(context)
        if (index in list.indices) {
            list[index] = op(list[index])
            save(context, list)
        }
    }

    private fun save(context: Context, list: List<Todo>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().apply { put("t", it.text); put("d", it.done) }) }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
