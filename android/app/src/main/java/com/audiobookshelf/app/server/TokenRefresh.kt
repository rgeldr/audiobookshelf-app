package com.audiobookshelf.app.server

const val REFRESH_TIMEOUT_MS = 15_000L
const val REFRESH_COOLDOWN_MS = 60_000L

enum class RefreshOutcome {
  Success,
  Transient,
  Rejected
}

data class RefreshVerdict(val outcome: RefreshOutcome, val accessToken: String?)

sealed class RefreshBegin {
  data class Start(val generation: Int) : RefreshBegin()
  data class Join(val generation: Int) : RefreshBegin()
}

enum class TrackAuthAction {
  RetryWithNewToken,
  KeepBufferedPlayback
}

/**
 * One refresh at a time for the whole process. A second 401 joins the flight instead of
 * sending the same refresh token again. Audiobookshelf rotates that token, so the second
 * request comes back 401 and used to wipe the session.
 */
class TokenRefreshGate {
  private val lock = Any()
  private var generation = 0
  private var inFlight = false
  private val waiters = mutableListOf<(RefreshVerdict) -> Unit>()

  fun begin(onResult: (RefreshVerdict) -> Unit): RefreshBegin {
    synchronized(lock) {
      if (inFlight) {
        waiters += onResult
        return RefreshBegin.Join(generation)
      }
      generation += 1
      inFlight = true
      return RefreshBegin.Start(generation)
    }
  }

  /** First completion wins and notifies the callers that joined. A late result is ignored. */
  fun complete(generation: Int, verdict: RefreshVerdict): Boolean {
    val listeners: List<(RefreshVerdict) -> Unit>
    synchronized(lock) {
      if (!inFlight || generation != this.generation) return false
      inFlight = false
      listeners = waiters.toList()
      waiters.clear()
    }
    listeners.forEach { it(verdict) }
    return true
  }
}

fun refreshOutcomeForHttp(code: Int): RefreshOutcome {
  if (code in 200..299) return RefreshOutcome.Success
  if (code == 401 || code == 403) return RefreshOutcome.Rejected
  return RefreshOutcome.Transient
}

fun refreshTimedOut(elapsedMs: Long, limitMs: Long = REFRESH_TIMEOUT_MS): Boolean = elapsedMs >= limitMs

fun refreshStopsPlayback(outcome: RefreshOutcome): Boolean = false

fun shouldRetryOriginalRequest(outcome: RefreshOutcome): Boolean = outcome == RefreshOutcome.Success

/** A rejected refresh token is not sent again until the cooldown passes. */
fun shouldStartRefresh(lastRejectedAt: Long, now: Long, cooldownMs: Long = REFRESH_COOLDOWN_MS): Boolean {
  if (lastRejectedAt <= 0L) return true
  return now - lastRejectedAt >= cooldownMs
}

/** Old direct-play URLs carry `?token=`. A public session URL does not. */
fun refreshedTrackUri(uri: String, accessToken: String?): String? {
  if (accessToken.isNullOrEmpty()) return null
  if (!uri.contains("token=")) return uri
  return uri.replace(Regex("token=[^&]*"), "token=$accessToken")
}

fun trackActionAfterUnauthorized(refreshSucceeded: Boolean): TrackAuthAction {
  return if (refreshSucceeded) TrackAuthAction.RetryWithNewToken else TrackAuthAction.KeepBufferedPlayback
}
