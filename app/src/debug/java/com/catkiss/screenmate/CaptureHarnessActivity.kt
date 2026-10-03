package com.catkiss.screenmate

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView

/** Debug-only consent harness: no exported entry point and no network credentials. */
class CaptureHarnessActivity: Activity() {
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private var tick=0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text="局长：我们一起回去吧。"; textSize=28f; gravity=android.view.Gravity.BOTTOM; setPadding(30,30,30,90) })
        if(intent.getBooleanExtra("video",false)) {
            val text=TextView(this).apply { textSize=38f; gravity=android.view.Gravity.CENTER }
            setContentView(text)
            val ticker=object: Runnable { override fun run() {
                tick++; text.text="连续视频测试 第 $tick 秒\n人物找到钥匙，随后打开门"
                text.setBackgroundColor(if(tick%2==0) android.graphics.Color.rgb(170,220,240) else android.graphics.Color.rgb(250,210,160))
                handler.postDelayed(this,1000)
            } }; handler.post(ticker)
        }
        if(savedInstanceState==null) {
            val manager=getSystemService(MediaProjectionManager::class.java)
            val request=if(Build.VERSION.SDK_INT>=34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
            @Suppress("DEPRECATION") startActivityForResult(request,1)
        }
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
    @Deprecated("Platform consent callback")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==1 && resultCode==RESULT_OK && data!=null) {
            startForegroundService(Intent(this,CaptureService::class.java).putExtra("consent",data).putExtra("title","Capture integration test").putExtra("mode",if(intent.getBooleanExtra("video",false)) "B站 / 连续视频（5秒）" else "无期迷途 · 剧情"))
        }
    }
}
