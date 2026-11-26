package com.ajmal.os

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SectionIndexer
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Slide/fade an overlay in and out with one spring. */
class OverlayAnim(
    private val view: View, private val off: Float, private val layer: ViewGroup,
    private val onGone: (() -> Unit)?
) {
    private val sp = Spring { t -> view.alpha = t; view.translationY = (1f - t) * off }
    private var closing = false

    fun show() {
        layer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sp.snap(0f)
        sp.to(1f, 260f)
    }

    fun hide() {
        if (closing) return
        closing = true
        sp.to(0f, 320f) { layer.removeView(view); onGone?.invoke() }
    }
}

/**
 * Wraps an overlay's content. A drag in direction [dir] (+1 = down, -1 = up) that starts while the
 * content is already at its scroll edge follows the finger and dismisses the overlay on release.
 * [onDismiss] returns true when the overlay is really going away (content then stays where it is).
 */
class PullLayout(
    ctx: Context, private val dir: Int,
    private val atEdge: () -> Boolean, private val onDismiss: () -> Boolean,
    private val onSwipeRight: (() -> Boolean)? = null,
    private val onTap: (() -> Unit)? = null,
    private val onSwipeLeft: (() -> Boolean)? = null
) : FrameLayout(ctx) {
    private val dp = ctx.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var edgeAtDown = false
    private var pulling = false
    private var horizontal = false
    private var hsign = 1
    private var tapOk = false
    private var vt: VelocityTracker? = null

    private fun wantsVertical(e: MotionEvent): Boolean {
        if (!edgeAtDown) return false
        val d = (e.rawY - downY) * dir
        return d > slop && d > abs(e.rawX - downX) * 1.3f
    }

    private fun wantsHorizontal(e: MotionEvent): Boolean {
        val d = e.rawX - downX
        val vert = abs(e.rawY - downY) * 1.3f
        return (onSwipeRight != null && d > slop && d > vert) || (onSwipeLeft != null && -d > slop && -d > vert)
    }

    private fun startPull(e: MotionEvent, horiz: Boolean) {
        pulling = true
        horizontal = horiz
        if (horiz) hsign = if (e.rawX - downX >= 0f) 1 else -1
        downX = e.rawX; downY = e.rawY
        lastX = e.rawX; lastY = e.rawY
        vt?.recycle()
        vt = VelocityTracker.obtain()
        vt?.addMovement(e)
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun reset() { animate().translationX(0f).translationY(0f).alpha(1f).setDuration(180).start() }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; edgeAtDown = atEdge(); pulling = false }
            MotionEvent.ACTION_MOVE -> if (!pulling) {
                if (wantsVertical(e)) { startPull(e, false); return true }
                if (wantsHorizontal(e)) { startPull(e, true); return true }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; edgeAtDown = atEdge(); pulling = false; tapOk = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pulling) {
                    if (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop) tapOk = false
                    if (wantsVertical(e)) startPull(e, false)
                    else if (wantsHorizontal(e)) startPull(e, true)
                    else return true
                }
                vt?.addMovement(e)
                lastX = e.rawX; lastY = e.rawY
                if (horizontal) {
                    val d = ((e.rawX - downX) * hsign).coerceAtLeast(0f)
                    translationX = d * 0.5f * hsign
                    alpha = (1f - d / (420f * dp)).coerceIn(0.35f, 1f)
                } else {
                    val d = ((e.rawY - downY) * dir).coerceAtLeast(0f)
                    translationY = d * 0.5f * dir
                    alpha = (1f - d / (420f * dp)).coerceIn(0.35f, 1f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pulling) {
                    pulling = false
                    var v = 0f
                    vt?.let {
                        it.computeCurrentVelocity(1000)
                        v = if (horizontal) it.xVelocity * hsign else it.yVelocity * dir
                        it.recycle()
                    }
                    vt = null
                    val up = e.actionMasked == MotionEvent.ACTION_UP
                    val handled = if (horizontal) {
                        val d = ((lastX - downX) * hsign).coerceAtLeast(0f)
                        up && (d > 100f * dp || v > 1300f) && ((if (hsign > 0) onSwipeRight?.invoke() else onSwipeLeft?.invoke()) == true)
                    } else {
                        val d = ((lastY - downY) * dir).coerceAtLeast(0f)
                        up && (d > 90f * dp || v > 1400f) && onDismiss()
                    }
                    if (!handled) reset()
                } else if (e.actionMasked == MotionEvent.ACTION_UP && tapOk) {
                    onTap?.invoke()
                }
            }
        }
        return true
    }
}

class Row(val type: Int, val text: String, val app: AppInfo?)

class AppRowView(ctx: Context) : View(ctx) {
    private val app = LauncherApp.get()
    private val dp = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPx = ctx.dpi(44)
    private var row: Row? = null
    private var bmp: Bitmap? = null
    private var key = ""

    fun bind(r: Row) {
        row = r
        bmp = null
        val a = r.app
        if (a != null) {
            val k = app.icons.key(a, iconPx)
            key = k
            bmp = app.icons.peek(k)
            if (bmp == null) app.icons.request(a, k, iconPx) {
                if (key == k) { bmp = app.icons.peek(k); invalidate() }
            }
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val hh = if (row?.type == 0) (30 * dp).toInt() else (60 * dp).toInt()
        setMeasuredDimension(MeasureSpec.getSize(w), hh)
    }

    override fun onDraw(c: Canvas) {
        val r = row ?: return
        val fg = if (app.isDark()) Color.WHITE else 0xFF1C1C1E.toInt()
        when (r.type) {
            0 -> {
                p.color = fg; p.alpha = 150; p.textSize = 13f * dp; p.isFakeBoldText = true
                c.drawText(r.text, 20 * dp, height - 8 * dp, p)
                p.isFakeBoldText = false
            }
            2 -> {
                p.color = fg; p.textSize = 16f * dp
                c.drawText("↺  " + r.text, 20 * dp, height / 2f + 6 * dp, p)
            }
            else -> {
                val b = bmp
                val tot = iconPx + 2 * IconCache.padFor(iconPx)
                if (b != null) c.drawBitmap(b, 14 * dp, (height - tot) / 2f, null)
                p.color = fg; p.alpha = 255; p.textSize = 17f * dp
                val tx = 14 * dp + tot + 8 * dp
                val label = TextUtils.ellipsize(r.text, TextPaint(p), width - tx - 12 * dp, TextUtils.TruncateAt.END)
                c.drawText(label.toString(), tx, height / 2f + 6 * dp, p)
            }
        }
    }
}

class RowAdapter(private val ctx: Context) : BaseAdapter(), SectionIndexer {
    var rows: List<Row> = emptyList()
    private var secNames: Array<Any> = emptyArray()
    private var secPos = IntArray(0)

    fun set(list: List<Row>, letters: Boolean) {
        rows = list
        if (letters) {
            val n = ArrayList<Any>(); val p = ArrayList<Int>()
            for ((i, r) in list.withIndex()) if (r.type == 0) { n.add(r.text); p.add(i) }
            secNames = n.toTypedArray(); secPos = p.toIntArray()
        } else { secNames = emptyArray(); secPos = IntArray(0) }
        notifyDataSetChanged()
    }

    override fun getCount() = rows.size
    override fun getItem(i: Int): Any = rows[i]
    override fun getItemId(i: Int) = i.toLong()
    override fun getViewTypeCount() = 3
    override fun getItemViewType(i: Int) = rows[i].type
    override fun isEnabled(position: Int) = rows[position].type != 0
    override fun getView(i: Int, cv: View?, parent: ViewGroup): View {
        val v = (cv as? AppRowView) ?: AppRowView(ctx).also {
            it.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        v.bind(rows[i])
        return v
    }

    override fun getSections(): Array<Any> = secNames
    override fun getPositionForSection(sectionIndex: Int): Int =
        if (secPos.isEmpty()) 0 else secPos[sectionIndex.coerceIn(0, secPos.size - 1)]
    override fun getSectionForPosition(position: Int): Int {
        var s = 0
        for (i in secPos.indices) if (secPos[i] <= position) s = i
        return s
    }
}

fun sectionRows(list: List<AppInfo>): List<Row> {
    val out = ArrayList<Row>()
    var last = ""
    for (a in list) {
        val ch = a.label.firstOrNull()?.uppercaseChar()
        val l = if (ch != null && ch.isLetter()) ch.toString() else "#"
        if (l != last) { out.add(Row(0, l, null)); last = l }
        out.add(Row(1, a.label, a))
    }
    return out
}

class MagnifierDrawable(private val color: Int, private val px: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.color = color
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        p.strokeWidth = w * 0.11f
        c.drawCircle(b.left + w * 0.43f, b.top + w * 0.43f, w * 0.27f, p)
        c.drawLine(b.left + w * 0.64f, b.top + w * 0.64f, b.left + w * 0.86f, b.top + w * 0.86f, p)
    }

    override fun setAlpha(a: Int) { p.alpha = Color.alpha(color) * a / 255 }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = px
    override fun getIntrinsicHeight(): Int = px
}

private fun styleEdit(h: HomeActivity, hint: String): EditText {
    val e = EditText(h)
    e.hint = hint
    e.setSingleLine(true)
    e.inputType = InputType.TYPE_CLASS_TEXT
    e.imeOptions = EditorInfo.IME_ACTION_SEARCH
    val dark = h.app.isDark()
    val sub = if (dark) 0xB3FFFFFF.toInt() else 0xB3000000.toInt()
    e.setTextColor(if (dark) Color.WHITE else 0xFF1C1C1E.toInt())
    e.setHintTextColor(sub)
    e.background = roundBg(if (dark) 0x40FFFFFF else 0x26000000, h.dpf(22f))
    e.setPadding(h.dpi(14), h.dpi(10), h.dpi(14), h.dpi(10))
    e.compoundDrawablePadding = h.dpi(8)
    e.setCompoundDrawablesRelativeWithIntrinsicBounds(MagnifierDrawable(sub, h.dpi(17)), null, null, null)
    e.textSize = 16f
    return e
}

private fun styleList(lv: ListView) {
    lv.divider = null
    lv.setSelector(android.R.color.transparent)
    lv.cacheColorHint = 0
    lv.overScrollMode = View.OVER_SCROLL_NEVER
}

private fun hideIme(h: HomeActivity, v: View) {
    (h.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
}

/** Shared bitmap lookup that asks the icon cache for missing icons once and repaints when they arrive. */
internal class IconSource(private val v: View) {
    private val app = LauncherApp.get()
    private val pending = HashSet<String>()

    fun get(a: AppInfo, size: Int): Bitmap? {
        val k = app.icons.key(a, size)
        val b = app.icons.peek(k)
        if (b == null && pending.add(k)) app.icons.request(a, k, size) { pending.remove(k); v.invalidate() }
        return b
    }
}

/**
 * One App Library card in the style of iOS: a frosted squircle holding four app slots. The first three
 * are full-size icons; the fourth is either a fourth icon or a 2x2 cluster of mini icons that opens the
 * whole category. The category name sits under the card.
 */
class CategoryCardView(
    ctx: Context, private val h: HomeActivity, private val title: String,
    private val apps: List<AppInfo>, private val cardPx: Int,
    private val onOpen: () -> Unit, private val onLaunched: () -> Unit,
    private val locked: Boolean = false
) : View(ctx) {
    /** True when a liquid glass slab is drawn behind this card, so the card skips its own flat background. */
    var glassBacked = false
    private val app = LauncherApp.get()
    private val dp = ctx.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val src = IconSource(this)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val path = Path()
    private val pad = cardPx * 0.085f
    private val gap = cardPx * 0.075f
    private val slot = (cardPx - 2 * pad - gap) / 2f
    private val titleH = 26f * dp
    private val bigOnly = apps.size <= 4
    private var pressedSlot = -1
    private var tapSlot = -1
    private var moved = false
    private var longFired = false
    private var downX = 0f
    private var downY = 0f
    private val lp = Runnable {
        val a = appAt(tapSlot)
        if (a != null) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            h.appActions(a)
        }
    }

    private fun appAt(s: Int): AppInfo? = if (locked) null else when {
        s in 0..2 -> apps.getOrNull(s)
        s == 3 && bigOnly -> apps.getOrNull(3)
        else -> null
    }

    private fun slotX(i: Int) = pad + (i % 2) * (slot + gap)
    private fun slotY(i: Int) = pad + (i / 2) * (slot + gap)

    private fun hit(x: Float, y: Float): Int {
        for (i in 0..3) {
            val l = slotX(i); val t = slotY(i)
            if (x >= l && x <= l + slot && y >= t && y <= t + slot) return i
        }
        return -1
    }

    override fun onMeasure(w: Int, hh: Int) {
        setMeasuredDimension(cardPx, (cardPx + titleH).toInt())
    }

    private fun drawIcon(c: Canvas, a: AppInfo, x: Float, y: Float, box: Float, alpha: Int) {
        val size = box.toInt()
        val b = src.get(a, size) ?: return
        val pd = IconCache.padFor(size)
        bp.alpha = alpha
        c.drawBitmap(b, x + (box - size) / 2f - pd, y + (box - size) / 2f - pd, bp)
    }

    /** A padlock instead of icons: the card never reveals which apps are hidden. */
    private fun drawLock(c: Canvas, fg: Int) {
        val cx = cardPx / 2f
        val bw = cardPx * 0.30f
        val bh = cardPx * 0.22f
        val top = cardPx / 2f - bh / 2f + cardPx * 0.06f
        bg.style = Paint.Style.FILL
        bg.color = (fg and 0x00FFFFFF) or (0xD0 shl 24)
        rf.set(cx - bw / 2f, top, cx + bw / 2f, top + bh)
        c.drawRoundRect(rf, bw * 0.14f, bw * 0.14f, bg)
        bg.style = Paint.Style.STROKE
        bg.strokeWidth = cardPx * 0.045f
        bg.strokeCap = Paint.Cap.ROUND
        val sw = bw * 0.62f
        rf.set(cx - sw / 2f, top - bh * 0.95f, cx + sw / 2f, top + bh * 0.2f)
        c.drawArc(rf, 180f, 180f, false, bg)
        c.drawLine(cx - sw / 2f, top - bh * 0.37f, cx - sw / 2f, top + 2f, bg)
        c.drawLine(cx + sw / 2f, top - bh * 0.37f, cx + sw / 2f, top + 2f, bg)
        bg.style = Paint.Style.FILL
    }

    override fun onDraw(c: Canvas) {
        val dark = app.isDark()
        val fg = if (dark) Color.WHITE else 0xFF1C1C1E.toInt()
        if (!glassBacked) {
            rf.set(0f, 0f, cardPx.toFloat(), cardPx.toFloat())
            val r = cardPx * 0.2f
            bg.style = Paint.Style.FILL
            bg.color = if (dark) Color.argb(58, 255, 255, 255) else Color.argb(118, 255, 255, 255)
            c.drawPath(Squircle.path(rf, r, path), bg)
            bg.style = Paint.Style.STROKE
            bg.strokeWidth = dp
            bg.color = Color.argb(if (dark) 40 else 90, 255, 255, 255)
            rf.set(dp / 2f, dp / 2f, cardPx - dp / 2f, cardPx - dp / 2f)
            c.drawPath(Squircle.path(rf, r, path), bg)
        }

        val big = if (bigOnly) 4 else 3
        if (locked) drawLock(c, fg)
        else for (i in 0 until big) {
            val a = apps.getOrNull(i) ?: break
            drawIcon(c, a, slotX(i), slotY(i), slot, if (pressedSlot == i) 150 else 255)
        }
        if (!bigOnly && !locked) {
            val g2 = gap * 0.55f
            val mini = (slot - g2) / 2f
            val x0 = slotX(3); val y0 = slotY(3)
            for (j in 0 until 4) {
                val a = apps.getOrNull(3 + j) ?: break
                drawIcon(c, a, x0 + (j % 2) * (mini + g2), y0 + (j / 2) * (mini + g2), mini, if (pressedSlot == 3) 150 else 255)
            }
        }

        tp.textSize = 12.5f * dp
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.color = fg
        tp.alpha = 215
        val shown = TextUtils.ellipsize(title, tp, cardPx - 10f * dp, TextUtils.TruncateAt.END).toString()
        c.drawText(shown, 7f * dp, cardPx + titleH * 0.72f, tp)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                moved = false; longFired = false
                tapSlot = if (e.y <= cardPx) hit(e.x, e.y) else -1
                pressedSlot = tapSlot
                invalidate()
                if (appAt(tapSlot) != null) postDelayed(lp, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                    moved = true
                    removeCallbacks(lp)
                    pressedSlot = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(lp)
                if (!moved && !longFired) {
                    val a = appAt(tapSlot)
                    if (a != null) { h.launch(a, this); onLaunched() } else onOpen()
                }
                pressedSlot = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(lp)
                pressedSlot = -1
                invalidate()
            }
        }
        return true
    }
}

/** Icon grid with labels, used inside a category sheet. */
class AppGridView(
    ctx: Context, private val h: HomeActivity, private val apps: List<AppInfo>,
    private val cols: Int, iconDp: Int, private val onLaunched: () -> Unit
) : View(ctx) {
    private val app = LauncherApp.get()
    private val dp = ctx.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val src = IconSource(this)
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val iconPx = (iconDp * dp).toInt()
    private val total = iconPx + 2 * IconCache.padFor(iconPx)
    private val cellH = total + 22f * dp
    private var downIdx = -1
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var longFired = false
    private val lp = Runnable {
        val a = apps.getOrNull(downIdx)
        if (a != null) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            h.appActions(a)
        }
    }

    fun gridHeight(): Int = (ceil(apps.size / cols.toFloat()) * cellH).toInt()

    override fun onMeasure(w: Int, hh: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), gridHeight())
    }

    override fun onDraw(c: Canvas) {
        val cw = width / cols.toFloat()
        tp.textSize = 11f * dp
        tp.textAlign = Paint.Align.CENTER
        tp.color = if (app.isDark()) Color.WHITE else 0xFF1C1C1E.toInt()
        for ((i, a) in apps.withIndex()) {
            val cx = (i % cols + 0.5f) * cw
            val top = (i / cols) * cellH
            val b = src.get(a, iconPx)
            if (b != null) c.drawBitmap(b, cx - total / 2f, top, bp)
            val s = TextUtils.ellipsize(a.label, tp, cw - 6f * dp, TextUtils.TruncateAt.END).toString()
            c.drawText(s, cx, top + total + 11f * dp, tp)
        }
    }

    private fun indexAt(x: Float, y: Float): Int {
        val cw = width / cols.toFloat()
        val i = (x / cw).toInt() + (y / cellH).toInt() * cols
        return if (i in apps.indices) i else -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false; longFired = false
                downIdx = indexAt(e.x, e.y)
                if (downIdx >= 0) postDelayed(lp, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> if (!moved && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                moved = true; removeCallbacks(lp)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(lp)
                val a = apps.getOrNull(downIdx)
                if (!moved && !longFired && a != null) { h.launch(a, this); onLaunched() }
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(lp)
        }
        return true
    }
}

private class Group(val title: String, val display: List<AppInfo>, val all: List<AppInfo>, val locked: Boolean = false)

/**
 * App Library in the style of iOS: search bar on top, a 2-column grid of category cards. Tapping the
 * search bar switches to an A-Z list. Pulling down from the top returns to the home screen.
 */
class LibraryOverlay(private val h: HomeActivity, private val srcView: View? = null) {
    val view = FrameLayout(h)
    private val content = LinearLayout(h)
    private val edit = styleEdit(h, "App Library")
    private val cancel = label(h, "Cancel", 16f, if (h.app.isDark()) Color.WHITE else 0xFF1C1C1E.toInt())
    private val scroll = ScrollView(h)
    private val gridHost = LinearLayout(h)
    private val list = ListView(h)
    private val adapter = RowAdapter(h)
    private var searching = false
    private var query = ""
    private var sheet: FrameLayout? = null
    private val pull = PullLayout(
        h, 1,
        { if (searching) !list.canScrollVertically(-1) else !scroll.canScrollVertically(-1) },
        { if (searching) { exitSearch(); false } else { close(); true } },
        { close(); true }   // swipe right: back to the home screen
    )
    private var closing = false
    private var genie: GenieView? = null
    private var snap: Bitmap? = null

    init {
        val bd = GlassFrame(h)
        bd.fullscreen = true
        bd.strength = 1.5f
        view.addView(bd, FrameLayout.LayoutParams(-1, -1))

        content.orientation = LinearLayout.VERTICAL
        content.setPadding(h.dpi(16), h.insetTop + h.dpi(12), h.dpi(16), h.insetBottom)

        val head = LinearLayout(h)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
        cancel.setPadding(h.dpi(14), h.dpi(8), h.dpi(2), h.dpi(8))
        cancel.visibility = View.GONE
        cancel.setOnClickListener { exitSearch() }
        head.addView(cancel)
        content.addView(head)

        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.setOnScrollChangeListener { _, _, y, _, oldY -> if (y != oldY) dirty = true }
        gridHost.orientation = LinearLayout.VERTICAL
        scroll.addView(gridHost, FrameLayout.LayoutParams(-1, -2))
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).also { it.topMargin = h.dpi(16) })

        styleList(list)
        list.isFastScrollEnabled = true
        list.adapter = adapter
        list.visibility = View.GONE
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).also { it.topMargin = h.dpi(8) })

        pull.isFocusableInTouchMode = true
        // Tablets: keep the library a comfortable width and centred.
        pull.addView(content, FrameLayout.LayoutParams(min(h.screenW, h.dpi(980)), -1, Gravity.CENTER_HORIZONTAL))
        view.addView(pull, FrameLayout.LayoutParams(-1, -1))

        edit.setOnFocusChangeListener { _, f -> if (f) enterSearch() }
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                if (query.isNotEmpty() && !searching) enterSearch()
                if (searching) rebuildList()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        edit.setOnEditorActionListener { _, _, _ ->
            val first = adapter.rows.firstOrNull { it.type == 1 }?.app
            if (first != null) { h.launch(first, null); close() }
            true
        }
        list.setOnItemClickListener { _, _, i, _ ->
            val a = adapter.rows[i].app
            if (a != null) { h.launch(a, null); close() }
        }
        list.setOnItemLongClickListener { _, _, i, _ ->
            val a = adapter.rows[i].app
            if (a != null) h.appActions(a)
            a != null
        }
        buildGrid()
    }

    private fun dur(ms: Long): Long = (ms * 100f / h.prefs.int(K.GENIE_SPEED).coerceAtLeast(20)).toLong()

    private fun srcRect(): RectF {
        val v = srcView
        val s: Float
        val cx: Float
        val cy: Float
        if (v != null && v.isAttachedToWindow) {
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            s = h.dockIconPx.toFloat().coerceAtLeast(h.dpf(40f))
            cx = loc[0] + v.width / 2f
            cy = loc[1] + v.height / 2f
        } else {
            s = h.dpf(56f)
            cx = h.screenW / 2f
            cy = h.screenH - h.dpf(60f)
        }
        return RectF(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f)
    }

    private fun snapshot(): Bitmap? {
        val w = view.width
        val hh = view.height
        if (w <= 0 || hh <= 0) return null
        return try {
            val b = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            b
        } catch (t: Throwable) { null }
    }

    private var cleanSnap: Bitmap? = null
    private var dirty = false
    private var openedWarm = true

    /** Opens with a genie animation out of the dock's library icon, as soon as the first layout is done. */
    fun show() {
        h.overlayLayer.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.visibility = View.INVISIBLE
        openedWarm = warmed
        view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (view.width <= 0) return
                view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                // Not pre-warmed yet (first seconds after start): give the icon cache a moment.
                if (warmed) genieOpen() else view.postDelayed({ genieOpen() }, 110)
                warmed = true
            }
        })
    }

    private fun genieOpen() {
        if (closing || view.parent == null) return
        val bmp = if (h.prefs.bool(K.REDUCE_MOTION) || Safe.mode) null else snapshot()
        if (bmp == null) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(160).start()
            return
        }
        val gv = GenieView(h, bmp, srcRect())
        genie = gv
        snap = bmp
        cleanSnap = bmp        // kept while the library is untouched, so closing needs no new snapshot
        if (!openedWarm) dirty = true   // icons may still have been loading in the picture
        h.overlayLayer.addView(gv, FrameLayout.LayoutParams(-1, -1))
        gv.animateTo(1f, dur(440)) {
            view.visibility = View.VISIBLE
            h.overlayLayer.removeView(gv)
            genie = null
            snap = null
        }
    }

    private fun finishClose() {
        genie?.let { h.overlayLayer.removeView(it) }
        genie = null
        snap?.recycle()
        snap = null
        cleanSnap?.recycle()
        cleanSnap = null
        h.overlayLayer.removeView(view)
        h.libraryGone()
    }

    fun close() {
        if (closing) return
        closing = true
        hideIme(h, edit)
        val hadSheet = sheet != null
        sheet?.let { view.removeView(it) }
        sheet = null
        val running = genie
        if (running != null) {          // closed while still opening: reverse from where it is
            running.animateTo(0f, dur(300)) { finishClose() }
            return
        }
        val reduce = h.prefs.bool(K.REDUCE_MOTION) || Safe.mode
        val pulled = pull.translationX != 0f || pull.translationY != 0f || pull.alpha < 0.999f
        val reuse = cleanSnap
        val bmp: Bitmap? = when {
            reduce -> null
            // Untouched since opening: reuse the picture from the opening, so the animation starts instantly.
            reuse != null && !reuse.isRecycled && !dirty && !pulled && !searching && !hadSheet -> reuse
            else -> { reuse?.recycle(); cleanSnap = null; snapshot() }
        }
        if (bmp == null) {
            view.animate().alpha(0f).setDuration(140).withEndAction { finishClose() }.start()
            return
        }
        val gv = GenieView(h, bmp, srcRect())
        gv.t = 1f
        genie = gv
        snap = bmp
        h.overlayLayer.addView(gv, FrameLayout.LayoutParams(-1, -1))
        view.visibility = View.INVISIBLE
        gv.animateTo(0f, dur(340)) { finishClose() }
    }

    companion object {
        private var warmed = false

        /** Builds the library once off-screen, so its icons are cached and the first open has no wait. */
        fun prewarm(h: HomeActivity) {
            if (warmed || Safe.mode) return
            guardRun("library.prewarm") {
                val w = h.screenW
                val hh = h.screenH
                if (w <= 0 || hh <= 0) return@guardRun
                val lo = LibraryOverlay(h, null)
                lo.view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hh, View.MeasureSpec.EXACTLY))
                lo.view.layout(0, 0, w, hh)
                val b = Bitmap.createBitmap(max(1, w / 8), max(1, hh / 8), Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                c.scale(1f / 8f, 1f / 8f)
                lo.view.draw(c)
                b.recycle()
                h.window.decorView.postDelayed({ warmed = true }, 800)   // icons are rendered by then
            }
        }
    }

    /** Back button: close the category sheet, then the search list, then the library. */
    fun back() {
        when {
            sheet != null -> closeSheet()
            searching -> exitSearch()
            else -> close()
        }
    }

    fun setBottomInset(px: Int) {
        content.setPadding(h.dpi(16), h.insetTop + h.dpi(12), h.dpi(16), if (px > h.insetBottom) px else h.insetBottom)
    }

    fun reload() {
        dirty = true
        buildGrid()
        if (searching) rebuildList()
    }

    private fun enterSearch() {
        if (searching) return
        dirty = true
        searching = true
        scroll.visibility = View.GONE
        list.visibility = View.VISIBLE
        cancel.visibility = View.VISIBLE
        rebuildList()
    }

    private fun exitSearch() {
        dirty = true
        searching = false
        edit.setText("")
        hideIme(h, edit)
        pull.requestFocus()
        list.visibility = View.GONE
        cancel.visibility = View.GONE
        scroll.visibility = View.VISIBLE
    }

    private fun rebuildList() {
        val q = query.trim().lowercase()
        val hidden = h.model.hiddenApps
        val all = h.model.apps.filter { !hidden.contains(it.key) }   // Hidden apps never show up in the list
        adapter.set(sectionRows(if (q.isEmpty()) all else all.filter { it.matches(q) }), true)
    }

    private fun buildGrid() {
        gridHost.removeAllViews()
        val m = h.model
        val hidden = m.hiddenApps
        val vis = m.apps.filter { !hidden.contains(it.key) }
        val byUse = compareByDescending<AppInfo> { m.usage[it.key] ?: 0 }.thenBy { it.lower }

        val groups = ArrayList<Group>()
        val freq = m.frequent().filter { !hidden.contains(it.key) }
        if (freq.isNotEmpty()) groups.add(Group("Suggestions", freq.take(4), freq))
        val rec = vis.sortedByDescending { it.installTime }.take(15)
        if (rec.isNotEmpty()) groups.add(Group("Recently Added", rec.take(4), rec))
        for (cat in m.categories()) {
            val l = vis.filter { m.categoryOf(it) == cat }.sortedWith(byUse)
            if (l.isNotEmpty()) groups.add(Group(cat, l, l.sortedBy { it.lower }))
        }
        val hid = m.apps.filter { hidden.contains(it.key) }
        // Always present (shows a padlock, never its contents), so its existence does not reveal whether anything is hidden.
        groups.add(Group("Hidden", emptyList(), hid.sortedBy { it.lower }, true))

        val gap = h.dpi(14)
        val avail = min(h.screenW, h.dpi(980)) - h.dpi(32)
        val n = (avail / h.dpi(165)).coerceIn(2, 5)
        val cw = (avail - gap * (n - 1)) / n
        for (pair in groups.chunked(n)) {
            val row = LinearLayout(h)
            row.orientation = LinearLayout.HORIZONTAL
            row.clipChildren = false
            for ((i, g) in pair.withIndex()) {
                val onOpen: () -> Unit = if (g.locked) ({ VaultUi.unlock(h) { openSheet(g.title, g.all) } }) else ({ openSheet(g.title, g.all) })
                val card = CategoryCardView(h, h, g.title, g.display, cw, onOpen, { close() }, g.locked)
                card.glassBacked = true
                // Liquid Glass slab behind the card (the card view only draws icons + title on top of it).
                val slab = GlassFrame(h)
                slab.liquid = true
                slab.squish = true
                slab.pullable = false
                slab.radiusDp = cw * 0.2f / h.resources.displayMetrics.density
                slab.shadowScale = 0.6f
                slab.glassH = cw
                slab.clipChildren = false
                slab.addView(card, FrameLayout.LayoutParams(-1, -2))
                val lp = LinearLayout.LayoutParams(cw, -2)
                if (i < n - 1) lp.rightMargin = gap
                row.addView(slab, lp)
            }
            gridHost.addView(row, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = h.dpi(6) })
        }
        gridHost.addView(View(h), LinearLayout.LayoutParams(-1, h.dpi(24)))
    }

    private fun openSheet(title: String, apps: List<AppInfo>) {
        if (sheet != null) return
        val fg = if (h.app.isDark()) Color.WHITE else 0xFF1C1C1E.toInt()
        val dm = h.resources.displayMetrics
        val layer = FrameLayout(h)
        layer.setBackgroundColor(0x66000000)
        layer.setOnClickListener { closeSheet() }

        val panel = GlassFrame(h)
        panel.radiusDp = 30f
        panel.strength = 1.3f
        panel.liquid = true
        panel.squish = true
        panel.pullable = false
        val col = LinearLayout(h)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(h.dpi(16), h.dpi(18), h.dpi(16), h.dpi(12))
        val t = label(h, title, 20f, fg)
        t.setTypeface(t.typeface, Typeface.BOLD)
        col.addView(t)
        val panelW = min(h.screenW - h.dpi(32), h.dpi(560))
        val sheetCols = ((panelW - h.dpi(32)) / h.dpi(88)).coerceIn(4, 6)
        val grid = AppGridView(h, h, apps, sheetCols, 60) { close() }
        val sv = ScrollView(h)
        sv.isVerticalScrollBarEnabled = false
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.addView(grid, FrameLayout.LayoutParams(-1, -2))
        val maxH = (dm.heightPixels * 0.6f).toInt()
        col.addView(sv, LinearLayout.LayoutParams(-1, min(grid.gridHeight(), maxH)).also { it.topMargin = h.dpi(10) })
        panel.addView(col, FrameLayout.LayoutParams(-1, -2))
        layer.addView(panel, FrameLayout.LayoutParams(panelW, -2, Gravity.CENTER))
        view.addView(layer, FrameLayout.LayoutParams(-1, -1))

        layer.alpha = 0f
        panel.scaleX = 0.92f; panel.scaleY = 0.92f
        layer.animate().alpha(1f).setDuration(170).start()
        panel.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        sheet = layer
    }

    private fun closeSheet() {
        val s = sheet ?: return
        sheet = null
        s.animate().alpha(0f).setDuration(140).withEndAction { view.removeView(s) }.start()
    }
}

/** Lightweight local search: instant filtering by name, recents, usage-based suggestions. Pull up to close. */
class SearchOverlay(private val h: HomeActivity) {
    val view = FrameLayout(h)
    private val content = LinearLayout(h)
    private val edit = styleEdit(h, "Search")
    private val list = ListView(h)
    private val adapter = RowAdapter(h)
    private var query = ""
    private val pull = PullLayout(h, -1, { !list.canScrollVertically(1) }, { close(); true }, null, { close() })
    private val anim = OverlayAnim(view, -h.dpf(40f), h.overlayLayer) { h.searchGone() }

    init {
        val bd = GlassFrame(h)
        bd.fullscreen = true
        bd.strength = 1.5f
        view.addView(bd, FrameLayout.LayoutParams(-1, -1))
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(h.dpi(16), h.insetTop + h.dpi(12), h.dpi(16), h.insetBottom)
        val fg = if (h.app.isDark()) Color.WHITE else 0xFF1C1C1E.toInt()
        val head = LinearLayout(h)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
        val clear = label(h, "Cancel", 16f, fg)
        clear.setPadding(h.dpi(14), h.dpi(8), h.dpi(2), h.dpi(8))
        clear.setOnClickListener { if (query.isNotEmpty()) edit.setText("") else close() }
        head.addView(clear)
        content.addView(head)
        styleList(list)
        list.adapter = adapter
        // A tap on the blank part of the list (below the results) goes back to the home screen.
        val tapSlop = ViewConfiguration.get(h).scaledTouchSlop
        list.setOnTouchListener(object : View.OnTouchListener {
            private var dx = 0f
            private var dy = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx = e.x; dy = e.y }
                    MotionEvent.ACTION_UP -> if (abs(e.x - dx) < tapSlop && abs(e.y - dy) < tapSlop &&
                        list.pointToPosition(e.x.toInt(), e.y.toInt()) == android.widget.AdapterView.INVALID_POSITION) {
                        v.post { close() }
                    }
                }
                return false
            }
        })
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).also { it.topMargin = h.dpi(8) })
        pull.addView(content, FrameLayout.LayoutParams(min(h.screenW, h.dpi(640)), -1, Gravity.CENTER_HORIZONTAL))
        view.addView(pull, FrameLayout.LayoutParams(-1, -1))

        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; rebuild() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        edit.setOnEditorActionListener { _, _, _ ->
            val first = adapter.rows.firstOrNull { it.type == 1 }?.app
            if (first != null) pick(first)
            true
        }
        list.setOnItemClickListener { _, _, i, _ ->
            val r = adapter.rows[i]
            if (r.type == 2) edit.setText(r.text).also { edit.setSelection(edit.text.length) }
            else if (r.app != null) pick(r.app)
        }
        list.setOnItemLongClickListener { _, _, i, _ ->
            val a = adapter.rows[i].app
            if (a != null) h.appActions(a)
            a != null
        }
        rebuild()
    }

    private fun pick(a: AppInfo) {
        h.model.noteSearch(query)
        h.launch(a, null)
        close()
    }

    fun show() {
        anim.show()
        edit.postDelayed({
            edit.requestFocus()
            (h.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }, 120)
    }

    fun close() {
        hideIme(h, edit)
        anim.hide()
    }

    fun setBottomInset(px: Int) {
        content.setPadding(h.dpi(16), h.insetTop + h.dpi(12), h.dpi(16), if (px > h.insetBottom) px else h.insetBottom)
    }

    private fun rebuild() {
        val m = h.model
        val q = query.trim().lowercase()
        val out = ArrayList<Row>()
        val hiddenSet = m.hiddenApps
        if (q.isEmpty()) {
            val fr = m.frequent().filter { !hiddenSet.contains(it.key) }.take(6)
            if (fr.isNotEmpty()) {
                out.add(Row(0, "SUGGESTIONS", null))
                for (a in fr) out.add(Row(1, a.label, a))
            }
            if (m.searches.isNotEmpty()) {
                out.add(Row(0, "RECENT SEARCHES", null))
                for (s in m.searches) out.add(Row(2, s, null))
            }
        } else {
            val hits = m.apps.filter { !hiddenSet.contains(it.key) && it.matches(q) }
                .sortedWith(compareBy<AppInfo>({ if (it.lower.startsWith(q)) 0 else 1 }, { -(m.usage[it.key] ?: 0) }, { it.lower }))
            for (a in hits.take(60)) out.add(Row(1, a.label, a))
        }
        adapter.set(out, false)
    }
}
