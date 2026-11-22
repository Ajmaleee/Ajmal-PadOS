package com.ajmal.os

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.view.View
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/** A weather location. [gps] places show the small location arrow, like iOS. */
class Place(val name: String, val lat: Double, val lon: Double, val gps: Boolean = false, val region: String = "") {
    val key: String get() = String.format(Locale.US, "%.3f,%.3f", lat, lon)
    val label: String get() = if (region.isNotBlank()) "$name, $region" else name
}

/** Widget settings that are not simple switches (location, unit, world-clock cities). */
object WCfg {
    private fun sp() = LauncherApp.get().getSharedPreferences("ajmal_widgets", Context.MODE_PRIVATE)
    private val ids: Set<String> by lazy { TimeZone.getAvailableIDs().toHashSet() }

    fun place(): Place? {
        val s = sp()
        val name = s.getString("name", null) ?: return null
        val la = s.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lo = s.getString("lon", null)?.toDoubleOrNull() ?: return null
        return Place(name, la, lo, s.getBoolean("gps", false), s.getString("region", "") ?: "")
    }

    fun setPlace(p: Place?) {
        val e = sp().edit()
        if (p == null) e.remove("name").remove("lat").remove("lon").remove("gps").remove("region")
        else e.putString("name", p.name).putString("lat", p.lat.toString()).putString("lon", p.lon.toString())
            .putBoolean("gps", p.gps).putString("region", p.region)
        e.apply()
    }

    fun unit(): String = sp().getString("unit", "auto") ?: "auto"
    fun setUnit(u: String) { sp().edit().putString("unit", u).apply() }
    fun useF(): Boolean = when (unit()) {
        "F" -> true
        "C" -> false
        else -> Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")
    }

    /** Four clocks: this device's own time zone first, then three chosen cities. */
    fun zones(): List<String> {
        val local = TimeZone.getDefault().id
        val saved = (sp().getString("zones", "") ?: "").split(',').filter { it.isNotBlank() && ids.contains(it) && it != local }
        val defaults = listOf("America/New_York", "Europe/London", "Asia/Tokyo", "Australia/Sydney").filter { it != local }
        val out = ArrayList<String>()
        out.add(local)
        for (i in 0 until 3) out.add(saved.getOrNull(i) ?: defaults.firstOrNull { d -> !out.contains(d) && !saved.contains(d) } ?: "UTC")
        return out
    }

    fun setZone(slot: Int, id: String) {   // slot 1..3
        val cur = zones().drop(1).toMutableList()
        if (slot in 1..3) cur[slot - 1] = id
        sp().edit().putString("zones", cur.joinToString(",")).apply()
    }

    fun cityName(id: String): String = id.substringAfterLast('/').replace('_', ' ')
}

class Weather(
    val placeKey: String, val fetched: Long,
    val temp: Double, val code: Int, val isDay: Boolean, val hi: Double, val lo: Double,
    val hourHour: List<Int>, val hourTemp: List<Double>, val hourCode: List<Int>, val hourDay: List<Boolean>,
    val dayName: List<String>, val dayHi: List<Double>, val dayLo: List<Double>, val dayCode: List<Int>
)

object Bt {
    const val TABLET = 0; const val EARBUDS = 1; const val HEADPHONES = 2; const val SPEAKER = 3; const val WATCH = 4
    const val KEYBOARD = 5; const val MOUSE = 6; const val PHONE = 7; const val OTHER = 8
}

class BtDevice(val name: String, val address: String, val kind: Int, val level: Int)

class SysInfo(
    val deviceName: String, val storageTotal: Long, val storageFree: Long,
    val ramTotal: Long, val ramFree: Long, val uptimeMs: Long
)

/**
 * Live data for the widgets. Nothing runs unless a widget is on screen: receivers, Bluetooth profile proxies,
 * the one-second clock tick and the weather refresh all start with the first widget and stop with the last.
 */
object WidgetData {
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private val subs = LinkedHashMap<View, Boolean>()   // view -> wants the one-second tick

    @Volatile var batteryPct = -1
    @Volatile var charging = false
    @Volatile var powerSave = false
    @Volatile var bt: List<BtDevice> = emptyList()
    @Volatile var weather: Weather? = null
    @Volatile var weatherError: String? = null
    @Volatile var sys: SysInfo? = null
    private var started = false
    private var screenOn = true
    private var fetching = false
    private var tickCount = 0
    private val btLevels = HashMap<String, Int>()
    private val proxies = HashMap<Int, BluetoothProfile>()
    private val getBatteryLevel: java.lang.reflect.Method? =
        try { BluetoothDevice::class.java.getMethod("getBatteryLevel") } catch (t: Throwable) { null }

    fun subscribe(v: View, ticks: Boolean) {
        subs[v] = ticks
        if (!started) start()
        v.invalidate()
    }

    fun unsubscribe(v: View) {
        subs.remove(v)
        if (subs.isEmpty()) stop()
    }

    private fun notifyAll() { for (v in ArrayList(subs.keys)) v.invalidate() }

    // ---------------------------------------------------------------- lifecycle

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            guardRun("widgetdata.receive") {
                when (i.action) {
                    Intent.ACTION_BATTERY_CHANGED -> readBattery(i)
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> readPowerSave()
                    Intent.ACTION_SCREEN_OFF -> screenOn = false
                    Intent.ACTION_SCREEN_ON -> { screenOn = true; readSys(); refreshBt(); refreshWeather(false) }
                    "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" -> {
                        val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val lv = i.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
                        if (d != null && lv in 0..100) btLevels[d.address] = lv
                        refreshBt()
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED,
                    "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
                    "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" -> main.postDelayed({ refreshBt() }, 600)
                }
                notifyAll()
            }
        }
    }

    private val btListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { main.post { proxies[profile] = proxy; refreshBt() } }
        override fun onServiceDisconnected(profile: Int) { main.post { proxies.remove(profile); refreshBt() } }
    }

    private val tickRun = object : Runnable {
        override fun run() {
            if (subs.isEmpty()) return
            guardRun("widgetdata.tick") {
                tickCount++
                if (screenOn) {
                    for ((v, wants) in ArrayList(subs.entries)) if (wants && v.isShown) v.invalidate()
                    if (tickCount % 10 == 0) { readSys(); for (v in ArrayList(subs.keys)) if (v.isShown) v.invalidate() }
                    if (tickCount % 600 == 0) refreshWeather(false)
                }
            }
            val fast = subs.values.any { it }
            main.postDelayed(this, if (fast) 1000L - System.currentTimeMillis() % 1000L else 10_000L)
        }
    }

    private fun start() {
        started = true
        val ctx = LauncherApp.get()
        guardRun("widgetdata.start") {
            val f = IntentFilter()
            f.addAction(Intent.ACTION_BATTERY_CHANGED)
            f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            f.addAction(Intent.ACTION_SCREEN_OFF)
            f.addAction(Intent.ACTION_SCREEN_ON)
            if (!Safe.mode) {
                f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                f.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
                f.addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
                f.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            }
            val sticky = ctx.registerReceiver(rx, f)
            if (sticky != null) readBattery(sticky)
        }
        guardRun("widgetdata.power") { readPowerSave() }
        readSys()
        if (!Safe.mode) {
            guardRun("widgetdata.bt") {
                val ad = BluetoothAdapter.getDefaultAdapter()
                if (ad != null) for (p in intArrayOf(1, 2, 4, 21)) {
                    try { ad.getProfileProxy(ctx, btListener, p) } catch (t: Throwable) { }
                }
            }
            loadWeatherCache()
            refreshWeather(false)
        }
        main.postDelayed(tickRun, 1000L)
    }

    private fun stop() {
        started = false
        main.removeCallbacks(tickRun)
        val ctx = LauncherApp.get()
        guardRun("widgetdata.stop") { ctx.unregisterReceiver(rx) }
        guardRun("widgetdata.proxies") {
            val ad = BluetoothAdapter.getDefaultAdapter()
            for ((p, px) in proxies) { try { ad?.closeProfileProxy(p, px) } catch (t: Throwable) { } }
            proxies.clear()
        }
    }

    // ---------------------------------------------------------------- battery, power, system

    private fun readBattery(i: Intent) {
        val lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        batteryPct = if (lv >= 0 && sc > 0) lv * 100 / sc else -1
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun readPowerSave() {
        val pm = LauncherApp.get().getSystemService(Context.POWER_SERVICE) as? PowerManager
        powerSave = pm?.isPowerSaveMode ?: false
    }

    fun readSys() {
        guardRun("widgetdata.sys") {
            val ctx = LauncherApp.get()
            val st = StatFs(Environment.getDataDirectory().path)
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val name = try { Settings.Global.getString(ctx.contentResolver, "device_name") } catch (t: Throwable) { null }
            sys = SysInfo(
                if (!name.isNullOrBlank()) name else Build.MODEL,
                st.totalBytes, st.availableBytes, mi.totalMem, mi.availMem, android.os.SystemClock.elapsedRealtime()
            )
        }
    }

    // ---------------------------------------------------------------- bluetooth

    private fun kindOf(d: BluetoothDevice): Int {
        val n = (d.name ?: "").lowercase()
        if (Regex("buds|airpods|pods|earbud|earphone|wf-|tws").containsMatchIn(n)) return Bt.EARBUDS
        if (n.contains("watch") || n.contains("band")) return Bt.WATCH
        if (n.contains("keyboard")) return Bt.KEYBOARD
        if (n.contains("mouse") || n.contains("trackpad")) return Bt.MOUSE
        val bc = d.bluetoothClass ?: return Bt.OTHER
        return when (bc.deviceClass) {
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES, BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> Bt.HEADPHONES
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> Bt.SPEAKER
            BluetoothClass.Device.PERIPHERAL_KEYBOARD, BluetoothClass.Device.PERIPHERAL_KEYBOARD_POINTING -> Bt.KEYBOARD
            BluetoothClass.Device.PERIPHERAL_POINTING -> Bt.MOUSE
            else -> when (bc.majorDeviceClass) {
                BluetoothClass.Device.Major.WEARABLE -> Bt.WATCH
                BluetoothClass.Device.Major.PHONE -> Bt.PHONE
                BluetoothClass.Device.Major.AUDIO_VIDEO -> Bt.HEADPHONES
                else -> Bt.OTHER
            }
        }
    }

    /** Battery level a connected device reports over standard Bluetooth, or -1 when it does not report one. */
    private fun levelOf(d: BluetoothDevice): Int {
        var v = -1
        try { v = (getBatteryLevel?.invoke(d) as? Int) ?: -1 } catch (t: Throwable) { }
        if (v in 0..100) { btLevels[d.address] = v; return v }
        return btLevels[d.address] ?: -1
    }

    fun refreshBt() {
        guardRun("widgetdata.refreshBt") {
            val ad = BluetoothAdapter.getDefaultAdapter()
            if (ad == null || !ad.isEnabled) { bt = emptyList(); notifyAll(); return@guardRun }
            val seen = LinkedHashMap<String, BtDevice>()
            for (px in ArrayList(proxies.values)) {
                for (d in px.connectedDevices) {
                    if (!seen.containsKey(d.address)) seen[d.address] = BtDevice(d.name ?: "Bluetooth device", d.address, kindOf(d), levelOf(d))
                }
            }
            bt = ArrayList(seen.values)
            notifyAll()
        }
    }

    // ---------------------------------------------------------------- weather (Open-Meteo, no account or key)

    private fun cacheFile() = File(LauncherApp.get().filesDir, "weather_cache.json")

    private fun loadWeatherCache() {
        if (weather != null) return
        guardRun("weather.cache") {
            val f = cacheFile()
            if (!f.exists()) return@guardRun
            val o = JSONObject(f.readText())
            weather = parseWeather(o.getJSONObject("data"), o.getString("key"), o.getLong("fetched"))
        }
    }

    fun refreshWeather(force: Boolean) {
        if (Safe.mode) return
        val p = WCfg.place() ?: return
        val now = System.currentTimeMillis()
        val w = weather
        if (!force && w != null && w.placeKey == p.key && now - w.fetched < 30 * 60_000L) return
        if (fetching) return
        fetching = true
        net.safe("weather.fetch") {
            try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}" +
                    "&current=temperature_2m,weather_code,is_day" +
                    "&hourly=temperature_2m,weather_code,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                    "&timezone=auto&forecast_days=7&forecast_hours=24"
                val text = httpGet(url)
                val data = JSONObject(text)
                val parsed = parseWeather(data, p.key, System.currentTimeMillis())
                guardRun("weather.save") {
                    cacheFile().writeText(JSONObject().put("key", p.key).put("fetched", parsed.fetched).put("data", data).toString())
                }
                main.post { weather = parsed; weatherError = null; notifyAll() }
            } catch (t: Throwable) {
                Diag.e("weather.fetch", t)
                main.post { weatherError = "Weather unavailable"; notifyAll() }
            } finally {
                fetching = false
            }
        }
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", "AjmalPadOS")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            c.inputStream.use { s ->
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 600_000) throw IOException("response too large")
                    out.write(buf, 0, n)
                }
            }
            return String(out.toByteArray())
        } finally { c.disconnect() }
    }

    private fun parseWeather(o: JSONObject, key: String, fetched: Long): Weather {
        val cur = o.getJSONObject("current")
        val temp = cur.getDouble("temperature_2m")
        val code = cur.optInt("weather_code", cur.optInt("weathercode", 0))
        val isDay = cur.optInt("is_day", 1) == 1
        val hr = o.getJSONObject("hourly")
        val times = hr.getJSONArray("time")
        val ht = hr.getJSONArray("temperature_2m")
        val hc = hr.getJSONArray("weather_code")
        val hd = hr.optJSONArray("is_day")
        val nowHour = cur.optString("time", "").take(13)
        var start = 0
        for (i in 0 until times.length()) if (times.getString(i).take(13) >= nowHour) { start = i; break }
        val hHour = ArrayList<Int>(); val hTemp = ArrayList<Double>(); val hCode = ArrayList<Int>(); val hDay = ArrayList<Boolean>()
        var i = start
        while (i < times.length() && hHour.size < 12) {
            hHour.add(times.getString(i).substring(11, 13).toInt())
            hTemp.add(ht.getDouble(i)); hCode.add(hc.getInt(i)); hDay.add(hd == null || hd.optInt(i, 1) == 1)
            i++
        }
        val d = o.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val dh = d.getJSONArray("temperature_2m_max")
        val dl = d.getJSONArray("temperature_2m_min")
        val dc = d.getJSONArray("weather_code")
        val dName = ArrayList<String>(); val dHi = ArrayList<Double>(); val dLo = ArrayList<Double>(); val dCode = ArrayList<Int>()
        for (k in 0 until dt.length()) {
            dName.add(if (k == 0) "Today" else LocalDate.parse(dt.getString(k)).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()))
            dHi.add(dh.getDouble(k)); dLo.add(dl.getDouble(k)); dCode.add(dc.getInt(k))
        }
        return Weather(key, fetched, temp, code, isDay, dHi.firstOrNull() ?: temp, dLo.firstOrNull() ?: temp,
            hHour, hTemp, hCode, hDay, dName, dHi, dLo, dCode)
    }

    /** City search for the weather location picker (Open-Meteo geocoding). Calls [cb] on the main thread; null = failed. */
    fun searchCity(q: String, cb: (List<Place>?) -> Unit) {
        net.safe("weather.geocode") {
            var res: List<Place>? = null
            try {
                val text = httpGet("https://geocoding-api.open-meteo.com/v1/search?name=" +
                    URLEncoder.encode(q.trim(), "UTF-8") + "&count=8&language=en&format=json")
                val arr = JSONObject(text).optJSONArray("results")
                val out = ArrayList<Place>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    val region = listOf(r.optString("admin1", ""), r.optString("country", "")).filter { it.isNotBlank() }.joinToString(", ")
                    out.add(Place(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"), false, region))
                }
                res = out
            } catch (t: Throwable) { Diag.e("weather.geocode", t) }
            main.post { guardRun("weather.geocode.cb") { cb(res) } }
        }
    }
}
