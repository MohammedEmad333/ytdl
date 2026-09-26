# Media Downloader — Android / Java

A personal-use Android media downloader built with Java and NewPipeExtractor.

Supported input includes YouTube, SoundCloud, and public Spotify links. Spotify support
uses public track/album/playlist metadata to find a matching YouTube source; it does **not**
extract or bypass Spotify audio streams.

Downloads are handled by an Android foreground service and saved through the Android media
APIs, so queued work can continue when you leave the app and report progress in the
notification bar.

No Android SDK is required on your local machine to build the APK: GitHub Actions installs
the toolchain and produces the debug artifact.

## Responsible use

Use this project only for media you own, media you have permission to download, or content
whose license and applicable rules allow downloading. You are responsible for complying
with copyright law and the terms and policies of the services you access.

This project is not affiliated with, endorsed by, or sponsored by YouTube, Google, Spotify,
or SoundCloud. Their names and trademarks belong to their respective owners.


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
NewPipeExtractor v0.26.5 — older extractor builds may hit YouTube delivery changes such as SABR
enforcement and return only a 360p muxed stream, with no adaptive formats at all.

## How the quality list works

YouTube serves two kinds of streams, and the list mixes both:

- **`1080p · merge with audio`** — DASH video-only. These carry no sound, so the app
  downloads the video track and the best AAC audio track separately, then merges them.
  This is where every resolution above 720p lives.
- **`360p · direct, no merge`** — muxed, video and audio already in one file. Tops out at
  360p on almost everything now, occasionally 720p. Goes straight to DownloadManager.
- **`Audio only`** — the audio track by itself, at full quality.

Both tracks are written interleaved, in timestamp order. This matters more than it sounds:
the obvious version — copy the whole video track, then the whole audio track — produces a
valid MP4 that plays badly, because MediaMuxer writes samples in the order it receives them.
That layout puts all the video in the first half of the file and all the audio in the second,
so playback seeks across the whole file and back for every 20ms of audio and read-ahead never
helps. It only shows at high resolutions: a 360p file fits in cache and the thrashing is free,
a 150MB 1080p60 file doesn't and it stutters.

The merge uses `MediaMuxer`, which is part of the Android framework. It's a remux, not a
re-encode: the compressed H.264 and AAC samples are copied into a new MP4 container
untouched. Takes a second or two and loses nothing. This is why there's no ffmpeg
dependency — FFmpegKit was retired in 2025 and pulled from the repos, but transcoding was
never actually needed for this.

Only MPEG-4 video is offered for merging. YouTube also serves WebM/VP9 at the same
resolutions, but pairing VP9 with AAC needs a WebM muxer and a different audio choice —
two containers to reason about instead of one, for no visible difference.

## Read this before you're disappointed by the output

**Some videos show only the muxed 360p** while others on the same build offer 1080p60. When
that happens the status line prints a diagnostic instead of the audio picker, reading
`usable/sent`. It exists because there are two causes with opposite fixes and no way to tell
them apart by looking: either YouTube sent no adaptive formats — the poToken wall, which
rolls out per video — or it sent them and `Streams` filtered them out, most likely because
they're delivered as DASH manifests rather than progressive HTTP. `sent` being 0 means it
isn't this app's doing. `sent` healthy with `usable` at 0 means it is.

**Some videos will fail** with "Sign in to confirm you're not a bot." That's YouTube's
integrity check. poTokens are the workaround — the extractor accepts them but doesn't
generate them, and generating one means running YouTube's own JS in a WebView. NewPipe
still fights this one.

**It will break.** Not if, when. YouTube changes its player and extraction stops working.
The fix is nearly always bumping the NewPipeExtractor version in `app/build.gradle` and
letting Actions rebuild — which you can do from a phone in about thirty seconds.

## Pause and resume

Pausing is nearly free, and by accident. Downloads were already chunked into 4 MB ranged
requests to defeat YouTube's throttling, so stopping between chunks and later restarting from
the partial file's length needs almost nothing extra.

Resume trusts the length of the file on disk as the offset to ask for next. That's only safe
because of one invariant: partial files are append-only, and they're deleted outright on
cancel or failure. A file is never left in a state where its length lies about its contents.
Break that and resumed downloads corrupt silently.

A paused task re-runs from the top when resumed. An already-complete stage costs one request
that returns 416, which `fetchToFile` reads as "already have it all" — so the video stage
skips itself and the audio stage picks up where it stopped.

The limit worth knowing: YouTube's stream URLs expire after a few hours. Pause overnight and
resume will fail with a 403, because the URL is stale rather than the file being wrong.
Re-fetch the video and queue it again.

## The poToken wall, and the token-server pointer

Some videos return only a muxed 360p stream: `0/0` in the diagnostic, no manifests. That's
YouTube gating the adaptive formats behind a poToken — an integrity token minted by running
its BotGuard challenge, which the phone can't safely do. The reference tools (NewPipe, yt-dlp)
hit the same wall for the same reason; it isn't this app's filters.

The practical escape is bgutil running as a local server in Termux — it runs the challenge and
mints tokens. So when a fetch comes back `0/0`, the app checks whether that server is answering
on `127.0.0.1:4416` and tailors the diagnostic: if it's up, it points you at `ytdl <url>`; if
not, it tells you to start it. The probe only runs when formats are actually missing, so a
healthy fetch never pays for a localhost call.

When the server is up, the app goes further than pointing: on `0/0` it mints a token from
bgutil and **re-extracts with it**, so the higher qualities appear in the app directly. The
token must be set before extraction, so this is a second fetch with the provider active, not
a patch of the first result. `PoToken.Provider` implements the extractor's `PoTokenProvider`
interface, mints once per video (cached across the three client callbacks), and is torn down
after each attempt — a video-bound token is useless for the next video, and stale reuse is
what produces 403s.

The honest caveats stand: this leans on two experimental pieces (NewPipeExtractor's
pre-release poToken support and bgutil), so it needs occasional care when YouTube shifts
BotGuard. And there's one unsettled detail baked into a single line of `PoToken.Provider`:
both poToken slots get the same content-bound token. If YouTube ever demands a separate
visitor-bound token for the streaming URLs, the streaming URLs would 403 while extraction
succeeds — and that one line is where the fix goes. When the server isn't running, none of
this fires and the app behaves exactly as before.

## Why a Task is a page URL and not two URLs

Playlists and expiry-proof pause look like separate features and are the same one.

A playlist item is queued before it's ever been extracted — you pick 1080p once for forty
videos and none of them have stream URLs yet. A task paused overnight has stream URLs that
have since expired. Both need the same thing: a task that knows *what was asked for* and can
work out the URLs whenever it needs them.

So a `Task` is a page URL plus a `Spec` — kind, target height, audio track — and stream URLs
are a cache. Single videos arrive pre-resolved because extraction is what drew the list;
playlist items arrive with nothing; expired tasks throw theirs away and resolve again. One
mechanism, three situations.

`Streams` holds the selection logic because both sides now need it and they must agree. The
activity uses it to show you the options, the service to re-derive them later. If they ever
disagreed you'd pick 1080p and silently get something else.

**The hazard this creates:** resume trusts the partial file's length as its offset. A fresh
URL is supposed to serve identical bytes for the same itag, and does — but if it ever didn't,
appending to the old partial would produce a corrupt file with no error anywhere. So the byte
length from the first attempt is recorded and re-checked; a mismatch fails loudly instead.

## The queue survives the process

The queue is written to `queue.json` in the app's files dir on every state change, and read
back by whichever of the activity or service is alive first.

Without this, expiry-proof resume was a claim rather than a feature. The queue lived in a
static list, so swiping the app away — or Android reclaiming the process, which is the norm
overnight — took every paused task with it and left its partial files orphaned in cache
forever. "Pause overnight" is exactly the case where the process doesn't survive.

The nice part falls out of an invariant that already existed. A task that was mid-transfer
when the process died has a valid partial on disk, because partials are append-only and are
deleted only on cancel or failure. That is precisely the pause invariant — so a killed
transfer and a paused one are the same situation, and restore identically. Any active state
comes back as PAUSED and resumes correctly. Crash recovery came free.

Stream URLs are persisted too, stale or not: if they still work a restored task saves a
request, and if they don't the 403 path re-derives them anyway.

## The queue

Everything goes through `DownloadService`, a foreground service — including single-file
downloads that once went to `DownloadManager`. Two mechanisms meant two progress models and
two different behaviours when backgrounded, which is worse than either one alone.

Tasks run one at a time. Tap a live task in the Downloads tab to cancel it; the worker checks
between chunks, so it stops in a second or so rather than after finishing the file.

The activity polls the task list every 500ms while it's on screen. Polling rather than
registering a listener: nothing to leak, and the service outlives the activity by design.

## The look

Dark, monospaced, dense — an instrument panel rather than a feed. The subject is codecs,
containers, bitrates and byte counts, so it's built to read as machine data: mono for
anything a machine produced, sans for prose. Amber means in transit and nothing else is
allowed to use it.

The one flourish is progress: a hairline rule under each row that fills, not a bar widget, so
a queue reads as several lines advancing at different rates. It's two weighted views rather
than a `ProgressBar` — weight 0 collapses to nothing, so 0% and 100% both work without a
special case.

Custom fonts are the obvious next lever and are deliberately absent: font files are binary.
Monospace is a system face, which is why it carries the personality here.

Every text size multiplies through `Ui.TYPE_SCALE`, so adjusting the whole scale is one
number rather than thirty scattered edits.

Tabs switch by horizontal fling as well as by tapping. The gesture detector is fed from
`dispatchTouchEvent` and never consumes anything — it only watches. Intercepting would mean
fighting the ListViews for the gesture, and a ListView calls `requestDisallowInterceptTouchEvent`
as soon as it starts scrolling, so an interception-based version drops swipes that begin on a
scrolled list.

The launcher icon is an adaptive icon whose layers are vectors rather than the usual PNG set,
for the same reason. A play triangle turned to point down over a bar: a play button one way, a
download arrow hitting a floor the other.

## Known limits

- MPEG-4 with a muxable codec for merging. AV1 is offered only on Android 12+, since
  MediaMuxer couldn't put it in an MP4 before that. No WebM/VP9, no HLS, no live streams.
- Playlists are capped at 200 items — each page is another round trip.
- Subtitles come out as TTML, not SRT. That's what YouTube's default format is through this
  API; getting VTT or SRT means asking the extractor directly rather than via StreamInfo.
- A task killed during SAVING leaves a pending MediaStore row behind. It's invisible and the
  system reaps it after a week.
- One task at a time.
- Debug signing. If you switch on `minifyEnabled` for a release build later, you need
  ProGuard keep rules for Rhino or signature deobfuscation gets stripped and the app
  breaks in release only.
- Distributed as a sideload/debug build. Review the policies of any app store or service before distributing it there.

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

## License

This project is released under the MIT License. See [LICENSE](LICENSE).
