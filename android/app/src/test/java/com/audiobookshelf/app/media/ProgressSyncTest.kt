package com.audiobookshelf.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressSyncTest {
  @Test
  fun failedSyncKeepsPlaybackGoing() {
    assertEquals(PlaybackContinuation.KeepPlaying, playbackContinuationAfterSyncFailure())
  }

  @Test
  fun missingServerSessionFallsBackToTheLocalSync() {
    assertEquals(SyncChannel.LocalSession, syncTargetAfterFailure(SyncChannel.OpenSession, SyncFailureKind.SessionMissing))
    assertEquals(SyncChannel.LocalSession, syncTargetAfterFailure(SyncChannel.LocalSession, SyncFailureKind.Retryable))
  }

  @Test
  fun networkAndTimeoutStayOnTheOpenSession() {
    assertEquals(SyncChannel.OpenSession, syncTargetAfterFailure(SyncChannel.OpenSession, SyncFailureKind.Retryable))
    assertEquals(SyncFailureKind.SessionMissing, failureKind("Unexpected code Response{protocol=h2, code=404, message=Not Found}"))
    assertEquals(SyncFailureKind.Retryable, failureKind("Failed to connect"))
    assertEquals(SyncFailureKind.Retryable, failureKind("Unexpected code Response{code=503, message=Service Unavailable}"))
    assertEquals(SyncFailureKind.Retryable, failureKind(null))
    assertEquals(404, httpStatusFromSyncError("code=404"))
    assertNull(httpStatusFromSyncError("Failed to connect"))
  }

  @Test
  fun newerProgressFromAnotherDeviceIsNotOverwritten() {
    assertTrue(shouldSendProgress(lastSuccessfulSyncAt = 200L, serverUpdatedAt = 200L))
    assertTrue(shouldSendProgress(lastSuccessfulSyncAt = 200L, serverUpdatedAt = 100L))
    assertFalse(shouldSendProgress(lastSuccessfulSyncAt = 100L, serverUpdatedAt = 150L))
    assertTrue(shouldSendProgress(lastSuccessfulSyncAt = 100L, serverUpdatedAt = null))
  }

  @Test
  fun failureCountIsNotResetWhenTheNoticeIsShown() {
    var count = 0
    val logged = mutableListOf<Int>()
    repeat(4) {
      count = nextFailedSyncCount(count)
      logged += count
    }
    assertEquals(listOf(1, 2, 3, 4), logged)
    assertFalse(reopenStreamAfterSyncFailure(SyncFailureKind.Retryable))
    assertTrue(reopenStreamAfterSyncFailure(SyncFailureKind.SessionMissing))
    assertEquals(SyncFailureKind.Retryable, failureKind("timeout"))
    assertEquals(SyncFailureKind.Retryable, failureKind("Failed to connect to books.example"))
  }

  @Test
  fun latestLocalMarkWinsAndTheNoticeIsRateLimited() {
    val older = ProgressMark(currentTime = 10.0, updatedAt = 100L)
    val newer = ProgressMark(currentTime = 25.0, updatedAt = 180L)
    assertEquals(newer, latestProgress(older, newer))
    assertEquals(newer, latestProgress(newer, older))
    assertEquals(newer, latestProgress(null, newer))

    assertTrue(shouldShowSyncNotice(now = 400_000L, lastShownAt = 0L))
    assertFalse(shouldShowSyncNotice(now = 400_000L, lastShownAt = 200_000L))
    assertTrue(shouldShowSyncNotice(now = 500_000L, lastShownAt = 200_000L))
  }
}
