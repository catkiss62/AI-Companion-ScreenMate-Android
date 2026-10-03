package com.catkiss.screenmate

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.*
import java.io.File
import kotlin.math.abs

fun Context.dp(value: Int) = (resources.displayMetrics.density*value).toInt()
fun rounded(color: Int, radius: Float = 24f) = GradientDrawable().apply { setColor(color); cornerRadius=radius }

class Overlay(private val service: CaptureService, private val app: MateApp, private val engine: WatchEngine) {
    private val wm=service.getSystemService(WindowManager::class.java)
    private val handler=Handler(Looper.getMainLooper())
    private val pet=MascotView(service,app)
    private val petParams=params(service.dp(100),service.dp(124))
    private var bubble: TextView?=null
    private var panel: LinearLayout?=null
    private var panelParams: WindowManager.LayoutParams?=null
    private var history: TextView?=null
    private var state: TextView?=null
    private var pausedButton: Button?=null
    private val hideBubble=Runnable { bubble?.let { remove(it) }; bubble=null }
    private fun params(w: Int,h: Int) = WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.LEFT; softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
    fun appVisible(visible: Boolean) {
        if(visible) { closePanel(); hideBubble.run() }
        pet.visibility=if(visible) View.INVISIBLE else View.VISIBLE
        petParams.flags=if(visible) petParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else petParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(pet,petParams) }
    }
    fun show() {
        wm.addView(pet,petParams); reposition(); appVisible(MainActivity.visible)
        var startX=0f; var startY=0f; var x=0; var y=0; var moved=false
        val slop=ViewConfiguration.get(service).scaledTouchSlop
        pet.setOnTouchListener { _, event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX=event.rawX; startY=event.rawY; x=petParams.x; y=petParams.y; moved=false; true }
                MotionEvent.ACTION_MOVE -> {
                    if(abs(event.rawX-startX)>slop || abs(event.rawY-startY)>slop) moved=true
                    if(moved) {
                        closePanel(); hideBubble.run()
                        val size=screen()
                        petParams.x=(x+event.rawX-startX).toInt().coerceIn(0,(size.first-petParams.width).coerceAtLeast(0))
                        petParams.y=(y+event.rawY-startY).toInt().coerceIn(0,(size.second-petParams.height).coerceAtLeast(0))
                        wm.updateViewLayout(pet,petParams)
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    if(moved) { savePosition(); pet.invalidate() }
                    else { pet.performClick(); if(panel==null) openPanel() else closePanel() }; true
                }
                MotionEvent.ACTION_CANCEL -> { if(moved) savePosition(); pet.invalidate(); true }
                else -> false
            }
        }
    }
    private fun screen(): Pair<Int,Int> {
        if(android.os.Build.VERSION.SDK_INT>=30) return wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        val m=android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }
    private fun savePosition() {
        val size=screen()
        app.config.left=petParams.x+petParams.width/2<size.first/2
        app.config.savePetPosition(size.first>size.second,
            petParams.x.toFloat()/(size.first-petParams.width).coerceAtLeast(1),
            petParams.y.toFloat()/(size.second-petParams.height).coerceAtLeast(1))
    }
    fun reposition() {
        val size=screen()
        val maxX=(size.first-petParams.width).coerceAtLeast(0)
        val maxY=(size.second-petParams.height).coerceAtLeast(0)
        val saved=app.config.petPosition(size.first>size.second)
        petParams.x=saved?.let { (it.first*maxX).toInt() } ?: if(app.config.left) service.dp(12) else maxX-service.dp(12)
        petParams.y=saved?.let { (it.second*maxY).toInt() } ?: maxY-service.dp(54)
        petParams.x=petParams.x.coerceIn(0,maxX); petParams.y=petParams.y.coerceIn(0,maxY)
        app.config.left=petParams.x+petParams.width/2<size.first/2
        runCatching { wm.updateViewLayout(pet,petParams) }
        pet.invalidate(); closePanel(); hideBubble.run()
    }
    fun message(text: String) {
        if(MainActivity.visible) return
        pet.bounce()
        if(panel!=null) { refresh(); return }
        handler.removeCallbacks(hideBubble); hideBubble.run()
        val view=TextView(service).apply {
            this.text=text; textSize=16f; setTextColor(Color.rgb(35,57,62)); maxLines=7
            setPadding(service.dp(16),service.dp(12),service.dp(16),service.dp(12))
            background=rounded(Color.rgb(244,251,249)); elevation=service.dp(6).toFloat()
            setOnClickListener { openPanel() }
        }
        val size=screen()
        val p=params(minOf(service.dp(320),size.first-service.dp(24)),WindowManager.LayoutParams.WRAP_CONTENT)
        p.x=if(app.config.left) service.dp(12) else size.first-p.width-service.dp(12)
        p.y=(petParams.y-service.dp(190)).coerceAtLeast(service.dp(30))
        bubble=view; wm.addView(view,p)
        handler.postDelayed(hideBubble,(5000L+text.length*90L).coerceAtMost(18_000))
    }
    fun status(text: String) { state?.text=text; pausedButton?.text=if(engine.paused) "继续" else "暂停"; refresh() }
    private fun refresh() {
        history?.text=app.store.recent(engine.sessionId,60).filter { it.kind=="user" || it.kind=="assistant" }
            .joinToString("\n\n") { "${if(it.kind=="user") "你" else "她"}  ${it.body}" }
        history?.let { view -> (view.parent as? ScrollView)?.post { (view.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN) } }
    }
    private fun openPanel() {
        if(panel!=null) return
        hideBubble.run()
        val root=LinearLayout(service).apply {
            orientation=LinearLayout.VERTICAL; setPadding(service.dp(16),service.dp(12),service.dp(16),service.dp(12))
            background=rounded(Color.rgb(248,250,247)); elevation=service.dp(8).toFloat(); isFocusableInTouchMode=true
        }
        val top=LinearLayout(service)
        state=TextView(service).apply { text=CaptureService.status; textSize=12f; setTextColor(Color.DKGRAY) }
        top.addView(state,LinearLayout.LayoutParams(0,service.dp(52),1f))
        top.addView(Button(service).apply { text="收起"; setOnClickListener { closePanel() } },LinearLayout.LayoutParams(service.dp(78),service.dp(52)))
        root.addView(top)
        history=TextView(service).apply { textSize=16f; setTextColor(Color.rgb(31,52,59)); setTextIsSelectable(true) }
        val scroll=ScrollView(service).apply { addView(history) }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val input=EditText(service).apply { hint="想说点什么…"; maxLines=3; textSize=16f; setSingleLine(false) }
        root.addView(input,LinearLayout.LayoutParams(-1,service.dp(70)))
        val row=LinearLayout(service)
        fun action(label: String, run: ()->Unit) = Button(service).apply { text=label; textSize=12f; setPadding(0,0,0,0); setOnClickListener { run() } }.also { row.addView(it,LinearLayout.LayoutParams(0,service.dp(48),1f)) }
        pausedButton=action(if(engine.paused) "继续" else "暂停") { engine.pause() }
        action("重试") { engine.retryReply() }
        action("发送") { if(engine.send(input.text.toString().trim())) { input.text.clear(); refresh() } else Toast.makeText(service,"请等待当前回复，或先输入文字",Toast.LENGTH_SHORT).show() }
        root.addView(row)
        val size=screen()
        val p=params(minOf(service.dp(420),size.first-service.dp(24)),minOf(service.dp(480),size.second-service.dp(100)))
        p.x=if(app.config.left) service.dp(12) else size.first-p.width-service.dp(12)
        p.y=(petParams.y-p.height-service.dp(6)).coerceAtLeast(service.dp(36))
        panel=root; panelParams=p; wm.addView(root,p); root.requestFocus(); refresh()
        input.setOnTouchListener { _, event ->
            if(event.action==MotionEvent.ACTION_DOWN) {
                p.flags=p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                wm.updateViewLayout(root,p)
                input.requestFocus()
            }
            false
        }
    }
    private fun closePanel() {
        panel?.let { view ->
            service.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0)
            remove(view)
        }
        panel=null; panelParams=null; history=null; state=null; pausedButton=null
    }
    private fun remove(view: View) { runCatching { wm.removeView(view) } }
    fun destroy() { handler.removeCallbacksAndMessages(null); closePanel(); hideBubble.run(); remove(pet); pet.dispose() }
}

class MascotView(context: Context, private val app: MateApp): View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val image=File(context.filesDir,"mascot.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    init { contentDescription="陪看伙伴，点击聊天，自由拖动" }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        if(app.config.left.xor(app.config.flip)) canvas.scale(-1f,1f,width/2f,height/2f)
        if(image!=null) {
            val factor=minOf(width.toFloat()/image.width,height.toFloat()/image.height)
            val w=image.width*factor; val h=image.height*factor
            canvas.drawBitmap(image,null,RectF((width-w)/2,height-h,(width+w)/2,height.toFloat()),paint)
        } else {
            // Original code-drawn placeholder, not artwork copied from the reference project.
            canvas.scale(width/100f,height/124f)
            paint.color=Color.rgb(92,156,171); canvas.drawOval(10f,43f,87f,105f,paint)
            val tail=Path().apply { moveTo(78f,74f); lineTo(98f,54f); quadTo(105f,87f,78f,92f); close() }
            canvas.drawPath(tail,paint)
            paint.color=Color.rgb(217,237,229); canvas.drawOval(12f,80f,73f,103f,paint)
            paint.color=Color.rgb(34,62,75); canvas.drawCircle(29f,68f,4f,paint)
            paint.color=Color.WHITE; canvas.drawCircle(28f,67f,1.2f,paint)
            paint.color=Color.rgb(229,154,154); canvas.drawOval(15f,77f,30f,82f,paint)
            paint.color=Color.rgb(126,189,201); canvas.drawOval(36f,25f,43f,40f,paint)
        }
        canvas.restore()
    }
    fun bounce() {
        animate().cancel(); pivotX=width/2f; pivotY=height.toFloat()
        animate().scaleX(1.05f).scaleY(.88f).setDuration(110).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(OvershootInterpolator(2f)).start()
        }.start()
    }
    fun dispose() { animate().cancel(); image?.recycle() }
}
