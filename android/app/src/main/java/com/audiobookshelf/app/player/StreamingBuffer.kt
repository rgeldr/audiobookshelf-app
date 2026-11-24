package com.audiobookshelf.app.player

const val STREAMING_RETRY_COUNT = 45

private const val RETRY_DELAY_CAP_MS = 30_000L

enum class LoadFailure {
  Network,
  Unavailable,
  NotRetryable
}

enum class BufferProfile {
  Direct,
  Hls
}

data class BufferDurations(
    val minBufferMs: Long,
    val maxBufferMs: Long,
    val bufferForPlaybackMs: Long,
    val bufferForPlaybackAfterRebufferMs: Long,
    val backBufferMs: Long,
    val targetBufferBytes: Long,
    val prioritizeTimeOverSize: Boolean
)

private const val MIN_BUFFER_FLOOR_US = 500_000L
private const val HLS_AUDIO_BUFFER_BYTES = 13_107_200L
private const val DIRECT_BUFFER_BYTES = 16L * 1024 * 1024

fun durations(profile: BufferProfile): BufferDurations {
  return when (profile) {
    BufferProfile.Direct ->
        BufferDurations(
            minBufferMs = 300_000,
            maxBufferMs = 300_000,
            bufferForPlaybackMs = 5_000,
            bufferForPlaybackAfterRebufferMs = 15_000,
            backBufferMs = 30_000,
            targetBufferBytes = DIRECT_BUFFER_BYTES,
            prioritizeTimeOverSize = false)
    BufferProfile.Hls ->
        BufferDurations(
            minBufferMs = 20_000,
            maxBufferMs = 45_000,
            bufferForPlaybackMs = 5_000,
            bufferForPlaybackAfterRebufferMs = 20_000,
            backBufferMs = 0,
            targetBufferBytes = HLS_AUDIO_BUFFER_BYTES,
            prioritizeTimeOverSize = false)
  }
}

/**
 * ExoPlayer 2.18.7 [com.google.android.exoplayer2.DefaultLoadControl.shouldContinueLoading].
 * Above 1x, the minimum is scaled by speed and capped at the maximum. Durations under 500ms are
 * raised to that floor. Between the minimum and the maximum, [wasLoading] is left unchanged.
 */
fun shouldContinueLoading(
    bufferedDurationUs: Long,
    playbackSpeed: Float,
    allocatedBytes: Long,
    targetBufferBytes: Long,
    minBufferUs: Long,
    maxBufferUs: Long,
    prioritizeTime: Boolean,
    wasLoading: Boolean
): Boolean {
  val targetReached = allocatedBytes >= targetBufferBytes
  var minUs = minBufferUs
  if (playbackSpeed > 1f) {
    val scaled = Math.round(minUs.toDouble() * playbackSpeed)
    minUs = minOf(scaled, maxBufferUs)
  }
  minUs = maxOf(minUs, MIN_BUFFER_FLOOR_US)
  if (bufferedDurationUs < minUs) {
    return prioritizeTime || !targetReached
  }
  if (bufferedDurationUs >= maxBufferUs || targetReached) {
    return false
  }
  return wasLoading
}

/** Media duration divided by playback speed, compared with the start or rebuffer threshold. */
fun shouldStartPlayback(
    bufferedDurationUs: Long,
    playbackSpeed: Float,
    rebuffering: Boolean,
    bufferForPlaybackUs: Long,
    bufferForPlaybackAfterRebufferUs: Long
): Boolean {
  val playoutUs =
      if (playbackSpeed == 1f) bufferedDurationUs
      else Math.round(bufferedDurationUs.toDouble() / playbackSpeed)
  val requiredUs = if (rebuffering) bufferForPlaybackAfterRebufferUs else bufferForPlaybackUs
  return requiredUs <= 0 || playoutUs >= requiredUs
}

/**
 * Attempt 1 retries immediately. Later attempts double from 1s and cap at 30s. Attempt 46 is fatal.
 * [LoadFailure.NotRetryable] is fatal on the first failure.
 */
fun retryDelayMs(errorCount: Int, failure: LoadFailure): Long? {
  if (failure == LoadFailure.NotRetryable || errorCount > STREAMING_RETRY_COUNT) return null
  if (errorCount <= 1) return 0
  val shift = (errorCount - 2).coerceAtMost(5)
  return minOf(1000L shl shift, RETRY_DELAY_CAP_MS)
}

/** 502, 503, and 504 are retried. Every other HTTP status is fatal. */
fun classifyHttpStatus(responseCode: Int): LoadFailure {
  return when (responseCode) {
    502, 503, 504 -> LoadFailure.Unavailable
    else -> LoadFailure.NotRetryable
  }
}

/** Direct-play container failures are the only errors that should open a transcode session. */
fun shouldFallbackToTranscode(errorCode: Int): Boolean {
  return errorCode == 3001 || errorCode == 3003
}
