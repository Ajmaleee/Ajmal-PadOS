package com.ajmal.os

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/** Original glyphs for the widgets (device icons, weather symbols), drawn with plain canvas shapes. */
object WidgetArt {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val path2 = Path()
    private val rf = RectF()

    private fun reset(color: Int) {
        p.reset(); p.isAntiAlias = true; p.color = color; p.style = Paint.Style.FILL
    }

    // ------------------------------------------------------------------ devices

    /** A device glyph centred on (cx, cy) inside a square of side [s]. */
    fun device(c: Canvas, kind: Int, cx: Float, cy: Float, s: Float, color: Int) {
        reset(color)
        val x0 = cx - s / 2f
        val y0 = cy - s / 2f
        fun u(v: Float) = v * s
        when (kind) {
            Bt.TABLET -> {
                path.rewind(); path.fillType = Path.FillType.EVEN_ODD
                rf.set(x0 + u(.2f), y0 + u(.06f), x0 + u(.8f), y0 + u(.94f)); path.addRoundRect(rf, u(.08f), u(.08f), Path.Direction.CW)
                rf.set(x0 + u(.26f), y0 + u(.14f), x0 + u(.74f), y0 + u(.78f)); path.addRoundRect(rf, u(.03f), u(.03f), Path.Direction.CW)
                c.drawPath(path, p)
                c.drawCircle(cx, y0 + u(.86f), u(.035f), p)
            }
            Bt.EARBUDS -> for (side in intArrayOf(-1, 1)) {
                val bx = cx + side * u(.2f)
                c.drawCircle(bx, y0 + u(.3f), u(.16f), p)
                val sx = bx + side * u(.05f)
                rf.set(sx - u(.06f), y0 + u(.3f), sx + u(.06f), y0 + u(.84f))
                c.drawRoundRect(rf, u(.06f), u(.06f), p)
            }
            Bt.HEADPHONES -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = u(.09f); p.strokeCap = Paint.Cap.ROUND
                rf.set(x0 + u(.15f), y0 + u(.1f), x0 + u(.85f), y0 + u(.82f))
                c.drawArc(rf, 180f, 180f, false, p)
                p.style = Paint.Style.FILL
                rf.set(x0 + u(.08f), y0 + u(.48f), x0 + u(.28f), y0 + u(.88f)); c.drawRoundRect(rf, u(.07f), u(.07f), p)
                rf.set(x0 + u(.72f), y0 + u(.48f), x0 + u(.92f), y0 + u(.88f)); c.drawRoundRect(rf, u(.07f), u(.07f), p)
            }
            Bt.SPEAKER -> {
                path.rewind(); path.fillType = Path.FillType.EVEN_ODD
                rf.set(x0 + u(.24f), y0 + u(.06f), x0 + u(.76f), y0 + u(.94f)); path.addRoundRect(rf, u(.1f), u(.1f), Path.Direction.CW)
                path.addCircle(cx, y0 + u(.27f), u(.07f), Path.Direction.CW)
                path.addCircle(cx, y0 + u(.62f), u(.17f), Path.Direction.CW)
                c.drawPath(path, p)
            }
            Bt.WATCH -> {
                rf.set(x0 + u(.34f), y0 + u(.02f), x0 + u(.66f), y0 + u(.98f)); c.drawRoundRect(rf, u(.06f), u(.06f), p)
                path.rewind(); path.fillType = Path.FillType.EVEN_ODD
                rf.set(x0 + u(.2f), y0 + u(.24f), x0 + u(.8f), y0 + u(.76f)); path.addRoundRect(rf, u(.14f), u(.14f), Path.Direction.CW)
                rf.set(x0 + u(.27f), y0 + u(.31f), x0 + u(.73f), y0 + u(.69f)); path.addRoundRect(rf, u(.08f), u(.08f), Path.Direction.CW)
                c.drawPath(path, p)
            }
            Bt.KEYBOARD -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = u(.07f)
                rf.set(x0 + u(.06f), y0 + u(.26f), x0 + u(.94f), y0 + u(.74f)); c.drawRoundRect(rf, u(.1f), u(.1f), p)
                p.style = Paint.Style.FILL
                for (r in 0..1) for (k in 0..3) c.drawCircle(x0 + u(.22f + k * .185f), y0 + u(.42f + r * .16f), u(.04f), p)
            }
            Bt.MOUSE -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = u(.07f); p.strokeCap = Paint.Cap.ROUND
                rf.set(x0 + u(.28f), y0 + u(.07f), x0 + u(.72f), y0 + u(.93f)); c.drawRoundRect(rf, u(.22f), u(.22f), p)
                c.drawLine(cx, y0 + u(.1f), cx, y0 + u(.4f), p)
            }
            Bt.PHONE -> {
                path.rewind(); path.fillType = Path.FillType.EVEN_ODD
                rf.set(x0 + u(.28f), y0 + u(.04f), x0 + u(.72f), y0 + u(.96f)); path.addRoundRect(rf, u(.09f), u(.09f), Path.Direction.CW)
                rf.set(x0 + u(.34f), y0 + u(.13f), x0 + u(.66f), y0 + u(.8f)); path.addRoundRect(rf, u(.02f), u(.02f), Path.Direction.CW)
                c.drawPath(path, p)
                c.drawCircle(cx, y0 + u(.88f), u(.03f), p)
            }
            else -> {   // the Bluetooth rune
                p.style = Paint.Style.STROKE; p.strokeWidth = u(.09f); p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                path.rewind()
                path.moveTo(x0 + u(.3f), y0 + u(.3f)); path.lineTo(x0 + u(.7f), y0 + u(.7f)); path.lineTo(x0 + u(.5f), y0 + u(.9f))
                path.lineTo(x0 + u(.5f), y0 + u(.1f)); path.lineTo(x0 + u(.7f), y0 + u(.3f)); path.lineTo(x0 + u(.3f), y0 + u(.7f))
                c.drawPath(path, p)
            }
        }
    }

    fun bolt(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        reset(color)
        val x0 = cx - s * 0.35f
        val y0 = cy - s / 2f
        path.rewind()
        path.moveTo(x0 + s * .55f, y0); path.lineTo(x0 + s * .08f, y0 + s * .56f); path.lineTo(x0 + s * .38f, y0 + s * .56f)
        path.lineTo(x0 + s * .3f, y0 + s); path.lineTo(x0 + s * .78f, y0 + s * .4f); path.lineTo(x0 + s * .47f, y0 + s * .4f)
        path.close()
        c.drawPath(path, p)
    }

    /** The small "current location" arrow. */
    fun locationArrow(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        reset(color)
        path.rewind()
        path.moveTo(cx + s * .5f, cy - s * .5f); path.lineTo(cx - s * .5f, cy - s * .1f); path.lineTo(cx - s * .08f, cy + s * .08f)
        path.lineTo(cx + s * .1f, cy + s * .5f); path.close()
        c.drawPath(path, p)
    }

    // ------------------------------------------------------------------ weather

    private const val SUN = 0xFFFFD60A.toInt()
    private const val SUN_RAY = 0xFFFFB800.toInt()
    private const val MOON = 0xFFF4E7A6.toInt()
    private const val CLOUD = 0xFFFFFFFF.toInt()
    private const val CLOUD_SHADE = 0xFFC9D2DE.toInt()
    private const val CLOUD_DARK = 0xFF8D97A8.toInt()
    private const val DROP = 0xFF6CC5FF.toInt()

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float) {
        reset(SUN)
        c.drawCircle(cx, cy, r, p)
        p.color = SUN_RAY; p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.22f; p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            c.drawLine(cx + (r * 1.35f * sin(a)).toFloat(), cy - (r * 1.35f * cos(a)).toFloat(),
                cx + (r * 1.8f * sin(a)).toFloat(), cy - (r * 1.8f * cos(a)).toFloat(), p)
        }
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, r: Float) {
        path.rewind(); path2.rewind()
        path.addCircle(cx, cy, r, Path.Direction.CW)
        path2.addCircle(cx + r * 0.55f, cy - r * 0.3f, r * 0.85f, Path.Direction.CW)
        path.op(path2, Path.Op.DIFFERENCE)
        reset(MOON)
        c.drawPath(path, p)
    }

    private fun cloud(c: Canvas, cx: Float, cy: Float, w: Float, color: Int) {
        reset(color)
        c.drawCircle(cx - w * .22f, cy + w * .05f, w * .2f, p)
        c.drawCircle(cx, cy - w * .1f, w * .28f, p)
        c.drawCircle(cx + w * .24f, cy + w * .04f, w * .22f, p)
        rf.set(cx - w * .42f, cy + w * .02f, cx + w * .46f, cy + w * .26f)
        c.drawRoundRect(rf, w * .12f, w * .12f, p)
    }

    /** The weather symbol for a WMO code, centred on (cx, cy) in a box of side [s]. */
    fun weather(c: Canvas, code: Int, day: Boolean, cx: Float, cy: Float, s: Float) {
        val w = s * 0.92f
        when (code) {
            0 -> if (day) sun(c, cx, cy, s * 0.26f) else moon(c, cx, cy, s * 0.34f)
            1, 2 -> {
                if (day) sun(c, cx - s * .16f, cy - s * .16f, s * .2f) else moon(c, cx - s * .16f, cy - s * .14f, s * .26f)
                cloud(c, cx + s * .06f, cy + s * .1f, w * .8f, CLOUD)
            }
            3 -> { cloud(c, cx + s * .1f, cy - s * .08f, w * .62f, CLOUD_SHADE); cloud(c, cx - s * .02f, cy + s * .08f, w * .8f, CLOUD) }
            45, 48 -> {
                cloud(c, cx, cy - s * .12f, w * .8f, CLOUD)
                reset(0xCCFFFFFF.toInt()); p.style = Paint.Style.STROKE; p.strokeWidth = s * .07f; p.strokeCap = Paint.Cap.ROUND
                for (k in 0..2) c.drawLine(cx - s * .3f, cy + s * (.26f + k * .13f), cx + s * .3f, cy + s * (.26f + k * .13f), p)
            }
            in 71..77, 85, 86 -> {
                cloud(c, cx, cy - s * .14f, w * .8f, CLOUD)
                reset(CLOUD)
                for (k in 0..2) c.drawCircle(cx + s * (-.22f + k * .22f), cy + s * (.3f + (k % 2) * .1f), s * .055f, p)
            }
            95, 96, 99 -> {
                cloud(c, cx, cy - s * .16f, w * .8f, CLOUD_DARK)
                bolt(c, cx, cy + s * .3f, s * .4f, SUN)
            }
            else -> {   // drizzle, rain, showers
                cloud(c, cx, cy - s * .16f, w * .8f, if (code >= 63) CLOUD_SHADE else CLOUD)
                reset(DROP); p.style = Paint.Style.STROKE; p.strokeWidth = s * .075f; p.strokeCap = Paint.Cap.ROUND
                val n = if (code in 51..57) 2 else 3
                for (k in 0 until n) {
                    val x = cx + s * (-.2f + k * (if (n == 2) .4f else .2f))
                    c.drawLine(x, cy + s * .22f, x - s * .06f, cy + s * .4f, p)
                }
            }
        }
    }

    fun condition(code: Int, day: Boolean): String = when (code) {
        0 -> if (day) "Sunny" else "Clear"
        1 -> if (day) "Mostly Sunny" else "Mostly Clear"
        2 -> "Partly Cloudy"
        3 -> "Cloudy"
        45, 48 -> "Foggy"
        51, 53, 55, 56, 57 -> "Drizzle"
        61 -> "Light Rain"
        63, 80, 81 -> "Rain"
        65, 82 -> "Heavy Rain"
        66, 67 -> "Freezing Rain"
        71, 73, 75, 77 -> "Snow"
        85, 86 -> "Snow Showers"
        95 -> "Thunderstorms"
        96, 99 -> "Hail Storms"
        else -> "Showers"
    }

    /** Sky gradient (top, bottom) like the iOS Weather widget. */
    fun sky(code: Int, day: Boolean): Pair<Int, Int> = when {
        code in 95..99 -> 0xFF4A4F63.toInt() to 0xFF2A2D3D.toInt()
        code in 71..77 || code in 85..86 -> 0xFF8AA0B8.toInt() to 0xFF5E738C.toInt()
        code in 51..67 || code in 80..82 -> (if (day) 0xFF5A6E85.toInt() to 0xFF37475C.toInt() else 0xFF2E3A4D.toInt() to 0xFF1B2433.toInt())
        code == 45 || code == 48 -> 0xFF8E9BA8.toInt() to 0xFF6B7783.toInt()
        code == 3 || code == 2 -> if (day) 0xFF6C8FB5.toInt() to 0xFF47688F.toInt() else 0xFF2B3550.toInt() to 0xFF1A2138.toInt()
        else -> if (day) 0xFF4C9BF0.toInt() to 0xFF2A6FD6.toInt() else 0xFF1D2A52.toInt() to 0xFF0B1230.toInt()
    }
}
