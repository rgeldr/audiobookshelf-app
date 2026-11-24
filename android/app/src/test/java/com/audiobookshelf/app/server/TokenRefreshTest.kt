package com.audiobookshelf.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenRefreshTest {
  @Test
  fun oneRefreshServesEveryCaller() {
    val gate = TokenRefreshGate()
    val start = gate.begin {}
    assertTrue(start is RefreshBegin.Start)
    assertEquals(1, (start as RefreshBegin.Start).generation)

    var joined: RefreshVerdict? = null
    val join = gate.begin { joined = it }
    assertTrue(join is RefreshBegin.Join)
    assertEquals(1, (join as RefreshBegin.Join).generation)

    gate.complete(1, RefreshVerdict(RefreshOutcome.Success, "access-new"))
    assertEquals(RefreshOutcome.Success, joined?.outcome)
    assertEquals("access-new", joined?.accessToken)

    val again = gate.begin {}
    assertEquals(2, (again as RefreshBegin.Start).generation)
  }

  @Test
  fun aLateRejectionDoesNotWipeAFinishedRefresh() {
    val gate = TokenRefreshGate()
    gate.begin {}
    gate.complete(1, RefreshVerdict(RefreshOutcome.Success, "access-new"))
    gate.complete(1, RefreshVerdict(RefreshOutcome.Rejected, null))
    val next = gate.begin {}
    assertEquals(2, (next as RefreshBegin.Start).generation)
  }

  @Test
  fun httpStatusAndTimeoutMapToOutcomes() {
    assertEquals(RefreshOutcome.Success, refreshOutcomeForHttp(200))
    assertEquals(RefreshOutcome.Rejected, refreshOutcomeForHttp(401))
    assertEquals(RefreshOutcome.Rejected, refreshOutcomeForHttp(403))
    assertEquals(RefreshOutcome.Transient, refreshOutcomeForHttp(500))
    assertEquals(RefreshOutcome.Transient, refreshOutcomeForHttp(0))
    assertFalse(refreshTimedOut(elapsedMs = 1_000L, limitMs = REFRESH_TIMEOUT_MS))
    assertTrue(refreshTimedOut(elapsedMs = REFRESH_TIMEOUT_MS, limitMs = REFRESH_TIMEOUT_MS))
  }

  @Test
  fun refreshNeverStopsPlayback() {
    for (outcome in RefreshOutcome.values()) {
      assertFalse(refreshStopsPlayback(outcome))
    }
    assertTrue(shouldRetryOriginalRequest(RefreshOutcome.Success))
    assertFalse(shouldRetryOriginalRequest(RefreshOutcome.Rejected))
    assertFalse(shouldRetryOriginalRequest(RefreshOutcome.Transient))
  }

  @Test
  fun aRejectedRefreshWaitsBeforeTryingAgain() {
    assertFalse(shouldStartRefresh(lastRejectedAt = 1_000L, now = 30_000L, cooldownMs = REFRESH_COOLDOWN_MS))
    assertTrue(shouldStartRefresh(lastRejectedAt = 1_000L, now = 61_000L, cooldownMs = REFRESH_COOLDOWN_MS))
    assertTrue(shouldStartRefresh(lastRejectedAt = 0L, now = 5_000L, cooldownMs = REFRESH_COOLDOWN_MS))
  }

  @Test
  fun trackUriPicksUpTheNewAccessToken() {
    assertEquals(
        "https://books.example/s/track.mp3?token=new",
        refreshedTrackUri("https://books.example/s/track.mp3?token=old", "new"))
    assertEquals(
        "https://books.example/public/session/abc/track/1",
        refreshedTrackUri("https://books.example/public/session/abc/track/1", "new"))
    assertNull(refreshedTrackUri("https://books.example/s/track.mp3?token=old", null))
  }

  @Test
  fun unauthorizedTrackKeepsPlayingWhenRefreshFails() {
    assertEquals(TrackAuthAction.RetryWithNewToken, trackActionAfterUnauthorized(refreshSucceeded = true))
    assertEquals(TrackAuthAction.KeepBufferedPlayback, trackActionAfterUnauthorized(refreshSucceeded = false))
  }
}
