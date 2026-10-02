package com.catkiss.screenmate

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class CaptureIntegrationTest {
    @Test fun realProjectionOverlayChatRotationAndStop() = runBlocking {
        val app=ApplicationProvider.getApplicationContext<MateApp>()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val device=UiDevice.getInstance(instrumentation)
        val frames=AtomicInteger(0)
        val seen=CompletableDeferred<Unit>()
        val fake=object: ModelGateway {
            override suspend fun observe(base64: String,mode: String): Observation {
                val bytes=Base64.decode(base64,Base64.NO_WRAP)
                val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                assertNotNull(bitmap); assertTrue(bitmap.width>0); assertTrue(maxOf(bitmap.width,bitmap.height)<=1280); bitmap.recycle()
                frames.incrementAndGet(); seen.complete(Unit)
                return Observation("桌面测试","这是测试画面","","",false)
            }
            override suspend fun reply(context: String,proactive: Boolean,fallback: Boolean)="我在这里陪你。"
        }
        val oldKey=app.config.secret("deep")
        var session=0L
        var scenario: ActivityScenario<CaptureHarnessActivity>?=null
        try {
            device.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
            withContext(Dispatchers.Main) { app.gateway=fake; app.config.setSecret("deep",""); MainActivity.visible=false }
            scenario=ActivityScenario.launch(CaptureHarnessActivity::class.java)
            val start=device.wait(Until.findObject(By.res("android:id/button1")),15000)
                ?: device.findObject(By.text(Pattern.compile("Start|Start now|Start sharing|Start recording|Share|Share screen|Continue",Pattern.CASE_INSENSITIVE)))
            if(start==null) {
                val hierarchy=java.io.ByteArrayOutputStream(); device.dumpWindowHierarchy(hierarchy)
                fail("System screen-sharing consent not found: " + hierarchy.toString("UTF-8"))
            }
            start!!.click()
            withTimeout(20000) { seen.await() }
            withContext(Dispatchers.Main) {
                val service=CaptureService.instance!!; session=service.engine!!.sessionId
                assertFalse(service.engine!!.paused); service.engine!!.pause(); assertFalse(service.engine!!.canCapture)
                service.engine!!.pause()
            }
            device.setOrientationLeft()
            device.pressHome()
            assertTrue(device.wait(Until.hasObject(By.desc("陪看伙伴，点击聊天，自由拖动")),10000))
            val pet=device.findObject(By.desc("陪看伙伴，点击聊天，自由拖动"))
            val targetX=device.displayWidth/2; val targetY=device.displayHeight/3
            pet.drag(android.graphics.Point(targetX,targetY),1000)
            device.waitForIdle()
            val moved=device.findObject(By.desc("陪看伙伴，点击聊天，自由拖动")).visibleBounds
            assertTrue(kotlin.math.abs(moved.centerX()-targetX)<80)
            assertTrue(kotlin.math.abs(moved.centerY()-targetY)<80)
            device.findObject(By.desc("陪看伙伴，点击聊天，自由拖动")).click()
            assertFalse(device.hasObject(By.text("结束")))
            device.findObject(By.text("暂停")).click()
            assertTrue(device.wait(Until.hasObject(By.text("继续")),3000))
            device.findObject(By.text("继续")).click()
            assertTrue(device.wait(Until.hasObject(By.text("暂停")),3000))
            val input=device.wait(Until.findObject(By.clazz("android.widget.EditText")),5000)
            assertNotNull(input); input.click(); input.text="你看到了吗"
            device.findObject(By.text("发送")).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("我在这里陪你")),8000))
            device.findObject(By.text("收起")).click()
            app.startActivity(android.content.Intent(app,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("结束本次陪看")),5000))
            device.findObject(By.text("结束本次陪看")).click()
            withTimeout(10000) { while(CaptureService.instance!=null) delay(100) }
            assertEquals("pending",app.store.session(session)!!.state)
            assertTrue(app.store.recent(session).any { it.kind=="assistant" })
            assertTrue(frames.get()>0)
        } finally {
            withContext(Dispatchers.Main) { CaptureService.instance?.end(); app.gateway=null }
            if(session!=0L) app.deleteSession(session)
            app.config.setSecret("deep",oldKey)
            device.setOrientationNatural(); device.unfreezeRotation(); scenario?.close()
        }
    }
}
