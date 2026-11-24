import test from 'node:test'
import assert from 'node:assert/strict'
import { playRequestAction, queueAfter, startQueue } from './playbackQueue.js'

const items = [
  { libraryItemId: 'a', episodeId: null, finished: true },
  { libraryItemId: 'b', episodeId: null, finished: false },
  { libraryItemId: 'c', episodeId: null, finished: true },
  { libraryItemId: 'local_d', episodeId: 'local_ep', finished: false }
]

test('header-style start skips finished items after the start index', () => {
  assert.deepEqual(queueAfter(items, 1), [{ libraryItemId: 'local_d', episodeId: 'local_ep' }])
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

test('header or row play of the item already loaded still installs its queue', () => {
  const queue = [{ libraryItemId: 'book-2', episodeId: null }]
  assert.equal(playRequestAction(true, queue), 'install-queue')
})

test('resuming an open item without a queue does not clear the current queue', () => {
  assert.equal(playRequestAction(true, null), 'resume')
  assert.equal(playRequestAction(true, undefined), 'resume')
})

test('a new item is prepared instead of resumed', () => {
  assert.equal(playRequestAction(false, [{ libraryItemId: 'book-2', episodeId: null }]), 'prepare')
})

const seriesBooks = [
  { libraryItemId: 'book-1', episodeId: null, finished: true },
  { libraryItemId: 'book-2', episodeId: null, finished: false },
  { libraryItemId: 'book-3', episodeId: null, finished: true },
  { libraryItemId: 'book-4', episodeId: null, finished: false },
  { libraryItemId: 'book-5', episodeId: null, finished: false }
]

const homePlay = {
  continueSeries: false,
  queue: null,
  seriesId: null,
  mediaType: 'book',
  series: [{ id: 'dresden', name: 'The Dresden Files' }, { id: 'other', name: 'Other' }],
  libraryId: 'lib-1',
  seriesItems: seriesBooks,
  startedId: 'book-2'
}

test('setting off leaves a home play with an empty queue, which ends in Stop', () => {
  const result = startQueue(homePlay)
  assert.equal(result.seriesId, null)
  assert.deepEqual(result.queue, [])
  assert.equal(result.fromSetting, false)
})

test('setting on queues unfinished books after the started one, in series order, skipping finished ones', () => {
  const result = startQueue({ ...homePlay, continueSeries: true })
  assert.equal(result.seriesId, 'dresden')
  assert.equal(result.seriesLibraryId, 'lib-1')
  assert.equal(result.fromSetting, true)
  assert.deepEqual(result.queue, [
    { libraryItemId: 'book-4', episodeId: null },
    { libraryItemId: 'book-5', episodeId: null }
  ])
})

test('setting on with no series leaves the queue empty', () => {
  const result = startQueue({ ...homePlay, continueSeries: true, series: [] })
  assert.equal(result.seriesId, null)
  assert.deepEqual(result.queue, [])
})

test('a podcast is not queued into a series when the setting is on', () => {
  const result = startQueue({ ...homePlay, continueSeries: true, mediaType: 'podcast' })
  assert.equal(result.seriesId, null)
  assert.deepEqual(result.queue, [])
})

test('an explicit playlist queue is unchanged when the setting is on', () => {
  const playlist = [{ libraryItemId: 'playlist-next', episodeId: null }]
  const result = startQueue({ ...homePlay, continueSeries: true, queue: playlist })
  assert.deepEqual(result.queue, playlist)
  assert.equal(result.fromSetting, false)
})

test('a series-page start keeps its series queue when the setting is off', () => {
  const result = startQueue({ ...homePlay, seriesId: 'dresden', seriesLibraryId: 'lib-1' })
  assert.equal(result.seriesId, 'dresden')
  assert.equal(result.fromSetting, false)
  assert.deepEqual(result.queue, [
    { libraryItemId: 'book-4', episodeId: null },
    { libraryItemId: 'book-5', episodeId: null }
  ])
})
