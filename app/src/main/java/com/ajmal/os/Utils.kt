package com.ajmal.os

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.TextView

fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun roundBg(color: Int, radiusPx: Float): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = radiusPx
    return g
}

fun mix(a: Int, b: Int, t: Float): Int {
    val r = (Color.red(a) * (1f - t) + Color.red(b) * t).toInt()
    val g = (Color.green(a) * (1f - t) + Color.green(b) * t).toInt()
    val bl = (Color.blue(a) * (1f - t) + Color.blue(b) * t).toInt()
    return Color.rgb(r, g, bl)
}

fun label(ctx: Context, text: String, sizeSp: Float, color: Int): TextView {
    val t = TextView(ctx)
    t.text = text
    t.textSize = sizeSp
    t.setTextColor(color)
    return t
}

/**
 * Interface size. Some tablets report a very low density (the Lenovo Tab M10 reports 160, so 1 dp = 1 px and
 * everything looks tiny). Automatic scales the launcher as if the screen were 213 dpi; the Settings slider then adjusts around that (100% = recommended).
 */
object UiScale {
    /** Effective % of the system density: the automatic base for this screen times the user's setting. */
    fun percent(): Int {
        val phys = Resources.getSystem().configuration.densityDpi
        val base = if (phys in 1..199) 213f / phys else 1f
        val rel = try { LauncherApp.get().prefs.int(K.UI_SCALE) } catch (e: Throwable) { 100 }
        return (base * rel).toInt().coerceIn(60, 300)
    }

    fun wrap(base: Context): Context {
        val p = percent()
        if (p == 100) return base
        val c = Configuration(base.resources.configuration)
        c.densityDpi = (Resources.getSystem().configuration.densityDpi * p / 100f).toInt()
        return base.createConfigurationContext(c)
    }
}
