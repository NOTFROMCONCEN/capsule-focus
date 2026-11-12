package app.capsulefocus

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * 每日番茄柱状图（自绘，无图表库依赖）：
 * 末位为今天（品牌红高亮），零值画短桩，目标值画虚线参考线。
 */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dim)
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val goalTextPaint = Paint(textPaint).apply {
        textAlign = Paint.Align.RIGHT
        color = context.getColor(R.color.dim)
    }
    private val goalLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dim)
        strokeWidth = dp(1.2f)
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }

    // [PERF] 颜色在构造时解析一次，onDraw 不再每帧查资源
    private val todayColor = context.getColor(R.color.primary)
    private val pastColor = (todayColor and 0x00FFFFFF) or 0x73000000
    private val zeroColor = (context.getColor(R.color.outline) and 0x00FFFFFF) or 0x40000000

    private var values: List<Int> = emptyList()
    private var labels: List<String> = emptyList()
    private var goal = 0

    fun set(values: List<Int>, labels: List<String>, goal: Int) {
        this.values = values
        this.labels = labels
        this.goal = goal
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val labelSpace = dp(15f)
        val chartTop = dp(8f)
        val chartBottom = height - labelSpace
        val chartH = max(chartBottom - chartTop, dp(6f))
        val maxV = max(max(values.max(), goal), 1)
        val slot = width.toFloat() / values.size
        val barW = slot * 0.52f

        values.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2
            val h = if (v == 0) dp(2f) else chartH * v / maxV
            barPaint.color = when {
                v == 0 -> zeroColor
                i == values.size - 1 -> todayColor
                else -> pastColor
            }
            canvas.drawRoundRect(
                cx - barW / 2, chartBottom - h, cx + barW / 2, chartBottom,
                barW / 4, barW / 4, barPaint)
            labels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                canvas.drawText(it, cx, height - dp(4f), textPaint)
            }
        }

        if (goal > 0) {
            val gy = chartBottom - chartH * goal / maxV
            canvas.drawLine(0f, gy, width.toFloat(), gy, goalLinePaint)
            canvas.drawText("目标 $goal", width.toFloat(), gy - dp(3f), goalTextPaint)
        }
    }
}
