package com.catkiss.screenmate

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Base64
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

class CaptureService: Service() {
    companion object {
        var instance: CaptureService? = null; private set
        var status: String = "尚未开始"; private set
        const val STOP = "screenmate.stop"
        const val PAUSE = "screenmate.pause"
    }
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: Overlay? = null
    var engine: WatchEngine? = null; private set
    private var ending = false
    private var width=0
    private var height=0
    private var lastAttempt=0L
    private val callback = object: MediaProjection.Callback() {
        override fun onStop() { end("系统已停止屏幕共享，会话已保存") }
        override fun onCapturedContentResize(w: Int,h: Int) { if(w>0 && h>0 && !ending) resize(w,h) }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) { engine?.visible=isVisible }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() { super.onCreate(); instance=this }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action == STOP) { end("已结束，记忆正在后台整理"); return START_NOT_STICKY }
        if(intent?.action == PAUSE) { engine?.pause(); return START_NOT_STICKY }
        if(engine != null) return START_NOT_STICKY
        if(intent == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("watch","陪看状态",NotificationManager.IMPORTANCE_LOW))
            val notification = notification("准备屏幕共享")
            if(Build.VERSION.SDK_INT >= 29) startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1,notification)
            val consent = if(Build.VERSION.SDK_INT>=33) intent.getParcelableExtra("consent",Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("consent")
            require(consent != null)
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,consent)
            projection!!.registerCallback(callback,handler)
            val app = application as MateApp
            val mode = intent.getStringExtra("mode") ?: "视频陪看"
            val id = app.store.create(intent.getStringExtra("title") ?: "一起看看",mode)
            engine = WatchEngine(app,id,mode,::state) { overlay?.message(it) }
            overlay = Overlay(this,app,engine!!).also { it.show() }
            val size = screenSize()
            resize(size.first,size.second)
            display = projection!!.createVirtualDisplay("ScreenMate",width,height,resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,handler)
            state("陪看中 · 等待画面")
        } catch(_: Exception) { end("屏幕共享启动失败，请检查浮窗权限并重新开始") }
        return START_NOT_STICKY
    }
    private fun screenSize(): Pair<Int,Int> {
        val wm = getSystemService(WindowManager::class.java)
        if(Build.VERSION.SDK_INT>=30) return wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }
    private fun resize(w: Int,h: Int) {
        val scale = minOf(1.0,1280.0/maxOf(w,h))
        val nw=(w*scale).roundToInt().coerceAtLeast(2); val nh=(h*scale).roundToInt().coerceAtLeast(2)
        if(nw == width && nh == height && reader != null) return
        val replacement = ImageReader.newInstance(nw,nh,PixelFormat.RGBA_8888,2)
        replacement.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            image.use { frame ->
                val e = engine ?: return@use
                val now = SystemClock.elapsedRealtime()
                if(!e.canCapture || now-lastAttempt < 1000) return@use
                lastAttempt=now
                try {
                    val plane=frame.planes[0]
                    val paddedWidth=plane.rowStride/plane.pixelStride
                    val bitmap=Bitmap.createBitmap(paddedWidth,frame.height,Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(plane.buffer)
                    val cropped=Bitmap.createBitmap(bitmap,0,0,frame.width,frame.height)
                    val output=ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG,78,output)
                    if(cropped !== bitmap) cropped.recycle()
                    bitmap.recycle()
                    val bytes=output.toByteArray()
                    val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    e.frame(Base64.encodeToString(bytes,Base64.NO_WRAP),hash)
                } catch(_: Exception) { state("暂时未取得画面，等待下一帧") }
            }
        },handler)
        display?.resize(nw,nh,resources.configuration.densityDpi)
        display?.surface=replacement.surface
        val old=reader; reader=replacement; width=nw; height=nh
        old?.setOnImageAvailableListener(null,null); old?.close()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if(Build.VERSION.SDK_INT<34) { val size=screenSize(); resize(size.first,size.second) }
        overlay?.reposition()
    }
    private fun notification(text: String): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,CaptureService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause=PendingIntent.getService(this,2,Intent(this,CaptureService::class.java).setAction(PAUSE),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"watch").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("ScreenMate 陪看")
            .setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"暂停 / 继续",pause).build())
            .addAction(Notification.Action.Builder(null,"结束",stop).build()).build()
    }
    private fun state(text: String) {
        status=text; overlay?.status(text)
        if(!ending) getSystemService(NotificationManager::class.java).notify(1,notification(text))
    }
    fun end(text: String = "已结束，记忆正在后台整理") {
        if(ending) return
        ending=true; status=text
        engine?.stop(); engine=null
        overlay?.destroy(); overlay=null
        reader?.setOnImageAvailableListener(null,null)
        display?.release(); display=null; reader?.close(); reader=null
        projection?.unregisterCallback(callback); projection?.stop(); projection=null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { end(); instance=null; super.onDestroy() }
}
