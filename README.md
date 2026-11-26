# AjmalPad OS — native Android launcher for the Lenovo Tab M10 (Liquid Glass edition)

Pure Kotlin + Android framework APIs. **Zero third-party dependencies**, no WebView/Flutter/RN, no network code,
no analytics. App id `com.ajmal.pados` (installs beside Ajmal OS), minSdk 29, targetSdk 29 (Android 10), ARM64-compatible (no native code).
Target: Lenovo Tab M10 TB-X505X, 1280×800, Snapdragon 429, ~2.8 GiB RAM.

## Build
**GitHub:** push this folder to a repo → Actions → *Build AjmalPad OS* → download artifact `AjmalPadOS-debug-apk`.
The workflow also prints package / SDK levels / HOME-filter check from the built APK.

**Local:** `gradle wrapper --gradle-version 8.5` once, then `./gradlew assembleDebug`
(APK: `app/build/outputs/apk/debug/app-debug.apk`). Needs JDK 17 + Android SDK platform 34.

## Install (Lenovo Tab M10 TB-X505X)
1. Enable *Install unknown apps* for your file manager / browser, install the APK (or `adb install -r app-debug.apk`).
2. Press Home → choose **AjmalPad OS** → *Always*. (Or Settings → Apps → Default apps → Home app.)
3. Optional: open **AjmalPad OS Settings** → *Wallpaper → Grant wallpaper access* so the glass can blur your wallpaper
   (Android 10 requires storage permission to read it; without it the glass is flat translucent).

## Architecture
| File | Role |
|---|---|
| `Model.kt` | app snapshot (LauncherApps, refreshed only on package callbacks), layout JSON, folders/pages logic |
| `Icons.kt` | off-thread icon renderer + LRU cache; light/dark/clear/tinted; custom icons & zip packs |
| `Glass.kt` | wallpaper → 1/8-res blurred cache → `GlassFrame` panels; 3 profiles; no GL thread |
| `Liquid.kt` | Liquid Glass: `LiquidCfg` constants, `LiquidBody` squish/stretch springs, `LiquidPainter` mesh-warp refraction + RGB dispersion |
| `Spring.kt` | one shared Choreographer callback, only alive while a spring runs |
| `Pager.kt` / `Views.kt` | pager + gesture engine, canvas-drawn icon view, grid, dock, widget frame |
| `Drag.kt` | drag & drop, folder creation by hover, edge page switching |
| `Library.kt`, `Folder.kt`, `Overview.kt` | App Library, Search, folders, page overview |
| `Widgets.kt` | real `AppWidgetHost` widgets (bind/configure/resize/remove/persist) |
| `SettingsActivity.kt` | every row writes a Prefs key that `HomeActivity` re-reads |

## Honest notes / known limits (please read)
- **Never compiled or run by the author of this code.** Expect to fix a few compile errors on the first CI run;
  paste the log and they get fixed. No performance number here has been measured on a real device — use
  *Settings → Performance → FPS and memory overlay* / *Memory diagnostics* and Android Studio profiler.
- **Ultra Glass is not a live GLSL shader.** Refraction + RGB dispersion are pre-baked on tiny bitmaps and cached
  (no continuous GL rendering, per the spec's idle-cost goal). Backdrop alignment assumes the system wallpaper is
  centre-cropped at offset 0.5 (the launcher pins offsets there). Parallax is not implemented.
- Live wallpapers work (system draws them) but glass falls back to flat translucency.
- Icons: only your own / licensed images (long-press → *Change icon*, or import a zip of `<package>.png`).
- Portrait and landscape share ONE home layout and grid, so your app order is identical in both orientations (cell sizes adapt). Swipe left/right are page navigation when set to Next/Previous page; set them to other actions
  and paging is replaced by those actions.
- "Lock screen" uses a device-admin *force-lock* policy (no accessibility service).

## Lenovo Tab M10 (TB-X505X, Android 10) notes

- Density 160 makes 1 dp = 1 px, so the launcher scales its interface automatically (Settings -> Appearance -> Interface size, 100% = recommended).
- Productivity-mode taskbar / 3-button bar: hidden by default (swipe from the bottom edge to show it). Switch it off in Settings to keep the bar and have the dock sit above it.
- App Library: button at the right end of the dock (genie animation), also opens when you swipe past the last page.
- Recognised system apps use iOS names and iOS-style icons (switch off under Icons).

## Hidden apps, diagnostics, preview

- Long-press an app -> *Move to Hidden (password)*. The **Hidden** card in the App Library opens only with the password (stored as a salted PBKDF2 hash; wrong guesses lock entry for a while). Hidden apps never appear in search or the A-Z list. Forgot it: Settings -> Hidden apps -> use your screen lock to reset.
- Settings has a pinned **live preview** (mini home screen with a real glass dock): glass, icon, label, dock, dimming and genie-speed settings update it while you move the slider. Tap it to play the genie.
- Errors are logged (Settings -> Diagnostics -> View error log, shareable). Repeated crashes switch on Safe mode automatically. The layout file is written atomically with a backup, and damaged files are repaired or restored.

## Creator

Ajmal Ali A - https://github.com/Ajmaleee - https://www.instagram.com/ajmaleee__/
Source: https://github.com/Ajmaleee/Ajmal-PadOS

You can always contact me to add new features, fix bugs, or submit a PR.

## iOS-style widgets and Today View

- **Widgets** (long-press the home screen -> Widgets): Clock, Batteries, Weather, Tablet, Calendar, each in small / medium / large (iOS proportions: 158x158, 338x158, 338x354 pt, 22 pt corners). They drag, resize and remove like any widget. Other apps' widgets are still under "Other apps' widgets".
  - *Clock*: analog dial with a seconds hand and city initials; medium shows four cities ("Today, +5HRS"), large adds date and digital time. Choose the cities in Settings -> Widgets & Today View.
  - *Batteries*: this tablet plus connected Bluetooth devices (earbuds, headphones, speakers, watches, keyboards...) as rings with device glyphs; red at 20% or less, yellow in power-saving mode, a bolt while charging. Bluetooth battery % only appears if the device reports it over standard Bluetooth.
  - *Weather*: sky-coloured card with current conditions, hourly row and 7-day ranges. Data comes from Open-Meteo (no account or key). Pick a city (or "Use my location") in Settings; only that city's coordinates are sent to open-meteo.com.
  - *Tablet*: battery, storage, memory, Android version, uptime.
- **Today View**: swipe right on the first home page. A strip of widgets slides in over a blurred home screen (swipe left or tap outside to leave; Edit opens the settings). Choose its cards in Settings.
- Nothing runs in the background unless a widget is on screen.

## Liquid Glass (Settings -> Glass profile -> *Ultra Glass (Liquid)*)
- Edge lens: a 28x14 mesh (denser at the edges) samples the blurred wallpaper through `(1 - depth/band)^2.3` along the outward normal, band = 0.41 x height, 62u refraction (u = height/100), 7% centre magnification that grows with deformation, 5% ripple.
- Dispersion: three passes at 1.09 / 1.00 / 0.91, one colour channel each, recombined with additive blending. *Reduced refraction* turns this into a single pass.
- Lighting: warm colour = 45% wallpaper average + 55% warm off-white; angle `--ang` (135 deg) shifts with deformation. Rim ring, bevel, crescents, gloss, warm focus, meniscus, specular, glint, sheen, deep + contact shadow, caustic.
- Squish: press = compress + jiggle, drag = asymptotic stretch with volume conservation, asymmetric corner radii, 3D tilt, slosh that swells with velocity. Springs k = 162, c = 10.5; the frame loop stops when settled. Enabled on the search pill, glass widgets and the settings preview (`GlassFrame.squish = true` for any other surface).
- Constants live in `LiquidCfg`. If `drawVertices` fails on a device the panel logs it and falls back to the classic glass.
- Differences from the web spec: no SVG filters on Android 10, so the displacement is analytic per mesh vertex instead of a baked map; `feTurbulence` is a two-sine slosh; the backdrop is the 1/8-scale blurred wallpaper (clamped edges replace the bleed margin).
- Always-liquid surfaces (any profile except Ultra Lite): the dock (press-squish only, so icon drags don't stretch it), the search pill, App Library category cards (a liquid slab sits behind each card), the library category sheet and folder panels. Needs wallpaper access for the refraction; without it the classic frosted glass is used.
