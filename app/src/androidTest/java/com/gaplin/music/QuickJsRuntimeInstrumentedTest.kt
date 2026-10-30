package com.gaplin.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickJsRuntimeInstrumentedTest {
    @Test
    fun packagedQuickJsAndYtDlpEjsStartOnAndroid() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = YtDlpDownloadManager(context)

        manager.initialize()

        val engine = manager.engineVersion()
        assertTrue("The embedded yt-dlp-ejs package is missing: $engine", engine.contains("yt-dlp-ejs 0.8.0"))
        assertTrue("The packaged QuickJS Android executable did not start: $engine", engine.contains("QuickJS-NG"))
        assertTrue("The packaged FFmpeg executable did not start: $engine", engine.contains("FFmpeg"))
        assertTrue("The yt-dlp-ejs dependency is missing: $engine", engine.contains("yt-dlp-ejs 0.8.0"))
    }
}
