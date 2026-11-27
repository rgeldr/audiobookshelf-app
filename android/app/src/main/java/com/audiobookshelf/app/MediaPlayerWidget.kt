package com.audiobookshelf.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.media.session.MediaButtonReceiver
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.player.WIDGET_ACTIVITY_RESUME_REQUEST_CODE
import com.audiobookshelf.app.player.WIDGET_OPEN_APP_REQUEST_CODE
import com.audiobookshelf.app.player.WIDGET_SERVICE_RESUME_REQUEST_CODE
import com.audiobookshelf.app.player.WIDGET_MEDIA_FAST_FORWARD
import com.audiobookshelf.app.player.WIDGET_MEDIA_PLAY_PAUSE
import com.audiobookshelf.app.player.WIDGET_MEDIA_REWIND
import com.audiobookshelf.app.player.WidgetControl
import com.audiobookshelf.app.player.WidgetTap
import com.audiobookshelf.app.player.shouldRestoreWidgetFromBookmark
import com.audiobookshelf.app.player.widgetControlIntent
import com.audiobookshelf.app.player.widgetResumeIntentAction
import com.audiobookshelf.app.player.widgetControls
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.AppWidgetTarget
import com.bumptech.glide.request.transition.Transition

/**
 * Implementation of App Widget functionality.
 */
class MediaPlayerWidget : AppWidgetProvider() {
  val tag = "MediaPlayerWidget"
  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    Log.d(tag, "onUpdate $appWidgetIds")
    restoreWidgetFromBookmark(context, appWidgetManager, appWidgetIds)
  }

  override fun onEnabled(context: Context) {
    Log.i(tag, "onEnabled check context ${context.packageName}")

    DbManager.initialize(context)

    DeviceManager.deviceData.lastPlaybackSession?.let {
      val appWidgetManager = AppWidgetManager.getInstance(context)
      val componentName = ComponentName(context, MediaPlayerWidget::class.java)
      val ids = appWidgetManager.getAppWidgetIds(componentName)
      Log.d(tag, "Setting initial widget state with last playback session ${it.displayTitle}")
      for (widgetId in ids) {
        updateAppWidget(context, appWidgetManager, widgetId, it, false, true)
      }
    }

    // Enter relevant functionality for when the first widget is created
    DeviceManager.initializeWidgetUpdater(context)
  }
}

internal fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, playbackSession: PlaybackSession?, isPlaying:Boolean, isAppClosed:Boolean) {
  val tag = "MediaPlayerWidget"
  val views = RemoteViews(context.packageName, R.layout.media_player_widget)
  Log.i(tag, "updateAppWidget ${playbackSession?.displayTitle ?: "No Title"} isPlaying=$isPlaying isAppClosed=$isAppClosed")
  val hasBookmark = try {
    DeviceManager.dbManager.getResumeBookmark() != null
  } catch (_: Throwable) {
    false
  }
  val sdkInt = android.os.Build.VERSION.SDK_INT
  val openApp = widgetActivityIntent(context, resume = false)
  if (widgetControlIntent(WidgetControl.Cover, isPlaying, sdkInt).tap == WidgetTap.OpenApp) {
    for (bodyId in WIDGET_BODY_IDS) {
      views.setOnClickPendingIntent(bodyId, openApp)
    }
  }
  val play = widgetControlIntent(WidgetControl.Play, isPlaying, sdkInt)
  val rewind = widgetControlIntent(WidgetControl.Rewind, isPlaying, sdkInt)
  val fastForward = widgetControlIntent(WidgetControl.FastForward, isPlaying, sdkInt)
  views.setOnClickPendingIntent(
          R.id.widgetPlayPauseButton,
          widgetPendingIntent(context, play.tap, play.mediaAction ?: WIDGET_MEDIA_PLAY_PAUSE)
  )
  views.setOnClickPendingIntent(
          R.id.widgetRewindButton,
          widgetPendingIntent(context, rewind.tap, rewind.mediaAction ?: WIDGET_MEDIA_REWIND)
  )
  views.setOnClickPendingIntent(
          R.id.widgetFastForwardButton,
          widgetPendingIntent(context, fastForward.tap, fastForward.mediaAction ?: WIDGET_MEDIA_FAST_FORWARD)
  )

  val controls = widgetControls(isAppClosed, hasBookmark)
  views.setViewVisibility(R.id.widgetButtonContainer, if (controls.play) View.VISIBLE else View.GONE)
  views.setViewVisibility(R.id.widgetRewindButton, if (controls.seek) View.VISIBLE else View.GONE)
  views.setViewVisibility(R.id.widgetFastForwardButton, if (controls.seek) View.VISIBLE else View.GONE)

  val imageUri = playbackSession?.getCoverUri(context) ?: Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon)
  val awt: AppWidgetTarget = object : AppWidgetTarget(context.applicationContext, R.id.widgetAlbumArt, views, appWidgetId) {
    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
      super.onResourceReady(resource, transition)
    }
  }

  val title = playbackSession?.displayTitle ?: "Unknown"
  views.setTextViewText(R.id.widgetMediaTitle, title)

  val options = RequestOptions().override(300, 300).placeholder(R.drawable.icon).error(R.drawable.icon)
  Glide.with(context.applicationContext).asBitmap().load(imageUri).apply(options).into(awt)

  Log.i(tag, "Update App Widget | Is Playing=$isPlaying | isAppClosed=$isAppClosed")

  val playPauseResource = if (isPlaying) androidx.mediarouter.R.drawable.ic_media_pause_dark else androidx.mediarouter.R.drawable.ic_media_play_dark
  views.setImageViewResource(R.id.widgetPlayPauseButton, playPauseResource)

  // Instruct the widget manager to update the widget
  appWidgetManager.updateAppWidget(appWidgetId, views)
}

internal fun restoreWidgetFromBookmark(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
  if (!shouldRestoreWidgetFromBookmark(PlayerNotificationService.isStarted)) return
  DbManager.initialize(context)
  val session = try {
    DeviceManager.deviceData.lastPlaybackSession
  } catch (_: Throwable) {
    null
  }
  for (widgetId in appWidgetIds) {
    updateAppWidget(context, appWidgetManager, widgetId, session, false, true)
  }
}

internal fun widgetBodyViewIds(): IntArray = WIDGET_BODY_IDS

private val WIDGET_BODY_IDS = intArrayOf(
        R.id.widgetBackground,
        R.id.widgetContent,
        R.id.widgetAlbumArt,
        R.id.widgetDetails,
        R.id.widgetMediaTitle,
        R.id.widgetButtonContainer,
        R.id.widgetCorner,
        R.id.tinyCornerIcon
)

private fun widgetActivityIntent(context: Context, resume: Boolean): PendingIntent {
  val open = Intent(context, MainActivity::class.java)
  open.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
  if (resume) open.action = widgetResumeIntentAction()
  return PendingIntent.getActivity(
          context,
          if (resume) WIDGET_ACTIVITY_RESUME_REQUEST_CODE else WIDGET_OPEN_APP_REQUEST_CODE,
          open,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  )
}

private fun widgetForegroundServiceIntent(context: Context): PendingIntent {
  if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
    return widgetActivityIntent(context, resume = true)
  }
  val resume = Intent(context, PlayerNotificationService::class.java)
  resume.action = widgetResumeIntentAction()
  return PendingIntent.getForegroundService(
          context,
          WIDGET_SERVICE_RESUME_REQUEST_CODE,
          resume,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  )
}

private fun widgetPendingIntent(context: Context, tap: WidgetTap, mediaAction: Long): PendingIntent {
  return when (tap) {
    WidgetTap.OpenApp -> widgetActivityIntent(context, resume = false)
    WidgetTap.OpenAppAndResume -> widgetActivityIntent(context, resume = true)
    WidgetTap.ResumeService -> widgetForegroundServiceIntent(context)
    WidgetTap.MediaButton -> MediaButtonReceiver.buildMediaButtonPendingIntent(context, mediaAction)
  }
}
