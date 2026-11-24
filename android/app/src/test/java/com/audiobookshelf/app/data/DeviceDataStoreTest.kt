package com.audiobookshelf.app.data

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.serializers.CompatibleFieldSerializer
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Paper configures Kryo with references disabled. A cycle then recurses until the stack overflows.
 * The cover bitmap does that: Bitmap.mColorSpace is a ColorSpace.Rgb whose clamped transfer
 * function is oetf.andThen(this::clamp), so the lambda points back at the color space.
 */
class DeviceDataStoreTest {
  @Test
  fun paperKryoOverflowsWhenAnObjectPointsAtItself() {
    val cycle = CoverColorSpace()
    try {
      paperWrite(cycle)
      fail("expected StackOverflowError")
    } catch (_: StackOverflowError) {
      // Paper leaves Kryo references off, so a field that points at its owner overflows.
    }
  }

  @Test
  fun coverBitmapFieldIsTransientSoKryoSkipsTheColorSpaceCycle() {
    val field = PlaybackSession::class.java.getDeclaredField("resolvedCoverBitmap")
    assertTrue(Modifier.isTransient(field.modifiers))
  }

  @Test
  fun repeatedSavesKeepTheServerLoginAndDropTheNestedItem() {
    val live = playingDeviceData()
    val first = prepareDeviceDataSave(live)
    val second = prepareDeviceDataSave(first.stored)
    val third = prepareDeviceDataSave(second.stored)

    assertEquals("access-token", third.stored.serverConnectionConfigs[0].token)
    assertEquals("cfg-1", third.stored.lastServerConnectionConfigId)
    assertEquals("Storm Front", third.stored.lastPlaybackSession?.displayTitle)
    assertNull(third.stored.lastPlaybackSession?.libraryItem)
    assertNull(third.stored.lastPlaybackSession?.localLibraryItem)
    assertEquals("li-1", live.lastPlaybackSession?.libraryItem?.id)
    assertEquals(DEVICE_DATA_SANITIZE_LOG, first.sanitizeNote)
    assertNull(third.sanitizeNote)
    paperWrite(third.stored)
  }

  @Test
  fun aFailedWriteDoesNotEscape() {
    val failure = writeDeviceDataCatching(prepareDeviceDataSave(playingDeviceData()).stored) {
      throw StackOverflowError("stack size 1036KB")
    }
    assertTrue(failure is StackOverflowError)
  }

  private class CoverColorSpace {
    var clamped: CoverColorSpace? = null

    init {
      clamped = this
    }
  }

  private fun playingDeviceData(): DeviceData {
    val metadata = BookMetadata(
            "Storm Front",
            null,
            null,
            null,
            mutableListOf(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            "Jim Butcher",
            null,
            null,
            "The Dresden Files",
            listOf(SeriesType("series-1", "The Dresden Files", "1"))
    )
    val track = AudioTrack(1, 0.0, 100.0, "Chapter 01", "https://server/track", "audio/mpeg", null, false, null, 1)
    val book = Book(metadata, null, emptyList(), null, emptyList(), mutableListOf(track), null, null, 100.0, 1)
    val libraryItem = LibraryItem(
            "li-1",
            "",
            "lib-1",
            "folder",
            "",
            "",
            0L,
            0L,
            0L,
            0L,
            0L,
            null,
            null,
            false,
            false,
            "book",
            book,
            null,
            null,
            null,
            null,
            null
    )
    val session = PlaybackSession(
            "session-1",
            "user",
            "li-1",
            null,
            "book",
            metadata,
            DeviceInfo("dev", "Google", "Pixel", 34, "0.14.2-beta"),
            emptyList(),
            "Storm Front",
            "Jim Butcher",
            "/cover",
            100.0,
            0,
            1L,
            2L,
            0L,
            book.tracks ?: mutableListOf(),
            10.0,
            libraryItem,
            null,
            null,
            "cfg-1",
            "https://server",
            "exo-player"
    )
    val config = ServerConnectionConfig(
            "cfg-1",
            0,
            "Home",
            "https://server",
            "2.26.0",
            "user",
            "jason",
            "access-token",
            hashMapOf()
    )
    return DeviceData(mutableListOf(config), "cfg-1", DeviceSettings.default(), session)
  }

  private fun paperWrite(value: Any) {
    val kryo = Kryo()
    kryo.setDefaultSerializer(CompatibleFieldSerializer::class.java)
    kryo.references = false
    val output = Output(1024, -1)
    kryo.writeObject(output, value)
  }
}
