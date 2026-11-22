package com.ajmal.os

import android.Manifest
import android.app.Activity
import android.content.Context
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Every row here writes a Prefs key that HomeActivity re-reads on resume. No decorative switches. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private val fg = Color.WHITE
    private val sub = 0xFF8E8E93.toInt()
    private var preview: GlassPreviewCard? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(UiScale.wrap(base))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = LauncherApp.get().prefs
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF0E0F13.toInt())
        root.fitsSystemWindows = true
        val maxW = Math.min(resources.displayMetrics.widthPixels, dpi(680))

        // Live preview, pinned above the list so every slider below can be judged while it moves.
        val pw = FrameLayout(this)
        val card = GlassPreviewCard(this)
        preview = card
        pw.addView(card, FrameLayout.LayoutParams(maxW - dpi(32), dpi(188), android.view.Gravity.CENTER_HORIZONTAL).also {
            it.topMargin = dpi(8)
        })
        root.addView(pw, LinearLayout.LayoutParams(-1, -2))
        val hide = label(this, "Hide live preview", 12f, sub)
        hide.gravity = android.view.Gravity.CENTER
        hide.setPadding(0, dpi(4), 0, dpi(4))
        hide.setOnClickListener {
            val show = card.visibility != View.VISIBLE
            card.visibility = if (show) View.VISIBLE else View.GONE
            hide.text = if (show) "Hide live preview" else "Show live preview"
        }
        root.addView(hide, LinearLayout.LayoutParams(-1, -2))

        val sv = ScrollView(this)
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dpi(16), dpi(0), dpi(16), dpi(40))
        sv.addView(list, FrameLayout.LayoutParams(maxW, -2, android.view.Gravity.CENTER_HORIZONTAL))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        build()
    }

    override fun onDestroy() {
        preview?.release()
        preview = null
        super.onDestroy()
    }

    private fun header(t: String) {
        val v = label(this, t.uppercase(), 12f, sub)
        v.setPadding(dpi(4), dpi(24), 0, dpi(8))
        list.addView(v)
    }

    private fun rowBase(): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        r.setPadding(dpi(14), dpi(12), dpi(14), dpi(12))
        r.background = roundBg(0xFF1A1C22.toInt(), dpf(12f))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dpi(6)
        list.addView(r, lp)
        return r
    }

    private fun toggle(title: String, key: String, desc: String? = null) {
        val r = rowBase()
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = android.view.Gravity.CENTER_VERTICAL
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(label(this, title, 16f, fg))
        if (desc != null) col.addView(label(this, desc, 12f, sub))
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this)
        sw.isChecked = prefs.bool(key)
        sw.setOnCheckedChangeListener { _, c -> prefs.set(key, c); preview?.refresh() }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
    }

    private fun seek(title: String, key: String, lo: Int, hi: Int, unit: String = "") {
        val r = rowBase()
        val head = LinearLayout(this)
        val t = label(this, title, 16f, fg)
        val v = label(this, "${prefs.int(key)}$unit", 14f, sub)
        head.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(v)
        r.addView(head)
        val sb = SeekBar(this)
        sb.max = hi - lo
        sb.progress = prefs.int(key) - lo
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {
                v.text = "${lo + p}$unit"
                if (user) { prefs.set(key, lo + p); preview?.refresh() }   // live: the preview follows the slider
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { prefs.set(key, lo + (s?.progress ?: 0)); preview?.refresh() }
        })
        r.addView(sb)
    }

    private fun choice(title: String, key: String, names: Array<String>) {
        val r = rowBase()
        r.addView(label(this, title, 16f, fg))
        val v = label(this, names.getOrElse(prefs.int(key)) { "" }, 13f, sub)
        r.addView(v)
        r.setOnClickListener {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setSingleChoiceItems(names, prefs.int(key)) { d, w ->
                    prefs.set(key, w)
                    v.text = names[w]
                    preview?.refresh()
                    d.dismiss()
                }
                .show()
        }
    }

    private fun action(title: String, desc: String?, click: () -> Unit) {
        val r = rowBase()
        r.addView(label(this, title, 16f, fg))
        if (desc != null) r.addView(label(this, desc, 12f, sub))
        r.setOnClickListener { click() }
    }

    private fun build() {
        val title = label(this, "AjmalPad OS", 30f, fg)
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        title.setPadding(dpi(4), dpi(16), 0, 0)
        list.addView(title)
        list.addView(label(this, "Changes apply when you return to the home screen.", 13f, sub).also { it.setPadding(dpi(4), 0, 0, 0) })

        header("Appearance")
        seek("Interface size (100% = recommended)", K.UI_SCALE, 70, 200, "%")
        choice("Theme", K.THEME, arrayOf("Automatic (system)", "Light", "Dark"))
        choice("Glass profile", K.PROFILE, arrayOf("Ultra Lite", "Balanced (default)", "Ultra Glass"))
        seek("Transparency", K.OPACITY, 0, 100, "%")
        seek("Blur intensity", K.BLUR, 0, 100, "%")
        seek("Refraction intensity", K.REFRACT, 0, 100, "%")
        choice("Glass tint", K.TINT, arrayOf("Neutral", "Blue", "Warm", "Green", "Pink", "From wallpaper"))
        seek("Edge highlights", K.EDGE, 0, 100, "%")
        seek("Shadow intensity", K.SHADOW, 0, 100, "%")
        choice("Icon appearance", K.ICON_APPEARANCE, arrayOf("Default", "Light", "Dark", "Clear (glass)", "Tinted", "Liquid Glass"))

        header("Icons")
        seek("Icon size", K.ICON_SIZE, 40, 80, " dp")
        seek("Corner radius", K.ICON_RADIUS, 10, 50, "%")
        toggle("iOS-style system app icons", K.IOS_ICONS, "Phone, Messages, Camera, Settings, Clock, Calendar and more are redrawn in an iOS look, in every appearance")
        toggle("Large icon mode", K.LARGE_ICONS)
        toggle("Show labels", K.LABELS)
        seek("Label size", K.LABEL_SIZE, 9, 16, " sp")
        choice("Label colour", K.LABEL_COLOR, arrayOf("Auto (wallpaper-aware)", "White", "Black"))
        action("Import icon pack (.zip)", "PNG files named <package.name>.png; your own or licensed icons only") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE), REQ_PACK)
        }
        action("Remove all custom icons", "Individual icons are changed by long-pressing an app → Change icon") {
            LauncherApp.get().icons.clearAllCustom(); prefs.touch(); toast("Custom icons removed")
        }

        header("Home screen")
        seek("Columns (same in portrait and landscape)", K.COLS, 3, 10)
        seek("Rows (same in portrait and landscape)", K.ROWS, 3, 9)
        seek("Horizontal spacing", K.HSPACE, 0, 30, " dp")
        seek("Vertical spacing", K.VSPACE, 0, 40, " dp")
        toggle("Page indicator", K.DOTS)
        toggle("Swipe past the last page opens App Library", K.LIB_SWIPE)
        toggle("Hide navigation bar / taskbar (swipe from the bottom edge to show it)", K.HIDE_NAV)
        toggle("Search pill", K.SEARCH_PILL)
        toggle("Auto-add newly installed apps", K.AUTO_ADD)
        toggle("Lock home screen layout", K.LOCK_LAYOUT, "Disables drag, edit mode, and removal")
        toggle("Dock visible", K.DOCK_VISIBLE)
        seek("Dock icon count", K.DOCK_COUNT, 3, 6)
        toggle("Dock background", K.DOCK_BG)
        seek("Dock glass intensity", K.DOCK_GLASS, 0, 100, "%")
        seek("Dock icon size", K.DOCK_ICON, 40, 72, " dp")
        seek("Dock distance from bottom", K.DOCK_MARGIN, 0, 40, " dp")

        header("Animations")
        seek("Animation speed", K.ANIM_SPEED, 50, 200, "%")
        seek("App Library genie speed (tap the preview to try it)", K.GENIE_SPEED, 40, 250, "%")
        seek("Spring damping (lower = bouncier)", K.SPRING, 40, 100, "%")
        toggle("Reduce motion", K.REDUCE_MOTION, "Replaces springs with instant changes")
        toggle("Icon press animations", K.ICON_ANIM)
        toggle("Decorative animation (edit-mode wiggle)", K.DECOR_ANIM)
        toggle("Haptic feedback", K.HAPTIC)

        header("Gestures")
        choice("Swipe up", K.G_UP, Act.names)
        choice("Swipe down", K.G_DOWN, Act.names)
        choice("Swipe left", K.G_LEFT, Act.names)
        choice("Swipe right", K.G_RIGHT, Act.names)
        choice("Double tap empty area", K.G_DOUBLE, Act.names)
        choice("Pinch in", K.G_PINCH, Act.names)
        choice("Long press empty area", K.G_LONG_EMPTY, Act.names)
        choice("Swipe up on dock", K.G_DOCK_UP, Act.names)
        action("Enable screen lock permission", "Device-admin 'force lock' only; needed for the Lock screen action") {
            val cn = ComponentName(this, LockAdmin::class.java)
            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn)
            try { startActivity(i) } catch (t: Throwable) { }
        }

        header("Wallpaper")
        action("Choose wallpaper", "Scaling and positioning are handled by Android's wallpaper picker") {
            try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")) } catch (t: Throwable) { }
        }
        toggle("Use wallpaper for glass blur", K.WP_BLUR, "Static wallpapers only; live wallpapers use flat glass")
        action("Grant wallpaper access", "Android 10 requires storage permission to read the wallpaper for blur") {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 7)
        }
        seek("Wallpaper dimming", K.DIM, 0, 80, "%")

        header("Performance")
        action("Apply Ultra Lite preset", "Cached blur, minimal refraction, light shadows") {
            prefs.set(K.PROFILE, 0); prefs.set(K.REDUCED_BLUR, true); prefs.set(K.REDUCED_REFR, true)
            prefs.set(K.SHADOW, 25); prefs.set(K.DECOR_ANIM, false); preview?.refresh(); toast("Ultra Lite applied")
        }
        toggle("Reduced blur", K.REDUCED_BLUR, "Smaller, cheaper blur texture")
        toggle("Reduced refraction", K.REDUCED_REFR)
        toggle("FPS and memory overlay", K.FPS)
        action("Memory diagnostics", null) { showMemory() }

        header("Hidden apps")
        action("Hidden apps password", if (Vault.isSet(this)) "A password is set. Tap to change or remove it" else "Not set yet. Hidden apps open only with a password") {
            if (!Vault.isSet(this)) VaultUi.create(this) { recreate() }
            else AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Hidden apps password")
                .setItems(arrayOf("Change password", "Remove password", "Forgot it? Use my screen lock")) { _, i ->
                    when (i) {
                        0 -> VaultUi.change(this) { recreate() }
                        1 -> VaultUi.remove(this) { recreate() }
                        else -> resetWithScreenLock()
                    }
                }.show()
        }
        action("How it works", "Long-press an app and choose Move to Hidden. The Hidden card in the App Library opens only with the password, and hidden apps never appear in search.") { }

        header("Diagnostics")
        action("View error log", "Crashes and recovered errors. Share it when reporting a bug") { showLog() }
        action(if (Safe.mode) "Safe mode is ON. Tap to turn it off" else "Safe mode is off",
            "After repeated crashes the launcher pauses blur, glass refraction, widgets and the genie so it always starts") {
            if (Safe.mode) { Safe.exit(); toast("Safe mode off"); recreate() }
            else toast("Safe mode switches on by itself after repeated crashes")
        }
        action("Restore layout backup", "Brings back the last automatic backup of the home screen layout") {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Restore the last layout backup?")
                .setPositiveButton("Restore") { _, _ ->
                    toast(if (LauncherApp.get().model.restoreBackup()) "Backup restored" else "No usable backup found")
                }
                .setNegativeButton("Cancel", null).show()
        }

        header("Backup")
        action("Export settings and layout", "Appearance, gestures, dock, folders, positions, widgets list") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "ajmalpad-os-backup.json"), REQ_EXPORT)
        }
        action("Import settings and layout", null) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_IMPORT)
        }
        action("Reset home screen layout", "Re-creates the default layout; apps are not affected") {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Reset layout?")
                .setPositiveButton("Reset") { _, _ -> LauncherApp.get().model.resetLayout(); toast("Layout reset") }
                .setNegativeButton("Cancel", null).show()
        }
        action("Reset all settings", null) { prefs.resetAll(); recreate() }
        action("Set AjmalPad OS as default home app", null) {
            try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) } catch (t: Throwable) { }
        }

        header("About")
        val about = rowBase()
        about.addView(label(this, "AjmalPad OS", 18f, fg))
        about.addView(label(this, "Created by Ajmal Ali A", 14f, sub))
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (t: Throwable) { null }
        if (ver != null) about.addView(label(this, "Version $ver", 12f, sub))
        action("GitHub", "github.com/Ajmaleee") { openUrl("https://github.com/Ajmaleee") }
        action("Instagram", "@ajmaleee__") { openUrl("https://www.instagram.com/ajmaleee__/") }
        action("Source code", "github.com/Ajmaleee/Ajmal-PadOS") { openUrl("https://github.com/Ajmaleee/Ajmal-PadOS") }
        val note = rowBase()
        note.addView(label(this, "You can always contact me to add new features, fix bugs, or submit a PR.", 14f, fg))
    }

    private fun openUrl(u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
        catch (t: Throwable) { toast("No browser found for $u") }
    }

    private fun showLog() {
        val text = Diag.readLog().ifBlank { "No errors recorded." }
        val shown = if (text.length > 12000) "...\n" + text.takeLast(12000) else text
        val tv = TextView(this)
        tv.text = shown
        tv.setTextIsSelectable(true)
        tv.typeface = Typeface.MONOSPACE
        tv.textSize = 11f
        tv.setTextColor(Color.WHITE)
        tv.setPadding(dpi(16), dpi(8), dpi(16), dpi(8))
        val box = ScrollView(this)
        box.addView(tv)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Error log").setView(box)
            .setPositiveButton("Share") { _, _ -> shareLog(text) }
            .setNeutralButton("Clear") { _, _ -> Diag.clearLog(); toast("Log cleared") }
            .setNegativeButton("Close", null).show()
    }

    private fun shareLog(text: String) {
        try {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "AjmalPad OS error log")
                .putExtra(Intent.EXTRA_TEXT, text.takeLast(60000))
            startActivity(Intent.createChooser(i, "Share log"))
        } catch (t: Throwable) { toast("Nothing to share with") }
    }

    /** Forgotten password: prove who you are with the device's own screen lock, then the password is removed. */
    private fun resetWithScreenLock() {
        val kg = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val i = try { kg?.createConfirmDeviceCredentialIntent("Reset Hidden apps password", "Confirm your screen lock to remove the password") } catch (t: Throwable) { null }
        if (i != null) {
            try { startActivityForResult(i, REQ_CRED) } catch (t: Throwable) { Diag.e("settings.credential", t); toast("Could not ask for your screen lock") }
        } else {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("No screen lock is set")
                .setMessage("This device has no PIN, pattern or password, so the Hidden apps password cannot be verified. Remove it anyway?")
                .setPositiveButton("Remove") { _, _ -> Vault.clear(this); toast("Password removed"); recreate() }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun readLimited(uri: Uri, max: Int): String? = try {
        contentResolver.openInputStream(uri)?.use { st ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = st.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw java.io.IOException("too large")
                out.write(buf, 0, n)
            }
            String(out.toByteArray())
        }
    } catch (t: Throwable) { Diag.e("settings.read", t); null }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun showMemory() {
        val rt = Runtime.getRuntime()
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val sys = ActivityManager.MemoryInfo()
        am.getMemoryInfo(sys)
        val app = LauncherApp.get()
        val msg = "Process PSS: ${mi.totalPss / 1024} MB\n" +
            "Java heap used: ${(rt.totalMemory() - rt.freeMemory()) / 1048576} MB\n" +
            "Native heap: ${Debug.getNativeHeapAllocatedSize() / 1048576} MB\n" +
            "Icon cache: ${app.icons.usedBytes() / 1048576} MB\n" +
            "Glass backdrop: ${app.glass.bw}×${app.glass.bh} px (1/${app.glass.scale} screen)\n" +
            "System available RAM: ${sys.availMem / 1048576} MB"
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Memory").setMessage(msg)
            .setPositiveButton("OK", null).show()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_CRED) {
            if (res == RESULT_OK) { Vault.clear(this); toast("Hidden apps password removed"); recreate() }
            else toast("Screen lock was not confirmed")
            return
        }
        val uri: Uri = data?.data ?: return
        if (res != RESULT_OK) return
        when (req) {
            REQ_PACK -> LauncherApp.get().icons.importPack(uri) { n -> prefs.touch(); toast("Imported $n icons") }
            REQ_EXPORT -> Thread {
                val ok = try {
                    (contentResolver.openOutputStream(uri) ?: throw java.io.IOException("cannot open")).use { it.write(LauncherApp.get().model.exportAll().toByteArray()) }
                    true
                } catch (t: Throwable) { Diag.e("settings.export", t); false }
                runOnUiThread { toast(if (ok) "Exported" else "Export failed") }
            }.start()
            REQ_IMPORT -> Thread {
                val text = readLimited(uri, 2_000_000)
                runOnUiThread {
                    val ok = text != null && guard("settings.import", false) { LauncherApp.get().model.importAll(text) }
                    toast(if (ok) "Imported. Return to the home screen." else "Not a valid AjmalPad OS backup. Nothing was changed.")
                    if (ok) recreate()
                }
            }.start()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 7) LauncherApp.get().glass.reload()
    }

    companion object {
        const val REQ_PACK = 21
        const val REQ_EXPORT = 22
        const val REQ_IMPORT = 23
        const val REQ_CRED = 24
    }
}
