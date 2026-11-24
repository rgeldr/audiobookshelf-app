package com.audiobookshelf.app.media

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.MediaProgress
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.server.ApiHandler
import java.util.*
import kotlin.concurrent.schedule

data class MediaProgressSyncData(
        var timeListened: Long, // seconds
        var duration: Double, // seconds
        var currentTime: Double // seconds
)

data class SyncResult(
        var serverSyncAttempted: Boolean,
        var serverSyncSuccess: Boolean?,
        var serverSyncMessage: String?
)

class MediaProgressSyncer(
        val playerNotificationService: PlayerNotificationService,
        private val apiHandler: ApiHandler
) {
  private val tag = "MediaProgressSync"
  private val METERED_CONNECTION_SYNC_INTERVAL = 60000

  private var listeningTimerTask: TimerTask? = null
  var listeningTimerRunning: Boolean = false

  private var lastSyncTime: Long = 0
  private var failedSyncs: Int = 0
  private var syncChannel = SyncChannel.OpenSession
  private var pendingServerSync = false
  private var lastNoticeAt = 0L
  private var lastSuccessfulSyncAt = 0L
  private var pendingMark: ProgressMark? = null

  var currentPlaybackSession: PlaybackSession? = null // copy of pb session currently syncing
  var currentLocalMediaProgress: LocalMediaProgress? = null

  private val currentDisplayTitle
    get() = currentPlaybackSession?.displayTitle ?: "Unset"
  val currentIsLocal
    get() = currentPlaybackSession?.isLocal == true
  val currentSessionId
    get() = currentPlaybackSession?.id ?: ""
  private val currentPlaybackDuration
    get() = currentPlaybackSession?.duration ?: 0.0

  fun start(playbackSession: PlaybackSession) {
    if (listeningTimerRunning) {
      Log.d(tag, "start: Timer already running for $currentDisplayTitle")
      if (playbackSession.id != currentSessionId) {
        Log.d(tag, "Playback session changed, reset timer")
        currentLocalMediaProgress = null
        listeningTimerTask?.cancel()
        lastSyncTime = 0L
        Log.d(tag, "start: Set last sync time 0 $lastSyncTime")
        failedSyncs = 0
        syncChannel = SyncChannel.OpenSession
        pendingServerSync = false
        lastSuccessfulSyncAt = playbackSession.updatedAt
      } else {
        return
      }
    } else if (playbackSession.id != currentSessionId) {
      currentLocalMediaProgress = null
    }

    listeningTimerRunning = true
    lastSyncTime = System.currentTimeMillis()
    currentPlaybackSession = playbackSession.clone()
    if (lastSuccessfulSyncAt == 0L) lastSuccessfulSyncAt = playbackSession.updatedAt
    Log.d(
            tag,
            "start: init last sync time $lastSyncTime with playback session id=${currentPlaybackSession?.id}"
    )

    listeningTimerTask =
            Timer("ListeningTimer", false).schedule(15000L, 15000L) {
              Handler(Looper.getMainLooper()).post() {
                if (playerNotificationService.currentPlayer.isPlaying) {
                  // Set auto sleep timer if enabled and within start/end time
                  playerNotificationService.sleepTimerManager.checkAutoSleepTimer()

                  // Only sync with server on unmetered connection every 15s OR sync with server if
                  // last sync time is >= 60s
                  val shouldSyncServer =
                          PlayerNotificationService.isUnmeteredNetwork ||
                                  System.currentTimeMillis() - lastSyncTime >=
                                          METERED_CONNECTION_SYNC_INTERVAL

                  val currentTime = playerNotificationService.getCurrentTimeSeconds()
                  if (currentTime > 0) {
                    sync(shouldSyncServer, currentTime) { syncResult ->
                      Log.d(tag, "Sync complete")

                      currentPlaybackSession?.let { playbackSession ->
                        MediaEventManager.saveEvent(playbackSession, syncResult)
                      }
                    }
                  }
                }
              }
            }
  }

  fun play(playbackSession: PlaybackSession) {
    Log.d(tag, "play ${playbackSession.displayTitle}")
    MediaEventManager.playEvent(playbackSession)

    start(playbackSession)
  }

  fun stop(shouldSync: Boolean? = true, cb: () -> Unit) {
    if (!listeningTimerRunning) {
      reset()
      return cb()
    }

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    Log.d(tag, "stop: Stopping listening for $currentDisplayTitle")

    val currentTime =
            if (shouldSync == true) playerNotificationService.getCurrentTimeSeconds() else 0.0
    if (currentTime > 0) { // Current time should always be > 0 on stop
      sync(true, currentTime) { syncResult ->
        currentPlaybackSession?.let { playbackSession ->
          MediaEventManager.stopEvent(playbackSession, syncResult)
        }

        reset()
        cb()
      }
    } else {
      currentPlaybackSession?.let { playbackSession ->
        MediaEventManager.stopEvent(playbackSession, null)
      }

      reset()
      cb()
    }
  }

  fun pause(cb: () -> Unit) {
    if (!listeningTimerRunning) return

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    Log.d(tag, "pause: Pausing progress syncer for $currentDisplayTitle")
    Log.d(tag, "pause: Last sync time $lastSyncTime")

    val currentTime = playerNotificationService.getCurrentTimeSeconds()
    if (currentTime > 0) { // Current time should always be > 0 on pause
      sync(true, currentTime) { syncResult ->
        lastSyncTime = 0L
        Log.d(tag, "pause: Set last sync time 0 $lastSyncTime")
        failedSyncs = 0

        currentPlaybackSession?.let { playbackSession ->
          MediaEventManager.pauseEvent(playbackSession, syncResult)
        }

        cb()
      }
    } else {
      lastSyncTime = 0L
      Log.d(tag, "pause: Set last sync time 0 $lastSyncTime (current time < 0)")
      failedSyncs = 0

      currentPlaybackSession?.let { playbackSession ->
        MediaEventManager.pauseEvent(playbackSession, null)
      }

      cb()
    }
  }

  fun finished(cb: () -> Unit) {
    if (!listeningTimerRunning) return

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    Log.d(tag, "finished: Stopping listening for $currentDisplayTitle")

    sync(true, currentPlaybackSession?.duration ?: 0.0) { syncResult ->
      reset()

      currentPlaybackSession?.let { playbackSession ->
        MediaEventManager.finishedEvent(playbackSession, syncResult)
      }

      cb()
    }
  }

  fun seek() {
    currentPlaybackSession?.currentTime = playerNotificationService.getCurrentTimeSeconds()
    Log.d(tag, "seek: $currentDisplayTitle, currentTime=${currentPlaybackSession?.currentTime}")

    if (currentPlaybackSession == null) {
      Log.e(tag, "seek: Playback session not set")
      return
    }

    MediaEventManager.seekEvent(currentPlaybackSession!!, null)
  }

  // Currently unused
  fun syncFromServerProgress(mediaProgress: MediaProgress) {
    currentPlaybackSession?.let {
      it.updatedAt = mediaProgress.lastUpdate
      it.currentTime = mediaProgress.currentTime

      MediaEventManager.syncEvent(
              mediaProgress,
              "Received from server get media progress request while playback session open"
      )
      saveLocalProgress(it)
    }
  }

  fun sync(shouldSyncServer: Boolean, currentTime: Double, cb: (SyncResult?) -> Unit) {
    if (lastSyncTime <= 0) {
      Log.e(tag, "Last sync time is not set $lastSyncTime")
      return cb(null)
    }

    val diffSinceLastSync = System.currentTimeMillis() - lastSyncTime
    if (diffSinceLastSync < 1000L) {
      return cb(null)
    }
    val listeningTimeToAdd = diffSinceLastSync / 1000L

    val syncData = MediaProgressSyncData(listeningTimeToAdd, currentPlaybackDuration, currentTime)
    currentPlaybackSession?.syncData(syncData)

    if (currentPlaybackSession?.progress?.isNaN() == true) {
      Log.e(
              tag,
              "Current Playback Session invalid progress ${currentPlaybackSession?.progress} | Current Time: ${currentPlaybackSession?.currentTime} | Duration: ${currentPlaybackSession?.getTotalDuration()}"
      )
      return cb(null)
    }

    if (currentPlaybackSession != null) {
      playerNotificationService.rememberResumeBookmark()
    }

    val hasNetworkConnection = DeviceManager.checkConnectivity(playerNotificationService)

    // Save playback session to db (server linked sessions only)
    //   Sessions are removed once successfully synced with the server
    currentPlaybackSession?.let { DeviceManager.dbManager.savePlaybackSession(it) }

    if (currentIsLocal) {
      // Save local progress sync
      currentPlaybackSession?.let {
        saveLocalProgress(it)
        lastSyncTime = System.currentTimeMillis()

        Log.d(
                tag,
                "Sync local device current serverConnectionConfigId=${DeviceManager.serverConnectionConfig?.id}"
        )
        AbsLogger.info("MediaProgressSyncer", "sync: Saved local progress (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${it.id})")

        // Local library item is linked to a server library item
        // Send sync to server also if connected to this server and local item belongs to this
        // server
        val isConnectedToSameServer = it.serverConnectionConfigId != null && DeviceManager.serverConnectionConfig?.id == it.serverConnectionConfigId
        if (hasNetworkConnection &&
                        shouldSyncServer &&
                        !it.libraryItemId.isNullOrEmpty() &&
                        isConnectedToSameServer
        ) {
          rememberPending(currentTime)
          apiHandler.sendLocalProgressSync(it) { syncSuccess, errorMsg ->
            Handler(Looper.getMainLooper()).post {
              if (syncSuccess) noteSyncSuccess(it.id, currentTime)
              else noteSyncFailure(errorMsg, currentTime, SyncChannel.LocalSession)
              cb(SyncResult(true, syncSuccess, errorMsg))
            }
          }
        } else {
          AbsLogger.info("MediaProgressSyncer", "sync: Not sending local progress to server (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${it.id}) (hasNetworkConnection: $hasNetworkConnection) (isConnectedToSameServer: $isConnectedToSameServer)")
          cb(SyncResult(false, null, null))
        }
      }
    } else if (hasNetworkConnection && shouldSyncServer) {
      val session = currentPlaybackSession
      if (session == null) {
        cb(null)
      } else if (syncChannel == SyncChannel.LocalSession) {
        sendSavedProgress(session, currentTime, cb)
      } else {
        AbsLogger.info("MediaProgressSyncer", "sync: Sending progress sync to server (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: ${currentSessionId}) (${DeviceManager.serverConnectionConfigName})")
        rememberPending(currentTime)
        apiHandler.sendProgressSync(currentSessionId, syncData) { syncSuccess, errorMsg ->
          Handler(Looper.getMainLooper()).post {
            if (syncSuccess) noteSyncSuccess(currentSessionId, currentTime)
            else noteSyncFailure(errorMsg, currentTime, SyncChannel.OpenSession)
            cb(SyncResult(true, syncSuccess, errorMsg))
          }
        }
      }
    } else {
      if (!hasNetworkConnection) pendingServerSync = true
      AbsLogger.info("MediaProgressSyncer", "sync: Not sending progress to server (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: $currentSessionId) (${DeviceManager.serverConnectionConfigName}) (hasNetworkConnection: $hasNetworkConnection)")
      Log.i(tag, "Progress kept locally at $currentTime; playback continues")
      cb(SyncResult(false, null, null))
    }
  }

  fun onNetworkRestored() {
    if (!pendingServerSync) return
    if (lastSyncTime <= 0L) lastSyncTime = System.currentTimeMillis() - 1500L
    val currentTime =
            playerNotificationService.getCurrentTimeSeconds().takeIf { it > 0.0 }
                    ?: currentPlaybackSession?.currentTime
                    ?: return
    Log.i(tag, "Network restored; sending saved progress at $currentTime")
    sync(true, currentTime) {}
  }

  private fun rememberPending(currentTime: Double) {
    val updatedAt = currentPlaybackSession?.updatedAt ?: System.currentTimeMillis()
    pendingMark = latestProgress(pendingMark, ProgressMark(currentTime, updatedAt))
  }

  private fun sendSavedProgress(session: PlaybackSession, currentTime: Double, cb: (SyncResult?) -> Unit) {
    val libraryItemId = session.libraryItemId
    if (libraryItemId.isNullOrEmpty()) {
      Log.i(tag, "No server item for saved progress; playback continues")
      cb(SyncResult(false, null, null))
      return
    }
    rememberPending(currentTime)
    pendingMark?.let { mark ->
      if (mark.updatedAt >= session.updatedAt) session.currentTime = mark.currentTime
    }
    apiHandler.getMediaProgress(libraryItemId, session.episodeId, DeviceManager.serverConnectionConfig) { progress ->
      Handler(Looper.getMainLooper()).post {
        if (!shouldSendProgress(lastSuccessfulSyncAt, progress?.lastUpdate)) {
          Log.i(tag, "Server progress ${progress?.lastUpdate} is newer than last sync $lastSuccessfulSyncAt; leaving it in place")
          pendingServerSync = false
          cb(SyncResult(true, false, null))
          return@post
        }
        Log.i(tag, "Open session is gone; sending saved progress through the local session endpoint")
        apiHandler.sendLocalProgressSync(session) { syncSuccess, errorMsg ->
          Handler(Looper.getMainLooper()).post {
            if (syncSuccess) noteSyncSuccess(session.id, currentTime)
            else noteSyncFailure(errorMsg, currentTime, SyncChannel.LocalSession)
            cb(SyncResult(true, syncSuccess, errorMsg))
          }
        }
      }
    }
  }

  private fun noteSyncSuccess(sessionId: String, currentTime: Double) {
    failedSyncs = 0
    pendingServerSync = false
    pendingMark = null
    lastSuccessfulSyncAt = System.currentTimeMillis()
    lastSyncTime = lastSuccessfulSyncAt
    playerNotificationService.alertSyncSuccess()
    DeviceManager.dbManager.removePlaybackSession(sessionId)
    AbsLogger.info("MediaProgressSyncer", "sync: Successfully synced progress (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: $sessionId) channel=$syncChannel")
    Log.i(tag, "Progress sync succeeded at $currentTime channel=$syncChannel")
  }

  private fun noteSyncFailure(errorMsg: String?, currentTime: Double, attempted: SyncChannel) {
    val continuation = playbackContinuationAfterSyncFailure()
    val kind = failureKind(errorMsg)
    val next = syncTargetAfterFailure(attempted, kind)
    syncChannel = next
    pendingServerSync = true
    failedSyncs++
    val now = System.currentTimeMillis()
    AbsLogger.error("MediaProgressSyncer", "sync: Progress sync failed kind=$kind channel=$syncChannel continuation=$continuation (count: $failedSyncs) (title: \"$currentDisplayTitle\") (currentTime: $currentTime) (session id: $currentSessionId) (${DeviceManager.serverConnectionConfigName}) ($errorMsg)")
    Log.i(tag, "Progress sync failed kind=$kind channel=$syncChannel continuation=$continuation currentTime=$currentTime; playback continues")
    if (shouldShowSyncNotice(now, lastNoticeAt)) {
      lastNoticeAt = now
      playerNotificationService.alertSyncFailing()
    }
    if (attempted == SyncChannel.OpenSession && next == SyncChannel.LocalSession) {
      currentPlaybackSession?.let { sendSavedProgress(it, currentTime) {} }
    }
  }

  private fun saveLocalProgress(playbackSession: PlaybackSession) {
    if (currentLocalMediaProgress == null) {
      val mediaProgress =
              DeviceManager.dbManager.getLocalMediaProgress(playbackSession.localMediaProgressId)
      if (mediaProgress == null) {
        currentLocalMediaProgress = playbackSession.getNewLocalMediaProgress()
      } else {
        currentLocalMediaProgress = mediaProgress
        currentLocalMediaProgress?.updateFromPlaybackSession(playbackSession)
      }
    } else {
      currentLocalMediaProgress?.updateFromPlaybackSession(playbackSession)
    }

    currentLocalMediaProgress?.let {
      if (it.progress.isNaN()) {
        Log.e(tag, "Invalid progress on local media progress")
      } else {
        DeviceManager.dbManager.saveLocalMediaProgress(it)
        playerNotificationService.clientEventEmitter?.onLocalMediaProgressUpdate(it)
        Log.d(
                tag,
                "Saved Local Progress Current Time: ID ${it.id} | ${it.currentTime} | Duration ${it.duration} | Progress ${it.progressPercent}%"
        )
      }
    }
  }

  fun reset() {
    currentPlaybackSession = null
    currentLocalMediaProgress = null
    lastSyncTime = 0L
    Log.d(tag, "reset: Set last sync time 0 $lastSyncTime")
    failedSyncs = 0
    syncChannel = SyncChannel.OpenSession
    pendingServerSync = false
    pendingMark = null
    lastNoticeAt = 0L
    lastSuccessfulSyncAt = 0L
  }
}
