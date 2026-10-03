package com.catkiss.screenmate

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity: Activity() {
    companion object { @Volatile var visible=false }
    private val app get() = application as MateApp
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var body: LinearLayout
    private var statusView: TextView?=null
    private var pendingTitle="一起看看"
    private var pendingMode="视频陪看"
    private var selectedSession=0L
    private var player: VideoView?=null
    private val handler=Handler(Looper.getMainLooper())
    private val ticker=object: Runnable { override fun run() { statusView?.text=CaptureService.status; handler.postDelayed(this,1000) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        home()
    }
    override fun onResume() { super.onResume(); visible=true; handler.post(ticker) }
    override fun onPause() { player?.stopPlayback(); visible=false; handler.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() { scope.cancel(); handler.removeCallbacksAndMessages(null); super.onDestroy() }
    private fun screen(title: String, subtitle: String) {
        player?.stopPlayback(); player=null
        statusView=null
        val scroll=ScrollView(this).apply { setBackgroundColor(Color.rgb(244,246,243)); clipToPadding=false }
        scroll.setOnApplyWindowInsetsListener { v,insets ->
            @Suppress("DEPRECATION") v.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            insets
        }
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(28),dp(24),dp(28),dp(48)) }
        val outer=LinearLayout(this).apply { gravity=Gravity.CENTER_HORIZONTAL; addView(body,LinearLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels,dp(760)),-2)) }
        scroll.addView(outer); setContentView(scroll)
        label(title,30f); label(subtitle,15f)
    }
    private fun label(text: String,size: Float=16f): TextView = TextView(this).apply {
        this.text=text; textSize=size; setTextColor(Color.rgb(39,60,65)); setPadding(0,dp(10),0,dp(10))
    }.also { body.addView(it) }
    private fun button(text: String, action: ()->Unit): Button = Button(this).apply {
        this.text=text; isAllCaps=false; setOnClickListener { action() }
    }.also { body.addView(it,LinearLayout.LayoutParams(-1,dp(56)).apply { topMargin=dp(8) }) }
    private fun field(caption: String, value: String, secret: Boolean=false, multi: Boolean=false): EditText {
        label(caption,14f)
        return EditText(this).apply {
            setText(value); textSize=16f; setPadding(dp(12),dp(10),dp(12),dp(10)); background=rounded(Color.WHITE)
            inputType=when { secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
            if(multi) minLines=3 else setSingleLine(true)
            importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }.also { body.addView(it,LinearLayout.LayoutParams(-1,-2)) }
    }
    private fun message(text: String) { Toast.makeText(this,text,Toast.LENGTH_LONG).show() }
    private fun home() {
        screen("一起看看", "ScreenMate · 0.1.2 连续视频测试版\n给剧情留一点陪伴，也给屏幕留一点安静。")
        statusView=label(CaptureService.status)
        body.addView(CheckBox(this).apply {
            text="视频测试：每段识别并整理后立即回复一句"; isChecked=app.config.replyEveryVideo
            setOnCheckedChangeListener { _,checked -> app.config.replyEveryVideo=checked }
        })
        label("开关开启时不受普通主动评论间隔限制。约每5秒录一段；回复仍需等待云端处理，气泡标明片段时间和延迟。",14f)
        if(CaptureService.instance?.engine!=null) {
            button("暂停 / 继续") { CaptureService.instance?.engine?.pause() }
            button("结束本次陪看") { CaptureService.instance?.end(); home() }
            label("点击屏幕角落的伙伴打开聊天；可自由拖动，横竖屏分别记住位置。")
        } else {
            val title=field("本次想看什么", "")
            val mode=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("B站 / 连续视频（5秒）","屏幕截图（对照）","无期迷途 · 剧情")) }
            body.addView(mode,LinearLayout.LayoutParams(-1,dp(52)))
            button("开始陪看") {
                pendingTitle=title.text.toString().trim().ifBlank { "一起看看 · ${SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(Date())}" }
                pendingMode=mode.selectedItem.toString()
                startWatch()
            }
        }
        button("接口与陪伴设置") { settings() }
        button("视频 / 识屏诊断与实际回放") { diagnostics() }
        button("会话与记忆") { sessions() }
        label("第一次使用",20f)
        label("1. 填写官方 Gemini 视频/识图、第二通道回复和 DeepSeek 整理接口。\n2. 允许悬浮窗，点击开始并选择要分享的应用。\n3. 选择连续视频模式，切到B站播放；可开启逐段回复测试速度和理解。\n4. 看完点击结束，会话记录会留在这里。")
        label("本版不接TTS。设置里可选采集应用播放音轨，不使用麦克风；来源禁止时可能只有静音。视频暂存于App缓存供回放，下一场开始清除。截图/OCR模式保留作对照。HyperOS 若清理后台，可在系统应用设置中允许后台运行，并将耗电策略调为无限制。",14f)
    }
    private fun startWatch() {
        app.config.ready()?.let { message(it); settings(); return }
        if(!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this).setTitle("允许显示陪看伙伴").setMessage("请在系统页面开启 ScreenMate 的悬浮窗权限，然后返回再次点击开始。")
                .setPositiveButton("前往设置") { _,_ -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) }.setNegativeButton("取消",null).show(); return
        }
        if(pendingMode.contains("连续视频") && app.config.capturePlaybackAudio && Build.VERSION.SDK_INT>=29 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),11); return
        }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),10)
        AlertDialog.Builder(this).setTitle("开始本次屏幕陪看")
            .setMessage("连续视频模式持续录制并约每5秒切一段MP4发送给官方Gemini；可选应用播放音轨，不使用麦克风。DeepSeek整理连续剧情，第二通道回复。视频保存在私有缓存供诊断，下一场开始清除。处理跟不上时会显示积压并暂停新增采集，空缺单独记录。截图/OCR模式仍可对照。\n\n建议只分享要看的应用。若分享整个屏幕，请先避开私密内容；暂停后不再采集，结束会撤销本次共享。模型用量和免费额度以各服务商账户为准。")
            .setNegativeButton("取消",null).setPositiveButton("选择分享画面") { _,_ ->
                @Suppress("DEPRECATION") startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),100)
            }.show()
    }
    private fun settings() {
        if(CaptureService.instance?.engine?.paused==false) CaptureService.instance?.engine?.pause()
        screen("陪伴设置","官方识图与中转回复分开配置。进入设置会暂停当前陪看；返回后可手动继续。")
        val c=app.config
        val visionKey=field("官方 Gemini API Key",c.secret("vision"),true)
        val visionModel=field("官方视频/识图模型（视频测试可填 gemini-3.5-flash）",c.visionModel)
        button("读取官方账户可用模型") {
            val key=visionKey.text.toString().trim()
            if(key.isBlank()) { message("先填写官方 Key"); return@button }
            runCatching { c.setSecret("vision",key) }.onFailure { message("无法安全保存 Key"); return@button }
            message("正在读取模型列表…")
            scope.launch {
                try {
                    val models=app.models.listVisionModels()
                    if(models.isEmpty()) message("账户没有可用的 generateContent 模型")
                    else AlertDialog.Builder(this@MainActivity).setTitle("选择支持图片输入的模型；免费资格请看 AI Studio")
                        .setItems(models.toTypedArray()) { _,which -> visionModel.setText(models[which]) }.show()
                } catch(e: Exception) { if(e is CancellationException) throw e; message(if(e is ApiFailure) e.message ?: "读取失败" else "读取失败，请检查 Key 与网络") }
            }
        }
        val relayUrl=field("第二通道完整接口地址（OpenAI 兼容）",c.relayUrl)
        val relayKey=field("第二通道 Key",c.secret("relay"),true)
        val relayModel=field("第二通道模型名（按中转站原样填写）",c.relayModel)
        val deepUrl=field("DeepSeek 完整接口地址",c.deepUrl)
        val deepKey=field("DeepSeek Key",c.secret("deep"),true)
        val deepModel=field("DeepSeek 模型",c.deepModel)
        val videoReplies=CheckBox(this).apply { text="每个视频片段整理完成后回复一句（测试用）"; isChecked=c.replyEveryVideo }; body.addView(videoReplies)
        val audio=CheckBox(this).apply { text="采集应用播放音轨（需录音权限，不使用麦克风）"; isChecked=c.capturePlaybackAudio }; body.addView(audio)
        label("5秒视频约12次识别/分钟，另含DeepSeek整理和逐段回复。额度以账户为准；输入框中的图片间隔不影响视频切片。当前整理模型建议 deepseek-flash，旧配置不会自动覆盖。",14f)
        val persona=field("她是谁，你们是什么关系",c.persona,multi=true)
        val interval=field("云端画面补充间隔（10–120 秒；不影响本地对白采集）",c.intervalSeconds.toString())
        val roiTop=field("对白区域上边界（屏幕高度百分比，默认 55；居中文字可设 0）",c.dialogueTop.toString())
        val roiBottom=field("对白区域下边界（百分比，默认 98）",c.dialogueBottom.toString())
        val comments=field("主动评论最短间隔（30–600 秒，默认 90）",c.commentSeconds.toString())
        val cap=field("本机每日识图请求上限（不是 Google 免费额度）",c.dailyCap.toString())
        label("本机计数按太平洋日期记录。今日：识图 ${app.store.usage("vision")}、第二通道 ${app.store.usage("relay")}、整理 ${app.store.usage("summary")}、兜底 ${app.store.usage("fallback")} 次。\n有变化且值得说时才主动评论；间隔是最短等待时间，不是定时强行说话。",14f)
        val flip=CheckBox(this).apply { text="反转导入图的默认朝向"; isChecked=c.flip }
        body.addView(flip)
        fun save(): Boolean {
            val ru=relayUrl.text.toString().trim(); val du=deepUrl.text.toString().trim()
            if(!Endpoints.validate(ru)||!Endpoints.validate(du)) { message("请使用无 query、无用户名密码的完整 HTTPS 接口地址"); return false }
            val vm=visionModel.text.toString().trim()
            if(vm.isNotBlank() && !Endpoints.model(vm)) { message("官方模型名格式不正确，不要包含 models/ 前缀"); return false }
            val i=interval.text.toString().toIntOrNull(); val co=comments.text.toString().toIntOrNull(); val ca=cap.text.toString().toIntOrNull()
            if(i==null||i !in 10..120||co==null||co !in 30..600||ca==null||ca !in 1..5000) { message("请检查间隔和上限范围"); return false }
            val rt=roiTop.text.toString().toIntOrNull(); val rb=roiBottom.text.toString().toIntOrNull()
            if(rt==null || rb==null || rt !in 0..90 || rb !in 10..100 || rb-rt<10) { message("对白区域至少覆盖 10% 高度，上边界须小于下边界"); return false }
            return runCatching {
                c.replyEveryVideo=videoReplies.isChecked; c.capturePlaybackAudio=audio.isChecked
                c.dialogueTop=rt; c.dialogueBottom=rb
                c.setSecret("vision",visionKey.text.toString()); c.setSecret("relay",relayKey.text.toString()); c.setSecret("deep",deepKey.text.toString())
                c.visionModel=vm; c.relayUrl=ru; c.relayModel=relayModel.text.toString().trim()
                c.deepUrl=du; c.deepModel=deepModel.text.toString().trim(); c.persona=persona.text.toString()
                c.intervalSeconds=i; c.commentSeconds=co; c.dailyCap=ca; c.flip=flip.isChecked; true
            }.getOrElse { message("无法安全保存配置，请重试"); false }
        }
        button("保存设置") { if(save()) { message("已保存"); home() } }
        button("导入本地伙伴图片") {
            if(CaptureService.instance!=null) { message("请先结束当前陪看，再更换图片"); return@button }
            if(save()) {
                @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),101)
            }
        }
        button("打开参考项目原人物素材") {
            startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/MeteorNOX/DeepSeek-Balance-Whale-Widget/blob/main/assets/DSniang1.png")))
        }
        button("恢复默认小鲸鱼") {
            if(CaptureService.instance!=null) message("请先结束当前陪看") else { File(filesDir,"mascot.png").delete(); message("已恢复，下次开始生效") }
        }
        label("参考项目中的人物图需要你在本机选择导入。本仓库只包含原创代码绘制的小鲸鱼占位，不包含该人物美术资产。",14f)
        button("返回") { home() }
    }
    private fun diagnostics() {
        screen("识屏诊断","这里显示实际采集及送给官方 Gemini 的图片。仅保留在内存，打开本页时停止取得新画面；切回游戏继续。黑色区域比例只是线索，不代表已判定播放保护。")
        val vd=VideoDiagnostics
        label("连续视频",20f)
        label("${vd.stage}\n音轨：${vd.audio}\n已完成 ${vd.completed} 段 / 待处理 ${vd.queue} 段 / 空缺 ${vd.gaps} 处\n最近识别 ${vd.visionMs}ms / 整理 ${vd.storyMs}ms / 回复 ${CaptureDiagnostics.replyMillis}ms\n最近视频输入token：${vd.tokens}（0表示未取得用量）\n剧情理解推进到：${if(vd.observedEnd>0) SimpleDateFormat("HH:mm:ss",Locale.CHINA).format(Date(vd.observedEnd)) else "尚无"}\n片尾至完成：${vd.totalMs/1000.0}秒")
        button("重试视频采集 / 失败片段") { CaptureService.instance?.retryVideo(); message("已请求重试；切回视频后采集继续") }
        fun replay(caption: String,clip: VideoClip?) {
            label(caption,20f)
            if(clip==null || !clip.file.exists()) { label("暂无片段"); return }
            label("${clip.label()} · ${(clip.end-clip.start)/1000.0}秒 · ${clip.width}×${clip.height} · ${clip.file.length()/1024}KB\n${clip.sound()}",14f)
            val container=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; body.addView(container)
            button("播放$caption") {
                player?.stopPlayback()
                val view=VideoView(this)
                container.removeAllViews(); container.addView(view,LinearLayout.LayoutParams(-1,dp(320)))
                player=view
                val snapshot=File(cacheDir,"video-playback.mp4")
                runCatching { clip.file.copyTo(snapshot,true); view.setVideoPath(snapshot.absolutePath) }
                    .onFailure { message("片段已清除，请刷新诊断") }
                view.setMediaController(MediaController(this).apply { setAnchorView(view) })
                view.setOnPreparedListener { view.start() }
                view.setOnErrorListener { _,_,_ -> message("视频无法解码，请保留诊断信息"); true }
            }
        }
        replay("最近采集视频",vd.latest)
        replay("最近实际发送视频",vd.sent)
        label("最近视频识别原文\n${vd.evidence.ifBlank { "暂无" }}",14f)
        label("连续剧情整理\n${vd.story.ifBlank { "暂无" }}",14f)
        button("清除本地视频预览") {
            player?.stopPlayback(); File(cacheDir,"video-preview").deleteRecursively(); File(cacheDir,"video-playback.mp4").delete()
            VideoDiagnostics.latest=null; VideoDiagnostics.sent=null; diagnostics()
        }
        val d=CaptureDiagnostics
        label("${d.note}\n分享目标可见：${d.visibility}\n稳定对白：${d.dialogueCount} 段\n最近耗时：本地识字 ${d.ocrMillis} ms / 云端识图 ${d.visionMillis} ms / 回复 ${d.replyMillis} ms")
        fun preview(title: String, frame: CaptureDiagnostics.Frame?) {
            label(title,20f)
            if(frame==null) { label("暂无图片"); return }
            label("${SimpleDateFormat("HH:mm:ss",Locale.CHINA).format(Date(frame.capturedAt))} · ${frame.width}×${frame.height} · 暗色采样 ${frame.darkPercent}%",14f)
            val bitmap=BitmapFactory.decodeByteArray(frame.jpeg,0,frame.jpeg.size)
            body.addView(ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds=true; contentDescription=title },LinearLayout.LayoutParams(-1,-2))
        }
        preview("最近采集的整张截图",d.latest)
        preview("最近实际发送给 Gemini 的截图",d.sent)
        label("本地识字区域：高度 ${app.config.dialogueTop}%–${app.config.dialogueBottom}%（设置里可调整）\n最近 OCR 原文：\n${d.ocrText.ifBlank { "尚无可读文字" }}")
        button("刷新诊断") { diagnostics() }
        button("返回") { home() }
    }
    private fun sessions() {
        screen("会话与记忆","每次陪看单独存放。已有摘要可以参与后续聊天；删除会同时移除记录和摘要。")
        val all=app.store.sessions()
        if(all.isEmpty()) label("还没有会话，开始一次陪看后会出现在这里。")
        all.forEach { s ->
            val state=when(s.state) { "active" -> "进行中"; "pending" -> "待整理 / 正在重试"; else -> "已整理" }
            button("${s.title}\n$state") { sessionDetail(s.id) }
        }
        button("返回") { home() }
    }
    private fun sessionDetail(id: Long) {
        val s=app.store.session(id) ?: return sessions()
        selectedSession=id
        screen(s.title,"${s.mode} · ${SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(Date(s.created))}")
        label("会话摘要",20f); label(s.summary.ifBlank { "还没有摘要。文字记录已保存，结束后会整理；断网时等待重试。" })
        button("重新检查 / 继续整理") { app.archive(id); message("已加入整理队列"); sessionDetail(id) }
        button("导出本次文字记录") {
            @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"ScreenMate-$id.txt"),102)
        }
        button("删除本次会话与记忆") {
            if(CaptureService.instance?.engine?.sessionId==id) { message("请先结束当前陪看"); return@button }
            AlertDialog.Builder(this).setTitle("删除这次会话？").setMessage("这次的观察、聊天和摘要都会删除，不影响其他会话。")
                .setNegativeButton("取消",null).setPositiveButton("删除") { _,_ -> app.deleteSession(id); sessions() }.show()
        }
        label("最近记录（完整记录可导出）",20f)
        app.store.recent(id,100).forEach { entry ->
            val type=when(entry.kind) { "user" -> "你"; "assistant" -> "她"; "dialogue" -> "本地对白"; "video_observation" -> "视频证据"; "video_story" -> "连续剧情"; "video_gap" -> "观察空缺"; "video_error" -> "视频处理错误"; else -> "画面观察" }
            label("${SimpleDateFormat("HH:mm:ss",Locale.CHINA).format(Date(entry.time))} · $type\n${entry.body}",14f)
        }
        button("返回会话列表") { sessions() }
    }
    @Deprecated("Platform permission callback")
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==11) {
            if(grantResults.firstOrNull()!=PackageManager.PERMISSION_GRANTED) { app.config.capturePlaybackAudio=false; message("音频权限未授予，本次只录画面") }
            startWatch()
        }
    }
    @Deprecated("Activity platform callback")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK || data==null) return
        when(requestCode) {
            100 -> {
                if(CaptureService.instance!=null) return
                startForegroundService(Intent(this,CaptureService::class.java).putExtra("consent",data).putExtra("title",pendingTitle).putExtra("mode",pendingMode))
                handler.postDelayed({ if(!isFinishing) { home(); moveTaskToBack(true) } },500)
            }
            101 -> data.data?.let { uri -> importImage(uri) }
            102 -> data.data?.let { uri -> exportSession(uri,selectedSession) }
        }
    }
    private fun importImage(uri: Uri) {
        scope.launch {
            val result=withContext(Dispatchers.IO) { runCatching {
                val bytes=contentResolver.openInputStream(uri)!!.use { input ->
                    val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                    while(true) { val n=input.read(buffer); if(n<0) break; require(out.size()+n<=12_000_000); out.write(buffer,0,n) }; out.toByteArray()
                }
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                require(bounds.outWidth>0 && bounds.outHeight>0)
                var sample=1
                while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1024) sample*=2
                val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: error("image")
                val temp=File(filesDir,"mascot.tmp")
                temp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }; bitmap.recycle()
                check(temp.renameTo(File(filesDir,"mascot.png")))
            } }
            message(if(result.isSuccess) "图片已导入，下次开始时使用" else "导入失败，请选择 12 MB 以内的 PNG / JPG 图片")
        }
    }
    private fun exportSession(uri: Uri,id: Long) {
        scope.launch {
            val result=withContext(Dispatchers.IO) { runCatching {
                val s=app.store.session(id) ?: error("deleted")
                contentResolver.openOutputStream(uri,"wt")!!.bufferedWriter().use { out ->
                    out.appendLine("${s.title}\n${s.mode}\n\n摘要\n${s.summary}\n\n记录")
                    var cursor=0L
                    while(true) {
                        val rows=app.store.entries(id,cursor,100); if(rows.isEmpty()) break
                        rows.forEach { out.appendLine("\n${Date(it.time)} [${it.kind}]\n${it.body}") }; cursor=rows.last().id
                    }
                }
            } }
            message(if(result.isSuccess) "文字记录已导出" else "导出失败，请重试")
        }
    }
    @Deprecated("Activity platform callback")
    override fun onBackPressed() { home() }
}
