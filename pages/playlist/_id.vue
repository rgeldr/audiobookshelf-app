<template>
  <div class="w-full h-full">
    <div class="w-full h-full overflow-y-auto py-6 md:p-8">
      <div class="w-full flex justify-center">
        <covers-playlist-cover :items="playlistItems" :width="180" :height="180" />
      </div>
      <div class="flex-grow px-1 py-6">
        <div class="flex items-center px-3">
          <h1 class="text-xl font-sans">
            {{ playlistName }}
          </h1>
          <div class="flex-grow" />
          <ui-btn v-if="showDownloadButton" :color="playlistDownloadInProgress ? 'warning' : 'primary'" :padding-x="2" small class="flex items-center justify-center mx-1" @click="downloadClick">
            <span class="material-symbols text-2xl" :class="playlistDownloadInProgress || startingDownload ? 'animate-pulse' : ''">{{ playlistDownloadInProgress || startingDownload ? 'downloading' : 'download' }}</span>
          </ui-btn>
          <ui-btn v-if="showPlayButton" color="success" :padding-x="4" :loading="playerIsStartingForThisMedia" small class="flex items-center justify-center mx-1 w-24" @click="playClick">
            <span class="material-symbols text-2xl fill">{{ playerIsPlaying ? 'pause' : 'play_arrow' }}</span>
            <span class="px-1 text-sm">{{ playerIsPlaying ? $strings.ButtonPause : $strings.ButtonPlay }}</span>
          </ui-btn>
        </div>

        <div class="my-8 max-w-2xl px-3">
          <p class="text-base text-fg">{{ description }}</p>
        </div>

        <tables-playlist-items-table :items="playlistItems" :playlist-id="playlist.id" @showMore="showMore" @playFromHere="playFromRow" />
      </div>
    </div>

    <modals-item-more-menu-modal v-model="showMoreMenu" :library-item="selectedLibraryItem" :episode="selectedEpisode" :playlist="playlist" hide-rss-feed-option :processing.sync="processing" />
    <div v-show="processing" class="fixed top-0 left-0 w-screen h-screen flex items-center justify-center bg-black/50 z-50">
      <ui-loading-indicator />
    </div>
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'
import { AbsDownloader } from '@/plugins/capacitor'
import cellularPermissionHelpers from '@/mixins/cellularPermissionHelpers'
import { queueAfter } from '@/utils/playbackQueue'
import { playlistDownloadAction, playlistDownloadPlan, playlistItemKey, showPlaylistDownloadButton, withLocalDownload } from '@/utils/playlistDownload'

export default {
  mixins: [cellularPermissionHelpers],
  async asyncData({ store, params, app, redirect, route }) {
    if (!store.state.user.user) {
      return redirect(`/connect?redirect=${route.path}`)
    }

    const playlist = await app.$nativeHttp.get(`/api/playlists/${params.id}`).catch((error) => {
      console.error('Failed', error)
      return false
    })

    if (!playlist) {
      return redirect('/bookshelf/playlists')
    }

    // Lookup matching local items & episodes and attach to playlist items
    if (playlist.items.length) {
      const localLibraryItems = (await app.$db.getLocalLibraryItems(playlist.items[0].libraryItem.mediaType)) || []
      if (localLibraryItems.length) {
        playlist.items.forEach((playlistItem) => {
          const matchingLocalLibraryItem = localLibraryItems.find((lli) => lli.libraryItemId === playlistItem.libraryItemId)
          if (!matchingLocalLibraryItem) return
          if (playlistItem.episode) {
            const matchingLocalEpisode = matchingLocalLibraryItem.media.episodes?.find((lep) => lep.serverEpisodeId === playlistItem.episodeId)
            if (matchingLocalEpisode) {
              playlistItem.localLibraryItem = matchingLocalLibraryItem
              playlistItem.localEpisode = matchingLocalEpisode
            }
          } else {
            playlistItem.localLibraryItem = matchingLocalLibraryItem
          }
        })
      }
    }

    return {
      playlist
    }
  },
  data() {
    return {
      showMoreMenu: false,
      processing: false,
      selectedLibraryItem: null,
      selectedEpisode: null,
      mediaIdStartingPlayback: null,
      startingDownload: false
    }
  },
  computed: {
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    playlistItems() {
      return this.playlist.items || []
    },
    playlistName() {
      return this.playlist.name || ''
    },
    description() {
      return this.playlist.description || ''
    },
    playableItems() {
      return this.playlistItems.filter((item) => {
        const libraryItem = item.libraryItem
        if (libraryItem.isMissing || libraryItem.isInvalid) return false
        if (item.episode) return item.episode.audioFile
        return libraryItem.media.tracks.length
      })
    },
    playerIsPlaying() {
      return this.$store.state.playerIsPlaying && this.isOpenInPlayer
    },
    isOpenInPlayer() {
      return !!this.playableItems.find((i) => {
        if (i.localLibraryItem && this.$store.getters['getIsMediaStreaming'](i.localLibraryItem.id, i.localEpisode?.id)) return true
        return this.$store.getters['getIsMediaStreaming'](i.libraryItemId, i.episodeId)
      })
    },
    showPlayButton() {
      return this.playableItems.length
    },
    userCanDownload() {
      return this.$store.getters['user/getUserCanDownload']
    },
    downloadInProgressKeys() {
      const keys = []
      this.playlistItems.forEach((item) => {
        const episodeId = item.episodeId || item.episode?.id || null
        if (this.$store.getters['globals/getDownloadItem'](item.libraryItemId, episodeId)) keys.push(playlistItemKey(item))
      })
      return keys
    },
    downloadPlan() {
      return playlistDownloadPlan(this.playlistItems, this.downloadInProgressKeys)
    },
    showDownloadButton() {
      return showPlaylistDownloadButton(this.userCanDownload, this.playlistItems)
    },
    playlistDownloadInProgress() {
      return this.downloadPlan.skippedInProgress > 0
    },
    isIos() {
      return this.$platform === 'ios'
    },
    playerIsStartingPlayback() {
      // Play has been pressed and waiting for native play response
      return this.$store.state.playerIsStartingPlayback
    },
    playerIsStartingForThisMedia() {
      if (!this.mediaIdStartingPlayback) return false
      const mediaId = this.$store.state.playerStartingPlaybackMediaId
      return mediaId === this.mediaIdStartingPlayback
    }
  },
  methods: {
    showMore(playlistItem) {
      this.selectedLibraryItem = playlistItem.libraryItem
      this.selectedEpisode = playlistItem.episode
      this.showMoreMenu = true
    },
    async playClick() {
      if (this.playerIsStartingPlayback) return
      await this.$hapticsImpact()

      if (this.playerIsPlaying) {
        this.$eventBus.$emit('pause-item')
      } else {
        this.playNextItem()
      }
    },
    playPayloadForPlaylistItem(item) {
      if (item.localLibraryItem) {
        return {
          libraryItemId: item.localLibraryItem.id,
          episodeId: item.localEpisode?.id || null,
          serverLibraryItemId: item.libraryItemId,
          serverEpisodeId: item.episodeId || null
        }
      }
      return {
        libraryItemId: item.libraryItemId,
        episodeId: item.episodeId || null
      }
    },
    queueEntriesFromPlaylist(startItem) {
      const startIndex = this.playableItems.indexOf(startItem)
      return queueAfter(
        this.playableItems.map((item) => {
          const payload = this.playPayloadForPlaylistItem(item)
          const progress = this.$store.getters['user/getUserMediaProgress'](item.libraryItemId, item.episodeId)
          return {
            libraryItemId: payload.libraryItemId,
            episodeId: payload.episodeId || null,
            finished: !!progress?.isFinished
          }
        }),
        startIndex
      )
    },
    emitPlaylistPlay(item) {
      const payload = this.playPayloadForPlaylistItem(item)
      payload.queue = this.queueEntriesFromPlaylist(item)
      this.mediaIdStartingPlayback = item.episodeId || item.libraryItemId
      this.$store.commit('setPlayerIsStartingPlayback', this.mediaIdStartingPlayback)
      this.$eventBus.$emit('play-item', payload)
    },
    playNextItem() {
      const nextItem = this.playableItems.find((i) => {
        const prog = this.$store.getters['user/getUserMediaProgress'](i.libraryItemId, i.episodeId)
        return !prog?.isFinished
      })
      if (nextItem) this.emitPlaylistPlay(nextItem)
    },
    playFromRow(item) {
      if (this.playerIsStartingPlayback) return
      const playable = this.playableItems.find((pi) => pi.libraryItemId === item.libraryItemId && (pi.episodeId || null) === (item.episodeId || null))
      if (!playable) return
      this.emitPlaylistPlay(playable)
    },
    playlistUpdated(playlist) {
      if (this.playlist.id !== playlist.id) return
      this.playlist = playlist
    },
    playlistRemoved(playlist) {
      if (this.playlist.id === playlist.id) {
        this.$router.replace('/bookshelf/playlists')
      }
    },
    newLocalLibraryItem(item) {
      this.$set(this.playlist, 'items', withLocalDownload(this.playlist.items, item))
    },
    libraryChanged() {
      // Playlist contents are shown in the context of the selected library, so leave this page
      // when the library changes rather than showing stale items
      this.$router.replace('/bookshelf/playlists')
    },
    async downloadClick() {
      if (this.startingDownload) return
      const hasPermission = await this.checkCellularPermission('download')
      if (!hasPermission) return

      this.startingDownload = true
      setTimeout(() => {
        this.startingDownload = false
      }, 1000)

      await this.$hapticsImpact()
      const plan = this.downloadPlan
      const action = playlistDownloadAction(plan)
      if (action === 'in-progress') {
        alert(this.$strings.MessagePlaylistDownloadsInProgress)
        return
      }
      if (action === 'already-downloaded') {
        alert(this.$strings.MessagePlaylistAlreadyDownloaded)
        return
      }
      if (this.isIos) {
        this.confirmAndStartDownloads(plan, null)
        return
      }
      this.chooseDownloadFolder(plan)
    },
    async chooseDownloadFolder(plan, selectedLocalFolder = null) {
      let localFolder = selectedLocalFolder
      const mediaType = plan.mediaType
      if (!localFolder) {
        const localFolders = (await this.$db.getLocalFolders()) || []
        const foldersWithMediaType = localFolders.filter((folder) => folder.mediaType == mediaType)
        const internalStorageFolder = foldersWithMediaType.find((folder) => folder.id === `internal-${mediaType}`)
        if (!foldersWithMediaType.length) {
          localFolder = {
            id: `internal-${mediaType}`,
            name: this.$strings.LabelInternalAppStorage,
            mediaType
          }
        } else if (foldersWithMediaType.length === 1 && internalStorageFolder) {
          localFolder = internalStorageFolder
        } else {
          this.$store.commit('globals/showSelectLocalFolderModal', {
            mediaType,
            callback: (folder) => {
              this.chooseDownloadFolder(plan, folder)
            }
          })
          return
        }
      }
      this.confirmAndStartDownloads(plan, localFolder)
    },
    async confirmAndStartDownloads(plan, localFolder) {
      let message = this.$getString('MessagePlaylistDownloadConfirmIos', [plan.pending.length, plan.fileCount, this.$bytesPretty(plan.totalBytes)])
      if (!this.isIos) {
        message = this.$getString('MessagePlaylistDownloadConfirm', [plan.pending.length, plan.fileCount, this.$bytesPretty(plan.totalBytes), localFolder.name])
      }
      const { value } = await Dialog.confirm({
        title: this.$strings.HeaderConfirm,
        message
      })
      if (!value) return
      for (let i = 0; i < plan.pending.length; i++) {
        this.startPlaylistItemDownload(localFolder, plan.pending[i])
      }
    },
    async startPlaylistItemDownload(localFolder, pendingItem) {
      const payload = {
        libraryItemId: pendingItem.libraryItemId
      }
      if (pendingItem.episodeId) payload.episodeId = pendingItem.episodeId
      if (localFolder) payload.localFolderId = localFolder.id
      const downloadRes = await AbsDownloader.downloadLibraryItem(payload)
      if (downloadRes && downloadRes.error) {
        const errorMsg = downloadRes.error || 'Unknown error'
        console.error('Download error', errorMsg)
        this.$toast.error(errorMsg)
      }
    }
  },
  mounted() {
    this.$eventBus.$on('library-changed', this.libraryChanged)
    this.$eventBus.$on('new-local-library-item', this.newLocalLibraryItem)
    this.$socket.$on('playlist_updated', this.playlistUpdated)
    this.$socket.$on('playlist_removed', this.playlistRemoved)
  },
  beforeDestroy() {
    this.$eventBus.$off('library-changed', this.libraryChanged)
    this.$eventBus.$off('new-local-library-item', this.newLocalLibraryItem)
    this.$socket.$off('playlist_updated', this.playlistUpdated)
    this.$socket.$off('playlist_removed', this.playlistRemoved)
  }
}
</script>
