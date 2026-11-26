# ShareDL — default yt-dlp JS and Journal UI (v4)

## YouTube / JavaScript

- Removed the `yt-dlp-ejs` package from the Chaquopy build and updater.
- Removed the Android WebView JavaScript challenge provider and its Kotlin bridge.
- Left JavaScript-runtime detection and defaults to yt-dlp (Deno is yt-dlp's default runtime when available).
- Disabled yt-dlp remote components, and filtered saved custom JS-runtime overrides so yt-dlp can use its own defaults.
- Removed stale `yt-dlp-ejs` update wheels left by earlier app versions.
- Kept the separate WebView sign-in screen for saving website cookies; it is not connected to yt-dlp's JS challenge execution.

**Important limitation:** yt-dlp's JavaScript runtime and EJS challenge scripts are separate. The app no longer installs or downloads EJS scripts, and this source does not bundle Deno for Android. Therefore YouTube challenge solving may be unavailable and some formats may be missing. This is the consequence of disabling EJS and the custom WebView provider, not a guarantee that every YouTube URL will work.

## Journal

- Added a fixed action row above the scrolling log.
- Added `Clear` and `Copy all` actions; copy sends the entire current journal text to the clipboard.
- Added copy confirmation and synchronized all resource translations through `tools/gen_strings.py` (en, ru, uz, kk, tg, be).

## Checks

- Python syntax compilation.
- XML parsing and locale key parity.
- Translation generator consistency.
- Journal control and downloader source reference checks.
- ZIP integrity.

Gradle/Android assembly and live YouTube downloads were not run in this environment.
