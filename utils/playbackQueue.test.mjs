import test from 'node:test'
import assert from 'node:assert/strict'
import { playRequestAction, queueAfter } from './playbackQueue.js'

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
