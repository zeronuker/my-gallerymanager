# Design language (ClaudeBorne brand)

The app follows the ClaudeBorne brand kit (`C:\Users\Amir Rashid\my-brandkit`, used by my-edoc, my-elogbook and my-superapp). New UI must look like those apps:

- **Tokens** come from `ui/Theme.kt` (`LocalCb`): surfaces, mint/blue/violet, ink, lines. No new hex colours; the only extra is `Warn` (amber) for warning lines.
- **Frames**: cards, dialogs and pickers use `doubleChamferFrame(cb)`, the double chamfered line in the brand gradient.
- **Buttons**: square, mono, uppercase, a faint line border that turns mint when pressed; one gradient primary at most per view. Use `BrandButton` (or `WideBrandButton`). Inside a `CbDialog`, `ActionText` is drawn as a brand button automatically: mint = primary, red = destructive, anything else = secondary.
- **Dialogs**: `CbDialog(title, onDismiss, subtitle = …)`: an uppercase mono title, an optional small subtitle, then the content. Result dialogs use a mint line for success and an amber (`Warn`) line for a problem.
- **Choosing files or folders**: always the shared `Picker` (via a `PickerRequest` handed to `MainScaffold`); never a second picker, a paste mode or a pick banner in the Files screen.
- **Live states** (waiting for a device) use `WaitingLine` with the pulsing mint `LiveDot`.
- **Text**: labels uppercase mono; body text sentence case; short.

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

# Flair rule (depth and feel, `ui/Flair.kt`)

The brand stays; it just must not feel flat. Reuse the helpers in `ui/Flair.kt`, never hand-roll:

- **Springs** for things that arrive (`cbSpring()`; already wired into `ui/Motion.kt`). Things that leave stay short fades.
- **Press feedback**: every tappable tile, tab, bar action and button uses `pressScale()` and gives a tick (`rememberTick()`); a long press that starts a selection uses `rememberThump()`.
- **Depth**: filled frames get the glow and top sheen from `doubleChamferFrame`; the one primary button glows (`cbGlow`); bars use `topSheen`; screens use `cbGrid`, which now carries the ambient mint/violet glow.
- **Glass**: dialogs blur the screen behind them (`CbDialog`).
- Only brand colours at lower opacity; no new hex colours.
