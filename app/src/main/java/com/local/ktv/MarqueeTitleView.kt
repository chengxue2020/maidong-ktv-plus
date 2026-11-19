package com.local.ktv

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.TextView

/**
 * 歌曲列表用的歌名视图：文本未超出可用宽度时静态完整显示；
 * 只要超宽就左右循环滚动(前后滚动)，不依赖焦点——触摸屏没有焦点行概念，
 * 这样每一首长歌名都能看到完整内容。
 * 复用双份文本共用一个移动轨道的绘制思路，相位回绕时可见文字不跳变。
 */
class MarqueeTitleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : TextView(context, attrs) {

    private var phase = 0f
    private var lastFrame = 0L
    private var scrollStartAt = 0L
    private var measuredText = ""
    private var measuredSize = 0f
    private var textWidth = 0f

    private val gap get() = 32f * resources.displayMetrics.density
    private val speed get() = 20f * resources.displayMetrics.density // px/s，比字幕略慢，观感柔和
    private val startDelayMs = 1500L // 先静止显示歌名开头 1.5 秒，避免还没看清开头就滚动

    init {
        setSingleLine(true)
        // 超宽时由本类自行滚动绘制(不调用 super，ellipsize 不参与)；
        // 极端情况(如不可见被系统绘制)仍保留省略号，不会出现无提示硬截断。
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        includeFontPadding = false
    }

    override fun setText(text: CharSequence?, type: BufferType) {
        super.setText(text, type)
        lastFrame = 0L
        phase = 0f
        scrollStartAt = 0L
    }

    override fun onDraw(canvas: Canvas) {
        val value = text?.toString().orEmpty()
        val available = width - paddingLeft - paddingRight
        if (value.isEmpty() || available <= 0) {
            super.onDraw(canvas)
            return
        }
        if (value != measuredText || paint.textSize != measuredSize) {
            measuredText = value
            measuredSize = paint.textSize
            textWidth = paint.measureText(value)
        }
        if (textWidth <= available) {
            // 未超宽：静态完整显示。
            phase = 0f
            lastFrame = 0L
            scrollStartAt = 0L
            super.onDraw(canvas)
            return
        }

        // 超宽：先静止显示歌名开头 1.5 秒，再左右循环滚动。
        val metrics = paint.fontMetrics
        val baseline = paddingTop + (height - paddingTop - paddingBottom - metrics.bottom - metrics.top) / 2f
        val cycle = textWidth + gap
        val now = SystemClock.uptimeMillis()
        if (scrollStartAt == 0L) scrollStartAt = now + startDelayMs
        if (now < scrollStartAt) {
            phase = 0f
            lastFrame = 0L
            drawTrack(canvas, value, baseline)
            // 延迟期间只定时触发一帧，不必持续逐帧重绘，避免整屏离屏行耗电。
            postInvalidateDelayed(scrollStartAt - now)
            return
        }
        if (lastFrame != 0L) {
            phase += (now - lastFrame).coerceAtMost(80L) * speed / 1000f
            if (phase >= cycle) phase %= cycle
        }
        lastFrame = now
        drawTrack(canvas, value, baseline)
        // 仅在可见时驱动下一帧，离屏/停止显示时自动停下，避免整屏离屏行持续耗电。
        if (isShown && windowVisibility == View.VISIBLE) postInvalidateOnAnimation()
    }

    private fun drawTrack(canvas: Canvas, value: String, baseline: Float) {
        paint.color = currentTextColor
        val saved = canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        var x = paddingLeft - phase
        while (x < width - paddingRight) {
            canvas.drawText(value, x, baseline, paint)
            x += textWidth + gap
        }
        canvas.restoreToCount(saved)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrame = 0L
        scrollStartAt = 0L
        postInvalidateOnAnimation()
    }
}
