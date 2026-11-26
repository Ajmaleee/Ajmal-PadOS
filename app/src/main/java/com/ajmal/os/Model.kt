package com.ajmal.os

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AppInfo(
    val pkg: String, val cls: String, val rawLabel: String,
    val installTime: Long, val system: Boolean, val category: Int
) {
    val key: String = "$pkg/$cls"
    private val iosName: String? by lazy(LazyThreadSafetyMode.PUBLICATION) { IosIcons.match(pkg, rawLabel)?.name }
    private val rawLower: String = rawLabel.lowercase()
    private val iosLower: String? by lazy(LazyThreadSafetyMode.PUBLICATION) { iosName?.lowercase() }

    /** iOS name (Phone, Messages, Safari, ...) for recognised system apps while the iOS icon pack is on. */
    val label: String get() { val n = iosName; return if (n != null && IosIcons.on) n else rawLabel }
    val lower: String get() { val n = iosLower; return if (n != null && IosIcons.on) n else rawLower }

    /** Search match on the shown name or the original one (so "chrome" still finds Safari). */
    fun matches(q: String): Boolean = lower.contains(q) || rawLower.contains(q)
}

/** True while the display is wider than tall (used for responsive UI only; the home layout is shared). */
object Orient {
    @Volatile var land = false
}

class Item(var id: Long, var type: Int) {
    var container = HOME
    var parent = 0L
    var page = 0
    var x = 0
    var y = 0
    var sx = 1
    var sy = 1
    var title = ""
    var pkg = ""
    var cls = ""
    var widgetId = -1
    val key: String get() = "$pkg/$cls"

    companion object {
        const val APP = 0
        const val FOLDER = 1
        const val WIDGET = 2
        const val HOME = 0
        const val DOCK = 1
        const val IN_FOLDER = 2
    }
}

/**
 * Holds the installed-app snapshot (queried once, refreshed only on package-change callbacks)
 * and the persisted home layout. All mutation happens on the main thread; disk I/O is on one
 * background thread and debounced.
 */
class LauncherModel(private val app: LauncherApp) {
    @Volatile var apps: List<AppInfo> = emptyList()
    @Volatile var byKey: Map<String, AppInfo> = emptyMap()
    val items = ArrayList<Item>()
    val pageOrder = ArrayList<Int>()
    val hiddenPages = HashSet<Int>()
    val known = HashSet<String>()
    val favorites = HashSet<String>()
    val hiddenApps = HashSet<String>()
    val customCat = HashMap<String, String>()
    val usage = HashMap<String, Int>()
    val searches = ArrayList<String>()
    var nextId = 1L
    var nextPage = 1
    var ready = false
    val listeners = ArrayList<() -> Unit>()

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val waiters = ArrayList<() -> Unit>()
    private val file = File(app.filesDir, "state.json")
    private val backupFile = File(app.filesDir, "state.bak")
    /** False until an app query has succeeded; a failed query must never be mistaken for "all apps removed". */
    @Volatile var queryOk = false
    /** One-shot message for the home screen about a repaired / restored layout. */
    @Volatile var loadNote: String? = null
    private var dirty = false
    private var refreshPending = false

    private val saveRun = Runnable { writeNow() }

    // ---------------------------------------------------------------- loading

    private fun readFile(f: File): String? =
        try { if (f.exists() && f.length() > 0L) f.readText() else null } catch (t: Throwable) { Diag.e("model.read", t); null }

    private fun tryParse(text: String): Boolean = try {
        parse(JSONObject(text)); true
    } catch (t: Throwable) {
        Diag.e("model.parse", t)
        resetState()
        false
    }

    /** Keeps a damaged state file for inspection instead of silently overwriting it. */
    private fun quarantine() {
        guardRun("model.quarantine") {
            if (file.exists()) file.copyTo(File(file.parentFile, "state.corrupt"), overwrite = true)
        }
    }

    fun load() {
        io.execute {
            var list: List<AppInfo>? = null
            try { list = queryApps() } catch (t: Throwable) { Diag.e("model.queryApps", t) }
            guardRun("icons.stamps") { app.icons.loadStamps() }
            val mainText = readFile(file)
            val backupText = readFile(backupFile)
            val apps0 = list
            main.post {
                try {
                    var ok = false
                    if (mainText != null && tryParse(mainText)) ok = true
                    else if (backupText != null && tryParse(backupText)) {
                        ok = true
                        loadNote = "Your layout file was damaged; the last backup was restored."
                    }
                    if (!ok && (mainText != null || backupText != null)) {
                        quarantine()
                        loadNote = "Your layout file was damaged; a fresh layout was created."
                    }
                    if (apps0 != null) { updateApps(apps0); queryOk = true }
                    ready = true
                    reconcile()
                } catch (t: Throwable) {
                    Diag.e("model.finishLoad", t)
                    ready = true
                }
                if (apps0 == null) main.postDelayed({ refresh() }, 3000)
                val w = ArrayList(waiters)
                waiters.clear()
                for (r in w) guardRun("model.waiter") { r() }
            }
        }
        try {
            val la = app.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            la.registerCallback(object : LauncherApps.Callback() {
                override fun onPackageRemoved(packageName: String?, user: UserHandle?) { refresh() }
                override fun onPackageAdded(packageName: String?, user: UserHandle?) { refresh() }
                override fun onPackageChanged(packageName: String?, user: UserHandle?) { refresh() }
                override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) { refresh() }
                override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) { refresh() }
            }, main)
        } catch (e: Exception) { Diag.e("model.callback", e) }
    }

    /** Restores the automatic backup of the layout (used from Settings -> Diagnostics). */
    fun restoreBackup(): Boolean {
        val text = readFile(backupFile) ?: return false
        if (!tryParse(text)) return false
        guardRun("model.restore") { reconcile(); save(); app.prefs.touch() }
        return true
    }

    fun whenReady(r: () -> Unit) {
        if (ready) r() else waiters.add(r)
    }

    fun refresh() {
        if (refreshPending) return
        refreshPending = true
        main.postDelayed({
            refreshPending = false
            io.safe("model.refresh") {
                var list: List<AppInfo>? = null
                try { list = queryApps() } catch (t: Throwable) { Diag.e("model.queryApps", t) }
                val fresh = list
                main.post {
                    guardRun("model.refreshApply") {
                        if (fresh != null) { updateApps(fresh); queryOk = true }
                        reconcile()
                        for (l in ArrayList(listeners)) guardRun("model.listener") { l() }
                    }
                }
            }
        }, 300)
    }

    private fun queryApps(): List<AppInfo> {
        val la = app.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val out = ArrayList<AppInfo>()
        for (ai in la.getActivityList(null, Process.myUserHandle())) {
            try {
                val cn = ai.componentName
                if (cn.packageName == app.packageName) continue
                val info = ai.applicationInfo
                val cat = if (Build.VERSION.SDK_INT >= 26) info.category else -1
                val name = (ai.label ?: cn.packageName).toString().ifBlank { cn.packageName }
                out.add(
                    AppInfo(
                        cn.packageName, cn.className, name, ai.firstInstallTime,
                        (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0, cat
                    )
                )
            } catch (t: Throwable) { Diag.e("model.queryApps.item", t) }
        }
        out.sortBy { it.lower }
        return out
    }

    /** True while the package is still on the device (even if it has no launcher activity right now). */
    fun isInstalled(pkg: String): Boolean = try {
        app.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (t: Throwable) {
        true
    }

    /** Re-sort after the iOS-names switch changed what labels say. */
    fun resort() {
        apps = apps.sortedBy { it.lower }
    }

    private fun updateApps(list: List<AppInfo>) {
        apps = list
        val m = HashMap<String, AppInfo>()
        for (a in list) m[a.key] = a
        byKey = m
    }

    // ---------------------------------------------------------------- persistence

    private fun resetState() {
        pageOrder.clear(); hiddenPages.clear(); known.clear(); favorites.clear(); hiddenApps.clear()
        customCat.clear(); usage.clear(); searches.clear(); items.clear()
    }

    /** Throws only if the essentials are unreadable; damaged optional sections or single items are skipped. */
    private fun parse(o: JSONObject) {
        resetState()
        nextId = o.optLong("nextId", 1L)
        nextPage = o.optInt("nextPage", 1)
        guardRun("model.parse.pages") {
            o.optJSONArray("pages")?.let { for (i in 0 until it.length()) pageOrder.add(it.getInt(i)) }
            o.optJSONArray("hiddenPages")?.let { for (i in 0 until it.length()) hiddenPages.add(it.getInt(i)) }
        }
        guardRun("model.parse.sets") {
            o.optJSONArray("known")?.let { for (i in 0 until it.length()) known.add(it.getString(i)) }
            o.optJSONArray("favs")?.let { for (i in 0 until it.length()) favorites.add(it.getString(i)) }
            o.optJSONArray("hidden")?.let { for (i in 0 until it.length()) hiddenApps.add(it.getString(i)) }
            o.optJSONArray("searches")?.let { for (i in 0 until it.length()) searches.add(it.getString(i)) }
        }
        guardRun("model.parse.cats") {
            o.optJSONObject("cats")?.let { c ->
                val k = c.keys()
                while (k.hasNext()) { val n = k.next(); customCat[n] = c.getString(n) }
            }
        }
        guardRun("model.parse.usage") {
            o.optJSONObject("usage")?.let { c ->
                val k = c.keys()
                while (k.hasNext()) { val n = k.next(); usage[n] = c.getInt(n) }
            }
        }
        var skipped = 0
        val arr = o.optJSONArray("items")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                try {
                    val j = arr.getJSONObject(i)
                    val m = Item(j.getLong("id"), j.getInt("t"))
                    m.container = j.optInt("c"); m.parent = j.optLong("p"); m.page = j.optInt("pg")
                    m.x = max(0, j.optInt("x")); m.y = max(0, j.optInt("y"))
                    m.sx = max(1, j.optInt("sx", 1)); m.sy = max(1, j.optInt("sy", 1))
                    // Dock slots and folder children only use x. An old build saved y = -1 for them, which drew
                    // dock icons one row above the dock, outside its touch area.
                    if (m.container != Item.HOME) { m.y = 0; m.sx = 1; m.sy = 1 }
                    m.title = j.optString("ti"); m.pkg = j.optString("pk"); m.cls = j.optString("cl")
                    m.widgetId = j.optInt("w", -1)
                    items.add(m)
                } catch (t: Throwable) { skipped++ }
            }
        }
        if (skipped > 0) Diag.e("model.parse.items", IOException("$skipped damaged item(s) skipped"))
        // Repairs: unique ids, id/page counters ahead of what exists, every used page present.
        var maxId = 0L
        val seen = HashSet<Long>()
        val it2 = items.iterator()
        while (it2.hasNext()) {
            val m = it2.next()
            if (!seen.add(m.id)) { it2.remove(); continue }
            if (m.id > maxId) maxId = m.id
        }
        if (nextId <= maxId) nextId = maxId + 1
        for (m in items) if (m.container == Item.HOME && !pageOrder.contains(m.page)) pageOrder.add(m.page)
        var maxPage = -1
        for (p in pageOrder) if (p > maxPage) maxPage = p
        if (nextPage <= maxPage) nextPage = maxPage + 1
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("v", 1); o.put("nextId", nextId); o.put("nextPage", nextPage)
        o.put("pages", JSONArray(pageOrder)); o.put("hiddenPages", JSONArray(hiddenPages))
        o.put("known", JSONArray(known)); o.put("favs", JSONArray(favorites))
        o.put("hidden", JSONArray(hiddenApps)); o.put("searches", JSONArray(searches))
        val c = JSONObject(); for ((k, v) in customCat) c.put(k, v); o.put("cats", c)
        val u = JSONObject(); for ((k, v) in usage) u.put(k, v); o.put("usage", u)
        val arr = JSONArray()
        for (m in items) {
            val j = JSONObject()
            j.put("id", m.id); j.put("t", m.type); j.put("c", m.container); j.put("p", m.parent)
            j.put("pg", m.page); j.put("x", m.x); j.put("y", m.y); j.put("sx", m.sx); j.put("sy", m.sy)
            j.put("ti", m.title); j.put("pk", m.pkg); j.put("cl", m.cls); j.put("w", m.widgetId)
            arr.put(j)
        }
        o.put("items", arr)
        return o
    }

    fun save() {
        dirty = true
        main.removeCallbacks(saveRun)
        main.postDelayed(saveRun, 400)
    }

    /** Called from onPause so nothing is lost if the process is killed. */
    fun flush() {
        if (dirty) { main.removeCallbacks(saveRun); writeNow() }
    }

    private fun writeNow() {
        if (!ready) return   // never overwrite a good file with a half-loaded (empty) state
        dirty = false
        val s = try { toJson().toString() } catch (t: Throwable) { Diag.e("model.toJson", t); return }
        io.safe("model.write") {
            val dir = file.parentFile ?: return@safe
            dir.mkdirs()
            val tmp = File(dir, "state.tmp")
            tmp.writeText(s)
            if (tmp.length() == 0L) { tmp.delete(); throw IOException("empty state write") }
            if (file.exists() && file.length() > 0L) file.copyTo(backupFile, overwrite = true)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IOException("could not replace state.json")
            }
        }
    }

    fun exportAll(): String {
        val o = JSONObject()
        o.put("app", "ajmalpad-os"); o.put("version", 1)
        o.put("prefs", app.prefs.exportJson()); o.put("state", toJson())
        return o.toString(2)
    }

    fun importAll(text: String): Boolean {
        val oldState = try { toJson().toString() } catch (t: Throwable) { null }
        val oldPrefs = try { app.prefs.exportJson() } catch (t: Throwable) { null }
        return try {
            val o = JSONObject(text)
            if (o.optString("app") != "ajmalpad-os") return false
            val st = o.getJSONObject("state")
            val pf = o.getJSONObject("prefs")
            parse(st)
            app.prefs.importJson(pf)
            reconcile()
            save()
            app.prefs.touch()
            true
        } catch (t: Throwable) {
            Diag.e("model.import", t)
            // Put everything back exactly as it was.
            try {
                if (oldState != null) parse(JSONObject(oldState))
                if (oldPrefs != null) app.prefs.importJson(oldPrefs)
                reconcile()
            } catch (t2: Throwable) { Diag.e("model.importRollback", t2) }
            false
        }
    }

    fun resetLayout() {
        items.clear(); pageOrder.clear(); hiddenPages.clear(); known.clear()
        pageOrder.add(0); nextPage = 1
        reconcile()
        save()
        app.prefs.touch()
    }

    // ---------------------------------------------------------------- reconcile

    fun reconcile() {
        val cols = app.prefs.cols()
        val rows = app.prefs.rows()
        var changed = false
        if (pageOrder.isEmpty()) { pageOrder.add(0); if (nextPage < 1) nextPage = 1; changed = true }
        if (queryOk) {
            // Only prune when the app list is trustworthy, and never lose an item of an app that is still
            // installed (an update can briefly hide its launcher activity).
            val iter = items.iterator()
            while (iter.hasNext()) {
                val m = iter.next()
                if (m.type != Item.APP || byKey.containsKey(m.key)) continue
                val alt = apps.firstOrNull { it.pkg == m.pkg }
                if (alt != null) { m.cls = alt.cls; changed = true }
                else if (!isInstalled(m.pkg)) { iter.remove(); changed = true }
            }
        }
        val first = known.isEmpty() && items.isEmpty()
        if (first) fillDock(app.prefs.int(K.DOCK_COUNT))
        val placed = HashSet<String>()
        for (m in items) if (m.type == Item.APP) placed.add(m.key)
        val auto = first || app.prefs.bool(K.AUTO_ADD)
        for (a in apps) {
            if (known.contains(a.key)) continue
            known.add(a.key)
            changed = true
            if (placed.contains(a.key) || hiddenApps.contains(a.key) || !auto) continue
            val m = newItem(Item.APP)
            m.pkg = a.pkg; m.cls = a.cls
            placeAnywhere(m, cols, rows, -1)
            items.add(m)
        }
        guardRun("model.tidy") { tidy() }
        if (changed) save()
    }

    private fun fillDock(n: Int) {
        val pm = app.packageManager
        val intents = ArrayList<Intent>()
        intents.add(Intent(Intent.ACTION_DIAL))
        intents.add(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING))
        intents.add(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER))
        intents.add(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        var x = 0
        for (intent in intents) {
            if (x >= n) break
            val ri = try { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) } catch (t: Throwable) { null } ?: continue
            val pkg = ri.activityInfo?.packageName ?: continue
            val a = apps.firstOrNull { it.pkg == pkg } ?: continue
            if (items.any { it.type == Item.APP && it.key == a.key }) continue
            val m = newItem(Item.APP)
            m.pkg = a.pkg; m.cls = a.cls; m.container = Item.DOCK; m.x = x++
            items.add(m)
        }
    }

    // ---------------------------------------------------------------- queries

    fun newItem(type: Int): Item = Item(nextId++, type)
    fun find(id: Long): Item? = items.firstOrNull { it.id == id }
    fun pageItems(page: Int): List<Item> = items.filter { it.container == Item.HOME && it.page == page }
    fun dockItems(): List<Item> = items.filter { it.container == Item.DOCK }.sortedBy { it.x }
    fun children(folderId: Long): List<Item> =
        items.filter { it.container == Item.IN_FOLDER && it.parent == folderId }.sortedBy { it.x }

    fun visiblePages(): List<Int> {
        if (pageOrder.isEmpty()) pageOrder.add(0)
        val v = pageOrder.filter { !hiddenPages.contains(it) }
        if (v.isEmpty()) { hiddenPages.remove(pageOrder[0]); return listOf(pageOrder[0]) }
        return v
    }

    fun itemAt(page: Int, x: Int, y: Int, ignore: Item?): Item? {
        for (m in items) {
            if (m.container != Item.HOME || m.page != page || m === ignore) continue
            if (x >= m.x && x < m.x + m.sx && y >= m.y && y < m.y + m.sy) return m
        }
        return null
    }

    fun occupancy(page: Int, cols: Int, rows: Int, ignore: Item?): Array<BooleanArray> {
        val g = Array(rows) { BooleanArray(cols) }
        for (m in items) {
            if (m.container != Item.HOME || m.page != page || m === ignore) continue
            for (yy in max(0, m.y) until min(rows, m.y + m.sy))
                for (xx in max(0, m.x) until min(cols, m.x + m.sx)) g[yy][xx] = true
        }
        return g
    }

    fun fits(g: Array<BooleanArray>, x: Int, y: Int, sx: Int, sy: Int): Boolean {
        if (x < 0 || y < 0 || y + sy > g.size || x + sx > g[0].size) return false
        for (yy in y until y + sy) for (xx in x until x + sx) if (g[yy][xx]) return false
        return true
    }

    /**
     * Where the items in the way of a widget dropped at (x, y, sx x sy) can go: each moves to the nearest free
     * spot of its own size on the same page. Null when something has nowhere to go (then the drop is refused).
     */
    fun planPush(page: Int, cols: Int, rows: Int, mover: Item, x: Int, y: Int, sx: Int, sy: Int): List<Pair<Item, IntArray>>? {
        val hit = items.filter {
            it.container == Item.HOME && it.page == page && it !== mover &&
                it.x < x + sx && it.x + it.sx > x && it.y < y + sy && it.y + it.sy > y
        }
        if (hit.isEmpty()) return emptyList()
        val g = occupancy(page, cols, rows, mover)
        for (o in hit) for (yy in max(0, o.y) until min(rows, o.y + o.sy)) for (xx in max(0, o.x) until min(cols, o.x + o.sx)) g[yy][xx] = false
        for (yy in y until min(rows, y + sy)) for (xx in x until min(cols, x + sx)) g[yy][xx] = true
        val out = ArrayList<Pair<Item, IntArray>>()
        for (o in hit.sortedByDescending { it.sx * it.sy }) {
            var best: IntArray? = null
            var bd = Int.MAX_VALUE
            for (yy in 0..(rows - o.sy)) for (xx in 0..(cols - o.sx)) {
                if (!fits(g, xx, yy, o.sx, o.sy)) continue
                val d = abs(xx - o.x) + abs(yy - o.y)
                if (d < bd) { bd = d; best = intArrayOf(xx, yy) }
            }
            val b = best ?: return null
            for (yy in b[1] until b[1] + o.sy) for (xx in b[0] until b[0] + o.sx) g[yy][xx] = true
            out.add(o to b)
        }
        return out
    }

    fun findFree(page: Int, sx: Int, sy: Int, cols: Int, rows: Int, ignore: Item?): IntArray? {
        val g = occupancy(page, cols, rows, ignore)
        for (y in 0 until rows) for (x in 0 until cols) if (fits(g, x, y, sx, sy)) return intArrayOf(x, y)
        return null
    }

    fun findFreeNear(page: Int, cols: Int, rows: Int, cx: Int, cy: Int, ignore: Item?): IntArray? {
        val g = occupancy(page, cols, rows, ignore)
        var best: IntArray? = null
        var bd = Int.MAX_VALUE
        for (y in 0 until rows) for (x in 0 until cols) {
            if (g[y][x]) continue
            val d = abs(x - cx) + abs(y - cy)
            if (d < bd) { bd = d; best = intArrayOf(x, y) }
        }
        return best
    }

    fun placeAnywhere(m: Item, cols: Int, rows: Int, preferPage: Int) {
        m.sx = min(m.sx, cols); m.sy = min(m.sy, rows)
        val order = ArrayList<Int>()
        if (pageOrder.contains(preferPage)) order.add(preferPage)
        for (p in pageOrder) if (p != preferPage) order.add(p)
        for (p in order) {
            val f = findFree(p, m.sx, m.sy, cols, rows, m)
            if (f != null) { m.container = Item.HOME; m.parent = 0; m.page = p; m.x = f[0]; m.y = f[1]; return }
        }
        val p = nextPage++
        pageOrder.add(p)
        m.container = Item.HOME; m.parent = 0; m.page = p; m.x = 0; m.y = 0
    }

    /** Fix up folders (dissolve single-child, drop empty/orphaned) and compact dock indices. */
    fun tidy() {
        var again = true
        while (again) {
            again = false
            val ids = HashSet<Long>()
            for (m in items) if (m.type == Item.FOLDER) ids.add(m.id)
            val iter = items.iterator()
            while (iter.hasNext()) {
                val m = iter.next()
                if (m.container == Item.IN_FOLDER && !ids.contains(m.parent)) { iter.remove(); again = true }
            }
            val counts = HashMap<Long, Int>()
            for (m in items) if (m.container == Item.IN_FOLDER) counts[m.parent] = (counts[m.parent] ?: 0) + 1
            for (f in ArrayList(items)) {
                if (f.type != Item.FOLDER) continue
                val n = counts[f.id] ?: 0
                if (n == 0) { items.remove(f); again = true }
                else if (n == 1) {
                    val ch = items.first { it.container == Item.IN_FOLDER && it.parent == f.id }
                    ch.container = f.container; ch.parent = 0; ch.page = f.page; ch.x = f.x; ch.y = f.y
                    items.remove(f); again = true
                }
            }
        }
        val dock = dockItems()
        for ((i, m) in dock.withIndex()) { m.x = i; m.y = 0; m.sx = 1; m.sy = 1 }
        for (f in items) if (f.type == Item.FOLDER) {
            for ((i, c) in children(f.id).withIndex()) { c.x = i; c.y = 0 }
        }
    }

    /** Re-seat items after grid/dock size changes. */
    /**
     * The home layout is shared by portrait and landscape, so the order never changes when the tablet rotates.
     * This only repairs a layout that no longer fits the grid (grid made smaller, overlapping items): such
     * items move to the first free cell.
     */
    fun ensureLayout(cols: Int, rows: Int): Boolean {
        var changed = false
        val homes = items.filter { it.container == Item.HOME }
        val sorted = homes.sortedWith(compareBy<Item>({ pageOrder.indexOf(it.page) }, { it.y }, { it.x }))
        val occ = HashMap<Int, Array<BooleanArray>>()
        fun grid(pg: Int) = occ.getOrPut(pg) { Array(rows) { BooleanArray(cols) } }
        fun isFree(g: Array<BooleanArray>, x: Int, y: Int, sx: Int, sy: Int): Boolean {
            if (x < 0 || y < 0 || x + sx > cols || y + sy > rows) return false
            for (yy in y until y + sy) for (xx in x until x + sx) if (g[yy][xx]) return false
            return true
        }
        fun mark(g: Array<BooleanArray>, x: Int, y: Int, sx: Int, sy: Int) {
            for (yy in y until y + sy) for (xx in x until x + sx) g[yy][xx] = true
        }
        val todo = ArrayList<Item>()
        for (m in sorted) {
            val nsx = min(max(1, m.sx), cols)
            val nsy = min(max(1, m.sy), rows)
            if (nsx != m.sx || nsy != m.sy) { m.sx = nsx; m.sy = nsy; changed = true }
            val g = grid(m.page)
            if (!isFree(g, m.x, m.y, m.sx, m.sy)) { todo.add(m); changed = true; continue }
            mark(g, m.x, m.y, m.sx, m.sy)
        }
        for (m in todo) {
            val order = ArrayList<Int>()
            if (pageOrder.contains(m.page)) order.add(m.page)
            for (pg in pageOrder) if (pg != m.page) order.add(pg)
            var placed = false
            search@ for (pg in order) {
                val g = grid(pg)
                for (y in 0..rows - m.sy) for (x in 0..cols - m.sx) {
                    if (isFree(g, x, y, m.sx, m.sy)) {
                        m.page = pg; m.x = x; m.y = y
                        mark(g, x, y, m.sx, m.sy)
                        placed = true
                        break@search
                    }
                }
            }
            if (!placed) {
                val pg = nextPage++
                pageOrder.add(pg)
                m.page = pg; m.x = 0; m.y = 0
                mark(grid(pg), 0, 0, m.sx, m.sy)
            }
        }
        if (changed) save()
        return changed
    }

    fun normalize(cols: Int, rows: Int, dockCount: Int): Boolean {
        var ch = false
        for (m in ArrayList(items)) {
            if (m.container == Item.DOCK && m.x >= dockCount) {
                m.container = -1
                placeAnywhere(m, cols, rows, -1)
                ch = true
            }
        }
        for (m in ArrayList(items)) {
            if (m.container != Item.HOME) continue
            if (m.sx > cols) { m.sx = cols; ch = true }
            if (m.sy > rows) { m.sy = rows; ch = true }
            if (m.x + m.sx > cols || m.y + m.sy > rows) {
                m.container = -1
                placeAnywhere(m, cols, rows, -1)
                ch = true
            }
        }
        if (ch) save()
        return ch
    }

    // ---------------------------------------------------------------- pages

    fun addPage(): Int {
        val p = nextPage++
        pageOrder.add(p)
        save()
        return p
    }

    fun movePage(id: Int, dir: Int) {
        val i = pageOrder.indexOf(id)
        val j = i + dir
        if (i < 0 || j < 0 || j >= pageOrder.size) return
        pageOrder.removeAt(i)
        pageOrder.add(j, id)
        save()
    }

    fun togglePageHidden(id: Int): Boolean {
        if (hiddenPages.contains(id)) { hiddenPages.remove(id); save(); return true }
        if (pageOrder.count { !hiddenPages.contains(it) } <= 1) return false
        hiddenPages.add(id)
        save()
        return true
    }

    /** Returns widget ids that must be released from the AppWidgetHost. */
    fun deletePage(id: Int): List<Int> {
        if (pageOrder.size <= 1) return emptyList()
        val widgets = ArrayList<Int>()
        val doomed = items.filter { it.container == Item.HOME && it.page == id }
        for (m in doomed) {
            if (m.type == Item.WIDGET) widgets.add(m.widgetId)
            items.remove(m)
        }
        pageOrder.remove(id)
        hiddenPages.remove(id)
        tidy()
        save()
        return widgets
    }

    // ---------------------------------------------------------------- app library helpers

    fun categoryOf(a: AppInfo): String {
        customCat[a.key]?.let { return it }
        return when (a.category) {
            0 -> "Games"; 1 -> "Music"; 2 -> "Video"; 3 -> "Photo"; 4 -> "Social"
            5 -> "News"; 6 -> "Maps"; 7 -> "Productivity"
            else -> if (a.system) "Utilities" else "Other"
        }
    }

    fun categories(): List<String> = apps.map { categoryOf(it) }.distinct().sorted()
    fun recent(): List<AppInfo> = apps.sortedByDescending { it.installTime }.take(15)
    fun frequent(): List<AppInfo> =
        apps.filter { (usage[it.key] ?: 0) > 0 }.sortedByDescending { usage[it.key] ?: 0 }.take(15)

    fun noteLaunch(a: AppInfo) {
        usage[a.key] = (usage[a.key] ?: 0) + 1
        save()
    }

    fun noteSearch(q: String) {
        val s = q.trim()
        if (s.isEmpty()) return
        searches.remove(s)
        searches.add(0, s)
        while (searches.size > 8) searches.removeAt(searches.size - 1)
        save()
    }
}
