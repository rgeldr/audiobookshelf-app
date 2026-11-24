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

// First series listed on the item. A play that already has a queue or a series
// (playlist, collection, series page) is left alone. Podcasts are not books.
export function seriesContinueTarget({ continueSeries, queue, seriesId, mediaType, series, libraryId }) {
  if (!continueSeries) return null
  if (Array.isArray(queue)) return null
  if (seriesId) return null
  if (mediaType === 'podcast') return null
  if (!libraryId) return null
  const first = Array.isArray(series) ? series.find((entry) => entry && entry.id) : null
  if (!first) return null
  return { seriesId: first.id, seriesLibraryId: libraryId }
}

export function startQueue(input = {}) {
  if (Array.isArray(input.queue)) {
    return {
      seriesId: input.seriesId || null,
      seriesLibraryId: input.seriesLibraryId || input.libraryId || null,
      queue: input.queue,
      fromSetting: false
    }
  }

  const existing = input.seriesId
    ? { seriesId: input.seriesId, seriesLibraryId: input.seriesLibraryId || input.libraryId || null }
    : null
  const attached = existing ? null : seriesContinueTarget(input)
  const chosen = existing || attached
  if (!chosen || !Array.isArray(input.seriesItems)) {
    return { seriesId: chosen?.seriesId || null, seriesLibraryId: chosen?.seriesLibraryId || null, queue: [], fromSetting: !!attached }
  }

  const serverId = String(input.startedId || '').replace(/^local_/, '')
  const startIndex = input.seriesItems.findIndex((item) => {
    const id = item.libraryItemId || item.id
    return id === input.startedId || id === serverId || id === `local_${serverId}`
  })
  return {
    seriesId: chosen.seriesId,
    seriesLibraryId: chosen.seriesLibraryId,
    queue: queueAfter(input.seriesItems, startIndex),
    fromSetting: !!attached
  }
}
