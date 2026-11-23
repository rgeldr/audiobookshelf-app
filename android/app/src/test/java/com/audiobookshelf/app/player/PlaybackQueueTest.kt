package com.audiobookshelf.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueTest {
  private fun entry(id: String, episodeId: String? = null) = PlaybackQueueEntry(id, episodeId)

  @Test
  fun pollReturnsEntriesInOrderThenNull() {
    val queue = PlaybackQueue()
    queue.replace(listOf(entry("a"), entry("b", "ep")))
    assertEquals("a", queue.poll()?.libraryItemId)
    assertEquals("ep", queue.poll()?.episodeId)
    assertNull(queue.poll())
  }

  @Test
  fun replaceDropsThePreviousQueue() {
    val queue = PlaybackQueue()
    queue.replace(listOf(entry("a")))
    queue.replace(listOf(entry("b")))
    assertEquals("b", queue.peek()?.libraryItemId)
    assertEquals(1, queue.size)
  }

  @Test
  fun clearMakesPeekNull() {
    val queue = PlaybackQueue()
    queue.replace(listOf(entry("a")))
    queue.clear()
    assertNull(queue.peek())
  }

  @Test
  fun explicitQueueBeatsAndroidAutoPodcastAdvance() {
    assertEquals(
            PlaybackEndAction.StartNext,
            playbackEndAction(hasQueueItem = true, isPodcastEpisode = true, isAndroidAuto = true)
    )
  }

  @Test
  fun androidAutoPodcastAdvanceRemainsWhenQueueIsEmpty() {
    assertEquals(
            PlaybackEndAction.PodcastAutoAdvance,
            playbackEndAction(hasQueueItem = false, isPodcastEpisode = true, isAndroidAuto = true)
    )
  }

  @Test
  fun booksAndPhonePodcastsStopWhenQueueIsEmpty() {
    assertEquals(
            PlaybackEndAction.Stop,
            playbackEndAction(hasQueueItem = false, isPodcastEpisode = false, isAndroidAuto = true)
    )
    assertEquals(
            PlaybackEndAction.Stop,
            playbackEndAction(hasQueueItem = false, isPodcastEpisode = true, isAndroidAuto = false)
    )
  }

  @Test
  fun meteredRemoteItemIsBlockedAndLocalItemIsNot() {
    assertFalse(shouldStartQueuedItem("server-id", blockMeteredRemote = true))
    assertTrue(shouldStartQueuedItem("local_server-id", blockMeteredRemote = true))
    assertTrue(shouldStartQueuedItem("server-id", blockMeteredRemote = false))
  }
}
