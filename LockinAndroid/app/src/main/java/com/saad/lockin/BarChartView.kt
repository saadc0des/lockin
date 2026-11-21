package com.saad.lockin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Small bar chart for the stats page: one bar per day, minutes above the bar. */
class BarChartView(c: Context) : View(c) {

    data class Bar(val top: String, val bottom: String, val value: Int, val today: Boolean)

    private var bars: List<Bar> = emptyList()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6C8CFF.toInt() }
    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF472B6.toInt() }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2F3A.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA3B2.toInt()
        textAlign = Paint.Align.CENTER
    }

    fun setData(b: List<Bar>) {
        bars = b
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return
        val d = resources.displayMetrics.density
        text.textSize = 9f * d
        val w = width.toFloat()
        val h = height.toFloat()
        val labelH = 32f * d
        val topPad = 14f * d
        val chartH = h - labelH - topPad
        val maxV = maxOf(1, bars.maxOf { it.value })
        val slot = w / bars.size
        val bw = slot * 0.62f
        bars.forEachIndexed { i, b ->
            val cx = slot * i + slot / 2f
            val bh = if (b.value > 0) maxOf(3f * d, chartH * b.value / maxV) else 3f * d
            val r = RectF(cx - bw / 2f, topPad + chartH - bh, cx + bw / 2f, topPad + chartH)
            val paint = if (b.value <= 0) emptyPaint else if (b.today) todayPaint else barPaint
            canvas.drawRoundRect(r, 3f * d, 3f * d, paint)
            if (b.value > 0) canvas.drawText(b.value.toString(), cx, r.top - 3f * d, text)
            canvas.drawText(b.top, cx, h - 17f * d, text)
            canvas.drawText(b.bottom, cx, h - 5f * d, text)
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val hPx = (190f * resources.displayMetrics.density).toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthSpec), hPx)
    }
}
