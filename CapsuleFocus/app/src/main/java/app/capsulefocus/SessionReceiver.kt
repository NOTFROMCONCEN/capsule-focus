package app.capsulefocus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知上的 暂停/继续/跳过/结束 按钮 */
class SessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SessionEngine.ACTION_PAUSE ->
                if (!SessionEngine.paused) SessionEngine.togglePause(context)
            SessionEngine.ACTION_RESUME ->
                if (SessionEngine.paused) SessionEngine.togglePause(context)
            SessionEngine.ACTION_SKIP -> SessionEngine.skip(context)
            SessionEngine.ACTION_STOP -> SessionEngine.stop(context)
        }
    }
}
