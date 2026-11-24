# Playlist Download Button Implementation Plan

Status: implemented

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a download button on the playlist detail page that downloads every still-needed playlist item for offline listening, including podcast episodes, through the app's existing `AbsDownloader.downloadLibraryItem` flow.

**Architecture:** Collection detail pages have no download control. The bulk download that already exists is the series bookshelf (`pages/bookshelf/series/_id.vue` plus the toolbar icon in `components/home/BookshelfToolbar.vue`). Copy that sequence onto `pages/playlist/_id.vue`: cellular check, Android folder choice, size confirmation, then one native download call per item. The decision "which rows still need a download, and how big are they" moves into a pure function in `utils/playlistDownload.js` so it can be unit-tested the same way as `utils/playbackQueue.js`. Native Android and iOS download code stays as it is. The playlist page is shared Vue, so the button shows on iOS too; do not change Swift.

**Tech Stack:** Nuxt 2 / Vue 2, Capacitor `Dialog` and `AbsDownloader`, existing `cellularPermissionHelpers` mixin, Node's built-in test runner (`node --test`). No new dependencies. No Kotlin change, so no new JUnit test.

## Global Constraints

- Karpathy guidelines (https://github.com/multica-ai/andrej-karpathy-skills/blob/main/skills/karpathy-guidelines/SKILL.md): simplest change, surgical edits, no speculative extras, assumptions stated below.
- Do not bump `package.json` `version`, `android/app/build.gradle` `versionCode` (121), or `versionName` (`0.14.2-beta`).
- Do not change Kotlin, Swift, ExoPlayer, `AbsDownloader.kt`, `DownloadItemManager.kt`, or the playback queue (`utils/playbackQueue.js`, `PlaybackQueue.kt`).
- Do not add a Vue/Jest/Vitest harness. This repo's unit tests are `node --test` files next to `utils/*.js`.
- Do not refactor the series page into a shared helper. Copy the small folder-and-confirm sequence. The series page has a bug (the "already downloaded" `alert` does not return, and the confirm dialog still opens). Do not copy that bug, and do not fix it on the series page.
- Do not add a collection download button, a per-row download icon, a playlist progress panel, an offline playlist cache, or translations for every locale file.
- New copy goes only in `strings/en-us.json`. `plugins/i18n.js` already falls back to `en-us` when another language is missing a key (`strings[key] || translations[defaultCode][key]`).
- One playlist is one media type. The server rejects a mix of books and episodes when a playlist is created (`PlaylistController.create`: if any item has `episodeId`, every item must be a podcast episode, and all items must belong to that playlist's library).
- After the feature is implemented, run `/review-bugbot` on the implementation PR and fix findings with tests. If that command is not available in the session, do the manual review in Task 4 and say so in the PR.
- Open this work only on `rgeldr/audiobookshelf-app`. Do not open a PR, issue, or comment on `advplyr/audiobookshelf-app` or `advplyr/audiobookshelf`.

## Assumptions

These are locked so implementation does not invent a second design. Jason can override them before implementation starts.

1. The button sits in the playlist title row, immediately before the existing Play button, because that row is the action row on both `pages/playlist/_id.vue` and `pages/collection/_id.vue`. The series download icon lives in the bookshelf toolbar, and the playlist page does not use that toolbar.
2. The button matches the item-page download button (`pages/item/_id/index.vue`): a `ui-btn`, icon only, `material-symbols` `download`. Primary when idle. Warning and `animate-pulse` with the `downloading` icon while any playlist item is already in `globals.itemDownloads`, or during the one-second double-tap guard. No text label, matching the item page.
3. The button is hidden when `user.permissions.download` is false, and when the playlist has no downloadable item (empty, or every row missing, invalid, or without audio/ebook). It stays visible when every downloadable item is already on the device, so a tap can tell the user that.
4. "Already downloaded" uses the local item the playlist page already attaches in `asyncData`: a book with `localLibraryItem`, or an episode with `localEpisode`. An episode whose podcast has a local library item but not that episode still needs a download.
5. "In progress" means `globals/getDownloadItem(libraryItemId, episodeId)` returns a row. That includes a partial download still in the queue (`itemProgress` < 1) and a finished download that is still processing (`itemProgress` == 1). Those rows are not started again. Native already returns `"Download already started for this media entity"` for an active id (`AbsDownloader.kt` `downloadLibraryItem`). Skipping them avoids one error toast per row.
6. A book download is the existing whole-item download: audio, ebook, and cover, because `downloadLibraryItem` without `episodeId` already does that. An ebook-only book is included. An episode download passes `episodeId` and downloads that episode's audio plus the podcast cover, which native already does.
7. The confirm dialog's file count and byte total count only items that will actually start. Books use `libraryItem.size` and `libraryItem.numFiles` (the same fields the series search uses). Episodes use `episode.size` (one audio file). Do not use the podcast's `libraryItem.size`; on a playlist episode that field is the whole podcast (`Playlist.toOldJSONExpanded` stores the episode's library item with `toOldJSONMinified()`).
8. There is no local playlist record. Opening a playlist while offline already fails the `GET /api/playlists/:id` and redirects to `/bookshelf/playlists`. Leave that path alone. If the page is already open and the device is offline, the native call returns `"Server request failed"` and the page toasts it, same as a single-item download.
9. Android folder choice is copied from the series page for the playlist's single media type (`book` or `podcast`). iOS passes no `localFolderId`; native defaults to `internal-${mediaType}`.
10. Cellular permission is checked once for the batch via `checkCellularPermission('download')`, before the folder picker and the confirm dialog.

## What exists today

### Collection page: no download button

`pages/collection/_id.vue` renders a cover, the collection name, a Play button, the description, and `tables-collection-books-table`. There is no download button, no `AbsDownloader` import, and no download store action. `components/tables/collection/BookTableRow.vue` only shows a `download_done` icon when `book.localLibraryItem` is set.

Collection-specific facts that the playlist button must not assume:

- A collection is books only (`collection.books`). It has no `episode` rows.
- The collection payload is `GET /api/collections/:id`. Playlist payload is `GET /api/playlists/:id` and each row is `{ libraryItemId, libraryItem, episodeId?, episode? }`.
- Books on a collection are expanded library items. Playlist books are expanded too (`libraryItem.toOldJSONExpanded()`, so `media.tracks`, `media.ebookFile`, `size`, and `numFiles` are present). Playlist episodes carry a minified podcast library item plus an expanded episode (`episode.audioFile`, `episode.size`).

### Series page: the bulk download to copy

The toolbar icon is only on the series bookshelf:

```16:16:components/home/BookshelfToolbar.vue
        <span v-if="seriesBookPage" class="material-symbols text-2xl px-2" @click="downloadSeries">download</span>
```

`downloadSeries` emits `download-series-click`. `pages/bookshelf/series/_id.vue` handles it:

1. Ignore the tap when `startingDownload` is already true.
2. `checkCellularPermission('download')` from `mixins/cellularPermissionHelpers.js`. `NEVER` toasts `ToastDownloadNotAllowedOnCellular` and stops. `ASK` opens `Dialog.confirm` with `MessageConfirmDownloadUsingCellular`. Any other network, or `ALWAYS`, continues. There is no separate offline check.
3. Set `startingDownload` for one second so a double tap does not open two dialogs. Haptic impact.
4. Pick an Android folder for media type `book`: no folders or only the internal folder uses internal storage; more than one folder opens `globals/showSelectLocalFolderModal` and resumes in the callback. iOS skips this.
5. Page through `GET /api/libraries/:libraryId/items?filter=series.<id>`. For each result, skip it when `$db.getLocalLibraryItem('local_' + id)` exists. Otherwise add `id` to the list and add `numFiles` and `size`.
6. If nothing is missing, `alert(MessageSeriesAlreadyDownloaded)` and then, because there is no `return`, still show the confirm dialog. The playlist page must return instead.
7. `Dialog.confirm` with `MessageSeriesDownloadConfirm` (Android, includes the folder name) or `MessageSeriesDownloadConfirmIos`.
8. On confirm, call `AbsDownloader.downloadLibraryItem({ libraryItemId, localFolderId })` once per id. Toast `downloadRes.error` when present.

There is no Vuex download action. Download state is:

- Permission: `store/user.js` getter `getUserCanDownload` (`user.permissions.download`).
- In-progress rows: `store/globals.js` `itemDownloads`, read with getter `getDownloadItem(libraryItemId, episodeId)`.
- Folder modal: mutation `globals/showSelectLocalFolderModal`.

The native call is `plugins/capacitor/AbsDownloader.js` `downloadLibraryItem`. Android (`android/app/src/main/java/com/audiobookshelf/app/plugins/AbsDownloader.kt`) retries an existing queue id, or loads the item from the server and enqueues it. A book with no files resolves `"No downloadable files found"`. A bad episode resolves `"Invalid podcast episode not found"`. A server failure resolves `"Server request failed"` (this is also what a missing download permission becomes, because the plugin does not inspect `permissions.download` itself). The item page hides the button when `getUserCanDownload` is false (`pages/item/_id/index.vue` `showDownload`). Do the same. Do not add a second permission check in Kotlin.

Episode downloads already pass `episodeId` (`pages/item/_id/_episode/index.vue` `startDownload`, and `components/tables/podcast/EpisodeRow.vue`). The playlist batch uses that same payload for episode rows and the book payload for book rows.

### Playlist page today

`pages/playlist/_id.vue` matches the collection layout: cover, title, Play button, description, table. `asyncData` attaches `localLibraryItem` and, for episodes, `localEpisode` when that episode is on disk. `playableItems` drops missing/invalid rows, episodes without `audioFile`, and books without tracks. The download button is a sibling of Play, not a change to that play filter. An ebook-only book is not playable and is still downloadable.

## File structure

| File | Responsibility |
| --- | --- |
| Create `utils/playlistDownload.js` | Pure selection, size totals, button visibility, and the no-op action (`confirm`, `in-progress`, `already-downloaded`). |
| Create `utils/playlistDownload.test.mjs` | Node tests for that module. Written first. |
| Modify `package.json` | Append the new test file to the `test` script only. Do not change `version`. |
| Modify `strings/en-us.json` | Three strings, next to the series download strings. |
| Modify `pages/playlist/_id.vue` | Button markup, and the click handler that calls the util plus the existing folder, dialog, and native APIs. |

No other files change.

## Out of scope

- A download button on collection pages.
- Fixing the series alert-without-return bug.
- Sharing folder-picker code with the series or item pages.
- Persisting playlists for offline open.
- Deduping rows. The server rejects a duplicate media item in one playlist.
- Adding the podcast cover's byte size to the episode total. Native still downloads the cover. The confirm total for episodes is the audio size only.
- Refetching the playlist after a download. The page listens for the existing `new-local-library-item` event and attaches that local row with `withLocalDownload`, the same signal the item page uses. The short gap before that event still matches the item page.
- Version bump. Kotlin tests. iOS native code.

---

### Task 1: Playlist download selection

**Files:**

- Create: `utils/playlistDownload.js`
- Create: `utils/playlistDownload.test.mjs`
- Modify: `package.json` script `test` (the string on line 15)

**Interfaces:**

- Consumes: nothing from later tasks. Inputs are plain objects shaped like playlist API rows plus the local fields `asyncData` already adds.
- Produces:
  - `playlistItemKey(item) -> string` — `libraryItemId` for a book, `libraryItemId:episodeId` for an episode.
  - `isDownloadablePlaylistItem(item) -> boolean`
  - `showPlaylistDownloadButton(userCanDownload, items) -> boolean`
  - `playlistDownloadPlan(items, inProgressKeys) -> { pending, skippedDownloaded, skippedInProgress, skippedUnusable, fileCount, totalBytes, mediaType }`
  - `pending` entries are `{ libraryItemId: string, episodeId: string|null, mediaType: string }` in playlist order.
  - `playlistDownloadAction(plan) -> 'confirm' | 'in-progress' | 'already-downloaded'`

- [ ] **Step 1: Write the failing test**

Create `utils/playlistDownload.test.mjs`:

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  playlistItemKey,
  isDownloadablePlaylistItem,
  showPlaylistDownloadButton,
  playlistDownloadPlan,
  playlistDownloadAction
} from './playlistDownload.js'

function book(id, extra = {}) {
  return {
    libraryItemId: id,
    libraryItem: {
      id,
      mediaType: 'book',
      isMissing: false,
      isInvalid: false,
      size: 1000,
      numFiles: 3,
      media: { tracks: [{}], ebookFile: null }
    },
    ...extra
  }
}

function episode(libraryItemId, episodeId, extra = {}) {
  return {
    libraryItemId,
    episodeId,
    episode: {
      id: episodeId,
      size: 500,
      audioFile: { ino: '1' }
    },
    libraryItem: {
      id: libraryItemId,
      mediaType: 'podcast',
      isMissing: false,
      isInvalid: false,
      size: 999999,
      numFiles: 40,
      media: {}
    },
    ...extra
  }
}

test('book and episode keys stay distinct for the same library item', () => {
  assert.equal(playlistItemKey(book('book-1')), 'book-1')
  assert.equal(playlistItemKey(episode('pod-1', 'ep-2')), 'pod-1:ep-2')
})

test('a book with tracks or an ebook is downloadable; a missing, invalid, or empty book is not', () => {
  assert.equal(isDownloadablePlaylistItem(book('book-1')), true)
  const ebookOnly = book('book-2')
  ebookOnly.libraryItem.media = { tracks: [], ebookFile: { ino: 'e' } }
  assert.equal(isDownloadablePlaylistItem(ebookOnly), true)
  const empty = book('book-3')
  empty.libraryItem.media = { tracks: [], ebookFile: null }
  assert.equal(isDownloadablePlaylistItem(empty), false)
  const missing = book('book-4')
  missing.libraryItem.isMissing = true
  assert.equal(isDownloadablePlaylistItem(missing), false)
  const invalid = book('book-5')
  invalid.libraryItem.isInvalid = true
  assert.equal(isDownloadablePlaylistItem(invalid), false)
})

test('an episode needs an audio file; a podcast local item without that episode is still downloadable', () => {
  assert.equal(isDownloadablePlaylistItem(episode('pod-1', 'ep-1')), true)
  const noAudio = episode('pod-1', 'ep-2')
  noAudio.episode.audioFile = null
  assert.equal(isDownloadablePlaylistItem(noAudio), false)
  const localPodcastOnly = episode('pod-1', 'ep-3', { localLibraryItem: { id: 'local_pod-1' } })
  assert.equal(isDownloadablePlaylistItem(localPodcastOnly), true)
})

test('the button is hidden without download permission or without a downloadable row', () => {
  assert.equal(showPlaylistDownloadButton(false, [book('book-1')]), false)
  assert.equal(showPlaylistDownloadButton(true, []), false)
  assert.equal(showPlaylistDownloadButton(true, null), false)
  const missing = book('book-1')
  missing.libraryItem.isMissing = true
  assert.equal(showPlaylistDownloadButton(true, [missing]), false)
  assert.equal(showPlaylistDownloadButton(true, [book('book-1', { localLibraryItem: { id: 'local_book-1' } })]), true)
})

test('pending books keep order and sum library item size and file count', () => {
  const plan = playlistDownloadPlan([book('a'), book('b', { libraryItem: { ...book('b').libraryItem, size: '2500', numFiles: '2' } })], [])
  assert.deepEqual(plan.pending, [
    { libraryItemId: 'a', episodeId: null, mediaType: 'book' },
    { libraryItemId: 'b', episodeId: null, mediaType: 'book' }
  ])
  assert.equal(plan.fileCount, 5)
  assert.equal(plan.totalBytes, 3500)
  assert.equal(plan.mediaType, 'book')
  assert.equal(playlistDownloadAction(plan), 'confirm')
})

test('a local book and an in-progress book are not pending and do not add to the total', () => {
  const items = [
    book('done', { localLibraryItem: { id: 'local_done' } }),
    book('busy'),
    book('need')
  ]
  const plan = playlistDownloadPlan(items, ['busy'])
  assert.deepEqual(plan.pending.map((row) => row.libraryItemId), ['need'])
  assert.equal(plan.skippedDownloaded, 1)
  assert.equal(plan.skippedInProgress, 1)
  assert.equal(plan.fileCount, 3)
  assert.equal(plan.totalBytes, 1000)
})

test('episode size is the episode, not the podcast, and one file', () => {
  const plan = playlistDownloadPlan([episode('pod-1', 'ep-1'), episode('pod-1', 'ep-2')], [])
  assert.deepEqual(plan.pending, [
    { libraryItemId: 'pod-1', episodeId: 'ep-1', mediaType: 'podcast' },
    { libraryItemId: 'pod-1', episodeId: 'ep-2', mediaType: 'podcast' }
  ])
  assert.equal(plan.totalBytes, 1000)
  assert.equal(plan.fileCount, 2)
  assert.equal(plan.mediaType, 'podcast')
})

test('a downloaded episode is skipped; a local podcast without the episode stays pending', () => {
  const items = [
    episode('pod-1', 'ep-1', { localLibraryItem: { id: 'local_pod-1' }, localEpisode: { id: 'local_ep-1' } }),
    episode('pod-1', 'ep-2', { localLibraryItem: { id: 'local_pod-1' } })
  ]
  const plan = playlistDownloadPlan(items, [])
  assert.deepEqual(plan.pending.map((row) => row.episodeId), ['ep-2'])
  assert.equal(plan.skippedDownloaded, 1)
  assert.equal(plan.totalBytes, 500)
})

test('an in-progress episode is skipped even when the podcast item is not local', () => {
  const plan = playlistDownloadPlan([episode('pod-1', 'ep-9')], ['pod-1:ep-9'])
  assert.deepEqual(plan.pending, [])
  assert.equal(plan.skippedInProgress, 1)
  assert.equal(plan.totalBytes, 0)
  assert.equal(playlistDownloadAction(plan), 'in-progress')
})

test('downloaded plus in-progress and nothing left to start is in-progress, not already-downloaded', () => {
  const items = [
    book('done', { localLibraryItem: { id: 'local_done' } }),
    episode('pod-1', 'ep-9')
  ]
  const plan = playlistDownloadPlan(items, ['pod-1:ep-9'])
  assert.deepEqual(plan.pending, [])
  assert.equal(plan.skippedDownloaded, 1)
  assert.equal(plan.skippedInProgress, 1)
  assert.equal(playlistDownloadAction(plan), 'in-progress')
})

test('missing and empty rows are unusable and an all-local playlist is already downloaded', () => {
  const missing = book('gone')
  missing.libraryItem.isMissing = true
  const plan = playlistDownloadPlan([missing, book('have', { localLibraryItem: { id: 'local_have' } })], [])
  assert.deepEqual(plan.pending, [])
  assert.equal(plan.skippedUnusable, 1)
  assert.equal(plan.skippedDownloaded, 1)
  assert.equal(playlistDownloadAction(plan), 'already-downloaded')
})

test('a null item list yields an empty already-downloaded plan', () => {
  const plan = playlistDownloadPlan(null, null)
  assert.deepEqual(plan.pending, [])
  assert.equal(plan.fileCount, 0)
  assert.equal(plan.totalBytes, 0)
  assert.equal(plan.mediaType, null)
  assert.equal(playlistDownloadAction(plan), 'already-downloaded')
})
```

In `package.json`, change the `test` script from:

```json
"test": "node --test utils/playbackQueue.test.mjs utils/dragSeek.test.mjs"
```

to:

```json
"test": "node --test utils/playbackQueue.test.mjs utils/dragSeek.test.mjs utils/playlistDownload.test.mjs"
```

Do not change any other `package.json` field.

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm test`

Expected: FAIL while loading `utils/playlistDownload.test.mjs`, with `Cannot find module` or `playlistItemKey is not defined` / a missing export. The existing playback-queue and drag-seek tests are not reached if node fails the file load. That failure is the red step. Do not change those other tests.

- [ ] **Step 3: Write the minimal implementation**

Create `utils/playlistDownload.js`:

```js
export function playlistItemKey(item) {
  const libraryItemId = item?.libraryItemId || item?.libraryItem?.id || ''
  const episodeId = item?.episodeId || item?.episode?.id || null
  return episodeId ? `${libraryItemId}:${episodeId}` : libraryItemId
}

export function isDownloadablePlaylistItem(item) {
  const libraryItem = item?.libraryItem
  if (!libraryItem || libraryItem.isMissing || libraryItem.isInvalid) return false
  if (item.episode || item.episodeId) return !!(item.episode && item.episode.audioFile)
  const media = libraryItem.media || {}
  const tracks = media.tracks || []
  return tracks.length > 0 || !!media.ebookFile
}

export function showPlaylistDownloadButton(userCanDownload, items) {
  if (!userCanDownload) return false
  return (Array.isArray(items) ? items : []).some(isDownloadablePlaylistItem)
}

function asCount(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number : 0
}

export function playlistDownloadPlan(items, inProgressKeys) {
  const list = Array.isArray(items) ? items : []
  const inProgress = inProgressKeys instanceof Set ? inProgressKeys : new Set(inProgressKeys || [])
  const pending = []
  let skippedDownloaded = 0
  let skippedInProgress = 0
  let skippedUnusable = 0
  let fileCount = 0
  let totalBytes = 0
  let mediaType = null

  for (const item of list) {
    if (!isDownloadablePlaylistItem(item)) {
      skippedUnusable++
      continue
    }
    const libraryItem = item.libraryItem
    const episodeId = item.episodeId || item.episode?.id || null
    if (!mediaType) mediaType = libraryItem.mediaType || (episodeId ? 'podcast' : 'book')
    const downloaded = episodeId ? !!item.localEpisode : !!item.localLibraryItem
    if (downloaded) {
      skippedDownloaded++
      continue
    }
    if (inProgress.has(playlistItemKey(item))) {
      skippedInProgress++
      continue
    }
    fileCount += episodeId ? 1 : asCount(libraryItem.numFiles)
    totalBytes += episodeId ? asCount(item.episode?.size ?? item.episode?.audioFile?.metadata?.size) : asCount(libraryItem.size)
    pending.push({
      libraryItemId: item.libraryItemId || libraryItem.id,
      episodeId: episodeId || null,
      mediaType: libraryItem.mediaType || mediaType
    })
  }

  return { pending, skippedDownloaded, skippedInProgress, skippedUnusable, fileCount, totalBytes, mediaType }
}

export function playlistDownloadAction(plan) {
  if (plan.pending.length) return 'confirm'
  if (plan.skippedInProgress > 0) return 'in-progress'
  return 'already-downloaded'
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npm test`

Expected: PASS for `utils/playbackQueue.test.mjs`, `utils/dragSeek.test.mjs`, and `utils/playlistDownload.test.mjs`.

- [ ] **Step 5: Commit**

```bash
git add utils/playlistDownload.js utils/playlistDownload.test.mjs package.json
git commit -m "Add playlist download selection tests and util."
```

---

### Task 2: Button and download click on the playlist page

**Files:**

- Modify: `strings/en-us.json` (insert immediately after `MessageSeriesDownloadConfirmIos`, around line 357)
- Modify: `pages/playlist/_id.vue`
- Test: `utils/playlistDownload.test.mjs` (no new cases; this task only calls the functions from Task 1)

**Interfaces:**

- Consumes: `showPlaylistDownloadButton`, `playlistDownloadPlan`, `playlistDownloadAction`, `playlistItemKey` from `utils/playlistDownload.js`.
- Produces: a download button on the playlist page and a `downloadClick` method. No new export.

The button's position and CSS are visual. This repo has no component test runner, and adding one is out of scope. Do not write a Vue test for markup. The selection behavior is already covered by Task 1. The Pixel checks in Task 3 cover the dialog, folder picker, and native calls.

- [ ] **Step 1: Add the four English strings**

In `strings/en-us.json`, after `MessageSeriesDownloadConfirmIos`:

```json
  "MessagePlaylistAlreadyDownloaded": "You have already downloaded every item in this playlist.",
  "MessagePlaylistDownloadConfirm": "Download missing {0} item(s) with {1} file(s), totaling {2} to folder {3}?",
  "MessagePlaylistDownloadConfirmIos": "Download missing {0} item(s) with {1} file(s), totaling {2}?",
  "MessagePlaylistDownloadsInProgress": "The remaining items in this playlist are already downloading.",
```

Keep the file's comma style. Do not edit the other `strings/*.json` files.

- [ ] **Step 2: Add the button before Play**

In `pages/playlist/_id.vue`, inside the title row, immediately before the Play `ui-btn` (the button that is `v-if="showPlayButton"`):

```html
          <ui-btn v-if="showDownloadButton" :color="playlistDownloadInProgress ? 'warning' : 'primary'" :padding-x="2" small class="flex items-center justify-center mx-1" @click="downloadClick">
            <span class="material-symbols text-2xl" :class="playlistDownloadInProgress || startingDownload ? 'animate-pulse' : ''">{{ playlistDownloadInProgress || startingDownload ? 'downloading' : 'download' }}</span>
          </ui-btn>
```

Leave the Play button's classes unchanged.

- [ ] **Step 3: Wire the click handler to the existing download flow**

Add imports and the mixin. Keep the existing `queueAfter` import.

```js
import { Dialog } from '@capacitor/dialog'
import { AbsDownloader } from '@/plugins/capacitor'
import cellularPermissionHelpers from '@/mixins/cellularPermissionHelpers'
import { queueAfter } from '@/utils/playbackQueue'
import { playlistDownloadAction, playlistDownloadPlan, playlistItemKey, showPlaylistDownloadButton } from '@/utils/playlistDownload'
```

Add `mixins: [cellularPermissionHelpers],` on the component, next to the other options.

Add `startingDownload: false` to `data()`.

Add these computed properties:

```js
    userCanDownload() {
      return this.$store.getters['user/getUserCanDownload']
    },
    downloadInProgressKeys() {
      const keys = []
      this.playlistItems.forEach((item) => {
        const episodeId = item.episodeId || item.episode?.id || null
        if (this.$store.getters['globals/getDownloadItem'](item.libraryItemId, episodeId)) keys.push(playlistItemKey(item))
      })
      return keys
    },
    downloadPlan() {
      return playlistDownloadPlan(this.playlistItems, this.downloadInProgressKeys)
    },
    showDownloadButton() {
      return showPlaylistDownloadButton(this.userCanDownload, this.playlistItems)
    },
    playlistDownloadInProgress() {
      return this.downloadPlan.skippedInProgress > 0
    },
    isIos() {
      return this.$platform === 'ios'
    }
```

Add these methods. `downloadClick` follows the series order: guard, cellular check, one-second guard, haptic, then plan. `chooseDownloadFolder` is the series folder block with the playlist media type. `confirmAndStartDownloads` is the series confirm block, and it returns on the two no-op actions instead of falling through.

```js
    async downloadClick() {
      if (this.startingDownload) return
      const hasPermission = await this.checkCellularPermission('download')
      if (!hasPermission) return

      this.startingDownload = true
      setTimeout(() => {
        this.startingDownload = false
      }, 1000)

      await this.$hapticsImpact()
      const plan = this.downloadPlan
      const action = playlistDownloadAction(plan)
      if (action === 'in-progress') {
        alert(this.$strings.MessagePlaylistDownloadsInProgress)
        return
      }
      if (action === 'already-downloaded') {
        alert(this.$strings.MessagePlaylistAlreadyDownloaded)
        return
      }
      if (this.isIos) {
        this.confirmAndStartDownloads(plan, null)
        return
      }
      this.chooseDownloadFolder(plan)
    },
    async chooseDownloadFolder(plan, selectedLocalFolder = null) {
      let localFolder = selectedLocalFolder
      const mediaType = plan.mediaType
      if (!localFolder) {
        const localFolders = (await this.$db.getLocalFolders()) || []
        const foldersWithMediaType = localFolders.filter((folder) => folder.mediaType == mediaType)
        const internalStorageFolder = foldersWithMediaType.find((folder) => folder.id === `internal-${mediaType}`)
        if (!foldersWithMediaType.length) {
          localFolder = {
            id: `internal-${mediaType}`,
            name: this.$strings.LabelInternalAppStorage,
            mediaType
          }
        } else if (foldersWithMediaType.length === 1 && internalStorageFolder) {
          localFolder = internalStorageFolder
        } else {
          this.$store.commit('globals/showSelectLocalFolderModal', {
            mediaType,
            callback: (folder) => {
              this.chooseDownloadFolder(plan, folder)
            }
          })
          return
        }
      }
      this.confirmAndStartDownloads(plan, localFolder)
    },
    async confirmAndStartDownloads(plan, localFolder) {
      let message = this.$getString('MessagePlaylistDownloadConfirmIos', [plan.pending.length, plan.fileCount, this.$bytesPretty(plan.totalBytes)])
      if (!this.isIos) {
        message = this.$getString('MessagePlaylistDownloadConfirm', [plan.pending.length, plan.fileCount, this.$bytesPretty(plan.totalBytes), localFolder.name])
      }
      const { value } = await Dialog.confirm({
        title: this.$strings.HeaderConfirm,
        message
      })
      if (!value) return
      for (let i = 0; i < plan.pending.length; i++) {
        this.startPlaylistItemDownload(localFolder, plan.pending[i])
      }
    },
    async startPlaylistItemDownload(localFolder, pendingItem) {
      const payload = {
        libraryItemId: pendingItem.libraryItemId
      }
      if (pendingItem.episodeId) payload.episodeId = pendingItem.episodeId
      if (localFolder) payload.localFolderId = localFolder.id
      const downloadRes = await AbsDownloader.downloadLibraryItem(payload)
      if (downloadRes && downloadRes.error) {
        const errorMsg = downloadRes.error || 'Unknown error'
        console.error('Download error', errorMsg)
        this.$toast.error(errorMsg)
      }
    }
```

Do not `await` inside the start loop. The series page fires every item and lets the native queue run them. Do the same.

Do not edit play, queue, or socket methods in this file.

- [ ] **Step 4: Run the unit tests again**

Run: `npm test`

Expected: PASS. This task adds no production branch that the node tests do not already cover. A failure here means the util or the test script was edited by mistake.

- [ ] **Step 5: Commit**

```bash
git add strings/en-us.json pages/playlist/_id.vue
git commit -m "Add a playlist download button that uses the existing download flow."
```

---

### Task 3: Verify on Windows and on the Pixel 8 Pro

No production code in this task. Do not bump the version. Do not run these steps on the plan-only PR; run them after Task 2 is on the implementation branch.

**Files:** none.

The visual placement of the button is exempt from the test-first rule. These checks are the verification for that placement and for the Capacitor dialog, folder modal, and native download calls, which the node tests cannot execute.

- [ ] **Step 1: Build and install from Jason's Windows checkout**

PowerShell, from `D:\GitHub\audiobookshelf-app`:

```powershell
git checkout cursor/playlist-download-button-6886
git pull
npm test
npm run sync
cd android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

`npm test` must pass before `npm run sync`. `assembleDebug` is the installable APK. Do not run a version bump. The debug app id is `com.audiobookshelf.app.debug` (`applicationIdSuffix ".debug"` in `android/app/build.gradle`), so this install does not replace a release install of `com.audiobookshelf.app`.

`gradlew.bat test` is not required: native code does not change. If a later review forces a Kotlin change, write the failing JUnit test first under `android/app/src/test/java/` and run `.\gradlew.bat test` from `android` before `assembleDebug`.

- [ ] **Step 2: Check the Pixel 8 Pro**

Use a server account that has `permissions.download` and a second account, or a temporary permission edit, that does not.

1. Open a book playlist that has at least two books, one already downloaded (the row shows `download_done`) and one not. The download button is in the title row, immediately left of Play, icon only, primary. Tap it. On a phone with only internal storage, confirm the dialog names Internal App Storage and a size that matches the not-yet-downloaded books. Confirm. Those books appear on the Downloads screen and finish in the local library. The already-downloaded book is not queued again. Cancel on a second tap after adding another book does not start a download.
2. Repeat with a podcast playlist. One episode already on the device stays put. Another episode of that same podcast downloads. The dialog's byte total follows the episode sizes, not the whole podcast.
3. Start a download, leave one other playlist item not downloaded, and while the first is still in progress tap the button again. The button is warning and pulsing. The dialog lists only the item that is not downloaded and not already in progress. Confirming queues that item and does not toast "already started" for the one in progress. On a playlist where every remaining item is already in the download queue, the tap alerts "The remaining items in this playlist are already downloading." and does not open the confirm dialog.
4. On a playlist whose every item is already downloaded, the button is still there. Tap it. The alert is "You have already downloaded every item in this playlist." No confirm dialog and no native download.
5. On an empty playlist, and on a playlist whose items are all missing, the download button is absent. Play stays absent when nothing is playable.
6. With download permission off, the button is absent on a playlist that has audio.
7. Set Settings → download using cellular to Never, switch to cellular, and tap the button. The toast is the existing cellular toast and nothing downloads. Set it back to Always before the next check.
8. If the device has more than one book folder, a book playlist opens the existing folder picker before the confirm dialog. Picking a folder continues. Backing out of the picker does not start a download.
9. Turn on airplane mode with the playlist page already open and tap the button. The toast is the native server-request failure (or the cellular toast, if the radio is still reported as cellular). Turn the radio back on, leave the playlist page, and open the playlist again in airplane mode. The existing redirect to the playlist list still happens; there is no offline playlist screen.
10. Play still starts the unfinished queue, including a downloaded local item, the same as before this change.

---

### Task 4: Review after implementation

**Files:** whatever the review tells you to change, plus a failing test in `utils/playlistDownload.test.mjs` before any logic fix.

- [ ] **Step 1: Run `/review-bugbot` on the implementation PR**

The implementation PR is the PR that contains Tasks 1–3, on `rgeldr/audiobookshelf-app` only. Run `/review-bugbot` against that PR.

If the command is not available, write that in the PR and do this manual pass instead:

- The button is hidden without `permissions.download` and on an empty or all-unusable playlist.
- A local episode is required before an episode is treated as downloaded. A local podcast item alone is not enough.
- Episode totals use `episode.size` and a file count of 1. Book totals use `libraryItem.size` and `libraryItem.numFiles`.
- In-progress rows are excluded from `pending` and from the totals.
- `already-downloaded` and `in-progress` return before `Dialog.confirm`.
- Books omit `episodeId` in the native payload. Episodes include it. `localFolderId` is omitted on iOS.
- `package.json` `version` and `android/app/build.gradle` `versionCode` / `versionName` are unchanged.
- No Kotlin, Swift, series page, or collection page diff.
- The diff does not touch `advplyr` remotes.

- [ ] **Step 2: Fix each finding with a test**

For a logic bug, add a failing case to `utils/playlistDownload.test.mjs`, run `npm test` and see that case fail, then change `utils/playlistDownload.js` until `npm test` passes. For a mistake in the page's payload or early return, adjust `pages/playlist/_id.vue` to match the util's contract and re-run `npm test`. Visual-only findings (spacing, icon) do not get a unit test; say that in the commit.

- [ ] **Step 3: Commit and push the review fixes**

```bash
git add utils/playlistDownload.js utils/playlistDownload.test.mjs pages/playlist/_id.vue strings/en-us.json package.json
git commit -m "Fix playlist download review findings."
git push -u origin cursor/playlist-download-button-6886
```

Stage only the files that actually changed.

## Self-review

Spec coverage:

- Collection download behavior is documented as absent, and the series flow is the one reused, including the cellular mixin, folder modal, confirm dialog, size fields, local-item skip, and native call. Collection-only facts (books only, no download UI) are listed.
- Files and placement are exact. The button uses the item-page `ui-btn` in the playlist title row.
- Edge cases have tests: already downloaded, in progress, partial-as-in-progress (any store row), podcast episode vs book, episode size vs podcast size, empty list, missing/invalid, ebook-only, local podcast without the episode, string sizes, no download permission. Offline and local-only are an assumption plus a Pixel check, because there is no local playlist to select.
- Server download permission hides the button. The native "Server request failed" path is unchanged.
- TDD: Task 1 writes the failing test before the util. Task 2's markup is called out as exempt from test-first. Task 4 fixes review findings with a failing test first.
- Version is not bumped. Windows and Pixel steps are in Task 3. Bugbot is Task 4.
- No Kotlin test, because native code does not change. The plan says when a Kotlin test would become required.

Placeholder scan: no TBD, no "handle edge cases" without code, no "similar to Task N" in place of the copied folder and confirm code.

Type consistency: `playlistDownloadPlan` returns `pending`, `skippedDownloaded`, `skippedInProgress`, `skippedUnusable`, `fileCount`, `totalBytes`, and `mediaType`. `playlistDownloadAction` reads `pending` and `skippedInProgress`. The page uses those names and `playlistItemKey` for the store lookup.
