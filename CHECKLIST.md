# AjmalPad OS checklist (Android 10 / API 29, Lenovo Tab M10 TB-X505X)

Round 6 (this one)
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
