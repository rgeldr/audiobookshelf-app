export function playRequestAction(alreadyStreaming, queue) {
  if (!alreadyStreaming) return 'prepare'
  if (Array.isArray(queue)) return 'install-queue'
  return 'resume'
}

export function queueAfter(items, startIndex) {
  if (!Array.isArray(items) || startIndex < 0 || startIndex >= items.length) return []
  const queue = []
  for (let i = startIndex + 1; i < items.length; i++) {
    const item = items[i]
    if (item.finished) continue
    queue.push({
      libraryItemId: item.libraryItemId,
      episodeId: item.episodeId || null
    })
  }
  return queue
}
