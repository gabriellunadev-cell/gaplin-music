package com.gaplin.music

/**
 * Keys shared by PlaybackService and the UI MediaController.
 * Media3 1.8.x does not reliably forward Player.Listener.onAudioSessionIdChanged
 * through MediaController, so the service publishes the ID as session extras.
 */
object PlaybackSessionKeys {
    const val AUDIO_SESSION_ID = "com.gaplin.music.extra.AUDIO_SESSION_ID"
}
