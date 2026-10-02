package com.catkiss.screenmate

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val app get()=ApplicationProvider.getApplicationContext<MateApp>()
    @Test fun storeRecoversInterruptedSessionsWithoutRestartingCapture() {
        val db=Store(app,"test-recovery.db")
        try {
            val id=db.create("剧情","无期迷途"); db.add(id,"observation","可见台词"); db.add(id,"user","我喜欢这个角色")
            assertTrue(db.recover().contains(id)); assertEquals("pending",db.session(id)!!.state)
            assertEquals(2,db.recent(id).size)
            assertNull(CaptureService.instance)
        } finally { db.close(); app.deleteDatabase("test-recovery.db") }
    }
    @Test fun deletingSessionCascadesAndLateSummaryCannotResurrectIt() {
        val db=Store(app,"test-delete.db")
        try {
            val id=db.create("movie","video"); val row=db.add(id,"user","hello")
            db.finish(id); db.delete(id); db.summary(id,"late result",row)
            assertNull(db.session(id)); assertTrue(db.entries(id).isEmpty()); assertEquals(-1,db.add(id,"assistant","late"))
        } finally { db.close(); app.deleteDatabase("test-delete.db") }
    }
    @Test fun rollingSummaryCursorAndArchiveAreIdempotent() {
        val db=Store(app,"test-summary.db")
        try {
            val id=db.create("movie","video"); val first=db.add(id,"observation","scene1")
            db.summary(id,"one",first)
            val second=db.add(id,"observation","scene2"); db.finish(id)
            assertFalse(db.markArchivedIfCaughtUp(id)); assertEquals(second,db.entries(id,first).single().id)
            db.summary(id,"two",second); db.summary(id,"stale",first)
            assertEquals("two",db.session(id)!!.summary); assertTrue(db.markArchivedIfCaughtUp(id)); assertFalse(db.markArchivedIfCaughtUp(id))
        } finally { db.close(); app.deleteDatabase("test-summary.db") }
    }
    @Test fun keyStorageUsesKeystoreAndDoesNotKeepPlaintext() {
        val c=Config(app); c.setSecret("test","test-secret-123")
        assertEquals("test-secret-123",c.secret("test"))
        assertFalse(app.getSharedPreferences("secrets",Context.MODE_PRIVATE).getString("test","")!!.contains("test-secret-123"))
        c.setSecret("test",""); assertEquals("",c.secret("test"))
    }
    @Test fun usageCountersPersistAndLanesAreIndependent() {
        val db=Store(app,"test-usage.db")
        try { db.count("vision"); db.count("vision"); db.count("relay"); assertEquals(2,db.usage("vision")); assertEquals(1,db.usage("relay")) }
        finally { db.close(); app.deleteDatabase("test-usage.db") }
    }
    @Test fun launcherRendersAndCanRecreate() {
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use { scenario ->
            scenario.onActivity { assertNotNull(it.findViewById<android.view.View>(android.R.id.content)) }
            scenario.recreate()
        }
    }
    @Test fun stoppingEngineCancelsPendingReplyAndPreservesUserRecord() = runBlocking {
        val id=app.store.create("test","video")
        val began=CompletableDeferred<Unit>(); var cancelled=false; var shown=false
        val fake=object: ModelGateway {
            override suspend fun observe(base64: String,mode: String)=Observation("","","","",false)
            override suspend fun reply(context: String,proactive: Boolean,fallback: Boolean): String {
                began.complete(Unit)
                try { awaitCancellation() } finally { cancelled=true }
            }
        }
        var e: WatchEngine?=null
        withContext(Dispatchers.Main) { e=WatchEngine(app,id,"video",{}, { shown=true },fake); assertTrue(e!!.send("看这里")) }
        withTimeout(5000) { began.await() }
        withContext(Dispatchers.Main) { e!!.stop() }
        withContext(Dispatchers.Main) { assertTrue(cancelled); assertFalse(shown); assertEquals("user",app.store.recent(id).last().kind) }
        app.deleteSession(id)
    }
    @Test fun failedRelayFallsBackOnlyOnceAndStoresOnlyVisibleReply() = runBlocking {
        val id=app.store.create("test fallback","video"); val done=CompletableDeferred<String>(); val calls=mutableListOf<Boolean>()
        val fake=object: ModelGateway {
            override suspend fun observe(base64: String,mode: String)=Observation("","","","",false)
            override suspend fun reply(context: String,proactive: Boolean,fallback: Boolean): String {
                calls.add(fallback); if(!fallback) throw ApiFailure(503); return "剧情里的她还没说清原因。"
            }
        }
        lateinit var e: WatchEngine
        withContext(Dispatchers.Main) { e=WatchEngine(app,id,"video",{}, { done.complete(it) },fake); e.send("她为什么走了") }
        assertEquals("剧情里的她还没说清原因。",withTimeout(5000) { done.await() })
        assertEquals(listOf(false,true),calls); assertEquals(listOf("user","assistant"),app.store.recent(id).map { it.kind })
        withContext(Dispatchers.Main) { e.stop() }; app.deleteSession(id)
    }
}
