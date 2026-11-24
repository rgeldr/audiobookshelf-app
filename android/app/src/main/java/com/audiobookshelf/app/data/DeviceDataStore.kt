package com.audiobookshelf.app.data

const val DEVICE_DATA_SANITIZE_LOG =
        "DeviceData sanitize: stored playback session without cover bitmap, library item, or local library item"

data class DeviceDataSave(val stored: DeviceData, val sanitizeNote: String?)

/**
 * Paper writes DeviceData with Kryo references turned off. The live playback session holds the
 * resolved cover bitmap, and that bitmap's color space points back at itself, so the write
 * overflows. The copy kept on disk has the title and server login, and not the bitmap or the
 * nested library item.
 */
fun prepareDeviceDataSave(data: DeviceData): DeviceDataSave {
  val session = data.lastPlaybackSession
  val sanitize = session != null && (session.libraryItem != null || session.localLibraryItem != null || session.hasResolvedCoverBitmap())
  val storedSession = session?.clone()?.also {
    it.libraryItem = null
    it.localLibraryItem = null
  }
  val stored = DeviceData(
          data.serverConnectionConfigs,
          data.lastServerConnectionConfigId,
          data.deviceSettings,
          storedSession
  )
  return DeviceDataSave(stored, if (sanitize) DEVICE_DATA_SANITIZE_LOG else null)
}

fun writeDeviceDataCatching(stored: DeviceData, write: (DeviceData) -> Unit): Throwable? {
  return try {
    write(stored)
    null
  } catch (failure: Throwable) {
    failure
  }
}
