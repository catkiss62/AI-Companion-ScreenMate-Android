package com.catkiss.screenmate

import android.os.SystemClock
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class WatchEngine(private val app: MateApp, val sessionId: Long, private val mode: String,
                  private val onState: (String)->Unit, private val onMessage: (String)->Unit,
                  private val models: ModelGateway = app.models) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val policy = WatchPolicy().apply { start() }
    private var visionJob: Job? = null
    private var replyJob: Job? = null
    private var userReply = false
    private var replySerial = 0L
    private var closed = false
    private var latestAt = 0L
    private var identity = ""
    private var records = 0
    private var lastHash: String? = null
    private var lastFrameAt = 0L
    var paused = false; private set
    var visible = true
    val busy get() = userReply
    val canCapture get() = !closed && !paused && visible && !MainActivity.visible && visionJob?.isActive != true &&
        policy.mayObserve(SystemClock.elapsedRealtime()) && app.store.usage("vision") < app.config.dailyCap

    fun frame(base64: String, hash: String) {
        if(!canCapture) return
        val now = SystemClock.elapsedRealtime()
        if(hash == lastHash && now - lastFrameAt < 60_000) {
            policy.observed(now,app.config.intervalSeconds * 1000L); return
        }
        lastHash = hash; lastFrameAt = now
        val ticket = policy.epoch
        onState("正在看画面…")
        visionJob = scope.launch {
            try {
                val observation = models.observe(base64,mode)
                if(!policy.accepts(ticket)) return@launch
                policy.observed(SystemClock.elapsedRealtime(),app.config.intervalSeconds * 1000L)
                // Timestamp is capture time, not network completion time.
                latestAt = now
                val novel = !TextBounds.sameEvidence(identity,observation.identity())
                if(novel) {
                    identity = observation.identity()
                    record("observation",observation.evidence())
                }
                onState("陪看中 · 识图 ${app.store.usage("vision")}/${app.config.dailyCap} 次（本机上限）")
                if(novel && replyJob?.isActive != true && policy.mayComment(SystemClock.elapsedRealtime(),latestAt,observation.interesting,app.config.commentSeconds * 1000L)) {
                    reply(proactive=true)
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                if(policy.accepts(ticket)) {
                    policy.failed(SystemClock.elapsedRealtime(),(e as? ApiFailure)?.retrySeconds)
                    onState("识图：${if(e is ApiFailure) e.message else "暂时失败"}；稍后重试")
                }
            }
        }
    }
    fun send(text: String): Boolean {
        if(closed || text.isBlank() || userReply) return false
        replyJob?.cancel() // User turn preempts an unspoken automatic comment.
        policy.user(SystemClock.elapsedRealtime())
        record("user",text.take(4000))
        reply(false)
        return true
    }
    private fun record(kind: String, text: String) {
        app.store.add(sessionId,kind,text)
        records++
        if(records % 30 == 0) app.archive(sessionId)
    }
    private fun context(proactive: Boolean): String {
        val age = if(latestAt == 0L) -1 else (SystemClock.elapsedRealtime() - latestAt)/1000
        val status = when { paused -> "已暂停，未观察当前画面"; !visible -> "分享的应用当前不可见"; age < 0 -> "尚无画面证据"; age > 60 -> "画面证据已过期，距采集${age}秒，不能当作当前画面"; else -> "最近一次画面采集距今${age}秒" }
        return JSONObject().put("task",if(proactive) "根据已观察的新节点决定简短主动反应" else "回复最后一条真实用户发言")
            .put("capture_status",status).put("mode",mode)
            .put("current_user_message",if(proactive) "" else app.store.lastChat(sessionId)?.takeIf { it.kind=="user" }?.body ?: "")
            .put("session_summary",app.store.session(sessionId)?.summary ?: "")
            .put("past_session_memories",app.store.memories(sessionId))
            .put("recent_records",JSONArray().apply {
                var budget=24000
                val rows=app.store.recent(sessionId).asReversed().mapNotNull { entry ->
                    if(budget<=0) null else { val text=entry.body.take(minOf(6000,budget)); budget-=text.length; entry.copy(body=text) }
                }.asReversed()
                rows.forEach { put(JSONObject().put("kind",it.kind).put("time",it.time).put("text",it.body)) }
            }).toString()
    }
    private fun reply(proactive: Boolean) {
        val serial = ++replySerial
        val ticket = policy.epoch
        val observedAt = latestAt
        if(proactive) policy.commented(SystemClock.elapsedRealtime())
        userReply = !proactive
        replyJob = scope.launch {
            try {
                onState(if(proactive) "在想刚才的剧情…" else "正在回复…")
                val data = context(proactive)
                var fallback = false
                val response = try { models.reply(data,proactive) } catch(e: CancellationException) { throw e }
                catch(_: Exception) { fallback = true; models.reply(data,proactive,true) }
                if(closed || ticket != policy.epoch || serial != replySerial) return@launch
                if(proactive && (!policy.active || !visible || MainActivity.visible || SystemClock.elapsedRealtime()-observedAt > 60_000)) return@launch
                if(!proactive || response.trim() != "SILENT") {
                    record("assistant",response)
                    onMessage(response)
                }
                onState(if(fallback) "本次由 DeepSeek 兜底回复" else if(paused) "已暂停观察" else "陪看中")
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(!closed && ticket == policy.epoch && serial == replySerial) onState("回复失败，可重试：${if(e is ApiFailure) e.message else "请检查接口配置或网络"}") }
            finally { if(ticket == policy.epoch && serial == replySerial) userReply = false }
        }
    }
    fun retryReply() {
        if(closed || userReply) return
        if(app.store.lastChat(sessionId)?.kind != "user") { onState("没有待重试的用户消息"); return }
        replyJob?.cancel(); reply(false)
    }
    fun pause() {
        if(closed) return
        paused = !paused
        policy.invalidate(); visionJob?.cancel(); replyJob?.cancel(); userReply = false
        if(!paused) { policy.start(); lastHash=null; latestAt=0 }
        onState(if(paused) "已暂停 · 不采集、不主动说话" else "已继续，等待新画面")
    }
    fun stop() {
        if(closed) return
        closed=true; policy.invalidate(); scope.cancel()
        app.store.finish(sessionId); app.archive(sessionId)
    }
}
