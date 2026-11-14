# NOVA Downloader — Android / Java

NOVA is a redesigned, native Android media downloader built with Java and NewPipeExtractor.

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


## NOVA interface

- **Explore:** prominent search and link entry, source chips, optional advanced filters,
  media previews and quality choices.
- **Transfers:** live progress, Wi-Fi-only mode, queue ordering, pause/resume, retry and
  cancellation controls.
- **Library:** completed and failed items with open/share/retry actions.
- Persistent three-destination bottom navigation, accessible 48 dp touch targets,
  high-contrast dark colors, scrollable source chips and a matching adaptive icon.
- The existing download engine and queue persistence remain unchanged by the redesign.

## NOVA 1.2: preferences and smarter library

- **Settings (gear icon):** change the app's primary interface language between Arabic,
  English, and the device language. Main navigation, search/transfer controls, Library,
  and Settings are bilingual; some detailed extraction/error descriptions remain English.
- **Preferred quality:** choose a default video resolution (360p to 2160p) and MP3 bitrate
  (128, 192 or 320 kbps). When that format exists, it appears first; unavailable
  qualities are never fabricated or downloaded automatically.
- **Network:** the same Wi-Fi-only restriction can now be changed in Settings.
- **Library:** search saved media by title, artist or format, filter by video/audio/
  subtitles/failed items, and sort by newest, oldest or title.
- Preferences stay on the device and do not need an account or internet connection.
- Existing queued tasks and saved files are retained when upgrading.

## NOVA 1.3: storage health and recovery

- Settings now includes **Storage & Recovery**, with an on-device view of space
  available to cache, paused/queued tasks and protected partial files.
- The scan reports task-specific temporary files eligible for cleanup. Files
  are never removed merely for being large: only recognized task-cache names
  older than 24 hours that do not belong to unfinished queue tasks qualify.
- Cleanup is **manual**, needs confirmation, and is blocked while a download
  worker is active. The worker and cleanup share a maintenance lock so they
  cannot write/delete the same cache entry concurrently.
- Completed media in Android MediaStore, thumbnails, other apps' files and
  partial files for queued or paused downloads are not selected.
- Files removed are counted and reported; an attempted cleanup is not treated
  as successful if files cannot be deleted.
- This does not scan or delete pending MediaStore rows left by an abrupt power
  loss. Those require a separate verified ownership/recovery workflow.

## NOVA 1.4: scheduling, playlists, signed releases

- **Daily download window:** optional start/end hours in device local time, including
  windows that cross midnight. The app checks the policy before selecting queued tasks
  and at transfer checkpoints. A blocked transfer remains queued for later.
- **Charging-only mode:** optional requirement to have a power source plugged in.
  Existing Wi-Fi-only preferences still apply.
- **Background reminder:** Android WorkManager periodically checks for queued work when
  a scheduling rule is enabled. An eligible task triggers a rate-limited tap-to-open
  reminder, not an automatic long-running foreground download from the background.
  Periodic checks are approximate, not exact alarms; Android can defer them.
  Android 13+ requires notification permission to show the reminder.
- **Playlists:** duplicate source URLs are removed without changing the order. The
  collection picker now supports searching within titles and selecting or clearing
  only the currently visible items, preserving selections hidden by filters.
- **Signed release workflow:** a separate manual Actions workflow checks tests and
  produces a signed release APK only when the repository's GitHub Actions secrets
  `NOVA_KEYSTORE_BASE64`, `NOVA_KEYSTORE_PASSWORD`, `NOVA_KEY_ALIAS`, and
  `NOVA_KEY_PASSWORD` are configured. The keystore must be generated and
  retained securely by the maintainer; no keys or passwords are committed.
  The workflow uploads a signed APK artifact but does not publish a public release.
  A debug APK remains available from the normal build workflow.

## NOVA 1.4.1: responsive storage recovery

- Safe cleanup now **returns immediately** if a transfer owns the cache-maintenance lock.
  Previously the operation could wait until a large download completed even though
  cleanup was meant to be unavailable while a transfer was running.
- Lock ownership, current worker status and queued-task protection are rechecked before
  deletion. Tests cover a busy lock owned by a different thread and a running worker.
- The download service, existing resume files and saved media remain unchanged.

## NOVA 1.4.2: crash-safe pending media recovery

- New downloads record the exact MediaStore URI in a local atomic ledger
  immediately after insertion, before copying media. Completed and successfully
  rolled-back rows are removed from the ledger.
- **Settings > Storage & Recovery** now shows tracked pending saves and offers a
  separately confirmed cleanup for entries older than one hour.
- Recovery deletes only a ledger-tracked row if MediaStore itself still reports
  `IS_PENDING=1`, no transfer is running and the worker maintenance lock is free.
  It never scans or bulk-deletes arbitrary library files.
- A completed media item (`IS_PENDING=0`) is never deleted, even if an interrupted
  ledger update left a marker behind.
- Failed/unverifiable deletions stay in the ledger for another manual inspection;
  corrupt ledgers are not silently replaced.
- The ledger is device-local (noBackupFilesDir) and is not included in cloud backup.
- Recovery is conservative and cannot repair a crash occurring in the tiny gap
  between MediaStore insertion and recording its URI; manual inspection is still
  required on real Android devices.

## NOVA 1.5: public X / Twitter post videos

- Paste an **X.com or Twitter.com public post link**, or share one into NOVA
  through Android. The Explore screen shows the attached MP4 video(s), lets you
  choose a clip, and queues it with the standard pause/resume manager.
- Supports public post video and GIF-as-MP4 files only. It does not access
  private, deleted, protected, paid or login-required posts.
- Video metadata is fetched from the independent **FxTwitter** public API
  (`api.fxtwitter.com`). This third-party service is not affiliated with X
  or NOVA. The app sends **only the numeric post ID**, not login details or
  tracking/query parameters. Video data is downloaded from `video.twimg.com`.
- If the third-party service is down, changes its JSON format, or no public MP4
  is available, the app reports an error rather than guessing a media URL.
- Selected video numbers are persisted in the queue URL so a paused item can
  be resolved again on resume without downloading a different clip.
- Only download videos you own or have permission to save. Respect licenses
  and the applicable rules of the publishing platform.

## NOVA 1.5.1: safer public X video lookup

- Accepts ordinary public X/Twitter post URLs and shared `/video/1` through
  `/video/16` suffixes. The video list is still displayed for explicit choice.
- Limits third-party FxTwitter JSON responses to 512 KiB even if the server omits
  the Content-Length header. Oversized metadata fails safely instead of being
  buffered without a limit.
- Rejects unsupported suffixes, untrusted domains, private/login-only posts and
  non-MP4 media. No account cookies are used or collected.
- Public post metadata is obtained from an **independent third-party API**;
  successful extraction depends on that service and X remaining accessible.

## NOVA 1.6: public X video provider fallback and HLS

- For public X/Twitter posts, NOVA tries **FxTwitter** for public metadata,
  then **vxTwitter** when the first provider is unavailable or has no supported media.
  Only the numeric public post ID is sent to each third-party endpoint.
- Prefer directly downloadable public MP4 variants. If none exists, NOVA can
  process a **finite, unencrypted MPEG-TS HLS** playlist available on
  `video.twimg.com`; it caches segments locally, then remuxes them to MP4
  without giving FFmpeg network access.
- Segment cache survives pauses and restarts when the playlist fingerprint
  remains unchanged. A changed playlist resets the segment cache rather
  than appending bytes from a different source.
- Protection: HTTPS and CDN hostname allowlist, no redirects to other hosts,
  bounded metadata and manifest sizes, maximum 512 segments, 64 MiB per
  segment and 1 GiB total. The playlist must be finite with ENDLIST.
- Unsupported formats (DRM/encryption, live or gapped HLS, byte-ranged,
  discontinuous or fragmented-MP4 streams) fail with a clear error.
  No login cookies, private/protected posts or paywall bypass.
- MP4 downloads still use their normal resumable ranged requests.
  HLS resumes **between complete segments**; conversion may not pause
  instantly during the local FFmpeg remux.
- Public metadata and media URLs are provided by third parties that may
  change or become unavailable. No guarantee for every public post.
- Use only media you own or have permission to save.

## NOVA 1.6.1: open X and import local videos without an API subscription

- **Explore > Open post in X:** paste a valid X/Twitter post URL and open the
  canonical post through Android's registered X app or browser. NOVA does not
  receive login credentials, cookies or restricted media URLs.
- **Explore > Import saved video**, or **Library > Import a video from your device**:
  Android's system document picker lets you select a video already saved locally
  through authorized means. NOVA copies supported formats (MP4, WebM, 3GP, MOV,
  MKV) into `Movies/NOVA` using scoped MediaStore, then records the item as
  completed in NOVA's searchable Library, with Open and Share actions.
- The import runs off the main thread, checks file type and size, limits imports
  to 2 GiB and uses the existing pending-MediaStore crash-recovery ledger.
  If the Library entry cannot be persisted, the copied media is rolled back.
- **No X account connection, paid API, cookie import, background private-feed
  scraping or extra storage permissions.** Opening a post does not automatically
  download video from accounts requiring a login.
- Videos must already be available on the device for the import feature to work.
  Only import content you own or are authorized to save.

## NOVA 1.6.2: download integrity and retry reliability

- Validate partial HTTP 206 Content-Range units and exact byte ranges before appending
  to an existing download. Invalid responses fail closed instead of corrupting a file.
- A local expected length alone no longer marks an HTTP 416 response as complete.
  A valid server-provided total must match the local file and any recorded total.
- Retry HTTP 429 rate limits alongside transient server errors with bounded exponential
  backoff. Honor valid Retry-After seconds or HTTP dates, capped at 30 seconds;
  pause, cancellation and network scheduling still interrupt retry waits.
- Verify complete HTTP 200 bodies against a known expected size. Re-check free
  storage periodically during long un-ranged responses rather than only before
  the transfer starts.
- Regression tests cover malformed/unsupported Content-Range, unverified 416,
  HTTP 429 retry, bounded Retry-After and mismatched HTTP 200 size.
- No extra account permissions, background scraping or paid APIs.

## NOVA 1.6.3: HTTP representation safety

- Request uncompressed (`Accept-Encoding: identity`) file bytes for ranged downloads,
  and reject compressed server responses so saved offsets always refer to real bytes.
- For multi-chunk streams with a strong HTTP ETag, send `If-Range` on later
  requests and reject a changed ETag before appending mismatched content.
- Weak ETags and malformed validators are never used for `If-Range`.
  Existing paused downloads still use the prior known-length and range guards.
- MockWebServer regression tests cover compression rejection, identity encoding,
  unchanged and changed ETags, and weak validators.
- This is a transfer-integrity improvement; it does not add site-login access.

## NOVA 1.7: offline library playback and favorites

- **Library > Play** opens saved videos inside NOVA using Android's built-in
  `VideoView` and `MediaController`. Tap the video for play/pause, timeline
  scrubbing and seeking; playback is local, with no remote-stream requests.
- Only previously completed, recognized MediaStore video items can be opened
  internally. The player activity is not exported and accepts a library task ID,
  not an arbitrary URI from another app.
- **Open externally** remains available for videos, and audio/subtitle files
  still open in a compatible installed app. If the native player cannot decode
  a saved video, an external-player fallback is offered.
- **Favorites:** star any completed library item and filter by Favorites.
  Favorites are stored locally, matched to the exact saved MediaStore URI
  rather than only its task ID, and do not alter or delete media.
- Preserves video position across activity recreation and pauses playback when
  NOVA goes into the background. Playback codec support follows the device.
- Focused unit tests exercise URI validation, private player intent, favorite
  persistence and search/filter behavior. No new Android permissions, accounts,
  external SDKs or API subscriptions are required.

## NOVA 1.7.1: offline audio and library playback compatibility

- **Library > Play in NOVA** now opens locally saved audio as well as video,
  including downloaded files stored under Android's MediaStore Downloads collection.
  MP3s published in the audio collection and imported videos remain supported.
- Offline audio player uses Android MediaPlayer with play/pause, seek bar, elapsed
  time and duration, playback completion, lifecycle pause and external-player fallback.
  Media files stay on-device; no external player or API subscription is required
  for supported Android audio formats.
- Playback accepts only completed NOVA queue records whose MediaStore URI is
  an expected Downloads, Audio or Video item. Incoming intents carry only a task
  ID, never an arbitrary remote URL or an untrusted storage path.
- Media loading is asynchronous and no playback is started while the activity
  is in the background. Unsupported formats offer an external-player fallback.
- Regression tests cover audio and video eligibility, rejected remote URIs,
  wrong media collections, intent safety and time labels.
- Video playback still uses the existing native VideoView. This release does
  not add codec support beyond Android's available decoders.

## NOVA 1.7.2: library and favorites resilience

- The Library skips malformed task records with missing state instead of crashing.
- Audio and subtitle filters tolerate missing metadata and preserve normal MIME categorization.
- Favorites are limited to successfully completed, locally saved MediaStore items.
  Remote URLs, untrusted content providers, and MediaStore URIs with query
  parameters cannot be marked as favorites.
- Regression tests cover missing state, missing/unknown media MIME types,
  subtitles and invalid favorite target URIs. Existing offline playback remains intact.

## Getting the APK

**Actions** tab → newest run → wait ~4 minutes → scroll to **Artifacts** → download
`app-debug-apk` → unzip → install. You'll need to allow install from unknown sources.

Every push to the default branch triggers a fresh build. You can also start one by hand
from the Actions tab via **Run workflow**.

## Tests and queue recovery

GitHub Actions runs local unit tests before building the debug APK. Tests
exercise atomic queue replacement and HTTP range/resume validation.

The queue is written with Android AtomicFile; a failed or interrupted save
should retain the last complete snapshot. Android backup and device-transfer
rules exclude queue.json, which contains short-lived signed media URLs and
refers to partial files not available after transfer.

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
untouched. Takes a second or two and loses nothing. Video remuxing needs no FFmpeg, but a maintained FFmpegKit fork is now
included for MP3 encoding and subtitle conversion.

Only MPEG-4 video is offered for merging. YouTube also serves WebM/VP9 at the same
resolutions, but pairing VP9 with AAC needs a WebM muxer and a different audio choice —
two containers to reason about instead of one, for no visible difference.

## Read this before you're disappointed by the output

**Some sources expose only low-resolution formats.** This may result from
extractor or source changes, or from formats the app does not support.
The Streams diagnostic helper is not currently shown in the UI.

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

Stream URLs can expire. On a stale URL error the service tries to refresh
the stream URL and continue; extraction may still fail.

## Current format limits

Some media sources expose only a limited set of qualities. The current app
does **not** automatically inject poTokens: the PoToken helper is not wired
into extraction. Streams.diagnostics is also not displayed in the current UI.
The available formats depend on the source and extractor version.

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
- Supported subtitle inputs are converted to SRT through the maintained FFmpegKit
  dependency. Availability depends on the source.
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

## AURORA UI redesign

The native Android UI uses a central design system with a dark canvas and fresh
lime accent. Explore, Downloads, History, incoming share links, and the launcher
are visually coordinated. Explore contains link and text search with collapsible
source/duration/sort filters. Downloads has queue progress cards, a summary,
Wi-Fi preference, batch actions, collapsible sorting, and overflow-safe controls.
History includes status badges and open/share actions.

No WebView, new UI framework, or change to the queue's on-disk format is needed.
The views style themselves on creation, replacing post-render UI mutations.
