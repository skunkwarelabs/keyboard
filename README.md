# keyBoard

A custom Android keyboard (IME) by **SkunkWare**. Clean layout, Material You
theming, glide typing, a full emoji browser — and, of course, the letter **B**
types as 🅱️.

<p align="center">
  <img src="store/play_feature_1024x500.png" width="640" alt="keyBoard">
</p>

## Features

- **Clean layout** — inset home row, wide shift/delete, and a
  `123` / `#+=` / `ABC` page system with a numbers page and a symbols page.
- **Material You theming** — colors are pulled live from the system dynamic
  palette (`android.R.color.system_*`) on Android 12+, tracking your wallpaper
  accent and light/dark mode. Falls back to a Material baseline below API 31.
- **Glide / swipe typing** — drag across the keys to type a word. No stock
  dictionary is available to a third-party IME, so it ships its own
  frequency-ranked wordlist (`assets/words.txt`) and decodes a gesture with a
  SHARK²-style shape match: the swipe path and each candidate word's ideal
  key-path are both resampled and compared, pruned to words sharing the swipe's
  first and last letter.
- **Emoji browser** — the full Unicode 16.0 set (`assets/emojis.txt`, ~1,900
  emoji) in a horizontally-scrolling grid, sorted into 9 categories with a tab
  strip, a **recently-used** section (persisted), and **name search**.
- **Smart shift** — tap for one-shot shift, double-tap for caps lock; the shift
  key is an icon that recolors per state, and the keycaps show capitals while
  engaged.
- **Rolling backspace** — hold to delete repeatedly, accelerating, then by whole
  words. Grapheme-cluster aware, so a 🅱️ (surrogate pair + variation selector)
  deletes in one press instead of decaying into the text-style box.
- **Haptics** — a strong taptic-style buzz fires on touch-down (no click-lag) for
  every keypress, including backspace repeats and emoji taps.
- **🅱️** — the B key renders as a flat red tile and commits the real 🅱️ emoji.

## Project layout

```
app/src/main/
├── java/com/skunk/keyboard/KeyboardService.kt   # the entire keyboard
├── assets/
│   ├── words.txt        # frequency-ranked wordlist for swipe decoding
│   └── emojis.txt       # Unicode 16.0 emoji, grouped by category, with names
├── res/drawable/        # shift / caps-lock / emoji vector icons
├── res/mipmap-*/        # launcher icon (OpenMoji 🅱️)
└── AndroidManifest.xml  # the InputMethodService declaration
store/                   # Google Play assets (icon, feature graphic)
```

It's a single `InputMethodService` written in plain Kotlin Views (no Compose) —
the keyboard surface is built programmatically, the emoji grid uses a
`RecyclerView`, and the whole input view is rebuilt on page/layout changes.

## Build

Requires the Android SDK (`local.properties` → `sdk.dir`) and a JDK 11+.

```sh
./gradlew :app:assembleDebug
```

## Install & enable

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable com.skunk.keyboard/.KeyboardService
adb shell ime set    com.skunk.keyboard/.KeyboardService
```

A keyboard has no launcher activity, so it won't appear in the app drawer — it
shows up under **Settings → System → Languages & input → On-screen keyboards**.

## License

GPL — see [LICENSE](LICENSE).
