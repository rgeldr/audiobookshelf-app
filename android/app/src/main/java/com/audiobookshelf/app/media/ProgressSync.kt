package com.audiobookshelf.app.media

enum class PlaybackContinuation {
  KeepPlaying
}

enum class SyncChannel {
  OpenSession,
  LocalSession
}

enum class SyncFailureKind {
  SessionMissing,
  Retryable
}

data class ProgressMark(val currentTime: Double, val updatedAt: Long)

const val SYNC_NOTICE_INTERVAL_MS = 300_000L

fun playbackContinuationAfterSyncFailure(): PlaybackContinuation = PlaybackContinuation.KeepPlaying

/**
 * Consecutive failures keep climbing. Showing the notice must not set the count back to 0,
 * which made the log read 1, 0, 1, 0.
 */
fun nextFailedSyncCount(current: Int): Int = current + 1

/** A 404 on the open session means the stream URL will die once the buffer drains. A timeout does not. */
fun reopenStreamAfterSyncFailure(kind: SyncFailureKind): Boolean = kind == SyncFailureKind.SessionMissing

fun httpStatusFromSyncError(message: String?): Int? {
  if (message == null) return null
  val match = Regex("""code=(\d{3})""").find(message) ?: return null
  return match.groupValues[1].toIntOrNull()
}

fun failureKind(message: String?): SyncFailureKind {
  if (httpStatusFromSyncError(message) == 404) return SyncFailureKind.SessionMissing
  return SyncFailureKind.Retryable
}

fun syncTargetAfterFailure(current: SyncChannel, kind: SyncFailureKind): SyncChannel {
  if (current == SyncChannel.OpenSession && kind == SyncFailureKind.SessionMissing) return SyncChannel.LocalSession
  return current
}

/** A server progress newer than our last successful sync belongs to another device. */
fun shouldSendProgress(lastSuccessfulSyncAt: Long, serverUpdatedAt: Long?): Boolean {
  if (serverUpdatedAt == null) return true
  return serverUpdatedAt <= lastSuccessfulSyncAt
}

fun latestProgress(current: ProgressMark?, update: ProgressMark): ProgressMark {
  if (current == null || update.updatedAt >= current.updatedAt) return update
  return current
}

fun shouldShowSyncNotice(now: Long, lastShownAt: Long): Boolean {
  return lastShownAt <= 0L || now - lastShownAt >= SYNC_NOTICE_INTERVAL_MS
}
