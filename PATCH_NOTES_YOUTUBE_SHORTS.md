# ShareDL — YouTube Shorts fallback patch

Changes in this source ZIP:

- On YouTube format-extraction failure, retry `web_embedded` before the `android_vr` fallback.
- `android_vr` retry no longer passes the cookie file (yt-dlp explicitly skips this client when cookies are supplied). This logged-out retry is only expected to work for publicly accessible videos.
- YouTube video pages no longer fall back to saving `og:image` as if it were the requested video. If no stream is available, ShareDL reports the failure instead of silently saving a thumbnail.
- WebView timeout message is more specific.

This is a source ZIP, not a built APK. Python syntax was checked; an Android/Gradle build and a live download test were not performed. YouTube client behavior changes frequently, so update yt-dlp from ShareDL's updater before testing. The patch cannot guarantee access to videos requiring login, age/region verification, or a currently unsupported YouTube challenge.
