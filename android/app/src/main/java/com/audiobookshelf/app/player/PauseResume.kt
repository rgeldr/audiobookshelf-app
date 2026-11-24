package com.audiobookshelf.app.player

enum class PauseStopReason {
  UserPause,
  SleepTimer,
  NotificationDismiss,
  UserClose
}

enum class ForegroundChange {
  Promote,
  DemoteKeepNotification,
  RemoveNotification,
  None
}

enum class TransportButton {
  Play,
  Pause,
  PlayPause,
  Stop,
  Other
}

enum class LoadedPlayer {
  Empty,
  Paused,
  Playing,
  Ended
}

enum class PlaybackSnapshot {
  ReadyPaused,
  Ended,
  Other
}

data class LoadedIdentity(
        val serverLibraryItemId: String?,
        val serverEpisodeId: String?,
        val localLibraryItemId: String?,
        val localEpisodeId: String?
)

data class MediaNotificationSpec(val attachSession: Boolean, val includeTransportActions: Boolean)

enum class ResumeAction {
  PlayLoaded,
  PauseAndKeep,
  ResumeFromBookmark,
  StopAndClear,
  Ignore
}

enum class ResumeSource {
  AlreadyLoaded,
  LocalFile,
  NewServerSession,
  Unavailable
}

data class ResumeBookmark(
        val libraryItemId: String,
        val episodeId: String?,
        val local: Boolean,
        val positionSeconds: Double,
        val savedAt: Long,
        val serverConnectionConfigId: String?,
        val queue: List<PlaybackQueueEntry>,
        val serverLibraryItemId: String?,
        val serverEpisodeId: String?
)

data class DownloadedEpisode(val localEpisodeId: String, val serverEpisodeId: String?)

data class DownloadedItem(
        val localLibraryItemId: String,
        val serverLibraryItemId: String?,
        val episodes: List<DownloadedEpisode>
)

data class ResumeChoice(
        val source: ResumeSource,
        val localLibraryItemId: String?,
        val localEpisodeId: String?,
        val serverLibraryItemId: String?,
        val serverEpisodeId: String?,
        val queue: List<PlaybackQueueEntry>
)

fun foregroundChangeFor(reason: PauseStopReason): ForegroundChange {
  // A user pause keeps the mediaPlayback foreground service. Android 15 shows the
  // session metadata with no transport controls once that service is no longer foreground.
  if (reason == PauseStopReason.UserPause) return ForegroundChange.None
  return ForegroundChange.RemoveNotification
}

fun foregroundChangeFor(action: ResumeAction): ForegroundChange {
  return when (action) {
    ResumeAction.PlayLoaded, ResumeAction.ResumeFromBookmark -> ForegroundChange.Promote
    ResumeAction.PauseAndKeep -> ForegroundChange.None
    ResumeAction.StopAndClear -> ForegroundChange.RemoveNotification
    ResumeAction.Ignore -> ForegroundChange.None
  }
}

fun resumeAction(button: TransportButton, player: LoadedPlayer, hasBookmark: Boolean): ResumeAction {
  return when (button) {
    TransportButton.Stop -> ResumeAction.PauseAndKeep
    TransportButton.Pause ->
            if (player == LoadedPlayer.Playing) ResumeAction.PauseAndKeep else ResumeAction.Ignore
    TransportButton.Other -> ResumeAction.Ignore
    TransportButton.Play, TransportButton.PlayPause ->
            when (player) {
              LoadedPlayer.Playing ->
                      if (button == TransportButton.Play) ResumeAction.Ignore
                      else ResumeAction.PauseAndKeep
              LoadedPlayer.Paused, LoadedPlayer.Ended -> ResumeAction.PlayLoaded
              LoadedPlayer.Empty ->
                      if (hasBookmark) ResumeAction.ResumeFromBookmark else ResumeAction.Ignore
            }
  }
}

fun mustForegroundImmediately(action: ResumeAction): Boolean {
  return action == ResumeAction.PlayLoaded || action == ResumeAction.ResumeFromBookmark
}

enum class MediaButtonDelivery {
  Dispatch,
  KeepPausedNotification,
  StopEmptyService
}

fun mustAcknowledgeMediaButtonStart(): Boolean = true

fun releasesForegroundAfter(delivery: MediaButtonDelivery): Boolean {
  return delivery == MediaButtonDelivery.StopEmptyService
}

fun mediaNotificationSpec(): MediaNotificationSpec {
  return MediaNotificationSpec(attachSession = true, includeTransportActions = true)
}

fun shouldPlayLoadedItemInsteadOfBookmark(player: LoadedPlayer, bookmarkMatchesLoaded: Boolean): Boolean {
  if (player == LoadedPlayer.Empty) return false
  if (player == LoadedPlayer.Ended || player == LoadedPlayer.Playing) return true
  return !bookmarkMatchesLoaded
}

fun playRestartsFromBeginning(player: LoadedPlayer): Boolean = player == LoadedPlayer.Ended

fun shouldReplayFromStart(player: LoadedPlayer, positionMs: Long, durationMs: Long): Boolean {
  if (playRestartsFromBeginning(player)) return true
  if (durationMs <= 0L) return false
  return durationMs - positionMs <= 1_500L
}

fun bookmarkMatchesLoadedItem(bookmark: ResumeBookmark, loaded: LoadedIdentity): Boolean {
  if (bookmark.local && loaded.localLibraryItemId != null) {
    return loaded.localLibraryItemId == bookmark.libraryItemId &&
            loaded.localEpisodeId == bookmark.episodeId
  }
  val bookmarkItem = bookmark.serverLibraryItemId ?: if (!bookmark.local) bookmark.libraryItemId else null
  val bookmarkEpisode = bookmark.serverEpisodeId ?: if (!bookmark.local) bookmark.episodeId else null
  if (bookmarkItem == null || loaded.serverLibraryItemId == null) return false
  return loaded.serverLibraryItemId == bookmarkItem && loaded.serverEpisodeId == bookmarkEpisode
}

fun shouldWriteResumeBookmark(snapshot: PlaybackSnapshot): Boolean {
  return snapshot == PlaybackSnapshot.ReadyPaused || snapshot == PlaybackSnapshot.Ended
}

fun keepTransportControlsAfterEnd(action: PlaybackEndAction): Boolean {
  return action == PlaybackEndAction.Stop
}

fun pausedHoldPositionMs(durationMs: Long): Long? {
  if (durationMs <= 1_500L) return null
  return durationMs - 1_000L
}

fun mediaButtonDelivery(
        action: ResumeAction,
        alreadyForeground: Boolean,
        player: LoadedPlayer
): MediaButtonDelivery {
  if (alreadyForeground || mustForegroundImmediately(action)) return MediaButtonDelivery.Dispatch
  if (player == LoadedPlayer.Empty) return MediaButtonDelivery.StopEmptyService
  return MediaButtonDelivery.KeepPausedNotification
}

fun warmResumeNeedsNewServerSession(local: Boolean, serverSessionFound: Boolean): Boolean {
  return !local && !serverSessionFound
}

fun positionToResume(
        bookmark: ResumeBookmark,
        otherPositionSeconds: Double?,
        otherUpdatedAt: Long?
): Double {
  if (otherPositionSeconds == null || otherUpdatedAt == null) return bookmark.positionSeconds
  if (otherUpdatedAt > bookmark.savedAt) return otherPositionSeconds
  return bookmark.positionSeconds
}

fun transportButton(keyCode: Int): TransportButton {
  return when (keyCode) {
    126 -> TransportButton.Play
    127 -> TransportButton.Pause
    85, 79 -> TransportButton.PlayPause
    86 -> TransportButton.Stop
    else -> TransportButton.Other
  }
}

fun resumeBookmarkFrom(
        isLocal: Boolean,
        sessionLibraryItemId: String?,
        sessionEpisodeId: String?,
        localLibraryItemId: String?,
        localEpisodeId: String?,
        positionSeconds: Double,
        savedAt: Long,
        serverConnectionConfigId: String?,
        queue: List<PlaybackQueueEntry>
): ResumeBookmark? {
  val copiedQueue = queue.toList()
  if (isLocal) {
    val localId = localLibraryItemId ?: return null
    return ResumeBookmark(
            libraryItemId = localId,
            episodeId = localEpisodeId,
            local = true,
            positionSeconds = positionSeconds,
            savedAt = savedAt,
            serverConnectionConfigId = serverConnectionConfigId,
            queue = copiedQueue,
            serverLibraryItemId = sessionLibraryItemId,
            serverEpisodeId = sessionEpisodeId
    )
  }
  val serverId = sessionLibraryItemId ?: return null
  return ResumeBookmark(
          libraryItemId = serverId,
          episodeId = sessionEpisodeId,
          local = false,
          positionSeconds = positionSeconds,
          savedAt = savedAt,
          serverConnectionConfigId = serverConnectionConfigId,
          queue = copiedQueue,
          serverLibraryItemId = serverId,
          serverEpisodeId = sessionEpisodeId
  )
}

fun chooseResume(
        bookmark: ResumeBookmark,
        downloads: List<DownloadedItem>,
        player: LoadedPlayer
): ResumeChoice {
  val localMatch = matchingDownload(bookmark, downloads)
  if (localMatch != null) {
    return ResumeChoice(
            source = ResumeSource.LocalFile,
            localLibraryItemId = localMatch.localLibraryItemId,
            localEpisodeId = localMatch.localEpisodeId,
            serverLibraryItemId = null,
            serverEpisodeId = null,
            queue = bookmark.queue
    )
  }

  val serverLibraryItemId = serverLibraryItemId(bookmark)
  val serverEpisodeId = serverEpisodeId(bookmark)
  if (player != LoadedPlayer.Empty) {
    return ResumeChoice(
            source = ResumeSource.AlreadyLoaded,
            localLibraryItemId = null,
            localEpisodeId = null,
            serverLibraryItemId = serverLibraryItemId,
            serverEpisodeId = serverEpisodeId,
            queue = bookmark.queue
    )
  }
  if (serverLibraryItemId != null) {
    return ResumeChoice(
            source = ResumeSource.NewServerSession,
            localLibraryItemId = null,
            localEpisodeId = null,
            serverLibraryItemId = serverLibraryItemId,
            serverEpisodeId = serverEpisodeId,
            queue = bookmark.queue
    )
  }
  return ResumeChoice(
          source = ResumeSource.Unavailable,
          localLibraryItemId = null,
          localEpisodeId = null,
          serverLibraryItemId = null,
          serverEpisodeId = null,
          queue = bookmark.queue
  )
}

private data class LocalMatch(val localLibraryItemId: String, val localEpisodeId: String?)

private fun matchingDownload(bookmark: ResumeBookmark, downloads: List<DownloadedItem>): LocalMatch? {
  if (bookmark.local) {
    val byLocalId = localItemMatch(
            downloads.find { it.localLibraryItemId == bookmark.libraryItemId },
            bookmark.episodeId
    )
    if (byLocalId != null) return byLocalId
  }
  val serverLibraryItemId = serverLibraryItemId(bookmark) ?: return null
  val serverEpisodeId = serverEpisodeId(bookmark)
  val item = downloads.find { it.serverLibraryItemId == serverLibraryItemId } ?: return null
  if (serverEpisodeId == null) {
    if (item.episodes.isEmpty()) return LocalMatch(item.localLibraryItemId, null)
    return null
  }
  val episode = item.episodes.find { it.serverEpisodeId == serverEpisodeId } ?: return null
  return LocalMatch(item.localLibraryItemId, episode.localEpisodeId)
}

private fun localItemMatch(item: DownloadedItem?, episodeId: String?): LocalMatch? {
  if (item == null) return null
  if (episodeId == null) {
    if (item.episodes.isEmpty()) return LocalMatch(item.localLibraryItemId, null)
    return null
  }
  val episode = item.episodes.find { it.localEpisodeId == episodeId } ?: return null
  return LocalMatch(item.localLibraryItemId, episode.localEpisodeId)
}

private fun serverLibraryItemId(bookmark: ResumeBookmark): String? {
  return bookmark.serverLibraryItemId ?: if (!bookmark.local) bookmark.libraryItemId else null
}

private fun serverEpisodeId(bookmark: ResumeBookmark): String? {
  return bookmark.serverEpisodeId ?: if (!bookmark.local) bookmark.episodeId else null
}
