package com.audiobookshelf.app.player

import android.net.Uri
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.server.TrackAuthAction
import com.audiobookshelf.app.server.refreshedTrackUri
import com.audiobookshelf.app.server.trackActionAfterUnauthorized
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.HttpDataSource

/**
 * A direct-play or HLS request that comes back 401/403 refreshes the access token once and retries.
 * If the refresh does not produce a token, the error is rethrown and the player is left up.
 */
class RefreshingHttpDataSource(
    private val upstream: HttpDataSource,
    private val refreshAccessToken: () -> String?
) : HttpDataSource by upstream {
  override fun open(dataSpec: DataSpec): Long {
    return try {
      upstream.open(dataSpec)
    } catch (error: HttpDataSource.InvalidResponseCodeException) {
      if (error.responseCode != 401 && error.responseCode != 403) throw error
      AbsLogger.error("RefreshingHttpDataSource", "Track request HTTP ${error.responseCode} for ${dataSpec.uri}; refreshing access token")
      try {
        upstream.close()
      } catch (_: Exception) {}
      val token = refreshAccessToken()
      if (trackActionAfterUnauthorized(!token.isNullOrEmpty()) != TrackAuthAction.RetryWithNewToken) {
        AbsLogger.error("RefreshingHttpDataSource", "Token refresh did not produce a token; playback stays up")
        throw error
      }
      val rewritten = refreshedTrackUri(dataSpec.uri.toString(), token) ?: dataSpec.uri.toString()
      upstream.setRequestProperty("Authorization", "Bearer $token")
      AbsLogger.info("RefreshingHttpDataSource", "Retrying track request after token refresh")
      upstream.open(dataSpec.buildUpon().setUri(Uri.parse(rewritten)).build())
    }
  }
}
