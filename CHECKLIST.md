# AjmalPad OS checklist (Android 10 / API 29, Lenovo Tab M10 TB-X505X)

Round 8 (this one)
- [x] Glass.kt: added the missing `import android.view.View` (CI run #5 confirms the fix builds); scanned all 28 files for other missing imports
- [x] App Library genie: no bounce (the dock button's press spring was overshooting; now a short non-springy press), starts right after layout, closing reuses the opening picture so it starts instantly, strong ease-out both ways
- [x] Genie shape: grows from the centre of the library button with a narrow neck (a fifth of the button width) instead of a square-looking base
- [x] The App Library is rendered once off-screen after start, so its first opening has no wait

Round 7
- [x] iOS-style widgets: Clock, Batteries (this tablet + Bluetooth devices with ring and device glyphs), Weather (Open-Meteo), Tablet info, Calendar; small / medium / large
- [x] Widget gallery (swipe through the sizes, tap to add) and live data that only runs while a widget is visible
- [x] Today View: swipe right on the first page, widget strip over a blurred home screen, swipe left / tap outside to close, configurable cards
- [x] Settings: weather city search / use my location, temperature unit, world-clock cities, Today View cards, glass widgets
- [ ] Not built: calendar events (needs the calendar permission), separate left/right earbud and case levels (needs vendor-specific Bluetooth protocols)

Round 6
- [x] Dock icons could not be moved and sat above the dock: an earlier build saved y = -1 for dock items, drawing them one row above the dock, outside its touch area. Fixed at load, on save and when dropping; the cell layout also clamps positions
- [x] App Library closes when you swipe right
- [x] Settings: genie animation speed (tap the preview to try it)
- [x] Hidden in the App Library, opens with a password only (salted hash, lock-out after wrong guesses, reset with screen lock)
- [x] Search: tapping an empty area goes back
- [x] Settings: pinned live preview (glass slab, icons, labels, dock, dimming)
- [x] Settings: creator details, links, source, contact note
- [x] Error handling: crash recorder + log viewer, safe mode after crash loops, protected background tasks, atomic layout saves with backup and repair, app list failures never wipe the layout, widget placeholders instead of deletion, guarded launches/imports/permissions, animation loop protected

Earlier rounds: see git history / previous checklist (dock, glass, iOS icons and names, genie, shared portrait/landscape layout, taskbar hiding, edge bounce, interface size).

Still to verify on the tablet (cannot be tested without the device)
- [ ] Build passes (no compiler was available while writing this)
- [ ] Taskbar hiding on the Lenovo build
- [ ] Wallpaper blur alignment and genie smoothness on the Snapdragon 429
