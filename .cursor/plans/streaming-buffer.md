# Stream a few minutes ahead on Android

Status: not started

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While an Android phone is streaming a book from a self-hosted Audiobookshelf server, keep enough audio buffered that a short connectivity loss (about 30–90 seconds, as in a car) does not stop playback. When the connection returns before the retry window ends, playback continues from the same position.

**Architecture:** Direct file playback already uses ExoPlayer's progressive source and HTTP range requests. The player is capped at 45 seconds of buffer, and once that buffer drains ExoPlayer treats the load as failed. Raise the direct-play buffer to 3 minutes of media and keep retrying network errors for about 3 minutes so a drained buffer waits instead of ending the session. Leave HLS (server transcode) on today's buffer. The server resets ffmpeg if the client requests a segment more than 10 segments ahead of segments already written.

**Tech Stack:** Kotlin, ExoPlayer 2.18.7 (not Media3), JUnit 4 on `android/app/src/test`. No Nuxt change, no server change.

## Global Constraints

- Plan only until a later session implements it. Do not start the code in the session that added this file.
- Karpathy guidelines (https://github.com/multica-ai/andrej-karpathy-skills/blob/main/skills/karpathy-guidelines/SKILL.md): think before coding, simplicity first, surgical changes, goal-driven verification.
- This repo is Jason's fork `rgeldr/audiobookshelf-app`. Do not open a PR, an issue, or a comment against `advplyr/audiobookshelf-app` or `advplyr/audiobookshelf`.
- Base the work on `master`. Do not retarget the auto-advance or progress-bar PRs.
- Android ExoPlayer only. Do not change iOS, Cast playback, the Capacitor web player, or the Audiobookshelf server.
- Do not migrate ExoPlayer 2.18.7 to Media3. The LoadControl and retry APIs this plan uses already exist in 2.18.7. A Media3 migration is a separate project.
- Do not add a disk cache (`SimpleCache`). Do not add a settings toggle. Do not bump `versionCode`, `versionName`, or `package.json` `"version"` (they stay `121` and `0.14.2-beta`).
- Do not refactor adjacent player code. The auto-advance queue and the progress-bar drag fix stay as they are.
- Test-first. Write a failing JUnit test, run it, then write the production code that makes it pass. This change has no visual tweak, so nothing is exempt from that order.
- Every new or changed decision function has a unit test. The LoadControl and the ExoPlayer policy classes are thin wrappers: they call those functions and add no decisions of their own.
- After the implementation is complete, run `/review-bugbot`. If that command is not available in the session, review the diff manually against this plan and say so in the PR.

## Assumptions

These are the product choices this plan locks in. Do not invent different ones during implementation.

1. The buffer target is 3 minutes of **media** time for direct play and direct stream, not a user setting. One minute is too close to a 90-second drop once playback speed is above 1x. A settings row would also touch `DeviceSettings`, `pages/settings.vue`, and the saved device JSON. Leave that out unless Jason asks.
2. "Ahead" means media time. At 2x, 180 seconds of audio is 90 seconds of clock time. At 3x it is 60 seconds. The retry window below covers a drop that outlasts the buffer.
3. Jason's driving case is a file the server direct-plays (typical mp3 or m4b/aac) through Caddy. HLS transcode keeps today's 45-second cap. See the HLS section for why a longer HLS buffer is unsafe without a server change.
4. Local files use the same longer buffer. Reading ahead from disk is cheap, and one profile for "not HLS" avoids a third path.
5. An outage longer than the retry window still ends playback the way a fatal player error ends it today: the existing toast and `closePlayback`. This plan does not keep a dead session open, and it does not resume by itself after that toast.
6. Cast playback is unchanged. The Cast player does not use this LoadControl.

---

## How streaming works today

The service builds one `ExoPlayer` in `initializeMPlayer`. It already replaces ExoPlayer's stock load control. The stock defaults are 50 seconds minimum and 50 seconds maximum (`DefaultLoadControl.DEFAULT_MIN_BUFFER_MS` / `DEFAULT_MAX_BUFFER_MS` in 2.18). This app is tighter than that:

```391:399:android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationService.kt
    val customLoadControl: LoadControl =
            DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                            1000 * 20, // 20s min buffer
                            1000 * 45, // 45s max buffer
                            1000 * 5, // 5s playback start
                            1000 * 20 // 20s playback rebuffer
                    )
                    .build()
```

There is no back buffer (`DEFAULT_BACK_BUFFER_DURATION_MS` is 0). Playback can start after 5 seconds buffered. After a stall it waits for 20 seconds before starting again. The builder does not call `setTargetBufferBytes` or `setPrioritizeTimeOverSizeThresholds`, so the audio byte cap stays at `DEFAULT_AUDIO_BUFFER_SIZE` = 200 × 64 KiB = 13,107,200 bytes (12.5 MiB). For a 64–128 kbps audiobook the 45-second time cap is what stops loading. For a high bitrate the byte cap can stop loading first: 12.5 MiB is about 70 seconds of 1,411 kbps PCM.

`preparePlayer` picks the source from `PlaybackSession.playMethod`:

| `playMethod` | Constant | Source | Data source |
|---|---|---|---|
| 0 direct play, 1 direct stream | not HLS | `ProgressiveMediaSource` | `DefaultHttpDataSource.Factory`, user agent `audiobookshelf_channel` |
| 2 transcode | `isHLS` | `HlsMediaSource` | `DefaultHttpDataSource.Factory`, plus `Authorization: Bearer <token>` |
| 3 local | `isLocal` | `ProgressiveMediaSource` | `DefaultDataSource.Factory` (file / content URI) |

```500:540:android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationService.kt
      if (playbackSession.isLocal) {
        // ...
        mediaSource =
                ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                        .createMediaSource(mediaItems[0])
      } else if (!playbackSession.isHLS) {
        // ...
        mediaSource =
                ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                        .createMediaSource(mediaItems[0])
      } else {
        // ...
        mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItems[0])
      }
```

No `LoadErrorHandlingPolicy` is set, so both factories use `DefaultLoadErrorHandlingPolicy`. No `MediaSource.Factory` is set on the player. Tracks after the first are added with `addMediaItems` and therefore use ExoPlayer's default factory, still with the 3-retry policy.

Direct play URL, server 2.22.0 and newer: `{server}/public/session/{sessionId}/track/{index}`. Older servers: the track `contentUrl` plus `?token=`. The progressive extractor issues HTTP range requests. A default Caddy `reverse_proxy` streams the body and forwards `Range`. Nothing in the app has to change for Caddy. If a drop still cannot fill the buffer after this change, check that Caddy is not buffering the whole response or stripping range requests. That is server config, not an app change.

The Android play request sends `forceDirectPlay: true` unless it is already retrying a direct-play failure. The server then direct-plays. HLS is the fallback, or the path for codecs the server refuses to copy (flac, opus, alac, wma, and a few others).

### What a network error does

`DefaultHttpDataSource` waits 8 seconds to connect and 8 seconds to read (`DEFAULT_CONNECT_TIMEOUT_MILLIS`, `DEFAULT_READ_TIMEOUT_MILLIS`).

For a progressive (direct-play) load, `ProgressiveMediaPeriod.onLoadError` in ExoPlayer 2.18.7 retries while `getRetryDelayMsFor` returns a delay. The default delay is `min((errorCount - 1) * 1000, 5000)` milliseconds, and network exceptions are retryable. The loader does **not** stop at 3 attempts by itself.

It does fail once playback needs data that is not buffered. `Loader.maybeThrowError` throws the last `IOException` when `errorCount > getMinimumLoadableRetryCount`. The default count is 3. The renderer calls that when the sample queue runs out. So:

- A drop shorter than the audio still in memory keeps playing. Background retries continue. If the network returns before the buffer hits zero, the next retry fills it and the listener never sees an error.
- A drop longer than the buffer (today, at most 45 seconds of media, less at 2x or 3x) makes the renderer throw. That is a fatal `PlaybackException`. The player goes idle. Buffered audio is discarded.

`PlayerListener.onPlayerError` forwards only the message:

```20:23:android/app/src/main/java/com/audiobookshelf/app/player/PlayerListener.kt
  override fun onPlayerError(error: PlaybackException) {
    val errorMessage = error.message ?: "Unknown Error"
    Log.e(tag, "onPlayerError $errorMessage")
    playerNotificationService.handlePlayerPlaybackError(errorMessage) // If was direct playing session, fallback to transcode
  }
```

`handlePlayerPlaybackError` treats every direct-play failure as "this file cannot be direct-played" and opens a new session with `forceTranscode: true`. If that request fails, or the session was already HLS, it emits `onPlaybackFailed`. The Vue player shows `Playback Failed: …` and calls `endPlayback`. `closePlayback` also clears the auto-advance queue. The connectivity callback in the service only reloads Android Auto libraries. It does not resume the player.

A 90-second airplane-mode drop during direct play therefore stops playback even though the loader was willing to retry. The 45-second cap drains, the error count is already above 3, and the app then tries to start a transcode session while the phone is still offline.

Progress sync can fail during the same drop and shows the existing sync-failing warning. It does not close playback. Leave it alone.

### HLS caveat (do not buffer minutes ahead)

Server `Stream.js` (current `advplyr/audiobookshelf` master) writes a full VOD playlist up front (`hls_playlist_type vod`, `hls_list_size 0`, 6-second MPEG-TS segments) while ffmpeg is still producing files. `HlsRouter` returns 404 when the segment file is not on disk yet. `Stream.checkSegmentNumberRequest` **resets the transcode** when the requested segment is more than 10 segments ahead of the furthest segment ffmpeg has written. Ten segments is 60 seconds.

The Android app does not handle the server's `stream_reset` socket event. A client that pulls 2–3 minutes of HLS will request segments that do not exist, the server kills ffmpeg and seeks, and playback breaks. Keep the HLS profile at 20s / 45s / 5s / 20s. Do not put the long retry policy on `HlsMediaSource`. A 404 storm against segments ffmpeg has not written is worse than today's behavior.

Copy (mp3/m4b) is usually faster than realtime, so today's 45-second HLS buffer often finds the files already there. AAC transcode on a slow NAS can already trip the 10-segment reset. Fixing that is a server change and is out of scope.

### Memory

The cap is the buffer duration, not the book. Approximate payload for the direct-play profile (180 seconds ahead + 30 seconds back):

| Bitrate | 180s ahead | + 30s back |
|---|---|---|
| 64 kbps | 1.4 MB | 1.7 MB |
| 128 kbps | 2.9 MB | 3.4 MB |
| 256 kbps | 5.8 MB | 6.7 MB |
| 320 kbps | 7.2 MB | 8.4 MB |
| ~900 kbps FLAC | 20 MB | 24 MB |
| 1,411 kbps PCM | 32 MB | 37 MB |

Set the byte ceiling to 48 MiB and prioritize the time thresholds below the minimum buffer, so a 128 kbps book still reaches 180 seconds and a PCM file cannot grow without bound. 48 MiB covers 210 seconds at about 1.5 Mbps. A higher bitrate stops at 48 MiB before 180 seconds. That is acceptable.

### Why not a disk cache

ExoPlayer 2.18 has `com.google.android.exoplayer2.upstream.cache.SimpleCache` (the same idea as Media3's `SimpleCache`). It is the wrong tool for a 30–90 second drop. A few megabytes of RAM already holds 3 minutes of speech. A disk cache adds a cache directory, an evictor, a `CacheDataSource` in front of both file and HTTP sources, and cache keys that change every playback session because the URL contains the session id. Use it only if the goal later becomes "keep tens of minutes" or "resume the same stream after the process is killed." Not this change.

---

## Recommended approach

Two profiles, chosen in `preparePlayer` before `setMediaSource` / `prepare`:

| | Direct play, direct stream, local | HLS transcode |
|---|---|---|
| Min buffer | 60s | 20s (unchanged) |
| Max buffer | 180s | 45s (unchanged) |
| Start playback | 5s | 5s |
| Resume after a stall | 15s | 20s |
| Back buffer | 30s, retained | 0 |
| Retry budget | 36 failed loads | ExoPlayer default (3) |

The minimum buffer is not the start delay. ExoPlayer starts after `bufferForPlayback` (5 seconds), then keeps loading until the max. Loading resumes when the buffered media falls under the minimum. At playback speed above 1x, ExoPlayer scales that minimum by the speed and caps it at the max. Copy that rule. At 2x the direct-play minimum becomes 120 seconds of media, still under the 180-second max.

The 30-second back buffer covers the in-app jump backward (default 10 seconds) and the pause rewind (up to about 30 seconds) without a new HTTP request during a drop. It is one parameter on the direct-play profile. Do not add a larger one.

Retry policy, progressive sources only:

- Retry connection failures, timeouts, and HTTP 502 / 503 / 504.
- Do not retry parser errors, file-not-found, cleartext-not-permitted, HTTP 404 / 410 / 416, or a position-out-of-range error.
- Delay matches today's curve: `min((errorCount - 1) * 1000, 5000)` ms.
- `getMinimumLoadableRetryCount` returns 36, and attempt 37 is fatal (`Loader.maybeThrowError` throws when `errorCount > minimum`). At a 5-second cap, 36 fast failures are about 3 minutes of waiting after the buffer is empty. Airplane mode fails the socket immediately, so the backoff dominates. A black-holed route can also sit in the 8-second connect timeout; that only makes the wait longer. Do not change the 8-second timeouts.
- Put the same policy on the `ProgressiveMediaSource.Factory` used for the first track and on the player's `MediaSource.Factory` (`DefaultMediaSourceFactory`) so tracks added with `addMediaItems` get it too. Do not change extractor flags, the user agent, or the HLS auth header.

`LoadControl` is fixed when the player is built, and `initializeMPlayer` runs before the play method is known. Build one load control that holds a mutable profile and one `DefaultAllocator`. Set the profile from `playbackSession.isHLS` at the start of the ExoPlayer branch in `preparePlayer`. Do not rebuild the player to swap controls. Do not instantiate two `DefaultLoadControl`s and delegate: each one owns an allocator, and `shouldContinueLoading` would look at the wrong byte count.

Copy `DefaultLoadControl.shouldContinueLoading` and `shouldStartPlayback` from ExoPlayer 2.18.7 into pure functions. Do not invent a new buffer algorithm. The 2.18.7 continue rule is:

- Scale `minBufferUs` by playback speed when speed is above 1, then cap at `maxBufferUs`.
- Floor that minimum at 500 ms.
- While buffered duration is under the minimum, keep loading if time is prioritized or the byte target is not reached.
- Once buffered duration is at least the max, or the byte target is reached while already above the minimum, stop loading.
- Between the minimum and the max, leave the previous loading flag as it was.

`shouldStartPlayback` uses the playout duration of the buffer (media duration divided by speed) against 5 seconds, or 15 seconds after a rebuffer. Do not wait for the 60-second minimum before the first sound.

Fatal-error guard, in the same pure-function file:

- `handlePlayerPlaybackError` currently force-transcodes on **any** direct-play error. A network failure that exhausts the 36 attempts would then open an HLS session while offline, which fails and closes playback anyway, and can reset a server stream if the network is only half up.
- Pass `PlaybackException.errorCode` through. Fall back to transcode only for container parse failures: `ERROR_CODE_PARSING_CONTAINER_MALFORMED` (3001) and `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` (3003).
- Every other code keeps today's terminal path: `onPlaybackFailed` and `closePlayback(true)`. Do not add a new error screen.

### Files

- New `android/app/src/main/java/com/audiobookshelf/app/player/StreamingBuffer.kt` — profiles, retry decision, transcode-fallback decision, the two load-control decisions. No ExoPlayer imports. No Android imports.
- New `android/app/src/main/java/com/audiobookshelf/app/player/StreamingLoadControl.kt` — `LoadControl` wrapper. Holds the allocator, the current profile, and the previous loading flag. Every branch calls `StreamingBuffer`.
- New `android/app/src/test/java/com/audiobookshelf/app/player/StreamingBufferTest.kt`.
- Edit `PlayerNotificationService.kt` — construct `StreamingLoadControl`, select the profile, attach the retry policy, pass the error code into `handlePlayerPlaybackError`.
- Edit `PlayerListener.kt` — pass `error.errorCode`.

That is three new files and two edited files. No Vue, Swift, or server file.

### Complexity

**Small.** The diff is the player-service buffer and the retry decision. It stays small only if the implementation does not add a setting, a disk cache, or a Media3 migration. Those would be a larger change (settings UI plus saved JSON, or a cache layer, or a library migration).

Risks:

- A custom `shouldContinueLoading` that drifts from ExoPlayer 2.18.7 can stall at the start or never fill the buffer. The unit tests lock the copied formula, including the speed scale and the 500 ms floor.
- Putting the 180-second profile on HLS can reset the server transcode. The HLS numbers are a test, not a comment.
- Raising the retry count without excluding 404 makes a genuinely missing direct-play URL spin for about 3 minutes. The retry function treats 404 / 410 / 416 as fatal.
- `setMediaSourceFactory` must not replace the explicit first-track source. Item 0 stays a `ProgressiveMediaSource` or `HlsMediaSource` built as it is today. The player factory applies to `addMediaItems` only.
- After 36 failures the session still closes and the auto-advance queue is cleared. That is existing `closePlayback` behavior. Do not "fix" it here.

---

## Tasks

### Task 1: Buffer profiles and load-control decisions

**Files:** Create `StreamingBuffer.kt`, `StreamingBufferTest.kt`.

**Interfaces:**

- `enum class BufferProfile { Direct, Hls }`
- `data class BufferDurations(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs, backBufferMs, targetBufferBytes, prioritizeTimeOverSize)`
- `fun durations(profile: BufferProfile): BufferDurations`
- `fun shouldContinueLoading(bufferedDurationUs, playbackSpeed, allocatedBytes, targetBufferBytes, minBufferUs, maxBufferUs, prioritizeTime, wasLoading): Boolean`
- `fun shouldStartPlayback(bufferedDurationUs, playbackSpeed, rebuffering, bufferForPlaybackUs, bufferForPlaybackAfterRebufferUs): Boolean`

- [ ] Write failing tests:
  - Direct durations are 60_000, 180_000, 5_000, 15_000, back buffer 30_000, target bytes `48 * 1024 * 1024`, prioritize time true.
  - HLS durations are 20_000, 45_000, 5_000, 20_000, back buffer 0, and do not use the 48 MiB ceiling (keep ExoPlayer's unset / default by using the existing 13,107,200 byte audio cap so HLS behavior stays put).
  - At speed 1.0, buffered 10 seconds, target not reached: continue loading.
  - At speed 1.0, buffered 180 seconds: stop, even if the previous flag was loading.
  - At speed 2.0, the minimum scales from 60 seconds to 120 seconds of media. Buffered 90 seconds, previous flag false: keep not loading. Buffered 30 seconds: load.
  - At speed 1.0, buffered 90 seconds (between min and max): return `wasLoading` unchanged.
  - Below the minimum, byte target already reached, prioritize time true: still load. Same inputs with prioritize time false: do not load.
  - Start playback at 5 seconds buffered, speed 1, not rebuffering. Do not start at 4 seconds. After rebuffer, 15 seconds is enough and 14 seconds is not. At speed 2, 5 seconds of media is only 2.5 seconds of playout, so playback does not start yet.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests com.audiobookshelf.app.player.StreamingBufferTest` and confirm the new tests fail because the production functions do not exist.
- [ ] Implement the functions. Copy the 2.18.7 inequalities. `Util.getMediaDurationForPlayoutDuration(mediaUs, speed)` is `mediaUs * speed`. Playout duration is `mediaUs / speed`.
- [ ] Re-run the same test. It passes.

### Task 2: Retry budget and transcode fallback

**Files:** `StreamingBuffer.kt`, `StreamingBufferTest.kt`.

**Interfaces:**

- `const val STREAMING_RETRY_COUNT = 36`
- `enum class LoadFailure { Network, Unavailable, NotRetryable }` — `Unavailable` is HTTP 502 / 503 / 504. `NotRetryable` is parser, file-not-found, cleartext, 404, 410, 416, position out of range.
- `fun retryDelayMs(errorCount: Int, failure: LoadFailure): Long?` — null means fatal (`C.TIME_UNSET` at the ExoPlayer boundary).
- `fun shouldFallbackToTranscode(errorCode: Int): Boolean`

- [ ] Write failing tests:
  - Network failure, error count 1, delay 0. Count 2, delay 1_000. Count 6, delay 5_000. Count 36, delay 5_000. Count 37, null.
  - `Unavailable` uses the same delays. `NotRetryable` is null at count 1.
  - `shouldFallbackToTranscode` is true only for 3001 and 3003. It is false for 2001 (connection failed), 2002 (timeout), 2004 (bad HTTP status), 2005 (file not found), and 3002 / 3004.
- [ ] Run the test task and confirm these tests fail.
- [ ] Implement the functions.
- [ ] Re-run. They pass.

### Task 3: Wire the player

**Files:** `StreamingLoadControl.kt`, `PlayerNotificationService.kt`, `PlayerListener.kt`.

The wrapper is not a place for new policy. `shouldContinueLoading` reads the active profile's durations and calls `StreamingBuffer.shouldContinueLoading`. `getMinimumLoadableRetryCount` returns `STREAMING_RETRY_COUNT`. `getRetryDelayMsFor` maps the ExoPlayer exception to `LoadFailure` and returns `C.TIME_UNSET` when `retryDelayMs` is null.

- [ ] Add `StreamingLoadControl` and a `LoadErrorHandlingPolicy` in the player package that delegates as above. No new decision that is not already tested.
- [ ] In `initializeMPlayer`, pass the load control to `ExoPlayer.Builder.setLoadControl`. Set `setMediaSourceFactory` to a `DefaultMediaSourceFactory` that uses the progressive retry policy. Remove the inline `DefaultLoadControl.Builder` block.
- [ ] In `preparePlayer`, set the profile from `playbackSession.isHLS` before building sources. Call `setLoadErrorHandlingPolicy` on the progressive factories (local and direct). Leave the `HlsMediaSource.Factory` on the default policy.
- [ ] Change `onPlayerError` and `handlePlayerPlaybackError` to take the error code. Call `shouldFallbackToTranscode`. Only the true branch requests a transcode session. The false branch stays `onPlaybackFailed` + `closePlayback(true)`.
- [ ] Run `:app:testDebugUnitTest`. The existing `PlaybackQueueTest` and `StreamingBufferTest` pass.

### Task 4: Manual drop test

This cannot be a unit test. Do it on a device or emulator against the self-hosted server, direct-playing a normal mp3 or m4b (the session log line is `preparePlayer: Direct playing item`, not `Playing HLS stream`).

- [ ] Play for at least 30 seconds so the buffer can fill. Turn airplane mode on for 30 seconds, then off. Audio does not stop. Logcat does not show `onPlayerError` or `Fallback to transcode`.
- [ ] Repeat for 90 seconds. At 1x, audio still does not stop. At 2x, 90 seconds of clock time is exactly the 180-second media buffer, so a stall at the end is acceptable. It must resume without the failure toast when the network returns, as long as the retry budget is not exhausted.
- [ ] With airplane mode still on after the buffer has drained, wait. The player sits buffering. When airplane mode goes off within the retry window, playback resumes. It does not open an HLS session (`Fallback to transcode` is absent).
- [ ] Leave airplane mode on until the toast `Playback Failed` appears. Playback ends. That is the budget-exhausted path.
- [ ] Play a file that transcodes (or force the existing fallback once) and confirm logcat says `Playing HLS stream` and that a short airplane toggle does not behave worse than today's build. Do not expect 3 minutes of HLS buffer.
- [ ] Play a downloaded / local item and confirm it still starts and seeks.

---

## Windows build and test

From `D:\GitHub\audiobookshelf-app`, JDK 21 on `JAVA_HOME`. `android/app/build.gradle` sets `jvmToolchain(17)`. Leave that line alone. Gradle may still compile Kotlin for 17 while the Gradle JVM is 21.

This change does not touch the web assets. `npm run sync` is still part of the check so the debug APK is built from a synced project. Do not bump the version before installing.

```powershell
cd D:\GitHub\audiobookshelf-app
java -version
npm run sync
cd android
.\gradlew.bat :app:testDebugUnitTest --tests com.audiobookshelf.app.player.StreamingBufferTest
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Then run the airplane-mode steps in Task 4. `assembleDebug` writes `app\build\outputs\apk\debug\app-debug.apk`.

## Review

After the tasks above are green, run `/review-bugbot` on the implementation diff. If that command is not available, review the diff manually against this plan (buffer numbers, HLS left at 45 seconds, retry exclusions, no version bump, no disk cache, no settings UI) and write that in the PR.
