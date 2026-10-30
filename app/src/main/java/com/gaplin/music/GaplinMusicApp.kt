package com.gaplin.music

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class GaplinMusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
        }.onFailure {
            Log.e("GaplinMusic", "Não foi possível inicializar o mecanismo de downloads", it)
        }
    }
}
