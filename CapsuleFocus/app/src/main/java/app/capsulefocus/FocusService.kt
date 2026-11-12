package app.capsulefocus

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 会话前台服务：把引擎构建的通知挂为前台通知（同一条即灵动胶囊通知），
 * dataSync 类型与 Kelivo 一致，避免会话期间进程被系统回收。
 */
class FocusService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // [FIX] 引擎已无会话（服务被异常拉起）时，不要挂一条无法清除的常驻通知
        if (SessionEngine.session == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    SessionEngine.ONGOING_ID,
                    SessionEngine.buildNotification(this),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(SessionEngine.ONGOING_ID, SessionEngine.buildNotification(this))
            }
            SessionEngine.serviceStarted(this)
        } catch (e: RuntimeException) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    fun shutdown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        SessionEngine.serviceStopped(this)
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ 对 dataSync 前台服务有 6 小时上限
        SessionEngine.stop(this)
        shutdown()
    }
}
