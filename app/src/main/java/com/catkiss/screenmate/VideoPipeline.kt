package com.catkiss.screenmate

import kotlinx.coroutines.*
import java.util.ArrayDeque
import java.io.File

object VideoDiagnostics {
    var latest: VideoClip?=null
    var sent: VideoClip?=null
    var recorded=0
    var requests=0
    var recognized=0
    var integrated=0
    var lastFailure=""
    val gapHistory=mutableListOf<String>()
    var completed=0
    var queue=0
    var current=0
    var observedEnd=0L
    var visionMs=0L
    var storyMs=0L
    var totalMs=0L
    var tokens=0
    var gaps=0
    var stage="等待视频"
    var audio="未启用播放音轨"
    var evidence=""
    var story=""
    fun reset() {
        recorded=0; requests=0; recognized=0; integrated=0; lastFailure=""; gapHistory.clear(); ApiDiagnostics.clear()
        latest=null; sent=null; completed=0; queue=0; current=0; observedEnd=0; visionMs=0; storyMs=0
        totalMs=0; tokens=0; gaps=0; stage="等待视频"; audio="未启用播放音轨"; evidence=""; story=""
    }
    fun preserve(clip: VideoClip, directory: File, sentCopy: Boolean) {
        directory.mkdirs()
        val copy=clip.copy(file=File(directory,if(sentCopy) "sent.mp4" else "latest.mp4"))
        clip.file.copyTo(copy.file,true)
        if(sentCopy) sent=copy else latest=copy
    }
}

/** Three ordered lanes overlap network work. Total buffered clips are still bounded. */
class VideoPipeline(private val app: MateApp, private val gateway: ModelGateway,
                    private val record: (String,String,Long)->Unit,
                    private val react: suspend (VideoClip,String)->Unit,
                    private val state: (String)->Unit) {
    private data class Item(val clip: VideoClip,var evidence: VideoEvidence?=null,var story: String?=null)
    private val queue=ArrayDeque<Item>()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val jobs=mutableMapOf<String,Job>()
    private var active=true
    private var generation=0
    private var failed=false
    private var backoffUntil=0L
    private val gapNotes=mutableListOf<String>()
    var story=""; private set
    val blockedReason: String get() = when {
        !active -> "视频处理已暂停"
        failed -> "${VideoDiagnostics.lastFailure.ifBlank { VideoDiagnostics.stage }}；暂停新增录像，需手动重试"
        app.store.usage("vision")>=app.config.dailyCap -> "达到本机识图日上限 ${app.config.dailyCap} 次（不是服务端429）；请调整设置后重试"
        queue.size>=6 -> "处理积压：待处理 ${queue.size} 段，暂停新增录像；队列减少后自动恢复"
        else -> ""
    }
    val canRecord get() = blockedReason.isEmpty()
    fun offer(clip: VideoClip) {
        if(!active) { clip.file.delete(); return }
        if(queue.size>=7) { gap("编码片段超出缓冲上限，未识别 ${clip.label()}"); clip.file.delete(); return }
        queue.add(Item(clip)); VideoDiagnostics.queue=queue.size
        kick()
    }
    fun gap(reason: String) {
        VideoDiagnostics.gaps++
        VideoDiagnostics.gapHistory.add(reason)
        if(VideoDiagnostics.gapHistory.size>10) VideoDiagnostics.gapHistory.removeAt(0)
        gapNotes.add(reason)
        story=(story+"\n观察空缺：$reason").takeLast(12000)
        record("video_gap",reason,System.currentTimeMillis())
        state(reason)
    }
    fun retry() {
        if(System.currentTimeMillis()<backoffUntil) { state("视频限流等待中，约${(backoffUntil-System.currentTimeMillis()+999)/1000}秒后可手动重试"); return }
        failed=false; kick()
    }
    private fun stage(lane: String,item: Item,block: suspend ()->Unit) {
        if(jobs[lane]?.isActive==true) return
        val ticket=generation
        val task=scope.launch(start=CoroutineStart.LAZY) {
            try { block() }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                if(ticket==generation) {
                    failed=true
                    backoffUntil=System.currentTimeMillis()+(if(e is ApiFailure && e.code==429) maxOf(15L,e.retrySeconds ?: 30L)*1000 else 0)
                    VideoDiagnostics.stage="视频 #${item.clip.sequence} $lane 失败：${if(e is ApiFailure) e.message else "请检查接口或网络"}；片段保留，在诊断页重试"
                    VideoDiagnostics.lastFailure=VideoDiagnostics.stage
                    state(VideoDiagnostics.stage); record("video_error",VideoDiagnostics.stage,System.currentTimeMillis())
                }
            } finally {
                if(ticket==generation) { jobs.remove(lane); scope.launch { yield(); kick() } }
            }
        }
        jobs[lane]=task; task.start()
    }
    private fun kick() {
        if(!active || failed || queue.isEmpty()) return
        val ticket=generation
        queue.firstOrNull { it.evidence==null }?.let { item -> stage("识别",item) {
            val clip=item.clip
            if(app.store.usage("vision")>=app.config.dailyCap) {
                failed=true; VideoDiagnostics.stage="达到本机视频/识图日上限；片段保留，请调整后重试"; VideoDiagnostics.lastFailure=VideoDiagnostics.stage; state(VideoDiagnostics.stage)
                return@stage
            }
            VideoDiagnostics.current=clip.sequence
            VideoDiagnostics.stage="识别 ${clip.label()}"; state(VideoDiagnostics.stage)
            withContext(Dispatchers.IO) { VideoDiagnostics.preserve(clip,File(app.cacheDir,"video-preview"),true) }
            val start=System.currentTimeMillis()
            val previousEvidence=queue.takeWhile { it!==item }.mapNotNull { it.evidence?.text }.takeLast(2).joinToString("\n")
            VideoDiagnostics.requests++
            val evidence=gateway.observeVideo(clip,(story+"\n"+previousEvidence).takeLast(12000))
            currentCoroutineContext().ensureActive(); if(ticket!=generation) return@stage
            item.evidence=evidence
            VideoDiagnostics.recognized++
            VideoDiagnostics.visionMs=System.currentTimeMillis()-start
            VideoDiagnostics.tokens=evidence.inputTokens; VideoDiagnostics.evidence=evidence.text
            record("video_observation",evidence.text,clip.start)
        } }
        queue.firstOrNull { it.story==null }?.takeIf { it.evidence!=null }?.let { item -> stage("整理",item) {
            val start=System.currentTimeMillis()
            val gapCursor=gapNotes.size
            val merged=gateway.integrateVideo(story,item.evidence!!.text)
            currentCoroutineContext().ensureActive(); if(ticket!=generation) return@stage
            val withGaps=(merged+gapNotes.drop(gapCursor).joinToString("",prefix="") { "\n观察空缺：$it" }).takeLast(12000)
            VideoDiagnostics.integrated++
            item.story=withGaps; story=withGaps
            VideoDiagnostics.storyMs=System.currentTimeMillis()-start
            VideoDiagnostics.story=story; VideoDiagnostics.observedEnd=item.clip.end
            record("video_story","${item.clip.label()} 后的连续剧情：\n$story",item.clip.end)
        } }
        queue.firstOrNull()?.takeIf { it.story!=null }?.let { item -> stage("回复",item) {
            val clip=item.clip
            react(clip,item.story!!)
            currentCoroutineContext().ensureActive(); if(ticket!=generation) return@stage
            VideoDiagnostics.completed++
            VideoDiagnostics.totalMs=(System.currentTimeMillis()-clip.end).coerceAtLeast(0)
            check(queue.removeFirst()===item)
            clip.file.delete(); VideoDiagnostics.queue=queue.size
            VideoDiagnostics.stage="已完成视频 #${clip.sequence} · 片尾至完成 ${VideoDiagnostics.totalMs/1000.0}秒 · 待处理${queue.size}段"
            state(VideoDiagnostics.stage)
        } }
    }
    fun pause(paused: Boolean) {
        generation++; active=!paused; jobs.values.toList().forEach { it.cancel() }; jobs.clear(); failed=false
        if(queue.isNotEmpty()) gap("暂停/结束取消 ${queue.size} 个尚未完成的视频片段，未完成部分不视为已观看")
        queue.forEach { it.clip.file.delete() }; queue.clear(); VideoDiagnostics.queue=0
    }
    fun close() { pause(true); scope.cancel() }
}
