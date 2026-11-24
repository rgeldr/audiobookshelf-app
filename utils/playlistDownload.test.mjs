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
