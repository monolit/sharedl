# ShareDL v5 — Journal auto-scroll

## Changes
- Journal now automatically scrolls to the bottom whenever `DownloadState.log` changes.
- Scrolling is posted to the `ScrollView` queue so it happens after the updated `TextView` content has been laid out.
- Existing pinned `Clear` / `Copy all` controls are unchanged.

## YouTube log notes
- `n challenge solving failed` means yt-dlp did not obtain the YouTube challenge response; without a usable JS runtime/EJS challenge scripts, available formats may be limited.
- `android_vr` warning about missing GVS PO Token followed by HTTP 403 means the selected format URL was rejected by YouTube. The app cannot guarantee a tokenless download for that video.
- `The page needs to be reloaded` from the `tv` player is a YouTube-side player response, not a local file-write error.
- `chrome131_android` impersonation is unavailable in the installed curl_cffi build; it is an optional retry and does not indicate that the original URL is malformed.
