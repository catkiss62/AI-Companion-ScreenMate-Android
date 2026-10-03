package com.catkiss.screenmate

import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class VideoIntegrationTest {
    @Test fun realProjectionProducesIndependentMovingFiveSecondClipsAndResumes() = runBlocking {
        val app=ApplicationProvider.getApplicationContext<MateApp>()
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldReply=app.config.replyEveryVideo; val oldAudio=app.config.capturePlaybackAudio
        val oldDeep=app.config.secret("deep")
        val clips=mutableListOf<VideoClip>(); val replies=mutableListOf<String>()
        var session=0L
        var audioPhase=false
        var scenario: ActivityScenario<CaptureHarnessActivity>?=null
        val fake=object: ModelGateway {
            override suspend fun observe(base64: String,mode: String): Observation=error("Video mode must not send screenshots")
            override suspend fun observeVideo(clip: VideoClip,prior: String): VideoEvidence {
                withContext(Dispatchers.IO) {
                    assertTrue(clip.file.length()>5000)
                    assertTrue("duration=${clip.end-clip.start}",clip.end-clip.start in 4900..6500)
                    val extractor=MediaExtractor()
                    try {
                        extractor.setDataSource(clip.file.absolutePath)
                        val videoTrack=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
                        val hasAudio=(0 until extractor.trackCount).any { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
                        assertEquals(audioPhase,hasAudio); assertEquals(audioPhase,clip.audio)
                        extractor.selectTrack(videoTrack)
                        assertTrue(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0)
                        var frames=0; while(extractor.sampleTime>=0) { frames++; extractor.advance() }
                        assertTrue("frames=$frames",frames>=20)
                    } finally { extractor.release() }
                    val retriever=MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(clip.file.absolutePath)
                        val colors=(0..4).map { second ->
                            val frame=retriever.getFrameAtTime(second*1_000_000L+200_000,MediaMetadataRetriever.OPTION_CLOSEST)!!
                            val color=frame.getPixel(frame.width/3,frame.height/3); frame.recycle(); color
                        }
                        assertTrue("The video must contain changing content: $colors",colors.toSet().size>1)
                    } finally { retriever.release() }
                }
                if(clips.isEmpty() || audioPhase) withContext(Dispatchers.IO) {
                    val values=android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Video.Media.DISPLAY_NAME,if(audioPhase) "qa-video-with-audio.mp4" else "qa-video-first.mp4")
                        put(android.provider.MediaStore.Video.Media.MIME_TYPE,"video/mp4")
                        put(android.provider.MediaStore.Video.Media.RELATIVE_PATH,"Movies/ScreenMateQA")
                    }
                    val uri=app.contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)!!
                    app.contentResolver.openOutputStream(uri)!!.use { out -> clip.file.inputStream().use { it.copyTo(out) } }
                }
                clips.add(clip)
                return VideoEvidence("${clip.label()} 里人物找到钥匙",1800)
            }
            override suspend fun integrateVideo(previous: String,evidence: String)="$previous\n$evidence"
            override suspend fun reply(context: String,proactive: Boolean,fallback: Boolean): String {
                assertTrue(context.contains("video_test")); replies.add(context); return "刚才她拿到了钥匙。"
            }
        }
        try {
            device.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
            withContext(Dispatchers.Main) {
                app.gateway=fake; app.config.replyEveryVideo=true; app.config.capturePlaybackAudio=false
                app.config.setSecret("deep",""); MainActivity.visible=false
            }
            scenario=ActivityScenario.launch(Intent(app,CaptureHarnessActivity::class.java).putExtra("video",true))
            val start=device.wait(Until.findObject(By.res("android:id/button1")),15000)
                ?: device.findObject(By.text(Pattern.compile("Start|Start now|Start sharing|Start recording|Share|Share screen|Continue",Pattern.CASE_INSENSITIVE)))
            assertNotNull(start); start!!.click()
            withTimeout(40000) { while(withContext(Dispatchers.Main) { VideoDiagnostics.completed<2 }) delay(200) }
            withContext(Dispatchers.Main) {
                val e=CaptureService.instance!!.engine!!; session=e.sessionId
                assertTrue(e.videoMode); assertTrue(clips.size>=2); assertTrue(replies.size>=2)
                assertEquals(clips[0].end,clips[1].start)
                assertTrue(VideoDiagnostics.sent!!.file.exists()); assertTrue(VideoDiagnostics.latest!!.file.exists())
                assertTrue(app.store.recent(session).any { it.kind=="assistant" && it.body.contains("视频 #") })
                e.pause()
            }
            delay(700)
            val before=withContext(Dispatchers.Main) { VideoDiagnostics.completed }
            delay(5500)
            device.executeShellCommand("pm grant ${app.packageName} android.permission.RECORD_AUDIO")
            withContext(Dispatchers.Main) {
                assertEquals(before,VideoDiagnostics.completed)
                audioPhase=true; app.config.capturePlaybackAudio=true
                CaptureService.instance!!.engine!!.pause()
            }
            withTimeout(25000) { while(withContext(Dispatchers.Main) { VideoDiagnostics.completed<=before }) delay(200) }
            withContext(Dispatchers.Main) { CaptureService.instance!!.end() }
            withTimeout(5000) { while(CaptureService.instance!=null) delay(100) }
        } finally {
            withContext(Dispatchers.Main) { CaptureService.instance?.end(); app.gateway=null; app.config.replyEveryVideo=oldReply; app.config.capturePlaybackAudio=oldAudio; app.config.setSecret("deep",oldDeep) }
            if(session!=0L) app.deleteSession(session)
            scenario?.close()
        }
    }
}
