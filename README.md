# YouTube downloader — NewPipeExtractor + Java

Paste a link, pick a format, DownloadManager saves it to Downloads.

Five files, no binaries. GitHub Actions builds the APK, so no local Android SDK is needed
— you never have to open Android Studio to get a working app.

## Getting the APK

**Actions** tab → newest run → wait ~4 minutes → scroll to **Artifacts** → download
`app-debug-apk` → unzip → install. You'll need to allow install from unknown sources.

Every push to the default branch triggers a fresh build. You can also start one by hand
from the Actions tab via **Run workflow**.

## Why it's shaped this way

No `gradlew`, no `gradle-wrapper.jar`, no launcher icons, no `strings.xml`, no layout XML.
Those are either binary or extra files, and both are hostile to a phone-only workflow. So
the workflow installs Gradle itself, the UI is built in code, and the manifest points only
at framework resources. It's not how you'd normally lay out an Android project — it's what
survives being typed into a browser.

Versions, since you can't easily check these from a phone: AGP 8.13.0 with Gradle 8.13
(that pairing is from Google's own compatibility table), compileSdk 36, JDK 17.
NewPipeExtractor v0.26.2 is the current release as of May 2026.

## Read this before you're disappointed by the output

**360p is the ceiling, and that's not a bug.** YouTube serves two kinds of streams. Muxed
(video and audio in one file) tops out at 360p on almost everything now, occasionally 720p.
Everything above is DASH: video-only and audio-only as separate files. This app lists muxed
video and standalone audio, so both download as single playable files.

1080p means downloading two streams and muxing them. The usual Android answer for that,
FFmpegKit, was retired by its maintainer in 2025 and pulled from the repos. Community forks
exist, but it's a genuine project, not an afternoon.

**Some videos will fail** with "Sign in to confirm you're not a bot." That's YouTube's
integrity check. poTokens are the workaround — the extractor accepts them but doesn't
generate them, and generating one means running YouTube's own JS in a WebView. NewPipe
still fights this one.

**It will break.** Not if, when. YouTube changes its player and extraction stops working.
The fix is nearly always bumping the NewPipeExtractor version in `app/build.gradle` and
letting Actions rebuild — which you can do from a phone in about thirty seconds.

## Known limits

- Muxed video and audio-only. No DASH, no HLS, no live streams.
- No playlists, no queue.
- Debug signing. If you switch on `minifyEnabled` for a release build later, you need
  ProGuard keep rules for Rhino or signature deobfuscation gets stripped and the app
  breaks in release only.
- Sideload only. Play's Developer Program Policy bans apps that download YouTube content.
