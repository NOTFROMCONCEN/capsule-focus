package com.demo.livecapsule

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    private val listener = object : TaskNotifier.Listener {
        override fun onStateChanged() = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.log)

        findViewById<Button>(R.id.start).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                TaskNotifier.start(this)
            }
        }
        findViewById<Button>(R.id.cancel).setOnClickListener { TaskNotifier.cancel(this) }
        findViewById<Button>(R.id.openLiveSettings).setOnClickListener { openSettingsPage(true) }
        findViewById<Button>(R.id.openNotifSettings).setOnClickListener { openSettingsPage(false) }
    }

    /** [FIX] 非 API36 / 厂商 ROM 没有该设置页时逐级降级，避免 ActivityNotFoundException */
    private fun openSettingsPage(promoted: Boolean) {
        val fallback = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        // 对应荣耀设置里的"实时通知 / 灵动岛"开关（Kelivo 同款深链）
        val target = if (promoted)
            Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS")
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .takeIf { it.resolveActivity(packageManager) != null } ?: fallback
        else fallback
        try {
            startActivity(target)
        } catch (_: Exception) {
            try { startActivity(fallback) } catch (_: Exception) { }
        }
    }

    override fun onStart() {
        super.onStart()
        TaskNotifier.addListener(listener)
        refresh()
    }

    override fun onStop() {
        super.onStop()
        TaskNotifier.removeListener(listener)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            TaskNotifier.start(this)
        }
    }

    private fun refresh() {
        val nm = getSystemService(NotificationManager::class.java)
        statusView.text = buildString {
            appendLine("系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            append("通知权限：")
            appendLine(if (nm?.areNotificationsEnabled() == true) "已允许" else "未允许")
            if (Build.VERSION.SDK_INT >= 36) {
                append("实时通知资格 canPostPromotedNotifications：")
                appendLine(
                    if (TaskNotifier.canPromote(this@MainActivity)) "已允许"
                    else "未开启（点下方按钮去系统设置）"
                )
                append("最近一次发布 FLAG_PROMOTED_ONGOING：")
                appendLine(
                    when (TaskNotifier.promoted) {
                        true -> "已提升为胶囊 ✔"
                        false -> "未提升 ✘"
                        null -> "尚未发布"
                    }
                )
            } else {
                appendLine("本机 API < 36：不支持实时通知，只会显示普通常驻通知")
            }
            append("任务状态：")
            append(if (TaskNotifier.running) "运行中 ${TaskNotifier.progress}%" else "空闲")
        }
        logView.text = TaskNotifier.logText()
    }
}
