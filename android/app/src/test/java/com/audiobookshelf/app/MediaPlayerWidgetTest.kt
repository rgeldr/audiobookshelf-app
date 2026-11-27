package com.audiobookshelf.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaPlayerWidgetTest {
  @Test
  fun bodyTapTargetsTheCoverAndTitleAndNotTheAuthorOrTransportButtons() {
    val ids = widgetBodyViewIds().toList()
    assertEquals(
            listOf(
                    R.id.widgetBackground,
                    R.id.widgetContent,
                    R.id.widgetAlbumArt,
                    R.id.widgetDetails,
                    R.id.widgetMediaTitle,
                    R.id.widgetButtonContainer,
                    R.id.widgetCorner,
                    R.id.tinyCornerIcon
            ),
            ids
    )
    assertFalse(ids.contains(R.id.widgetPlayPauseButton))
    assertFalse(ids.contains(R.id.widgetRewindButton))
    assertFalse(ids.contains(R.id.widgetFastForwardButton))
  }
}
