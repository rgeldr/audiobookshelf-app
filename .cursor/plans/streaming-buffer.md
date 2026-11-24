# Stream five minutes ahead on Android

Status: implemented

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While an Android phone is streaming a book from a self-hosted Audiobookshelf server, keep 5 minutes of audio buffered so a connectivity loss does not stop playback. Jason listens at 1.25x, so that cushion is about 4 minutes of clock time. If the buffer does empty, keep waiting about 15 minutes for the connection before the existing failure toast.

**Architecture:** Direct file playback already uses ExoPlayer's progressive source and HTTP range requests. The player is capped at 45 seconds of buffer, and once that buffer drains ExoPlayer treats the load as failed. Raise the direct-play buffer to 5 minutes of media (minimum and maximum both 300 seconds) and keep retrying network errors long enough that a drained buffer waits about 15 minutes instead of ending the session. Leave HLS (server transcode) on today's buffer. The server resets ffmpeg if the client requests a segment more than 10 segments ahead of segments already written.

**Tech Stack:** Kotlin, ExoPlayer 2.18.7 (not Media3), JUnit 4 on `android/app/src/test`. No Nuxt change, no server change.

## Global Constraints

- Implemented on `cursor/streaming-buffer-plan-16c6`. Task 4 (phone drop) is still for Jason. Do not bump the version.
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

Jason answered the open questions. These are the product choices this plan locks in. Do not invent different ones during implementation.

1. The buffer is a fixed 5 minutes of **media** ahead (300 seconds). There is no settings screen and no new `DeviceSettings` field.
2. Jason's library is a mix of mp3 and m4b, played as direct file streams through Caddy. That path is the one this change lengthens. HLS transcode keeps today's 45-second cap. See the HLS section for why a longer HLS buffer is unsafe without a server change.
3. He listens at 1.25x. 300 seconds of audio is 300 / 1.25 = 240 seconds of clock time, about 4 minutes. The player still stores 300 seconds of media at that speed, because ExoPlayer caps the speed-scaled minimum at the maximum.
4. After the buffer is empty, wait about 15 minutes of failed loads before the existing failure toast. The ExoPlayer error count also increments while the buffer is still playing, so the retry budget is sized for a full drain plus those 15 minutes. See the retry section.
5. HTTP 404 / 410 / 416, parser errors, file-not-found, cleartext, and position-out-of-range stay non-retryable. Transcode fallback stays limited to an unreadable container (`ERROR_CODE_PARSING_CONTAINER_MALFORMED` 3001 and `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` 3003).
6. Local files use the same longer buffer. Reading ahead from disk is cheap, and one profile for "not HLS" avoids a third path.
7. An outage longer than the retry window still ends playback the way a fatal player error ends it today: the existing toast and `closePlayback`. This plan does not keep a dead session open, and it does not resume by itself after that toast.
8. Cast playback is unchanged. The Cast player does not use this LoadControl.

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

This snippet is the buffer the change replaces.

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

The cap is the buffer duration, not the book. Jason's files are mp3 and m4b at typical spoken-word bitrates (about 64–128 kbps, occasionally up to a high stereo encode around 320 kbps). Payload for 300 seconds ahead plus the 30-second back buffer:

| Bitrate | 300s ahead | + 30s back |
|---|---|---|
| 64 kbps | 2.3 MiB | 2.5 MiB |
| 128 kbps | 4.6 MiB | 5.0 MiB |
| 192 kbps | 6.9 MiB | 7.6 MiB |
| 256 kbps | 9.2 MiB | 10.1 MiB |
| 320 kbps | 11.4 MiB | 12.6 MiB |

ExoPlayer's default audio ceiling is 12.5 MiB (13,107,200 bytes). That fits 300 seconds of a 128 kbps file and even 300 seconds of a 320 kbps file, but 320 kbps plus the 30-second back buffer is 13.2 MB and would stop a few seconds short of 5 minutes. Set the direct-play ceiling to **16 MiB** (`16 * 1024 * 1024` = 16,777,216 bytes). Every row in the table fits, with about 3.5 MiB of slack for 64 KiB allocator segments. A 128 kbps book still stops on the 300-second time cap, around 5 MiB, not at 16 MiB.

Do not prioritize time over the byte ceiling. With minimum equal to maximum, the "below the minimum" branch is the entire load, and prioritizing time would ignore the 16 MiB cap until 300 seconds were buffered. Leaving the priority off makes 16 MiB a real ceiling for an unexpectedly high bitrate, while normal audiobook bitrates still reach 300 seconds. Do not size the ceiling for FLAC or PCM. Those files are not this library, and a 48 MiB cap is unused memory headroom.

### Why not a disk cache

ExoPlayer 2.18 has `com.google.android.exoplayer2.upstream.cache.SimpleCache` (the same idea as Media3's `SimpleCache`). It is the wrong tool here. A few megabytes of RAM already holds 5 minutes of an mp3 or m4b. A disk cache adds a cache directory, an evictor, a `CacheDataSource` in front of both file and HTTP sources, and cache keys that change every playback session because the URL contains the session id. Use it only if the goal later becomes "keep tens of minutes" or "resume the same stream after the process is killed." Not this change.

---

## Recommended approach

Two profiles, chosen in `preparePlayer` before `setMediaSource` / `prepare`:

| | Direct play, direct stream, local | HLS transcode |
|---|---|---|
| Min buffer | 300s | 20s (unchanged) |
| Max buffer | 300s | 45s (unchanged) |
| Start playback | 5s | 5s |
| Resume after a stall | 15s | 20s |
| Back buffer | 30s, retained | 0 |
| Byte ceiling | 16 MiB, time not prioritized | 12.5 MiB audio default |
| Retry budget | 45 failed loads, backoff capped at 30s | ExoPlayer default (3) |

Minimum and maximum are both 300 seconds on purpose. ExoPlayer starts playback after `bufferForPlayback` (5 seconds), then loads until the max and does not resume loading until the buffered media falls **under the minimum**. A low minimum, such as 60 seconds under a 300-second max, would let a full buffer drain to 60 seconds before the next fill. The cushion present when the signal drops would be the minimum, not the 5 minutes Jason asked for. ExoPlayer's own defaults use the same number for both. Copy that shape. The 5-second start and the 15-second resume-after-stall stay short so the book still starts quickly and a rebuffer does not wait out the 5 minutes.

At playback speed above 1x, ExoPlayer scales the minimum by the speed and caps it at the maximum. At 1.25x, 300 × 1.25 = 375 seconds, which the cap pulls back to 300 seconds of media. That is 240 seconds of clock time, about 4 minutes. Copy that rule. Do not shrink the stored buffer to 240 seconds.

The back buffer stays 30 seconds. It is the audio **behind** the playhead, used for the in-app jump backward (default 10 seconds) and the pause rewind (up to about 30 seconds) without a new HTTP request during a drop. Making it longer does not add look-ahead, and the 16 MiB ceiling is what makes the 300-second look-ahead reachable at up to 320 kbps with that 30 seconds included. Do not grow it.

Retry policy, progressive sources only:

- Retry connection failures, timeouts, and HTTP 502 / 503 / 504.
- Do not retry parser errors, file-not-found, cleartext-not-permitted, HTTP 404 / 410 / 416, or a position-out-of-range error.
- Backoff, for a retryable failure: attempt 1 waits 0 ms (a blip retries immediately), then 1s, 2s, 4s, 8s, 16s, then 30s for every later attempt. In code: for `errorCount == 1` return 0; otherwise `min(1000L shl (errorCount - 2).coerceAtMost(5), 30_000L)`.
- `getMinimumLoadableRetryCount` returns 45. `Loader.maybeThrowError` throws when `errorCount > 45`, so the 46th failure is fatal and its delay is null.
- The sum of the delays for attempts 1 through 45 is 1,201 seconds, about 20.0 minutes. That is longer than 15 minutes because the error count runs during the whole outage, including while buffered audio is still playing. A full 300-second buffer takes 300 seconds to drain at 1x and 240 seconds at 1.25x. After that drain, the remaining delays are 15.0 minutes at 1x and 16.0 minutes at 1.25x. Do not set the count to `15 min / 30s` and ignore the drain. Airplane mode fails the socket immediately, so the backoff is the wait. A black-holed route can also sit in the 8-second connect timeout; that only makes the wait longer. Do not change the 8-second timeouts.
- Once the network returns, the next retry is at most 30 seconds away.
- Put the same policy on the `ProgressiveMediaSource.Factory` used for the first track and on the player's `MediaSource.Factory` (`DefaultMediaSourceFactory`) so tracks added with `addMediaItems` get it too. Do not change extractor flags, the user agent, or the HLS auth header.

`LoadControl` is fixed when the player is built, and `initializeMPlayer` runs before the play method is known. Build one load control that holds a mutable profile and one `DefaultAllocator`. Set the profile from `playbackSession.isHLS` at the start of the ExoPlayer branch in `preparePlayer`. Do not rebuild the player to swap controls. Do not instantiate two `DefaultLoadControl`s and delegate: each one owns an allocator, and `shouldContinueLoading` would look at the wrong byte count.

Copy `DefaultLoadControl.shouldContinueLoading` and `shouldStartPlayback` from ExoPlayer 2.18.7 into pure functions. Do not invent a new buffer algorithm. The 2.18.7 continue rule is:

- Scale `minBufferUs` by playback speed when speed is above 1, then cap at `maxBufferUs`.
- Floor that minimum at 500 ms.
- While buffered duration is under the minimum, keep loading if time is prioritized or the byte target is not reached.
- Once buffered duration is at least the max, or the byte target is reached while already above the minimum, stop loading.
- Between the minimum and the max, leave the previous loading flag as it was.

`shouldStartPlayback` uses the playout duration of the buffer (media duration divided by speed) against 5 seconds, or 15 seconds after a rebuffer. At 1.25x that is 6.25 seconds of media to start, and 18.75 seconds of media to resume after a stall. Do not wait for the 300-second minimum before the first sound.

Fatal-error guard, in the same pure-function file:

- `handlePlayerPlaybackError` currently force-transcodes on **any** direct-play error. A network failure that exhausts the 45 attempts would then open an HLS session while offline, which fails and closes playback anyway, and can reset a server stream if the network is only half up.
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
- Putting the 300-second profile on HLS can reset the server transcode. The HLS numbers are a test, not a comment.
- Raising the retry count without excluding 404 makes a genuinely missing direct-play URL spin for the whole backoff, about 20 minutes. The retry function treats 404 / 410 / 416 as fatal on the first failure.
- `setMediaSourceFactory` must not replace the explicit first-track source. Item 0 stays a `ProgressiveMediaSource` or `HlsMediaSource` built as it is today. The player factory applies to `addMediaItems` only.
- After 45 failures the session still closes and the auto-advance queue is cleared. That is existing `closePlayback` behavior. Do not "fix" it here.

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

- [x] Write failing tests:
  - Direct durations are 300_000, 300_000, 5_000, 15_000, back buffer 30_000, target bytes `16 * 1024 * 1024`, prioritize time false.
  - HLS durations are 20_000, 45_000, 5_000, 20_000, back buffer 0, target bytes 13_107_200, prioritize time false. HLS does not use the 16 MiB ceiling.
  - Using the direct profile at speed 1.0, buffered 10 seconds, byte target not reached: continue loading. Buffered 300 seconds: stop, even if the previous flag was loading. Buffered 299 seconds: still load.
  - Using the direct profile at speed 1.25, the scaled minimum stays 300 seconds (375 seconds capped at the max). Buffered 299 seconds: load. Buffered 300 seconds: stop.
  - Formula coverage with an explicit 60-second minimum and 180-second maximum, so the copied ExoPlayer rule stays tested even though the direct profile has no gap between min and max. At speed 2.0 the minimum scales to 120 seconds, so 90 seconds of buffer is still under the minimum and must load (30 seconds loads too). 150 seconds is between the scaled minimum and the maximum, so it returns `wasLoading`. At speed 1.0, buffered 90 seconds is between 60 and 180 and returns `wasLoading` unchanged. An earlier draft of this bullet said 90 seconds at speed 2.0 stays not loading. That disagrees with `DefaultLoadControl` 2.18.7 (`bufferedDurationUs < minBufferUs` loads). The tests and the code follow ExoPlayer.
  - Below the minimum, byte target already reached, prioritize time true: still load. Same inputs with prioritize time false: do not load. Direct profile (prioritize time false) at 60 seconds buffered with 16 MiB already allocated: do not load.
  - Start playback at 5 seconds buffered, speed 1, not rebuffering. Do not start at 4 seconds. After rebuffer, 15 seconds is enough and 14 seconds is not. At speed 1.25, 6.25 seconds of media starts playback and 5 seconds does not. After a rebuffer at 1.25, 18.75 seconds of media is enough and 15 seconds is not.
- [x] Run `./gradlew :app:testDebugUnitTest --tests com.audiobookshelf.app.player.StreamingBufferTest` and confirm the new tests fail because the production functions do not exist.
- [x] Implement the functions. Copy the 2.18.7 inequalities. `Util.getMediaDurationForPlayoutDuration(mediaUs, speed)` is `mediaUs * speed`. Playout duration is `mediaUs / speed`.
- [x] Re-run the same test. It passes.

### Task 2: Retry budget and transcode fallback

**Files:** `StreamingBuffer.kt`, `StreamingBufferTest.kt`.

**Interfaces:**

- `const val STREAMING_RETRY_COUNT = 45`
- `enum class LoadFailure { Network, Unavailable, NotRetryable }` — `Unavailable` is HTTP 502 / 503 / 504. `NotRetryable` is parser, file-not-found, cleartext, 404, 410, 416, position out of range.
- `fun retryDelayMs(errorCount: Int, failure: LoadFailure): Long?` — null means fatal (`C.TIME_UNSET` at the ExoPlayer boundary).
- `fun shouldFallbackToTranscode(errorCode: Int): Boolean`

- [x] Write failing tests:
  - Network failure delays: count 1 is 0, count 2 is 1_000, count 3 is 2_000, count 4 is 4_000, count 5 is 8_000, count 6 is 16_000, count 7 is 30_000, count 45 is 30_000, count 46 is null.
  - The sum of the non-null delays for counts 1 through 45 is 1_201_000 ms.
  - `Unavailable` uses the same delays. `NotRetryable` is null at count 1.
  - `shouldFallbackToTranscode` is true only for 3001 and 3003. It is false for 2001 (connection failed), 2002 (timeout), 2004 (bad HTTP status), 2005 (file not found), and 3002 / 3004.
- [x] Run the test task and confirm these tests fail.
- [x] Implement the functions.
- [x] Re-run. They pass.

### Task 3: Wire the player

**Files:** `StreamingLoadControl.kt`, `PlayerNotificationService.kt`, `PlayerListener.kt`.

The wrapper is not a place for new policy. `shouldContinueLoading` reads the active profile's durations and calls `StreamingBuffer.shouldContinueLoading`. `getMinimumLoadableRetryCount` returns `STREAMING_RETRY_COUNT`. `getRetryDelayMsFor` maps the ExoPlayer exception to `LoadFailure` and returns `C.TIME_UNSET` when `retryDelayMs` is null.

- [x] Add `StreamingLoadControl` and a `LoadErrorHandlingPolicy` in the player package that delegates as above. No new decision that is not already tested.
- [x] In `initializeMPlayer`, pass the load control to `ExoPlayer.Builder.setLoadControl`. Set `setMediaSourceFactory` to a `DefaultMediaSourceFactory` that uses the progressive retry policy. Remove the inline `DefaultLoadControl.Builder` block.
- [x] In `preparePlayer`, set the profile from `playbackSession.isHLS` before building sources. Call `setLoadErrorHandlingPolicy` on the progressive factories (local and direct). Leave the `HlsMediaSource.Factory` on the default policy.
- [x] Change `onPlayerError` and `handlePlayerPlaybackError` to take the error code. Call `shouldFallbackToTranscode`. Only the true branch requests a transcode session. The false branch stays `onPlaybackFailed` + `closePlayback(true)`.
- [x] Run `:app:testDebugUnitTest`. The existing `PlaybackQueueTest` and `StreamingBufferTest` pass.

### Task 4: Manual drop test

This cannot be a unit test. Do it on a device or emulator against the self-hosted server, direct-playing one of Jason's mp3 or m4b files (the session log line is `preparePlayer: Direct playing item`, not `Playing HLS stream`). Set playback speed to 1.25x. The full 15-minute budget is locked by the delay-sum unit test. This pass checks that audio survives a real drop and that an empty buffer waits instead of toasting immediately.

- [ ] Let playback run about 15 seconds so it has started and the buffer has had time to fill (5 minutes of a 128 kbps file is about 5 MiB). Turn airplane mode on for 90 seconds, then off. Audio does not stop. Logcat does not show `onPlayerError` or `Fallback to transcode`.
- [ ] Repeat with airplane mode on for about 3 minutes. At 1.25x that consumes 225 seconds of media, under the 300-second cushion. Audio does not stop.
- [ ] Leave airplane mode on until the audio stops (the buffer has drained). Wait another 2 minutes while it is still offline. The player sits buffering. The `Playback Failed` toast does not appear. Turn airplane mode off. Playback resumes within about 30 seconds (the backoff cap) and does not open an HLS session (`Fallback to transcode` is absent).
- [ ] Play a file that transcodes (or force the existing fallback once) and confirm logcat says `Playing HLS stream` and that a short airplane toggle does not behave worse than today's build. Do not expect 5 minutes of HLS buffer.
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

After the tasks above are green, run `/review-bugbot` on the implementation diff. If that command is not available, review the diff manually against this plan (direct-play min and max both 300 seconds, 16 MiB ceiling, retry count 45 with the 30-second cap, 404 and parser errors not retried, transcode fallback only for 3001 and 3003, HLS left at 45 seconds, no version bump, no disk cache, no settings UI) and write that in the PR.
