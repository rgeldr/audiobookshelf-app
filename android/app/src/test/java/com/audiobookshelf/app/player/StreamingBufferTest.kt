package com.audiobookshelf.app.player

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ParserException
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.upstream.DataSourceException
import com.google.android.exoplayer2.upstream.Loader
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingBufferTest {
  @Test
  fun loadControlFollowsTheActiveProfile() {
    val control = StreamingLoadControl()
    assertEquals(30_000_000L, control.backBufferDurationUs)
    assertTrue(control.retainBackBufferFromKeyframe())
    assertTrue(control.shouldContinueLoading(0, 10_000_000, 1f))
    assertFalse(control.shouldContinueLoading(0, 300_000_000, 1f))
    assertTrue(control.shouldStartPlayback(5_000_000, 1f, false, C.TIME_UNSET))

    control.profile = BufferProfile.Hls
    assertEquals(0L, control.backBufferDurationUs)
    assertFalse(control.retainBackBufferFromKeyframe())
    assertTrue(control.shouldContinueLoading(0, 10_000_000, 1f))
    assertFalse(control.shouldContinueLoading(0, 45_000_000, 1f))
  }

  @Test
  fun directProfileHoldsFiveMinutesAndSixteenMebibytes() {
    val direct = durations(BufferProfile.Direct)
    assertEquals(300_000L, direct.minBufferMs)
    assertEquals(300_000L, direct.maxBufferMs)
    assertEquals(5_000L, direct.bufferForPlaybackMs)
    assertEquals(15_000L, direct.bufferForPlaybackAfterRebufferMs)
    assertEquals(30_000L, direct.backBufferMs)
    assertEquals(16L * 1024 * 1024, direct.targetBufferBytes)
    assertFalse(direct.prioritizeTimeOverSize)
  }

  @Test
  fun hlsProfileKeepsTodaysBuffer() {
    val hls = durations(BufferProfile.Hls)
    assertEquals(20_000L, hls.minBufferMs)
    assertEquals(45_000L, hls.maxBufferMs)
    assertEquals(5_000L, hls.bufferForPlaybackMs)
    assertEquals(20_000L, hls.bufferForPlaybackAfterRebufferMs)
    assertEquals(0L, hls.backBufferMs)
    assertEquals(13_107_200L, hls.targetBufferBytes)
    assertFalse(hls.prioritizeTimeOverSize)
  }

  @Test
  fun directProfileLoadsUntilFiveMinutes() {
    val direct = durations(BufferProfile.Direct)
    assertTrue(load(direct, bufferedMs = 10_000, speed = 1f, allocatedBytes = 0, wasLoading = false))
    assertTrue(load(direct, bufferedMs = 299_000, speed = 1f, allocatedBytes = 0, wasLoading = false))
    assertFalse(load(direct, bufferedMs = 300_000, speed = 1f, allocatedBytes = 0, wasLoading = true))
  }

  @Test
  fun directProfileAtOneAndAQuarterSpeedStillStopsAtFiveMinutes() {
    val direct = durations(BufferProfile.Direct)
    assertTrue(load(direct, bufferedMs = 299_000, speed = 1.25f, allocatedBytes = 0, wasLoading = false))
    assertFalse(load(direct, bufferedMs = 300_000, speed = 1.25f, allocatedBytes = 0, wasLoading = true))
  }

  @Test
  fun speedScalesTheMinimumAndTheGapKeepsThePreviousFlag() {
    assertTrue(
        continueLoading(
            bufferedMs = 30_000,
            speed = 2f,
            allocatedBytes = 0,
            targetBytes = 16L * 1024 * 1024,
            minMs = 60_000,
            maxMs = 180_000,
            prioritizeTime = false,
            wasLoading = false))
    assertTrue(
        continueLoading(
            bufferedMs = 90_000,
            speed = 2f,
            allocatedBytes = 0,
            targetBytes = 16L * 1024 * 1024,
            minMs = 60_000,
            maxMs = 180_000,
            prioritizeTime = false,
            wasLoading = false))
    assertFalse(
        continueLoading(
            bufferedMs = 150_000,
            speed = 2f,
            allocatedBytes = 0,
            targetBytes = 16L * 1024 * 1024,
            minMs = 60_000,
            maxMs = 180_000,
            prioritizeTime = false,
            wasLoading = false))
    assertTrue(
        continueLoading(
            bufferedMs = 150_000,
            speed = 2f,
            allocatedBytes = 0,
            targetBytes = 16L * 1024 * 1024,
            minMs = 60_000,
            maxMs = 180_000,
            prioritizeTime = false,
            wasLoading = true))
    assertFalse(
        continueLoading(
            bufferedMs = 90_000,
            speed = 1f,
            allocatedBytes = 0,
            targetBytes = 16L * 1024 * 1024,
            minMs = 60_000,
            maxMs = 180_000,
            prioritizeTime = false,
            wasLoading = false))
    assertTrue(
        continueLoading(
            bufferedMs = 90_000,
            speed = 1f,
            allocatedBytes = 0,
            targetBytes = 16L * 1024 * 1024,
            minMs = 60_000,
            maxMs = 180_000,
            prioritizeTime = false,
            wasLoading = true))
  }

  @Test
  fun byteCeilingStopsLoadingBelowTheMinimumUnlessTimeIsPrioritized() {
    assertTrue(
        continueLoading(
            bufferedMs = 10_000,
            speed = 1f,
            allocatedBytes = 100,
            targetBytes = 100,
            minMs = 60_000,
            maxMs = 180_000,
            prioritizeTime = true,
            wasLoading = false))
    assertFalse(
        continueLoading(
            bufferedMs = 10_000,
            speed = 1f,
            allocatedBytes = 100,
            targetBytes = 100,
            minMs = 60_000,
            maxMs = 180_000,
            prioritizeTime = false,
            wasLoading = true))
    val direct = durations(BufferProfile.Direct)
    assertFalse(
        load(
            direct,
            bufferedMs = 60_000,
            speed = 1f,
            allocatedBytes = direct.targetBufferBytes,
            wasLoading = true))
  }

  @Test
  fun byteCeilingStopsLoadingBetweenMinimumAndMaximum() {
    assertFalse(
        continueLoading(
            bufferedMs = 90_000,
            speed = 1f,
            allocatedBytes = 100,
            targetBytes = 100,
            minMs = 60_000,
            maxMs = 180_000,
            prioritizeTime = false,
            wasLoading = true))
  }

  @Test
  fun minimumBufferIsAtLeastHalfASecond() {
    assertTrue(
        continueLoading(
            bufferedUs = 400_000,
            speed = 1f,
            allocatedBytes = 0,
            targetBytes = 16L * 1024 * 1024,
            minUs = 0,
            maxUs = 180_000_000,
            prioritizeTime = false,
            wasLoading = false))
    assertFalse(
        continueLoading(
            bufferedUs = 600_000,
            speed = 1f,
            allocatedBytes = 0,
            targetBytes = 16L * 1024 * 1024,
            minUs = 0,
            maxUs = 180_000_000,
            prioritizeTime = false,
            wasLoading = false))
  }

  @Test
  fun networkRetriesBackOffToThirtySecondsAndStopAfterFortyFive() {
    assertEquals(0L, retryDelayMs(1, LoadFailure.Network))
    assertEquals(1_000L, retryDelayMs(2, LoadFailure.Network))
    assertEquals(2_000L, retryDelayMs(3, LoadFailure.Network))
    assertEquals(4_000L, retryDelayMs(4, LoadFailure.Network))
    assertEquals(8_000L, retryDelayMs(5, LoadFailure.Network))
    assertEquals(16_000L, retryDelayMs(6, LoadFailure.Network))
    assertEquals(30_000L, retryDelayMs(7, LoadFailure.Network))
    assertEquals(30_000L, retryDelayMs(STREAMING_RETRY_COUNT, LoadFailure.Network))
    assertNull(retryDelayMs(STREAMING_RETRY_COUNT + 1, LoadFailure.Network))
    var sum = 0L
    for (count in 1..STREAMING_RETRY_COUNT) {
      sum += retryDelayMs(count, LoadFailure.Network)!!
    }
    assertEquals(1_201_000L, sum)
  }

  @Test
  fun unavailableUsesTheSameDelaysAndOtherFailuresDoNotRetry() {
    assertEquals(0L, retryDelayMs(1, LoadFailure.Unavailable))
    assertEquals(30_000L, retryDelayMs(7, LoadFailure.Unavailable))
    assertNull(retryDelayMs(1, LoadFailure.NotRetryable))
    assertEquals(LoadFailure.Unavailable, classifyHttpStatus(502))
    assertEquals(LoadFailure.Unavailable, classifyHttpStatus(503))
    assertEquals(LoadFailure.Unavailable, classifyHttpStatus(504))
    assertEquals(LoadFailure.NotRetryable, classifyHttpStatus(404))
    assertEquals(LoadFailure.NotRetryable, classifyHttpStatus(410))
    assertEquals(LoadFailure.NotRetryable, classifyHttpStatus(416))
    assertEquals(LoadFailure.NotRetryable, classifyHttpStatus(500))
  }

  @Test
  fun loadExceptionsMapOntoTheRetryClasses() {
    assertEquals(LoadFailure.Network, classifyLoadException(IOException("timeout")))
    assertEquals(LoadFailure.NotRetryable, classifyLoadException(FileNotFoundException("missing")))
    assertEquals(
        LoadFailure.NotRetryable,
        classifyLoadException(ParserException.createForMalformedContainer("bad", null)))
    assertEquals(
        LoadFailure.NotRetryable,
        classifyLoadException(
            DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)))
    assertEquals(
        LoadFailure.NotRetryable,
        classifyLoadException(Loader.UnexpectedLoaderException(IllegalStateException("boom"))))
  }

  @Test
  fun transcodeFallbackIsOnlyForAnUnreadableContainer() {
    assertTrue(shouldFallbackToTranscode(3001))
    assertTrue(shouldFallbackToTranscode(3003))
    assertFalse(shouldFallbackToTranscode(2001))
    assertFalse(shouldFallbackToTranscode(2002))
    assertFalse(shouldFallbackToTranscode(2004))
    assertFalse(shouldFallbackToTranscode(2005))
    assertFalse(shouldFallbackToTranscode(3002))
    assertFalse(shouldFallbackToTranscode(3004))
  }

  @Test
  fun progressivePolicyRetriesEveryDataType() {
    val policy = StreamingLoadErrorPolicy()
    assertEquals(STREAMING_RETRY_COUNT, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    assertEquals(STREAMING_RETRY_COUNT, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST))
  }

  @Test
  fun playbackStartsAfterFiveSecondsAndResumesAfterFifteen() {
    val direct = durations(BufferProfile.Direct)
    assertTrue(start(direct, bufferedUs = 5_000_000, speed = 1f, rebuffering = false))
    assertFalse(start(direct, bufferedUs = 4_000_000, speed = 1f, rebuffering = false))
    assertTrue(start(direct, bufferedUs = 15_000_000, speed = 1f, rebuffering = true))
    assertFalse(start(direct, bufferedUs = 14_000_000, speed = 1f, rebuffering = true))
    assertTrue(start(direct, bufferedUs = 6_250_000, speed = 1.25f, rebuffering = false))
    assertFalse(start(direct, bufferedUs = 5_000_000, speed = 1.25f, rebuffering = false))
    assertTrue(start(direct, bufferedUs = 18_750_000, speed = 1.25f, rebuffering = true))
    assertFalse(start(direct, bufferedUs = 15_000_000, speed = 1.25f, rebuffering = true))
  }

  private fun load(
      durations: BufferDurations,
      bufferedMs: Long,
      speed: Float,
      allocatedBytes: Long,
      wasLoading: Boolean
  ): Boolean {
    return continueLoading(
        bufferedMs = bufferedMs,
        speed = speed,
        allocatedBytes = allocatedBytes,
        targetBytes = durations.targetBufferBytes,
        minMs = durations.minBufferMs,
        maxMs = durations.maxBufferMs,
        prioritizeTime = durations.prioritizeTimeOverSize,
        wasLoading = wasLoading)
  }

  private fun continueLoading(
      bufferedMs: Long = 0,
      bufferedUs: Long = bufferedMs * 1000,
      speed: Float,
      allocatedBytes: Long,
      targetBytes: Long,
      minMs: Long = 0,
      maxMs: Long = 0,
      minUs: Long = minMs * 1000,
      maxUs: Long = maxMs * 1000,
      prioritizeTime: Boolean,
      wasLoading: Boolean
  ): Boolean {
    return shouldContinueLoading(
        bufferedDurationUs = bufferedUs,
        playbackSpeed = speed,
        allocatedBytes = allocatedBytes,
        targetBufferBytes = targetBytes,
        minBufferUs = minUs,
        maxBufferUs = maxUs,
        prioritizeTime = prioritizeTime,
        wasLoading = wasLoading)
  }

  private fun start(
      durations: BufferDurations,
      bufferedUs: Long,
      speed: Float,
      rebuffering: Boolean
  ): Boolean {
    return shouldStartPlayback(
        bufferedDurationUs = bufferedUs,
        playbackSpeed = speed,
        rebuffering = rebuffering,
        bufferForPlaybackUs = durations.bufferForPlaybackMs * 1000,
        bufferForPlaybackAfterRebufferUs = durations.bufferForPlaybackAfterRebufferMs * 1000)
  }
}
