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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text="ScreenMate capture integration test"; textSize=28f })
        if(savedInstanceState==null) {
            val manager=getSystemService(MediaProjectionManager::class.java)
            val request=if(Build.VERSION.SDK_INT>=34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
            @Suppress("DEPRECATION") startActivityForResult(request,1)
        }
    }
    @Deprecated("Platform consent callback")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==1 && resultCode==RESULT_OK && data!=null) {
            startForegroundService(Intent(this,CaptureService::class.java).putExtra("consent",data).putExtra("title","Capture integration test").putExtra("mode","video"))
        }
    }
}
