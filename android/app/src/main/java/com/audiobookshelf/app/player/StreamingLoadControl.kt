package com.audiobookshelf.app.player

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.LoadControl
import com.google.android.exoplayer2.ParserException
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.trackselection.ExoTrackSelection
import com.google.android.exoplayer2.upstream.Allocator
import com.google.android.exoplayer2.upstream.DataSourceException
import com.google.android.exoplayer2.upstream.DefaultAllocator
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.upstream.LoadErrorHandlingPolicy
import com.google.android.exoplayer2.upstream.Loader
import java.io.FileNotFoundException
import java.io.IOException

const val STREAMING_BUFFER_LOG_TAG = "StreamingBuffer"

/** One allocator, two profiles. [PlayerNotificationService] sets [profile] before each prepare. */
class StreamingLoadControl : LoadControl {
  var profile: BufferProfile = BufferProfile.Direct
  var mediaDurationMs: Long = 0

  private val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
  private var wasLoading = false

  private fun selectedDurations(): BufferDurations = durationsFor(profile, mediaDurationMs)

  override fun onPrepared() {
    wasLoading = false
  }

  override fun onTracksSelected(
      renderers: Array<com.google.android.exoplayer2.Renderer>,
      trackGroups: TrackGroupArray,
      trackSelections: Array<ExoTrackSelection>
  ) {}

  override fun onStopped() {
    wasLoading = false
    allocator.reset()
  }

  override fun onReleased() {
    wasLoading = false
    allocator.reset()
  }

  override fun getAllocator(): Allocator = allocator

  override fun getBackBufferDurationUs(): Long = selectedDurations().backBufferMs * 1000

  override fun retainBackBufferFromKeyframe(): Boolean = selectedDurations().backBufferMs > 0

  override fun shouldContinueLoading(
      playbackPositionUs: Long,
      bufferedDurationUs: Long,
      playbackSpeed: Float
  ): Boolean {
    val selected = selectedDurations()
    wasLoading =
        com.audiobookshelf.app.player.shouldContinueLoading(
            bufferedDurationUs = bufferedDurationUs,
            playbackSpeed = playbackSpeed,
            allocatedBytes = allocator.totalBytesAllocated.toLong(),
            targetBufferBytes = selected.targetBufferBytes,
            minBufferUs = selected.minBufferMs * 1000,
            maxBufferUs = selected.maxBufferMs * 1000,
            prioritizeTime = selected.prioritizeTimeOverSize,
            wasLoading = wasLoading)
    return wasLoading
  }

  override fun shouldStartPlayback(
      bufferedDurationUs: Long,
      playbackSpeed: Float,
      rebuffering: Boolean,
      targetLiveOffsetUs: Long
  ): Boolean {
    val selected = selectedDurations()
    return com.audiobookshelf.app.player.shouldStartPlayback(
        bufferedDurationUs = bufferedDurationUs,
        playbackSpeed = playbackSpeed,
        rebuffering = rebuffering,
        bufferForPlaybackUs = selected.bufferForPlaybackMs * 1000,
        bufferForPlaybackAfterRebufferUs = selected.bufferForPlaybackAfterRebufferMs * 1000)
  }
}

/** Progressive (direct-play and local) retry policy. HLS keeps ExoPlayer's default. */
class StreamingLoadErrorPolicy : LoadErrorHandlingPolicy {
  override fun getFallbackSelectionFor(
      fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
      loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
  ): LoadErrorHandlingPolicy.FallbackSelection? = null

  override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
    val failure = classifyLoadException(loadErrorInfo.exception)
    return retryDelayMs(loadErrorInfo.errorCount, failure) ?: C.TIME_UNSET
  }

  override fun getMinimumLoadableRetryCount(dataType: Int): Int = STREAMING_RETRY_COUNT
}

internal fun classifyLoadException(exception: IOException): LoadFailure {
  if (exception is ParserException ||
      exception is FileNotFoundException ||
      exception is HttpDataSource.CleartextNotPermittedException ||
      exception is Loader.UnexpectedLoaderException ||
      DataSourceException.isCausedByPositionOutOfRange(exception)) {
    return LoadFailure.NotRetryable
  }
  if (exception is HttpDataSource.InvalidResponseCodeException) {
    return classifyHttpStatus(exception.responseCode)
  }
  return LoadFailure.Network
}
