package com.audiobookshelf.app

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val buildTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

fun formatGitSha(rawSha: String?, dirty: Boolean): String {
  val sha = rawSha?.trim().orEmpty()
  if (sha.isEmpty() || sha == "unknown") return "unknown"
  return if (dirty) "$sha-dirty" else sha
}

fun formatBuildTime(epochMillis: Long): String = buildTimeFormatter.format(Instant.ofEpochMilli(epochMillis))
