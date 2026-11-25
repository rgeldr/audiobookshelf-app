package com.audiobookshelf.app.player

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.view.KeyEvent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.*
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.VolumeProviderCompat
import android.media.AudioManager
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.data.DeviceInfo
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.managers.SleepTimerManager
import com.audiobookshelf.app.media.MediaManager
import com.audiobookshelf.app.media.MediaProgressSyncer
import com.audiobookshelf.app.media.getUriToAbsIconDrawable
import com.audiobookshelf.app.media.getUriToDrawable
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.server.ApiHandler
import com.audiobookshelf.app.server.mayWaitForRefresh
import com.audiobookshelf.app.server.planUnpause
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector.CustomActionProvider
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.extractor.mp3.Mp3Extractor
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.*
import java.util.*
import kotlin.concurrent.schedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

const val SLEEP_TIMER_WAKE_UP_EXPIRATION = 120000L // 2m
const val PLAYER_CAST = "cast-player"
const val PLAYER_EXO = "exo-player"

class PlayerNotificationService : MediaBrowserServiceCompat() {

  companion object {
    var isStarted = false
    var isClosed = false
    var isUnmeteredNetwork = false
    var hasNetworkConnectivity = false // Not 100% reliable has internet
    var isSwitchingPlayer = false // Used when switching between cast player and exoplayer
  }

  private val tag = "PlayerNotificationServ"
  private var holdingEndedSession = false

  interface ClientEventEmitter {
    fun onPlaybackSession(playbackSession: PlaybackSession)
    fun onPlaybackClosed()
    fun onPlayingUpdate(isPlaying: Boolean)
    fun onMetadata(metadata: PlaybackMetadata)
    fun onSleepTimerEnded(currentPosition: Long)
    fun onSleepTimerSet(sleepTimeRemaining: Int, isAutoSleepTimer: Boolean)
    fun onLocalMediaProgressUpdate(localMediaProgress: LocalMediaProgress)
    fun onPlaybackFailed(errorMessage: String)
    fun onMediaPlayerChanged(mediaPlayer: String)
    fun onProgressSyncFailing()
    fun onProgressSyncSuccess()
    fun onNetworkMeteredChanged(isUnmetered: Boolean)
    fun onMediaItemHistoryUpdated(mediaItemHistory: MediaItemHistory)
    fun onPlaybackSpeedChanged(playbackSpeed: Float)
  }
  private val binder = LocalBinder()

  var clientEventEmitter: ClientEventEmitter? = null

  private lateinit var ctx: Context
  private lateinit var mediaSessionConnector: MediaSessionConnector
  private lateinit var playerNotificationManager: PlayerNotificationManager
  lateinit var mediaSession: MediaSessionCompat
  private var remoteVolumeProvider: VolumeProviderCompat? = null
  private lateinit var transportControls: MediaControllerCompat.TransportControls

  lateinit var mediaManager: MediaManager
  lateinit var apiHandler: ApiHandler

  lateinit var mPlayer: ExoPlayer
  private lateinit var streamingLoadControl: StreamingLoadControl
  private val streamingLoadErrorPolicy = StreamingLoadErrorPolicy()
  lateinit var currentPlayer: Player
  var castPlayer: CastPlayer? = null

  lateinit var sleepTimerManager: SleepTimerManager
  lateinit var mediaProgressSyncer: MediaProgressSyncer

  private var notificationId = 10
  private var channelId = "audiobookshelf_channel"
  private var channelName = "Audiobookshelf Channel"

  var currentPlaybackSession: PlaybackSession? = null
  private var initialPlaybackRate: Float? = null

  private val metadataScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private var metadataArtJob: Job? = null

  private var isAndroidAuto = false
  val playbackQueue = PlaybackQueue()
  var autoAdvanceGeneration: Int = 0
  private var resumeInFlight = false
  private var warmCheckInFlight = false
  private var suppressResumeBookmark = false
  private var closingPlayback = false
  private var streamReopenAttempted = false
  private var streamRecoveryInFlight = false

  // The following are used for the shake detection
  private var isShakeSensorRegistered: Boolean = false
  private var mSensorManager: SensorManager? = null
  private var mAccelerometer: Sensor? = null
  private var mShakeDetector: ShakeDetector? = null
  private var shakeSensorUnregisterTask: TimerTask? = null

  // These are used to trigger reloading if
  private var forceReloadingAndroidAuto: Boolean = false
  private var firstLoadDone: Boolean = false

  fun isBrowseTreeInitialized(): Boolean {
    return this::browseTree.isInitialized
  }

  // Cache latest search so it wont trigger again when returning from series for example
  private var cachedSearch: String = ""
  private var cachedSearchResults: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()

  /*
     Service related stuff
  */
  override fun onBind(intent: Intent): IBinder? {
    Log.d(tag, "onBind")

    // Android Auto Media Browser Service
    if (SERVICE_INTERFACE == intent.action) {
      Log.d(tag, "Is Media Browser Service")
      return super.onBind(intent)
    }
    return binder
  }

  inner class LocalBinder : Binder() {
    // Return this instance of LocalService so clients can call public methods
    fun getService(): PlayerNotificationService = this@PlayerNotificationService
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    isStarted = true
    Log.i(
            tag,
            "onStartCommand action=${intent?.action} startId=$startId foreground=${PlayerNotificationListener.isForegroundService}"
    )
    if (shouldResumeFromWidget(intent?.action)) {
      Log.i(tag, "widget resume action")
      deliverMediaButton(TransportButton.Play)
      return START_STICKY
    }
    if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
      val keyEvent =
              if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
              } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
              }
      if (keyEvent?.action == KeyEvent.ACTION_UP) {
        val button = transportButton(keyEvent.keyCode)
        if (button != TransportButton.Other) {
          deliverMediaButton(button)
        }
      } else if (keyEvent?.action == KeyEvent.ACTION_DOWN && keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
        val hasBookmark = try {
          DeviceManager.dbManager.getResumeBookmark() != null
        } catch (_: Throwable) {
          false
        }
        if (coldWidgetPlay(keyEvent.action, keyEvent.keyCode, loadedPlayer() == LoadedPlayer.Empty, hasBookmark)) {
          deliverMediaButton(TransportButton.PlayPause)
        }
      }
    }

    return START_STICKY
  }

  @Deprecated("Deprecated in Java")
  override fun onStart(intent: Intent?, startId: Int) {
    Log.d(tag, "onStart $startId")
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun createNotificationChannel(channelId: String, channelName: String): String {
    val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
    chan.lightColor = Color.DKGRAY
    chan.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    service.createNotificationChannel(chan)
    return channelId
  }

  // detach player
  override fun onDestroy() {
    try {
      val connectivityManager =
              getSystemService(ConnectivityManager::class.java) as ConnectivityManager
      connectivityManager.unregisterNetworkCallback(networkCallback)
    } catch (error: Exception) {
      Log.e(tag, "Error unregistering network listening callback $error")
    }

    AbsLogger.info(tag, "onDestroy")
    Log.i(tag, "onDestroy; media session released. Widget keeps play when a resume bookmark exists")
    isStarted = false
    isClosed = true
    DeviceManager.widgetUpdater?.onPlayerChanged(this)

    playerNotificationManager.setPlayer(null)
    mPlayer.release()
    castPlayer?.release()
    mediaSession.release()
    mediaProgressSyncer.reset()
    metadataScope.cancel()

    super.onDestroy()
  }

  // removing service when user swipe out our app
  override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    Log.d(tag, "onTaskRemoved")

    stopSelf()
  }

  override fun onCreate() {
    Log.d(tag, "onCreate")
    super.onCreate()
    ctx = this

    // Initialize Paper
    DbManager.initialize(ctx)

    // Initialize widget
    DeviceManager.initializeWidgetUpdater(ctx)

    // To listen for network change from metered to unmetered
    val networkRequest =
            NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .build()
    val connectivityManager =
            getSystemService(ConnectivityManager::class.java) as ConnectivityManager
    connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

    DbManager.initialize(ctx)
    AbsLogger.info(tag, "onCreate")

    // Initialize API
    apiHandler = ApiHandler(ctx)

    // Initialize sleep timer
    sleepTimerManager = SleepTimerManager(this)

    // Initialize Media Progress Syncer
    mediaProgressSyncer = MediaProgressSyncer(this, apiHandler)

    // Initialize shake sensor
    Log.d(tag, "onCreate Register sensor listener ${mAccelerometer?.isWakeUpSensor}")
    initSensor()

    // Initialize media manager
    mediaManager = MediaManager(apiHandler, ctx)

    channelId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              createNotificationChannel(channelId, channelName)
            } else ""

    val sessionActivityPendingIntent =
            packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
              PendingIntent.getActivity(this, 0, sessionIntent, PendingIntent.FLAG_IMMUTABLE)
            }

    mediaSession =
            MediaSessionCompat(this, tag).apply {
              setSessionActivity(sessionActivityPendingIntent)
              isActive = true
            }

    val mediaController = MediaControllerCompat(ctx, mediaSession.sessionToken)

    // This is for Media Browser
    sessionToken = mediaSession.sessionToken

    val builder = PlayerNotificationManager.Builder(ctx, notificationId, channelId)

    builder.setMediaDescriptionAdapter(AbMediaDescriptionAdapter(mediaController, this))
    builder.setNotificationListener(PlayerNotificationListener(this))

    playerNotificationManager = builder.build()
    playerNotificationManager.setMediaSessionToken(mediaSession.sessionToken)
    playerNotificationManager.setUsePlayPauseActions(true)
    playerNotificationManager.setUseNextAction(false)
    playerNotificationManager.setUsePreviousAction(false)
    playerNotificationManager.setUseChronometer(false)
    playerNotificationManager.setUseStopAction(false)
    playerNotificationManager.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    playerNotificationManager.setPriority(NotificationCompat.PRIORITY_MAX)
    playerNotificationManager.setUseFastForwardActionInCompactView(true)
    playerNotificationManager.setUseRewindActionInCompactView(true)
    playerNotificationManager.setSmallIcon(R.drawable.icon_monochrome)

    // Unknown action
    playerNotificationManager.setBadgeIconType(NotificationCompat.BADGE_ICON_LARGE)

    transportControls = mediaController.transportControls

    mediaSessionConnector = MediaSessionConnector(mediaSession)
    // Without this, the connector's default metadata provider rebuilds metadata from the
    // player's own state on media item transitions/timeline changes, dropping the cover art
    // bitmap that PlaybackSession.resolveCoverBitmapAsync resolves separately.
    mediaSessionConnector.setMediaMetadataProvider { _ ->
      currentPlaybackSession?.getMediaMetadataCompat(ctx) ?: MediaMetadataCompat.Builder().build()
    }
    val queueNavigator: TimelineQueueNavigator =
            object : TimelineQueueNavigator(mediaSession) {
              override fun getSupportedQueueNavigatorActions(player: Player): Long {
                return PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE
              }

              override fun getMediaDescription(
                      player: Player,
                      windowIndex: Int
              ): MediaDescriptionCompat {
                if (currentPlaybackSession == null) {
                  Log.e(tag, "Playback session is not set - returning blank MediaDescriptionCompat")
                  return MediaDescriptionCompat.Builder().build()
                }

                val coverUri = currentPlaybackSession!!.getCoverUri(ctx)


                var bitmap: Bitmap? = null
                // Local covers get bitmap
                // Note: In Android Auto for local cover images, setting the icon uri to a local path does not work (cover is blank)
                // so we create and set the bitmap here instead of AbMediaDescriptionAdapter
                if (currentPlaybackSession!!.localLibraryItem?.coverContentUrl != null) {
                  bitmap =
                    if (Build.VERSION.SDK_INT < 28) {
                      MediaStore.Images.Media.getBitmap(ctx.contentResolver, coverUri)
                    } else {
                      val source: ImageDecoder.Source =
                        ImageDecoder.createSource(ctx.contentResolver, coverUri)
                      ImageDecoder.decodeBitmap(source)
                    }
                }

                // Fix for local images crashing on Android 11 for specific devices
                // https://stackoverflow.com/questions/64186578/android-11-mediastyle-notification-crash/64232958#64232958
                try {
                  ctx.grantUriPermission(
                          "com.android.systemui",
                          coverUri,
                          Intent.FLAG_GRANT_READ_URI_PERMISSION
                  )
                } catch (error: Exception) {
                  Log.e(tag, "Grant uri permission error $error")
                }

                val extra = Bundle()
                extra.putString(
                        MediaMetadataCompat.METADATA_KEY_ARTIST,
                        currentPlaybackSession!!.displayAuthor
                )

                val mediaDescriptionBuilder =
                        MediaDescriptionCompat.Builder()
                                .setExtras(extra)
                                .setTitle(currentPlaybackSession!!.displayTitle)

                bitmap?.let { mediaDescriptionBuilder.setIconBitmap(it) }
                  ?: mediaDescriptionBuilder.setIconUri(coverUri)

                return mediaDescriptionBuilder.build()
              }
            }

    setMediaSessionConnectorPlaybackActions()
    mediaSessionConnector.setQueueNavigator(queueNavigator)
    mediaSessionConnector.setPlaybackPreparer(MediaSessionPlaybackPreparer(this))

    mediaSession.setCallback(MediaSessionCallback(this))

    initializeMPlayer()
    currentPlayer = mPlayer
  }

  private fun initializeMPlayer() {
    streamingLoadControl = StreamingLoadControl()
    val mediaSourceFactory =
            DefaultMediaSourceFactory(this).setLoadErrorHandlingPolicy(streamingLoadErrorPolicy)

    mPlayer =
            ExoPlayer.Builder(this)
                    .setLoadControl(streamingLoadControl)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .setSeekBackIncrementMs(deviceSettings.jumpBackwardsTimeMs)
                    .setSeekForwardIncrementMs(deviceSettings.jumpForwardTimeMs)
                    .build()
    mPlayer.setHandleAudioBecomingNoisy(true)
    mPlayer.addListener(PlayerListener(this))
    val audioAttributes: AudioAttributes =
            AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build()
    mPlayer.setAudioAttributes(audioAttributes, true)

    // attach player to playerNotificationManager
    playerNotificationManager.setPlayer(mPlayer)

    mediaSessionConnector.setPlayer(mPlayer)
  }

  /*
    User callable methods
  */
  fun preparePlayer(
          playbackSession: PlaybackSession,
          playWhenReady: Boolean,
          playbackRate: Float?
  ) {
    suppressResumeBookmark = false
    holdingEndedSession = false
    if (!streamRecoveryInFlight) streamReopenAttempted = false
    if (!isStarted) {
      Log.i(tag, "preparePlayer: foreground service not started - Starting service --")
      Intent(ctx, PlayerNotificationService::class.java).also { intent ->
        ContextCompat.startForegroundService(ctx, intent)
      }
      isStarted = true
    }

    // TODO: When an item isFinished the currentTime should be reset to 0
    //        will reset the time if currentTime is within 5s of duration (for android auto)
    Log.d(
            tag,
            "Prepare Player Session Current Time=${playbackSession.currentTime}, Duration=${playbackSession.duration}"
    )
    if (playbackSession.duration - playbackSession.currentTime < 5) {
      Log.d(tag, "Prepare Player Session is finished, so restart it")
      playbackSession.currentTime = 0.0
    }

    isClosed = false

    val mediaItems = playbackSession.getMediaItems(ctx)
    val playbackRateToUse = playbackRate ?: initialPlaybackRate ?: 1f
    initialPlaybackRate = playbackRate

    // Set actions on Android Auto like jump forward/backward
    setMediaSessionConnectorCustomActions(playbackSession)

    playbackSession.mediaPlayer = getMediaPlayer()

    if (playbackSession.mediaPlayer == PLAYER_CAST && playbackSession.isLocal) {
      Log.w(tag, "Cannot cast local media item - switching player")
      currentPlaybackSession = null
      switchToPlayer(false)
      playbackSession.mediaPlayer = getMediaPlayer()
    }

    if (playbackSession.mediaPlayer == PLAYER_CAST) {
      // If cast-player is the first player to be used
      mediaSessionConnector.setPlayer(castPlayer)
      playerNotificationManager.setPlayer(castPlayer)
    }

    currentPlaybackSession = playbackSession
    DeviceManager.setLastPlaybackSession(
            playbackSession
    ) // Save playback session to use when app is closed
    if (!PlayerNotificationListener.isForegroundService) {
      startForegroundForResume()
    }

    metadataArtJob?.cancel()
    metadataArtJob =
            playbackSession.resolveCoverBitmapAsync(ctx, metadataScope) {
              mediaSessionConnector.invalidateMediaSessionMetadata()
            }
    mediaSessionConnector.invalidateMediaSessionMetadata()

    AbsLogger.info("PlayerNotificationService", "preparePlayer: Opened session ${currentPlaybackSession?.id} for item ${currentPlaybackSession?.mediaItemId} playMethod=${currentPlaybackSession?.playMethod}. MediaPlayer ${currentPlaybackSession?.mediaPlayer}")
    // Notify client
    clientEventEmitter?.onPlaybackSession(playbackSession)

    // Update widget
    DeviceManager.widgetUpdater?.onPlayerChanged(this)

    if (mediaItems.isEmpty()) {
      Log.e(tag, "Invalid playback session no media items to play; leaving the media notification up")
      currentPlaybackSession = null
      return
    }

    if (mPlayer == currentPlayer) {
      val mediaSource: MediaSource
      streamingLoadControl.profile =
              if (playbackSession.isHLS) BufferProfile.Hls else BufferProfile.Direct
      streamingLoadControl.mediaDurationMs =
              if (playbackSession.isHLS || playbackSession.duration <= 0) 0L
              else (playbackSession.duration * 1000).toLong()
      Log.i(
              STREAMING_BUFFER_LOG_TAG,
              "profile=${streamingLoadControl.profile} mediaDurationMs=${streamingLoadControl.mediaDurationMs}"
      )

      if (playbackSession.isLocal) {
        AbsLogger.info("PlayerNotificationService", "preparePlayer: Playing local item ${currentPlaybackSession?.mediaItemId}.")
        val dataSourceFactory = DefaultDataSource.Factory(ctx)

        val extractorsFactory = DefaultExtractorsFactory()
        extractorsFactory.setConstantBitrateSeekingEnabled(true)

        if (DeviceManager.deviceData.deviceSettings?.enableMp3IndexSeeking == true) {
          // @see
          // https://exoplayer.dev/troubleshooting.html#why-is-seeking-inaccurate-in-some-mp3-files
          extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        }

        mediaSource =
                ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                        .setLoadErrorHandlingPolicy(streamingLoadErrorPolicy)
                        .createMediaSource(mediaItems[0])
      } else if (!playbackSession.isHLS) {
        AbsLogger.info("PlayerNotificationService", "preparePlayer: Direct playing item ${currentPlaybackSession?.mediaItemId}.")
        val dataSourceFactory = refreshingHttpFactory()

        val extractorsFactory = DefaultExtractorsFactory()
        extractorsFactory.setConstantBitrateSeekingEnabled(true)

        if (DeviceManager.deviceData.deviceSettings?.enableMp3IndexSeeking == true) {
          // @see
          // https://exoplayer.dev/troubleshooting.html#why-is-seeking-inaccurate-in-some-mp3-files
          extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        }

        mediaSource =
                ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                        .setLoadErrorHandlingPolicy(streamingLoadErrorPolicy)
                        .createMediaSource(mediaItems[0])
      } else {
        AbsLogger.info("PlayerNotificationService", "preparePlayer: Playing HLS stream of item ${currentPlaybackSession?.mediaItemId}.")
        val dataSourceFactory = refreshingHttpFactory("Bearer ${DeviceManager.token}")
        mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItems[0])
      }
      mPlayer.setMediaSource(mediaSource)

      // Add remaining media items if multi-track
      if (mediaItems.size > 1) {
        currentPlayer.addMediaItems(mediaItems.subList(1, mediaItems.size))
        Log.d(tag, "currentPlayer total media items ${currentPlayer.mediaItemCount}")

        val currentTrackIndex = playbackSession.getCurrentTrackIndex()
        val currentTrackTime = playbackSession.getCurrentTrackTimeMs()
        Log.d(
                tag,
                "currentPlayer current track index $currentTrackIndex & current track time $currentTrackTime"
        )
        currentPlayer.seekTo(currentTrackIndex, currentTrackTime)
      } else {
        currentPlayer.seekTo(playbackSession.currentTimeMs)
      }

      Log.d(
              tag,
              "Prepare complete for session ${currentPlaybackSession?.displayTitle} | ${currentPlayer.mediaItemCount}"
      )
      currentPlayer.playWhenReady = playWhenReady
      currentPlayer.setPlaybackSpeed(playbackRateToUse)

      currentPlayer.prepare()
    } else if (castPlayer != null) {
      val currentTrackIndex = playbackSession.getCurrentTrackIndex()
      val currentTrackTime = playbackSession.getCurrentTrackTimeMs()
      val mediaType = playbackSession.mediaType
      Log.d(tag, "Loading cast player $currentTrackIndex $currentTrackTime $mediaType")

      castPlayer?.load(
              mediaItems,
              currentTrackIndex,
              currentTrackTime,
              playWhenReady,
              playbackRateToUse,
              mediaType
      )
    }
  }

  private fun setMediaSessionConnectorCustomActions(playbackSession: PlaybackSession) {
    val mediaItems = playbackSession.getMediaItems(ctx)
    val customActionProviders =
            mutableListOf(
                    JumpBackwardCustomActionProvider(),
                    JumpForwardCustomActionProvider(),
                    ChangePlaybackSpeedCustomActionProvider() // Will be pushed to far left
            )
    if (playbackSession.mediaPlayer != PLAYER_CAST && mediaItems.size > 1) {
      customActionProviders.addAll(
              listOf(
                      SkipBackwardCustomActionProvider(),
                      SkipForwardCustomActionProvider(),
              )
      )
    }
    mediaSessionConnector.setCustomActionProviders(*customActionProviders.toTypedArray())
  }

  fun setMediaSessionConnectorPlaybackActions() {
    var playbackActions =
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_STOP

    if (deviceSettings.allowSeekingOnMediaControls) {
      playbackActions = playbackActions or PlaybackStateCompat.ACTION_SEEK_TO
    }
    mediaSessionConnector.setEnabledPlaybackActions(playbackActions)
  }

  fun handlePlayerPlaybackError(errorMessage: String, errorCode: Int) {
    // A direct-play container the extractor cannot read falls back to transcode.
    // onPlayerError means the retry policy already gave up, or the status was not retryable.
    currentPlaybackSession?.let { playbackSession ->
      if (playbackSession.isDirectPlay && shouldFallbackToTranscode(errorCode)) {
        val playItemRequestPayload = getPlayItemRequestPayload(true)
        Log.d(tag, "Fallback to transcode $playItemRequestPayload.mediaPlayer")
        AbsLogger.error(tag, "Direct play container failed code=$errorCode; opening a transcode session")

        val libraryItemId = playbackSession.libraryItemId ?: "" // Must be true since direct play
        val episodeId = playbackSession.episodeId
        mediaProgressSyncer.stop(false) {
          apiHandler.playLibraryItem(libraryItemId, episodeId, playItemRequestPayload) {
            if (it == null) { // Play request failed
              clientEventEmitter?.onPlaybackFailed(errorMessage)
              Log.i(tag, "Closing playback after transcode fallback failed code=$errorCode")
              AbsLogger.error(tag, "Closing playback after transcode fallback failed code=$errorCode")
              closePlayback(true)
            } else {
              Handler(Looper.getMainLooper()).post { preparePlayer(it, true, null) }
            }
          }
        }
      } else {
        if (playbackSession.isDirectPlay) {
          Log.i(
                  STREAMING_BUFFER_LOG_TAG,
                  "Playback error code=$errorCode; not falling back to transcode"
          )
        }
        applyStreamFailure(
                httpStatus = httpStatusFromPlayerError(errorMessage),
                retriesExhausted = true,
                reason = "player error code=$errorCode $errorMessage"
        )
      }
    }
  }

  /** A progress sync 404 means the direct-play URL for this session will fail once the buffer drains. */
  fun recoverLostStreamSession(reason: String) {
    applyStreamFailure(httpStatus = 404, retriesExhausted = false, reason = reason)
  }

  fun markStreamSessionHealthy() {
    streamReopenAttempted = false
  }

  private fun applyStreamFailure(httpStatus: Int?, retriesExhausted: Boolean, reason: String) {
    val session = currentPlaybackSession
    if (session == null || session.isLocal) {
      AbsLogger.error(tag, "Stream failure with no server session to reopen ($reason)")
      if (retriesExhausted) {
        clientEventEmitter?.onPlaybackFailed(reason)
        closePlayback(true)
      }
      return
    }
    val position = playbackPositionSeconds(session)
    val downloaded = downloadedSession(session, position) != null
    val action =
            actionAfterStreamFailure(
                    httpStatus = httpStatus,
                    retriesExhausted = retriesExhausted,
                    downloadedCopyAvailable = downloaded,
                    reopenAlreadyAttempted = streamReopenAttempted,
                    recoveryInFlight = streamRecoveryInFlight
            )
    AbsLogger.error(
            tag,
            "Stream failure action=$action http=${httpStatus ?: "-"} bufferedHideMs=${bufferedPlaybackRemainingMs(getBufferedAheadMs(), currentPlaybackSpeed())} reason=$reason"
    )
    when (action) {
      StreamFailureAction.KeepPlaying -> return
      StreamFailureAction.ReopenSession -> reopenPlayback(session, position, reason)
      StreamFailureAction.PlayDownloadedCopy -> playDownloadedCopy(session, position, reason)
      StreamFailureAction.Close -> {
        clientEventEmitter?.onPlaybackFailed(reason)
        Log.i(tag, "Closing playback after stream failure $reason")
        closePlayback(true)
      }
    }
  }

  private fun reopenPlayback(session: PlaybackSession, position: Double, reason: String) {
    val libraryItemId = session.libraryItemId
    if (libraryItemId.isNullOrEmpty()) {
      playDownloadedCopy(session, position, reason)
      return
    }
    val speed = currentPlaybackSpeed()
    val episodeId = session.episodeId
    val previousSessionId = session.id
    streamReopenAttempted = true
    streamRecoveryInFlight = true
    AbsLogger.error(tag, "Reopening server session at $position after $reason (session $previousSessionId)")
    mediaProgressSyncer.stop(false) {
      apiHandler.playLibraryItem(libraryItemId, episodeId, getPlayItemRequestPayload(session.isHLS)) { opened ->
        Handler(Looper.getMainLooper()).post {
          if (!streamRecoveryInFlight) return@post
          if (opened == null) {
            AbsLogger.error(tag, "Server did not open a new session at $position")
            streamRecoveryInFlight = false
            playDownloadedCopy(session, position, reason)
            return@post
          }
          opened.currentTime = position
          AbsLogger.info(tag, "Opened session ${opened.id} at $position (replaced $previousSessionId)")
          preparePlayer(opened, true, speed)
          streamRecoveryInFlight = false
        }
      }
    }
  }

  private fun playDownloadedCopy(session: PlaybackSession, position: Double, reason: String) {
    val local = downloadedSession(session, position)
    if (local == null) {
      AbsLogger.error(tag, "No downloaded copy for ${session.libraryItemId}; closing playback ($reason)")
      clientEventEmitter?.onPlaybackFailed(reason)
      closePlayback(true)
      return
    }
    streamReopenAttempted = true
    AbsLogger.info(tag, "Playing the downloaded copy of ${session.libraryItemId} at $position")
    Handler(Looper.getMainLooper()).post { preparePlayer(local, true, currentPlaybackSpeed()) }
  }

  private fun downloadedSession(session: PlaybackSession, position: Double): PlaybackSession? {
    val libraryItemId = session.libraryItemId ?: return null
    val local = DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItemId) ?: return null
    val episode =
            session.episodeId?.let { episodeId ->
              (local.media as? Podcast)?.episodes?.find { it.serverEpisodeId == episodeId || it.id == episodeId }
            }
    if (!session.episodeId.isNullOrEmpty() && episode == null) return null
    if (!local.hasTracks(this, episode)) return null
    return local.getPlaybackSession(episode, getDeviceInfo()).also { it.currentTime = position }
  }

  private fun playbackPositionSeconds(session: PlaybackSession): Double {
    return try {
      val seconds = getCurrentTimeSeconds()
      if (seconds > 0.0) seconds else session.currentTime
    } catch (e: Exception) {
      session.currentTime
    }
  }

  private fun currentPlaybackSpeed(): Float {
    return try {
      currentPlayer.playbackParameters.speed
    } catch (e: Exception) {
      1f
    }
  }

  private fun refreshingHttpFactory(authorization: String? = null): com.google.android.exoplayer2.upstream.DataSource.Factory {
    val base = DefaultHttpDataSource.Factory().setUserAgent(channelId)
    if (authorization != null) {
      base.setDefaultRequestProperties(hashMapOf("Authorization" to authorization))
    }
    return com.google.android.exoplayer2.upstream.DataSource.Factory {
      RefreshingHttpDataSource(base.createDataSource()) { refreshAccessTokenBlocking() }
    }
  }

  private fun refreshAccessTokenBlocking(): String? {
    val thread = Thread.currentThread()
    if (!mayWaitForRefresh(thread.name, Looper.myLooper() == Looper.getMainLooper())) {
      AbsLogger.error(tag, "Token refresh was not awaited on ${thread.name}; playback continues")
      apiHandler.refreshAuthTokens(DeviceManager.serverConnectionConfigId) {}
      return null
    }
    val latch = java.util.concurrent.CountDownLatch(1)
    var token: String? = null
    apiHandler.refreshAuthTokens(DeviceManager.serverConnectionConfigId) { result ->
      if (result is com.audiobookshelf.app.server.ApiHandler.RefreshResult.Success) token = result.accessToken
      latch.countDown()
    }
    latch.await(com.audiobookshelf.app.server.REFRESH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    return token
  }

  private fun getBufferedAheadMs(): Long {
    return try {
      (getBufferedTime() - getCurrentTime()).coerceAtLeast(0L)
    } catch (e: Exception) {
      0L
    }
  }

  fun handlePlaybackEnded() {
    val action =
            playbackEndAction(
                    hasQueueItem = playbackQueue.peek() != null,
                    isPodcastEpisode = currentPlaybackSession?.isPodcastEpisode == true,
                    isAndroidAuto = isAndroidAuto
            )
    Log.i("AbsAutoAdvance", "ended action=$action queueSize=${playbackQueue.size}")
    when (action) {
      PlaybackEndAction.Stop -> {
        if (keepTransportControlsAfterEnd(action)) holdPausedNearEnd()
      }
      PlaybackEndAction.PodcastAutoAdvance -> playNextAndroidAutoPodcastEpisode()
      PlaybackEndAction.StartNext -> playNextQueuedItem()
    }
  }

  private fun holdPausedNearEnd() {
    if (holdingEndedSession) return
    holdingEndedSession = true
    val durationMs = currentItemDurationMs()
    val hold = pausedHoldPositionMs(durationMs)
    Log.i(tag, "Playback ended; holding paused media session at $hold duration=$durationMs")
    notePauseOrigin(PAUSE_PLAYBACK_ENDED)
    currentPlayer.pause()
    if (hold != null) currentPlayer.seekTo(hold)
    rememberResumeBookmark()
    mediaSession.isActive = true
  }

  private fun playNextAndroidAutoPodcastEpisode() {
    Log.d(tag, "Podcast playback ended on android auto")
    val libraryItem = currentPlaybackSession?.libraryItem ?: return

    // Need to sync with server to set as finished
    mediaProgressSyncer.finished {
      // Need to reload media progress
      mediaManager.loadServerUserMediaProgress {
        val podcast = libraryItem.media as Podcast
        val nextEpisode = podcast.getNextUnfinishedEpisode(libraryItem.id, mediaManager)
        Log.d(tag, "handlePlaybackEnded nextEpisode=$nextEpisode")
        nextEpisode?.let { podcastEpisode ->
          mediaManager.play(libraryItem, podcastEpisode, getPlayItemRequestPayload(false)) {
            if (it == null) {
              Log.e(tag, "Failed to play library item")
            } else {
              val playbackRate = mediaManager.getSavedPlaybackRate()
              Handler(Looper.getMainLooper()).post { preparePlayer(it, true, playbackRate) }
            }
          }
        }
      }
    }
  }

  private fun playNextQueuedItem() {
    val next = playbackQueue.peek() ?: return
    val blockMeteredRemote =
            !isUnmeteredNetwork &&
                    deviceSettings.streamingUsingCellular == StreamingUsingCellularSetting.NEVER
    if (!shouldStartQueuedItem(next.libraryItemId, blockMeteredRemote)) {
      Log.i("AbsAutoAdvance", "not advancing metered remote ${next.libraryItemId}")
      playbackQueue.clear()
      return
    }
    Log.i("AbsAutoAdvance", "advancing to ${next.libraryItemId} episode=${next.episodeId ?: "-"}")

    playbackQueue.poll()
    val generation = autoAdvanceGeneration
    val startNext = {
      if (generation != autoAdvanceGeneration) {
        Log.i("AbsAutoAdvance", "dropped advance because a newer play replaced it")
      } else {
        Handler(Looper.getMainLooper()).post {
          PlayerListener.lazyIsPlaying = false
          startQueuedPlayback(next, generation)
        }
      }
    }
    // finished() returns without its callback when the listening timer is not running.
    if (mediaProgressSyncer.listeningTimerRunning) {
      mediaProgressSyncer.finished { startNext() }
    } else {
      startNext()
    }
  }

  private fun startQueuedPlayback(entry: PlaybackQueueEntry, generation: Int) {
    if (generation != autoAdvanceGeneration) return

    val playbackRate = mediaManager.getSavedPlaybackRate()
    if (entry.libraryItemId.startsWith("local")) {
      val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(entry.libraryItemId)
      if (localLibraryItem == null) {
        Log.i("AbsAutoAdvance", "advance failed for ${entry.libraryItemId} episode=${entry.episodeId ?: "-"} local item missing")
        playbackQueue.clear()
        return
      }

      var episode: PodcastEpisode? = null
      val episodeId = entry.episodeId
      if (!episodeId.isNullOrEmpty()) {
        val podcast = localLibraryItem.media as? Podcast
        episode = podcast?.episodes?.find { it.id == episodeId }
        if (episode == null) {
          Log.i("AbsAutoAdvance", "advance failed for ${entry.libraryItemId} episode=${entry.episodeId ?: "-"} episode missing")
          playbackQueue.clear()
          return
        }
      }

      val playbackSession = localLibraryItem.getPlaybackSession(episode, getDeviceInfo())
      preparePlayer(playbackSession, true, playbackRate)
      if (generation == autoAdvanceGeneration && currentPlaybackSession == null) {
        Log.i("AbsAutoAdvance", "advance failed for ${entry.libraryItemId} episode=${entry.episodeId ?: "-"} no media items")
        playbackQueue.clear()
      }
      return
    }

    apiHandler.playLibraryItem(entry.libraryItemId, entry.episodeId, getPlayItemRequestPayload(false)) {
      if (generation != autoAdvanceGeneration) return@playLibraryItem
      if (it == null) {
        Log.i("AbsAutoAdvance", "advance failed for ${entry.libraryItemId} episode=${entry.episodeId ?: "-"} server play failed")
        playbackQueue.clear()
      } else {
        Handler(Looper.getMainLooper()).post {
          if (generation != autoAdvanceGeneration) return@post
          PlayerListener.lazyIsPlaying = false
          preparePlayer(it, true, playbackRate)
          if (generation == autoAdvanceGeneration && currentPlaybackSession == null) {
            Log.i("AbsAutoAdvance", "advance failed for ${entry.libraryItemId} episode=${entry.episodeId ?: "-"} no media items")
            playbackQueue.clear()
          }
        }
      }
    }
  }

  fun startNewPlaybackSession() {
    currentPlaybackSession?.let { playbackSession ->
      Log.i(tag, "Starting new playback session for ${playbackSession.displayTitle}")

      val forceTranscode = playbackSession.isHLS // If already HLS then force
      val playItemRequestPayload = getPlayItemRequestPayload(forceTranscode)

      val libraryItemId = playbackSession.libraryItemId ?: "" // Must be true since direct play
      val episodeId = playbackSession.episodeId
      mediaProgressSyncer.stop(false) {
        apiHandler.playLibraryItem(libraryItemId, episodeId, playItemRequestPayload) {
          if (it == null) {
            Log.e(tag, "Failed to start new playback session")
          } else {
            Log.d(
                    tag,
                    "New playback session response from server with session id ${it.id} for \"${it.displayTitle}\""
            )
            Handler(Looper.getMainLooper()).post { preparePlayer(it, true, null) }
          }
        }
      }
    }
  }

  fun switchToPlayer(useCastPlayer: Boolean) {
    val wasPlaying = currentPlayer.isPlaying
    if (useCastPlayer) {
      if (currentPlayer == castPlayer) {
        Log.d(tag, "switchToPlayer: Already using Cast Player " + castPlayer?.deviceInfo)
        return
      } else {
        Log.d(tag, "switchToPlayer: Switching to cast player from exo player stop exo player")
        mPlayer.stop()
      }
    } else {
      if (currentPlayer == mPlayer) {
        Log.d(tag, "switchToPlayer: Already using Exo Player " + mPlayer.deviceInfo)
        return
      } else if (castPlayer != null) {
        Log.d(tag, "switchToPlayer: Switching to exo player from cast player stop cast player")
        castPlayer?.stop()
      }
    }

    if (currentPlaybackSession == null) {
      Log.e(tag, "switchToPlayer: No Current playback session")
    } else {
      isSwitchingPlayer = true
    }

    // Playback session in progress syncer is a copy that is up-to-date so replace current here with
    // that
    //  TODO: bad design here implemented to prevent the session in MediaProgressSyncer from
    // changing while syncing
    if (mediaProgressSyncer.currentPlaybackSession != null) {
      currentPlaybackSession = mediaProgressSyncer.currentPlaybackSession?.clone()
    }

    currentPlayer =
            if (useCastPlayer) {
              Log.d(tag, "switchToPlayer: Using Cast Player " + castPlayer?.deviceInfo)
              mediaSessionConnector.setPlayer(castPlayer)
              playerNotificationManager.setPlayer(castPlayer)
              setMediaSessionToCastVolume()
              castPlayer as CastPlayer
            } else {
              Log.d(tag, "switchToPlayer: Using ExoPlayer")
              mediaSessionConnector.setPlayer(mPlayer)
              playerNotificationManager.setPlayer(mPlayer)
              setMediaSessionToLocalVolume()
              mPlayer
            }

    clientEventEmitter?.onMediaPlayerChanged(getMediaPlayer())

    currentPlaybackSession?.let {
      Log.d(tag, "switchToPlayer: Starting new playback session ${it.displayTitle}")
      if (wasPlaying) { // media is paused when switching players
        clientEventEmitter?.onPlayingUpdate(false)
      }

      // TODO: Start a new playback session here instead of using the existing
      preparePlayer(it, false, null)
    }
  }

  private fun setMediaSessionToCastVolume() {
    val currentVol = try { castPlayer?.getDeviceVolume() ?: 0 } catch (_: Exception) { 0 }
    val provider = object : VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE, 100, currentVol) {
      override fun onSetVolumeTo(volume: Int) {
        // Clamp, update UI immediately, then send to device
        val clamped = volume.coerceIn(0, 100)
        setCurrentVolume(clamped)
        try { castPlayer?.setDeviceVolume(clamped) } catch (_: Exception) {}
      }

      override fun onAdjustVolume(direction: Int) {
        // Use Android-provided step (−1, 0, +1). Clamp, update UI immediately, then send.
        val current = currentVolume
        val target = (current + direction).coerceIn(0, 100)
        setCurrentVolume(target)
        try { castPlayer?.setDeviceVolume(target) } catch (_: Exception) {}
      }
    }
    remoteVolumeProvider = provider
    mediaSession.setPlaybackToRemote(provider)
  }

  private fun setMediaSessionToLocalVolume() {
    mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
    remoteVolumeProvider = null
  }

  fun getCurrentTrackStartOffsetMs(): Long {
    return if (currentPlayer.mediaItemCount > 1) {
      val windowIndex = currentPlayer.currentMediaItemIndex
      val currentTrackStartOffset = currentPlaybackSession?.getTrackStartOffsetMs(windowIndex) ?: 0L
      currentTrackStartOffset
    } else {
      0
    }
  }

  fun getCurrentTime(): Long {
    return currentPlayer.currentPosition + getCurrentTrackStartOffsetMs()
  }

  fun getCurrentTimeSeconds(): Double {
    return getCurrentTime() / 1000.0
  }

  private fun getBufferedTime(): Long {
    return if (currentPlayer.mediaItemCount > 1) {
      val windowIndex = currentPlayer.currentMediaItemIndex
      val currentTrackStartOffset = currentPlaybackSession?.getTrackStartOffsetMs(windowIndex) ?: 0L
      currentPlayer.bufferedPosition + currentTrackStartOffset
    } else {
      currentPlayer.bufferedPosition
    }
  }

  fun getBufferedTimeSeconds(): Double {
    return getBufferedTime() / 1000.0
  }

  fun getDuration(): Long {
    return currentPlaybackSession?.totalDurationMs ?: 0L
  }

  private fun currentItemDurationMs(): Long {
    if (::currentPlayer.isInitialized) {
      val duration = currentPlayer.duration
      if (duration > 0L) return duration
    }
    return getDuration()
  }

  fun getCurrentPlaybackSessionCopy(): PlaybackSession? {
    return currentPlaybackSession?.clone()
  }

  fun getCurrentBookChapter(): BookChapter? {
    return currentPlaybackSession?.getChapterForTime(this.getCurrentTime())
  }

  fun getEndTimeOfChapterOrTrack(): Long? {
    return getCurrentBookChapter()?.endMs ?: currentPlaybackSession?.getCurrentTrackEndTime()
  }

  private fun getNextBookChapter(): BookChapter? {
    return currentPlaybackSession?.getNextChapterForTime(this.getCurrentTime())
  }

  fun getEndTimeOfNextChapterOrTrack(): Long? {
    return getNextBookChapter()?.endMs ?: currentPlaybackSession?.getNextTrackEndTime()
  }

  // Called from PlayerListener play event
  // check with server if progress has updated since last play and sync progress update
  fun checkCurrentSessionProgress(seekBackTime: Long): Boolean {
    if (currentPlaybackSession == null) return true

    mediaProgressSyncer.currentPlaybackSession?.let { playbackSession ->
      if (!DeviceManager.checkConnectivity(ctx)) {
        return true // carry on
      }

      if (playbackSession.isLocal) {

        // Make sure this connection config exists
        val serverConnectionConfig =
                DeviceManager.getServerConnectionConfig(playbackSession.serverConnectionConfigId)
        if (serverConnectionConfig == null) {
          Log.d(
                  tag,
                  "checkCurrentSessionProgress: Local library item server connection config is not saved ${playbackSession.serverConnectionConfigId}"
          )
          return true // carry on
        }

        // Local playback session check if server has updated media progress
        Log.d(
                tag,
                "checkCurrentSessionProgress: Checking if local media progress was updated on server"
        )
        apiHandler.getMediaProgress(
                playbackSession.libraryItemId!!,
                playbackSession.episodeId,
                serverConnectionConfig
        ) { mediaProgress ->
          if (mediaProgress != null &&
                          mediaProgress.lastUpdate > playbackSession.updatedAt &&
                          mediaProgress.currentTime != playbackSession.currentTime
          ) {
            Log.d(
                    tag,
                    "checkCurrentSessionProgress: Media progress was updated since last play time updating from ${playbackSession.currentTime} to ${mediaProgress.currentTime}"
            )
            mediaProgressSyncer.syncFromServerProgress(mediaProgress)

            // Update current playback session stored in PNS since MediaProgressSyncer version is a
            // copy
            mediaProgressSyncer.currentPlaybackSession?.let { updatedPlaybackSession ->
              currentPlaybackSession = updatedPlaybackSession
            }

            Handler(Looper.getMainLooper()).post {
              seekPlayer(playbackSession.currentTimeMs)
              // Should already be playing
              currentPlayer.volume = 1F // Volume on sleep timer might have decreased this
              currentPlaybackSession?.let { mediaProgressSyncer.play(it) }
              clientEventEmitter?.onPlayingUpdate(true)
            }
          } else {
            Handler(Looper.getMainLooper()).post {
              if (seekBackTime > 0L) {
                seekBackward(seekBackTime)
              }

              // Should already be playing
              currentPlayer.volume = 1F // Volume on sleep timer might have decreased this
              mediaProgressSyncer.currentPlaybackSession?.let { playbackSession ->
                mediaProgressSyncer.play(playbackSession)
              }
              clientEventEmitter?.onPlayingUpdate(true)
            }
          }
        }
      } else {
        // Streaming from server so check if playback session still exists on server
        Log.d(
                tag,
                "checkCurrentSessionProgress: Checking if playback session ${playbackSession.id} for server stream is still available"
        )
        apiHandler.getPlaybackSession(playbackSession.id) {
          if (it == null) {
            Log.d(
                    tag,
                    "checkCurrentSessionProgress: Playback session does not exist on server - start new playback session"
            )

            Handler(Looper.getMainLooper()).post {
              notePauseOrigin(PAUSE_MEDIA_SESSION)
              currentPlayer.pause()
              startNewPlaybackSession()
            }
          } else {
            Log.d(tag, "checkCurrentSessionProgress: Playback session still available on server")
            Handler(Looper.getMainLooper()).post {
              if (seekBackTime > 0L) {
                seekBackward(seekBackTime)
              }

              currentPlayer.volume = 1F // Volume on sleep timer might have decreased this
              mediaProgressSyncer.currentPlaybackSession?.let { playbackSession ->
                mediaProgressSyncer.play(playbackSession)
              }

              clientEventEmitter?.onPlayingUpdate(true)
            }
          }
        }
      }
    }
    return false
  }

  fun rememberResumeBookmark() {
    if (suppressResumeBookmark) return
    val session = currentPlaybackSession ?: return
    val bookmark =
            resumeBookmarkFrom(
                    isLocal = session.isLocal,
                    sessionLibraryItemId = session.libraryItemId,
                    sessionEpisodeId = session.episodeId,
                    localLibraryItemId = session.localLibraryItem?.id,
                    localEpisodeId = session.localEpisodeId,
                    positionSeconds = getCurrentTimeSeconds(),
                    savedAt = System.currentTimeMillis(),
                    serverConnectionConfigId = session.serverConnectionConfigId,
                    queue = playbackQueue.snapshot()
            )
                    ?: return
    Log.i(
            tag,
            "Saved resume bookmark item=${bookmark.libraryItemId} episode=${bookmark.episodeId} local=${bookmark.local} position=${bookmark.positionSeconds}"
    )
    DeviceManager.dbManager.saveResumeBookmark(bookmark)
  }

  fun demotePausedForeground() {
    Log.w(tag, "demotePausedForeground called; pause keeps the media foreground service")
  }

  private fun deliverMediaButton(button: TransportButton) {
    val player = loadedPlayer()
    val bookmark = DeviceManager.dbManager.getResumeBookmark()
    val action = resumeAction(button, player, bookmark != null)
    val delivery = mediaButtonDelivery(action, PlayerNotificationListener.isForegroundService, player)
    Log.i(tag, "Media button $button action=$action delivery=$delivery player=$player")
    if (mustAcknowledgeMediaButtonStart()) startForegroundForResume()
    when (delivery) {
      MediaButtonDelivery.Dispatch -> handleTransport(button, PAUSE_MEDIA_BUTTON)
      MediaButtonDelivery.KeepPausedNotification -> {
        playerNotificationManager.invalidate()
        Log.i(tag, "Kept the paused media notification in the foreground")
      }
      MediaButtonDelivery.StopEmptyService -> {
        if (releasesForegroundAfter(delivery)) {
          Log.i(tag, "Media button with nothing to play; releasing the foreground service")
          stopForeground(Service.STOP_FOREGROUND_REMOVE)
          PlayerNotificationListener.isForegroundService = false
          stopSelf()
        }
      }
    }
  }

  fun handleTransport(button: TransportButton, pauseOrigin: String = PAUSE_MEDIA_SESSION) {
    val bookmark = DeviceManager.dbManager.getResumeBookmark()
    val action = resumeAction(button, loadedPlayer(), bookmark != null)
    if (action == ResumeAction.ResumeFromBookmark && resumeInFlight) return
    if (mustForegroundImmediately(action) && !PlayerNotificationListener.isForegroundService) {
      startForegroundForResume()
    }
    when (action) {
      ResumeAction.PlayLoaded -> resumeLoaded(bookmark)
      ResumeAction.PauseAndKeep -> pause(pauseOrigin)
      ResumeAction.ResumeFromBookmark -> if (bookmark != null) resumeFromBookmark(bookmark)
      ResumeAction.StopAndClear -> closePlayback()
      ResumeAction.Ignore -> {}
    }
  }

  private fun playbackIsEmpty(): Boolean = loadedPlayer() == LoadedPlayer.Empty

  fun resumeFromRecentItem(mediaId: String?): Boolean {
    val bookmark = try {
      DeviceManager.dbManager.getResumeBookmark()
    } catch (_: Throwable) {
      null
    }
    if (playRequest(mediaId, bookmark != null, playbackIsEmpty()) != PlayRequest.ResumeBookmark || bookmark == null) {
      return false
    }
    if (!PlayerNotificationListener.isForegroundService) startForegroundForResume()
    AbsLogger.info(tag, "resume recent item id=$mediaId")
    resumeFromBookmark(bookmark)
    return true
  }

  private fun loadedPlayer(): LoadedPlayer {
    if (currentPlaybackSession == null || !::currentPlayer.isInitialized) return LoadedPlayer.Empty
    if (currentPlayer.playbackState == Player.STATE_IDLE) return LoadedPlayer.Empty
    if (currentPlayer.playbackState == Player.STATE_ENDED) return LoadedPlayer.Ended
    return if (currentPlayer.isPlaying) LoadedPlayer.Playing else LoadedPlayer.Paused
  }

  private fun loadedIdentity(session: PlaybackSession): LoadedIdentity {
    return LoadedIdentity(
            serverLibraryItemId = session.libraryItemId,
            serverEpisodeId = session.episodeId,
            localLibraryItemId = session.localLibraryItem?.id,
            localEpisodeId = session.localEpisodeId
    )
  }

  private fun startForegroundForResume() {
    val spec = mediaNotificationSpec()
    val notification = PlayerNotificationListener.lastNotification ?: buildMediaForegroundNotification(spec)
    Log.i(
            tag,
            "Posting media foreground notification attachSession=${spec.attachSession} actions=${spec.includeTransportActions} reused=${PlayerNotificationListener.lastNotification != null}"
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    } else {
      startForeground(notificationId, notification)
    }
    PlayerNotificationListener.isForegroundService = true
    mediaSession.isActive = true
  }

  private fun buildMediaForegroundNotification(spec: MediaNotificationSpec): Notification {
    val session = currentPlaybackSession
    val builder =
            NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(R.drawable.icon_monochrome)
                    .setContentTitle(session?.displayTitle ?: "Audiobookshelf")
                    .setContentText(session?.displayAuthor)
                    .setOngoing(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
    if (spec.includeTransportActions) {
      builder.addAction(mediaNotificationAction(android.R.drawable.ic_media_rew, "Rewind", PlaybackStateCompat.ACTION_REWIND))
      builder.addAction(mediaNotificationAction(android.R.drawable.ic_media_play, "Play", PlaybackStateCompat.ACTION_PLAY_PAUSE))
      builder.addAction(mediaNotificationAction(android.R.drawable.ic_media_ff, "Fast forward", PlaybackStateCompat.ACTION_FAST_FORWARD))
    }
    if (spec.attachSession) {
      val style = androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession.sessionToken)
      if (spec.includeTransportActions) style.setShowActionsInCompactView(0, 1, 2)
      builder.setStyle(style)
    }
    return builder.build()
  }

  private fun mediaNotificationAction(icon: Int, title: String, action: Long): NotificationCompat.Action {
    val pending = androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(this, action)
    return NotificationCompat.Action(icon, title, pending)
  }

  private fun downloadedItems(): List<DownloadedItem> {
    return DeviceManager.dbManager.getLocalLibraryItems().map { item ->
      val episodes =
              (item.media as? Podcast)?.episodes?.map { episode ->
                DownloadedEpisode(episode.id, episode.serverEpisodeId)
              }
                      ?: emptyList()
      DownloadedItem(item.id, item.libraryItemId, episodes)
    }
  }

  private fun episodeFrom(local: LocalLibraryItem?, episodeId: String?): PodcastEpisode? {
    if (local == null || episodeId == null) return null
    return (local.media as? Podcast)?.episodes?.find { it.id == episodeId }
  }

  private fun resumeLoaded(bookmark: ResumeBookmark?) {
    val player = loadedPlayer()
    val session = currentPlaybackSession
    val sameItem = bookmark != null && session != null && bookmarkMatchesLoadedItem(bookmark, loadedIdentity(session))
    if (bookmark != null && !shouldPlayLoadedItemInsteadOfBookmark(player, sameItem)) {
      val choice = chooseResume(bookmark, downloadedItems(), player)
      if (choice.source == ResumeSource.LocalFile && !alreadyPlayingLocal(choice)) {
        Log.i(tag, "Switching the loaded item to its local download")
        playLocalChoice(bookmark, choice)
        return
      }
    } else if (player == LoadedPlayer.Ended || (player == LoadedPlayer.Paused && !sameItem)) {
      Log.i(tag, "Play keeps the loaded item player=$player bookmarkMatches=$sameItem")
    }
    val plan = planUnpause(hasLoadedMedia = true, accessTokenExpired = true)
    if (!plan.blocksCaller && plan.resumeImmediately) play()
    if (session == null || session.isLocal || !DeviceManager.checkConnectivity(ctx)) return
    if (warmCheckInFlight) return
    warmCheckInFlight = true
    apiHandler.getPlaybackSession(session.id) { found ->
      Handler(Looper.getMainLooper()).post {
        warmCheckInFlight = false
        if (!warmResumeNeedsNewServerSession(session.isLocal, found != null)) return@post
        if (bookmark != null) resumeFromBookmark(bookmark) else startNewPlaybackSession()
      }
    }
  }

  private fun alreadyPlayingLocal(choice: ResumeChoice): Boolean {
    val session = currentPlaybackSession ?: return false
    return session.isLocal &&
            session.localLibraryItem?.id == choice.localLibraryItemId &&
            session.localEpisodeId == choice.localEpisodeId
  }

  private fun playLocalChoice(bookmark: ResumeBookmark, choice: ResumeChoice) {
    if (resumeInFlight) return
    resumeInFlight = true
    val localId = choice.localLibraryItemId
    val local = if (localId == null) null else DeviceManager.dbManager.getLocalLibraryItem(localId)
    val episode = episodeFrom(local, choice.localEpisodeId)
    if (local == null || (choice.localEpisodeId != null && episode == null)) {
      Log.i(tag, "resume bookmark missing local item $localId")
      abandonResumeNotification()
      resumeInFlight = false
      return
    }
    val session = local.getPlaybackSession(episode, getDeviceInfo())
    session.currentTime = positionToResume(bookmark, session.currentTime, session.updatedAt)
    playbackQueue.replace(choice.queue)
    PlayerListener.lazyIsPlaying = false
    preparePlayer(session, true, mediaManager.getSavedPlaybackRate())
    resumeInFlight = false
  }

  fun resumeFromBookmark(bookmark: ResumeBookmark) {
    if (resumeInFlight) return
    val choice = chooseResume(bookmark, downloadedItems(), LoadedPlayer.Empty)
    when (choice.source) {
      ResumeSource.AlreadyLoaded -> {}
      ResumeSource.Unavailable -> {
        Log.i(tag, "resume bookmark has no file and no server item")
        abandonResumeNotification()
      }
      ResumeSource.LocalFile -> playLocalChoice(bookmark, choice)
      ResumeSource.NewServerSession -> openServerSession(bookmark, choice)
    }
  }

  private fun openServerSession(bookmark: ResumeBookmark, choice: ResumeChoice) {
    if (resumeInFlight) return
    resumeInFlight = true
    val serverId = choice.serverLibraryItemId
    val configId =
            connectionConfigIdForResume(
                    bookmark.serverConnectionConfigId,
                    DeviceManager.deviceData.serverConnectionConfigs.map { it.id },
                    DeviceManager.serverConnectionConfigId.takeIf { it.isNotEmpty() }
            )
    val config = DeviceManager.getServerConnectionConfig(configId)
    if (config == null || serverId == null || !DeviceManager.checkConnectivity(ctx)) {
      Log.i(tag, "resume bookmark offline for $serverId config=$configId")
      abandonResumeNotification()
      resumeInFlight = false
      return
    }
    DeviceManager.serverConnectionConfig = config
    AbsLogger.info(tag, "resume bookmark server=${config.id}")
    val rate = mediaManager.getSavedPlaybackRate()
    apiHandler.playLibraryItem(serverId, choice.serverEpisodeId, getPlayItemRequestPayload(false)) { session ->
      if (session == null) {
        Handler(Looper.getMainLooper()).post {
          Log.i(tag, "resume bookmark server play failed $serverId")
          abandonResumeNotification()
          resumeInFlight = false
        }
        return@playLibraryItem
      }
      apiHandler.getMediaProgress(serverId, choice.serverEpisodeId, DeviceManager.serverConnectionConfig) { progress ->
        Handler(Looper.getMainLooper()).post {
          session.currentTime = positionToResume(bookmark, progress?.currentTime, progress?.lastUpdate)
          playbackQueue.replace(choice.queue)
          PlayerListener.lazyIsPlaying = false
          preparePlayer(session, true, rate)
          resumeInFlight = false
        }
      }
    }
  }

  private fun abandonResumeNotification() {
    stopForeground(Service.STOP_FOREGROUND_REMOVE)
    PlayerNotificationListener.isForegroundService = false
  }

  fun play() {
    if (currentPlayer.isPlaying) {
      Log.d(tag, "Already playing")
      return
    }
    val durationMs = currentItemDurationMs()
    val positionMs = currentPlayer.currentPosition
    if (shouldReplayFromStart(loadedPlayer(), positionMs, durationMs)) {
      Log.i(tag, "Replaying from the start position=$positionMs duration=$durationMs")
      currentPlayer.seekTo(0)
    }
    currentPlayer.volume = 1F
    currentPlayer.play()
  }

  fun pause(origin: String) {
    notePauseOrigin(origin)
    currentPlayer.pause()
  }

  fun playPause(origin: String): Boolean {
    return if (currentPlayer.isPlaying) {
      pause(origin)
      false
    } else {
      play()
      true
    }
  }

  fun seekPlayer(time: Long) {
    var timeToSeek = time
    Log.d(tag, "seekPlayer mediaCount = ${currentPlayer.mediaItemCount} | $timeToSeek")
    if (timeToSeek < 0) {
      Log.w(tag, "seekPlayer invalid time $timeToSeek - setting to 0")
      timeToSeek = 0L
    } else if (timeToSeek > getDuration()) {
      Log.w(tag, "seekPlayer invalid time $timeToSeek - setting to MAX - 2000")
      timeToSeek = getDuration() - 2000L
    }

    if (currentPlayer.mediaItemCount > 1) {
      currentPlaybackSession?.currentTime = timeToSeek / 1000.0
      val newWindowIndex = currentPlaybackSession?.getCurrentTrackIndex() ?: 0
      val newTimeOffset = currentPlaybackSession?.getCurrentTrackTimeMs() ?: 0
      Log.d(tag, "seekPlayer seekTo $newWindowIndex | $newTimeOffset")
      currentPlayer.seekTo(newWindowIndex, newTimeOffset)
    } else {
      currentPlayer.seekTo(timeToSeek)
    }
  }

  fun skipToPrevious() {
    currentPlayer.seekToPrevious()
  }

  fun skipToNext() {
    currentPlayer.seekToNext()
  }

  fun jumpForward() {
    seekForward(deviceSettings.jumpForwardTimeMs)
  }

  fun jumpBackward() {
    seekBackward(deviceSettings.jumpBackwardsTimeMs)
  }

  fun seekForward(amount: Long) {
    seekPlayer(getCurrentTime() + amount)
  }

  fun seekBackward(amount: Long) {
    seekPlayer(getCurrentTime() - amount)
  }

  fun setPlaybackSpeed(speed: Float) {
    mediaManager.userSettingsPlaybackRate = speed
    currentPlayer.setPlaybackSpeed(speed)

    // Refresh Android Auto actions
    mediaProgressSyncer.currentPlaybackSession?.let { setMediaSessionConnectorCustomActions(it) }
  }

  fun closePlayback(calledOnError: Boolean? = false) {
    if (closingPlayback) return
    closingPlayback = true
    holdingEndedSession = false
    streamRecoveryInFlight = false
    streamReopenAttempted = false
    AbsLogger.info(tag, "closePlayback calledOnError=$calledOnError session=${mediaProgressSyncer.currentSessionId}")
    Log.i(tag, "closePlayback calledOnError=$calledOnError; removing the media notification")
    suppressResumeBookmark = true
    autoAdvanceGeneration += 1
    playbackQueue.clear()
    DeviceManager.dbManager.clearResumeBookmark()
    val config = DeviceManager.serverConnectionConfig

    val isLocal = mediaProgressSyncer.currentIsLocal
    val currentSessionId = mediaProgressSyncer.currentSessionId
    if (mediaProgressSyncer.listeningTimerRunning) {
      Log.i(tag, "About to close playback so stopping media progress syncer first")

      mediaProgressSyncer.stop(
              calledOnError == false
      ) { // If closing on error then do not sync progress (causes exception)
        Log.d(tag, "Media Progress syncer stopped")
        // If not local session then close on server
        if (!isLocal && currentSessionId != "") {
          apiHandler.closePlaybackSession(currentSessionId, config) {
            Log.d(tag, "Closed playback session $currentSessionId")
          }
        }
      }
    } else {
      // If not local session then close on server
      if (!isLocal && currentSessionId != "") {
        apiHandler.closePlaybackSession(currentSessionId, config) {
          Log.d(tag, "Closed playback session $currentSessionId")
        }
      }
    }

    try {
      currentPlayer.stop()
      currentPlayer.clearMediaItems()
    } catch (e: Exception) {
      Log.e(tag, "Exception clearing exoplayer $e")
    }

    currentPlaybackSession = null
    mediaProgressSyncer.reset()
    clientEventEmitter?.onPlaybackClosed()

    PlayerListener.lastPauseTime = 0
    isClosed = true
    DeviceManager.widgetUpdater?.onPlayerClosed()
    stopForeground(Service.STOP_FOREGROUND_REMOVE)
    stopSelf()
    closingPlayback = false
  }

  fun sendClientMetadata(playerState: PlayerState) {
    val duration = currentPlaybackSession?.getTotalDuration() ?: 0.0
    clientEventEmitter?.onMetadata(PlaybackMetadata(duration, getCurrentTimeSeconds(), playerState))
  }

  fun getMediaPlayer(): String {
    return if (currentPlayer == castPlayer) PLAYER_CAST else PLAYER_EXO
  }

  @SuppressLint("HardwareIds")
  fun getDeviceInfo(): DeviceInfo {
    /* EXAMPLE
     manufacturer: Google
     model: Pixel 6
     brand: google
     sdkVersion: 32
     appVersion: 0.9.46-beta
    */
    val deviceId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
    return DeviceInfo(
            deviceId,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.VERSION.SDK_INT,
            BuildConfig.VERSION_NAME
    )
  }

  private val deviceSettings
    get() = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()

  fun getPlayItemRequestPayload(forceTranscode: Boolean): PlayItemRequestPayload {
    return PlayItemRequestPayload(
            getMediaPlayer(),
            !forceTranscode,
            forceTranscode,
            getDeviceInfo()
    )
  }

  fun getContext(): Context {
    return ctx
  }

  fun alertSyncFailing() {
    clientEventEmitter?.onProgressSyncFailing()
  }

  fun alertSyncSuccess() {
    clientEventEmitter?.onProgressSyncSuccess()
  }

  //
  // MEDIA BROWSER STUFF (ANDROID AUTO)
  //
  private val VALID_MEDIA_BROWSERS =
          mutableListOf(
                  "com.audiobookshelf.app",
                  "com.audiobookshelf.app.debug",
                  ANDROID_AUTO_PKG_NAME,
                  ANDROID_AUTO_SIMULATOR_PKG_NAME,
                  ANDROID_WEARABLE_PKG_NAME,
                  ANDROID_GSEARCH_PKG_NAME,
                  ANDROID_AUTOMOTIVE_PKG_NAME
          )

  private val AUTO_MEDIA_ROOT = "/"
  private val LIBRARIES_ROOT = "__LIBRARIES__"
  private val RECENTLY_ROOT = "__RECENTLY__"
  private val DOWNLOADS_ROOT = "__DOWNLOADS__"
  private val CONTINUE_ROOT = "__CONTINUE__"
  private lateinit var browseTree: BrowseTree
  private val browseTreeInitListeners = mutableListOf<() -> Unit>()

  private fun waitForBrowseTree(cb: () -> Unit)
  {
    if (this::browseTree.isInitialized)
    {
      cb()
    }
    else
    {
      browseTreeInitListeners += cb
    }
  }

  private fun onBrowseTreeInitialized()
  {
    // Called after browseTree is assigned for the first time
    browseTreeInitListeners.forEach { it.invoke() }
    browseTreeInitListeners.clear()
  }

  // Only allowing android auto or similar to access media browser service
  //  normal loading of audiobooks is handled in webview (not natively)
  private fun isValid(packageName: String, uid: Int): Boolean {
    Log.d(tag, "onGetRoot: Checking package $packageName with uid $uid")
    if (!VALID_MEDIA_BROWSERS.contains(packageName)) {
      Log.d(tag, "onGetRoot: package $packageName not valid for the media browser service")
      return false
    }
    return true
  }

  override fun onGetRoot(
          clientPackageName: String,
          clientUid: Int,
          rootHints: Bundle?
  ): BrowserRoot? {
    val recent = rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT, false) == true
    val hasBookmark = try {
      DeviceManager.dbManager.getResumeBookmark() != null
    } catch (_: Throwable) {
      false
    }
    val decision =
            browserRootFor(
                    clientPackageName,
                    recent,
                    hasBookmark,
                    VALID_MEDIA_BROWSERS.contains(clientPackageName)
            )
    if (decision == BrowserDecision.Reject) {
      if (recent) AbsLogger.info(tag, "onGetRoot rejected package=$clientPackageName recent=true")
      return null
    }
    if (decision == BrowserDecision.RecentRoot) {
      AbsLogger.info(tag, "onGetRoot recent resume package=$clientPackageName")
      val extras = Bundle()
      extras.putBoolean(BrowserRoot.EXTRA_RECENT, true)
      return BrowserRoot(RECENT_MEDIA_ROOT, extras)
    }
    // Verify that the specified package is allowed to access your content
    return if (!isValid(clientPackageName, clientUid)) {
      // No further calls will be made to other media browsing methods.
      null
    } else {
      AbsLogger.info(tag, "onGetRoot: clientPackageName: $clientPackageName, clientUid: $clientUid")
      isStarted = true

      // Reset cache if no longer connected to server or server changed
      if (mediaManager.checkResetServerItems()) {
        AbsLogger.info(tag, "onGetRoot: Reset Android Auto server items cache (${DeviceManager.serverConnectionConfigString}). Playback is unchanged.")
        forceReloadingAndroidAuto = true
      }

      isAndroidAuto = true

      val extras = Bundle()
      extras.putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true)
      extras.putInt(
              MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
              MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
      )
      extras.putInt(
              MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
              MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
      )

      BrowserRoot(AUTO_MEDIA_ROOT, extras)
    }
  }

  private fun recentResumeChildren(): MutableList<MediaBrowserCompat.MediaItem> {
    val session = DeviceManager.deviceData.lastPlaybackSession
    val description =
            MediaDescriptionCompat.Builder()
                    .setMediaId(RESUME_MEDIA_ID)
                    .setTitle(session?.displayTitle ?: "Audiobookshelf")
                    .setSubtitle(session?.displayAuthor)
                    .build()
    return mutableListOf(
            MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    )
  }

  override fun onLoadChildren(
          parentMediaId: String,
          result: Result<MutableList<MediaBrowserCompat.MediaItem>>
  ) {
    AbsLogger.info(tag, "onLoadChildren: parentMediaId: $parentMediaId (${DeviceManager.serverConnectionConfigString})")

    if (parentMediaId == RECENT_MEDIA_ROOT) {
      result.sendResult(recentResumeChildren())
      return
    }

    result.detach()

    // Prevent crashing if app is restarted while browsing
    if ((parentMediaId != DOWNLOADS_ROOT && parentMediaId != AUTO_MEDIA_ROOT) && !firstLoadDone) {
      result.sendResult(null)
      return
    }

    if (parentMediaId == DOWNLOADS_ROOT) { // Load downloads
      val localBooks = DeviceManager.dbManager.getLocalLibraryItems("book")
      val localPodcasts = DeviceManager.dbManager.getLocalLibraryItems("podcast")
      val localBrowseItems: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()

      localBooks.forEach { localLibraryItem ->
        if (localLibraryItem.media.getAudioTracks().isNotEmpty()) {
          val progress = DeviceManager.dbManager.getLocalMediaProgress(localLibraryItem.id)
          val description = localLibraryItem.getMediaDescription(progress, ctx)

          localBrowseItems +=
                  MediaBrowserCompat.MediaItem(
                          description,
                          MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                  )
        }
      }

      localPodcasts.forEach { localLibraryItem ->
        val mediaDescription = localLibraryItem.getMediaDescription(null, ctx)
        localBrowseItems +=
                MediaBrowserCompat.MediaItem(
                        mediaDescription,
                        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                )
      }

      result.sendResult(localBrowseItems)
    } else if (parentMediaId == CONTINUE_ROOT) {
      val localBrowseItems: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
      mediaManager.serverItemsInProgress.forEach { itemInProgress ->
        val progress: MediaProgressWrapper?
        val mediaDescription: MediaDescriptionCompat
        if (itemInProgress.episode != null) {
          if (itemInProgress.isLocal) {
            progress =
                    DeviceManager.dbManager.getLocalMediaProgress(
                            "${itemInProgress.libraryItemWrapper.id}-${itemInProgress.episode.id}"
                    )
          } else {
            progress =
                    mediaManager.serverUserMediaProgress.find {
                      it.libraryItemId == itemInProgress.libraryItemWrapper.id &&
                              it.episodeId == itemInProgress.episode.id
                    }

            // to show download icon
            val localLibraryItem =
                    DeviceManager.dbManager.getLocalLibraryItemByLId(
                            itemInProgress.libraryItemWrapper.id
                    )
            localLibraryItem?.let { lli ->
              val localEpisode =
                      (lli.media as Podcast).episodes?.find {
                        it.serverEpisodeId == itemInProgress.episode.id
                      }
              itemInProgress.episode.localEpisodeId = localEpisode?.id
            }
          }
          mediaDescription =
                  itemInProgress.episode.getMediaDescription(
                          itemInProgress.libraryItemWrapper,
                          progress,
                          ctx
                  )
        } else {
          if (itemInProgress.isLocal) {
            progress =
                    DeviceManager.dbManager.getLocalMediaProgress(
                            itemInProgress.libraryItemWrapper.id
                    )
          } else {
            progress =
                    mediaManager.serverUserMediaProgress.find {
                      it.libraryItemId == itemInProgress.libraryItemWrapper.id
                    }

            val localLibraryItem =
                    DeviceManager.dbManager.getLocalLibraryItemByLId(
                            itemInProgress.libraryItemWrapper.id
                    )
            (itemInProgress.libraryItemWrapper as LibraryItem).localLibraryItemId =
                    localLibraryItem?.id // To show downloaded icon
          }
          mediaDescription = itemInProgress.libraryItemWrapper.getMediaDescription(progress, ctx)
        }
        localBrowseItems +=
                MediaBrowserCompat.MediaItem(
                        mediaDescription,
                        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                )
      }
      result.sendResult(localBrowseItems)
    } else if (parentMediaId == AUTO_MEDIA_ROOT) {
      Log.d(tag, "Trying to initialize browseTree.")
      if (!this::browseTree.isInitialized || forceReloadingAndroidAuto) {
        forceReloadingAndroidAuto = false
        AbsLogger.info(tag, "onLoadChildren: Loading Android Auto items")
        mediaManager.loadAndroidAutoItems {
          AbsLogger.info(tag, "onLoadChildren: Loaded Android Auto data, initializing browseTree")

          browseTree =
                  BrowseTree(
                          this,
                          mediaManager.serverItemsInProgress,
                          mediaManager.serverLibraries,
                          mediaManager.allLibraryPersonalizationsDone
                  )
          onBrowseTreeInitialized()
          val children =
                  browseTree[parentMediaId]?.map { item ->
                    Log.d(tag, "Found top menu item: ${item.description.title}")
                    MediaBrowserCompat.MediaItem(
                            item.description,
                            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                    )
                  }

          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          firstLoadDone = true
          if (mediaManager.serverLibraries.isNotEmpty()) {
            AbsLogger.info(tag, "onLoadChildren: Android Auto fetching personalized data for all libraries")
            mediaManager.populatePersonalizedDataForAllLibraries {
              AbsLogger.info(tag, "onLoadChildren: Android Auto loaded personalized data for all libraries")
              notifyChildrenChanged("/")
            }

            AbsLogger.info(tag, "onLoadChildren: Android Auto fetching in progress items")
            mediaManager.initializeInProgressItems {
              AbsLogger.info(tag, "onLoadChildren: Android Auto loaded in progress items")
              notifyChildrenChanged("/")
            }
          }
        }
      } else {
        Log.d(tag, "Starting browseTree refresh")
        browseTree =
                BrowseTree(
                        this,
                        mediaManager.serverItemsInProgress,
                        mediaManager.serverLibraries,
                        mediaManager.allLibraryPersonalizationsDone
                )
        onBrowseTreeInitialized()
        val children =
                browseTree[parentMediaId]?.map { item ->
                  Log.d(tag, "Found top menu item: ${item.description.title}")
                  MediaBrowserCompat.MediaItem(
                          item.description,
                          MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                  )
                }

        AbsLogger.info(tag, "onLoadChildren: Android auto data loaded")
        result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
      }
    } else if (parentMediaId == LIBRARIES_ROOT || parentMediaId == RECENTLY_ROOT)
    {
      Log.d(tag, "First load done: $firstLoadDone")
      if (!firstLoadDone)
      {
        result.sendResult(null)
        return
      }

      if (!this::browseTree.isInitialized)
      {
        // ✅ good: detach and wait for init
        result.detach()
        waitForBrowseTree {
          val children = browseTree[parentMediaId]?.map { item ->
            Log.d(tag, "[MENU: $parentMediaId] Showing list item ${item.description.title}")
            MediaBrowserCompat.MediaItem(
              item.description,
              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
            )
          }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
        return
      }

      // Already initialized: just return
      val children = browseTree[parentMediaId]?.map { item ->
        Log.d(tag, "[MENU: $parentMediaId] Showing list item ${item.description.title}")
        MediaBrowserCompat.MediaItem(
          item.description,
          MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
      }
      result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
    } else if (mediaManager.getIsLibrary(parentMediaId)) { // Load library items for library
      Log.d(tag, "Loading items for library $parentMediaId")
      val selectedLibrary = mediaManager.getLibrary(parentMediaId)
      if (selectedLibrary?.mediaType == "podcast") { // Podcasts are browseable
        mediaManager.loadLibraryPodcasts(parentMediaId) { libraryItems ->
          val children =
                  libraryItems?.map { libraryItem ->
                    val mediaDescription = libraryItem.getMediaDescription(null, ctx)
                    MediaBrowserCompat.MediaItem(
                            mediaDescription,
                            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                    )
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else {
        val children =
                mutableListOf(
                        MediaBrowserCompat.MediaItem(
                                MediaDescriptionCompat.Builder()
                                        .setTitle("Authors")
                                        .setMediaId("__LIBRARY__${parentMediaId}__AUTHORS")
                                        .setIconUri(getUriToAbsIconDrawable(ctx, "authors"))
                                        .build(),
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        ),
                        MediaBrowserCompat.MediaItem(
                                MediaDescriptionCompat.Builder()
                                        .setTitle("Series")
                                        .setMediaId("__LIBRARY__${parentMediaId}__SERIES_LIST")
                                        .setIconUri(getUriToAbsIconDrawable(ctx, "columns"))
                                        .build(),
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        ),
                        MediaBrowserCompat.MediaItem(
                                MediaDescriptionCompat.Builder()
                                        .setTitle("Collections")
                                        .setMediaId("__LIBRARY__${parentMediaId}__COLLECTIONS")
                                        .setIconUri(
                                                getUriToDrawable(
                                                        ctx,
                                                        R.drawable.md_book_multiple_outline
                                                )
                                        )
                                        .build(),
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        )
                )
        if (mediaManager.getHasDiscovery(parentMediaId)) {
          children.add(
                  MediaBrowserCompat.MediaItem(
                          MediaDescriptionCompat.Builder()
                                  .setTitle("Discovery")
                                  .setMediaId("__LIBRARY__${parentMediaId}__DISCOVERY")
                                  .setIconUri(getUriToDrawable(ctx, R.drawable.md_telescope))
                                  .build(),
                          MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                  )
          )
        }
        result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
      }
    } else if (parentMediaId.startsWith(RECENTLY_ROOT)) {
      Log.d(tag, "Browsing recently $parentMediaId")
      val mediaIdParts = parentMediaId.split("__")
      if (!mediaManager.getIsLibrary(mediaIdParts[2])) {
        Log.d(tag, "${mediaIdParts[2]} is not library")
        result.sendResult(null)
        return
      }
      Log.d(tag, "Mediaparts: ${mediaIdParts.size} | $mediaIdParts")
      if (mediaIdParts.size == 3) {
        mediaManager.getLibraryRecentShelfs(mediaIdParts[2]) { availableShelfs ->
          Log.d(tag, "Found ${availableShelfs.size} shelfs")
          val children: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
          for (shelf in availableShelfs) {
            if (shelf.type == "book") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Books")
                                      .setMediaId("${parentMediaId}__BOOK")
                                      .setIconUri(
                                              getUriToDrawable(
                                                      ctx,
                                                      R.drawable.md_book_open_blank_variant_outline
                                              )
                                      )
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            } else if (shelf.type == "series") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Series")
                                      .setMediaId("${parentMediaId}__SERIES")
                                      .setIconUri(getUriToAbsIconDrawable(ctx, "columns"))
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            } else if (shelf.type == "episode") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Episodes")
                                      .setMediaId("${parentMediaId}__EPISODE")
                                      .setIconUri(getUriToAbsIconDrawable(ctx, "microphone_2"))
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            } else if (shelf.type == "podcast") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Podcast")
                                      .setMediaId("${parentMediaId}__PODCAST")
                                      .setIconUri(getUriToAbsIconDrawable(ctx, "podcast"))
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            } else if (shelf.type == "authors") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Authors")
                                      .setMediaId("${parentMediaId}__AUTHORS")
                                      .setIconUri(getUriToAbsIconDrawable(ctx, "authors"))
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            }
          }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts.size == 4) {
        mediaManager.getLibraryRecentShelfByType(mediaIdParts[2], mediaIdParts[3]) { shelf ->
          if (shelf === null) {
            result.sendResult(mutableListOf())
          } else {
            if (shelf.type == "book") {
              val children =
                      (shelf as LibraryShelfBookEntity).entities?.map { libraryItem ->
                        val progress =
                                mediaManager.serverUserMediaProgress.find {
                                  it.libraryItemId == libraryItem.id
                                }
                        val localLibraryItem =
                                DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItem.id)
                        libraryItem.localLibraryItemId = localLibraryItem?.id
                        val description =
                                libraryItem.getMediaDescription(progress, ctx, null, false)
                        MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                        )
                      }
              result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
            } else if (shelf.type == "episode") {
              val episodesWithRecentEpisode =
                      (shelf as LibraryShelfEpisodeEntity).entities?.filter { libraryItem ->
                        libraryItem.recentEpisode !== null
                      }
              val children =
                      episodesWithRecentEpisode?.map { libraryItem ->
                        val podcast = libraryItem.media as Podcast
                        val progress =
                                mediaManager.serverUserMediaProgress.find {
                                  it.libraryItemId == libraryItem.libraryId &&
                                          it.episodeId == libraryItem.recentEpisode?.id
                                }

                        // to show download icon
                        val localLibraryItem =
                                DeviceManager.dbManager.getLocalLibraryItemByLId(
                                        libraryItem.recentEpisode!!.id
                                )
                        localLibraryItem?.let { lli ->
                          val localEpisode =
                                  (lli.media as Podcast).episodes?.find {
                                    it.serverEpisodeId == libraryItem.recentEpisode.id
                                  }
                          libraryItem.recentEpisode.localEpisodeId = localEpisode?.id
                        }

                        val description =
                                libraryItem.recentEpisode.getMediaDescription(
                                        libraryItem,
                                        progress,
                                        ctx
                                )
                        MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                        )
                      }
              result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
            } else if (shelf.type == "podcast") {
              val children =
                      (shelf as LibraryShelfPodcastEntity).entities?.map { libraryItem ->
                        val mediaDescription = libraryItem.getMediaDescription(null, ctx)
                        MediaBrowserCompat.MediaItem(
                                mediaDescription,
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        )
                      }
              result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
            } else if (shelf.type == "series") {
              val children =
                      (shelf as LibraryShelfSeriesEntity).entities?.map { librarySeriesItem ->
                        val description = librarySeriesItem.getMediaDescription(null, ctx)
                        MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        )
                      }
              result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
            } else if (shelf.type == "authors") {
              val children =
                      (shelf as LibraryShelfAuthorEntity).entities?.map { authorItem ->
                        val description = authorItem.getMediaDescription(null, ctx)
                        MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        )
                      }
              result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
            } else {
              result.sendResult(mutableListOf())
            }
          }
        }
      }
    } else if (parentMediaId.startsWith("__LIBRARY__")) {
      Log.d(tag, "Browsing library $parentMediaId")
      val mediaIdParts = parentMediaId.split("__")
      /*
       MediaIdParts for Library
       1: LIBRARY
       2: mediaId for library
       3: Browsing style (AUTHORS, AUTHOR, AUTHOR_SERIES, SERIES_LIST, SERIES, COLLECTION, COLLECTIONS, DISCOVERY)
       4:
         - Paging: SERIES_LIST, AUTHORS
         - SeriesId: SERIES
         - AuthorId: AUTHOR, AUTHOR_SERIES
         - CollectionId: COLLECTIONS
       5: SeriesId: AUTHOR_SERIES
      */
      if (!mediaManager.getIsLibrary(mediaIdParts[2])) {
        Log.d(tag, "${mediaIdParts[2]} is not library")
        result.sendResult(null)
        return
      }
      Log.d(tag, "$mediaIdParts")
      if (mediaIdParts[3] == "SERIES_LIST" && mediaIdParts.size == 5) {
        Log.d(tag, "Loading series from library ${mediaIdParts[2]} with paging ${mediaIdParts[4]}")
        mediaManager.loadLibrarySeriesWithAudio(mediaIdParts[2], mediaIdParts[4]) { seriesItems ->
          Log.d(tag, "Received ${seriesItems.size} series")

          val seriesLetters =
                  seriesItems
                          .groupingBy { iwb ->
                            iwb.title.substring(0, mediaIdParts[4].length + 1).uppercase()
                          }
                          .eachCount()
          if (seriesItems.size >
                          DeviceManager.deviceData.deviceSettings!!
                                  .androidAutoBrowseLimitForGrouping &&
                          seriesItems.size > 1 &&
                          seriesLetters.size > 1
          ) {
            val children =
                    seriesLetters.map { (seriesLetter, seriesCount) ->
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle(seriesLetter)
                                      .setMediaId("${parentMediaId}${seriesLetter.last()}")
                                      .setSubtitle("$seriesCount series")
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          } else {
            val children =
                    seriesItems.map { seriesItem ->
                      val description = seriesItem.getMediaDescription(null, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          }
        }
      } else if (mediaIdParts[3] == "SERIES_LIST") {
        Log.d(tag, "Loading series from library ${mediaIdParts[2]}")
        mediaManager.loadLibrarySeriesWithAudio(mediaIdParts[2]) { seriesItems ->
          Log.d(tag, "Received ${seriesItems.size} series")
          if (seriesItems.size >
                          DeviceManager.deviceData.deviceSettings!!
                                  .androidAutoBrowseLimitForGrouping && seriesItems.size > 1
          ) {
            val seriesLetters =
                    seriesItems.groupingBy { iwb -> iwb.title.first().uppercaseChar() }.eachCount()
            val children =
                    seriesLetters.map { (seriesLetter, seriesCount) ->
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle(seriesLetter.toString())
                                      .setSubtitle("$seriesCount series")
                                      .setMediaId("${parentMediaId}__${seriesLetter}")
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          } else {
            val children =
                    seriesItems.map { seriesItem ->
                      val description = seriesItem.getMediaDescription(null, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          }
        }
      } else if (mediaIdParts[3] == "SERIES") {
        Log.d(tag, "Loading items for serie ${mediaIdParts[4]} from library ${mediaIdParts[2]}")
        mediaManager.loadLibrarySeriesItemsWithAudio(mediaIdParts[2], mediaIdParts[4]) {
                libraryItems ->
          Log.d(tag, "Received ${libraryItems.size} library items")
          var items = libraryItems
          if (DeviceManager.deviceData.deviceSettings!!.androidAutoBrowseSeriesSequenceOrder ===
                          AndroidAutoBrowseSeriesSequenceOrderSetting.DESC
          ) {
            items = libraryItems.reversed()
          }
          val children =
                  items.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItem.id)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    val description = libraryItem.getMediaDescription(progress, ctx, null, true)
                    MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                    )
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts[3] == "AUTHORS" && mediaIdParts.size == 5) {
        Log.d(tag, "Loading authors from library ${mediaIdParts[2]} with paging ${mediaIdParts[4]}")
        mediaManager.loadAuthorsWithBooks(mediaIdParts[2], mediaIdParts[4]) { authorItems ->
          Log.d(tag, "Received ${authorItems.size} authors")

          val authorLetters =
                  authorItems
                          .groupingBy { iwb ->
                            iwb.name.substring(0, mediaIdParts[4].length + 1).uppercase()
                          }
                          .eachCount()
          if (authorItems.size >
                          DeviceManager.deviceData.deviceSettings!!
                                  .androidAutoBrowseLimitForGrouping &&
                          authorItems.size > 1 &&
                          authorLetters.size > 1
          ) {
            val children =
                    authorLetters.map { (authorLetter, authorCount) ->
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle(authorLetter)
                                      .setMediaId("${parentMediaId}${authorLetter.last()}")
                                      .setSubtitle("$authorCount authors")
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          } else {
            val children =
                    authorItems.map { authorItem ->
                      val description = authorItem.getMediaDescription(null, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          }
        }
      } else if (mediaIdParts[3] == "AUTHORS") {
        Log.d(tag, "Loading authors from library ${mediaIdParts[2]}")
        mediaManager.loadAuthorsWithBooks(mediaIdParts[2]) { authorItems ->
          Log.d(tag, "Received ${authorItems.size} authors")
          if (authorItems.size >
                          DeviceManager.deviceData.deviceSettings!!
                                  .androidAutoBrowseLimitForGrouping && authorItems.size > 1
          ) {
            val authorLetters =
                    authorItems.groupingBy { iwb -> iwb.name.first().uppercaseChar() }.eachCount()
            val children =
                    authorLetters.map { (authorLetter, authorCount) ->
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle(authorLetter.toString())
                                      .setSubtitle("$authorCount authors")
                                      .setMediaId("${parentMediaId}__${authorLetter}")
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          } else {
            val children =
                    authorItems.map { authorItem ->
                      val description = authorItem.getMediaDescription(null, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          }
        }
      } else if (mediaIdParts[3] == "AUTHOR") {
        mediaManager.loadAuthorBooksWithAudio(mediaIdParts[2], mediaIdParts[4]) { libraryItems ->
          val children =
                  libraryItems.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItem.id)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    if (libraryItem.collapsedSeries != null) {
                      val description =
                              libraryItem.getMediaDescription(progress, ctx, mediaIdParts[4])
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    } else {
                      val description = libraryItem.getMediaDescription(progress, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                      )
                    }
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts[3] == "AUTHOR_SERIES") {
        mediaManager.loadAuthorSeriesBooksWithAudio(
                mediaIdParts[2],
                mediaIdParts[4],
                mediaIdParts[5]
        ) { libraryItems ->
          var items = libraryItems
          if (DeviceManager.deviceData.deviceSettings!!.androidAutoBrowseSeriesSequenceOrder ===
                          AndroidAutoBrowseSeriesSequenceOrderSetting.DESC
          ) {
            items = libraryItems.reversed()
          }
          val children =
                  items.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItem.id)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    val description = libraryItem.getMediaDescription(progress, ctx, null, true)
                    if (libraryItem.collapsedSeries != null) {
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    } else {
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                      )
                    }
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts[3] == "COLLECTIONS") {
        Log.d(tag, "Loading collections from library ${mediaIdParts[2]}")
        mediaManager.loadLibraryCollectionsWithAudio(mediaIdParts[2]) { collectionItems ->
          Log.d(tag, "Received ${collectionItems.size} collections")
          val children =
                  collectionItems.map { collectionItem ->
                    val description = collectionItem.getMediaDescription(null, ctx)
                    MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                    )
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts[3] == "COLLECTION") {
        Log.d(tag, "Loading collection ${mediaIdParts[4]} books from library ${mediaIdParts[2]}")
        mediaManager.loadLibraryCollectionBooksWithAudio(mediaIdParts[2], mediaIdParts[4]) {
                libraryItems ->
          Log.d(tag, "Received ${libraryItems.size} collections")
          val children =
                  libraryItems.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItem.id)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    val description = libraryItem.getMediaDescription(progress, ctx)
                    MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                    )
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts[3] == "DISCOVERY") {
        Log.d(tag, "Loading discovery from library ${mediaIdParts[2]}")
        mediaManager.loadLibraryDiscoveryBooksWithAudio(mediaIdParts[2]) { libraryItems ->
          Log.d(tag, "Received ${libraryItems.size} libraryItems for discovery")
          val children =
                  libraryItems.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItem.id)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    val description = libraryItem.getMediaDescription(progress, ctx)
                    MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                    )
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else {
        result.sendResult(null)
      }
    } else {
      Log.d(tag, "Loading podcast episodes for podcast $parentMediaId")
      mediaManager.loadPodcastEpisodeMediaBrowserItems(parentMediaId, ctx) { result.sendResult(it) }
    }
  }

  override fun onSearch(
          query: String,
          extras: Bundle?,
          result: Result<MutableList<MediaBrowserCompat.MediaItem>>
  ) {
    result.detach()
    if (cachedSearch != query) {
      Log.d(tag, "Search bundle: $extras")
      var foundBooks: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
      var foundPodcasts: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
      var foundSeries: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
      var foundAuthors: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()

      mediaManager.serverLibraries.forEach { serverLibrary ->
        runBlocking {
          // Skip searching library if it doesn't have any audio files
          if (serverLibrary.stats?.numAudioFiles == 0) return@runBlocking
          val searchResult = mediaManager.doSearch(serverLibrary.id, query)
          for (resultData in searchResult.entries.iterator()) {
            when (resultData.key) {
              "book" -> foundBooks.addAll(resultData.value)
              "series" -> foundSeries.addAll(resultData.value)
              "authors" -> foundAuthors.addAll(resultData.value)
              "podcast" -> foundPodcasts.addAll(resultData.value)
            }
          }
        }
      }
      foundBooks.addAll(foundSeries)
      foundBooks.addAll(foundAuthors)
      cachedSearchResults = foundBooks
    }
    result.sendResult(cachedSearchResults)
    cachedSearch = query
    Log.d(tag, "onSearch: Done")
  }

  //
  // SHAKE SENSOR
  //
  private fun initSensor() {
    // ShakeDetector initialization
    mSensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    mAccelerometer = mSensorManager!!.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    mShakeDetector = ShakeDetector()
    mShakeDetector!!.setOnShakeListener(
            object : ShakeDetector.OnShakeListener {
              override fun onShake(count: Int) {
                Log.d(tag, "PHONE SHAKE! $count")
                sleepTimerManager.handleShake()
              }
            }
    )
  }

  // Shake sensor used for sleep timer
  fun registerSensor() {
    if (isShakeSensorRegistered) {
      Log.i(tag, "Shake sensor already registered")
      return
    }
    shakeSensorUnregisterTask?.cancel()

    Log.d(tag, "Registering shake SENSOR ${mAccelerometer?.isWakeUpSensor}")
    val success =
            mSensorManager!!.registerListener(
                    mShakeDetector,
                    mAccelerometer,
                    SensorManager.SENSOR_DELAY_UI
            )
    if (success) isShakeSensorRegistered = true
  }

  fun unregisterSensor() {
    if (!isShakeSensorRegistered) return

    // Unregister shake sensor after wake up expiration
    shakeSensorUnregisterTask?.cancel()
    shakeSensorUnregisterTask =
            Timer("ShakeUnregisterTimer", false).schedule(SLEEP_TIMER_WAKE_UP_EXPIRATION) {
              Handler(Looper.getMainLooper()).post {
                Log.d(tag, "wake time expired: Unregistering shake sensor")
                mSensorManager!!.unregisterListener(mShakeDetector)
                isShakeSensorRegistered = false
              }
            }
  }

  private val networkCallback =
          object : ConnectivityManager.NetworkCallback() {
            // Network capabilities have changed for the network
            override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
            ) {
              super.onCapabilitiesChanged(network, networkCapabilities)

              val wasConnected = hasNetworkConnectivity
              isUnmeteredNetwork =
                      networkCapabilities.hasCapability(
                              NetworkCapabilities.NET_CAPABILITY_NOT_METERED
                      )
              hasNetworkConnectivity =
                      networkCapabilities.hasCapability(
                              NetworkCapabilities.NET_CAPABILITY_VALIDATED
                      ) &&
                              networkCapabilities.hasCapability(
                                      NetworkCapabilities.NET_CAPABILITY_INTERNET
                              )
              val networkRestored = !wasConnected && hasNetworkConnectivity
              val networkLost = wasConnected && !hasNetworkConnectivity
              Log.i(
                      tag,
                      "Network capabilities changed. hasNetworkConnectivity=$hasNetworkConnectivity | isUnmeteredNetwork=$isUnmeteredNetwork | restored=$networkRestored"
              )
              if (networkRestored) AbsLogger.info(tag, "Network restored")
              if (networkLost) AbsLogger.info(tag, "Network lost")
              clientEventEmitter?.onNetworkMeteredChanged(isUnmeteredNetwork)
              if (networkRestored) {
                Handler(Looper.getMainLooper()).post { mediaProgressSyncer.onNetworkRestored() }
              }
              if (hasNetworkConnectivity) {
                // Force android auto loading if libraries are empty.
                // Lack of network connectivity is most likely reason for libraries being empty
                if (isBrowseTreeInitialized() &&
                                firstLoadDone &&
                                mediaManager.serverLibraries.isEmpty()
                ) {
                  forceReloadingAndroidAuto = true
                  notifyChildrenChanged("/")
                }
              }
            }
          }

  inner class JumpBackwardCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_JUMP_BACKWARD,
                      getContext().getString(R.string.action_jump_backward),
                      R.drawable.exo_icon_rewind
              )
              .build()
    }
  }

  inner class JumpForwardCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_JUMP_FORWARD,
                      getContext().getString(R.string.action_jump_forward),
                      R.drawable.exo_icon_fastforward
              )
              .build()
    }
  }

  inner class SkipForwardCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_SKIP_FORWARD,
                      getContext().getString(R.string.action_skip_forward),
                      R.drawable.skip_next_24
              )
              .build()
    }
  }

  inner class SkipBackwardCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_SKIP_BACKWARD,
                      getContext().getString(R.string.action_skip_backward),
                      R.drawable.skip_previous_24
              )
              .build()
    }
  }

  inner class ChangePlaybackSpeedCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      val playbackRate = mediaManager.getSavedPlaybackRate()

      // Rounding values in the event a non preset value (.5, 1, 1.2, 1.5, 2, 3) is selected in the
      // phone app
      val drawable: Int =
              when (playbackRate) {
                in 0.5f..0.7f -> R.drawable.ic_play_speed_0_5x
                in 0.8f..1.0f -> R.drawable.ic_play_speed_1_0x
                in 1.1f..1.3f -> R.drawable.ic_play_speed_1_2x
                in 1.4f..1.7f -> R.drawable.ic_play_speed_1_5x
                in 1.8f..2.4f -> R.drawable.ic_play_speed_2_0x
                in 2.5f..3.0f -> R.drawable.ic_play_speed_3_0x
                // anything set above 3 will be show the 3x to save from creating 100 icons
                else -> R.drawable.ic_play_speed_3_0x
              }
      val customActionExtras = Bundle()
      customActionExtras.putFloat("speed", playbackRate)
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_CHANGE_SPEED,
                      getContext().getString(R.string.action_change_speed),
                      drawable
              )
              .setExtras(customActionExtras)
              .build()
    }
  }
}
