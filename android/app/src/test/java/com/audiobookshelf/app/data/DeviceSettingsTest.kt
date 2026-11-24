package com.audiobookshelf.app.data

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceSettingsTest {
  @Test
  fun continueSeriesIsOffByDefault() {
    assertEquals(false, DeviceSettings.default().continueSeries)
  }

  @Test
  fun savedSettingsWithoutTheFieldStayOff() {
    val saved = """
      {
        "disableAutoRewind": false,
        "enableAltView": true,
        "allowSeekingOnMediaControls": false,
        "jumpBackwardsTime": 10,
        "jumpForwardTime": 10,
        "enableMp3IndexSeeking": false,
        "disableShakeToResetSleepTimer": false,
        "shakeSensitivity": "MEDIUM",
        "lockOrientation": "NONE",
        "hapticFeedback": "LIGHT",
        "autoSleepTimer": false,
        "autoSleepTimerStartTime": "22:00",
        "autoSleepTimerEndTime": "06:00",
        "autoSleepTimerAutoRewind": false,
        "autoSleepTimerAutoRewindTime": 300000,
        "sleepTimerLength": 900000,
        "disableSleepTimerFadeOut": false,
        "disableSleepTimerResetFeedback": false,
        "enableSleepTimerAlmostDoneChime": false,
        "languageCode": "en-us",
        "downloadUsingCellular": "ALWAYS",
        "streamingUsingCellular": "ALWAYS",
        "androidAutoBrowseLimitForGrouping": 100,
        "androidAutoBrowseSeriesSequenceOrder": "ASC"
      }
    """.trimIndent()

    val settings = jacksonObjectMapper().readValue<DeviceSettings>(saved)
    assertEquals(false, settings.continueSeries)
  }
}
