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
NewPipeExtractor v0.26.3 (June 2026) — v0.26.2 and earlier hit YouTube's SABR
enforcement and return only a 360p muxed stream, with no adaptive formats at all.

## How the quality list works

YouTube serves two kinds of streams, and the list mixes both:

- **`1080p · merge with audio`** — DASH video-only. These carry no sound, so the app
  downloads the video track and the best AAC audio track separately, then merges them.
  This is where every resolution above 720p lives.
- **`360p · direct, no merge`** — muxed, video and audio already in one file. Tops out at
  360p on almost everything now, occasionally 720p. Goes straight to DownloadManager.
- **`Audio only`** — the audio track by itself, at full quality.

The merge uses `MediaMuxer`, which is part of the Android framework. It's a remux, not a
re-encode: the compressed H.264 and AAC samples are copied into a new MP4 container
untouched. Takes a second or two and loses nothing. This is why there's no ffmpeg
dependency — FFmpegKit was retired in 2025 and pulled from the repos, but transcoding was
never actually needed for this.

Only MPEG-4 video is offered for merging. YouTube also serves WebM/VP9 at the same
resolutions, but pairing VP9 with AAC needs a WebM muxer and a different audio choice —
two containers to reason about instead of one, for no visible difference.

## Read this before you're disappointed by the output

**Some videos will fail** with "Sign in to confirm you're not a bot." That's YouTube's
integrity check. poTokens are the workaround — the extractor accepts them but doesn't
generate them, and generating one means running YouTube's own JS in a WebView. NewPipe
still fights this one.

**It will break.** Not if, when. YouTube changes its player and extraction stops working.
The fix is nearly always bumping the NewPipeExtractor version in `app/build.gradle` and
letting Actions rebuild — which you can do from a phone in about thirty seconds.

## Known limits

- MPEG-4 video only for merging. No WebM/VP9, no HLS, no live streams.
- Merging runs on the activity's executor, not a foreground service — leaving the app
  mid-download cancels it. Direct downloads go through DownloadManager and survive it.
- No playlists, no queue.
- Debug signing. If you switch on `minifyEnabled` for a release build later, you need
  ProGuard keep rules for Rhino or signature deobfuscation gets stripped and the app
  breaks in release only.
- Sideload only. Play's Developer Program Policy bans apps that download YouTube content.

## Why downloads are chunked

YouTube throttles a single long-lived GET on a stream URL down to roughly playback speed.
The intent is to stop a player buffering an entire video ahead of itself; a downloader eats
the same limit, and no amount of local bandwidth helps because the cap is applied at the
far end. So `fetchToFile` requests the file in 4 MB ranges instead of asking for all of it
at once — each request gets a fresh budget. It's the same trick yt-dlp uses.

If it's still slow, the next lever is fetching several ranges in parallel rather than one
after another. That needs a `RandomAccessFile` and seeks instead of a plain append, which
is why it isn't here yet.

## Audio track selection

YouTube ships dubbed audio tracks alongside the original, frequently at identical bitrates.
Ranking on kbps alone picks whichever the extractor happened to list first — a coin flip
between the original and a dub, and one that looks like a bug in the muxer rather than a bad
guess about language.

`rank()` treats `AudioTrackType.ORIGINAL` as decisive and uses bitrate only to break ties.
But that only helps when YouTube tags the tracks at all, and it doesn't always. So the choice
isn't left to ranking alone: a dropdown above the format list shows every merge-eligible
track, defaulted to the ranked best, and the merge reads it at download time.

Each entry spells out its type. If they all read `(untagged)`, YouTube sent no track metadata,
`rank()` had nothing to work with, and the dropdown is the only thing preventing a random
language. That's worth seeing on screen rather than discovering in a finished file.
