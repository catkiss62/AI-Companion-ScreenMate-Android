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
    private val dialogue = DialogueTracker()
    private var settleJob: Job? = null
    private var evidenceVersion = 0L
    val videoMode = mode.contains("连续视频")
    private var videoReplyFailure: Exception?=null
    val videoPipeline = if(videoMode) VideoPipeline(app,models,
        { kind,text,time -> app.store.add(sessionId,kind,text,time) },
        { clip -> videoReaction(clip) },onState) else null
    private suspend fun videoReaction(clip: VideoClip) {
        latestAt=SystemClock.elapsedRealtime()-(System.currentTimeMillis()-clip.end).coerceAtLeast(0)
        evidenceVersion++
        if(!app.config.replyEveryVideo) { maybeComment(true); return }
        while(!closed && !paused && app.config.replyEveryVideo) {
            replyJob?.join()
            currentCoroutineContext().ensureActive()
            if(closed || paused) return
            videoReplyFailure=null
            val task=reply(true,clip,true)
            task.join()
            currentCoroutineContext().ensureActive()
            if(task.isCancelled) continue // A user turn gets priority; this test reaction resumes after it.
            videoReplyFailure?.let { throw it }
            return
        }
    }
    val storyMode = mode.contains("无期迷途")
    val captureEpoch get() = policy.epoch
    private var lastHash: String? = null
    private var lastFrameAt = 0L
    var paused = false; private set
    var visible = true
    val busy get() = userReply
    // Local observation continues even during cloud requests, rate limiting or daily budget exhaustion.
    val canCapture get() = !closed && !paused && visible && !MainActivity.visible
    fun acceptsCapture(epoch: Long) = canCapture && policy.accepts(epoch)
    fun localText(text: String, capturedAt: Long, epoch: Long) {
        if(!storyMode || !acceptsCapture(epoch)) return
        CaptureDiagnostics.ocrText=text
        settleJob?.cancel()
        val line=dialogue.accept(text,capturedAt)
        if(line!=null) commitDialogue(line,capturedAt)
        else settleJob=scope.launch {
            // Static surfaces need not produce a second ImageReader frame.
            delay(1000)
            if(acceptsCapture(epoch)) dialogue.accept(text,SystemClock.elapsedRealtime())?.let { commitDialogue(it,capturedAt) }
        }
    }
    private fun commitDialogue(line: DialogueTracker.Line, capturedAt: Long) {
        latestAt=maxOf(latestAt,capturedAt)
        if(!line.extension) evidenceVersion++
        record("dialogue", "${if(line.extension) "同页文字补充（勿当作另一句）" else "本地识别对白（可能有错字或漏字）"}：\n${line.text}", capturedAt)
        CaptureDiagnostics.dialogueCount++
        onState("陪看中 · 已记录 ${CaptureDiagnostics.dialogueCount} 段对白")
        maybeComment(true)
    }
    private fun maybeComment(interesting: Boolean) {
        if(replyJob?.isActive != true && policy.mayComment(SystemClock.elapsedRealtime(),latestAt,interesting,app.config.commentSeconds*1000L))
            reply(proactive=true)
    }
    fun frame(base64: String, hash: String, capturedAt: Long = SystemClock.elapsedRealtime(), diagnostic: CaptureDiagnostics.Frame? = null) {
        val now = SystemClock.elapsedRealtime()
        if(videoMode || !canCapture || visionJob?.isActive == true || !policy.mayObserve(now) || app.store.usage("vision")>=app.config.dailyCap) return
        if(hash == lastHash && now-lastFrameAt<60_000) return
        val ticket = policy.epoch
        val versionAtCapture=evidenceVersion
        // Spacing is between request starts, not response completion plus another interval.
        policy.requested(now,app.config.intervalSeconds*1000L)
        CaptureDiagnostics.sent=diagnostic
        onState("正在补充画面理解 · 对白仍持续记录")
        visionJob = scope.launch {
            try {
                val observation = models.observe(base64,mode)
                if(!policy.accepts(ticket)) return@launch
                policy.succeeded()
                CaptureDiagnostics.visionMillis=SystemClock.elapsedRealtime()-now
                lastHash=hash; lastFrameAt=capturedAt
                if(capturedAt>latestAt) latestAt=capturedAt
                val novel = !TextBounds.sameEvidence(identity,observation.identity())
                if(novel) {
                    identity = observation.identity()
                    record("observation",observation.evidence(),capturedAt)
                    if(!storyMode) evidenceVersion++
                }
                onState("陪看中 · 对白 ${CaptureDiagnostics.dialogueCount} 段 · 云端识图 ${app.store.usage("vision")}/${app.config.dailyCap}")
                // A slow visual result must not provoke a reaction to a previous page.
                if(novel && (!storyMode || versionAtCapture==evidenceVersion)) maybeComment(observation.interesting)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                if(policy.accepts(ticket)) {
                    policy.failed(SystemClock.elapsedRealtime(),(e as? ApiFailure)?.retrySeconds)
                    onState("云端识图：${if(e is ApiFailure) e.message else "暂时失败"}；本地对白继续记录")
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
    private fun record(kind: String, text: String, capturedAt: Long? = null) {
        val wallTime = capturedAt?.let { System.currentTimeMillis()-(SystemClock.elapsedRealtime()-it) } ?: System.currentTimeMillis()
        app.store.add(sessionId,kind,text,wallTime)
        records++
        if(records % 30 == 0) app.archive(sessionId)
    }
    private fun context(proactive: Boolean, clip: VideoClip?=null, forced: Boolean=false): String {
        val age = if(latestAt == 0L) -1 else (SystemClock.elapsedRealtime() - latestAt)/1000
        val status = when { paused -> "已暂停，未观察当前画面"; !visible -> "分享的应用当前不可见"; age < 0 -> "尚无画面证据"; age > 60 -> "画面证据已过期，距采集${age}秒，不能当作当前画面"; else -> "最近一次有效观察距今${age}秒" }
        return JSONObject().put("task",if(proactive) "根据已观察的新节点决定简短主动反应" else "回复最后一条真实用户发言")
            .put("capture_status",status).put("mode",mode)
            .put("video_test",forced).put("target_video",clip?.label() ?: "")
            .put("continuous_video_story",videoPipeline?.story ?: "")
            .put("evidence_rule","dialogue 是本地 OCR，可能有错漏；同页补充不是新台词。按 time 理解先后，晚返回的 observation 可能早于此前记录。只能评论已观察剧情，不补全漏字。")
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
    private fun reply(proactive: Boolean, clip: VideoClip?=null, forced: Boolean=false): Job {
        val serial = ++replySerial
        val ticket = policy.epoch
        val observedAt = latestAt
        val version = evidenceVersion
        val replyStarted = SystemClock.elapsedRealtime()
        if(proactive) policy.commented(SystemClock.elapsedRealtime())
        userReply = !proactive
        replyJob = scope.launch {
            try {
                onState(if(proactive) "在想刚才的剧情…" else "正在回复…")
                val data = context(proactive,clip,forced)
                var fallback = false
                val response = try { models.reply(data,proactive) } catch(e: CancellationException) { throw e }
                catch(_: Exception) { fallback = true; models.reply(data,proactive,true) }
                if(closed || ticket != policy.epoch || serial != replySerial) return@launch
                CaptureDiagnostics.replyMillis=SystemClock.elapsedRealtime()-replyStarted
                if(proactive && !forced && !ReactionFreshness.accepts(storyMode,evidenceVersion-version)) { onState("剧情已推进，略过过时评论"); return@launch }
                if(proactive && !forced && (!policy.active || !visible || MainActivity.visible || SystemClock.elapsedRealtime()-observedAt > 60_000)) return@launch
                if(forced && response.trim()=="SILENT") throw ApiFailure(-1)
                if(!proactive || response.trim() != "SILENT") {
                    val displayed=if(forced && clip!=null) "【${clip.label()} · 延迟${(System.currentTimeMillis()-clip.end).coerceAtLeast(0)/1000}秒】\n$response" else response
                    record("assistant",displayed)
                    onMessage(displayed)
                }
                onState(if(fallback) "本次由 DeepSeek 兜底回复" else if(paused) "已暂停观察" else "陪看中")
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(forced) videoReplyFailure=e; if(!closed && ticket == policy.epoch && serial == replySerial) onState("回复失败，可重试：${if(e is ApiFailure) e.message else "请检查接口配置或网络"}") }
            finally { if(ticket == policy.epoch && serial == replySerial) userReply = false }
        }
        return replyJob!!
    }
    fun retryReply() {
        if(closed || userReply) return
        if(app.store.lastChat(sessionId)?.kind != "user") { onState("没有待重试的用户消息"); return }
        replyJob?.cancel(); reply(false)
    }
    fun pause() {
        if(closed) return
        paused = !paused
        videoPipeline?.pause(paused)
        policy.invalidate(); visionJob?.cancel(); replyJob?.cancel(); userReply = false
        settleJob?.cancel(); dialogue.reset()
        if(!paused) { policy.start(); lastHash=null; latestAt=0 }
        onState(if(paused) "已暂停 · 不采集、不主动说话" else "已继续，等待新画面")
    }
    fun stop() {
        if(closed) return
        closed=true; videoPipeline?.close(); policy.invalidate(); scope.cancel()
        app.store.finish(sessionId); app.archive(sessionId)
    }
}
