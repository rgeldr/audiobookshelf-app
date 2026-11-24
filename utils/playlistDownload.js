export function playlistItemKey(item) {
  const libraryItemId = item?.libraryItemId || item?.libraryItem?.id || ''
  const episodeId = item?.episodeId || item?.episode?.id || null
  return episodeId ? `${libraryItemId}:${episodeId}` : libraryItemId
}

export function isDownloadablePlaylistItem(item) {
  const libraryItem = item?.libraryItem
  if (!libraryItem || libraryItem.isMissing || libraryItem.isInvalid) return false
  if (item.episode || item.episodeId) return !!(item.episode && item.episode.audioFile)
  const media = libraryItem.media || {}
  const tracks = media.tracks || []
  return tracks.length > 0 || !!media.ebookFile
}

export function showPlaylistDownloadButton(userCanDownload, items) {
  if (!userCanDownload) return false
  return (Array.isArray(items) ? items : []).some(isDownloadablePlaylistItem)
}

function asCount(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number : 0
}

export function playlistDownloadPlan(items, inProgressKeys) {
  const list = Array.isArray(items) ? items : []
  const inProgress = inProgressKeys instanceof Set ? inProgressKeys : new Set(inProgressKeys || [])
  const pending = []
  let skippedDownloaded = 0
  let skippedInProgress = 0
  let skippedUnusable = 0
  let fileCount = 0
  let totalBytes = 0
  let mediaType = null

  for (const item of list) {
    if (!isDownloadablePlaylistItem(item)) {
      skippedUnusable++
      continue
    }
    const libraryItem = item.libraryItem
    const episodeId = item.episodeId || item.episode?.id || null
    if (!mediaType) mediaType = libraryItem.mediaType || (episodeId ? 'podcast' : 'book')
    const downloaded = episodeId ? !!item.localEpisode : !!item.localLibraryItem
    if (downloaded) {
      skippedDownloaded++
      continue
    }
    if (inProgress.has(playlistItemKey(item))) {
      skippedInProgress++
      continue
    }
    fileCount += episodeId ? 1 : asCount(libraryItem.numFiles)
    totalBytes += episodeId ? asCount(item.episode?.size ?? item.episode?.audioFile?.metadata?.size) : asCount(libraryItem.size)
    pending.push({
      libraryItemId: item.libraryItemId || libraryItem.id,
      episodeId: episodeId || null,
      mediaType: libraryItem.mediaType || mediaType
    })
  }

  return { pending, skippedDownloaded, skippedInProgress, skippedUnusable, fileCount, totalBytes, mediaType }
}

export function withLocalDownload(items, localLibraryItem) {
  if (!Array.isArray(items) || !localLibraryItem) return items
  return items.map((item) => {
    if (item.libraryItemId !== localLibraryItem.libraryItemId) return item
    const episodeId = item.episodeId || item.episode?.id || null
    if (episodeId) {
      const localEpisode = localLibraryItem.media?.episodes?.find((episode) => episode.serverEpisodeId === episodeId)
      if (!localEpisode) return item
      return { ...item, localLibraryItem, localEpisode }
    }
    return { ...item, localLibraryItem }
  })
}

export function playlistDownloadAction(plan) {
  if (plan.pending.length) return 'confirm'
  if (plan.skippedInProgress > 0) return 'in-progress'
  return 'already-downloaded'
}
