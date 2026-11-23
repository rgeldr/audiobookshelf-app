package com.audiobookshelf.app.player

data class PlaybackQueueEntry(
        val libraryItemId: String,
        val episodeId: String?
)

class PlaybackQueue {
  private val entries = ArrayDeque<PlaybackQueueEntry>()

  val size: Int
    get() = entries.size

  fun replace(next: List<PlaybackQueueEntry>) {
    entries.clear()
    entries.addAll(next)
  }

  fun clear() {
    entries.clear()
  }

  fun peek(): PlaybackQueueEntry? = entries.firstOrNull()

  fun poll(): PlaybackQueueEntry? = if (entries.isEmpty()) null else entries.removeFirst()
}

enum class PlaybackEndAction {
  StartNext,
  PodcastAutoAdvance,
  Stop
}

fun playbackEndAction(
        hasQueueItem: Boolean,
        isPodcastEpisode: Boolean,
        isAndroidAuto: Boolean
): PlaybackEndAction {
  if (hasQueueItem) return PlaybackEndAction.StartNext
  if (isPodcastEpisode && isAndroidAuto) return PlaybackEndAction.PodcastAutoAdvance
  return PlaybackEndAction.Stop
}

fun shouldStartQueuedItem(libraryItemId: String, blockMeteredRemote: Boolean): Boolean {
  if (blockMeteredRemote && !libraryItemId.startsWith("local")) return false
  return true
}
