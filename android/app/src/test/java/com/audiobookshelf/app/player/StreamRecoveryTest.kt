package com.audiobookshelf.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRecoveryTest {
  @Test
  fun aTimeoutStillInsideTheRetryWindowDoesNotStopPlayback() {
    assertEquals(
        StreamFailureAction.KeepPlaying,
        actionAfterStreamFailure(
            httpStatus = null,
            retriesExhausted = false,
            downloadedCopyAvailable = false,
            reopenAlreadyAttempted = false,
            recoveryInFlight = false))
  }

  @Test
  fun exhaustedNetworkRetriesReopenTheSessionOnce() {
    assertEquals(
        StreamFailureAction.ReopenSession,
        actionAfterStreamFailure(
            httpStatus = null,
            retriesExhausted = true,
            downloadedCopyAvailable = true,
            reopenAlreadyAttempted = false,
            recoveryInFlight = false))
  }

  @Test
  fun aDeadSessionReopensImmediatelyAndDoesNotWaitForRetries() {
    for (status in listOf(401, 403, 404, 410)) {
      assertEquals(
          StreamFailureAction.ReopenSession,
          actionAfterStreamFailure(
              httpStatus = status,
              retriesExhausted = false,
              downloadedCopyAvailable = false,
              reopenAlreadyAttempted = false,
              recoveryInFlight = false))
    }
  }

  @Test
  fun aSecondFailureUsesTheDownloadOrCloses() {
    assertEquals(
        StreamFailureAction.PlayDownloadedCopy,
        actionAfterStreamFailure(
            httpStatus = 404,
            retriesExhausted = true,
            downloadedCopyAvailable = true,
            reopenAlreadyAttempted = true,
            recoveryInFlight = false))
    assertEquals(
        StreamFailureAction.Close,
        actionAfterStreamFailure(
            httpStatus = null,
            retriesExhausted = true,
            downloadedCopyAvailable = false,
            reopenAlreadyAttempted = true,
            recoveryInFlight = false))
  }

  @Test
  fun anExpiredAccessTokenDoesNotClosePlayback() {
    assertEquals(
        StreamFailureAction.KeepPlaying,
        actionAfterStreamFailure(
            httpStatus = 401,
            retriesExhausted = true,
            downloadedCopyAvailable = false,
            reopenAlreadyAttempted = true,
            recoveryInFlight = false))
  }

  @Test
  fun anErrorWhileReopenIsInFlightDoesNotClose() {
    assertEquals(
        StreamFailureAction.KeepPlaying,
        actionAfterStreamFailure(
            httpStatus = 404,
            retriesExhausted = true,
            downloadedCopyAvailable = false,
            reopenAlreadyAttempted = true,
            recoveryInFlight = true))
  }

  @Test
  fun fiveMinuteBufferHidesADeadUrlUntilItDrains() {
    assertEquals(300_000L, bufferedPlaybackRemainingMs(bufferedMediaMs = 300_000L, playbackSpeed = 1f))
    assertEquals(250_000L, bufferedPlaybackRemainingMs(bufferedMediaMs = 300_000L, playbackSpeed = 1.2f))
    assertEquals(0L, bufferedPlaybackRemainingMs(bufferedMediaMs = 0L, playbackSpeed = 1.2f))
  }

  @Test
  fun resumeLeavesTheOpenSessionAlone() {
    assertFalse(reopenStreamOnResume(pausedForMs = 15 * 60_000L, sessionBoundUrl = true))
    assertTrue(streamUrlIsSessionBound("https://books.example/public/session/997f23d0/track/9"))
    assertFalse(streamUrlIsSessionBound("file:///storage/book.mp3"))
  }

  @Test
  fun playerErrorTextKeepsTheHttpStatus() {
    val detail =
        describePlayerError(
            message = "Source error",
            errorCode = 2004,
            causes = listOf("InvalidResponseCodeException: Response code: 404"))
    assertEquals(404, httpStatusFromPlayerError(detail))
    assertEquals(null, httpStatusFromPlayerError("timeout"))
    assertTrue(detail.contains("errorCode=2004"))
  }

  @Test
  fun pauseReasonsAreNamed() {
    assertEquals("user", pauseReasonLabel(1))
    assertEquals("audio-focus-loss", pauseReasonLabel(2))
    assertEquals("becoming-noisy", pauseReasonLabel(3))
    assertEquals("remote", pauseReasonLabel(4))
    assertEquals("end-of-item", pauseReasonLabel(5))
  }
}
