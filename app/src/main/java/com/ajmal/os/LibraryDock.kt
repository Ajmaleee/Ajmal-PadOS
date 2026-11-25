package com.ajmal.os

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * The App Library button at the right end of the dock (like the iPad): a glass squircle holding four mini
 * category clusters. Tapping it opens the library with a genie animation from this icon.
 */
class LibraryDockIcon(ctx: Context, private val h: HomeActivity) : View(ctx) {
    private val app = LauncherApp.get()
    private val dp = ctx.resources.displayMetrics.density
    private val src = IconSource(this)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rf = RectF()
    private val rf2 = RectF()
    private val path = Path()
    private var clusters: List<List<AppInfo>> = emptyList()
    private var press = 1f
    private var pressAnim: ValueAnimator? = null

    /** Press feedback without any spring, so nothing overshoots before the genie starts. */
    private fun pressTo(target: Float, ms: Long) {
        pressAnim?.cancel()
        val a = ValueAnimator.ofFloat(press, target)
        a.duration = ms
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener { press = it.animatedValue as Float; invalidate() }
        pressAnim = a
        a.start()
    }

    init {
        isClickable = true
        setOnClickListener {
            pressAnim?.cancel()
            press = 1f
            invalidate()
            h.openLibrary(this)
        }
    }

    fun refresh() {
        val m = h.model
        val hidden = m.hiddenApps
        val vis = m.apps.filter { !hidden.contains(it.key) }
        val byUse = compareByDescending<AppInfo> { m.usage[it.key] ?: 0 }.thenBy { it.lower }
        val out = ArrayList<List<AppInfo>>()
        for (cat in m.categories()) {
            val l = vis.filter { m.categoryOf(it) == cat }.sortedWith(byUse).take(4)
            if (l.isNotEmpty()) out.add(l)
            if (out.size == 4) break
        }
        clusters = out
        invalidate()
    }

    private fun tiny(c: Canvas, a: AppInfo, x: Float, y: Float, box: Float) {
        val sz = max(8, box.toInt())
        val b = src.get(a, sz) ?: return
        val pd = IconCache.padFor(sz)
        c.drawBitmap(b, x + (box - sz) / 2f - pd, y + (box - sz) / 2f - pd, bp)
    }

    override fun onDraw(c: Canvas) {
        val s = min(h.dockIconPx.toFloat(), min(width, height).toFloat())
        if (s <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        // Divider between the dock apps and the library button.
        bg.style = Paint.Style.FILL
        bg.shader = null
        bg.color = Color.argb(70, 255, 255, 255)
        c.drawRect(0f, height * 0.24f, max(1f, dp), height * 0.76f, bg)

        c.save()
        c.scale(press, press, cx, cy)
        rf.set(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f)
        val r = s * app.prefs.int(K.ICON_RADIUS) / 100f
        bg.shader = LinearGradient(0f, rf.top, 0f, rf.bottom, Color.argb(130, 255, 255, 255), Color.argb(64, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawPath(Squircle.path(rf, r, path), bg)
        bg.shader = null
        bg.style = Paint.Style.STROKE
        bg.strokeWidth = max(1f, s * 0.014f)
        bg.color = Color.argb(120, 255, 255, 255)
        c.drawPath(Squircle.path(rf, r, path), bg)
        bg.style = Paint.Style.FILL

        val pad = s * 0.1f
        val gap = s * 0.06f
        val mini = (s - 2f * pad - gap) / 2f
        for ((k, list) in clusters.withIndex()) {
            if (k >= 4) break
            val mx = rf.left + pad + (k % 2) * (mini + gap)
            val my = rf.top + pad + (k / 2) * (mini + gap)
            rf2.set(mx, my, mx + mini, my + mini)
            bg.color = Color.argb(85, 255, 255, 255)
            c.drawPath(Squircle.path(rf2, mini * 0.27f, path), bg)
            val ip = mini * 0.12f
            val ig = mini * 0.08f
            val tiny = (mini - 2f * ip - ig) / 2f
            for ((q, a) in list.withIndex()) {
                if (q >= 4) break
                tiny(c, a, mx + ip + (q % 2) * (tiny + ig), my + ip + (q / 2) * (tiny + ig), tiny)
            }
        }
        c.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressTo(0.94f, 70)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressTo(1f, 90)
        }
        return super.onTouchEvent(e)
    }
}
