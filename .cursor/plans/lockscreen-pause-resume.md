# Lock-screen pause and headset resume Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After a pause with the screen locked, a headset or lock-screen play press resumes the same book or episode at its saved position, including when Android has killed the player process.

**Architecture:** Pause keeps the ExoPlayer 2.18.7 media session active and demotes the service out of the foreground without removing the notification. A small bookmark (item, position, queue) is written on pause and on progress sync, and deleted only when the user, the sleep timer, or a swiped notification actually stops playback. A play press on an empty player starts the service in the foreground immediately and reloads that bookmark. A local item plays from disk. A remote item opens a new server playback session, because the old one may have expired. No Media3 migration.

**Tech Stack:** Kotlin, ExoPlayer 2.18.7, `MediaSessionCompat`, `PlayerNotificationManager`, JUnit 4 (`android/app/src/test`). Android only. `minSdk` 24, `targetSdk` 36.

## Global Constraints

- Plan only until Jason says to implement. This document does not change player code.
- Fork only: `rgeldr/audiobookshelf-app`, base `master`. Do not open a PR against `advplyr/audiobookshelf-app` and do not push to upstream.
- No version bump. Leave `versionName` `0.14.2-beta` and `versionCode` `121` in `android/app/build.gradle`.
- Stay on ExoPlayer 2.18.7. Do not migrate to Media3.
- Do not edit the `DefaultLoadControl` durations in `PlayerNotificationService.initializeMPlayer` (lines 391–398) or `handlePlayerPlaybackError` (lines 619–643). Those belong to PR #5.
- Do not change iOS, the Capacitor web player, or the Audiobookshelf server.
- Karpathy guidelines (https://github.com/multica-ai/andrej-karpathy-skills/blob/main/skills/karpathy-guidelines/SKILL.md): state assumptions, smallest change that fixes the bug, no drive-by refactor, every changed decision covered by a failing test first.
- After implementation, run `/review-bugbot`. If that command is not available in the session, do a manual review of the diff and say so in the PR.
- The auto-advance plan (`.cursor/plans/auto-advance-playlists.md`, assumption 8) said the queue dies with the process. This request overrides that for pause/resume only: the queue is copied into the bookmark. Do not otherwise change auto-advance.

## Assumptions

These are locked for implementation. Open questions at the bottom are the ones not locked.

1. A user pause (in-app, headset pause, headset stop, lock-screen pause) keeps the notification and the media session, and writes the bookmark.
2. The sleep timer firing, swiping the notification away, and the in-app Close action call today's `closePlayback`: notification removed, server session closed, bookmark deleted, queue cleared. A later headset play does not resume.
3. `AudioPlayer.vue` `beforeDestroy` must not call `closePlayback`. The native service owns the session when the WebView is torn down.
4. Headset `KEYCODE_MEDIA_STOP` stays a pause, matching `MediaSessionCallback` today. It does not clear the bookmark.
5. A remote item with no network cannot resume after the process is dead. Keep the bookmark and do not guess a downloaded copy. A local item plays from disk with no network.
6. On a warm resume (player still has media), try `play()` first. If the item is remote, the network is up, and `GET /api/session/:id` returns nothing, open a new session with the existing `startNewPlaybackSession` / `playLibraryItem` path. Do not uncomment the 30-second block in `PlayerListener`.
7. When the server progress `updatedAt` is newer than the bookmark, use the server position. Otherwise use the bookmark. Rewinding on another device must win.
8. Do not add a battery-optimization prompt, a settings toggle, or a Media3 `MediaSessionService`.

---

## Root cause

There is no app timer that closes playback after a pause. `pause()` only pauses ExoPlayer. The controls disappear, and a later headset play does nothing, because of the three facts below.

### 1. Pause never leaves the foreground, and the paused card is not a resumption card

`PlayerNotificationService.pause` (lines 1057–1058) calls `currentPlayer.pause()` and returns. Nothing schedules a stop.

ExoPlayer 2.18.7 `PlayerNotificationManager.getOngoing` (r2.18.7, `PlayerNotificationManager.java` lines 1395–1399) is true only when the player is `STATE_BUFFERING` or `STATE_READY` **and** `playWhenReady` is true. On pause the manager republishes the same notification with `setOngoing(false)`. It does **not** cancel it. Cancellation in this version happens only from `setPlayer(null)` or the dismiss intent (`PlayerNotificationManager.java` class doc, lines 81–82, and `stopNotification` at lines 1183–1192).

`PlayerNotificationListener.onNotificationPosted` (lines 21–34) calls `startForeground` only when `ongoing` is true. The pause update takes the `else` branch and never calls `stopForeground`. The service stays a `mediaPlayback` foreground service for the whole pause, with `isForegroundService` left true.

The session is set active once in `onCreate` (lines 275–278) and released only in `onDestroy` (line 209). `MediaSessionConnector` does not call `setActive` (r2.18.7 class doc, lines 69–72). While the process lives, the session stays active and the connector reports `STATE_PAUSED` (`MediaSessionConnector.getMediaSessionPlaybackState`, `STATE_READY` + `playWhenReady == false`).

Pixel SystemUI then expires that card. `PAUSED_MEDIA_TIMEOUT` defaults to 10 minutes (`debug.sysui.media_timeout`). `RESUME_MEDIA_TIMEOUT` is 2 days, and it applies only to a **resumption** card (no live session). If the phone is dozing, the timeout is applied as soon as the screen wakes and the deadline has passed (`MediaTimeoutListener`, Android 15 SystemUI). This app never publishes a resumption card. Plexamp, Pocket Casts, and Smart AudioBook Player do, which is why their play control is still there after hours or days. Holding this process in the foreground does not beat the 10-minute SystemUI timeout, and it blocks the 2-day card, because that card is for a session that is gone.

### 2. Once the process is gone, play starts an empty player

These paths destroy the service:

- `closePlayback` (lines 1125–1171) stops the player, clears the queue, closes the server session, `stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf()`.
- `AudioPlayer.vue` `beforeDestroy` (lines 1013–1015) calls that whenever the WebView component is destroyed while a session exists. A locked screen does not by itself destroy the activity. A later activity teardown (recents swipe is `onTaskRemoved` at lines 217–221, which also `stopSelf()`; WebView destroy runs the Vue hook) does.
- Notification dismiss sends `ACTION_DISMISS`. The manager treats it as `dismissedByUser = true` (`PlayerNotificationManager.java` line 1586). `PlayerNotificationListener` (lines 41–43) then `stopSelf()` without `closePlayback`.
- `onDestroy` (lines 206–209) calls `playerNotificationManager.setPlayer(null)` and `mediaSession.release()`.

The manifest registers `androidx.media.session.MediaButtonReceiver` for `ACTION_MEDIA_BUTTON` (`AndroidManifest.xml` lines 87–91). `PlayerNotificationService` (lines 93–101) only filters `android.media.browse.MediaBrowserService`. It does not filter `ACTION_MEDIA_BUTTON`. `androidx.media:media:1.7.0` therefore does not `startForegroundService` with the key event. It binds the browser service, which creates a new empty ExoPlayer in `onCreate`.

`MediaSessionCallback.onPlay` (lines 36–38) only calls `play()`. `play()` (lines 1048–1054) calls `currentPlayer.play()` on that empty player. The block that would reload `DeviceManager.deviceData.lastPlaybackSession` is commented out (`MediaSessionCallback.kt` lines 169–182), with a note that the service is destroyed before it can run.

Android 12+ will not let a background app start a `mediaPlayback` foreground service except from an exemption such as a media-button `startForegroundService` that then calls `startForeground` within a few seconds. This bind path never does that. A dead process cannot legally come back as a playing foreground service from a headset press.

`onStartCommand` (lines 169–173) returns `START_STICKY` and ignores the intent.

### 3. The position and the queue are not in a bookmark that survives a successful sync

`DeviceManager.setLastPlaybackSession` (lines 178–180) is called from `preparePlayer` (lines 473–475) only. That copy is the session at start. Later `currentTime` updates are not written back there.

On pause, `PlayerListener` (lines 108–111) calls `MediaProgressSyncer.pause`, which syncs. `sync` writes a `playbackSession` Paper row (line 253) and, on a successful remote sync, **deletes** it (`removePlaybackSession`, line 308). Local progress is stored separately. The auto-advance queue is the in-memory `PlaybackQueue` (service line 128), cleared in `closePlayback` (line 1128), and not saved.

`checkCurrentSessionProgress` (lines 941–1045) already reopens a missing server session via `startNewPlaybackSession` (lines 769–791). The call site is commented out (`PlayerListener.kt` lines 10 and 81–86, the old 30-second `PAUSE_LEN_BEFORE_RECHECK`). A long-paused stream whose server session expired is not repaired on play.

The sleep timer does not stop the service. At zero it calls `pause()` (`SleepTimerManager.kt` lines 168–170). Under a "keep the notification while paused" change, the sleep timer would leave the lock-screen control up. Assumption 2 prevents that.

## What this plan does not change

- ExoPlayer buffer durations and the direct-play retry policy (PR #5).
- Playlist download (PR #4, JavaScript only). No shared files.
- The double-tap headset seek in `MediaSessionCallback.handleMediaButtonClickCount`.
- Auto-rewind in `PlayerListener.calcPauseSeekBackTime`.
- Replacing `MediaSessionConnector`'s callback. `onCreate` line 384 overwrites the connector callback with `MediaSessionCallback`. Leave that. Play and pause already arrive there while the process lives.
- iOS.

## PR overlap

| PR | Branch | Overlap |
| --- | --- | --- |
| #4 | `cursor/playlist-download-button-6886` | None. Vue playlist download only. |
| #5 | `cursor/streaming-buffer-plan-16c6` | Plan only, not merged. It will edit `initializeMPlayer`'s `LoadControl` and add a retry policy used from `handlePlayerPlaybackError`. This work edits other parts of the same `PlayerNotificationService.kt`. Keep PR #5's hunks if both land. Do not touch lines 391–419 or 619–643. A warm resume that hits a dead stream still uses that existing error path. |

---

## File structure

| File | Responsibility |
| --- | --- |
| Create `android/app/src/main/java/com/audiobookshelf/app/player/PauseResume.kt` | Pure decisions: button to action, foreground change, resume source, position pick. |
| Create `android/app/src/test/java/com/audiobookshelf/app/player/PauseResumeTest.kt` | Unit tests for those decisions. |
| Modify `android/app/src/main/java/com/audiobookshelf/app/player/PlaybackQueue.kt` | `snapshot()` so the bookmark can copy the queue. |
| Modify `android/app/src/test/java/com/audiobookshelf/app/player/PlaybackQueueTest.kt` | One test for `snapshot()`. |
| Modify `android/app/src/main/java/com/audiobookshelf/app/managers/DbManager.kt` | Read, write, and delete one Paper bookmark. Glue, not a decision. |
| Modify `PlayerNotificationService.kt` | Demote on user pause, foreground-before-work on a media-button start, resume from the bookmark, clear the bookmark in `closePlayback`. |
| Modify `PlayerNotificationListener.kt` | Swipe calls `closePlayback`, not bare `stopSelf`. |
| Modify `PlayerListener.kt` | On a real pause, remember the bookmark and demote. Do not uncomment the 30-second block. |
| Modify `MediaSessionCallback.kt` | `onPlay` / play key uses `resumeAction` instead of bare `play()` when the player is empty. |
| Modify `SleepTimerManager.kt` | Timer end calls `closePlayback()`. |
| Modify `AndroidManifest.xml` | Add `ACTION_MEDIA_BUTTON` on the player service so the receiver uses `startForegroundService`. |
| Modify `components/app/AudioPlayer.vue` | Delete the `beforeDestroy` `closePlayback()` call. |

`PauseResume.kt` is the only new decision file. `ResumeBookmark` lives in it. `DbManager` is storage. The service is the caller.

---

### Task 1: Pause and resume decisions

**Files:**
- Create: `android/app/src/main/java/com/audiobookshelf/app/player/PauseResume.kt`
- Test: `android/app/src/test/java/com/audiobookshelf/app/player/PauseResumeTest.kt`

**Interfaces:**
- Consumes: `PlaybackQueueEntry` from `PlaybackQueue.kt` (already on master).
- Produces:
  - `enum class PauseStopReason { UserPause, SleepTimer, NotificationDismiss, UserClose }`
  - `enum class ForegroundChange { Promote, DemoteKeepNotification, RemoveNotification, None }`
  - `enum class TransportButton { Play, Pause, PlayPause, Stop, Other }`
  - `enum class LoadedPlayer { Empty, Paused, Playing }`
  - `enum class ResumeAction { PlayLoaded, PauseAndKeep, ResumeFromBookmark, StopAndClear, Ignore }`
  - `enum class ResumeSource { AlreadyLoaded, LocalFile, NewServerSession }`
  - `data class ResumeBookmark(val libraryItemId: String, val episodeId: String?, val local: Boolean, val positionSeconds: Double, val savedAt: Long, val serverConnectionConfigId: String?, val queue: List<PlaybackQueueEntry>)`
  - `fun foregroundChangeFor(reason: PauseStopReason): ForegroundChange`
  - `fun resumeAction(button: TransportButton, player: LoadedPlayer, hasBookmark: Boolean): ResumeAction`
  - `fun foregroundChangeFor(action: ResumeAction): ForegroundChange`
  - `fun mustForegroundImmediately(action: ResumeAction): Boolean`
  - `fun resumeSource(player: LoadedPlayer, local: Boolean): ResumeSource`
  - `fun warmResumeNeedsNewServerSession(local: Boolean, serverSessionFound: Boolean): Boolean`
  - `fun positionToResume(bookmark: ResumeBookmark, otherPositionSeconds: Double?, otherUpdatedAt: Long?): Double`
  - `fun transportButton(keyCode: Int): TransportButton`

Key codes are the platform values, passed in as `Int` so the test does not need `KeyEvent`: `126` play, `127` pause, `85` play/pause, `86` stop, `79` headset hook. Headset hook is `PlayPause`. Anything else is `Other`.

- [ ] **Step 1: Write the failing test**

Create `PauseResumeTest.kt`:

```kotlin
package com.audiobookshelf.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseResumeTest {
  private fun bookmark(savedAt: Long, position: Double) =
          ResumeBookmark(
                  libraryItemId = "lib-1",
                  episodeId = null,
                  local = false,
                  positionSeconds = position,
                  savedAt = savedAt,
                  serverConnectionConfigId = "srv",
                  queue = listOf(PlaybackQueueEntry("lib-2", null))
          )

  @Test
  fun userPauseDemotesAndKeepsTheNotification() {
    assertEquals(ForegroundChange.DemoteKeepNotification, foregroundChangeFor(PauseStopReason.UserPause))
  }

  @Test
  fun sleepTimerDismissAndCloseRemoveTheNotification() {
    assertEquals(ForegroundChange.RemoveNotification, foregroundChangeFor(PauseStopReason.SleepTimer))
    assertEquals(ForegroundChange.RemoveNotification, foregroundChangeFor(PauseStopReason.NotificationDismiss))
    assertEquals(ForegroundChange.RemoveNotification, foregroundChangeFor(PauseStopReason.UserClose))
  }

  @Test
  fun playOnEmptyPlayerWithBookmarkRestarts() {
    assertEquals(
            ResumeAction.ResumeFromBookmark,
            resumeAction(TransportButton.Play, LoadedPlayer.Empty, hasBookmark = true)
    )
  }

  @Test
  fun playOnEmptyPlayerWithoutBookmarkDoesNothing() {
    assertEquals(
            ResumeAction.Ignore,
            resumeAction(TransportButton.Play, LoadedPlayer.Empty, hasBookmark = false)
    )
  }

  @Test
  fun playOnPausedPlayerResumesTheLoadedItem() {
    assertEquals(
            ResumeAction.PlayLoaded,
            resumeAction(TransportButton.Play, LoadedPlayer.Paused, hasBookmark = true)
    )
  }

  @Test
  fun pauseKeepsTheSessionAndStopDoesNotClearIt() {
    assertEquals(
            ResumeAction.PauseAndKeep,
            resumeAction(TransportButton.Pause, LoadedPlayer.Playing, hasBookmark = true)
    )
    assertEquals(
            ResumeAction.PauseAndKeep,
            resumeAction(TransportButton.Stop, LoadedPlayer.Playing, hasBookmark = true)
    )
  }

  @Test
  fun playPauseTogglesAndRestartsWhenEmpty() {
    assertEquals(
            ResumeAction.PauseAndKeep,
            resumeAction(TransportButton.PlayPause, LoadedPlayer.Playing, hasBookmark = true)
    )
    assertEquals(
            ResumeAction.ResumeFromBookmark,
            resumeAction(TransportButton.PlayPause, LoadedPlayer.Empty, hasBookmark = true)
    )
  }

  @Test
  fun resumeAndPlayEnterTheForegroundBeforeAnyNetworkWork() {
    assertTrue(mustForegroundImmediately(ResumeAction.ResumeFromBookmark))
    assertTrue(mustForegroundImmediately(ResumeAction.PlayLoaded))
    assertFalse(mustForegroundImmediately(ResumeAction.PauseAndKeep))
    assertEquals(ForegroundChange.Promote, foregroundChangeFor(ResumeAction.ResumeFromBookmark))
    assertEquals(ForegroundChange.DemoteKeepNotification, foregroundChangeFor(ResumeAction.PauseAndKeep))
    assertEquals(ForegroundChange.RemoveNotification, foregroundChangeFor(ResumeAction.StopAndClear))
  }

  @Test
  fun emptyLocalItemUsesDiskAndEmptyRemoteItemOpensANewServerSession() {
    assertEquals(ResumeSource.LocalFile, resumeSource(LoadedPlayer.Empty, local = true))
    assertEquals(ResumeSource.NewServerSession, resumeSource(LoadedPlayer.Empty, local = false))
    assertEquals(ResumeSource.AlreadyLoaded, resumeSource(LoadedPlayer.Paused, local = false))
  }

  @Test
  fun warmRemoteResumeReopensOnlyWhenTheServerSessionIsGone() {
    assertFalse(warmResumeNeedsNewServerSession(local = true, serverSessionFound = false))
    assertFalse(warmResumeNeedsNewServerSession(local = false, serverSessionFound = true))
    assertTrue(warmResumeNeedsNewServerSession(local = false, serverSessionFound = false))
  }

  @Test
  fun newerOtherDeviceProgressBeatsTheBookmark() {
    val saved = bookmark(savedAt = 100L, position = 50.0)
    assertEquals(80.0, positionToResume(saved, otherPositionSeconds = 80.0, otherUpdatedAt = 200L), 0.0)
    assertEquals(50.0, positionToResume(saved, otherPositionSeconds = 10.0, otherUpdatedAt = 50L), 0.0)
    assertEquals(50.0, positionToResume(saved, otherPositionSeconds = null, otherUpdatedAt = null), 0.0)
  }

  @Test
  fun mediaKeyCodesMapToButtons() {
    assertEquals(TransportButton.Play, transportButton(126))
    assertEquals(TransportButton.Pause, transportButton(127))
    assertEquals(TransportButton.PlayPause, transportButton(85))
    assertEquals(TransportButton.PlayPause, transportButton(79))
    assertEquals(TransportButton.Stop, transportButton(86))
    assertEquals(TransportButton.Other, transportButton(0))
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

From `D:\GitHub\audiobookshelf-app\android` (JDK 21):

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.audiobookshelf.app.player.PauseResumeTest
```

Expected: FAIL, `Unresolved reference: foregroundChangeFor` (or the class file missing).

- [ ] **Step 3: Write the minimal implementation**

Create `PauseResume.kt` with exactly the functions the tests call. Behavior:

- `foregroundChangeFor(reason)`: `UserPause` → `DemoteKeepNotification`. The other three reasons → `RemoveNotification`.
- `resumeAction`: `Stop` and `Pause` while `Playing` → `PauseAndKeep`. `Pause` otherwise → `Ignore`. `Play` while `Playing` → `Ignore`. `Play` while `Paused` → `PlayLoaded`. `Play` while `Empty` → `ResumeFromBookmark` if `hasBookmark`, else `Ignore`. `PlayPause` while `Playing` → `PauseAndKeep`, while `Paused` → `PlayLoaded`, while `Empty` → same as `Play`. `Other` → `Ignore`.
- `foregroundChangeFor(action)`: `PlayLoaded` and `ResumeFromBookmark` → `Promote`. `PauseAndKeep` → `DemoteKeepNotification`. `StopAndClear` → `RemoveNotification`. `Ignore` → `None`.
- `mustForegroundImmediately`: true only for `PlayLoaded` and `ResumeFromBookmark`.
- `resumeSource`: non-empty player → `AlreadyLoaded`. Empty and local → `LocalFile`. Empty and not local → `NewServerSession`.
- `warmResumeNeedsNewServerSession`: true only when `local` is false and `serverSessionFound` is false.
- `positionToResume`: if either other argument is null, return `bookmark.positionSeconds`. If `otherUpdatedAt > bookmark.savedAt`, return `otherPositionSeconds`. Otherwise return `bookmark.positionSeconds`.
- `transportButton`: `126` play, `127` pause, `85` and `79` play/pause, `86` stop, else `Other`.

`ResumeBookmark` is a `data class` with the fields in the test. No Android imports in this file.

- [ ] **Step 4: Run the test to verify it passes**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.audiobookshelf.app.player.PauseResumeTest
```

Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```powershell
git add android/app/src/main/java/com/audiobookshelf/app/player/PauseResume.kt android/app/src/test/java/com/audiobookshelf/app/player/PauseResumeTest.kt
git commit -m "Add pause and headset-resume decisions."
```

---

### Task 2: Copy the queue into a snapshot

**Files:**
- Modify: `android/app/src/main/java/com/audiobookshelf/app/player/PlaybackQueue.kt`
- Test: `android/app/src/test/java/com/audiobookshelf/app/player/PlaybackQueueTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `PlaybackQueue.snapshot(): List<PlaybackQueueEntry>`. A copy. Later `poll` on the queue does not change the list already returned.

- [ ] **Step 1: Write the failing test**

Add to `PlaybackQueueTest.kt`:

```kotlin
  @Test
  fun snapshotIsACopy() {
    val queue = PlaybackQueue()
    queue.replace(listOf(entry("a"), entry("b", "ep")))
    val copy = queue.snapshot()
    queue.poll()
    assertEquals(2, copy.size)
    assertEquals("a", copy[0].libraryItemId)
    assertEquals("ep", copy[1].episodeId)
  }
```

- [ ] **Step 2: Run the test to verify it fails**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.audiobookshelf.app.player.PlaybackQueueTest.snapshotIsACopy
```

Expected: FAIL, `Unresolved reference: snapshot`.

- [ ] **Step 3: Write the minimal implementation**

In `PlaybackQueue`:

```kotlin
  fun snapshot(): List<PlaybackQueueEntry> = entries.toList()
```

- [ ] **Step 4: Run the test to verify it passes**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.audiobookshelf.app.player.PlaybackQueueTest
```

Expected: PASS, including the existing queue tests.

- [ ] **Step 5: Commit**

```powershell
git add android/app/src/main/java/com/audiobookshelf/app/player/PlaybackQueue.kt android/app/src/test/java/com/audiobookshelf/app/player/PlaybackQueueTest.kt
git commit -m "Copy the playback queue for the resume bookmark."
```

---

### Task 3: Store one bookmark

**Files:**
- Modify: `android/app/src/main/java/com/audiobookshelf/app/managers/DbManager.kt` (next to `savePlaybackSession`, around line 358)

**Interfaces:**
- Consumes: `ResumeBookmark`.
- Produces: `DbManager.saveResumeBookmark`, `getResumeBookmark`, `clearResumeBookmark`. Paper book name `"resumeBookmark"`, key `"current"`.

No unit test. Paper is not available in the local unit-test JVM. The phone script checks that a killed process still resumes.

- [ ] **Step 1: Add the three methods**

```kotlin
  fun saveResumeBookmark(bookmark: ResumeBookmark) {
    Paper.book("resumeBookmark").write("current", bookmark)
  }

  fun getResumeBookmark(): ResumeBookmark? {
    return Paper.book("resumeBookmark").read("current")
  }

  fun clearResumeBookmark() {
    Paper.book("resumeBookmark").delete("current")
  }
```

Import `ResumeBookmark`. Do not delete `savePlaybackSession` or change `lastPlaybackSession`. The old session row is still deleted on a successful sync. The bookmark is a different row and must survive that delete.

- [ ] **Step 2: Commit**

```powershell
git add android/app/src/main/java/com/audiobookshelf/app/managers/DbManager.kt
git commit -m "Store the paused playback bookmark."
```

---

### Task 4: Demote on pause, resume from the bookmark, clear it on a real stop

**Files:**
- Modify: `android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationService.kt`
- Modify: `android/app/src/main/java/com/audiobookshelf/app/player/PlayerListener.kt`
- Modify: `android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationListener.kt`
- Modify: `android/app/src/main/java/com/audiobookshelf/app/player/MediaSessionCallback.kt`
- Modify: `android/app/src/main/java/com/audiobookshelf/app/media/MediaProgressSyncer.kt`
- Modify: `android/app/src/main/java/com/audiobookshelf/app/managers/SleepTimerManager.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `components/app/AudioPlayer.vue`

**Interfaces:**
- Consumes: every function from Task 1, `PlaybackQueue.snapshot`, the DbManager methods from Task 3, existing `preparePlayer`, `play`, `pause`, `closePlayback`, `startNewPlaybackSession`, `apiHandler.playLibraryItem`, `apiHandler.getPlaybackSession`, `LocalLibraryItem.getPlaybackSession`.
- Produces: service methods `rememberResumeBookmark()`, `demotePausedForeground()`, `handleTransport(button: TransportButton)`, `resumeFromBookmark(bookmark: ResumeBookmark)`.

Do not construct `PlayerNotificationService` in a unit test. The decisions are already tested. This task is the wiring. Do not edit lines 391–419 or 619–643.

- [ ] **Step 1: Manifest**

Inside the existing `PlayerNotificationService` intent-filter, add the media-button action next to the browser action:

```xml
<action android:name="android.intent.action.MEDIA_BUTTON" />
```

`androidx.media.session.MediaButtonReceiver` then calls `startForegroundService` with the key event. That is the Android 12+ exemption. Leave the receiver declaration as it is.

- [ ] **Step 2: Remember and demote**

Add to the service:

```kotlin
  private var resumeInFlight = false

  fun loadedPlayer(): LoadedPlayer {
    if (!::mPlayer.isInitialized || mPlayer.mediaItemCount == 0) return LoadedPlayer.Empty
    return if (mPlayer.isPlaying) LoadedPlayer.Playing else LoadedPlayer.Paused
  }

  fun rememberResumeBookmark() {
    val session = currentPlaybackSession ?: return
    val libraryItemId = session.libraryItemId ?: return
    DeviceManager.dbManager.saveResumeBookmark(
            ResumeBookmark(
                    libraryItemId = libraryItemId,
                    episodeId = session.episodeId,
                    local = session.isLocal,
                    positionSeconds = getCurrentTimeSeconds(),
                    savedAt = System.currentTimeMillis(),
                    serverConnectionConfigId = session.serverConnectionConfigId,
                    queue = playbackQueue.snapshot()
            )
    )
  }

  fun demotePausedForeground() {
    if (!PlayerNotificationListener.isForegroundService) return
    stopForeground(Service.STOP_FOREGROUND_DETACH)
    PlayerNotificationListener.isForegroundService = false
    mediaSession.isActive = true
  }

  private fun startForegroundForResume() {
    val notification =
            NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(R.drawable.icon_monochrome)
                    .setContentTitle("Audiobookshelf")
                    .setOngoing(true)
                    .build()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    } else {
      startForeground(notificationId, notification)
    }
    PlayerNotificationListener.isForegroundService = true
  }
```

`minSdk` is 24, so `STOP_FOREGROUND_DETACH` needs no version guard. `startForegroundForResume` must run before any `playLibraryItem` call. `preparePlayer` replaces this placeholder notification.

In `PlayerListener.onIsPlayingChanged`, in the existing `else` branch that sets `lastPauseTime` (the real pause, not the buffering early-return), after `mediaProgressSyncer.pause { ... }` is scheduled, call:

```kotlin
playerNotificationService.rememberResumeBookmark()
playerNotificationService.demotePausedForeground()
```

Also call `rememberResumeBookmark()` in `MediaProgressSyncer.sync` immediately after `currentPlaybackSession?.syncData(syncData)`, and only when that session is non-null and its progress is not NaN. That is before the server callback, so a failed sync still leaves a bookmark. One line. That covers an OS kill while still playing, not only a pause. Do not clear the bookmark anywhere in the syncer. The successful-sync `removePlaybackSession` stays.

- [ ] **Step 3: Transport handling**

```kotlin
  fun handleTransport(button: TransportButton) {
    val bookmark = DeviceManager.dbManager.getResumeBookmark()
    val action = resumeAction(button, loadedPlayer(), bookmark != null)
    if (mustForegroundImmediately(action)) startForegroundForResume()
    when (action) {
      ResumeAction.PlayLoaded -> resumeLoaded(bookmark)
      ResumeAction.PauseAndKeep -> pause()
      ResumeAction.ResumeFromBookmark -> bookmark?.let { resumeFromBookmark(it) }
      ResumeAction.StopAndClear -> closePlayback()
      ResumeAction.Ignore -> {}
    }
  }
```

`MediaSessionCallback.onPlay` calls `handleTransport(TransportButton.Play)` instead of `play()`. The play-pause key path that today calls `play()` or `pause()` calls `handleTransport(TransportButton.Play)` or `handleTransport(TransportButton.Pause)`. Headset hook calls `handleTransport(TransportButton.PlayPause)`. `KEYCODE_MEDIA_STOP` calls `handleTransport(TransportButton.Stop)`, which stays `PauseAndKeep`. Leave fast-forward, rewind, and the click-count seek alone.

`onStartCommand`: if `intent?.action == Intent.ACTION_MEDIA_BUTTON`, read `EXTRA_KEY_EVENT`. On `ACTION_UP` for play, pause, play/pause, headset hook, and stop, call `handleTransport(transportButton(keyEvent.keyCode))`. Ignore `ACTION_DOWN` for those keys so a press is not handled twice. Return `START_STICKY` either way. A null intent (sticky restart) does not play.

`resumeLoaded`:

```kotlin
  private fun resumeLoaded(bookmark: ResumeBookmark?) {
    val session = currentPlaybackSession
    if (session == null || session.isLocal || !DeviceManager.checkConnectivity(ctx)) {
      play()
      return
    }
    apiHandler.getPlaybackSession(session.id) { found ->
      Handler(Looper.getMainLooper()).post {
        if (!warmResumeNeedsNewServerSession(session.isLocal, found != null)) {
          play()
        } else if (bookmark != null) {
          resumeFromBookmark(bookmark)
        } else {
          startNewPlaybackSession()
        }
      }
    }
  }
```

`resumeFromBookmark`:

```kotlin
  fun resumeFromBookmark(bookmark: ResumeBookmark) {
    if (resumeInFlight) return
    resumeInFlight = true
    val rate = mediaManager.getSavedPlaybackRate()
    val done = { resumeInFlight = false }
    when (resumeSource(LoadedPlayer.Empty, bookmark.local)) {
      ResumeSource.AlreadyLoaded -> done()
      ResumeSource.LocalFile -> {
        val local = DeviceManager.dbManager.getLocalLibraryItem(bookmark.libraryItemId)
        val episode = episodeFrom(local, bookmark.episodeId)
        if (local == null || (bookmark.episodeId != null && episode == null)) {
          Log.i(tag, "resume bookmark missing local item ${bookmark.libraryItemId}")
          done()
          return
        }
        val session = local.getPlaybackSession(episode, getDeviceInfo())
        session.currentTime = positionToResume(bookmark, session.currentTime, session.updatedAt)
        playbackQueue.replace(bookmark.queue)
        preparePlayer(session, true, rate)
        done()
      }
      ResumeSource.NewServerSession -> {
        if (!DeviceManager.checkConnectivity(ctx)) {
          Log.i(tag, "resume bookmark offline for ${bookmark.libraryItemId}")
          stopForeground(Service.STOP_FOREGROUND_REMOVE)
          PlayerNotificationListener.isForegroundService = false
          done()
          return
        }
        apiHandler.playLibraryItem(bookmark.libraryItemId, bookmark.episodeId, getPlayItemRequestPayload(false)) {
          Handler(Looper.getMainLooper()).post {
            if (it == null) {
              Log.i(tag, "resume bookmark server play failed ${bookmark.libraryItemId}")
              stopForeground(Service.STOP_FOREGROUND_REMOVE)
              PlayerNotificationListener.isForegroundService = false
            } else {
              it.currentTime = positionToResume(bookmark, it.currentTime, it.updatedAt)
              playbackQueue.replace(bookmark.queue)
              preparePlayer(it, true, rate)
            }
            done()
          }
        }
      }
    }
  }
```

`episodeFrom` matches the lookup in `startQueuedPlayback`:

```kotlin
  private fun episodeFrom(local: LocalLibraryItem?, episodeId: String?): PodcastEpisode? {
    if (local == null || episodeId.isNullOrEmpty()) return null
    val podcast = local.media as? Podcast
    return podcast?.episodes?.find { it.id == episodeId }
  }
```

Do not clear the bookmark on failure. The next play press tries again.

`closePlayback`: call `DeviceManager.dbManager.clearResumeBookmark()` next to `playbackQueue.clear()`. Leave the rest of `closePlayback` as it is, including `STOP_FOREGROUND_REMOVE`.

- [ ] **Step 4: Stops that must remove the notification**

`PlayerNotificationListener.onNotificationCancelled`: when `dismissedByUser` is true, call `closePlayback()` instead of `stopSelf()`. Still set `isForegroundService = false`.

`SleepTimerManager` at the `sleepTimeSecondsRemaining <= 0` branch: replace `pause()` with `playerNotificationService.closePlayback()`. Keep `onSleepTimerEnded` and `clearSleepTimer`.

`AudioPlayer.vue` `beforeDestroy`: delete the `if (this.playbackSession) { this.closePlayback() }` block (lines 1013–1015). Leave listener removal and the interval clear. The in-app Close action still calls `closePlayback()`.

- [ ] **Step 5: Run the unit tests**

```powershell
cd D:\GitHub\audiobookshelf-app\android
.\gradlew.bat :app:testDebugUnitTest --tests com.audiobookshelf.app.player.PauseResumeTest --tests com.audiobookshelf.app.player.PlaybackQueueTest
```

Expected: PASS. No new test class in this task.

- [ ] **Step 6: Commit**

```powershell
git add android/app/src/main/AndroidManifest.xml android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationService.kt android/app/src/main/java/com/audiobookshelf/app/player/PlayerListener.kt android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationListener.kt android/app/src/main/java/com/audiobookshelf/app/player/MediaSessionCallback.kt android/app/src/main/java/com/audiobookshelf/app/media/MediaProgressSyncer.kt android/app/src/main/java/com/audiobookshelf/app/managers/SleepTimerManager.kt components/app/AudioPlayer.vue
git commit -m "Resume the last item from a headset play after pause."
```

---

### Task 5: Review

- [ ] **Step 1: `/review-bugbot` on the branch diff against `master`.**

If the command is not available, read the diff and check the list below, then say in the PR that bugbot was unavailable and the review was manual.

- Pause does not call `closePlayback` or `stopForeground(STOP_FOREGROUND_REMOVE)`.
- Sleep timer, notification swipe, and in-app Close do call `closePlayback`, and that deletes the bookmark.
- `initializeMPlayer` buffer numbers and `handlePlayerPlaybackError` are unchanged.
- `versionCode` and `versionName` are unchanged.
- A second play press while `resumeInFlight` is true does not open two server sessions.
- Offline remote failure does not delete the bookmark.
- The queue copied at pause is passed to `playbackQueue.replace` before `preparePlayer` on resume.
- Vue `beforeDestroy` no longer closes native playback.

---

## Phone test script

Build and install on the Pixel 8 Pro from Windows 11, JDK 21, repo `D:\GitHub\audiobookshelf-app`. Debug application id is `com.audiobookshelf.app.debug`.

```powershell
cd D:\GitHub\audiobookshelf-app
npm run sync
cd android
.\gradlew.bat :app:testDebugUnitTest --tests com.audiobookshelf.app.player.PauseResumeTest --tests com.audiobookshelf.app.player.PlaybackQueueTest
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

`npm run sync` is `nuxt generate && npx cap sync`. Do that before Gradle on a fresh tree so `android/capacitor-cordova-android-plugins` exists.

Log tags:

```powershell
adb logcat -c
adb logcat -s PlayerNotificationServ:D MediaSessionCallback:D PlayerNotificationListener:D PlayerListener:D MediaButtonReceiver:D AbsAutoAdvance:I
```

The service tag string is `PlayerNotificationServ` (truncated in code), not the class name.

Simulate a headset:

```powershell
adb shell input keyevent 127
adb shell input keyevent 126
adb shell dumpsys media_session
adb shell dumpsys activity services com.audiobookshelf.app.debug
```

`127` is `KEYCODE_MEDIA_PAUSE`. `126` is `KEYCODE_MEDIA_PLAY`. In `dumpsys media_session`, the Audiobookshelf session should stay `active=true` with `state=PAUSED(2)` after pause, and `state=PLAYING(3)` after play. `dumpsys activity services` should show the service not in the foreground after pause (`isForegroundService` false in the listener log: `stopForeground` detach) and in the foreground again after play.

Manual, screen locked, Pixel Buds Pro 2:

1. Play a remote book for about a minute. Pause from the buds. Lock the phone.
2. After 1 minute, wake the lock screen. The control should still be there. Buds play resumes the same position (auto-rewind may step back a few seconds; that is existing behavior).
3. Pause again, lock, wait 10 minutes. Wake. SystemUI may have dropped the live card. `dumpsys media_session` may still show `PAUSED`. Press play on the buds. Playback resumes. This is the bug today.
4. Pause, lock, leave it 1 hour, then overnight. Buds play resumes the same item. If the process was killed, logcat shows `resume bookmark` / `playLibraryItem` and a new session id, position at the bookmark unless the server progress is newer.
5. Repeat step 4 on a downloaded item in airplane mode. It plays from disk.
6. Repeat step 4 on a remote item in airplane mode after force-stopping the app. Play does not start. Turn the network back on and press play again. It starts. The bookmark was kept.
7. Start a playlist so the queue has a next item. Pause, force-stop (`adb shell am force-stop com.audiobookshelf.app.debug`), then buds play, then seek to the end. The next queue item starts.
8. Sleep timer set to 1 minute. When it fires, the notification is gone. Buds play does nothing. The in-app player is closed.
9. Pause, swipe the notification away. Buds play does nothing.
10. Pause and leave the app under normal battery optimization (do not request an exemption). Overnight resume in step 4 is the battery check.

Force-stop clears the process but not Paper. `adb shell am kill com.audiobookshelf.app.debug` is the softer kill. Use force-stop for step 7.

## Edge cases

| Case | Result |
| --- | --- |
| Sleep timer | `closePlayback`. No resume. |
| Swipe notification | `closePlayback`. No resume. |
| In-app Close | Existing `closePlayback`. No resume. |
| Headset stop | Pause. Notification stays. Resume works. |
| OS kills the process | Bookmark and queue remain. Play restarts the service and calls `startForeground` before the network. |
| WebView / activity destroyed | Vue no longer closes native playback. |
| Auto-advance queue | Copied into the bookmark on pause and on sync. Restored before `preparePlayer`. |
| Server session expired | Warm: `getPlaybackSession` null → `resumeFromBookmark` → `playLibraryItem`. Cold: `playLibraryItem` directly. |
| Offline local item | `LocalFile`. |
| Offline remote item, process dead | Log, remove the placeholder notification, keep the bookmark. |
| Battery optimization | Service is not foreground while paused, so Android may kill it. Resume does not depend on the process staying up. |
| PR #5 retry | Unchanged. A warm `play()` that then errors still uses `handlePlayerPlaybackError`. |
| Cast | `rememberResumeBookmark` uses `currentPlaybackSession.isLocal` and the current player time. Do not special-case cast. If cast is active, demoting the phone service is unchanged from today's pause. |

## Open questions

1. Sleep timer today only pauses, and the in-app player stays on screen. This plan closes playback when the timer fires, so the lock screen does not keep a resume control. Confirm that is what you want.
2. A live paused session's lock-screen card still expires at about 10 minutes. That is Pixel SystemUI, not this app. Hours and days work by restarting from the bookmark when you press play. Confirm that matches what you see in Pocket Casts, rather than a permanent foreground notification.
3. Swiping the app out of recents hits `onTaskRemoved` → `stopSelf()` and does not clear the bookmark, so a later play resumes. Notification swipe does clear it. Confirm recents swipe should still resume.
4. A remote item that is also downloaded still resumes as remote. Airplane mode after a kill will not play it until the network is back. Confirm you do not want an automatic switch to the local file.
