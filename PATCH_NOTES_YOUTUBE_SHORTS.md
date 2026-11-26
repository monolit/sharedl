# ShareDL — YouTube fallback patch (v2)

Changes in this source ZIP:

- Removed the redundant `web_embedded` retry. It could invoke the same WebView JS challenge again and only works for videos that can be embedded.
- Capped a single WebView JS challenge wait at 15 seconds instead of 90 seconds.
- When YouTube formats are unavailable or the JS challenge fails, ShareDL now tries `android_vr`, then `tv_simply`, with cookies disabled.
- The fallback attempts skip SponsorBlock so a SponsorBlock API timeout does not delay each retry. The normal first attempt still respects the SponsorBlock setting.
- The selected fallback client replaces any user-specified `youtube:player_client` extractor argument while retaining other extractor arguments.
- YouTube video pages still cannot be reported as successfully downloaded by saving `og:image` as a thumbnail.

This is a source ZIP, not a built APK. Python syntax and ZIP integrity are checked. An Android/Gradle build and a live YouTube download were not performed. `android_vr` and `tv_simply` are logged-out fallbacks; videos requiring account cookies, age verification, regional access, or unsupported challenges may still fail.
