package com.catkiss.screenmate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StoryPipelineTest {
    private val app get()=ApplicationProvider.getApplicationContext<MateApp>()
    @Test fun bundledRecognizerReadsChineseWithoutCloudKeys() {
        val bitmap=Bitmap.createBitmap(1400,400,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=64f }
        canvas.drawText("局长：我们一起回去吧。",30f,120f,paint)
        val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result=Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),30,TimeUnit.SECONDS)
            assertTrue("OCR result: ${result.text}",result.text.contains("局长"))
            assertTrue(result.text.contains("一起"))
        } finally { recognizer.close(); bitmap.recycle() }
    }
    @Test fun dialogueContinuesThroughSlowCloudAndReplyAndDropsLatePauseResult() = runBlocking {
        val id=app.store.create("story pipeline test","无期迷途 · 剧情")
        val visionStarted=CompletableDeferred<Unit>()
        val releaseVision=CompletableDeferred<Unit>()
        val replyStarted=CompletableDeferred<Unit>()
        val releaseReply=CompletableDeferred<Unit>()
        val replyFinished=CompletableDeferred<Unit>()
        val shown=mutableListOf<String>()
        val fake=object: ModelGateway {
            override suspend fun observe(base64: String,mode: String): Observation {
                visionStarted.complete(Unit)
                withContext(NonCancellable) { releaseVision.await() }
                return Observation("旧画面","旧台词","","",true)
            }
            override suspend fun reply(context: String,proactive: Boolean,fallback: Boolean): String {
                replyStarted.complete(Unit)
                releaseReply.await(); replyFinished.complete(Unit)
                return "过时的评论"
            }
        }
        lateinit var e: WatchEngine
        try {
            withContext(Dispatchers.Main) {
                MainActivity.visible=false
                e=WatchEngine(app,id,"无期迷途 · 剧情",{}, { shown.add(it) },fake)
                e.frame("fake","first")
            }
            withTimeout(5000) { visionStarted.await() }
            withContext(Dispatchers.Main) {
                val now=SystemClock.elapsedRealtime(); val epoch=e.captureEpoch
                assertTrue(e.canCapture)
                e.localText("局长：你回来了。",now-800,epoch)
                e.localText("局长：你回来了。",now,epoch)
            }
            withTimeout(5000) { replyStarted.await() }
            withContext(Dispatchers.Main) {
                val now=SystemClock.elapsedRealtime(); val epoch=e.captureEpoch
                e.localText("海拉：先离开这里。",now-800,epoch)
                e.localText("海拉：先离开这里。",now,epoch)
                assertEquals(2,app.store.recent(id).count { it.kind=="dialogue" })
            }
            releaseReply.complete(Unit); withTimeout(5000) { replyFinished.await() }
            withContext(Dispatchers.Main) {
                assertTrue(shown.isEmpty())
                val oldEpoch=e.captureEpoch
                e.pause(); assertFalse(e.canCapture)
                e.localText("暂停后的迟到台词",SystemClock.elapsedRealtime(),oldEpoch)
                e.pause(); assertTrue(e.canCapture)
                e.localText("旧任务再次迟到",SystemClock.elapsedRealtime(),oldEpoch)
            }
            releaseVision.complete(Unit); delay(100)
            withContext(Dispatchers.Main) {
                assertEquals(2,app.store.recent(id).size)
                assertTrue(shown.isEmpty())
            }
        } finally {
            releaseVision.complete(Unit); releaseReply.complete(Unit)
            withContext(Dispatchers.Main) { e.stop() }
            app.deleteSession(id)
        }
    }
}
