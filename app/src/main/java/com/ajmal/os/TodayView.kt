package com.ajmal.os

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Today View, like iPadOS: swipe right on the first home page and a strip of widgets slides in from the
 * left over a blurred home screen. Swipe left or tap outside the strip to go back. "Edit" opens the
 * widget settings.
 */
class TodayView(private val h: HomeActivity) {
    val view = FrameLayout(h)
    private val bd = GlassFrame(h)
    private val scroll = ScrollView(h)
    private val colW = min(h.screenW - h.dpi(24), h.dpi(396))
    private val slide get() = (colW + h.dpi(16)).toFloat()
    private val pull = PullLayout(h, 1, { false }, { false }, null, { close() }, { close(); true })
    private var closing = false
    private var progress = 0f
    private var anim: ValueAnimator? = null

    init {
        bd.fullscreen = true
        bd.strength = 1.6f
        view.addView(bd, FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(h)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(h.dpi(16), h.insetTop + h.dpi(22), h.dpi(16), h.insetBottom + h.dpi(30))
        val now = Date()
        val day = label(h, SimpleDateFormat("EEEE", Locale.getDefault()).format(now), 30f, Color.WHITE)
        day.setTypeface(day.typeface, Typeface.BOLD)
        col.addView(day)
        col.addView(label(h, SimpleDateFormat("d MMMM", Locale.getDefault()).format(now), 16f, 0xCCFFFFFF.toInt()))

        val cardW = colW - h.dpi(32)
        val cardH = (cardW * IosWidgets.refH(IosWidgets.Size.M) / IosWidgets.refW(IosWidgets.Size.M)).toInt()
        fun card(kind: String) {
            col.addView(IosWidgetFrame(h, kind, IosWidgets.Size.M), LinearLayout.LayoutParams(cardW, cardH).also { it.topMargin = h.dpi(14) })
        }
        val p = h.prefs
        if (p.bool(K.TD_CLOCK)) card("clock")
        if (p.bool(K.TD_WEATHER)) card("weather")
        if (p.bool(K.TD_BATT)) card("battery")
        if (p.bool(K.TD_TABLET)) card("tablet")
        if (p.bool(K.TD_CAL)) card("calendar")
        if (col.childCount <= 2) {
            val none = label(h, "No widgets selected.\nTap Edit to choose what Today View shows.", 15f, 0xCCFFFFFF.toInt())
            col.addView(none, LinearLayout.LayoutParams(-2, -2).also { it.topMargin = h.dpi(24) })
        }

        val edit = label(h, "Edit", 15f, Color.WHITE)
        edit.gravity = Gravity.CENTER
        edit.setPadding(h.dpi(26), h.dpi(9), h.dpi(26), h.dpi(9))
        edit.background = roundBg(0x44FFFFFF, h.dpf(20f))
        edit.setOnClickListener { close(); h.openSettings() }
        col.addView(edit, LinearLayout.LayoutParams(-2, -2).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = h.dpi(22) })

        scroll.addView(col)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        pull.addView(scroll, FrameLayout.LayoutParams(colW, -1, Gravity.START))
        view.addView(pull, FrameLayout.LayoutParams(-1, -1))
        applyProgress()
    }

    private fun applyProgress() {
        scroll.translationX = -(1f - progress) * slide
        bd.alpha = progress
    }

    private fun dur(ms: Long): Long =
        if (h.prefs.bool(K.REDUCE_MOTION)) 1L else (ms * 100f / h.prefs.int(K.ANIM_SPEED).coerceAtLeast(30)).toLong()

    private fun run(to: Float, ms: Long, end: () -> Unit) {
        anim?.let { it.removeAllListeners(); it.cancel() }
        val a = ValueAnimator.ofFloat(progress, to)
        a.duration = ms
        a.interpolator = if (to > progress) DecelerateInterpolator(1.7f) else AccelerateInterpolator(1.2f)
        a.addUpdateListener { progress = it.animatedValue as Float; applyProgress() }
        a.addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { end() } })
        anim = a
        a.start()
    }

    fun show() {
        h.overlayLayer.addView(view, FrameLayout.LayoutParams(-1, -1))
        run(1f, dur(340)) { }
    }

    fun close() {
        if (closing) return
        closing = true
        run(0f, dur(260)) {
            guardRun("today.remove") { h.overlayLayer.removeView(view) }
            h.todayGone()
        }
    }
}
