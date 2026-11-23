# Progress bar follows the finger immediately

Status: plan only, not started

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In the full-screen player, the progress handle and the time label follow the finger on the first movement. Playback seeks once, when the finger lifts.

**Architecture:** Drag-to-seek already exists. The handle is the touch target. `touchmove` already turns a horizontal delta into a time and redraws the bar locally. `touchend` already calls `seek`. The bug is that `touchstartCursor` does not arm that drag until `await this.$hapticsImpact()` resolves, and `touchmove` drops every event while the flag is false. Extract the existing time math into a pure function and arm the drag synchronously. Leave the native seek on release.

**Tech Stack:** Nuxt 2 / Vue 2, Node's built-in test runner (`node --test`). No Kotlin, Swift, or server change.

## Global Constraints

- Plan only until a later session implements it. Do not start the code in the session that added this file.
- Karpathy guidelines (https://github.com/multica-ai/andrej-karpathy-skills/blob/main/skills/karpathy-guidelines/SKILL.md): think before coding, simplicity first, surgical changes, goal-driven verification.
- This repo is Jason's fork `rgeldr/audiobookshelf-app`. Do not open a PR or an issue against `advplyr/audiobookshelf-app` or `advplyr/audiobookshelf`.
- Base the work on `cursor/auto-advance-playlists-plan-fc06` (PR #1). Do not retarget that PR and do not change auto-advance behavior.
- Touch only `utils/dragSeek.js`, `utils/dragSeek.test.mjs`, the `test` script in `package.json`, and the drag methods in `components/app/AudioPlayer.vue`.
- Do not add Jest, Vitest, or a Vue test harness. The DOM writes that move the handle and the label stay untested here. They already run from `updateTrack` and `updateTimestamp`.
- Do not add tap-to-seek, a larger hit target, a second scrubber, live seeking while the finger is down, or a cast-preview channel.

## Assumptions

1. Jason can already drag the handle. The defect is the pause between the press and the first movement of the handle and the time label.
2. Seeking on finger-up is the behavior to keep. The audio position stays put until release.
3. Chapter-track mode keeps today's mapping: the main bar is the current chapter, and the drag is clamped to that chapter. The thin total bar stays display-only.
4. A tap on the handle that does not move still does not seek. A press on the bar away from the handle still does nothing.
5. The same Vue file runs in the Android WebView, iOS, and the web build. The fix belongs there. Do not add an Android-only branch and do not edit Swift or Kotlin.

---

## Root cause

The handle starts a drag only after a native haptics call finishes. Until then, finger movement is discarded.

```654:663:components/app/AudioPlayer.vue
    async touchstartCursor(e) {
      if (!e || !e.touches || !this.$refs.track || !this.showFullscreen || this.playerSettings.lockUi) return

      await this.$hapticsImpact()
      this.isDraggingCursor = true
      this.draggingTouchStartX = e.touches[0].pageX
      this.draggingTouchStartTime = this.currentTime
      this.draggingCurrentTime = this.currentTime
      this.updateTrack()
    },
```

`touchmove` is already listening on `document.body` (mounted at lines 1003-1005). It returns immediately while the flag is false, so those events are not applied later:

```744:745:components/app/AudioPlayer.vue
    touchmove(e) {
      if (!this.isDraggingCursor || !e.touches) return
```

`$hapticsImpact` returns the Capacitor promise (`plugins/haptics.js` lines 54-61). `@capacitor/haptics` `HapticsPlugin.impact` calls `Vibrator.vibrate(...)` and only then `call.resolve()`. The default feedback is `LIGHT` (`store/globals.js` line 15), whose waveform is a 50ms one-shot, but the JS `await` waits on the plugin call itself, including the vibrator binder call, not on a timer inside this app. While that promise is pending:

- the handle stays at the playback position
- the time label stays at the playback position
- if the finger lifts before the promise resolves, `touchend` still sees `isDraggingCursor === false`, so it does not seek, and the late assignment can leave the flag true after the gesture is over

There is no hold timer, no pixel threshold, and no "wait until we know the direction" step in this component. The `await` is the wait.

### Checked and not the cause

| Suspect | What the code does |
| --- | --- |
| Long-press / hold timer | None in `AudioPlayer.vue`. The `touchDuration > 1200` check (lines 730-732) runs only on `touchend`, and only in the branch where `isDraggingCursor` is false. It does not gate the drag. `assets/app.css` sets `-webkit-touch-callout: none` and `user-select: none` on `*`. |
| Swipe-down to collapse deciding direction during the move | `touchstart` (lines 699-712) only stores `touchStartY` and `touchStartTime`. `touchmove` does not read them. The collapse decision is on `touchend` (lines 734-737) and is skipped when a drag is armed (lines 724-728). |
| Passive `touchmove` listener | `document.body.addEventListener('touchmove', this.touchmove)` (line 1005) is passive by Chrome's default for `body`. The callback still runs. Passive only blocks `preventDefault`. It does not delay the callback. Do not flip this listener to non-passive. It is attached for the whole player lifetime. |
| Throttle / debounce | `touchmove` writes the handle on every event. Nothing wraps it in a timer or `requestAnimationFrame`. |
| Native seek or `getCurrentTime` round-trip before the redraw | `AbsAudioPlayer.seek` runs from `seek()` (lines 633-644), and `seek()` runs from `touchend` (line 726), not from `touchmove`. The 1s poll (`startPlayInterval`, lines 684-691) updates `currentTime` from the player. During a drag, `updateTrack` and `updateTimestamp` prefer `draggingCurrentTime` (lines 572 and 602). The preview does not wait on ExoPlayer or Cast. |
| CSS transition on the fill or the handle | The fill and the cursor get `style.width` and `style.left` in `updateTrack` (lines 616-625). Neither element has a transition class. `#playerTrack` animates `margin` only for 0.15s (lines 1065-1067). `#playerContent` has Tailwind `transition-all`, which transitions that element's own properties, not a child's `width` or `left`. `assets/transitions.css` is Vue enter/leave only. |

The cursor is the only touch target (`@touchstart="touchstartCursor"` on the 28px handle, line 118). The track has `@click.stop` and no touch handler (line 114). `overflow-hidden` on the 6px track was added in `e0747e3f` to clip the fill; it also clips hit testing to that strip. Jason can already start a drag, so this plan does not change the hit target.

---

## Minimal proposed change

Arm the drag in `touchstartCursor` before haptics, and keep haptics off the await path. Keep the existing delta math, moved into a pure function so the tests lock it.

```
finger down on the handle
        |
        | synchronously: isDraggingCursor = true, record start X and start time
        | this.$hapticsImpact() without await
        v
touchmove (already on document.body)
        |
        | dragSeekTime(...) -> draggingCurrentTime
        | updateTimestamp() + updateTrack()   // label and handle, local only
        v
finger up
        |
        | existing touchend: seek(draggingCurrentTime) once
        v
AbsAudioPlayer.seek  ->  PlayerNotificationService.seekPlayer
```

`touchstartCursor` must not be `async`. An `await` before the flag, even of a fast promise, puts arming on the other side of the haptics call.

Claim the touch with `preventDefault()` on that same `touchstart`, before the function returns. Vue's listener on the handle is not passive, so `preventDefault` there tells the WebView this touch is ours. Do not add a second listener and do not change the `body` `touchmove` registration.

---

## Edge cases

| Case | Behavior |
| --- | --- |
| Chapter track on | Same as today. `dragBounds` uses the current chapter's start and end. The drag cannot leave the chapter. The main label is chapter-relative because `updateTimestamp` already subtracts `currentChapter.start` (lines 572-580). The total bar, when shown (lines 28-42), already follows `draggingCurrentTime` through `currentTimePretty` (lines 366-371) and stays non-interactive. |
| Chapter track on, no current chapter | Same as today: `dragBounds` uses `0` through `totalDuration`. |
| Chapter track off | The bar is the whole book. Clamp to `0` and `totalDuration`. |
| Tap on the handle | `draggingCurrentTime` stays `currentTime`. `touchend` does not call `seek` (line 725). Player does not collapse, because the drag flag is set. |
| Press on the bar, missing the handle | Unchanged. No drag, no seek. Do not add tap-to-seek. |
| Swipe down on the cover or the empty player | `isDraggingCursor` is false. A downward move of more than 100px still collapses (lines 734-737). A touch longer than 1200ms still does not collapse. |
| Swipe that starts on the handle | This is a drag. It seeks on release and does not collapse. That is the existing `if (this.isDraggingCursor)` split. Arming the flag on press makes the split reliable when haptics is slow. |
| Very long books | Time per pixel is `duration / trackWidth`. A 30-hour book on a ~360px bar is hundreds of seconds per pixel. That is the bar's geometry. `dragSeekTime` must not round to a whole second. `seek()` already passes the fractional value through (lines 643-644). Native code truncates to milliseconds in `seekPlayer`; leave that alone. |
| Live time label | Already implemented. `updateTimestamp` writes the handle's label from `draggingCurrentTime`. `timeRemainingPretty` / `currentTimePretty` re-render from the same value. No new label element. The fix is that the first `touchmove` is allowed to call them. |
| `trackWidth` is 0 | `dragSeekTime` returns the clamped start time. Do not divide by zero and do not seek to `NaN`. |
| Auto-advance queue | `seek()` does not call `prepareLibraryItem` and does not clear `PlaybackQueue`. A release before the end leaves the queue in place. A release that lands on the end still goes through `seekPlayer` (lines 1070-1089) and can still emit `STATE_ENDED`. Do not special-case the queue. |
| Cast | The on-screen handle is local during the drag. On release, the same `seek()` path reaches `CastPlayer.seekTo` (`CastPlayer.kt` lines 654-662). Do not send cast seeks on each `touchmove`. |
| `lockUi` or minimized player | `touchstartCursor` still returns immediately (line 655). No drag. |
| Haptics off, slow, or rejected | `plugins/haptics.js` returns `undefined` when feedback is `OFF`. Otherwise it returns a promise. Call it and do not await it. A slow or rejected vibration must not block the flag. |

---

## File structure

| File | Responsibility |
| --- | --- |
| Create `utils/dragSeek.js` | `dragBounds` and `dragSeekTime`. No DOM, no haptics. |
| Create `utils/dragSeek.test.mjs` | Node tests. Write this first. |
| Modify `package.json` | Append the new test file to the `test` script. |
| Modify `components/app/AudioPlayer.vue` | Arm the drag synchronously. Call the pure functions from `touchmove`. |

No other files.

---

### Task 1: Drag time math

**Files:**
- Create: `utils/dragSeek.test.mjs`
- Create: `utils/dragSeek.js`
- Modify: `package.json` script `test`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `dragBounds(useChapterTrack, chapter, totalDuration) -> { duration, minTime, maxTime }`
    - When `useChapterTrack` is true and `chapter` is an object, `duration` is `chapter.end - chapter.start`, `minTime` is `chapter.start`, `maxTime` is `chapter.start + duration`.
    - Otherwise `duration` is `totalDuration`, `minTime` is `0`, `maxTime` is `totalDuration`.
  - `dragSeekTime({ startTime, startX, pageX, trackWidth, duration, minTime, maxTime }) -> number`
    - `startTime + (duration / trackWidth) * (pageX - startX)`, then clamped to `[minTime, maxTime]`.
    - When `trackWidth` is not `> 0` or `duration` is not `> 0`, return `startTime` clamped to that range.
    - No pixel dead zone. No rounding.

- [ ] **Step 1: Write the failing test**

`utils/dragSeek.test.mjs`:

```javascript
import test from 'node:test'
import assert from 'node:assert/strict'
import { dragBounds, dragSeekTime } from './dragSeek.js'

const chapter = { start: 100, end: 130 }

test('whole-book bounds run from zero to the duration', () => {
  assert.deepEqual(dragBounds(false, chapter, 1000), { duration: 1000, minTime: 0, maxTime: 1000 })
  assert.deepEqual(dragBounds(true, null, 1000), { duration: 1000, minTime: 0, maxTime: 1000 })
})

test('chapter bounds are the current chapter only', () => {
  assert.deepEqual(dragBounds(true, chapter, 1000), { duration: 30, minTime: 100, maxTime: 130 })
})

test('a sub-pixel move changes time with no dead zone and no whole-second rounding', () => {
  const time = dragSeekTime({
    startTime: 10,
    startX: 0,
    pageX: 0.01,
    trackWidth: 300,
    duration: 100,
    minTime: 0,
    maxTime: 100
  })
  assert.ok(Math.abs(time - (10 + (100 / 300) * 0.01)) < 1e-9)
  assert.notEqual(time, 10)
  assert.notEqual(time, 11)
})

test('dragging left and right clamps to the book', () => {
  const bounds = dragBounds(false, null, 50)
  assert.equal(dragSeekTime({ startTime: 10, startX: 100, pageX: 0, trackWidth: 100, ...bounds }), 0)
  assert.equal(dragSeekTime({ startTime: 40, startX: 0, pageX: 200, trackWidth: 100, ...bounds }), 50)
})

test('chapter mode cannot seek into the next chapter', () => {
  const bounds = dragBounds(true, chapter, 1000)
  const time = dragSeekTime({ startTime: 110, startX: 0, pageX: 500, trackWidth: 100, ...bounds })
  assert.equal(time, 130)
})

test('a zero-width track keeps the start time', () => {
  assert.equal(
    dragSeekTime({ startTime: 12.5, startX: 0, pageX: 40, trackWidth: 0, duration: 100, minTime: 0, maxTime: 100 }),
    12.5
  )
})

test('a long book keeps the fractional second a partial pixel produces', () => {
  const duration = 30 * 60 * 60
  const time = dragSeekTime({
    startTime: 0,
    startX: 0,
    pageX: 0.5,
    trackWidth: 360,
    duration,
    minTime: 0,
    maxTime: duration
  })
  assert.ok(Math.abs(time - duration / 360 / 2) < 1e-6)
  assert.ok(!Number.isInteger(time))
})
```

Add the file to `package.json`:

```json
"test": "node --test utils/playbackQueue.test.mjs utils/dragSeek.test.mjs"
```

- [ ] **Step 2: Run the test and confirm it fails**

PowerShell, from `D:\GitHub\audiobookshelf-app`:

```powershell
node --test utils/dragSeek.test.mjs
```

Expected: fail because `./dragSeek.js` cannot be loaded. The playback-queue tests are not part of this failure.

- [ ] **Step 3: Implement the functions**

`utils/dragSeek.js`:

```javascript
export function dragBounds(useChapterTrack, chapter, totalDuration) {
  if (useChapterTrack && chapter) {
    const duration = chapter.end - chapter.start
    return { duration, minTime: chapter.start, maxTime: chapter.start + duration }
  }
  return { duration: totalDuration, minTime: 0, maxTime: totalDuration }
}

export function dragSeekTime({ startTime, startX, pageX, trackWidth, duration, minTime, maxTime }) {
  const clamp = (value) => Math.min(maxTime, Math.max(minTime, value))
  if (!(trackWidth > 0) || !(duration > 0)) return clamp(startTime)
  return clamp(startTime + (duration / trackWidth) * (pageX - startX))
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

```powershell
npm test
```

Expected: the new file's 7 tests pass, and `utils/playbackQueue.test.mjs` still passes.

- [ ] **Step 5: Commit**

```powershell
git add utils/dragSeek.js utils/dragSeek.test.mjs package.json
git commit -m "Add drag-to-seek time math for the player progress bar."
```

---

### Task 2: Arm the drag before haptics

**Files:**
- Modify: `components/app/AudioPlayer.vue`

**Interfaces:**
- Consumes: `dragBounds` and `dragSeekTime` from Task 1
- Produces: a non-async `touchstartCursor` that sets `isDraggingCursor` before calling `$hapticsImpact`

The label and the handle already redraw from `draggingCurrentTime`. Do not add a preview element. Do not seek inside `touchmove`.

This task has no new unit test. There is no Vue test runner. The ordering is verified on a phone in the next section.

- [ ] **Step 1: Import the helpers**

Next to the other `@/utils` import in the script:

```javascript
import { dragBounds, dragSeekTime } from '@/utils/dragSeek'
```

- [ ] **Step 2: Replace `touchstartCursor`**

Replace the method at lines 654-663. It must not be `async` and it must not `await`.

```javascript
touchstartCursor(e) {
  if (!e || !e.touches || !this.$refs.track || !this.showFullscreen || this.playerSettings.lockUi) return
  if (e.cancelable) e.preventDefault()

  this.isDraggingCursor = true
  this.draggingTouchStartX = e.touches[0].pageX
  this.draggingTouchStartTime = this.currentTime
  this.draggingCurrentTime = this.currentTime
  this.updateTrack()
  this.$hapticsImpact()
},
```

`preventDefault` claims the touch so the WebView does not treat it as a scroll. The `body` swipe listener still records Y. `touchend` still prefers the drag flag over collapse.

- [ ] **Step 3: Use the helper in `touchmove`**

Replace the body of `touchmove` (lines 744-764). Leave the early return. Do not call `seek`.

```javascript
touchmove(e) {
  if (!this.isDraggingCursor || !e.touches) return

  const bounds = dragBounds(this.playerSettings.useChapterTrack, this.currentChapter, this.totalDuration)
  this.draggingCurrentTime = dragSeekTime({
    startTime: this.draggingTouchStartTime,
    startX: this.draggingTouchStartX,
    pageX: e.touches[0].pageX,
    trackWidth: this.$refs.track?.clientWidth || this.trackWidth,
    ...bounds
  })

  this.updateTimestamp()
  this.updateTrack()
},
```

Leave `touchend` as it is, including the seek-on-release check at lines 724-728.

- [ ] **Step 4: Run the unit tests again**

```powershell
npm test
```

Expected: same passing tests as Task 1. This task does not change `dragSeek.js`.

- [ ] **Step 5: Commit**

```powershell
git add components/app/AudioPlayer.vue
git commit -m "Follow the finger on the progress bar before haptics returns."
```

---

## Verification

### Unit tests (required before calling the work done)

PowerShell, from `D:\GitHub\audiobookshelf-app`:

```powershell
npm test
```

Expected: exit 0. `dragSeek.test.mjs` reports 7 passed. `playbackQueue.test.mjs` still passes.

### Build a debug APK on Windows 11

JDK 21. Repo checkout at `D:\GitHub\audiobookshelf-app`, on the branch that stacks on `cursor/auto-advance-playlists-plan-fc06`.

```powershell
cd D:\GitHub\audiobookshelf-app
java -version
npm test
npm run sync
cd android
.\gradlew.bat assembleDebug
```

`java -version` should report 21. `npm run sync` is `nuxt generate` and `npx cap sync`. The APK is `android\app\build\outputs\apk\debug\app-debug.apk`. Install that build on the Android device. The unit tests do not need Gradle. `assembleDebug` does not need a phone.

### Manual checks on the Android device

Use a book long enough that a short drag is an obvious time change, and a second book in a playlist for the queue check. Full-screen player. Chapter track off unless the step says otherwise.

1. **First movement.** Press the handle and drag immediately. The handle and the left time label move with the finger on that first movement. There is no pause while the phone vibrates. Audio keeps playing from the old position until the finger lifts, then it continues from the label's time.
2. **Tap.** Press the handle and release without moving. The time does not change. The player stays full screen.
3. **Miss the handle.** Press the bar away from the handle. Nothing seeks. This plan does not add tap-to-seek.
4. **Swipe to close.** Swipe down on the cover. The player collapses. Swipe down starting on the handle: the bar tracks the horizontal movement, and the player does not collapse.
5. **Chapter track.** Turn Chapter Track on in the player menu. Drag the main bar. The label stays inside the current chapter and follows the finger. Release seeks inside that chapter. If Total Track is also on, the thin upper bar moves with the dragged book time and does not start its own drag.
6. **Long book.** Drag a fraction of the bar. The label shows the new time immediately, including seconds. It does not snap to a whole minute and it does not wait for buffering.
7. **Jump buttons.** After a drag-release, the 10 second jump buttons still jump from the new position.
8. **Auto-advance.** Play book A from a playlist whose next row is book B. Drag inside A and release before the end. Let A finish (or jump to the end with the forward button, not by a second feature). B still starts. Dragging A to the end and releasing may start B, because a seek to the end already ends the item.
9. **Cast.** Start casting. Drag the handle. The on-screen label follows during the drag. The cast device changes position when the finger lifts, not during the drag.
10. **Lock.** Lock the player from the menu. The handle does not drag. Unlock. It drags again.

Use a server you already have. Do not point any of this at `advplyr/audiobookshelf` as a pull request or an issue.

---

## Success criteria

- `npm test` passes, including the 7 `dragSeek` tests.
- On a device, check 1 passes: the handle and the time label move on the first finger movement, and audio seeks on release.
- Checks 4, 5, and 8 pass: swipe-down on the cover still closes, chapter track stays inside the chapter, and a mid-book drag does not drop the auto-advance queue.
- Diff does not touch `android/`, `ios/`, `plugins/capacitor/AbsAudioPlayer.js`, or the auto-advance queue.

## Out of scope

- Tap-to-seek on the track.
- A larger handle or removing `overflow-hidden` from the track.
- Seeking on every `touchmove`, including casting on every move.
- Changing `seekPlayer`, Cast, or the playback queue.
- iOS-specific Swift. The shared Vue method is the whole fix.
- Rounding long-book scrubs to coarser steps.
- A Vue component test.
