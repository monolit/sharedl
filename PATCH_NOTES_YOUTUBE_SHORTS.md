# Historical notes: YouTube fallback v3

This file records the earlier v3 YouTube fallback changes. It is superseded by
`PATCH_NOTES_JS_JOURNAL_V4.md`: v4 removes the custom WebView JS challenge
provider and the `yt-dlp-ejs` package, and leaves JS-runtime selection to yt-dlp.

The v3 changes retained in the current source are:

- YouTube video success requires a real media file; image thumbnails and sidecars are not treated as videos.
- YouTube thumbnail fallbacks are suppressed when no video stream is available.
- Incomplete YouTube URLs without a valid video ID fail early with a clear error.
- The fallback sequence does not retry `web_embedded` or `tv_simply`.

See the v4 patch notes for current JavaScript and Journal behavior and its YouTube limitations.
