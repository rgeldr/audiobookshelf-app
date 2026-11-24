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
  fun userPauseDemotesAndKeepsTheNotification() {
    assertEquals(ForegroundChange.DemoteKeepNotification, foregroundChangeFor(PauseStopReason.UserPause))
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
    assertEquals(ForegroundChange.DemoteKeepNotification, foregroundChangeFor(ResumeAction.PauseAndKeep))
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
}
