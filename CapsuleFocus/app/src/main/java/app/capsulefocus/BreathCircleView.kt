package app.capsulefocus

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min

/**
 * 呼吸节奏圆：随阶段胀缩——吸气放大、屏息停住、呼气缩小。
 * 动画只在 阶段切换/暂停状态变化 时重建，引擎的秒级 tick 不打断插值，
 * 暂停时冻结在当前大小，恢复后从当前大小续播剩余时长。
 */
class BreathCircleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var fraction = 0f            // 0=收拢 1=胀满
    private var animator: ValueAnimator? = null
    private var lastKey: String? = null
    private var lastPaused = false

    fun breathe(phase: SessionEngine.Phase?, remaining: Int, paused: Boolean, key: String?) {
        if (phase == null || key == null) {
            animator?.cancel(); animator = null
            fraction = 0f
            lastKey = null
            invalidate()
            return
        }
        stroke.color = phase.color
        fill.color = (phase.color and 0x00FFFFFF) or 0x2E000000

        val structural = key != lastKey || paused != lastPaused
        lastKey = key
        lastPaused = paused

        if (paused) {
            animator?.cancel(); animator = null   // 冻结在当前大小
            invalidate()
            return
        }
        if (!structural && animator != null) return   // 阶段内继续播

        if (phase.kind == SessionEngine.Kind.HOLD_IN ||
            phase.kind == SessionEngine.Kind.HOLD_OUT) {
            // [PERF] 屏息时圆不变：不再让一个 ofFloat(x, x) 动画整段每帧空转重绘
            animator?.cancel(); animator = null
            invalidate()
            return
        }
        val target = if (phase.kind == SessionEngine.Kind.INHALE) 1f else 0f
        animator?.cancel()
        animator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = remaining * 1000L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        // [FIX] 重置去重状态，View 重新 attach 后动画能正常重启
        animator = null
        lastKey = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val maxR = min(width, height) / 2f * 0.92f
        val r = maxR * (0.55f + 0.45f * fraction)
        canvas.drawCircle(width / 2f, height / 2f, r, fill)
        canvas.drawCircle(width / 2f, height / 2f, r, stroke)
    }
}
