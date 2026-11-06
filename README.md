# Reach Launcher

An original Android home-screen app (package `com.reach.launcher`). It is written from scratch on
public Android framework APIs only: no AndroidX, no Gradle, and no third-party code or assets.

## Build and install

```
python -I build.py            # -> ReachLauncher.apk (aapt2 -> javac -> d8 -> zipalign -> apksigner)
python -I build.py --install  # also runs `adb install -r` using ../adb.exe
```

Needs `%LOCALAPPDATA%\Android\Sdk` with build-tools 35.0.1 and platforms/android-35 (both already on
this machine), plus a JDK on PATH. The APK is signed with the local `debug.keystore`.
minSdk 26 (Android 8), targetSdk 35.

To use it, open **Reach settings → Make Reach the default home app**. App shortcuts and pinning
only work when Reach is the default home app (an Android rule).

## Features

| Area | What it does | Code |
|---|---|---|
| Home layouts | **App pages** (default): a grid of apps you swipe left and right, page dots, a dock of favorites, and a clock + widgets page to the left. **One-handed list**: clock and widgets at the top, favorites near the bottom. | `HomeActivity`, `Pager`, `AppCell`, `AppRow` |
| App list | Swipe up for A–Z sections. Drag the letter strip on the edge to jump to a letter. Work-profile apps are included and badged. Rows rebuild when apps are installed, updated or removed. | `AppRepo`, `AlphabetBar` |
| Search | Ranks prefix, word-prefix, initials ("gm" → Google Maps), substring, then in-order letters. Enter opens the top result. Web and app-store search rows are included. | `Ui.score` |
| Favorites | Long-press an app → add, remove, move up or down. Phone, Messages, browser, camera and gallery are added on first run. | `Prefs`, `AppRepo.seedFavorites` |
| App shortcuts | The long-press sheet lists each app's shortcuts. Long-press a shortcut to pin it as a favorite. Accepts `requestPinShortcut` from other apps. | `AppRepo.appShortcuts`, `PinActivity` |
| Notifications | Count badges, and in list mode the latest message under each favorite (tap to open it, long-press to dismiss). Needs notification access. | `NotifService` |
| Widgets | Any installed app widget, with bind, configure and reconfigure steps. Resize taller or shorter, reorder, remove. Accepts `requestPinAppWidget`. | `Widgets`, `PinActivity` |
| Icon packs | Any icon pack that uses the common `appfilter.xml` format. Apps the pack doesn't cover get its back, mask and scale treatment. | `IconPack` |
| Icon styles | Built-in looks drawn from each app's own icon: monochrome glyph, glyph on a tile, or letter tile, in white, black or accent. They also work on top of an icon pack. The glyph comes from the app's monochrome layer (Android 13+) when it has one, otherwise from its foreground silhouette, otherwise a letter. | `IconStyler` |
| Fonts | 46 bundled Google Fonts (SIL Open Font License, copied from `../WidgetStudio/res/font`) plus system fonts. The picker previews each font in its own typeface. Home text can be white or black. | `FontList`, `Ui.typeface` |
| Themes | Named looks (accent, font, text color, icon pack/style, depth, sizes, dim) stored as JSON. Seven built-ins. Apply, create from the current look, edit live with a preview card, duplicate, rename, delete. | `Themes`, `SettingsActivity.editorPage` |
| Squircle & depth | Squircle reshapes any icon into a smooth superellipse tile. Shadow & depth (Flat → Deep) adds soft drop shadows, a top highlight, raised dock/search bar and deeper text shadows. Icons render at 256 px. | `IconStyler` |
| Folders | Long-press an app → Add to folder / New folder. Folders sit first on the app pages as glass tiles and open in a glass popup (tap the title to rename). Plus an automatic "Rarely used" folder: apps unopened for 3 weeks, from Android usage history if granted, otherwise from launches counted in Reach after a week. | `Folders`, `HomeActivity.openFolder` |
| Glass settings | Minimal card pages in a translucent window that blurs the screen behind it (Android 12+, where the device allows cross-window blur). Otherwise a frosted gradient from the wallpaper's colors. Sheets and folders follow the same rule. | `SettingsActivity`, `Sheet`, `Ui.frosted` |
| Edit mode | Long-press empty space (or the clock). Every element gets a dashed outline. Tap one for its options, pinch to resize, swipe between pages. Bottom strip: page thumbnails, ＋ adds a tile page, ✕ removes one. Top bar: Elements, Add tile, Add widget, Wallpaper, Theme, Done. Edits are saved into the active theme. | `HomeActivity` (edit mode section) |
| Clock & search bar | Clock: size, font/weight, alignment, 12/24 h, seconds, color, date style and size, show/hide. Search bar: show/remove, top or above the dock, height, shape, transparency, placeholder text. | `HomeActivity.applyClock/applySearch` |
| Tiles | Reach's own tiles: Date, Day progress, Battery, Week calendar, App tiles (small or wide). They sit on the clock page and on extra tile pages. Add, reorder and remove them in edit mode. | `Tiles` |
| Widget stacks | Several widgets in one slot; swipe up/down to flip. Adding a widget asks: new, or stack with an existing one. Widget cards: none/glass/dark/surface with adjustable corners (part of the theme). | `Widgets` |
| Light / dark | Appearance: Light, Dark or Auto (follows the phone). Themes can have light + dark versions. "Minimal" is built in: solid soft-grey or near-black background, surface tiles, outline icons. | `Prefs.applyLook`, `Themes.minimal` |
| Full-screen theme preview | Tapping a theme previews it on the real home screen, with ‹ › to browse all themes and Apply/Cancel. Cancel, Back or leaving restores the previous look. | `HomeActivity.startPreview` |
| Gallery wallpaper | Change wallpaper → Photo from gallery → home, lock, both, or crop & preview. | `WallpaperPicker` |
| Customization | Layout, grid columns, left-handed mode, icon and label size, clock, search bar, wallpaper dim, font, accent color, icon pack, swipe-down action, previews, hidden apps, renames. Settings rows are at least 64dp tall with 28dp between sections. | `SettingsActivity` |
| Gestures | Swipe up opens the list. Swipe down opens the notification shade (or search). Long-press empty space opens the home menu. The home button returns to the first app page. | `HomeActivity.dispatchTouchEvent` |

## Not done yet

- **Drag-and-drop rearranging** on app pages. Pages are alphabetical; the dock order comes from favorites (Move up/down).
- **Per-app icon picking** from an icon pack's full icon list. **Drag-to-reorder inside folders.**
- **Outline icons** are traced from each app's symbol, so a few apps without a monochrome layer or clean foreground get a letter instead.
- **Real blur depends on the device.** On the test Galaxy, Android reports cross-window blur as disabled for apps (`mBlurEnabled=false`), so Reach shows the frosted fallback there.
- **No bundled iOS icons**: Apple's icon artwork is copyrighted. Use the Squircle style, or install a third-party iOS-style icon pack (Theme → Icon pack → Get icon packs).
- **Placing widgets inside the app grid** like icons. Widgets live on the page to the left of the first app page.
- **Double-tap to lock** and other screen-off gestures. These need an AccessibilityService.
- **Swipe-down to the notification shade** uses a non-SDK call. If a future Android version blocks it, swipe-down falls back to search.
- **Backup and restore** of the layout, plus usage-based suggestions.
- **No automated tests yet.** Testing so far was manual on a Galaxy foldable (Android 15).
