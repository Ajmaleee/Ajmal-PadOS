package com.ajmal.os

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * Genie effect: a snapshot of the overlay is warped through a mesh so it pours out of (or is sucked back
 * into) the source icon. t = 0 is the icon, t = 1 is the full screen. One bitmap mesh draw per frame.
 */
class GenieView(ctx: Context, private val bmp: Bitmap, private val src: RectF) : View(ctx) {
    var t = 0f
        set(v) { field = v; invalidate() }
    private val cols = 10
    private val rows = 24
    private val verts = FloatArray((cols + 1) * (rows + 1) * 2)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var anim: ValueAnimator? = null

    private fun smooth(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    override fun onDraw(c: Canvas) {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val sx = src.centerX()
        val sy = src.centerY()
        val iw = src.width()
        val k = min(1f, t * 1.1f)
        val topY = sy - sy * (1f - (1f - k) * (1f - k) * (1f - k))
        val botY = src.bottom + (h - src.bottom) * smooth((t - 0.3f) / 0.7f)
        var n = 0
        for (i in 0..rows) {
            val v = i / rows.toFloat()
            val s = smooth((t - 0.55f * v) / 0.45f)
            val width = iw + (w - iw) * s
            val cx = sx + (w / 2f - sx) * s
            val y = topY + (botY - topY) * v
            for (j in 0..cols) {
                verts[n++] = cx + (j / cols.toFloat() - 0.5f) * width
                verts[n++] = y
            }
        }
        paint.alpha = (255f * min(1f, t * 4f)).toInt()
        c.drawBitmapMesh(bmp, cols, rows, verts, 0, null, 0, paint)
    }

    fun animateTo(target: Float, ms: Long, end: () -> Unit) {
        anim?.let { it.removeAllListeners(); it.cancel() }
        val a = ValueAnimator.ofFloat(t, target)
        a.duration = ms
        a.interpolator = if (target > t) DecelerateInterpolator(1.3f) else AccelerateInterpolator(1.1f)
        a.addUpdateListener { t = it.animatedValue as Float }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { end() }
        })
        anim = a
        a.start()
    }
}
