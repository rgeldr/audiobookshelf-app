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
  Playing,
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
  return snapshot == PlaybackSnapshot.ReadyPaused ||
          snapshot == PlaybackSnapshot.Ended ||
          snapshot == PlaybackSnapshot.Playing
}

fun shouldReplaceResumeBookmark(snapshot: PlaybackSnapshot, positionSeconds: Double, hasBookmark: Boolean): Boolean {
  if (!shouldWriteResumeBookmark(snapshot)) return false
  if (snapshot == PlaybackSnapshot.Playing && positionSeconds <= 0.0 && hasBookmark) return false
  return true
}

const val RESUME_FROM_WIDGET_ACTION = "com.audiobookshelf.app.action.RESUME_FROM_WIDGET"

enum class WidgetPlayAction {
  MediaButton,
  OpenAppAndResume,
  ResumeService
}

enum class WidgetPlayDelivery {
  MediaButton,
  ForegroundService,
  Activity
}

enum class WidgetRegion {
  Body,
  Play,
  Seek
}

enum class WidgetTap {
  OpenApp,
  OpenAppAndResume,
  MediaButton,
  ResumeService
}

const val WIDGET_OPEN_APP_REQUEST_CODE = 1
const val WIDGET_ACTIVITY_RESUME_REQUEST_CODE = 2
const val WIDGET_SERVICE_RESUME_REQUEST_CODE = 3

/** PendingIntent.getForegroundService exists from API 26. Older widgets fall back to the activity. */
const val FOREGROUND_SERVICE_RESUME_SDK = 26

fun widgetPlayDelivery(playing: Boolean, sdkInt: Int): WidgetPlayDelivery {
  if (playing) return WidgetPlayDelivery.MediaButton
  if (sdkInt >= FOREGROUND_SERVICE_RESUME_SDK) return WidgetPlayDelivery.ForegroundService
  return WidgetPlayDelivery.Activity
}

fun widgetPlayAction(playing: Boolean, sdkInt: Int): WidgetPlayAction {
  return when (widgetPlayDelivery(playing, sdkInt)) {
    WidgetPlayDelivery.MediaButton -> WidgetPlayAction.MediaButton
    WidgetPlayDelivery.ForegroundService -> WidgetPlayAction.ResumeService
    WidgetPlayDelivery.Activity -> WidgetPlayAction.OpenAppAndResume
  }
}

fun widgetTap(region: WidgetRegion, playing: Boolean, sdkInt: Int): WidgetTap {
  return when (region) {
    WidgetRegion.Body -> WidgetTap.OpenApp
    WidgetRegion.Seek -> WidgetTap.MediaButton
    WidgetRegion.Play ->
            when (widgetPlayDelivery(playing, sdkInt)) {
              WidgetPlayDelivery.MediaButton -> WidgetTap.MediaButton
              WidgetPlayDelivery.ForegroundService -> WidgetTap.ResumeService
              WidgetPlayDelivery.Activity -> WidgetTap.OpenAppAndResume
            }
  }
}

/** A real pause, including audio-focus loss, must repaint before an OEM can kill the process. */
fun shouldRepaintWidgetForPause(playWhenReady: Boolean): Boolean = !playWhenReady

enum class WidgetControl {
  Cover,
  Play,
  Rewind,
  FastForward
}

/** PlaybackStateCompat transport flags. Each one becomes a different media-button key code. */
const val WIDGET_MEDIA_PLAY_PAUSE = 512L
const val WIDGET_MEDIA_REWIND = 8L
const val WIDGET_MEDIA_FAST_FORWARD = 64L

data class WidgetControlIntent(val tap: WidgetTap, val mediaAction: Long?)

fun widgetControlIntent(control: WidgetControl, playing: Boolean, sdkInt: Int): WidgetControlIntent {
  val tap =
          when (control) {
            WidgetControl.Cover -> WidgetTap.OpenApp
            WidgetControl.Play -> widgetTap(WidgetRegion.Play, playing, sdkInt)
            WidgetControl.Rewind, WidgetControl.FastForward -> widgetTap(WidgetRegion.Seek, playing, sdkInt)
          }
  val mediaAction =
          if (tap == WidgetTap.MediaButton) {
            when (control) {
              WidgetControl.Play -> WIDGET_MEDIA_PLAY_PAUSE
              WidgetControl.Rewind -> WIDGET_MEDIA_REWIND
              WidgetControl.FastForward -> WIDGET_MEDIA_FAST_FORWARD
              WidgetControl.Cover -> null
            }
          } else {
            null
          }
  return WidgetControlIntent(tap, mediaAction)
}

enum class WidgetServiceCommand {
  None,
  TogglePlayback,
  Play,
  Pause,
  Rewind,
  FastForward,
  Stop
}

/**
 * Widget pending intents from MediaButtonReceiver carry ACTION_DOWN only (key action 0).
 * Play/pause is 85, rewind is 89, and fast-forward is 90. The matching ACTION_UP is
 * ignored so a second event does not run the command again. Dedicated play (126),
 * pause (127), and stop (86) keys still arrive as ACTION_UP.
 */
fun widgetMediaCommand(keyAction: Int, keyCode: Int): WidgetServiceCommand {
  if (keyAction == 0) {
    return when (keyCode) {
      85, 79 -> WidgetServiceCommand.TogglePlayback
      89 -> WidgetServiceCommand.Rewind
      90 -> WidgetServiceCommand.FastForward
      else -> WidgetServiceCommand.None
    }
  }
  if (keyAction == 1) {
    return when (keyCode) {
      126 -> WidgetServiceCommand.Play
      127 -> WidgetServiceCommand.Pause
      86 -> WidgetServiceCommand.Stop
      else -> WidgetServiceCommand.None
    }
  }
  return WidgetServiceCommand.None
}

/** Play while already playing must pause. Play while paused or empty still resumes. */
fun widgetResumeTransport(): TransportButton = TransportButton.PlayPause

fun widgetSeekAllowed(player: LoadedPlayer): Boolean = player != LoadedPlayer.Empty

fun widgetResumeIntentAction(): String = RESUME_FROM_WIDGET_ACTION

fun shouldRestoreWidgetFromBookmark(serviceStarted: Boolean): Boolean = !serviceStarted

fun shouldResumeFromWidget(action: String?): Boolean = action == RESUME_FROM_WIDGET_ACTION

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

fun coldWidgetPlay(keyAction: Int, keyCode: Int, playerEmpty: Boolean, hasBookmark: Boolean): Boolean {
  return keyAction == 0 && keyCode == 85 && playerEmpty && hasBookmark
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

const val RESUME_MEDIA_ID = "resume-bookmark"
const val RECENT_MEDIA_ROOT = "__RESUME__"

data class WidgetControls(val play: Boolean, val seek: Boolean)

fun widgetControls(serviceClosed: Boolean, hasBookmark: Boolean): WidgetControls {
  if (!serviceClosed) return WidgetControls(play = true, seek = true)
  if (hasBookmark) return WidgetControls(play = true, seek = false)
  return WidgetControls(play = false, seek = false)
}

enum class BrowserDecision {
  AutoRoot,
  RecentRoot,
  Reject
}

fun browserRootFor(
        packageName: String,
        recentHint: Boolean,
        hasBookmark: Boolean,
        isAutoClient: Boolean
): BrowserDecision {
  if (isAutoClient) return BrowserDecision.AutoRoot
  if (recentHint && hasBookmark && packageName == "com.android.systemui") return BrowserDecision.RecentRoot
  return BrowserDecision.Reject
}

enum class PlayRequest {
  ResumeBookmark,
  Browse
}

fun playRequest(mediaId: String?, hasBookmark: Boolean, playerEmpty: Boolean): PlayRequest {
  if (!hasBookmark) return PlayRequest.Browse
  if (mediaId == RESUME_MEDIA_ID) return PlayRequest.ResumeBookmark
  if (playerEmpty && mediaId == null) return PlayRequest.ResumeBookmark
  return PlayRequest.Browse
}

fun connectionConfigIdForResume(bookmarkConfigId: String?, configIds: List<String>, activeId: String?): String? {
  if (bookmarkConfigId != null) {
    return if (configIds.contains(bookmarkConfigId)) bookmarkConfigId else null
  }
  if (activeId != null && configIds.contains(activeId)) return activeId
  if (configIds.size == 1) return configIds[0]
  return null
}
