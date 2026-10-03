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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.math.roundToInt

class CaptureService: Service() {
    companion object {
        var instance: CaptureService? = null; private set
        var status: String = "尚未开始"; private set
        const val STOP = "screenmate.stop"
        const val PAUSE = "screenmate.pause"
    }
    private val handler = Handler(Looper.getMainLooper())
    private val imageWorker = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: Overlay? = null
    var engine: WatchEngine? = null; private set
    @Volatile private var ending = false
    private var width=0
    private var height=0
    private var lastAttempt=0L
    private var videoCapture: VideoCapture?=null
    private var videoSequence=0
    private var videoBlocked=""
    private var videoError=false
    private var videoGeneration=0
    private val videoTicker=object: Runnable {
        override fun run() {
            if(ending) return
            reconcileVideo()
            handler.postDelayed(this,200)
        }
    }
    fun appVisibility(visible: Boolean) { overlay?.appVisible(visible); reconcileVideo() }
    fun retryVideo() { videoError=false; engine?.videoPipeline?.retry(); reconcileVideo() }
    private fun stopVideo() {
        videoGeneration++
        display?.surface=null
        videoCapture?.close(); videoCapture=null
    }
    private fun reconcileVideo() {
        val e=engine ?: return
        if(!e.videoMode) return
        val reason=when {
            videoError -> "视频编码失败，点击诊断页的重试"
            e.paused -> "已暂停视频采集"
            MainActivity.visible -> "在App内查看设置/诊断，暂停录制；切回视频继续"
            !e.visible -> "共享目标不可见，暂停录制"
            e.videoPipeline?.canRecord!=true -> "处理积压/接口失败/日上限，暂停新增录像；请看视频诊断"
            else -> ""
        }
        if(reason.isNotEmpty()) {
            if(videoCapture!=null) { stopVideo(); e.videoPipeline?.gap("$reason；未满5秒的片段未提交，形成观察空缺") }
            if(videoBlocked!=reason) { videoBlocked=reason; state(reason) }
            return
        }
        if(videoCapture!=null) return
        videoBlocked=""
        val generation=++videoGeneration
        try {
            val capture=VideoCapture(this,projection,width,height,(application as MateApp).config.capturePlaybackAudio,
                { original ->
                    if(ending || engine!==e || generation!=videoGeneration || !e.canCapture) original.file.delete()
                    else {
                        val clip=original.copy(sequence=++videoSequence)
                        imageWorker.execute {
                            runCatching {
                                VideoDiagnostics.preserve(clip,java.io.File(cacheDir,"video-preview"),false)
                                val retriever=android.media.MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(VideoDiagnostics.latest!!.file.absolutePath)
                                    val bitmap=retriever.getFrameAtTime(1_000_000,android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    if(bitmap!=null) {
                                        val out=ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG,78,out)
                                        var dark=0; var samples=0
                                        for(y in 0 until bitmap.height step maxOf(1,bitmap.height/40)) for(x in 0 until bitmap.width step maxOf(1,bitmap.width/40)) {
                                            val pixel=bitmap.getPixel(x,y); samples++
                                            if(android.graphics.Color.red(pixel)<12 && android.graphics.Color.green(pixel)<12 && android.graphics.Color.blue(pixel)<12) dark++
                                        }
                                        val frame=CaptureDiagnostics.Frame(out.toByteArray(),clip.start+1000,bitmap.width,bitmap.height,dark*100/samples)
                                        bitmap.recycle()
                                        handler.post { if(!ending && generation==videoGeneration) { CaptureDiagnostics.latest=frame; CaptureDiagnostics.note="视频片段中的代表帧；请回放视频检查动态内容" } }
                                    }
                                } finally { retriever.release() }
                            }
                            handler.post {
                                if(!ending && engine===e && generation==videoGeneration && e.canCapture) e.videoPipeline?.offer(clip)
                                else clip.file.delete()
                            }
                        }
                    }
                }, { error -> if(generation==videoGeneration && !ending) { videoError=true; state(error); VideoDiagnostics.stage=error } })
            videoCapture=capture; VideoDiagnostics.audio=capture.audioStatus
            display?.surface=capture.surface
            capture.start()
            state("连续视频采集中 · 每段约5秒 · ${capture.audioStatus}")
        } catch(_: Exception) { videoError=true; stopVideo(); state("无法启动视频编码，诊断页可重试，或改用截图模式") }
    }
    private val callback = object: MediaProjection.Callback() {
        override fun onStop() { end("系统已停止屏幕共享，会话已保存") }
        override fun onCapturedContentResize(w: Int,h: Int) { if(w>0 && h>0 && !ending) resize(w,h) }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) { engine?.visible=isVisible; CaptureDiagnostics.visibility=isVisible }
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
            CaptureDiagnostics.reset(); VideoDiagnostics.reset()
            java.io.File(cacheDir,"video-preview").deleteRecursively()
            java.io.File(cacheDir,"video-segments").deleteRecursively()
            val mode = intent.getStringExtra("mode") ?: "无期迷途 · 剧情"
            val id = app.store.create(intent.getStringExtra("title") ?: "一起看看",mode)
            engine = WatchEngine(app,id,mode,::state,{ overlay?.message(it) },app.gateway ?: app.models)
            overlay = Overlay(this,app,engine!!).also { it.show() }
            val size = screenSize()
            resize(size.first,size.second)
            display = projection!!.createVirtualDisplay("ScreenMate",width,height,resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,if(engine!!.videoMode) null else reader!!.surface,null,handler)
            if(engine!!.videoMode) handler.post(videoTicker)
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
        val scale = minOf(1.0,(if(engine?.storyMode==true) 2880.0 else 1280.0)/maxOf(w,h))
        val nw=((w*scale).roundToInt()/2*2).coerceAtLeast(2); val nh=((h*scale).roundToInt()/2*2).coerceAtLeast(2)
        if(engine?.videoMode==true) {
            if(nw==width && nh==height) return
            if(videoCapture!=null) engine?.videoPipeline?.gap("画面尺寸变化，重新配置编码；边界未满5秒片段未提交")
            stopVideo(); width=nw; height=nh
            display?.resize(nw,nh,resources.configuration.densityDpi)
            return
        }
        if(nw == width && nh == height && reader != null) return
        val replacement = ImageReader.newInstance(nw,nh,PixelFormat.RGBA_8888,2)
        replacement.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val e=engine
            val now=SystemClock.elapsedRealtime()
            if(e==null || !e.canCapture || now-lastAttempt<750 || !processing.compareAndSet(false,true)) {
                image.close(); return@setOnImageAvailableListener
            }
            lastAttempt=now
            val epoch=e.captureEpoch
            val c=(application as MateApp).config
            val top=c.dialogueTop; val bottom=c.dialogueBottom
            // Ownership of this acquired Image belongs to the worker until it is closed.
            imageWorker.execute {
                var bitmap: Bitmap?=null
                try {
                    image.use { frame ->
                        val plane=frame.planes[0]
                        val padded=Bitmap.createBitmap(plane.rowStride/plane.pixelStride,frame.height,Bitmap.Config.ARGB_8888)
                        try {
                            plane.buffer.rewind(); padded.copyPixelsFromBuffer(plane.buffer)
                            bitmap=Bitmap.createBitmap(padded,0,0,frame.width,frame.height)
                        } finally { if(bitmap !== padded) padded.recycle() }
                    }
                    processBitmap(bitmap!!,e,epoch,now,top,bottom)
                } catch(_: Exception) {
                    bitmap?.recycle(); finishProcessing()
                    handler.post { if(e.acceptsCapture(epoch)) { CaptureDiagnostics.note="采集处理失败，等待下一帧"; state(CaptureDiagnostics.note) } }
                }
            }
        },handler)
        display?.surface=null
        display?.resize(nw,nh,resources.configuration.densityDpi)
        display?.surface=replacement.surface
        val old=reader; reader=replacement; width=nw; height=nh
        old?.setOnImageAvailableListener(null,null); if(old!=null) imageWorker.execute { old.close() }
    }
    private fun processBitmap(bitmap: Bitmap, e: WatchEngine, epoch: Long, now: Long, top: Int, bottom: Int) {
        var dark=0; var total=0
        for(y in 0 until bitmap.height step maxOf(1,bitmap.height/40)) for(x in 0 until bitmap.width step maxOf(1,bitmap.width/40)) {
            val pixel=bitmap.getPixel(x,y); total++
            if(android.graphics.Color.red(pixel)<12 && android.graphics.Color.green(pixel)<12 && android.graphics.Color.blue(pixel)<12) dark++
        }
        val scale=minOf(1.0,1280.0/maxOf(bitmap.width,bitmap.height))
        val small=if(scale<1) Bitmap.createScaledBitmap(bitmap,(bitmap.width*scale).roundToInt(),(bitmap.height*scale).roundToInt(),true) else bitmap
        val out=ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG,78,out)
        val bytes=out.toByteArray()
        val diagnostic=CaptureDiagnostics.Frame(bytes,System.currentTimeMillis()-(SystemClock.elapsedRealtime()-now),small.width,small.height,dark*100/total)
        if(small !== bitmap) small.recycle()
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val base64=Base64.encodeToString(bytes,Base64.NO_WRAP)
        handler.post {
            if(e.acceptsCapture(epoch)) {
                CaptureDiagnostics.latest=diagnostic
                CaptureDiagnostics.note=if(diagnostic.darkPercent>=98) "画面接近全黑，请查看截图；可能是转场或采集异常" else "已采集新画面"
                // Dark scenes are reported, not silently removed from evidence.
                e.frame(base64,hash,now,diagnostic)
            }
        }
        if(!e.storyMode) { bitmap.recycle(); finishProcessing(); return }
        val y=(bitmap.height*top/100).coerceIn(0,bitmap.height-1)
        val end=(bitmap.height*bottom/100).coerceIn(y+1,bitmap.height)
        val crop=Bitmap.createBitmap(bitmap,0,y,bitmap.width,end-y)
        val began=SystemClock.elapsedRealtime()
        // Keep both bitmaps alive until ML Kit has finished, including shutdown/pause.
        try {
            recognizer.process(InputImage.fromBitmap(crop,0)).addOnCompleteListener(imageWorker) { task ->
                val text=if(task.isSuccessful) task.result.textBlocks.sortedWith(compareBy({ it.boundingBox?.top ?: 0 },{ it.boundingBox?.left ?: 0 }))
                    .joinToString("\n") { it.text } else ""
                if(crop !== bitmap) crop.recycle()
                bitmap.recycle(); finishProcessing()
                handler.post {
                    if(e.acceptsCapture(epoch)) {
                        CaptureDiagnostics.ocrMillis=SystemClock.elapsedRealtime()-began
                        if(task.isSuccessful) e.localText(text,now,epoch)
                        else { CaptureDiagnostics.note="本地识字失败，云端仍可补充画面"; state(CaptureDiagnostics.note) }
                    }
                }
            }
        } catch(error: Exception) {
            if(crop !== bitmap) crop.recycle()
            throw error
        }
    }
    private fun finishProcessing() {
        processing.set(false)
        handler.post { if(ending) { recognizer.close(); imageWorker.shutdown() } }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if(Build.VERSION.SDK_INT<34) { val size=screenSize(); resize(size.first,size.second) }
        overlay?.reposition()
    }
    private fun notification(text: String): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause=PendingIntent.getService(this,2,Intent(this,CaptureService::class.java).setAction(PAUSE),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"watch").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("ScreenMate 陪看")
            .setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"暂停 / 继续",pause).build()).build()
    }
    private fun state(text: String) {
        status=text; overlay?.status(text)
        if(!ending) getSystemService(NotificationManager::class.java).notify(1,notification(text))
    }
    fun end(text: String = "已结束，记忆正在后台整理") {
        if(ending) return
        ending=true; status=text
        handler.removeCallbacks(videoTicker); stopVideo()
        engine?.stop(); engine=null
        overlay?.destroy(); overlay=null
        reader?.setOnImageAvailableListener(null,null)
        display?.release(); display=null
        val oldReader=reader; reader=null
        if(oldReader!=null) imageWorker.execute { oldReader.close() }
        projection?.unregisterCallback(callback); projection?.stop(); projection=null
        if(!processing.get()) { recognizer.close(); imageWorker.shutdown() }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { end(); instance=null; super.onDestroy() }
}
