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
import android.view.animation.PathInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * Genie effect: a snapshot of the overlay is warped through a mesh so it pours out of (or is sucked back
 * into) the source icon. t = 0 is the icon, t = 1 is the full screen. One bitmap mesh draw per frame.
 */
class GenieView(ctx: Context, private val bmp: Bitmap, private val src: RectF) : View(ctx) {
    var t = 0f
        set(v) { field = v; invalidate() }
    private val cols = 10
    private val rows = 28
    private val verts = FloatArray((cols + 1) * (rows + 1) * 2)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var anim: ValueAnimator? = null

    private fun smooth(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    override fun onDraw(c: Canvas) {
        if (bmp.isRecycled) return
        try { drawMesh(c) } catch (t: Throwable) { Diag.e("genie.draw", t) }
    }

    private fun drawMesh(c: Canvas) {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val sx = src.centerX()
        val base = src.centerY()                 // everything starts at the middle of the library button
        val tw = max(6f, src.width() * 0.2f)     // the neck at the button is only a fraction of its width
        val top = base * (1f - smooth(t / 0.75f))
        val bot = base + (h - base) * smooth((t - 0.25f) / 0.75f)
        val delay = 0.6f
        var n = 0
        for (i in 0..rows) {
            val v = i / rows.toFloat()
            val s = smooth((t - delay * v) / (1f - delay))   // rows near the button open last
            val width = tw + (w - tw) * s
            val cx = sx + (w / 2f - sx) * s
            val y = top + (bot - top) * v
            for (j in 0..cols) {
                verts[n++] = cx + (j / cols.toFloat() - 0.5f) * width
                verts[n++] = y
            }
        }
        paint.alpha = (255f * smooth(t / 0.1f)).toInt()   // only the very first / last instant fades
        c.drawBitmapMesh(bmp, cols, rows, verts, 0, null, 0, paint)
    }

    fun animateTo(target: Float, ms: Long, end: () -> Unit) {
        anim?.let { it.removeAllListeners(); it.cancel() }
        val a = ValueAnimator.ofFloat(t, target)
        a.duration = ms
        // One strong ease-out both ways: it starts at full speed (no wind-up, no overshoot) and lands softly.
        a.interpolator = PathInterpolator(0.15f, 0.85f, 0.2f, 1f)
        a.addUpdateListener { t = it.animatedValue as Float }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { end() }
        })
        anim = a
        a.start()
    }
}
