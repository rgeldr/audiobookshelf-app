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
  // An expired access token must not tear the player down. The buffer and the notification stay.
  if (httpStatus == 401 || httpStatus == 403) return StreamFailureAction.KeepPlaying
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

const val PAUSE_IN_APP = "in-app"
const val PAUSE_MEDIA_BUTTON = "media-button"
const val PAUSE_HEADSET = "headset"
const val PAUSE_MEDIA_SESSION = "media-session"
const val PAUSE_NOTIFICATION = "notification"
const val PAUSE_PLAYBACK_ENDED = "playback-ended"

@Volatile private var pendingPauseOrigin: String? = null

fun notePauseOrigin(origin: String) {
  pendingPauseOrigin = origin
}

fun consumePauseOrigin(pausing: Boolean): String? {
  val origin = pendingPauseOrigin
  pendingPauseOrigin = null
  return if (pausing) origin else null
}

/** 79 headset hook, 85 play/pause, 127 pause. Lock screen and Bluetooth share [PAUSE_MEDIA_SESSION]. */
fun pauseOriginForKeyCode(keyCode: Int): String {
  return if (keyCode == 79) PAUSE_HEADSET else PAUSE_MEDIA_BUTTON
}

/**
 * ExoPlayer reports in-app, notification, lock screen, and headset pauses as reason 1.
 * The origin noted at the call site names those. An untagged reason 1 is the notification
 * broadcast, which calls `player.pause()` itself.
 * Reason 3 is the headphone-unplug pause and stays `becoming-noisy`.
 */
fun pauseReasonLabel(reason: Int, origin: String? = null): String {
  return when (reason) {
    1 -> origin ?: PAUSE_NOTIFICATION
    2 -> "audio-focus-loss"
    3 -> "becoming-noisy"
    4 -> "remote"
    5 -> "end-of-item"
    else -> "unknown-$reason"
  }
}
