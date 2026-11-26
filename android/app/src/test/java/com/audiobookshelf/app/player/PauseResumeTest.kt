package com.audiobookshelf.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseResumeTest {
  private fun bookmark(savedAt: Long, position: Double) =
          ResumeBookmark(
                  libraryItemId = "lib-1",
                  episodeId = null,
                  local = false,
                  positionSeconds = position,
                  savedAt = savedAt,
                  serverConnectionConfigId = "srv",
                  queue = listOf(PlaybackQueueEntry("lib-2", null)),
                  serverLibraryItemId = "lib-1",
                  serverEpisodeId = null
          )

  @Test
  fun userPauseKeepsTheMediaForegroundService() {
    // Android 15 drops transport controls when the media session is not a mediaPlayback
    // foreground service. A pause must leave the service in the foreground.
    assertEquals(ForegroundChange.None, foregroundChangeFor(PauseStopReason.UserPause))
  }

  @Test
  fun sleepTimerDismissAndCloseRemoveTheNotification() {
    assertEquals(ForegroundChange.RemoveNotification, foregroundChangeFor(PauseStopReason.SleepTimer))
    assertEquals(ForegroundChange.RemoveNotification, foregroundChangeFor(PauseStopReason.NotificationDismiss))
    assertEquals(ForegroundChange.RemoveNotification, foregroundChangeFor(PauseStopReason.UserClose))
  }

  @Test
  fun playOnEmptyPlayerWithBookmarkRestarts() {
    assertEquals(
            ResumeAction.ResumeFromBookmark,
            resumeAction(TransportButton.Play, LoadedPlayer.Empty, hasBookmark = true)
    )
  }

  @Test
  fun playOnEmptyPlayerWithoutBookmarkDoesNothing() {
    assertEquals(
            ResumeAction.Ignore,
            resumeAction(TransportButton.Play, LoadedPlayer.Empty, hasBookmark = false)
    )
  }

  @Test
  fun playOnPausedPlayerResumesTheLoadedItem() {
    assertEquals(
            ResumeAction.PlayLoaded,
            resumeAction(TransportButton.Play, LoadedPlayer.Paused, hasBookmark = true)
    )
  }

  @Test
  fun pauseKeepsTheSessionAndStopDoesNotClearIt() {
    assertEquals(
            ResumeAction.PauseAndKeep,
            resumeAction(TransportButton.Pause, LoadedPlayer.Playing, hasBookmark = true)
    )
    assertEquals(
            ResumeAction.PauseAndKeep,
            resumeAction(TransportButton.Stop, LoadedPlayer.Playing, hasBookmark = true)
    )
  }

  @Test
  fun playPauseTogglesAndRestartsWhenEmpty() {
    assertEquals(
            ResumeAction.PauseAndKeep,
            resumeAction(TransportButton.PlayPause, LoadedPlayer.Playing, hasBookmark = true)
    )
    assertEquals(
            ResumeAction.ResumeFromBookmark,
            resumeAction(TransportButton.PlayPause, LoadedPlayer.Empty, hasBookmark = true)
    )
  }

  @Test
  fun resumeAndPlayEnterTheForegroundBeforeAnyNetworkWork() {
    assertTrue(mustForegroundImmediately(ResumeAction.ResumeFromBookmark))
    assertTrue(mustForegroundImmediately(ResumeAction.PlayLoaded))
    assertFalse(mustForegroundImmediately(ResumeAction.PauseAndKeep))
    assertEquals(ForegroundChange.Promote, foregroundChangeFor(ResumeAction.ResumeFromBookmark))
    assertEquals(ForegroundChange.None, foregroundChangeFor(ResumeAction.PauseAndKeep))
    assertEquals(ForegroundChange.RemoveNotification, foregroundChangeFor(ResumeAction.StopAndClear))
  }

  @Test
  fun downloadedBookPlaysLocallyAndKeepsTheQueue() {
    val saved = bookmark(savedAt = 100L, position = 50.0)
    val downloads = listOf(DownloadedItem("local-1", "lib-1", emptyList()))
    val choice = chooseResume(saved, downloads, LoadedPlayer.Empty)
    assertEquals(ResumeSource.LocalFile, choice.source)
    assertEquals("local-1", choice.localLibraryItemId)
    assertNull(choice.localEpisodeId)
    assertEquals(saved.queue, choice.queue)
  }

  @Test
  fun missingDownloadStreamsTheServerItem() {
    val saved = bookmark(savedAt = 100L, position = 50.0)
    val choice = chooseResume(saved, emptyList(), LoadedPlayer.Empty)
    assertEquals(ResumeSource.NewServerSession, choice.source)
    assertEquals("lib-1", choice.serverLibraryItemId)
    assertEquals(saved.queue, choice.queue)
  }

  @Test
  fun downloadedEpisodePlaysThatLocalFile() {
    val saved = bookmark(savedAt = 100L, position = 12.0).copy(episodeId = "ep-9", serverEpisodeId = "ep-9")
    val downloads =
            listOf(
                    DownloadedItem(
                            "local-pod",
                            "lib-1",
                            listOf(DownloadedEpisode("local-ep-9", "ep-9"), DownloadedEpisode("local-ep-8", "ep-8"))
                    )
            )
    val choice = chooseResume(saved, downloads, LoadedPlayer.Empty)
    assertEquals(ResumeSource.LocalFile, choice.source)
    assertEquals("local-pod", choice.localLibraryItemId)
    assertEquals("local-ep-9", choice.localEpisodeId)
    assertEquals(saved.queue, choice.queue)
  }

  @Test
  fun podcastDownloadWithoutThatEpisodeStillStreams() {
    val saved = bookmark(savedAt = 100L, position = 12.0).copy(episodeId = "ep-9", serverEpisodeId = "ep-9")
    val downloads =
            listOf(DownloadedItem("local-pod", "lib-1", listOf(DownloadedEpisode("local-ep-8", "ep-8"))))
    val choice = chooseResume(saved, downloads, LoadedPlayer.Empty)
    assertEquals(ResumeSource.NewServerSession, choice.source)
    assertEquals("ep-9", choice.serverEpisodeId)
  }

  @Test
  fun pausedStreamSwitchesToTheDownload() {
    val saved = bookmark(savedAt = 100L, position = 50.0)
    val downloads = listOf(DownloadedItem("local-1", "lib-1", emptyList()))
    val choice = chooseResume(saved, downloads, LoadedPlayer.Paused)
    assertEquals(ResumeSource.LocalFile, choice.source)
    assertEquals("local-1", choice.localLibraryItemId)
  }

  @Test
  fun pausedStreamWithoutADownloadKeepsTheLoadedPlayer() {
    val saved = bookmark(savedAt = 100L, position = 50.0)
    val choice = chooseResume(saved, emptyList(), LoadedPlayer.Paused)
    assertEquals(ResumeSource.AlreadyLoaded, choice.source)
  }

  @Test
  fun localOnlyBookmarkWithNoFileIsUnavailable() {
    val saved =
            bookmark(savedAt = 100L, position = 50.0)
                    .copy(libraryItemId = "local-1", local = true, serverLibraryItemId = null, serverEpisodeId = null)
    val choice = chooseResume(saved, emptyList(), LoadedPlayer.Empty)
    assertEquals(ResumeSource.Unavailable, choice.source)
  }

  @Test
  fun localBookStillOnDiskPlaysThatFile() {
    val saved =
            bookmark(savedAt = 100L, position = 50.0)
                    .copy(libraryItemId = "local-1", local = true, serverLibraryItemId = null, serverEpisodeId = null)
    val downloads = listOf(DownloadedItem("local-1", null, emptyList()))
    val choice = chooseResume(saved, downloads, LoadedPlayer.Empty)
    assertEquals(ResumeSource.LocalFile, choice.source)
    assertEquals("local-1", choice.localLibraryItemId)
    assertNull(choice.localEpisodeId)
  }

  @Test
  fun localBookmarkWithServerIdStreamsWhenTheFileIsGone() {
    val saved =
            bookmark(savedAt = 100L, position = 50.0)
                    .copy(libraryItemId = "local-1", local = true, serverLibraryItemId = "lib-1")
    val choice = chooseResume(saved, emptyList(), LoadedPlayer.Empty)
    assertEquals(ResumeSource.NewServerSession, choice.source)
    assertEquals("lib-1", choice.serverLibraryItemId)
  }

  @Test
  fun localEpisodeWithoutAServerIdPlaysTheLocalFile() {
    val saved =
            bookmark(savedAt = 100L, position = 12.0)
                    .copy(
                            libraryItemId = "local-pod",
                            episodeId = "local-ep",
                            local = true,
                            serverLibraryItemId = null,
                            serverEpisodeId = null
                    )
    val downloads =
            listOf(DownloadedItem("local-pod", null, listOf(DownloadedEpisode("local-ep", null))))
    val choice = chooseResume(saved, downloads, LoadedPlayer.Empty)
    assertEquals(ResumeSource.LocalFile, choice.source)
    assertEquals("local-pod", choice.localLibraryItemId)
    assertEquals("local-ep", choice.localEpisodeId)
  }

  @Test
  fun localSessionBookmarkKeepsTheServerLinkAndCopiesTheQueue() {
    val queue = mutableListOf(PlaybackQueueEntry("next", null))
    val saved =
            resumeBookmarkFrom(
                    isLocal = true,
                    sessionLibraryItemId = "server-book",
                    sessionEpisodeId = null,
                    localLibraryItemId = "local-1",
                    localEpisodeId = null,
                    positionSeconds = 40.0,
                    savedAt = 5L,
                    serverConnectionConfigId = "srv",
                    queue = queue
            )
    queue.add(PlaybackQueueEntry("later", null))
    assertEquals("local-1", saved!!.libraryItemId)
    assertTrue(saved.local)
    assertEquals("server-book", saved.serverLibraryItemId)
    assertEquals(1, saved.queue.size)
  }

  @Test
  fun streamBookmarkUsesTheServerId() {
    val saved =
            resumeBookmarkFrom(
                    isLocal = false,
                    sessionLibraryItemId = "server-book",
                    sessionEpisodeId = "ep-1",
                    localLibraryItemId = null,
                    localEpisodeId = null,
                    positionSeconds = 40.0,
                    savedAt = 5L,
                    serverConnectionConfigId = "srv",
                    queue = emptyList()
            )
    assertEquals("server-book", saved!!.libraryItemId)
    assertEquals("ep-1", saved.episodeId)
    assertEquals("ep-1", saved.serverEpisodeId)
    assertFalse(saved.local)
  }

  @Test
  fun bookmarkWithoutAnIdIsNull() {
    assertNull(
            resumeBookmarkFrom(
                    isLocal = true,
                    sessionLibraryItemId = "server-book",
                    sessionEpisodeId = null,
                    localLibraryItemId = null,
                    localEpisodeId = null,
                    positionSeconds = 1.0,
                    savedAt = 1L,
                    serverConnectionConfigId = null,
                    queue = emptyList()
            )
    )
    assertNull(
            resumeBookmarkFrom(
                    isLocal = false,
                    sessionLibraryItemId = null,
                    sessionEpisodeId = null,
                    localLibraryItemId = null,
                    localEpisodeId = null,
                    positionSeconds = 1.0,
                    savedAt = 1L,
                    serverConnectionConfigId = null,
                    queue = emptyList()
            )
    )
  }

  @Test
  fun warmRemoteResumeReopensOnlyWhenTheServerSessionIsGone() {
    assertFalse(warmResumeNeedsNewServerSession(local = true, serverSessionFound = false))
    assertFalse(warmResumeNeedsNewServerSession(local = false, serverSessionFound = true))
    assertTrue(warmResumeNeedsNewServerSession(local = false, serverSessionFound = false))
  }

  @Test
  fun newerOtherDeviceProgressBeatsTheBookmark() {
    val saved = bookmark(savedAt = 100L, position = 50.0)
    assertEquals(80.0, positionToResume(saved, otherPositionSeconds = 80.0, otherUpdatedAt = 200L), 0.0)
    assertEquals(50.0, positionToResume(saved, otherPositionSeconds = 10.0, otherUpdatedAt = 50L), 0.0)
    assertEquals(50.0, positionToResume(saved, otherPositionSeconds = null, otherUpdatedAt = null), 0.0)
  }

  @Test
  fun mediaButtonWithoutPlaybackReleasesAnEmptyService() {
    assertEquals(
            MediaButtonDelivery.Dispatch,
            mediaButtonDelivery(ResumeAction.ResumeFromBookmark, alreadyForeground = false, LoadedPlayer.Empty)
    )
    assertEquals(
            MediaButtonDelivery.StopEmptyService,
            mediaButtonDelivery(ResumeAction.Ignore, alreadyForeground = false, LoadedPlayer.Empty)
    )
    assertEquals(
            MediaButtonDelivery.Dispatch,
            mediaButtonDelivery(ResumeAction.PauseAndKeep, alreadyForeground = true, LoadedPlayer.Playing)
    )
    assertEquals(
            MediaButtonDelivery.KeepPausedNotification,
            mediaButtonDelivery(ResumeAction.PauseAndKeep, alreadyForeground = false, LoadedPlayer.Paused)
    )
  }

  @Test
  fun mediaKeyCodesMapToButtons() {
    assertEquals(TransportButton.Play, transportButton(126))
    assertEquals(TransportButton.Pause, transportButton(127))
    assertEquals(TransportButton.PlayPause, transportButton(85))
    assertEquals(TransportButton.PlayPause, transportButton(79))
    assertEquals(TransportButton.Stop, transportButton(86))
    assertEquals(TransportButton.Other, transportButton(0))
  }

  @Test
  fun mediaButtonAcknowledgesForegroundAndPauseDoesNotDropIt() {
    assertTrue(mustAcknowledgeMediaButtonStart())
    assertFalse(releasesForegroundAfter(MediaButtonDelivery.Dispatch))
    assertFalse(releasesForegroundAfter(MediaButtonDelivery.KeepPausedNotification))
    assertTrue(releasesForegroundAfter(MediaButtonDelivery.StopEmptyService))
  }

  @Test
  fun foregroundNotificationCarriesTheSessionAndActions() {
    val spec = mediaNotificationSpec()
    assertTrue(spec.attachSession)
    assertTrue(spec.includeTransportActions)
  }

  @Test
  fun endedEpisodeReplaysInsteadOfThePreviousBookmark() {
    assertEquals(
            ResumeAction.PlayLoaded,
            resumeAction(TransportButton.Play, LoadedPlayer.Ended, hasBookmark = true)
    )
    assertEquals(
            ResumeAction.PlayLoaded,
            resumeAction(TransportButton.PlayPause, LoadedPlayer.Ended, hasBookmark = true)
    )
    assertTrue(shouldPlayLoadedItemInsteadOfBookmark(LoadedPlayer.Ended, bookmarkMatchesLoaded = false))
    assertTrue(playRestartsFromBeginning(LoadedPlayer.Ended))
    assertFalse(playRestartsFromBeginning(LoadedPlayer.Paused))
    assertTrue(shouldReplayFromStart(LoadedPlayer.Paused, positionMs = 59_000L, durationMs = 60_000L))
    assertFalse(shouldReplayFromStart(LoadedPlayer.Paused, positionMs = 10_000L, durationMs = 60_000L))
  }

  @Test
  fun pausedPodcastDoesNotSwitchToADownloadedBook() {
    val book = bookmark(savedAt = 100L, position = 50.0)
    val podcast = LoadedIdentity(
            serverLibraryItemId = "pod-1",
            serverEpisodeId = "ep-1",
            localLibraryItemId = null,
            localEpisodeId = null
    )
    assertFalse(bookmarkMatchesLoadedItem(book, podcast))
    assertTrue(shouldPlayLoadedItemInsteadOfBookmark(LoadedPlayer.Paused, bookmarkMatchesLoaded = false))
    assertTrue(shouldPlayLoadedItemInsteadOfBookmark(LoadedPlayer.Ended, bookmarkMatchesLoaded = false))
  }

  @Test
  fun pausedBookStillSwitchesToItsOwnDownload() {
    val book = bookmark(savedAt = 100L, position = 50.0)
    val loaded = LoadedIdentity(
            serverLibraryItemId = "lib-1",
            serverEpisodeId = null,
            localLibraryItemId = null,
            localEpisodeId = null
    )
    assertTrue(bookmarkMatchesLoadedItem(book, loaded))
    assertFalse(shouldPlayLoadedItemInsteadOfBookmark(LoadedPlayer.Paused, bookmarkMatchesLoaded = true))
    assertFalse(shouldPlayLoadedItemInsteadOfBookmark(LoadedPlayer.Empty, bookmarkMatchesLoaded = false))
  }

  @Test
  fun endedPlaybackWritesThatItemAndKeepsControls() {
    assertTrue(shouldWriteResumeBookmark(PlaybackSnapshot.Ended))
    assertTrue(shouldWriteResumeBookmark(PlaybackSnapshot.ReadyPaused))
    assertTrue(shouldWriteResumeBookmark(PlaybackSnapshot.Playing))
    assertFalse(shouldWriteResumeBookmark(PlaybackSnapshot.Other))
    assertTrue(keepTransportControlsAfterEnd(PlaybackEndAction.Stop))
    assertFalse(keepTransportControlsAfterEnd(PlaybackEndAction.StartNext))
    assertFalse(keepTransportControlsAfterEnd(PlaybackEndAction.PodcastAutoAdvance))
    assertEquals(59_000L, pausedHoldPositionMs(60_000L))
    assertNull(pausedHoldPositionMs(1_000L))
  }

  @Test
  fun closedWidgetKeepsPlayOnlyWhileABookmarkExists() {
    val closedWithBookmark = widgetControls(serviceClosed = true, hasBookmark = true)
    assertTrue(closedWithBookmark.play)
    assertFalse(closedWithBookmark.seek)
    val closedWithoutBookmark = widgetControls(serviceClosed = true, hasBookmark = false)
    assertFalse(closedWithoutBookmark.play)
    assertFalse(closedWithoutBookmark.seek)
    val open = widgetControls(serviceClosed = false, hasBookmark = false)
    assertTrue(open.play)
    assertTrue(open.seek)
  }

  @Test
  fun systemUiRecentRootRequiresABookmarkAndAutoKeepsItsRoot() {
    assertEquals(
            BrowserDecision.RecentRoot,
            browserRootFor("com.android.systemui", recentHint = true, hasBookmark = true, isAutoClient = false)
    )
    assertEquals(
            BrowserDecision.Reject,
            browserRootFor("com.android.systemui", recentHint = true, hasBookmark = false, isAutoClient = false)
    )
    assertEquals(
            BrowserDecision.Reject,
            browserRootFor("com.android.systemui", recentHint = false, hasBookmark = true, isAutoClient = false)
    )
    assertEquals(
            BrowserDecision.AutoRoot,
            browserRootFor("com.google.android.projection.gearhead", recentHint = true, hasBookmark = true, isAutoClient = true)
    )
    assertEquals(
            BrowserDecision.Reject,
            browserRootFor("com.example.other", recentHint = true, hasBookmark = true, isAutoClient = false)
    )
  }

  @Test
  fun resumeBookmarkIdAndThePlayButtonResumeTheBookmark() {
    assertEquals(PlayRequest.ResumeBookmark, playRequest(RESUME_MEDIA_ID, hasBookmark = true, playerEmpty = true))
    assertEquals(PlayRequest.ResumeBookmark, playRequest(RESUME_MEDIA_ID, hasBookmark = true, playerEmpty = false))
    assertEquals(PlayRequest.ResumeBookmark, playRequest(null, hasBookmark = true, playerEmpty = true))
    assertEquals(PlayRequest.Browse, playRequest("library-item", hasBookmark = true, playerEmpty = true))
    assertEquals(PlayRequest.Browse, playRequest(RESUME_MEDIA_ID, hasBookmark = false, playerEmpty = true))
    assertEquals(PlayRequest.Browse, playRequest(null, hasBookmark = true, playerEmpty = false))
  }

  @Test
  fun coldPlayUsesTheBookmarkServerNotTheActiveOne() {
    assertEquals(
            "srv-b",
            connectionConfigIdForResume("srv-b", listOf("srv-a", "srv-b"), activeId = "srv-a")
    )
    assertNull(connectionConfigIdForResume("missing", listOf("srv-a"), activeId = "srv-a"))
    assertEquals("srv-a", connectionConfigIdForResume(null, listOf("srv-a"), activeId = null))
  }

  @Test
  fun widgetPlayDownResumesOnlyWhenThePlayerIsEmpty() {
    assertTrue(coldWidgetPlay(keyAction = 0, keyCode = 85, playerEmpty = true, hasBookmark = true))
    assertFalse(coldWidgetPlay(keyAction = 1, keyCode = 85, playerEmpty = true, hasBookmark = true))
    assertFalse(coldWidgetPlay(keyAction = 0, keyCode = 85, playerEmpty = false, hasBookmark = true))
    assertFalse(coldWidgetPlay(keyAction = 0, keyCode = 79, playerEmpty = true, hasBookmark = true))
    assertFalse(coldWidgetPlay(keyAction = 0, keyCode = 85, playerEmpty = true, hasBookmark = false))
  }

  @Test
  fun pausedOrIdleWidgetPlayStartsTheProcessAndPlayingStaysAMediaButton() {
    // A pause paints the widget while the service is still alive. A later OEM kill
    // never gets onDestroy, so that paint must already be able to start the process.
    assertEquals(WidgetPlayDelivery.ForegroundService, widgetPlayDelivery(playing = false, sdkInt = 31))
    assertEquals(WidgetPlayDelivery.ForegroundService, widgetPlayDelivery(playing = false, sdkInt = 26))
    assertEquals(WidgetPlayDelivery.Activity, widgetPlayDelivery(playing = false, sdkInt = 25))
    assertEquals(WidgetPlayDelivery.MediaButton, widgetPlayDelivery(playing = true, sdkInt = 36))
    assertEquals(WidgetPlayDelivery.MediaButton, widgetPlayDelivery(playing = true, sdkInt = 24))
    assertEquals(WidgetPlayAction.ResumeService, widgetPlayAction(playing = false, sdkInt = 31))
    assertEquals(WidgetPlayAction.OpenAppAndResume, widgetPlayAction(playing = false, sdkInt = 24))
    assertEquals(WidgetPlayAction.MediaButton, widgetPlayAction(playing = true, sdkInt = 31))
  }

  @Test
  fun audioFocusPauseRepaintsTheWidgetTheSameWayAnInAppPauseDoes() {
    // ExoPlayer reason 2 is audio-focus loss. Reason 1 plus an in-app origin is the JS pause.
    // Neither is a buffering seek, so both must repaint the paused widget immediately.
    assertEquals("audio-focus-loss", pauseReasonLabel(2, PAUSE_IN_APP))
    assertEquals("in-app", pauseReasonLabel(1, PAUSE_IN_APP))
    assertTrue(shouldRepaintWidgetForPause(playWhenReady = false))
    assertFalse(shouldRepaintWidgetForPause(playWhenReady = true))
  }

  @Test
  fun stoppedProcessRestoresTheWidgetAndARunningServiceDoesNot() {
    assertTrue(shouldRestoreWidgetFromBookmark(serviceStarted = false))
    assertFalse(shouldRestoreWidgetFromBookmark(serviceStarted = true))
  }

  @Test
  fun playingAtZeroDoesNotReplaceAnExistingBookmark() {
    assertFalse(shouldReplaceResumeBookmark(PlaybackSnapshot.Playing, 0.0, hasBookmark = true))
    assertTrue(shouldReplaceResumeBookmark(PlaybackSnapshot.Playing, 12.0, hasBookmark = true))
    assertTrue(shouldReplaceResumeBookmark(PlaybackSnapshot.Playing, 0.0, hasBookmark = false))
    assertTrue(shouldReplaceResumeBookmark(PlaybackSnapshot.ReadyPaused, 0.0, hasBookmark = true))
  }

  @Test
  fun widgetBodyAlwaysOpensTheAppAndPausedPlayStartsTheService() {
    for (playing in listOf(true, false)) {
      for (sdk in listOf(24, 31)) {
        assertEquals(WidgetTap.OpenApp, widgetTap(WidgetRegion.Body, playing, sdk))
        assertEquals(WidgetTap.MediaButton, widgetTap(WidgetRegion.Seek, playing, sdk))
      }
    }
    assertEquals(WidgetTap.ResumeService, widgetTap(WidgetRegion.Play, playing = false, sdkInt = 31))
    assertEquals(WidgetTap.OpenAppAndResume, widgetTap(WidgetRegion.Play, playing = false, sdkInt = 24))
    assertEquals(WidgetTap.MediaButton, widgetTap(WidgetRegion.Play, playing = true, sdkInt = 31))
    assertEquals(RESUME_FROM_WIDGET_ACTION, widgetResumeIntentAction())
    assertTrue(WIDGET_SERVICE_RESUME_REQUEST_CODE != WIDGET_ACTIVITY_RESUME_REQUEST_CODE)
    assertTrue(WIDGET_SERVICE_RESUME_REQUEST_CODE != WIDGET_OPEN_APP_REQUEST_CODE)
  }

  @Test
  fun widgetResumeActionIsRecognized() {
    assertTrue(shouldResumeFromWidget(RESUME_FROM_WIDGET_ACTION))
    assertFalse(shouldResumeFromWidget(null))
    assertFalse(shouldResumeFromWidget("android.intent.action.MAIN"))
  }
}
