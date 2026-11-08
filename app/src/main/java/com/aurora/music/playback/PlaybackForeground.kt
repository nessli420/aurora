package com.aurora.music.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.aurora.music.playback.PlaybackService.Companion.ACTION_ALARM_DISMISS
import com.aurora.music.playback.PlaybackService.Companion.ACTION_PLAY_PAUSE
import com.aurora.music.playback.PlaybackService.Companion.ACTION_RECEIVER_STOP

@UnstableApi
internal class PlaybackForeground(
    private val service: MediaSessionService,
    private val session: () -> MediaSession?,
    private val live: () -> Player,
    private val mediaNotification: (MediaSession, Boolean) -> Unit,
) {
    private val player get() = live()
    var receiverForeground = false
        private set
    private var alarmForeground = false

    fun setReceiverForeground(enabled: Boolean) {
        receiverForeground = enabled
        val notificationManager = service.getSystemService(NotificationManager::class.java)
        if (!enabled) {
            notificationManager.cancel(NETWORK_NOTIFICATION_ID)
            if (!(session()?.player?.isPlaying ?: false)) service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            session()?.let { mediaNotification(it, it.player.isPlaying) }
            return
        }
        notificationManager.createNotificationChannel(NotificationChannel("aurora_receiver", "Aurora receiver", NotificationManager.IMPORTANCE_LOW))
        val open = android.app.PendingIntent.getActivity(service, 91, android.content.Intent(service, com.aurora.music.MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val stop = android.app.PendingIntent.getService(service, 92, android.content.Intent(service, PlaybackService::class.java).setAction(ACTION_RECEIVER_STOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val transport = android.app.PendingIntent.getService(service, 93, android.content.Intent(service, PlaybackService::class.java).setAction(ACTION_PLAY_PAUSE),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val builder = androidx.core.app.NotificationCompat.Builder(service, "aurora_receiver")
            .setSmallIcon(com.aurora.music.R.drawable.ic_aurora_logo).setContentTitle("Aurora receiver")
            .setContentText(if (player.isPlaying) player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Playing" else "Ready for a paired device")
            .setContentIntent(open).setOngoing(true)
            .addAction(0, "Turn off", stop)
        if (player.currentMediaItem != null) {
            builder.setContentTitle(player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Aurora receiver")
                .setContentText(player.currentMediaItem?.mediaMetadata?.artist?.toString().orEmpty())
                .addAction(if (player.playWhenReady) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (player.playWhenReady) "Pause" else "Play", transport)
            session()?.let { builder.setStyle(androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(it).setShowActionsInCompactView(1)) }
        }
        val notification = builder.build()
        if (android.os.Build.VERSION.SDK_INT >= 29) service.startForeground(NETWORK_NOTIFICATION_ID, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                if (player.isPlaying) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        else service.startForeground(NETWORK_NOTIFICATION_ID, notification)
    }

    // startForegroundService needs a notification before the likes arrive; media3 replaces it once playing
    fun holdAlarmForeground() {
        session()?.let { service.addSession(it) }
        alarmForeground = false
        val active = session()?.player ?: player
        // media3 already holds the foreground while playing or buffering
        if (receiverForeground || (active.playWhenReady && active.playbackState != Player.STATE_IDLE && active.playbackState != Player.STATE_ENDED)) return
        val nm = service.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel("aurora_alarm_start", "Alarm loading", NotificationManager.IMPORTANCE_LOW))
        val notification = androidx.core.app.NotificationCompat.Builder(service, "aurora_alarm_start")
            .setSmallIcon(com.aurora.music.R.drawable.ic_launcher_monochrome)
            .setContentTitle("Aurora alarm")
            .build()
        alarmForeground = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 29) service.startForeground(ALARM_START_NOTIF_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else service.startForeground(ALARM_START_NOTIF_ID, notification)
        }.isSuccess
    }

    fun releaseAlarmForeground() {
        if (!alarmForeground) return
        alarmForeground = false
        if (!receiverForeground) service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    fun postAlarmNotification() {
        val nm = service.getSystemService(NotificationManager::class.java) ?: return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("aurora_alarm", "Alarm", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val piFlags = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        val fsIntent = android.content.Intent(service, AlarmActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val fsPending = android.app.PendingIntent.getActivity(service, 1, fsIntent, piFlags)
        val dismissPending = android.app.PendingIntent.getService(
            service, 2, android.content.Intent(service, PlaybackService::class.java).setAction(ACTION_ALARM_DISMISS), piFlags,
        )
        val notif = androidx.core.app.NotificationCompat.Builder(service, "aurora_alarm")
            .setSmallIcon(com.aurora.music.R.drawable.ic_launcher_monochrome)
            .setContentTitle("Aurora alarm")
            .setContentText("Tap to dismiss")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(fsPending)
            .setFullScreenIntent(fsPending, true)
            .addAction(0, "Dismiss", dismissPending)
            .build()
        nm.notify(ALARM_NOTIF_ID, notif)
    }

    fun cancelAlarmNotification() {
        runCatching { service.getSystemService(NotificationManager::class.java)?.cancel(ALARM_NOTIF_ID) }
    }

    companion object {
        private const val NETWORK_NOTIFICATION_ID = 0xA15
        private const val ALARM_NOTIF_ID = 0xA1A
        private const val ALARM_START_NOTIF_ID = 0xA1B
    }
}
