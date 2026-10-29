package com.gaplin.music.playback

import android.os.Bundle

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.gaplin.music.AudioEqualizerController
import com.gaplin.music.PlaybackSessionKeys
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(
                eventTime: AnalyticsListener.EventTime,
                audioSessionId: Int
            ) {
                AudioEqualizerController.attach(applicationContext, audioSessionId)
                mediaSession?.setSessionExtras(
                    Bundle().apply {
                        putInt(PlaybackSessionKeys.AUDIO_SESSION_ID, audioSessionId)
                    }
                )
            }
        })

        mediaSession = MediaSession.Builder(this, player)
            .setSessionExtras(
                Bundle().apply {
                    putInt(PlaybackSessionKeys.AUDIO_SESSION_ID, 0)
                }
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        AudioEqualizerController.release()
        super.onDestroy()
    }
}
