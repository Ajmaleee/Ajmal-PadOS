package com.ajmal.os

import android.graphics.*
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Central material + physics constants for the water-filled "Liquid Glass" look.
 * "u" is the layout unit: 1u = 1/100 of the element height, so every optical effect scales with the element.
 */
object LiquidCfg {
    const val TRANSPARENCY = 0.99f            // fill alpha = (1 - transparency) * 3
    const val REFRACTION = 62f                // edge sampling shift in u
    const val DISPERSION = 0.09f              // R = 1.09x, G = 1.00x, B = 0.91x
    const val EDGE_BAND = 0.41f               // x element height (spec range 0.38 - 0.44)
    const val MAG = 1.07f                     // centre lens magnification
    const val RIPPLE_AMP = 0.05f
    const val MARGIN_U = 76f                  // bleed: the backdrop bitmap is clamped, so deformed edges always sample real pixels
    const val SPRING_K = 162f                 // spec 155 - 170
    const val SPRING_C = 10.5f                // spec 9 - 12 (damping ratio ~0.41: overshoots and wobbles)
    const val GX = 28                         // mesh columns
    const val GY = 14                         // mesh rows
    val OFF_WHITE = intArrayOf(255, 244, 232)
}

/**
 * Water-balloon body: press compresses (and jiggles on impact), dragging stretches asymptotically,
 * volume is conserved (Poisson coupling). Runs its own frame loop ONLY while something moves.
 */
class LiquidBody(private val host: View, private val dp: Float) : Choreographer.FrameCallback {
    // state, velocity, target
    var q = 0f; private var vq = 0f; private var qT = 0f          // press / compression
    var ex = 0f; private var vex = 0f; private var exT = 0f       // horizontal stretch
    var ey = 0f; private var vey = 0f; private var eyT = 0f       // vertical stretch
    var ox = 0f; private var vox = 0f; private var oxT = 0f       // translation toward pull (px)
    var oy = 0f; private var voy = 0f; private var oyT = 0f

    /** false = press-only squish (no stretch from dragging). */
    var pull = true
    var running = false; private set
    var energy = 0f; private set
    var phase = 0f; private set
    var touchU = 0f; private set      // touch position relative to centre, -0.5..0.5
    var touchV = 0f; private set

    private var downX = 0f
    private var downY = 0f
    private var last = 0L
    private val m = 0.28f             // rubber limit (fraction of size)

    private fun rubber(u: Float): Float = if (u < 0f) -m * (1f - exp(u / m)) else m * (1f - exp(-u / m))

    fun cancel() {
        if (running) Choreographer.getInstance().removeFrameCallback(this)
        running = false
        q = 0f; ex = 0f; ey = 0f; ox = 0f; oy = 0f
        vq = 0f; vex = 0f; vey = 0f; vox = 0f; voy = 0f
        qT = 0f; exT = 0f; eyT = 0f; oxT = 0f; oyT = 0f
        energy = 0f
        resetTilt()
    }

    /** Observes touches without consuming them. */
    fun onTouch(e: MotionEvent) {
        if (LauncherApp.get().prefs.bool(K.REDUCE_MOTION) || Safe.mode) return
        val w = max(1f, host.width.toFloat())
        val h = max(1f, host.height.toFloat())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                qT = 1f; vq += 5.5f                       // impact impulse -> squish + jiggle
                touchU = e.x / w - 0.5f; touchV = e.y / h - 0.5f
                start()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                touchU = (e.x / w - 0.5f).coerceIn(-0.5f, 0.5f); touchV = (e.y / h - 0.5f).coerceIn(-0.5f, 0.5f)
                val sl = 6f * dp
                if (pull && hypot(dx, dy) > sl) {
                    exT = rubber(abs(dx) / w); eyT = rubber(abs(dy) / h)
                    oxT = rubber(dx / w) * w * 0.35f; oyT = rubber(dy / h) * h * 0.35f
                    qT = 0.6f
                    start()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                qT = 0f; exT = 0f; eyT = 0f; oxT = 0f; oyT = 0f
                vq -= 2.5f                                // rebound kick
                start()
            }
        }
    }

    private fun start() {
        if (!running) {
            running = true
            last = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(t: Long) {
        if (!running) return
        val dt = if (last == 0L) 0.0167f else min((t - last) / 1e9f, 0.032f)
        last = t
        try { step(dt) } catch (e: Throwable) { Diag.e("liquid.step", e); cancel(); return }
        host.invalidate()
        (host.parent as? View)?.invalidate()   // the body can bulge past its own bounds
        if (running) Choreographer.getInstance().postFrameCallback(this)
    }

    private fun step(dt: Float) {
        val k = LiquidCfg.SPRING_K
        val c = LiquidCfg.SPRING_C
        var left = dt
        while (left > 0f) {
            val h = min(left, 0.004f)
            vq += (-k * (q - qT) - c * vq) * h; q += vq * h
            vex += (-k * (ex - exT) - c * vex) * h; ex += vex * h
            vey += (-k * (ey - eyT) - c * vey) * h; ey += vey * h
            vox += (-k * (ox - oxT) - c * vox) * h; ox += vox * h
            voy += (-k * (oy - oyT) - c * voy) * h; oy += voy * h
            left -= h
        }
        val w = max(1f, host.width.toFloat())
        val hgt = max(1f, host.height.toFloat())
        energy = (abs(vq) * 0.12f + abs(vex) * 0.5f + abs(vey) * 0.5f + abs(vox) / w * 0.5f + abs(voy) / hgt * 0.5f).coerceAtMost(1.4f)
        phase += dt * (3f + energy * 9f)
        applyTilt()
        val settled = abs(q - qT) < 0.002f && abs(vq) < 0.02f &&
            abs(ex - exT) < 0.002f && abs(vex) < 0.02f && abs(ey - eyT) < 0.002f && abs(vey) < 0.02f &&
            abs(ox - oxT) < 0.2f && abs(vox) < 2f && abs(oy - oyT) < 0.2f && abs(voy) < 2f
        if (settled && qT == 0f) {
            q = 0f; ex = 0f; ey = 0f; ox = 0f; oy = 0f; energy = 0f
            running = false
            resetTilt()
        }
    }

    // perspective tilt toward the touch / pull
    private fun applyTilt() {
        val w = max(1f, host.width.toFloat())
        val h = max(1f, host.height.toFloat())
        host.cameraDistance = 9000f * dp
        host.rotationY = (touchU * 7f * q + ox / w * 14f).coerceIn(-8f, 8f)
        host.rotationX = (-touchV * 7f * q - oy / h * 14f).coerceIn(-8f, 8f)
    }

    private fun resetTilt() { host.rotationX = 0f; host.rotationY = 0f }

    /** Volume-conserving scale about the centre. */
    fun scaleX(): Float { val a = rawX(); val b = rawY(); return a / sqrt(a * b) }
    fun scaleY(): Float { val a = rawX(); val b = rawY(); return b / sqrt(a * b) }
    private fun rawX(): Float = max(0.6f, (1f / (1f - 0.12f * q).coerceIn(0.6f, 1.4f)) * (1f + 0.9f * ex) * (1f - 0.45f * ey))
    private fun rawY(): Float = max(0.6f, (1f - 0.12f * q).coerceIn(0.6f, 1.4f) * (1f + 0.9f * ey) * (1f - 0.45f * ex))

    /** Maps the undeformed shape to the deformed one. */
    fun matrix(w: Float, h: Float, out: Matrix) {
        out.setScale(scaleX(), scaleY(), w / 2f, h / 2f)
        out.postTranslate(ox, oy)
    }

    /** 8-value elliptical radii (tlx tly trx try brx bry blx bly): pulled corners flare, opposite corners taper. */
    fun radii(base: Float, w: Float, h: Float, out: FloatArray) {
        val l = hypot(ox / w, oy / h)
        val f = min(1f, l * 6f)
        val n = hypot(ox, oy)
        val dx = if (n > 1e-3f) ox / n else 0f
        val dy = if (n > 1e-3f) oy / n else 0f
        val cs = intArrayOf(-1, -1, 1, -1, 1, 1, -1, 1)
        for (i in 0 until 4) {
            val cx = cs[i * 2]; val cy = cs[i * 2 + 1]
            out[i * 2] = (base * (1f + 0.38f * f * cx * dx)).coerceIn(base * 0.45f, base * 1.6f)
            out[i * 2 + 1] = (base * (1f + 0.38f * f * cy * dy)).coerceIn(base * 0.45f, base * 1.6f)
        }
    }

    /** Lighting angle (CSS-style, 135 = from top-left) shifts with deformation and velocity. */
    fun angle(w: Float, h: Float): Float = 135f + 50f * (ox / max(1f, w)) - 35f * (oy / max(1f, h)) + 8f * energy
}

/**
 * Draws the liquid glass: backdrop mesh-warped through an edge lens (1 - depth/band)^2.3 along the outward normal,
 * three channel passes at 1.09 / 1.00 / 0.91 (recombined additively = "screen" of isolated R, G, B),
 * low-frequency slosh driven by body velocity, then water / specular / rim lighting.
 * Canvas.drawVertices + BitmapShader stands in for the SVG feDisplacementMap pipeline (no GL, no RenderEffect on API 29).
 */
class LiquidPainter(private val dp: Float) {
    private val gx = LiquidCfg.GX
    private val gy = LiquidCfg.GY
    private val nv = (gx + 1) * (gy + 1)
    private val pos = FloatArray(nv * 2)
    private val tR = FloatArray(nv * 2)
    private val tG = FloatArray(nv * 2)
    private val tB = FloatArray(nv * 2)
    private val idx = ShortArray(gx * gy * 6)
    private val us = FloatArray(gx + 1) { 0.5f - 0.5f * cos(Math.PI * it / gx).toFloat() }   // denser near the edges
    private val vs = FloatArray(gy + 1) { 0.5f - 0.5f * cos(Math.PI * it / gy).toFloat() }
    private val radii = FloatArray(8)
    private val path = Path()
    private val ring = Path()
    private val rr = RectF()
    private val mT = Matrix()
    private val bounds = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var shader: BitmapShader? = null
    private var shaderBmp: Bitmap? = null
    private val fR: ColorMatrixColorFilter
    private val fG: ColorMatrixColorFilter
    private val fB: ColorMatrixColorFilter
    private val fAll: ColorMatrixColorFilter
    private val xAdd = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val xScreen = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    private val xDstIn = PorterDuffXfermode(PorterDuff.Mode.DST_IN)

    // cached gradients
    private var kW = -1f; private var kH = -1f; private var kAng = -999f; private var kEdge = -1f; private var kWarm = 0
    private var gBevel: LinearGradient? = null
    private var gCres: LinearGradient? = null
    private var gSheen: LinearGradient? = null
    private var gGloss: LinearGradient? = null
    private var gFocus: RadialGradient? = null
    private var gMen: RadialGradient? = null
    private var gHi: RadialGradient? = null
    private var gGlint: RadialGradient? = null
    private var gCaus: RadialGradient? = null

    init {
        var p = 0
        for (j in 0 until gy) for (i in 0 until gx) {
            val a = (j * (gx + 1) + i).toShort()
            val b = (a + 1).toShort()
            val c = (a + gx + 1).toShort()
            val d = (c + 1).toShort()
            idx[p++] = a; idx[p++] = b; idx[p++] = c
            idx[p++] = b; idx[p++] = d; idx[p++] = c
        }
        // saturate(1.12) brightness(1.03), then isolate a single channel per pass.
        val cm = ColorMatrix(); cm.setSaturation(1.12f)
        val br = ColorMatrix(); br.setScale(1.03f, 1.03f, 1.03f, 1f)
        cm.postConcat(br)
        val a = cm.array
        fAll = ColorMatrixColorFilter(cm)
        fun chan(row: Int): ColorMatrixColorFilter {
            val m = FloatArray(20)
            for (k in 0 until 5) m[row * 5 + k] = a[row * 5 + k]
            m[19] = 255f                                // alpha constant 1
            return ColorMatrixColorFilter(ColorMatrix(m))
        }
        fR = chan(0); fG = chan(1); fB = chan(2)
    }

    private fun al(x: Float): Int = (x.coerceIn(0f, 1f) * 255f).toInt()

    private fun shapePath(out: Path, ins: Float, w: Float, h: Float) {
        rr.set(ins, ins, w - ins, h - ins)
        val r = FloatArray(8) { max(0f, radii[it] - ins) }
        out.reset()
        out.addRoundRect(rr, r, Path.Direction.CW)
    }

    /**
     * @return false when nothing could be drawn (caller falls back to the classic glass).
     */
    fun draw(
        c: Canvas, w: Float, h: Float, baseRad: Float, g: GlassRenderer, bm: Bitmap, loc: IntArray,
        body: LiquidBody?, dark: Boolean, refrPref: Float, edge: Float, opacityPref: Int,
        shadow: Float, dispersionOn: Boolean, warm: Int, base: Int
    ): Boolean {
        if (w < 2f || h < 2f) return false
        val u = h / 100f
        val r0 = min(baseRad, min(w, h) / 2f)
        val sx: Float; val sy: Float; val ox: Float; val oy: Float; val q: Float; val en: Float
        if (body != null && body.running) {
            sx = body.scaleX(); sy = body.scaleY(); ox = body.ox; oy = body.oy; q = body.q; en = body.energy
            body.radii(r0, w, h, radii)
            body.matrix(w, h, mT)
        } else {
            sx = 1f; sy = 1f; ox = 0f; oy = 0f; q = 0f; en = 0f
            for (i in 0 until 8) radii[i] = r0
            mT.reset()
        }
        val ang = body?.angle(w, h) ?: 135f
        val phase = body?.phase ?: 0f
        ensureShaders(w, h, ang, edge, warm)
        shapePath(path, 0f, w, h)

        val cx = w / 2f
        val cy = h / 2f

        // ---- elevation: caustic beneath, deep soft shadow, tight contact shadow (tighter + darker when pressed)
        c.save()
        c.concat(mT)
        paint.reset(); paint.isAntiAlias = true
        if (shadow > 0.02f) {
            paint.style = Paint.Style.FILL
            for (i in 1..4) {
                val e = i * 2.4f * dp * (1f - 0.25f * q)
                rr.set(-e * 0.7f, e * 0.35f, w + e * 0.7f, h + e)
                paint.color = Color.argb(al(0.27f * shadow / i), 0, 0, 0)
                c.drawRoundRect(rr, r0 + e * 0.7f, r0 + e * 0.7f, paint)
            }
            val ce = 1.4f * dp
            rr.set(-ce * 0.3f, ce * 0.8f, w + ce * 0.3f, h + ce)
            paint.color = Color.argb(al((0.20f + 0.16f * q.coerceIn(0f, 1f)) * shadow), 0, 0, 0)
            c.drawRoundRect(rr, r0 + ce, r0 + ce, paint)
        }
        gCaus?.let {
            paint.shader = it; paint.xfermode = xScreen
            paint.style = Paint.Style.FILL
            rr.set(-w * 0.1f, h * 0.55f, w * 1.1f, h * 1.55f)
            c.drawOval(rr, paint)
            paint.shader = null; paint.xfermode = null
        }
        c.restore()

        // ---- backdrop: edge-lens mesh, three channel passes
        buildMesh(w, h, u, sx, sy, ox, oy, en, phase, refrPref, dispersionOn, g, loc)
        if (shader == null || shaderBmp !== bm) {
            shader = BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shaderBmp = bm
        }
        mT.mapRect(bounds, RectF(0f, 0f, w, h))
        bounds.inset(-2f * dp, -2f * dp)
        val sc = c.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, null)
        try {
            paint.reset(); paint.isAntiAlias = true; paint.isFilterBitmap = true; paint.isDither = true
            paint.shader = shader
            val n2 = nv * 2
            if (dispersionOn) {
                paint.colorFilter = fR
                c.drawVertices(Canvas.VertexMode.TRIANGLES, n2, pos, 0, tR, 0, null, 0, idx, 0, idx.size, paint)
                paint.xfermode = xAdd
                paint.colorFilter = fG
                c.drawVertices(Canvas.VertexMode.TRIANGLES, n2, pos, 0, tG, 0, null, 0, idx, 0, idx.size, paint)
                paint.colorFilter = fB
                c.drawVertices(Canvas.VertexMode.TRIANGLES, n2, pos, 0, tB, 0, null, 0, idx, 0, idx.size, paint)
            } else {
                paint.colorFilter = fAll
                c.drawVertices(Canvas.VertexMode.TRIANGLES, n2, pos, 0, tG, 0, null, 0, idx, 0, idx.size, paint)
            }
            // clip to the (deformed, asymmetric-radius) glass outline with an anti-aliased mask
            paint.reset(); paint.isAntiAlias = true
            paint.color = Color.WHITE; paint.xfermode = xDstIn
            c.save(); c.concat(mT); c.drawPath(path, paint); c.restore()
        } finally {
            c.restoreToCount(sc)
        }

        // ---- surface (everything below follows the deformed outline)
        c.save()
        c.concat(mT)
        paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.FILL

        // fill alpha = (1 - transparency) * 3, nudged by the user's Transparency slider
        val a = (1f - LiquidCfg.TRANSPARENCY) * 3f * (0.5f + (100 - opacityPref) / 50f)
        paint.color = (base and 0xFFFFFF) or (al(a) shl 24)
        c.drawPath(path, paint)

        // water overlay: top gloss, warm focus glow near the bottom, brightened meniscus at the edge
        paint.shader = gGloss; c.drawPath(path, paint)
        paint.shader = gFocus; c.drawPath(path, paint)
        paint.shader = gMen; c.drawPath(path, paint)

        // specular: top-left highlight, top-right glint, diagonal sheen along --ang
        paint.shader = gHi; c.drawPath(path, paint)
        paint.shader = gGlint; c.drawPath(path, paint)
        paint.shader = gSheen; c.drawPath(path, paint)
        paint.shader = null

        if (edge > 0.02f) {
            paint.style = Paint.Style.STROKE
            // inner glow
            paint.strokeWidth = 6f * dp
            paint.color = Color.argb(al(0.05f * edge), 255, 255, 255)
            shapePath(ring, 3f * dp, w, h); c.drawPath(ring, paint)
            // bright top-left / warm bottom-right crescents
            paint.strokeWidth = 2.6f * dp
            paint.shader = gCres
            shapePath(ring, 2.2f * dp, w, h); c.drawPath(ring, paint)
            // 1px crisp white inner ring
            paint.shader = null
            paint.strokeWidth = 1f * dp
            paint.color = Color.argb(al(0.50f * edge), 255, 255, 255)
            shapePath(ring, 0.5f * dp, w, h); c.drawPath(ring, paint)
            // gradient-masked 1.6u bevel following --ang
            val bw = max(1.2f * dp, 1.6f * u)
            paint.strokeWidth = bw
            paint.shader = gBevel
            shapePath(ring, bw / 2f, w, h); c.drawPath(ring, paint)
            paint.shader = null
        }
        c.restore()
        paint.reset()
        return true
    }

    private fun buildMesh(
        w: Float, h: Float, u: Float, sx: Float, sy: Float, ox: Float, oy: Float, en: Float, phase: Float,
        refrPref: Float, disp: Boolean, g: GlassRenderer, loc: IntArray
    ) {
        val cx = w / 2f
        val cy = h / 2f
        val hw = w / 2f
        val hh = h / 2f
        val band = min(LiquidCfg.EDGE_BAND * h, min(hw, hh))
        val scale = LiquidCfg.REFRACTION * u * refrPref
        val mag = LiquidCfg.MAG + 0.9f * (abs(sx - 1f) + abs(sy - 1f)) * 0.5f     // bulges when compressed / stretched
        val amp = en * 2.4f * dp                                                  // internal slosh swells with velocity
        val lcx = cx + ox
        val lcy = cy + oy
        val inv = 1f / g.scale
        val kr = if (disp) 1f + LiquidCfg.DISPERSION else 1f
        val kb = 1f - LiquidCfg.DISPERSION
        val ripple = LiquidCfg.RIPPLE_AMP
        var o = 0
        for (j in 0..gy) {
            val py = vs[j] * h
            for (i in 0..gx) {
                val px = us[i] * w
                val ddx = px - cx
                val ddy = py - cy
                // rounded-rect signed distance (radius of the nearest corner)
                val ci = (if (ddy >= 0f) 2 else 0) + (if (ddx >= 0f) 1 else 0)   // tl, tr, bl, br
                val rad = radiiFor(ci)
                val qx = abs(ddx) - (hw - rad)
                val qy = abs(ddy) - (hh - rad)
                val sgx = if (ddx < 0f) -1f else 1f
                val sgy = if (ddy < 0f) -1f else 1f
                var nx: Float
                var ny: Float
                val sd: Float
                if (qx > 0f && qy > 0f) {
                    val l = sqrt(qx * qx + qy * qy)
                    nx = qx / l * sgx; ny = qy / l * sgy
                    sd = l - rad
                } else if (qx > qy) {
                    nx = sgx; ny = 0f; sd = qx - rad
                } else {
                    nx = 0f; ny = sgy; sd = qy - rad
                }
                val depth = -sd
                var vx = 0f
                var vy = 0f
                if (depth < band) {
                    val t = (1f - max(0f, depth) / band).coerceIn(0f, 1f)
                    val mgn = t.pow(2.3f) * (1f + ripple * sin(t * 11f))
                    vx = nx * mgn; vy = ny * mgn
                }
                // gentle convex dome in the interior
                vx -= ddx / hw * 0.015f
                vy -= ddy / hh * 0.015f
                // deformed position of this vertex
                val dxp = cx + ddx * sx + ox
                val dyp = cy + ddy * sy + oy
                pos[o] = dxp; pos[o + 1] = dyp
                // low-frequency slosh
                val wx = amp * sin(dyp / (26f * dp) + phase)
                val wy = amp * sin(dxp / (32f * dp) + phase * 1.3f)
                // backdrop pixel under this vertex, magnified about the live centre
                val bx = loc[0] + lcx + (dxp - lcx) / mag
                val by = loc[1] + lcy + (dyp - lcy) / mag
                val dxs = vx * scale + wx
                val dys = vy * scale + wy
                tR[o] = (bx + dxs * kr) * inv; tR[o + 1] = (by + dys * kr) * inv
                tG[o] = (bx + dxs) * inv;      tG[o + 1] = (by + dys) * inv
                tB[o] = (bx + dxs * kb) * inv; tB[o + 1] = (by + dys * kb) * inv
                o += 2
            }
        }
    }

    private fun radiiFor(corner: Int): Float {
        // corner: 0 tl, 1 tr, 2 bl, 3 br -> radii order tl tr br bl
        val k = when (corner) { 0 -> 0; 1 -> 1; 2 -> 3; else -> 2 }
        return (radii[k * 2] + radii[k * 2 + 1]) / 2f
    }

    private fun ensureShaders(w: Float, h: Float, ang: Float, edge: Float, warm: Int) {
        val qa = Math.round(ang).toFloat()
        if (w == kW && h == kH && qa == kAng && edge == kEdge && warm == kWarm) return
        kW = w; kH = h; kAng = qa; kEdge = edge; kWarm = warm
        val rad = Math.toRadians(qa.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        val L = (abs(dx) * w + abs(dy) * h) / 2f
        val cx = w / 2f
        val cy = h / 2f
        val x0 = cx - dx * L; val y0 = cy - dy * L
        val x1 = cx + dx * L; val y1 = cy + dy * L
        fun wh(a: Float) = Color.argb(al(a), 255, 255, 255)
        fun wm(a: Float) = (warm and 0xFFFFFF) or (al(a) shl 24)
        gBevel = LinearGradient(x0, y0, x1, y1,
            intArrayOf(wh(0.90f * edge), wh(0.04f * edge), wm(0.75f * edge)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        gCres = LinearGradient(x0, y0, x1, y1,
            intArrayOf(wh(0.55f * edge), wh(0f), wm(0f), wm(0.60f * edge)), floatArrayOf(0f, 0.34f, 0.66f, 1f), Shader.TileMode.CLAMP)
        gSheen = LinearGradient(x0, y0, x1, y1,
            intArrayOf(wh(0f), wh(0f), wh(0.10f), wh(0f), wh(0f)), floatArrayOf(0f, 0.28f, 0.42f, 0.56f, 1f), Shader.TileMode.CLAMP)
        gGloss = LinearGradient(0f, 0f, 0f, h * 0.5f, wh(0.12f), wh(0f), Shader.TileMode.CLAMP)
        gFocus = RadialGradient(cx, h * 0.95f, w * 0.55f, wm(0.20f), wm(0f), Shader.TileMode.CLAMP).also {
            val m = Matrix(); m.setScale(1f, 0.45f, cx, h * 0.95f); it.setLocalMatrix(m)
        }
        val rm = max(cx, 1f)
        gMen = RadialGradient(cx, cy, rm, intArrayOf(wh(0f), wh(0f), wh(0.09f), wh(0.22f)),
            floatArrayOf(0f, 0.60f, 0.78f, 1f), Shader.TileMode.CLAMP).also {
            val m = Matrix(); m.setScale(1f, (h / 2f) / rm, cx, cy); it.setLocalMatrix(m)
        }
        gHi = RadialGradient(0.26f * w, 0.17f * h, 0.30f * w, wh(0.34f), wh(0f), Shader.TileMode.CLAMP).also {
            val m = Matrix(); m.setScale(1f, 0.28f, 0.26f * w, 0.17f * h); it.setLocalMatrix(m)
        }
        gGlint = RadialGradient(0.86f * w, 0.14f * h, max(2f * dp, 0.05f * h), wh(0.60f), wh(0f), Shader.TileMode.CLAMP)
        gCaus = RadialGradient(cx, h, w * 0.5f, wm(0.22f), wm(0f), Shader.TileMode.CLAMP).also {
            val m = Matrix(); m.setScale(1f, 0.35f, cx, h); it.setLocalMatrix(m)
        }
    }
}
