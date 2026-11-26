package com.ajmal.os

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/** A vivid, detailed backdrop so blur, refraction and tint are easy to judge. Drawn once per size. */
class SceneView(ctx: Context) : View(ctx) {
    private var scene: Bitmap? = null
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint()
    var onScene: ((Bitmap) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        val b = guard<Bitmap?>("preview.scene", null) { buildScene(w, h) } ?: return
        scene = b
        onScene?.invoke(b)
    }

    private fun buildScene(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val q = Paint(Paint.ANTI_ALIAS_FLAG)
        q.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF1E3A8A.toInt(), 0xFF9333EA.toInt(), 0xFFF97316.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), q)
        q.shader = null
        val circles = arrayOf(
            floatArrayOf(0.14f, 0.30f, 0.30f), floatArrayOf(0.52f, 0.86f, 0.36f),
            floatArrayOf(0.86f, 0.22f, 0.32f), floatArrayOf(0.36f, 0.06f, 0.20f), floatArrayOf(0.70f, 0.62f, 0.18f)
        )
        val cols = intArrayOf(0xFF22D3EE.toInt(), 0xFFFACC15.toInt(), 0xFF34D399.toInt(), 0xFFF472B6.toInt(), 0xFFFFFFFF.toInt())
        for ((i, k) in circles.withIndex()) {
            q.color = cols[i]
            c.drawCircle(k[0] * w, k[1] * h, k[2] * h, q)
        }
        q.color = Color.argb(70, 255, 255, 255)
        q.strokeWidth = h * 0.05f
        for (i in -2..6) c.drawLine(i * w / 5f, 0f, i * w / 5f + h * 0.9f, h.toFloat(), q)
        q.color = Color.WHITE
        q.typeface = Typeface.DEFAULT_BOLD
        q.textSize = h * 0.42f
        c.drawText("Aa", w * 0.06f, h * 0.62f, q)
        q.textSize = h * 0.16f
        c.drawText("AjmalPad OS", w * 0.58f, h * 0.2f, q)
        return b
    }

    fun release() { scene = null }

    override fun onDraw(c: Canvas) {
        val b = scene ?: return
        c.drawBitmap(b, 0f, 0f, p)
        val d = LauncherApp.get().prefs.int(K.DIM)
        if (d > 0) {
            dimPaint.color = Color.argb((d * 255 / 100).coerceIn(0, 255), 0, 0, 0)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        }
    }
}

/** A row of real app icons drawn with the current icon settings (home style with labels, or dock style). */
class PreviewIcons(ctx: Context, private val dock: Boolean) : View(ctx) {
    private val app = LauncherApp.get()
    private val dp = ctx.resources.displayMetrics.density
    private val src = IconSource(this)
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val path = Path()
    private var apps: List<AppInfo> = emptyList()

    fun refresh() { apps = pick(); invalidate() }

    private fun pick(): List<AppInfo> {
        val m = app.model
        val hidden = m.hiddenApps
        val vis = m.apps.filter { !hidden.contains(it.key) }
        val out = ArrayList<AppInfo>()
        for (id in listOf("phone", "messages", "browser", "camera", "music", "photos", "settings", "clock")) {
            val a = vis.firstOrNull { IosIcons.match(it.pkg, it.rawLabel)?.id == id && !out.contains(it) }
            if (a != null) out.add(a)
            if (out.size == 5) break
        }
        for (a in vis) { if (out.size >= 5) break; if (!out.contains(a)) out.add(a) }
        return out
    }

    override fun onDraw(c: Canvas) {
        try { drawRow(c) } catch (t: Throwable) { Diag.e("preview.icons", t) }
    }

    private fun drawRow(c: Canvas) {
        if (apps.isEmpty()) apps = pick()
        val p = app.prefs
        val cells = if (dock) 6 else 5
        val cw = width / cells.toFloat()
        val labels = !dock && p.bool(K.LABELS)
        val labelPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, p.int(K.LABEL_SIZE).toFloat(), resources.displayMetrics)
        val labelH = if (labels) labelPx * 1.5f else 0f
        var want = (if (dock) p.int(K.DOCK_ICON) else p.int(K.ICON_SIZE)) * dp
        if (!dock && p.bool(K.LARGE_ICONS)) want *= 1.25f
        val s = max(16f * dp, min(want, min(cw * 0.8f, (height - labelH) * 0.78f))).toInt()
        val pad = IconCache.padFor(s)
        val tot = s + 2 * pad
        val y0 = ((height - tot - labelH) / 2f).coerceAtLeast(0f)
        val fg = if (p.int(K.LABEL_COLOR) == 2) Color.BLACK else Color.WHITE
        for ((i, a) in apps.withIndex()) {
            val cx = (i + 0.5f) * cw
            val b = src.get(a, s)
            if (b != null) {
                c.drawBitmap(b, Math.round(cx - tot / 2f).toFloat(), Math.round(y0).toFloat(), bp)
            } else {
                bg.style = Paint.Style.FILL
                bg.color = Color.argb(60, 255, 255, 255)
                rf.set(cx - s / 2f, y0 + pad, cx + s / 2f, y0 + pad + s)
                c.drawPath(Squircle.path(rf, s * p.int(K.ICON_RADIUS) / 100f, path), bg)
            }
            if (labels) {
                tp.textSize = labelPx
                tp.textAlign = Paint.Align.CENTER
                tp.color = fg
                if (fg == Color.WHITE) tp.setShadowLayer(2f * dp, 0f, dp, 0x80000000.toInt()) else tp.clearShadowLayer()
                val t = TextUtils.ellipsize(a.label, tp, cw - 4f * dp, TextUtils.TruncateAt.END).toString()
                c.drawText(t, cx, y0 + tot + labelPx * 1.05f, tp)
            }
        }
        if (dock) {
            // Where the App Library button sits.
            val cx = 5.5f * cw
            rf.set(cx - s / 2f, y0 + pad, cx + s / 2f, y0 + pad + s)
            bg.style = Paint.Style.FILL
            bg.color = Color.argb(110, 255, 255, 255)
            c.drawPath(Squircle.path(rf, s * p.int(K.ICON_RADIUS) / 100f, path), bg)
            bg.color = Color.argb(120, 255, 255, 255)
            val g = s * 0.07f
            val m = (s - 3 * g) / 2f
            for (k in 0 until 4) {
                val mx = rf.left + g + (k % 2) * (m + g)
                val my = rf.top + g + (k / 2) * (m + g)
                val r2 = RectF(mx, my, mx + m, my + m)
                c.drawRoundRect(r2, m * 0.25f, m * 0.25f, bg)
            }
        }
    }
}

/**
 * Live preview pinned above the settings list: a mini home screen on a vivid backdrop with a real glass dock
 * and real icons. Every glass / icon / label / dock / dimming / animation setting shows up here while you move
 * the slider. Tap it to play the App Library genie at the current speed.
 */
class GlassPreviewCard(ctx: Context) : FrameLayout(ctx) {
    private val app = LauncherApp.get()
    private val dp = ctx.resources.displayMetrics.density
    private val content = FrameLayout(ctx)
    private val sceneView = SceneView(ctx)
    private val renderer = GlassRenderer(app, true)
    private val homeRow = PreviewIcons(ctx, false)
    private val dockRow = PreviewIcons(ctx, true)
    private val slab = GlassFrame(ctx)
    private val caption = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRun = Runnable { refreshNow() }
    private var genie: GenieView? = null
    private var genieBmp: Bitmap? = null

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 20f * dp) }
        }
        addView(content, LayoutParams(-1, -1))
        content.addView(sceneView, LayoutParams(-1, -1))
        content.addView(homeRow, LayoutParams(-1, (100 * dp).toInt(), Gravity.TOP))
        slab.renderer = renderer
        slab.origin = sceneView
        slab.radiusDp = 28f
        slab.squish = true
        slab.addView(dockRow, LayoutParams(-1, -1))
        val lp = LayoutParams(0, (74 * dp).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = (10 * dp).toInt()
        content.addView(slab, lp)
        sceneView.onScene = { b -> renderer.setScene(b, max(app.glass.screenW, app.glass.screenH).coerceAtLeast(1)) }
        isClickable = true
        setOnClickListener { playGenie() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        (slab.layoutParams as LayoutParams).width = (w * 0.92f).toInt()
        slab.requestLayout()
        refreshNow()
    }

    /** Coalesced: many slider ticks cause one redraw. */
    fun refresh() {
        handler.removeCallbacks(refreshRun)
        handler.postDelayed(refreshRun, 50)
    }

    private fun refreshNow() {
        guardRun("preview.refresh") {
            val p = app.prefs
            slab.strength = 0.4f + p.int(K.DOCK_GLASS) / 100f
            slab.drawBg = p.bool(K.DOCK_BG)
            renderer.reload()
            homeRow.refresh()
            dockRow.refresh()
            sceneView.invalidate()
            slab.invalidate()
            invalidate()
        }
    }

    fun playGenie() {
        if (genie != null || width < 10 || height < 10 || Safe.mode || app.prefs.bool(K.REDUCE_MOTION)) return
        val bmp = guard<Bitmap?>("preview.genie", null) {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { content.draw(Canvas(it)) }
        } ?: return
        val s = 40f * dp
        val src = RectF(width - s - 16f * dp, height - s - 12f * dp, width - 16f * dp, height - 12f * dp)
        val gv = GenieView(context, bmp, src)
        genie = gv
        genieBmp = bmp
        content.visibility = View.INVISIBLE
        addView(gv, LayoutParams(-1, -1))
        val speed = max(20, app.prefs.int(K.GENIE_SPEED))
        gv.animateTo(1f, (440 * 100f / speed).toLong()) {
            gv.postDelayed({ gv.animateTo(0f, (340 * 100f / speed).toLong()) { finishGenie() } }, 380)
        }
    }

    private fun finishGenie() {
        genie?.let { removeView(it) }
        genie = null
        genieBmp?.recycle()
        genieBmp = null
        content.visibility = View.VISIBLE
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (genie == null) {
            caption.textSize = 11f * dp
            caption.color = Color.argb(200, 255, 255, 255)
            caption.setShadowLayer(2f * dp, 0f, dp, 0x99000000.toInt())
            c.drawText("Live preview · tap to play the genie", 12f * dp, height - 8f * dp, caption)
        }
    }

    fun release() {
        handler.removeCallbacks(refreshRun)
        finishGenie()
        sceneView.release()
        renderer.release()
    }
}
