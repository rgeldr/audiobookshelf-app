package com.audiobookshelf.app.player

import kotlin.math.round

enum class StreamFailureAction {
  KeepPlaying,
  ReopenSession,
  PlayDownloadedCopy,
  Close
}

/**
 * A direct-play URL for server 2.22+ is `/public/session/{id}/track/{n}`.
 * 401, 403, 404, and 410 mean that session or token is gone, so reopen once.
 * A timeout stays on ExoPlayer's retry window until that window is used up, then reopens once.
 * A second failure plays a download when one exists, otherwise it closes.
 */
fun actionAfterStreamFailure(
    httpStatus: Int?,
    retriesExhausted: Boolean,
    downloadedCopyAvailable: Boolean,
    reopenAlreadyAttempted: Boolean,
    recoveryInFlight: Boolean
): StreamFailureAction {
  if (recoveryInFlight) return StreamFailureAction.KeepPlaying
  val sessionDead = httpStatus == 401 || httpStatus == 403 || httpStatus == 404 || httpStatus == 410
  if (!sessionDead && !retriesExhausted) return StreamFailureAction.KeepPlaying
  if (!reopenAlreadyAttempted) return StreamFailureAction.ReopenSession
  if (downloadedCopyAvailable) return StreamFailureAction.PlayDownloadedCopy
  return StreamFailureAction.Close
}

/** Wall-clock audio left in the buffer. A dead URL is not heard until this elapses. */
fun bufferedPlaybackRemainingMs(bufferedMediaMs: Long, playbackSpeed: Float): Long {
  if (bufferedMediaMs <= 0L || playbackSpeed <= 0f) return 0L
  return round(bufferedMediaMs / playbackSpeed.toDouble()).toLong()
}

/** Pressing play after a pause does not mint a new server session. */
fun reopenStreamOnResume(pausedForMs: Long, sessionBoundUrl: Boolean): Boolean = false

fun streamUrlIsSessionBound(url: String): Boolean = url.contains("/public/session/")

fun describePlayerError(message: String?, errorCode: Int, causes: List<String>): String {
  val head = "${message ?: "Unknown Error"} | errorCode=$errorCode"
  if (causes.isEmpty()) return head
  return head + " | " + causes.joinToString(" | ")
}

fun httpStatusFromPlayerError(message: String?): Int? {
  if (message == null) return null
  val match = Regex("""Response code:\s*(\d{3})""").find(message) ?: return null
  return match.groupValues[1].toIntOrNull()
}

/** ExoPlayer [com.google.android.exoplayer2.Player.PlayWhenReadyChangeReason] values. */
fun pauseReasonLabel(reason: Int): String {
  return when (reason) {
    1 -> "user"
    2 -> "audio-focus-loss"
    3 -> "becoming-noisy"
    4 -> "remote"
    5 -> "end-of-item"
    else -> "unknown-$reason"
  }
}
