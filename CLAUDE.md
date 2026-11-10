# Animation rule (applies to every new or changed feature)

Nothing in the app may snap instantly unless it logically has to. When you add or change UI, animate it:

- **Appearing / disappearing**: screens, overlays, dialogs, menus, bars, chrome, selection bars, list items. Use the shared helpers in `ui/Motion.kt` (do not hand-roll timings per screen).
- **State changes**: tab underline/colour, selection border/check mark, header text swaps, mode switches, grid/list swaps.
- **Progress**: every operation that takes time shows a progress indicator that moves. Known percentage: a smoothed progress bar. Unknown percentage: the indeterminate shimmer bar. Never a static "WORKING…" label alone. Applies to AI upscale, saving/exporting, join, copy/move/zip/extract, encrypt/decrypt, scanning, sending, receiving, joining a network, waiting for a peer, loading.
- **Lists and images**: `animateItem` on list/grid items, crossfade on image loads.
- **Beyond the Compose screens**: the foreground notification shows a progress bar during transfers; the web page served to computers (`transfer/WebPage.kt`) uses CSS transitions for bars, highlights and list changes; theme changes (PHONE/DARK/LIGHT) animate colours; app start, lock/unlock and permission screens fade rather than swap.
- **Loading states**: anything that loads or computes (library, editor image, thumbnails, face/subject detection, auto enhance, heal/clone, scans) shows a moving indicator, not blank space or static text.
- **Allowed to be instant**: anything that must track the finger or apply live (drag, pinch-zoom, crop handles, slider drags), and pager swipes (already animated).

Before finishing any UI change, check each new composable against this list and verify the transitions on the emulator.
