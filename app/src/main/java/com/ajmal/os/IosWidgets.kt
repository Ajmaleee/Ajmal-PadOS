package com.ajmal.os

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * iOS-style widgets (Clock, Batteries, Weather, Calendar, Tablet). Original artwork following the proportions
 * of the iOS widget families: small 158x158, medium 338x158, large 338x354 points, 22 pt corners.
 * They are home-screen items (so they drag, resize and remove like any widget) and are also the cards of
 * the Today View.
 */
object IosWidgets {
    const val ID = -2
    const val PKG = "ajmalpad"

    enum class Size { S, M, L }

    class Kind(val id: String, val title: String, val blurb: String)

    val kinds = listOf(
        Kind("clock", "Clock", "Analog clock with seconds. Medium shows four cities"),
        Kind("battery", "Batteries", "This tablet and connected Bluetooth devices, earbuds included"),
        Kind("weather", "Weather", "Current conditions, hourly and 7-day forecast"),
        Kind("tablet", "Tablet", "Battery, storage, memory and system details"),
        Kind("calendar", "Calendar", "Today's date and the month")
    )

    fun refW(s: Size) = if (s == Size.S) 158f else 338f
    fun refH(s: Size) = if (s == Size.L) 354f else 158f

    fun spans(s: Size): Pair<Int, Int> = when (s) { Size.S -> 2 to 2; Size.M -> 4 to 2; Size.L -> 4 to 4 }

    fun sizeOf(sx: Int, sy: Int): Size = if (sx >= 4 && sy >= 3) Size.L else if (sx >= 3) Size.M else Size.S

    fun create(act: HomeActivity, item: Item): View {
        val f = IosWidgetFrame(act, item.cls, sizeOf(item.sx, item.sy))
        val wf = WidgetFrame(act, act)
        wf.addView(f, FrameLayout.LayoutParams(-1, -1))
        wf.tag = item
        return wf
    }

    fun resize(v: View, item: Item) {
        ((v as? FrameLayout)?.getChildAt(0) as? IosWidgetFrame)?.size = sizeOf(item.sx, item.sy)
    }
}

/** Keeps the widget's iOS proportions inside whatever grid rectangle it was given. */
class IosWidgetFrame(ctx: Context, val kind: String, size: IosWidgets.Size) : FrameLayout(ctx) {
    private val glassMode = LauncherApp.get().prefs.bool(K.WIDGET_GLASS)
    private val content = IosWidgetContent(ctx, kind, size, glassMode)
    private val card: FrameLayout = if (glassMode) GlassFrame(ctx).also { it.radiusDp = 22f } else FrameLayout(ctx)
    private val dp = ctx.resources.displayMetrics.density
    var size: IosWidgets.Size = size
        set(v) { field = v; content.size = v; fit(width, height) }

    init {
        card.addView(content, LayoutParams(-1, -1))
        addView(card, LayoutParams(0, 0, Gravity.CENTER))
    }

    private fun fit(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val m = 3f * dp
        val aw = w - 2 * m
        val ah = h - 2 * m
        val ratio = IosWidgets.refW(size) / IosWidgets.refH(size)
        val cw: Float
        val ch: Float
        if (aw / ah > ratio) { ch = ah; cw = ch * ratio } else { cw = aw; ch = cw / ratio }
        val lp = card.layoutParams as LayoutParams
        if (lp.width != cw.toInt() || lp.height != ch.toInt()) {
            lp.width = cw.toInt(); lp.height = ch.toInt()
            card.layoutParams = lp
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fit(w, h)
    }
}

class IosWidgetContent(ctx: Context, private val kind: String, size: IosWidgets.Size, private val glass: Boolean) : View(ctx) {
    var size: IosWidgets.Size = size
        set(v) { field = v; invalidate() }
    private val app = LauncherApp.get()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val path = Path()
    private var dark = true
    private var fg = Color.WHITE
    private var fg2 = 0x99FFFFFF.toInt()
    private val faces = arrayOf(
        Typeface.create("sans-serif", Typeface.NORMAL), Typeface.create("sans-serif-medium", Typeface.NORMAL),
        Typeface.create("sans-serif", Typeface.BOLD), Typeface.create("sans-serif-light", Typeface.NORMAL)
    )
    private val orange = 0xFFFF9F0A.toInt()
    private val green get() = if (dark) 0xFF30D158.toInt() else 0xFF34C759.toInt()
    private val red = 0xFFFF453A.toInt()
    private val yellow = 0xFFFFD60A.toInt()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WidgetData.subscribe(this, kind == "clock")
    }

    override fun onDetachedFromWindow() {
        WidgetData.unsubscribe(this)
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        try { render(c) } catch (t: Throwable) { Diag.e("widget.draw.$kind", t) }
    }

    private fun render(c: Canvas) {
        val refW = IosWidgets.refW(size)
        val u = width / refW
        if (u <= 0f) return
        dark = app.isDark()
        val isWeather = kind == "weather"
        fg = if (dark || isWeather) Color.WHITE else Color.BLACK
        fg2 = if (dark || isWeather) 0xAAFFFFFF.toInt() else 0x99000000.toInt()
        c.save()
        c.scale(u, u)
        val w = refW
        val h = height / u
        if (!glass || isWeather) background(c, w, h)
        when (kind) {
            "clock" -> clock(c, w, h)
            "battery" -> battery(c, w, h)
            "weather" -> weather(c, w, h)
            "calendar" -> calendar(c, w, h)
            "tablet" -> tablet(c, w, h)
        }
        c.restore()
    }

    // ------------------------------------------------------------------ helpers

    private fun background(c: Canvas, w: Float, h: Float) {
        rf.set(0f, 0f, w, h)
        bg.reset(); bg.isAntiAlias = true
        if (kind == "weather") {
            val wx = WidgetData.weather
            val sky = if (WCfg.place() == null || wx == null) (0xFF3A7BD5.toInt() to 0xFF2A5298.toInt()) else WidgetArt.sky(wx.code, wx.isDay)
            bg.shader = LinearGradient(0f, 0f, 0f, h, sky.first, sky.second, Shader.TileMode.CLAMP)
        } else {
            bg.color = if (dark) (if (kind == "clock") 0xFF000000.toInt() else 0xFF1C1C1E.toInt()) else 0xFFFFFFFF.toInt()
        }
        c.drawRoundRect(rf, 22f, 22f, bg)
        if (!dark && kind != "weather") {
            line.reset(); line.isAntiAlias = true; line.style = Paint.Style.STROKE; line.strokeWidth = 0.6f; line.color = 0x1F000000
            rf.set(0.3f, 0.3f, w - 0.3f, h - 0.3f)
            c.drawRoundRect(rf, 22f, 22f, line)
        }
    }

    private fun text(
        c: Canvas, s: String, x: Float, y: Float, pt: Float, color: Int, face: Int = 0,
        align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f, maxW: Float = 0f
    ) {
        tp.reset(); tp.isAntiAlias = true
        tp.textSize = pt; tp.color = color; tp.typeface = faces[face]; tp.textAlign = align; tp.letterSpacing = spacing
        val t = if (maxW > 0f) TextUtils.ellipsize(s, tp, maxW, TextUtils.TruncateAt.END).toString() else s
        c.drawText(t, x, y, tp)
    }

    private fun measure(s: String, pt: Float, face: Int): Float {
        tp.reset(); tp.textSize = pt; tp.typeface = faces[face]
        return tp.measureText(s)
    }

    private fun ring(c: Canvas, cx: Float, cy: Float, r: Float, stroke: Float, pct: Int, color: Int, track: Int) {
        line.reset(); line.isAntiAlias = true; line.style = Paint.Style.STROKE; line.strokeWidth = stroke; line.strokeCap = Paint.Cap.ROUND
        line.color = track
        c.drawCircle(cx, cy, r, line)
        if (pct > 0) {
            line.color = color
            rf.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(rf, -90f, 360f * min(pct, 100) / 100f, false, line)
        }
    }

    private fun bar(c: Canvas, x: Float, y: Float, w: Float, h: Float, frac: Float, color: Int) {
        bg.reset(); bg.isAntiAlias = true
        bg.color = if (dark) 0x33FFFFFF else 0x1F000000
        rf.set(x, y, x + w, y + h)
        c.drawRoundRect(rf, h / 2f, h / 2f, bg)
        bg.color = color
        rf.set(x, y, x + max(h, w * frac.coerceIn(0f, 1f)), y + h)
        c.drawRoundRect(rf, h / 2f, h / 2f, bg)
    }

    private fun temp(v: Double): String {
        val t = if (WCfg.useF()) v * 9.0 / 5.0 + 32.0 else v
        return t.roundToInt().toString() + "°"
    }

    // ------------------------------------------------------------------ clock

    private fun dayOf(tz: TimeZone, now: Long): Int {
        val cal = Calendar.getInstance(tz); cal.timeInMillis = now
        return cal.get(Calendar.YEAR) * 400 + cal.get(Calendar.DAY_OF_YEAR)
    }

    private fun subtitle(tz: TimeZone): String {
        val now = System.currentTimeMillis()
        val local = TimeZone.getDefault()
        val d = (tz.getOffset(now) - local.getOffset(now)) / 3600000f
        val a = abs(d)
        val num = if (a == a.toInt().toFloat()) a.toInt().toString() else String.format(Locale.US, "%.1f", a)
        val off = (if (d >= 0f) "+" else "-") + num + (if (a == 1f) "HR" else "HRS")
        val diff = dayOf(tz, now) - dayOf(local, now)
        return (if (diff > 0) "Tomorrow" else if (diff < 0) "Yesterday" else "Today") + ", " + off
    }

    private fun hand(c: Canvas, cx: Float, cy: Float, deg: Float, len: Float, wd: Float, color: Int, tail: Float) {
        val a = Math.toRadians(deg.toDouble())
        val s = sin(a).toFloat()
        val co = cos(a).toFloat()
        line.reset(); line.isAntiAlias = true; line.strokeCap = Paint.Cap.ROUND; line.strokeWidth = wd; line.color = color
        c.drawLine(cx - s * tail, cy + co * tail, cx + s * len, cy - co * len, line)
    }

    private fun dial(c: Canvas, cx: Float, cy: Float, r: Float, tz: TimeZone, full: Boolean, initials: String?) {
        val marks = if (dark) Color.WHITE else Color.BLACK
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = System.currentTimeMillis()
        val sec = cal.get(Calendar.SECOND)
        val min = cal.get(Calendar.MINUTE) + sec / 60f
        val hr = (cal.get(Calendar.HOUR) % 12) + min / 60f
        line.reset(); line.isAntiAlias = true; line.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 60) {
            val hourTick = i % 5 == 0
            if (!full && !hourTick) continue
            val a = Math.toRadians(i * 6.0)
            val s = sin(a).toFloat()
            val co = cos(a).toFloat()
            val len = if (hourTick) r * (if (full) 0.1f else 0.14f) else r * 0.05f
            line.strokeWidth = if (hourTick) r * (if (full) 0.03f else 0.05f) else r * 0.012f
            line.color = if (hourTick) marks else (marks and 0x00FFFFFF) or (0x80 shl 24)
            c.drawLine(cx + s * (r - len), cy - co * (r - len), cx + s * r, cy - co * r, line)
        }
        if (full) {
            for (n in intArrayOf(12, 3, 6, 9)) {
                val a = Math.toRadians((n % 12) * 30.0)
                val sz = r * 0.27f
                text(c, n.toString(), cx + sin(a).toFloat() * r * 0.72f, cy - cos(a).toFloat() * r * 0.72f + sz * 0.36f, sz, marks, 1, Paint.Align.CENTER)
            }
            if (initials != null) text(c, initials, cx, cy - r * 0.3f, r * 0.14f, orange, 2, Paint.Align.CENTER, 0.12f)
        }
        val hw = max(r * 0.07f, 2.2f)
        val mw = max(r * 0.05f, 1.7f)
        hand(c, cx, cy, hr * 30f, r * 0.5f, hw, marks, 0f)
        hand(c, cx, cy, min * 6f, r * 0.8f, mw, marks, 0f)
        hand(c, cx, cy, sec * 6f, r * 0.88f, max(r * 0.02f, 1f), orange, r * 0.2f)
        bg.reset(); bg.isAntiAlias = true; bg.color = orange
        c.drawCircle(cx, cy, max(r * 0.06f, 2.4f), bg)
        bg.color = if (dark) Color.BLACK else Color.WHITE
        c.drawCircle(cx, cy, max(r * 0.025f, 1f), bg)
    }

    private fun clock(c: Canvas, w: Float, h: Float) {
        val local = TimeZone.getDefault()
        when (size) {
            IosWidgets.Size.S -> dial(c, 79f, 79f, 68f, local, true, WCfg.cityName(local.id).take(3).uppercase())
            IosWidgets.Size.M -> {
                val z = WCfg.zones()
                for (i in 0 until 4) {
                    val tz = TimeZone.getTimeZone(z[i])
                    val cx = w / 4f * (i + 0.5f)
                    dial(c, cx, 60f, 34f, tz, false, null)
                    text(c, WCfg.cityName(z[i]), cx, 122f, 11.5f, fg, 2, Paint.Align.CENTER, 0f, 78f)
                    text(c, subtitle(tz), cx, 137f, 10f, fg2, 0, Paint.Align.CENTER, 0f, 82f)
                }
            }
            IosWidgets.Size.L -> {
                dial(c, 169f, 122f, 104f, local, true, WCfg.cityName(local.id).take(3).uppercase())
                val now = Date()
                text(c, SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now), 169f, 276f, 17f, fg, 2, Paint.Align.CENTER)
                val is24 = DateFormat.is24HourFormat(context)
                text(c, SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", Locale.getDefault()).format(now), 169f, 326f, 42f, fg, 3, Paint.Align.CENTER)
                text(c, WCfg.cityName(local.id).uppercase(), 169f, 346f, 10.5f, fg2, 1, Paint.Align.CENTER, 0.12f)
            }
        }
    }

    // ------------------------------------------------------------------ batteries

    private class Dev(val name: String, val kind: Int, val pct: Int, val charging: Boolean, val lowPower: Boolean, val note: String)

    private fun devices(): List<Dev> {
        val sys = WidgetData.sys
        val out = ArrayList<Dev>()
        out.add(Dev(sys?.deviceName ?: Build.MODEL, Bt.TABLET, WidgetData.batteryPct, WidgetData.charging, WidgetData.powerSave,
            if (WidgetData.charging) "Charging" else if (WidgetData.powerSave) "Low Power Mode" else "This tablet"))
        for (d in WidgetData.bt) out.add(Dev(d.name, d.kind, d.level, false, false, if (d.level >= 0) "Connected" else "Connected, battery not reported"))
        return out
    }

    private fun ringColor(d: Dev): Int = if (d.lowPower) yellow else if (d.pct in 0..20 && !d.charging) red else green
    private fun track(): Int = if (dark) 0x33FFFFFF else 0x1F000000

    private fun battery(c: Canvas, w: Float, h: Float) {
        val devs = devices()
        when (size) {
            IosWidgets.Size.S -> {
                if (devs.size == 1) {
                    val d = devs[0]
                    ring(c, 79f, 64f, 40f, 9f, d.pct, ringColor(d), track())
                    WidgetArt.device(c, d.kind, 79f, 64f, 34f, fg)
                    if (d.charging) WidgetArt.bolt(c, 79f + 30f, 64f - 36f, 15f, green)
                    text(c, if (d.pct >= 0) "${d.pct}%" else "--", 79f, 140f, 26f, fg, 1, Paint.Align.CENTER)
                } else {
                    val pos = arrayOf(46f to 46f, 112f to 46f, 46f to 112f, 112f to 112f)
                    for ((i, d) in devs.take(4).withIndex()) {
                        ring(c, pos[i].first, pos[i].second, 29f, 6f, d.pct, ringColor(d), track())
                        WidgetArt.device(c, d.kind, pos[i].first, pos[i].second, 26f, fg)
                        if (d.charging) WidgetArt.bolt(c, pos[i].first + 24f, pos[i].second - 26f, 11f, green)
                    }
                }
            }
            IosWidgets.Size.M -> {
                val n = min(devs.size, 4)
                val colW = 84.5f
                val x0 = (w - n * colW) / 2f
                for ((i, d) in devs.take(4).withIndex()) {
                    val cx = x0 + colW * (i + 0.5f)
                    ring(c, cx, 56f, 31f, 6.5f, d.pct, ringColor(d), track())
                    WidgetArt.device(c, d.kind, cx, 56f, 29f, fg)
                    if (d.charging) WidgetArt.bolt(c, cx + 26f, 56f - 28f, 12f, green)
                    text(c, if (d.pct >= 0) "${d.pct}%" else "--", cx, 117f, 18f, fg, 1, Paint.Align.CENTER)
                    text(c, d.name, cx, 134f, 10f, fg2, 0, Paint.Align.CENTER, 0f, colW - 6f)
                }
            }
            IosWidgets.Size.L -> {
                val rowH = 52f
                for ((i, d) in devs.take(6).withIndex()) {
                    val y = 14f + i * rowH
                    ring(c, 40f, y + 26f, 18f, 4.5f, d.pct, ringColor(d), track())
                    WidgetArt.device(c, d.kind, 40f, y + 26f, 17f, fg)
                    text(c, d.name, 70f, y + 24f, 16f, fg, 1, Paint.Align.LEFT, 0f, 190f)
                    text(c, d.note, 70f, y + 41f, 11f, fg2, 0, Paint.Align.LEFT, 0f, 200f)
                    text(c, if (d.pct >= 0) "${d.pct}%" else "--", 318f, y + 32f, 19f, fg, 1, Paint.Align.RIGHT)
                    if (i < min(devs.size, 6) - 1) {
                        line.reset(); line.color = if (dark) 0x22FFFFFF else 0x16000000; line.strokeWidth = 0.6f
                        c.drawLine(70f, y + rowH, 322f, y + rowH, line)
                    }
                }
                if (devs.size == 1) {
                    text(c, "Connected Bluetooth devices appear here.", 169f, 160f, 12f, fg2, 0, Paint.Align.CENTER)
                    text(c, "Battery % shows if the device reports it.", 169f, 178f, 12f, fg2, 0, Paint.Align.CENTER)
                }
            }
        }
    }

    // ------------------------------------------------------------------ weather

    private fun weatherNote(c: Canvas, w: Float, h: Float, title: String, sub: String) {
        text(c, title, 16f, 40f, 16f, Color.WHITE, 2, Paint.Align.LEFT, 0f, w - 32f)
        text(c, sub, 16f, 60f, 12f, fg2, 0, Paint.Align.LEFT, 0f, w - 32f)
        WidgetArt.weather(c, 0, true, w - 40f, 40f, 40f)
    }

    private fun weather(c: Canvas, w: Float, h: Float) {
        val place = WCfg.place()
        val wx = WidgetData.weather
        if (place == null) { weatherNote(c, w, h, "Set a location", "Settings > Widgets > Weather location"); return }
        if (wx == null || wx.placeKey != place.key) {
            val err = WidgetData.weatherError
            weatherNote(c, w, h, if (err != null) "Weather unavailable" else "Loading weather...", if (err != null) "Check the internet connection" else place.name)
            return
        }
        val white = Color.WHITE
        val soft = 0xCCFFFFFF.toInt()
        // Current conditions (all sizes)
        val cityW = measure(place.name, 15f, 2)
        text(c, place.name, 16f, 27f, 15f, white, 2, Paint.Align.LEFT, 0f, if (size == IosWidgets.Size.S) 126f else 150f)
        if (place.gps) WidgetArt.locationArrow(c, 16f + min(cityW, 126f) + 9f, 22f, 8f, white)
        val big = if (size == IosWidgets.Size.L) 56f else 50f
        text(c, temp(wx.temp), 16f, if (size == IosWidgets.Size.L) 88f else 78f, big, white, 3)
        if (size != IosWidgets.Size.L) {
            WidgetArt.weather(c, wx.code, wx.isDay, 27f, 106f, 22f)
            text(c, WidgetArt.condition(wx.code, wx.isDay), 16f, 129f, 12.5f, white, 1, Paint.Align.LEFT, 0f, 146f)
            text(c, "H:${temp(wx.hi)}  L:${temp(wx.lo)}", 16f, 145f, 12.5f, soft, 0, Paint.Align.LEFT, 0f, 146f)
        }
        when (size) {
            IosWidgets.Size.S -> {}
            IosWidgets.Size.M -> {
                val n = min(5, wx.hourHour.size)
                val cw = 30f
                val x0 = 338f - 14f - n * cw
                for (i in 0 until n) {
                    val cx = x0 + cw * (i + 0.5f)
                    text(c, if (i == 0) "Now" else hourLabel(wx.hourHour[i]), cx, 40f, 11f, soft, 1, Paint.Align.CENTER)
                    WidgetArt.weather(c, wx.hourCode[i], wx.hourDay[i], cx, 80f, 22f)
                    text(c, temp(wx.hourTemp[i]), cx, 122f, 14.5f, white, 1, Paint.Align.CENTER)
                }
            }
            IosWidgets.Size.L -> {
                WidgetArt.weather(c, wx.code, wx.isDay, 270f, 62f, 50f)
                text(c, WidgetArt.condition(wx.code, wx.isDay), 322f, 112f, 13f, white, 1, Paint.Align.RIGHT, 0f, 150f)
                text(c, "H:${temp(wx.hi)}  L:${temp(wx.lo)}", 322f, 130f, 13f, soft, 0, Paint.Align.RIGHT)
                sep(c, 142f)
                val n = min(6, wx.hourHour.size)
                val cw = (338f - 24f) / 6f
                for (i in 0 until n) {
                    val cx = 12f + cw * (i + 0.5f)
                    text(c, if (i == 0) "Now" else hourLabel(wx.hourHour[i]), cx, 163f, 11f, soft, 1, Paint.Align.CENTER)
                    WidgetArt.weather(c, wx.hourCode[i], wx.hourDay[i], cx, 192f, 22f)
                    text(c, temp(wx.hourTemp[i]), cx, 228f, 14f, white, 1, Paint.Align.CENTER)
                }
                sep(c, 240f)
                val days = min(5, wx.dayName.size)
                var gmin = Double.MAX_VALUE
                var gmax = -Double.MAX_VALUE
                for (i in 0 until days) { gmin = min(gmin, wx.dayLo[i]); gmax = max(gmax, wx.dayHi[i]) }
                val span = max(1.0, gmax - gmin)
                for (i in 0 until days) {
                    val y = 262f + i * 20.5f
                    text(c, wx.dayName[i], 16f, y, 14f, white, 1)
                    WidgetArt.weather(c, wx.dayCode[i], true, 100f, y - 5f, 17f)
                    text(c, temp(wx.dayLo[i]), 168f, y, 14f, soft, 0, Paint.Align.RIGHT)
                    val bx = 180f
                    val bw = 100f
                    bg.reset(); bg.isAntiAlias = true; bg.color = 0x33FFFFFF
                    rf.set(bx, y - 9f, bx + bw, y - 3f); c.drawRoundRect(rf, 3f, 3f, bg)
                    val l = bx + bw * ((wx.dayLo[i] - gmin) / span).toFloat()
                    val r = bx + bw * ((wx.dayHi[i] - gmin) / span).toFloat()
                    bg.shader = LinearGradient(bx, 0f, bx + bw, 0f, 0xFF64D2FF.toInt(), 0xFFFF9F0A.toInt(), Shader.TileMode.CLAMP)
                    rf.set(l, y - 9f, max(r, l + 6f), y - 3f); c.drawRoundRect(rf, 3f, 3f, bg)
                    bg.shader = null
                    text(c, temp(wx.dayHi[i]), 322f, y, 14f, white, 1, Paint.Align.RIGHT)
                }
            }
        }
    }

    private fun sep(c: Canvas, y: Float) {
        line.reset(); line.color = 0x33FFFFFF; line.strokeWidth = 0.6f
        c.drawLine(16f, y, 322f, y, line)
    }

    private fun hourLabel(h: Int): String =
        if (DateFormat.is24HourFormat(context)) String.format(Locale.US, "%02d", h) else (if (h % 12 == 0) 12 else h % 12).toString() + (if (h < 12) "AM" else "PM")

    // ------------------------------------------------------------------ calendar

    private fun calendar(c: Canvas, w: Float, h: Float) {
        val now = Calendar.getInstance()
        val red = 0xFFFF3B30.toInt()
        val weekday = SimpleDateFormat("EEEE", Locale.getDefault()).format(now.time).uppercase()
        val month = SimpleDateFormat("LLLL", Locale.getDefault()).format(now.time)
        val day = now.get(Calendar.DAY_OF_MONTH)
        when (size) {
            IosWidgets.Size.S, IosWidgets.Size.M -> {
                text(c, weekday, 16f, 30f, 13f, red, 2, Paint.Align.LEFT, 0.04f, 140f)
                text(c, day.toString(), 14f, 98f, 62f, fg, 3)
                text(c, month, 16f, 126f, 13f, fg2, 1, Paint.Align.LEFT, 0f, 140f)
                text(c, now.get(Calendar.YEAR).toString(), 16f, 143f, 12f, fg2, 0)
                if (size == IosWidgets.Size.M) monthGrid(c, 172f, 22f, 150f, 20f, 11.5f, 8.5f, month.uppercase(), true)
            }
            IosWidgets.Size.L -> monthGrid(c, 16f, 26f, 306f, 44f, 17f, 15f, month + " " + now.get(Calendar.YEAR), false)
        }
    }

    private fun monthGrid(c: Canvas, x: Float, y: Float, wd: Float, rowH: Float, numPt: Float, todayR: Float, title: String, small: Boolean) {
        val red = 0xFFFF3B30.toInt()
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_MONTH)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val first = cal.firstDayOfWeek
        val offset = (cal.get(Calendar.DAY_OF_WEEK) - first + 7) % 7
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val colW = wd / 7f
        text(c, title, x, y + if (small) 4f else 4f, if (small) 11f else 22f, if (small) red else fg, 2, Paint.Align.LEFT, if (small) 0.05f else 0f)
        val head = SimpleDateFormat("EEEEE", Locale.getDefault())
        val hy = y + if (small) 22f else 34f
        val tmp = Calendar.getInstance()
        for (i in 0 until 7) {
            tmp.set(Calendar.DAY_OF_WEEK, ((first - 1 + i) % 7) + 1)
            text(c, head.format(tmp.time), x + colW * (i + 0.5f), hy, if (small) 9.5f else 12f, fg2, 1, Paint.Align.CENTER)
        }
        val gy = hy + if (small) 18f else 34f
        for (d in 1..days) {
            val idx = offset + d - 1
            val cx = x + colW * (idx % 7 + 0.5f)
            val cy = gy + rowH * (idx / 7)
            if (d == today) {
                bg.reset(); bg.isAntiAlias = true; bg.color = red
                c.drawCircle(cx, cy - numPt * 0.36f, todayR, bg)
            }
            text(c, d.toString(), cx, cy, numPt, if (d == today) Color.WHITE else fg, if (d == today) 2 else 1, Paint.Align.CENTER)
        }
    }

    // ------------------------------------------------------------------ tablet

    private fun gb(b: Long): String = String.format(Locale.US, "%.1f", b / 1e9)

    private fun uptime(ms: Long): String {
        val m = ms / 60000L
        return if (m >= 1440) "${m / 1440}d ${(m % 1440) / 60}h" else "${m / 60}h ${m % 60}m"
    }

    private fun tablet(c: Canvas, w: Float, h: Float) {
        val s = WidgetData.sys
        val pct = WidgetData.batteryPct
        val blue = 0xFF0A84FF.toInt()
        val barCol = 0xFF5E5CE6.toInt()
        val bc = if (WidgetData.powerSave) yellow else if (pct in 0..20 && !WidgetData.charging) red else green
        val name = s?.deviceName ?: Build.MODEL
        val usedS = if (s != null) s.storageTotal - s.storageFree else 0L
        val usedR = if (s != null) s.ramTotal - s.ramFree else 0L
        when (size) {
            IosWidgets.Size.S -> {
                text(c, name, 16f, 27f, 14f, fg, 2, Paint.Align.LEFT, 0f, 126f)
                text(c, if (pct >= 0) "$pct%" else "--", 14f, 80f, 40f, fg, 3)
                if (WidgetData.charging) WidgetArt.bolt(c, 124f, 64f, 20f, green)
                text(c, if (s != null) "${gb(s.storageFree)} GB free" else "Storage", 16f, 122f, 12f, fg2, 1, Paint.Align.LEFT, 0f, 126f)
                if (s != null) bar(c, 16f, 132f, 126f, 6f, usedS.toFloat() / max(1L, s.storageTotal), blue)
            }
            IosWidgets.Size.M -> {
                text(c, name, 16f, 28f, 15f, fg, 2, Paint.Align.LEFT, 0f, 100f)
                text(c, "Android " + Build.VERSION.RELEASE, 16f, 46f, 12f, fg2, 0)
                ring(c, 44f, 100f, 26f, 6f, pct, bc, track())
                text(c, if (pct >= 0) "$pct" else "--", 44f, 106f, 15f, fg, 1, Paint.Align.CENTER)
                if (WidgetData.charging) WidgetArt.bolt(c, 72f, 76f, 12f, green)
                if (s != null) {
                    text(c, "Storage", 122f, 32f, 12f, fg, 1)
                    text(c, "${gb(usedS)} of ${gb(s.storageTotal)} GB", 322f, 32f, 12f, fg2, 0, Paint.Align.RIGHT)
                    bar(c, 122f, 42f, 200f, 9f, usedS.toFloat() / max(1L, s.storageTotal), blue)
                    text(c, "Memory", 122f, 82f, 12f, fg, 1)
                    text(c, "${gb(usedR)} of ${gb(s.ramTotal)} GB", 322f, 82f, 12f, fg2, 0, Paint.Align.RIGHT)
                    bar(c, 122f, 92f, 200f, 9f, usedR.toFloat() / max(1L, s.ramTotal), barCol)
                    text(c, "Up " + uptime(s.uptimeMs), 122f, 134f, 11f, fg2, 0)
                }
            }
            IosWidgets.Size.L -> {
                text(c, name, 16f, 32f, 19f, fg, 2, Paint.Align.LEFT, 0f, 200f)
                text(c, "Android " + Build.VERSION.RELEASE, 16f, 52f, 13f, fg2, 0)
                ring(c, 270f, 54f, 30f, 7f, pct, bc, track())
                text(c, if (pct >= 0) "$pct%" else "--", 270f, 60f, 16f, fg, 1, Paint.Align.CENTER)
                if (WidgetData.charging) WidgetArt.bolt(c, 304f, 20f, 14f, green)
                if (s != null) {
                    text(c, "Storage", 16f, 100f, 13f, fg, 1)
                    text(c, "${gb(usedS)} of ${gb(s.storageTotal)} GB", 322f, 100f, 13f, fg2, 0, Paint.Align.RIGHT)
                    bar(c, 16f, 110f, 306f, 11f, usedS.toFloat() / max(1L, s.storageTotal), blue)
                    text(c, "Memory", 16f, 150f, 13f, fg, 1)
                    text(c, "${gb(usedR)} of ${gb(s.ramTotal)} GB", 322f, 150f, 13f, fg2, 0, Paint.Align.RIGHT)
                    bar(c, 16f, 160f, 306f, 11f, usedR.toFloat() / max(1L, s.ramTotal), barCol)
                }
                val rows = listOf(
                    "Model" to Build.MODEL,
                    "Chipset" to Build.HARDWARE,
                    "Security patch" to (Build.VERSION.SECURITY_PATCH ?: "-"),
                    "Kernel" to (System.getProperty("os.version") ?: "-"),
                    "Uptime" to (if (s != null) uptime(s.uptimeMs) else "-")
                )
                for ((i, r) in rows.withIndex()) {
                    val y = 214f + i * 27f
                    text(c, r.first, 16f, y, 13f, fg2, 0)
                    text(c, r.second, 322f, y, 13f, fg, 1, Paint.Align.RIGHT, 0f, 200f)
                    if (i < rows.size - 1) { line.reset(); line.color = if (dark) 0x22FFFFFF else 0x16000000; line.strokeWidth = 0.6f; c.drawLine(16f, y + 9f, 322f, y + 9f, line) }
                }
            }
        }
    }
}

/** The widget gallery: swipe through the sizes of each widget, tap one to add it to the current page. */
object IosWidgetGallery {
    fun show(act: HomeActivity) {
        if (act.isFinishing) return
        val dp = act.resources.displayMetrics.density
        val sv = ScrollView(act)
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        sv.addView(col)
        val dlg = AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Widgets")
            .setView(sv)
            .setNeutralButton("Other apps' widgets") { _, _ -> act.widgets.showAndroidPicker() }
            .setNegativeButton("Close", null)
            .create()
        for (k in IosWidgets.kinds) {
            col.addView(label(act, k.title, 17f, Color.WHITE).also { it.setTypeface(it.typeface, Typeface.BOLD); it.setPadding(0, (14 * dp).toInt(), 0, 0) })
            col.addView(label(act, k.blurb, 12f, 0xFF9A9AA2.toInt()))
            val hs = HorizontalScrollView(act)
            hs.isHorizontalScrollBarEnabled = false
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            hs.addView(row)
            for (s in IosWidgets.Size.values()) {
                val pw = (if (s == IosWidgets.Size.S) 140 else 296) * dp
                val ph = pw * IosWidgets.refH(s) / IosWidgets.refW(s)
                val cell = LinearLayout(act)
                cell.orientation = LinearLayout.VERTICAL
                val prev = FrameLayout(act)
                prev.addView(IosWidgetFrame(act, k.id, s), FrameLayout.LayoutParams(-1, -1))
                // A transparent cover so the tap goes to the tile, not to the preview inside.
                val cover = View(act)
                cover.setOnClickListener { dlg.dismiss(); act.widgets.addIos(k.id, s) }
                prev.addView(cover, FrameLayout.LayoutParams(-1, -1))
                cell.addView(prev, LinearLayout.LayoutParams(pw.toInt(), ph.toInt()))
                cell.addView(label(act, when (s) { IosWidgets.Size.S -> "Small"; IosWidgets.Size.M -> "Medium"; else -> "Large" }, 11f, 0xFF9A9AA2.toInt()).also { it.gravity = Gravity.CENTER })
                row.addView(cell, LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = (10 * dp).toInt(); it.topMargin = (8 * dp).toInt() })
            }
            col.addView(hs)
        }
        dlg.show()
    }
}
