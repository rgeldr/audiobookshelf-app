# Auto-advance playback through a playlist, series, or collection

Status: implemented

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When playback of a book (or playlist episode) is started from a playlist, collection, or series in the Android app, finishing that item starts the next playable item in that container, in order, until the last item.

**Architecture:** The Vue pages already know the ordered list. They pass the remaining items as a queue on the existing `play-item` event. The Android `PlayerNotificationService` stores that queue in memory and, on ExoPlayer `STATE_ENDED`, starts the next item with the same session API the app already uses for one item. No new server endpoint. iOS and the in-app web player are unchanged.

**Tech Stack:** Nuxt 2 / Vue 2, Capacitor plugin `AbsAudioPlayer`, Kotlin, ExoPlayer 2.18, JUnit 4, Node's built-in test runner (`node --test`).

## Global Constraints

- Implemented on this branch. The decisions below are what shipped.
- Karpathy guidelines (https://github.com/multica-ai/andrej-karpathy-skills/blob/main/skills/karpathy-guidelines/SKILL.md): think before coding, simplicity first, surgical changes, goal-driven verification.
- Android only. Do not change iOS, the Capacitor web player, or `advplyr/audiobookshelf`.
- No server change. One playback session is still one library item (`POST /api/items/:id/play` or `POST /api/items/:id/play/:episodeId`).
- Do not refactor adjacent player code. Do not add a settings toggle, a next-book media button, queue persistence, or Android Auto browse entries for series/playlists/collections.
- Every new or changed decision function has a unit test. Do not add Jest, Vitest, or a Vue test harness.
- Playback that was not started from a playlist, collection, or series does not auto-advance.

## Assumptions

These are the product choices this plan locks in. Do not invent different ones during implementation.

1. The queue starts at the item the user pressed play on and runs forward to the end of that container. Earlier items are not played.
2. An item already marked finished is skipped, except the item the user explicitly started (a row tap or an item-page play still plays that item).
3. Playlist header Play and collection header Play keep today's start rule: the first unfinished playable item. The queue is the unfinished playable items after that one.
4. If every item is finished, header Play still does nothing.
5. Playlist order is `playlist.items` order. Collection order is `collection.books` order. Series order is the server sort `sort=sequence` (numeric sequence, ascending), not the order the series bookshelf happens to render. The series bookshelf currently sends no `sort` (`components/bookshelf/LazyBookshelf.vue` `buildSearchParams`, series-books branch).
6. Series auto-advance applies only when the book was opened or played from that series bookshelf (`seriesId` on the route or the play event). Opening the same book from the library, search, or Continue does not continue the series. A book in several series uses only the series the user opened.
7. Pause, headset stop, and the sleep timer do not start the next item and do not clear the queue. Closing the player or starting a different item replaces the queue.
8. The queue lives in the player service process. If Android kills the process, the queue is gone. Do not persist it.
9. On a metered network, a remote queue item is not started when device setting `streamingUsingCellular` is `NEVER`. A downloaded item (`libraryItemId` starts with `local`) still starts. `ASK` does not show a dialog during auto-advance (the screen may be locked); the next item starts.
10. If starting the next item fails, stop. Do not skip ahead through the rest of the queue.

---

## Root cause

Tracks inside one book already advance. ExoPlayer is given every track of the current playback session as media items, and it moves to the next track on its own:

```542:545:android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationService.kt
      if (mediaItems.size > 1) {
        currentPlayer.addMediaItems(mediaItems.subList(1, mediaItems.size))
        Log.d(tag, "currentPlayer total media items ${currentPlayer.mediaItemCount}")
```

Nothing equivalent exists for the next library item. Playlist, collection, and series playback all start exactly one item.

Playlist header Play emits one `play-item` for the first unfinished row. The rest of `playableItems` is discarded:

```142:155:pages/playlist/_id.vue
    playNextItem() {
      const nextItem = this.playableItems.find((i) => {
        const prog = this.$store.getters['user/getUserMediaProgress'](i.libraryItemId, i.episodeId)
        return !prog?.isFinished
      })
      if (nextItem) {
        this.mediaIdStartingPlayback = nextItem.episodeId || nextItem.libraryItemId
        this.$store.commit('setPlayerIsStartingPlayback', this.mediaIdStartingPlayback)
        if (nextItem.localLibraryItem) {
          this.$eventBus.$emit('play-item', { libraryItemId: nextItem.localLibraryItem.id, episodeId: nextItem.localEpisode?.id, serverLibraryItemId: nextItem.libraryItemId, serverEpisodeId: nextItem.episodeId })
        } else {
          this.$eventBus.$emit('play-item', { libraryItemId: nextItem.libraryItemId, episodeId: nextItem.episodeId })
        }
      }
    },
```

A playlist row play does the same for that row only (`components/tables/playlist/ItemTableRow.vue` `playClick`, around lines 158-178). Collection header Play and collection row play are the same shape (`pages/collection/_id.vue` `playNextItem`, lines 123-137; `components/tables/collection/BookTableRow.vue` `playClick`, lines 92-111).

The series bookshelf has no play-all. Grid cards navigate to the item page (`components/cards/LazyBookCard.vue` `clickCard`). List cards can play that one book (`components/cards/LazyListBookCard.vue` `play`, lines 327-351). The item page then emits one `play-item` (`pages/item/_id/index.vue` `play`, lines 570-591). No series id is passed.

`AudioPlayerContainer.playLibraryItem` forwards that single id to `AbsAudioPlayer.prepareLibraryItem` (`components/app/AudioPlayerContainer.vue` lines 195-276). Native `prepareLibraryItem` opens one local session or one `POST /api/items/:id/play` session (`android/app/src/main/java/com/audiobookshelf/app/plugins/AbsAudioPlayer.kt` lines 212-291; `ApiHandler.playLibraryItem` lines 593-607).

When the timeline ends, the listener reports `STATE_ENDED` and calls `handlePlaybackEnded`:

```143:147:android/app/src/main/java/com/audiobookshelf/app/player/PlayerListener.kt
      if (playerNotificationService.currentPlayer.playbackState == Player.STATE_ENDED) {
        Log.d(tag, "STATE_ENDED")
        playerNotificationService.sendClientMetadata(PlayerState.ENDED)
        playerNotificationService.handlePlaybackEnded()
      }
```

`handlePlaybackEnded` returns immediately unless three things are all true: Android Auto is connected, the current session is a podcast episode, and that podcast has another unfinished episode. Books never enter this block. Podcasts on the phone (Android Auto not connected) do not either:

```643:668:android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationService.kt
  fun handlePlaybackEnded() {
    Log.d(tag, "handlePlaybackEnded")
    if (isAndroidAuto && currentPlaybackSession?.isPodcastEpisode == true) {
      Log.d(tag, "Podcast playback ended on android auto")
      val libraryItem = currentPlaybackSession?.libraryItem ?: return
      mediaProgressSyncer.finished {
        mediaManager.loadServerUserMediaProgress {
          val podcast = libraryItem.media as Podcast
          val nextEpisode = podcast.getNextUnfinishedEpisode(libraryItem.id, mediaManager)
          // ... mediaManager.play + preparePlayer
        }
      }
    }
  }
```

`skipToNext` / `onSkipToNext` call `currentPlayer.seekToNext()`, which is the next ExoPlayer media item (the next track of this book), not the next book (`PlayerNotificationService.kt` lines 999-1001; `MediaSessionCallback.kt` lines 75-77).

The UI cannot fill this gap while the phone is locked. `AbsAudioPlayer` drops `onMetadata` when the activity is paused, which is the driving and chores case:

```65:68:android/app/src/main/java/com/audiobookshelf/app/plugins/AbsAudioPlayer.kt
        override fun onMetadata(metadata: PlaybackMetadata) {
          // Skip frequent metadata updates when app is backgrounded to prevent event queue buildup
          if (!isInForeground) return
          notifyListeners("onMetadata", JSObject(jacksonMapper.writeValueAsString(metadata)))
```

`AudioPlayer.vue` only logs the ended state (lines 871-874). It does not start another item.

### How iOS and web differ

Same gap, different files. Out of scope.

- iOS queues the tracks of one session in `AVQueuePlayer`. When `currentItem` becomes nil it marks the audio session inactive. It does not open another library item (`ios/App/Shared/player/AudioPlayer.swift` lines 810-820). Remote next/previous seek by the jump interval (lines 741-757).
- The Capacitor web player (`plugins/capacitor/AbsAudioPlayer.js` `evtEnded`, lines 208-219) loads the next track of the same session. On the last track it emits `ENDED` and stops.
- The official server web client has no book-to-book playback queue. Playback is one session per item. Confirmed by the play URL the app already calls and by the absence of a queue API in `advplyr/audiobookshelf`. No server change is required or desired.

### What already exists and must stay

- Multi-track advance inside one book or one episode.
- Android Auto "next unfinished episode of this podcast", newest `publishedAt` first (`Podcast.getNextUnfinishedEpisode`, `DataClasses.kt` lines 120-127). This runs only when the explicit queue is empty. A playlist of episodes uses playlist order instead, including while Android Auto is connected.
- Android Auto browse (`BrowseTree.kt`) lists Continue, Recent, Downloads, and Libraries. It does not list series, playlists, or collections. Playing a book from that browse tree still starts one item and does not gain a queue.

---

## Minimal proposed change

Build the remaining queue in JS when playback starts from a container. Store it on the Android service. On `STATE_ENDED`, start the next entry natively so it works with the screen off.

```
playlist / collection / series play
        |
        | play-item { ..., queue: [{libraryItemId, episodeId}, ...] }
        v
AudioPlayerContainer.playLibraryItem
        |
        | prepareLibraryItem({ ..., queue })
        v
AbsAudioPlayer.prepareLibraryItem  -->  PlaybackQueue.replace(queue)
        |
        v
one item plays, as today
        |
        | ExoPlayer STATE_ENDED
        v
handlePlaybackEnded
        | queue nonempty --> mark current item finished, preparePlayer(next)
        | queue empty and Android Auto podcast --> existing next-episode path
        | else --> stop, as today
```

Queue entries are the items after the one being started, with finished items removed. Each entry is `{ libraryItemId, episodeId }`. `episodeId` is null for books. `libraryItemId` is the local id (`local_...`) when that item is downloaded, otherwise the server id. That is the same choice playlist row play already makes, so downloaded and streamed items share one code path (`AbsAudioPlayer.prepareLibraryItem` already branches on `libraryItemId.startsWith("local")`).

Do not start the next item from Vue on `ENDED`. The service and the page would both start it whenever the app is in the foreground.

---

## Edge cases

| Case | Behavior |
| --- | --- |
| Last item ends | `peek()` is null. No queue work. Android Auto podcast next-episode still runs when its existing condition is true. Otherwise the player stays `ENDED`, as today. |
| Podcast episode vs book | Books have `episodeId: null`. Playlist episodes keep their episode id and play in playlist order. A podcast opened from the podcast page sends no queue, so phone playback still does not advance episodes, and Android Auto still uses `getNextUnfinishedEpisode`. |
| Already finished | Skipped when the queue is built, except the item the user started. Header Play still begins at the first unfinished item. |
| Downloaded vs streamed | Downloaded items use the local id and play offline. Streamed items call the existing play API. Mixed queues are allowed. |
| Metered network and streaming set to NEVER | Remote next item is not started. Queue is cleared. Local next item is started. |
| User pauses or presses stop | `MediaSessionCallback.onPause` / `onStop` call `pause()` (`MediaSessionCallback.kt` lines 62-68). `STATE_ENDED` does not fire. Queue is kept. |
| Sleep timer | `SleepTimerManager` calls `pause()` when the timer hits zero (`SleepTimerManager.kt` around lines 168-175). It does not seek to the end of the book. Next item does not start. |
| User closes the player | `closePlayback` clears the queue and bumps a generation counter so an in-flight "start next" is dropped. |
| User starts a different item | `prepareLibraryItem` replaces the queue (empty when the new play has no queue) and bumps the generation counter. |
| User seeks to the end | ExoPlayer reports `STATE_ENDED`. That counts as the item finishing, so the next queue item starts. |
| Android Auto | A queue started in the app still advances, because `STATE_ENDED` is handled in the service. Browse-tree play of one book still has no queue. Existing podcast episode advance is unchanged when the queue is empty. |
| Next play request fails | Log, clear the rest of the queue, leave the player ended. Do not walk the queue looking for one that works. |
| Series fetch fails or device is offline | Play the one book with an empty queue. |
| Process death | Queue is memory-only. Accept it. |
| Casting | `playServerLibraryItemAndCast` calls `prepareLibraryItem` without a queue, which clears the queue. Auto-advance stops for that cast. Do not add cast-queue support. |

---

## File structure

| File | Responsibility |
| --- | --- |
| Create `utils/playbackQueue.js` | Pure function: remaining queue after the started item, skipping finished items. |
| Create `utils/playbackQueue.test.js` | Node tests for that function. |
| Modify `package.json` | `"test": "node --test utils/playbackQueue.test.js"`. |
| Create `android/.../player/PlaybackQueue.kt` | In-memory queue plus `playbackEndAction` and `shouldStartQueuedItem`. No Android `Context`. |
| Create `android/app/src/test/.../player/PlaybackQueueTest.kt` | JUnit tests for the Kotlin functions. |
| Modify `android/app/build.gradle` | `testImplementation "junit:junit:$junitVersion"` so the new test compiles. `junitVersion` is already `4.13.2` in `android/variables.gradle`. |
| Modify `pages/playlist/_id.vue`, `components/tables/playlist/ItemTableRow.vue` | Attach `queue` to `play-item`. |
| Modify `pages/collection/_id.vue`, `components/tables/collection/BookTableRow.vue` | Attach `queue` to `play-item`. |
| Modify `mixins/bookshelfCardsHelpers.js`, `components/cards/LazyBookCard.vue`, `components/cards/LazyListBookCard.vue` | Pass `seriesId` from the series bookshelf into navigation and list-view play. |
| Modify `pages/item/_id/index.vue` | Forward `seriesId` on audiobook `play-item` when the query has it. |
| Modify `components/app/AudioPlayerContainer.vue` | Resolve a series id into a queue, then pass `queue` to `prepareLibraryItem`. |
| Modify `android/.../plugins/AbsAudioPlayer.kt` | Read `queue` and replace the service queue before preparing. |
| Modify `android/.../player/PlayerNotificationService.kt` | On end, start the next queued item or keep the podcast path. Clear the queue in `closePlayback`. |

No other files.

---

### Task 1: Queue builder

**Files:**
- Create: `utils/playbackQueue.js`
- Create: `utils/playbackQueue.test.js`
- Modify: `package.json` scripts

**Interfaces:**
- Consumes: nothing
- Produces: `queueAfter(items, startIndex) -> Array<{libraryItemId: string, episodeId: string|null}>`
  - `items[i]` is `{ libraryItemId: string, episodeId: string|null, finished: boolean }`
  - `startIndex` is the item currently being started. It is not included in the result.
  - Later items with `finished === true` are omitted. Order of the rest is unchanged.
  - Returns `[]` when `startIndex` is out of range or there is no later unfinished item.

- [ ] **Step 1: Write the failing test**

`utils/playbackQueue.test.js`:

```javascript
const test = require('node:test')
const assert = require('node:assert/strict')
const { queueAfter } = require('./playbackQueue')

const items = [
  { libraryItemId: 'a', episodeId: null, finished: true },
  { libraryItemId: 'b', episodeId: null, finished: false },
  { libraryItemId: 'c', episodeId: null, finished: true },
  { libraryItemId: 'local_d', episodeId: 'local_ep', finished: false }
]

test('header-style start skips finished items after the start index', () => {
  assert.deepEqual(queueAfter(items, 1), [
    { libraryItemId: 'local_d', episodeId: 'local_ep' }
  ])
})

test('the started item is not in the queue even when it is unfinished', () => {
  assert.deepEqual(queueAfter(items, 1)[0].libraryItemId, 'local_d')
})

test('a finished item that the user started is not replayed later; later unfinished items remain', () => {
  assert.deepEqual(queueAfter(items, 0), [
    { libraryItemId: 'b', episodeId: null },
    { libraryItemId: 'local_d', episodeId: 'local_ep' }
  ])
})

test('last item and unknown index produce an empty queue', () => {
  assert.deepEqual(queueAfter(items, 3), [])
  assert.deepEqual(queueAfter(items, -1), [])
  assert.deepEqual(queueAfter(items, 4), [])
})

test('episode id null stays null', () => {
  assert.equal(queueAfter(items, 0)[0].episodeId, null)
})
```

- [ ] **Step 2: Run the test and confirm it fails**

From `D:\GitHub\audiobookshelf-app` in PowerShell:

```powershell
node --test utils/playbackQueue.test.js
```

Expected: fail because `./playbackQueue` cannot be loaded.

- [ ] **Step 3: Implement the function**

`utils/playbackQueue.js`:

```javascript
function queueAfter(items, startIndex) {
  if (!Array.isArray(items) || startIndex < 0 || startIndex >= items.length) return []
  const queue = []
  for (let i = startIndex + 1; i < items.length; i++) {
    const item = items[i]
    if (item.finished) continue
    queue.push({
      libraryItemId: item.libraryItemId,
      episodeId: item.episodeId || null
    })
  }
  return queue
}

module.exports = { queueAfter }
```

Add to `package.json` scripts:

```json
"test": "node --test utils/playbackQueue.test.js"
```

- [ ] **Step 4: Run the test and confirm it passes**

```powershell
node --test utils/playbackQueue.test.js
```

Expected: 5 passed.

- [ ] **Step 5: Commit**

```powershell
git add utils/playbackQueue.js utils/playbackQueue.test.js package.json
git commit -m "Add playback queue builder for the items after the one that was started."
```

---

### Task 2: Android queue and end-of-item decision

**Files:**
- Create: `android/app/src/main/java/com/audiobookshelf/app/player/PlaybackQueue.kt`
- Create: `android/app/src/test/java/com/audiobookshelf/app/player/PlaybackQueueTest.kt`
- Modify: `android/app/build.gradle` dependencies

**Interfaces:**
- Consumes: nothing from Task 1 (the JSON shape matches `PlaybackQueueEntry`)
- Produces:
  - `data class PlaybackQueueEntry(val libraryItemId: String, val episodeId: String?)`
  - `class PlaybackQueue` with `replace(List)`, `clear()`, `peek(): PlaybackQueueEntry?`, `poll(): PlaybackQueueEntry?`
  - `enum class PlaybackEndAction { StartNext, PodcastAutoAdvance, Stop }`
  - `fun playbackEndAction(hasQueueItem: Boolean, isPodcastEpisode: Boolean, isAndroidAuto: Boolean): PlaybackEndAction`
  - `fun shouldStartQueuedItem(libraryItemId: String, blockMeteredRemote: Boolean): Boolean`
    - returns false only when `blockMeteredRemote` is true and `libraryItemId` does not start with `"local"`

- [ ] **Step 1: Add JUnit and write the failing test**

In `android/app/build.gradle` `dependencies`, add:

```gradle
testImplementation "junit:junit:$junitVersion"
```

`PlaybackQueueTest.kt` (package `com.audiobookshelf.app.player`):

```kotlin
package com.audiobookshelf.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueTest {
  private fun entry(id: String, episodeId: String? = null) = PlaybackQueueEntry(id, episodeId)

  @Test
  fun pollReturnsEntriesInOrderThenNull() {
    val queue = PlaybackQueue()
    queue.replace(listOf(entry("a"), entry("b", "ep")))
    assertEquals("a", queue.poll()?.libraryItemId)
    assertEquals("ep", queue.poll()?.episodeId)
    assertNull(queue.poll())
  }

  @Test
  fun replaceDropsThePreviousQueue() {
    val queue = PlaybackQueue()
    queue.replace(listOf(entry("a")))
    queue.replace(listOf(entry("b")))
    assertEquals("b", queue.peek()?.libraryItemId)
    assertEquals(1, queue.size)
  }

  @Test
  fun clearMakesPeekNull() {
    val queue = PlaybackQueue()
    queue.replace(listOf(entry("a")))
    queue.clear()
    assertNull(queue.peek())
  }

  @Test
  fun explicitQueueBeatsAndroidAutoPodcastAdvance() {
    assertEquals(
      PlaybackEndAction.StartNext,
      playbackEndAction(hasQueueItem = true, isPodcastEpisode = true, isAndroidAuto = true)
    )
  }

  @Test
  fun androidAutoPodcastAdvanceRemainsWhenQueueIsEmpty() {
    assertEquals(
      PlaybackEndAction.PodcastAutoAdvance,
      playbackEndAction(hasQueueItem = false, isPodcastEpisode = true, isAndroidAuto = true)
    )
  }

  @Test
  fun booksAndPhonePodcastsStopWhenQueueIsEmpty() {
    assertEquals(
      PlaybackEndAction.Stop,
      playbackEndAction(hasQueueItem = false, isPodcastEpisode = false, isAndroidAuto = true)
    )
    assertEquals(
      PlaybackEndAction.Stop,
      playbackEndAction(hasQueueItem = false, isPodcastEpisode = true, isAndroidAuto = false)
    )
  }

  @Test
  fun meteredRemoteItemIsBlockedAndLocalItemIsNot() {
    assertFalse(shouldStartQueuedItem("server-id", blockMeteredRemote = true))
    assertTrue(shouldStartQueuedItem("local_server-id", blockMeteredRemote = true))
    assertTrue(shouldStartQueuedItem("server-id", blockMeteredRemote = false))
  }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

PowerShell, from `D:\GitHub\audiobookshelf-app\android`:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.audiobookshelf.app.player.PlaybackQueueTest
```

Expected: compilation failure, unresolved `PlaybackQueue`.

- [ ] **Step 3: Implement `PlaybackQueue.kt`**

```kotlin
package com.audiobookshelf.app.player

data class PlaybackQueueEntry(
  val libraryItemId: String,
  val episodeId: String?
)

class PlaybackQueue {
  private val entries = ArrayDeque<PlaybackQueueEntry>()

  val size: Int
    get() = entries.size

  fun replace(next: List<PlaybackQueueEntry>) {
    entries.clear()
    entries.addAll(next)
  }

  fun clear() {
    entries.clear()
  }

  fun peek(): PlaybackQueueEntry? = entries.firstOrNull()

  fun poll(): PlaybackQueueEntry? = if (entries.isEmpty()) null else entries.removeFirst()
}

enum class PlaybackEndAction {
  StartNext,
  PodcastAutoAdvance,
  Stop
}

fun playbackEndAction(
  hasQueueItem: Boolean,
  isPodcastEpisode: Boolean,
  isAndroidAuto: Boolean
): PlaybackEndAction {
  if (hasQueueItem) return PlaybackEndAction.StartNext
  if (isPodcastEpisode && isAndroidAuto) return PlaybackEndAction.PodcastAutoAdvance
  return PlaybackEndAction.Stop
}

fun shouldStartQueuedItem(libraryItemId: String, blockMeteredRemote: Boolean): Boolean {
  if (blockMeteredRemote && !libraryItemId.startsWith("local")) return false
  return true
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.audiobookshelf.app.player.PlaybackQueueTest
```

Expected: BUILD SUCCESSFUL, all 7 tests passed.

- [ ] **Step 5: Commit**

```powershell
git add android/app/build.gradle android/app/src/main/java/com/audiobookshelf/app/player/PlaybackQueue.kt android/app/src/test/java/com/audiobookshelf/app/player/PlaybackQueueTest.kt
git commit -m "Add an in-memory playback queue and the end-of-item decision."
```

---

### Task 3: Start the next item from the Android service

**Files:**
- Modify: `android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationService.kt`
- Modify: `android/app/src/main/java/com/audiobookshelf/app/plugins/AbsAudioPlayer.kt`

**Interfaces:**
- Consumes: `PlaybackQueue`, `PlaybackQueueEntry`, `playbackEndAction`, `shouldStartQueuedItem` from Task 2
- Produces: `PlayerNotificationService.playbackQueue` and `PlayerNotificationService.autoAdvanceGeneration`
  - `prepareLibraryItem` replaces `playbackQueue` from the `queue` plugin argument and increments `autoAdvanceGeneration` before preparing the requested item
  - Native auto-advance calls `preparePlayer` directly. It must not call `prepareLibraryItem`, or it would wipe the rest of the queue
  - `closePlayback` clears the queue and increments the generation before stopping the player

The decision functions are already tested. This task wires them. Do not add a test that constructs `PlayerNotificationService`.

- [ ] **Step 1: Hold the queue on the service**

Next to the other fields in `PlayerNotificationService` (near `isAndroidAuto`, line 127):

```kotlin
val playbackQueue = PlaybackQueue()
var autoAdvanceGeneration: Int = 0
```

At the top of `closePlayback` (line 1027), before the player is stopped:

```kotlin
autoAdvanceGeneration += 1
playbackQueue.clear()
```

- [ ] **Step 2: Replace the queue from `prepareLibraryItem`**

In `AbsAudioPlayer.prepareLibraryItem`, after the empty-id check and before either the local or server branch, on the main looper together with the existing prepare work:

```kotlin
playerNotificationService.autoAdvanceGeneration += 1
playerNotificationService.playbackQueue.replace(parsePlaybackQueue(call))
```

`call.getArray("queue")` is a Capacitor `JSArray`. A missing array means an empty queue (cast handoff and every play that is not from a container).

```kotlin
private fun parsePlaybackQueue(call: PluginCall): List<PlaybackQueueEntry> {
  val array = call.getArray("queue") ?: return emptyList()
  val entries = ArrayList<PlaybackQueueEntry>(array.length())
  for (i in 0 until array.length()) {
    val obj = array.optJSONObject(i) ?: continue
    val libraryItemId = obj.optString("libraryItemId", "")
    if (libraryItemId.isEmpty()) continue
    val episodeId = obj.optString("episodeId", "").ifEmpty { null }
    entries.add(PlaybackQueueEntry(libraryItemId, episodeId))
  }
  return entries
}
```

Put the `replace` call in both the local and server success paths immediately before `preparePlayer`, and also when those paths fail (`call.resolve` with an error, local item missing, episode missing). On failure, `playbackQueue.clear()` so a failed play cannot later advance an old queue. Increment the generation once per `prepareLibraryItem` call, not once per branch.

- [ ] **Step 3: Branch `handlePlaybackEnded`**

Move the current podcast body (lines 645-667) into `private fun playNextAndroidAutoPodcastEpisode()` with no edits inside it.

Replace `handlePlaybackEnded` with:

```kotlin
fun handlePlaybackEnded() {
  Log.d(tag, "handlePlaybackEnded")
  when (
    playbackEndAction(
      hasQueueItem = playbackQueue.peek() != null,
      isPodcastEpisode = currentPlaybackSession?.isPodcastEpisode == true,
      isAndroidAuto = isAndroidAuto
    )
  ) {
    PlaybackEndAction.Stop -> return
    PlaybackEndAction.PodcastAutoAdvance -> playNextAndroidAutoPodcastEpisode()
    PlaybackEndAction.StartNext -> playNextQueuedItem()
  }
}
```

```kotlin
private fun playNextQueuedItem() {
  val next = playbackQueue.peek() ?: return
  val blockMeteredRemote =
    !isUnmeteredNetwork &&
      DeviceManager.deviceData.deviceSettings?.streamingUsingCellular ==
        com.audiobookshelf.app.data.StreamingUsingCellularSetting.NEVER
  if (!shouldStartQueuedItem(next.libraryItemId, blockMeteredRemote)) {
    Log.i(tag, "Auto-advance skipped for $next: cellular streaming is NEVER on a metered network")
    playbackQueue.clear()
    return
  }
  playbackQueue.poll()
  val generation = autoAdvanceGeneration
  val startNext = {
    if (generation != autoAdvanceGeneration) {
      Log.d(tag, "Auto-advance cancelled before the next item started")
    } else {
      Handler(Looper.getMainLooper()).post {
        if (generation != autoAdvanceGeneration) return@post
        PlayerListener.lazyIsPlaying = false
        startQueuedPlayback(next)
      }
    }
  }
  if (mediaProgressSyncer.listeningTimerRunning) {
    mediaProgressSyncer.finished { startNext() }
  } else {
    // finished() returns without calling its callback when the timer is not running
    startNext()
  }
}
```

`startQueuedPlayback` copies the local-vs-server split in `AbsAudioPlayer.prepareLibraryItem` (lines 226-291):

- Local id: `DeviceManager.dbManager.getLocalLibraryItem`. If `episodeId` is set, find that episode on the podcast. Missing item or episode: log and `playbackQueue.clear()`, return. Otherwise `getPlaybackSession(episode, getDeviceInfo())` and `preparePlayer(session, true, mediaManager.getSavedPlaybackRate())`.
- Server id: `apiHandler.playLibraryItem(libraryItemId, episodeId, getPlayItemRequestPayload(false))`. Null session: log and `playbackQueue.clear()`. Otherwise `preparePlayer(session, true, mediaManager.getSavedPlaybackRate())` on the main looper.
- No `startTime` override. The new session starts at the server's saved position (or 0). `preparePlayer` already restarts a session that is within 5 seconds of its duration (lines 441-444).

`mediaProgressSyncer.finished` syncs the ended item at its duration before the callback, which is what marks it finished. That is the existing podcast behavior. Do not add a second progress API.

- [ ] **Step 4: Compile**

```powershell
cd D:\GitHub\audiobookshelf-app\android
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests com.audiobookshelf.app.player.PlaybackQueueTest
```

Expected: compile succeeds and the Task 2 tests still pass. There is no new unit test in this task.

- [ ] **Step 5: Commit**

```powershell
git add android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationService.kt android/app/src/main/java/com/audiobookshelf/app/plugins/AbsAudioPlayer.kt
git commit -m "Start the next queued library item when Android playback ends."
```

---

### Task 4: Send the queue from playlist, collection, and series

**Files:**
- Modify: `pages/playlist/_id.vue`
- Modify: `components/tables/playlist/ItemTableRow.vue`
- Modify: `pages/collection/_id.vue`
- Modify: `components/tables/collection/BookTableRow.vue`
- Modify: `mixins/bookshelfCardsHelpers.js`
- Modify: `components/cards/LazyBookCard.vue`
- Modify: `components/cards/LazyListBookCard.vue`
- Modify: `pages/item/_id/index.vue`
- Modify: `components/app/AudioPlayerContainer.vue`

**Interfaces:**
- Consumes: `queueAfter` from Task 1
- Produces: `play-item` payload may include
  - `queue`: array of `{ libraryItemId, episodeId }` (playlist and collection)
  - `seriesId` and `seriesLibraryId`: set only from the series bookshelf (item page and list card)
  - `AudioPlayerContainer` always passes `queue` (possibly `[]`) into `prepareLibraryItem`

Playlist and collection already have the full ordered list in memory. Series books are paged and not sorted, so the container fetches them at play time with `sort=sequence`.

- [ ] **Step 1: Playlist header Play**

In `pages/playlist/_id.vue`, import `queueAfter` from `@/utils/playbackQueue`. Add methods:

```javascript
queueEntriesFromPlaylist() {
  return this.playableItems.map((item) => ({
    libraryItemId: item.localLibraryItem ? item.localLibraryItem.id : item.libraryItemId,
    episodeId: item.localLibraryItem ? item.localEpisode?.id || null : item.episodeId || null,
    finished: !!this.$store.getters['user/getUserMediaProgress'](item.libraryItemId, item.episodeId)?.isFinished
  }))
},
playPayloadForPlaylistItem(item, queue) {
  const payload = item.localLibraryItem
    ? {
        libraryItemId: item.localLibraryItem.id,
        episodeId: item.localEpisode?.id,
        serverLibraryItemId: item.libraryItemId,
        serverEpisodeId: item.episodeId
      }
    : { libraryItemId: item.libraryItemId, episodeId: item.episodeId }
  payload.queue = queue
  return payload
},
emitPlaylistPlay(item) {
  const items = this.queueEntriesFromPlaylist()
  const startIndex = this.playableItems.indexOf(item)
  const queue = queueAfter(items, startIndex)
  this.mediaIdStartingPlayback = item.episodeId || item.libraryItemId
  this.$store.commit('setPlayerIsStartingPlayback', this.mediaIdStartingPlayback)
  this.$eventBus.$emit('play-item', this.playPayloadForPlaylistItem(item, queue))
}
```

`playNextItem` becomes: find the first `playableItems` entry whose `queueEntriesFromPlaylist()` twin is not finished (same `!isFinished` test as today). If none, return. Otherwise `this.emitPlaylistPlay(thatItem)`.

- [ ] **Step 2: Playlist row**

`PlaylistItemsTable.vue` renders `items` (every playlist item, including ones `playableItems` drops) via `tables-playlist-item-table-row` and does not pass an index. Do not key the queue off that `v-for` index.

In `ItemTableRow.playClick`, keep the pause branch (`pause-item`). Replace the two `play-item` emits with:

```javascript
this.$emit('playFromHere')
```

In `PlaylistItemsTable.vue` add `@playFromHere="$emit('playFromHere', item)"` on the row.

In `pages/playlist/_id.vue` the table is `<tables-playlist-items-table :items="playlistItems" ...>`. Add `@playFromHere="playFromRow"`.

```javascript
playFromRow(item) {
  const playable = this.playableItems.find((entry) => entry.libraryItemId === item.libraryItemId && (entry.episodeId || null) === (item.episodeId || null))
  if (!playable || this.playerIsStartingPlayback) return
  this.emitPlaylistPlay(playable)
}
```

`playableItems` is a filter of the same `playlist.items` objects, so `indexOf` in `emitPlaylistPlay` finds the row.

- [ ] **Step 3: Collection, same pattern**

`pages/collection/_id.vue` `playableItems` are books. `episodeId` is always null. Local id is `book.localLibraryItem.id` when present, otherwise `book.id`. Finished uses `getUserMediaProgress(book.id)` with no episode, matching the current `playNextItem`.

Header Play calls the collection equivalent of `emitPlaylistPlay` for the first unfinished playable book.

`CollectionBooksTable.vue` renders `booksCopy`, a same-order shallow copy of `books` (`init()`, line 59). `BookTableRow` emits `play-item` itself (`playClick`, lines 92-111). Keep its pause branch. Replace the play emits with `this.$emit('playFromHere')`. The table re-emits `@playFromHere="$emit('playFromHere', book)"`. The collection page handles that with `playableItems.find((book) => book.id === emitted.id)` and `queueAfter`. Queue order is `playableItems` order, which follows `collection.books`.

- [ ] **Step 4: Series context on the cards**

In `mixins/bookshelfCardsHelpers.js` `mountEntityCard`, when `this.entityName === 'series-books'`:

```javascript
props.seriesId = this.seriesId
```

`LazyBookshelf` already has prop `seriesId`. The mixin runs on that component, so `this.seriesId` is in scope. Do not set `seriesId` for any other entity name.

`LazyBookCard.clickCard`: when `this.seriesId` is set, navigate to `` `/item/${this.libraryItemId}?seriesId=${this.seriesId}` `` (and keep the existing `localLibraryItemId` query when that branch applies, plus `seriesId`). Add prop `seriesId: { type: String, default: null }`.

`LazyListBookCard.clickCard`: same query change.

`LazyListBookCard.play`: when `this.seriesId` is set, add `seriesId: this.seriesId` and `seriesLibraryId: this.libraryItem.libraryId` to the existing `play-item` payload. Do not fetch inside the card. The card is constructed with `new ComponentClass` outside the Nuxt tree.

- [ ] **Step 5: Item page forwards `seriesId`**

In `pages/item/_id/index.vue` `play`, audiobook branch only (the `else` at line 570). When `this.$route.query.seriesId` is a non-empty string, add to the emitted payload:

```javascript
seriesId: this.$route.query.seriesId,
seriesLibraryId: this.libraryItem.libraryId
```

Do not add it for podcasts. Podcast episode order is unchanged.

- [ ] **Step 6: Container resolves the queue and passes it through**

In `AudioPlayerContainer.playLibraryItem`, after the existing early returns (permission denial, cast cancel, already streaming):

```javascript
let queue = Array.isArray(payload.queue) ? payload.queue : []
if (payload.seriesId && payload.seriesLibraryId) {
  queue = await this.buildSeriesQueue(payload.seriesId, payload.seriesLibraryId, payload.serverLibraryItemId || payload.libraryItemId)
}
preparePayload.queue = queue
```

`buildSeriesQueue(seriesId, libraryId, startedServerOrLocalId)`:

1. Page `/api/libraries/${libraryId}/items?filter=series.${this.$encode(seriesId)}&sort=sequence&desc=0&minified=1&limit=100&page=${page}` until `results.length` reaches `total` or a request fails.
2. On failure, return `[]` and still play the requested item.
3. Keep results with `(book.media?.numTracks || 0) > 0`. Minified books expose `numTracks`, not `tracks` (`Book.toOldJSONMinified` on the server).
4. For each book, `finished` is `!!this.$store.getters['user/getUserMediaProgress'](book.id)?.isFinished`.
5. `libraryItemId` is `` `local_${book.id}` `` when `await this.$db.getLocalLibraryItem(`local_${book.id}`)` returns an item, otherwise `book.id`. `episodeId` is null.
6. Find `startIndex` by comparing the started id to both `book.id` and `` `local_${book.id}` ``. If it is missing, return `[]`.
7. Return `queueAfter(items, startIndex)`.

`playServerLibraryItemAndCast` is left unchanged, so a cast clears the queue on the native side (missing `queue` argument).

The already-streaming short-circuit stays above this work, so seeking inside the current item does not replace the queue.

- [ ] **Step 7: Run the queue unit test again**

```powershell
cd D:\GitHub\audiobookshelf-app
node --test utils/playbackQueue.test.js
```

Expected: still 5 passed. The Vue files are not unit-tested; there is no Vue test runner in this repo. Manual checks are in the next section.

- [ ] **Step 8: Commit**

```powershell
git add pages/playlist/_id.vue pages/collection/_id.vue pages/item/_id/index.vue components/tables/playlist/ItemTableRow.vue components/tables/playlist/PlaylistItemsTable.vue components/tables/collection/BookTableRow.vue components/tables/collection/CollectionBooksTable.vue mixins/bookshelfCardsHelpers.js components/cards/LazyBookCard.vue components/cards/LazyListBookCard.vue components/app/AudioPlayerContainer.vue
git commit -m "Pass the remaining playlist, collection, or series items when playback starts."
```

---

## Verification

### Unit tests (required before calling the work done)

PowerShell:

```powershell
cd D:\GitHub\audiobookshelf-app
node --test utils/playbackQueue.test.js
cd android
.\gradlew.bat :app:testDebugUnitTest --tests com.audiobookshelf.app.player.PlaybackQueueTest
```

Expected: both commands exit 0. The Node file reports 5 passed. The Gradle file reports the 7 tests in `PlaybackQueueTest`.

### Manual checks on an Android device

Jason's machine is Windows 11, PowerShell, repos under `D:\GitHub`. Use two or three short audiobooks so you can seek to the end instead of listening through.

```powershell
cd D:\GitHub\audiobookshelf-app
npm ci
npm run sync
npx cap open android
```

Install the debug build on a device or emulator that is logged into an Audiobookshelf server. `logcat` filter: `PlayerNotificationService|AbsAudioPlayer|PlayerListener`.

1. **Playlist, in order, screen off.** Put books A, B, C in a playlist in that order, all unfinished. Open the playlist, press Play (starts A). Seek to the last few seconds of A. Lock the phone. B starts on its own. Repeat for C. After C, playback stops and does not return to A. Confirm with the notification title and logcat `handlePlaybackEnded` / `preparePlayer`.
2. **Playlist row starts in the middle.** Play B from its row. A does not play. C plays when B ends. C is the last, so playback then stops.
3. **Finished item in the middle.** Mark B finished. Header Play starts A. When A ends, C starts and B does not. Then tap B's row: B plays even though it is finished, and C follows if C is unfinished.
4. **All finished.** Header Play does nothing.
5. **Collection.** Repeat checks 1 and 3 on a collection. Order matches the collection page.
6. **Series.** Open a series whose sequence is 1, 2, 3. Open book 2 from the series (grid and, separately, list view). Press Play on the item page. When it ends, book 3 starts. Book 1 does not. Open book 2 from the library instead of the series: when it ends, nothing follows.
7. **Series with a finished book and a download.** Mark book 2 finished, download book 3, leave book 4 on the server. Play book 1 from the series. Book 2 is skipped. Book 3 plays from local storage (airplane mode after book 1 has ended and book 3 has started, or log `Playing local item`). Book 4 streams.
8. **Podcast playlist vs podcast show.** A playlist that contains episodes plays the next playlist row, not the next episode by publish date. A podcast started from the podcast page still does not advance on the phone. On Android Auto, with no queue, the next unfinished episode of that podcast still starts.
9. **Pause, stop, sleep timer.** Start A with B queued. Pause. Wait past the end of where A would have finished. B does not start. Resume, set a sleep timer for 1 minute. When it fires, audio pauses and B does not start. Swipe the notification away. Seek is no longer possible and a later end event does not start B.
10. **Close during the handoff.** Seek A to the end and immediately close the player. B does not start (generation counter).
11. **Single book.** Play a book from the library that is also in a series, without going through the series page. It ends as it does today.
12. **Android Auto.** Start the playlist on the phone, then connect Android Auto (or the Desktop Head Unit). Let the current item reach the end. The next playlist item starts. Play one book from the Android Auto library list (not from the in-app playlist). It does not continue into another book.
13. **Cellular NEVER.** Set streaming on cellular to Never. Download B, leave C online. Start A on Wi-Fi with the queue, switch the device to cellular, seek A to the end. B (local) starts. Seek B to the end. C does not start.

Use a server you already have. Do not point any of this at `advplyr/audiobookshelf` as a pull request or an issue.

---

## Success criteria

- `node --test utils/playbackQueue.test.js` passes.
- `:app:testDebugUnitTest --tests com.audiobookshelf.app.player.PlaybackQueueTest` passes.
- Manual checks 1, 3, 6, 9, and 11 pass on a device. Those cover order, finished items, series context, pause/sleep, and "not every book".
- A book that was not started from a playlist, collection, or series still ends without starting another book.
- Diff does not touch `ios/`, `plugins/capacitor/AbsAudioPlayer.js`, or any server repo.

## Out of scope

- iOS auto-advance.
- Web player auto-advance (`plugins/capacitor/AbsAudioPlayer.js`).
- A user setting to turn this off.
- Next/previous book buttons on the notification or Android Auto.
- Persisting the queue across process death.
- Changing the series bookshelf's display sort.
- Android Auto browse menus for series, playlists, or collections.
- Any change to `advplyr/audiobookshelf`.
