package com.catkiss.screenmate

import kotlinx.coroutines.*
import java.util.ArrayDeque
import java.io.File

object VideoDiagnostics {
    var latest: VideoClip?=null
    var sent: VideoClip?=null
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

/** Bounded FIFO. A failed segment stays at its stage for explicit retry, never silently skipped. */
class VideoPipeline(private val app: MateApp, private val gateway: ModelGateway,
                    private val record: (String,String,Long)->Unit,
                    private val react: suspend (VideoClip)->Unit,
                    private val state: (String)->Unit) {
    private data class Item(val clip: VideoClip,var evidence: VideoEvidence?=null,var story: String?=null)
    private val queue=ArrayDeque<Item>()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job: Job?=null
    private var active=true
    private var generation=0
    private var failed=false
    private var backoffUntil=0L
    var story=""; private set
    // At most 6 complete clips plus the currently recording fragment (about 35 seconds).
    val canRecord get() = active && !failed && queue.size<6 && app.store.usage("vision")<app.config.dailyCap
    fun offer(clip: VideoClip) {
        if(!active) { clip.file.delete(); return }
        if(queue.size>=7) { gap("编码片段超出缓冲上限，未识别 ${clip.label()}"); clip.file.delete(); return }
        queue.add(Item(clip)); VideoDiagnostics.queue=queue.size
        run()
    }
    fun gap(reason: String) {
        VideoDiagnostics.gaps++
        story=(story+"\n观察空缺：$reason").takeLast(12000)
        record("video_gap",reason,System.currentTimeMillis())
        state(reason)
    }
    fun retry() {
        if(System.currentTimeMillis()<backoffUntil) { state("视频限流退避中，请稍后重试"); return }
        failed=false; run()
    }
    private fun run() {
        if(!active || failed || job?.isActive==true || queue.isEmpty()) return
        val ticket=generation
        job=scope.launch {
            while(active && !failed && queue.isNotEmpty() && generation==ticket) {
                val item=queue.first(); val clip=item.clip
                try {
                    VideoDiagnostics.current=clip.sequence
                    if(item.evidence==null) {
                        if(app.store.usage("vision")>=app.config.dailyCap) { failed=true; state("达到本机视频/识图日上限；待处理片段保留，请调整上限后重试"); break }
                        VideoDiagnostics.stage="识别 ${clip.label()}"; state(VideoDiagnostics.stage)
                        withContext(Dispatchers.IO) { VideoDiagnostics.preserve(clip,File(app.cacheDir,"video-preview"),true) }
                        val start=System.currentTimeMillis()
                        val evidence=gateway.observeVideo(clip,story)
                        ensureActive(); if(ticket!=generation) break
                        item.evidence=evidence
                        VideoDiagnostics.visionMs=System.currentTimeMillis()-start
                        VideoDiagnostics.tokens=evidence.inputTokens
                        VideoDiagnostics.evidence=evidence.text
                        record("video_observation",evidence.text,clip.start)
                    }
                    if(item.story==null) {
                        VideoDiagnostics.stage="整理视频 #${clip.sequence} 的连续剧情"; state(VideoDiagnostics.stage)
                        val start=System.currentTimeMillis()
                        val merged=gateway.integrateVideo(story,item.evidence!!.text)
                        ensureActive(); if(ticket!=generation) break
                        item.story=merged; story=merged
                        VideoDiagnostics.storyMs=System.currentTimeMillis()-start
                        VideoDiagnostics.story=story; VideoDiagnostics.observedEnd=clip.end
                        record("video_story","${clip.label()} 后的连续剧情：\n$story",clip.end)
                    }
                    react(clip)
                    ensureActive(); if(ticket!=generation) break
                    VideoDiagnostics.completed++
                    VideoDiagnostics.totalMs=(System.currentTimeMillis()-clip.end).coerceAtLeast(0)
                    queue.removeFirst(); clip.file.delete(); VideoDiagnostics.queue=queue.size
                    VideoDiagnostics.stage="已完成视频 #${clip.sequence} · 片尾至完成 ${VideoDiagnostics.totalMs/1000.0}秒 · 待处理${queue.size}段"
                    state(VideoDiagnostics.stage)
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                    failed=true
                    backoffUntil=System.currentTimeMillis()+(if(e is ApiFailure && e.code==429) maxOf(15L,e.retrySeconds ?: 30L)*1000 else 0)
                    VideoDiagnostics.stage="视频 #${clip.sequence} 处理失败：${if(e is ApiFailure) e.message else "请检查接口或网络"}；片段保留，在诊断页重试"
                    state(VideoDiagnostics.stage)
                    record("video_error",VideoDiagnostics.stage,System.currentTimeMillis())
                }
            }
        }
    }
    fun pause(paused: Boolean) {
        generation++; active=!paused; job?.cancel(); job=null; failed=false
        if(queue.isNotEmpty()) gap("暂停/结束取消 ${queue.size} 个尚未完成的视频片段，未完成部分不视为已观看")
        queue.forEach { it.clip.file.delete() }; queue.clear(); VideoDiagnostics.queue=0
    }
    fun close() { pause(true); scope.cancel() }
}
