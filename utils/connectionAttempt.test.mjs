import test from 'node:test'
import assert from 'node:assert/strict'
import { connectionAttemptTimedOut, CONNECTION_ATTEMPT_TIMEOUT_MS } from './connectionAttempt.js'

test('a connection attempt is still running before the limit', () => {
  assert.equal(connectionAttemptTimedOut(1000, CONNECTION_ATTEMPT_TIMEOUT_MS), false)
})

test('a connection attempt times out at the limit so the spinner can clear', () => {
  assert.equal(connectionAttemptTimedOut(CONNECTION_ATTEMPT_TIMEOUT_MS, CONNECTION_ATTEMPT_TIMEOUT_MS), true)
  assert.equal(connectionAttemptTimedOut(CONNECTION_ATTEMPT_TIMEOUT_MS + 1), true)
})
