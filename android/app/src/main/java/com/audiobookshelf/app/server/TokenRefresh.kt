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

data class UnpausePlan(
        val resumeImmediately: Boolean,
        val refreshInBackground: Boolean,
        val blocksCaller: Boolean
)

/** Play starts from the buffer. A token refresh never makes the play caller wait. */
fun planUnpause(hasLoadedMedia: Boolean, accessTokenExpired: Boolean): UnpausePlan {
  return UnpausePlan(
          resumeImmediately = hasLoadedMedia,
          refreshInBackground = accessTokenExpired,
          blocksCaller = false
  )
}

data class SavedRefresh(
        val accessToken: String,
        val refreshToken: String,
        val serverConnectionConfigId: String,
        val serverName: String
)

/**
 * A 200 with an access token is saved against the server id the refresh was for.
 * The active connection can be null while the app is reconnecting; the label is that server, never a stand-in.
 */
fun refreshToSave(
        httpCode: Int,
        accessToken: String?,
        refreshToken: String?,
        serverConnectionConfigId: String?,
        serverName: String?
): SavedRefresh? {
  if (refreshOutcomeForHttp(httpCode) != RefreshOutcome.Success) return null
  if (accessToken.isNullOrEmpty() || serverConnectionConfigId.isNullOrEmpty()) return null
  val name = serverName?.takeIf { it.isNotBlank() } ?: serverConnectionConfigId
  return SavedRefresh(accessToken, refreshToken.orEmpty(), serverConnectionConfigId, name)
}

/** The loader thread may wait. The main thread and the ExoPlayer playback thread may not. */
fun mayWaitForRefresh(threadName: String, isMainThread: Boolean): Boolean {
  if (isMainThread) return false
  if (threadName.contains("Playback")) return false
  return true
}

/** One armed generation. A fire for any other generation, or after cancel, does nothing. */
class RefreshDeadline {
  private val lock = Any()
  private var armedGeneration: Int? = null

  fun arm(generation: Int) {
    synchronized(lock) { armedGeneration = generation }
  }

  fun cancel(generation: Int) {
    synchronized(lock) {
      if (armedGeneration == generation) armedGeneration = null
    }
  }

  fun onFire(generation: Int): Boolean {
    synchronized(lock) {
      if (armedGeneration != generation) return false
      armedGeneration = null
      return true
    }
  }
}

/**
 * Save the token and release whoever is waiting before a log line or a WebView callback.
 * The 15s deadline cannot replace a success, and a deadline from an older generation cannot
 * finish the flight that is armed now.
 */
class RefreshCompletion {
  var saved: SavedRefresh? = null
  var outcome: RefreshOutcome? = null
  var callerReleased = false
  var generation = 0
    private set
  private val deadline = RefreshDeadline()

  fun start(): Int {
    generation += 1
    callerReleased = false
    deadline.arm(generation)
    return generation
  }

  fun httpResult(
          code: Int,
          accessToken: String?,
          refreshToken: String?,
          serverId: String?,
          serverName: String?
  ) {
    val save = refreshToSave(code, accessToken, refreshToken, serverId, serverName)
    if (save != null) {
      saved = save
      outcome = RefreshOutcome.Success
    } else {
      outcome = refreshOutcomeForHttp(code)
    }
    deadline.cancel(generation)
    callerReleased = true
  }

  /** True only when this deadline is the one that times the flight out. */
  fun deadlineFor(generation: Int): Boolean {
    if (!deadline.onFire(generation)) return false
    if (generation != this.generation || callerReleased) return false
    outcome = RefreshOutcome.Transient
    callerReleased = true
    return true
  }
}
