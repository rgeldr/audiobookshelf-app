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
    trackWidth: 370,
    duration,
    minTime: 0,
    maxTime: duration
  })
  assert.ok(Math.abs(time - duration / 370 / 2) < 1e-6)
  assert.ok(!Number.isInteger(time))
})
