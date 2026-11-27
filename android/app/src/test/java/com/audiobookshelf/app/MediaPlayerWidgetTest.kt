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

  @Test
  fun coverCropKeepsTheTopOfATallCoverTheCenterOfAWideCoverAndAnExactFit() {
    // A portrait cover in a shorter slot keeps the top rows and drops the bottom.
    assertEquals(WidgetCoverCrop(0, 0, 1200, 1500), widgetCoverCrop(1200, 1800, 200, 250))
    // A wide cover fills the height and crops the sides evenly. The top row stays.
    assertEquals(WidgetCoverCrop(420, 0, 960, 1200), widgetCoverCrop(1800, 1200, 200, 250))
    // Matching aspect uses the whole image.
    assertEquals(WidgetCoverCrop(0, 0, 400, 500), widgetCoverCrop(400, 500, 200, 250))
  }
}
