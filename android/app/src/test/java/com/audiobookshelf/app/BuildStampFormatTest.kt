package com.audiobookshelf.app

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class BuildStampFormatTest {
  @Test
  fun aCleanShortShaIsKept() {
    assertEquals("3ec5780", formatGitSha("3ec5780", false))
  }

  @Test
  fun aDirtyTreeAddsASuffix() {
    assertEquals("3ec5780-dirty", formatGitSha(" 3ec5780\n", true))
  }

  @Test
  fun aMissingShaStaysUnknownEvenWhenDirty() {
    assertEquals("unknown", formatGitSha(null, true))
    assertEquals("unknown", formatGitSha("  ", false))
    assertEquals("unknown", formatGitSha("unknown", true))
  }

  @Test
  fun buildTimeIsUtcToTheSecond() {
    assertEquals("1970-01-01T00:00:00Z", formatBuildTime(0))
    assertEquals("2026-11-24T18:44:21Z", formatBuildTime(Instant.parse("2026-11-24T18:44:21Z").toEpochMilli()))
  }
}
