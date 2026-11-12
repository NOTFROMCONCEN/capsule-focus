package app.capsulefocus

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 下拉快捷设置磁贴「开始专注」：一键以上次配置开始番茄钟。
 * 已有会话在跑或缺通知权限时，退化为打开应用处理。
 */
class FocusTileService : TileService() {

    override fun onStartListening() {
        // [FIX] 磁贴反映会话状态，下拉即可看出是否已有会话在跑
        qsTile?.apply {
            state = if (SessionEngine.session != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val app = applicationContext
        if (SessionEngine.session != null) {
            openApp()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            openApp()   // 进应用授权，授权回调后自动开始
            return
        }
        // [FIX] 与 MainActivity 共用同一份配置规则：
        // 自定义专注时长 / 自定义呼吸节奏 / 长休息配置不再被磁贴静默丢弃
        SessionEngine.start(app, SessionEngine.pomodoroFromPrefs(app))
        openApp()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(
                this, 3, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
