# ShareDL — YouTube fallback patch (v3)

This patch is based on `ShareDL-youtube-fallback-v2.zip` and fixes issues visible in the latest ShareDL journal screenshots.

## Changes

- A YouTube download is successful only when a real media file exists. `.webp`, `.jpg`, thumbnail, metadata, subtitle, and temporary files cannot be mistaken for a video.
- The YouTube result filter is applied to yt-dlp, gallery-dl, and the final Open Graph fallback. An `og:image` thumbnail is never returned as a successful video download.
- When a YouTube video cannot be obtained, the retry sequence is `android_vr` and then `tv`, without cookies or SponsorBlock. The previous `tv_simply` retry was removed because current yt-dlp PO Token documentation lists GVS PO Token as required for that client.
- yt-dlp warning diagnostics are preserved when an attempt fails, so a missing GVS PO Token or skipped HTTPS formats can be recognized by the fallback logic.
- Incomplete links such as `https://youtube.com/shorts` or `https://youtube.com/watch` now fail early with a clear message instead of being sent to the generic extractor.
- The 15-second WebView challenge timeout from v2 is retained; `web_embedded` is not retried because it can repeat the same JS challenge and only supports embeddable videos.

## Checks performed

- `py_compile` passed for `app/src/main/python/dl.py`.
- Local tests passed for full/incomplete YouTube URLs, media-vs-thumbnail classification, final thumbnail suppression, extractor-argument replacement, and the `android_vr` → `tv` fallback sequence.
- ZIP integrity was checked after packaging.

## Limitations

This is a source ZIP, not an APK. Gradle/Android build and a live YouTube download were not run in this environment. The fallback clients cannot bypass private videos, age checks, geo restrictions, or all YouTube-side anti-bot changes. `tv` may return limited or DRM-protected formats in some cases. For the best results, update yt-dlp and its EJS components from ShareDL Settings before testing.
