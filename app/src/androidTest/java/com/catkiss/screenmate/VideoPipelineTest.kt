package com.catkiss.screenmate

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoPipelineTest {
    private val app get()=ApplicationProvider.getApplicationContext<MateApp>()
    private fun clip(n: Int): VideoClip {
        val now=System.currentTimeMillis()
        return VideoClip(File.createTempFile("test-video-",".mp4",app.cacheDir).apply { writeBytes(byteArrayOf(1,2,3)) },now-5000,now,false,0,640,360,n)
    }
    @Test fun forcedRepliesUseContinuousStoryInOrderDespiteNormalCooldown() = runBlocking {
        val old=app.config.replyEveryVideo; app.config.replyEveryVideo=true
        val id=app.store.create("video test","连续视频")
        val shown=mutableListOf<String>(); val observed=mutableListOf<Int>()
        val done=CompletableDeferred<Unit>()
        val fake=object: ModelGateway {
            override suspend fun observe(base64: String,mode: String): Observation=error("Screenshot path must not run")
            override suspend fun observeVideo(clip: VideoClip,prior: String): VideoEvidence {
                observed.add(clip.sequence)
                if(clip.sequence==2) assertTrue(prior.contains("找到钥匙"))
                return VideoEvidence(if(clip.sequence==1) "找到钥匙" else "打开门",1200)
            }
            override suspend fun integrateVideo(previous: String,evidence: String)="$previous；$evidence"
            override suspend fun reply(context: String,proactive: Boolean,fallback: Boolean): String {
                val j=JSONObject(context)
                assertTrue(j.getBoolean("video_test")); assertTrue(j.getString("continuous_video_story").contains("找到钥匙"))
                return if(j.getString("target_video").contains("#2")) "刚找到的钥匙派上用场了。" else "她找到钥匙了。"
            }
        }
        lateinit var e: WatchEngine
        try {
            withContext(Dispatchers.Main) {
                MainActivity.visible=false; VideoDiagnostics.reset()
                e=WatchEngine(app,id,"连续视频",{}, { shown.add(it); if(shown.size==2) done.complete(Unit) },fake)
                e.videoPipeline!!.offer(clip(1)); e.videoPipeline.offer(clip(2))
            }
            withTimeout(8000) { done.await() }
            withContext(Dispatchers.Main) {
                assertEquals(listOf(1,2),observed)
                assertTrue(shown[0].contains("#1")); assertTrue(shown[1].contains("#2"))
                assertEquals(2,app.store.recent(id).count { it.kind=="video_observation" })
                assertEquals(2,app.store.recent(id).count { it.kind=="assistant" })
            }
        } finally { withContext(Dispatchers.Main) { e.stop() }; app.deleteSession(id); app.config.replyEveryVideo=old }
    }
    @Test fun failedStoryRetryDoesNotObserveOrSaveSameVideoTwice() = runBlocking {
        var observations=0; var merges=0
        val failed=CompletableDeferred<Unit>(); val done=CompletableDeferred<Unit>(); val rows=mutableListOf<String>()
        val fake=object: ModelGateway {
            override suspend fun observe(base64: String,mode: String): Observation=error("unused")
            override suspend fun reply(context: String,proactive: Boolean,fallback: Boolean)="unused"
            override suspend fun observeVideo(clip: VideoClip,prior: String): VideoEvidence { observations++; return VideoEvidence("证据") }
            override suspend fun integrateVideo(previous: String,evidence: String): String { merges++; if(merges==1) throw ApiFailure(503); return "连续前情" }
        }
        lateinit var p: VideoPipeline
        try {
            withContext(Dispatchers.Main) {
                p=VideoPipeline(app,fake,{kind,_,_ -> rows.add(kind)},{ done.complete(Unit) },{ if(it.contains("处理失败")) failed.complete(Unit) })
                p.offer(clip(1))
            }
            withTimeout(5000) { failed.await() }
            withContext(Dispatchers.Main) { assertFalse(p.canRecord); p.retry() }
            withTimeout(5000) { done.await() }
            assertEquals(1,observations); assertEquals(2,merges)
            assertEquals(1,rows.count { it=="video_observation" })
        } finally { withContext(Dispatchers.Main) { p.close() } }
    }
    @Test fun boundedQueueAndPauseRejectLateNonCooperativeResult() = runBlocking {
        val started=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val rows=mutableListOf<String>(); var reactions=0
        val fake=object: ModelGateway {
            override suspend fun observe(base64: String,mode: String): Observation=error("unused")
            override suspend fun reply(context: String,proactive: Boolean,fallback: Boolean)="unused"
            override suspend fun observeVideo(clip: VideoClip,prior: String): VideoEvidence {
                started.complete(Unit); withContext(NonCancellable) { release.await() }; return VideoEvidence("迟到")
            }
            override suspend fun integrateVideo(previous: String,evidence: String)=error("Late evidence cannot be merged")
        }
        lateinit var p: VideoPipeline
        val clips=(1..6).map { clip(it) }
        try {
            withContext(Dispatchers.Main) {
                p=VideoPipeline(app,fake,{kind,_,_ -> rows.add(kind)},{ reactions++ },{})
                clips.forEach { p.offer(it) }; assertFalse(p.canRecord)
            }
            withTimeout(5000) { started.await() }
            withContext(Dispatchers.Main) { p.pause(true); p.pause(false) }
            release.complete(Unit); delay(150)
            withContext(Dispatchers.Main) {
                assertFalse(rows.contains("video_observation")); assertEquals(0,reactions)
                assertTrue(rows.contains("video_gap")); assertTrue(clips.all { !it.file.exists() })
            }
        } finally { release.complete(Unit); withContext(Dispatchers.Main) { p.close() } }
    }
}
