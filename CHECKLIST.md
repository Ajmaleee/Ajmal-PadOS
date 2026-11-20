# AjmalPad OS checklist (Android 10 / API 29, Lenovo Tab M10 TB-X505X)

Previous requests
- [x] Dock: icons centred in the panel (panel height now follows icon size)
- [x] Glass visible everywhere: Balanced profile by default, highlights/rim always on, see-through backdrop, frosted fallback when wallpaper access is missing
- [x] Smooth icon edges on the low-res screen: icons rendered at 2x and averaged down, drawn on whole pixels with filtering
- [x] iOS app names (Phone, Messages, Safari, App Store, Voice Memos, ...) for recognised apps
- [x] App Library button at the right end of the dock, opens/closes with a genie animation
- [x] Swiping past the last home page opens the App Library
- [x] Interface size slider: 100% = recommended; higher is larger, lower is smaller (no more "zoomed out")

This round
- [x] Specs reviewed: Android 10 (targetSdk 29, minSdk 29), SDM429, 2.8 GiB, 1280x800 at 160 dpi
- [x] Productivity taskbar / 3-button bar hidden by default (sticky immersive); real bar height reserved when it stays visible or hiding is off
- [x] One shared layout and grid for portrait and landscape: app order never changes on rotation
- [x] Page edges bounce (rubber band + spring from the real position) instead of jittering

Still to verify on the tablet (cannot be tested without the device)
- [ ] Taskbar hides on the Lenovo build (some OEM bars ignore the flag; the launcher then reserves its height)
- [ ] Wallpaper blur alignment on this device
- [ ] Genie animation smoothness on the Snapdragon 429
