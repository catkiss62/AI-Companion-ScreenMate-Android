package com.catkiss.screenmate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.projection.MediaProjection
import android.os.*
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import kotlin.math.abs

/** One continuous encoder; each MP4 begins on a keyframe. No projection restart at boundaries. */
data class VideoClip(val file: File, val start: Long, val end: Long, val audio: Boolean,
                     val peak: Int, val width: Int, val height: Int, val sequence: Int = 0) {
    fun label() = "视频 #$sequence · ${java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.CHINA).format(java.util.Date(start))}–${java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.CHINA).format(java.util.Date(end))}"
    fun sound() = if(!audio) "无音轨，不能声称听见声音" else if(peak<32) "含音轨但接近静音；不代表来源允许采集" else "含播放音轨（不使用麦克风），峰值 $peak；具体内容以识别为准"
}

class VideoCapture(private val context: Context, projection: MediaProjection?, val width: Int, val height: Int,
                   audioWanted: Boolean, private val onClip: (VideoClip)->Unit, private val onError: (String)->Unit) {
    private val thread=HandlerThread("ScreenMate-video").apply { start() }
    private val worker=Handler(thread.looper)
    private val main=Handler(Looper.getMainLooper())
    private val video=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var audioCodec: MediaCodec?=null
    private var audioRecord: AudioRecord?=null
    private var videoFormat: MediaFormat?=null
    private var audioFormat: MediaFormat?=null
    private var muxer: MediaMuxer?=null
    private var path: File?=null
    private var videoTrack=-1
    private var audioTrack=-1
    private var segmentStart=-1L
    private var lastVideo=-1L
    private var segmentPeak=0
    private var audioBase=-1L
    private var audioSamples=0L
    private var initializedAt=SystemClock.elapsedRealtime()
    private val clockOffset=System.currentTimeMillis()-System.nanoTime()/1_000_000
    private data class Sample(val data: ByteArray,val pts: Long,val flags: Int)
    private val sound=ArrayDeque<Sample>()
    @Volatile private var closing=false
    val surface: Surface
    var audioStatus="未启用播放音轨"; private set
    init {
        try {
            video.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE,1_800_000)
                setInteger(MediaFormat.KEY_FRAME_RATE,10)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,100_000)
                if(Build.VERSION.SDK_INT>=29) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER,10f)
            },null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface=video.createInputSurface(); video.start()
            if(audioWanted) setupAudio(projection)
        } catch(e: Exception) { runCatching { video.release() }; thread.quitSafely(); throw e }
    }
    fun start() { worker.post(pump) }
    private fun setupAudio(projection: MediaProjection?) {
        if(Build.VERSION.SDK_INT<29 || projection==null || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            audioStatus="未获得播放音频权限，当前无音轨"; return
        }
        try {
            val playback=AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .excludeUid(android.os.Process.myUid()).build()
            audioRecord=AudioRecord.Builder().setAudioPlaybackCaptureConfig(playback)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(8192,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)*4)).build()
            check(audioRecord!!.state==AudioRecord.STATE_INITIALIZED)
            audioCodec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,16000,1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE,48000)
                },null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); start()
            }
            audioRecord!!.startRecording(); audioStatus="正在尝试采集播放音轨（禁止录音的应用可能返回静音）"
        } catch(_: Exception) {
            runCatching { audioRecord?.release() }; audioRecord=null
            runCatching { audioCodec?.release() }; audioCodec=null
            audioStatus="播放音频采集不可用，当前无音轨"
        }
    }
    private val pump=object: Runnable {
        override fun run() {
            if(closing) return
            try {
                feedAudio(); audioCodec?.let { drain(it,false) }; drain(video,true)
                if(SystemClock.elapsedRealtime()-initializedAt>12_000 && lastVideo<0) error("no frames")
                if(segmentStart>=0 && lastVideo-segmentStart>8_000_000) error("missing keyframe")
                worker.postDelayed(this,20)
            } catch(_: Exception) {
                main.post { onError("视频编码中断，请查看诊断后重试采集；此段未保存") }
                close()
            }
        }
    }
    private fun feedAudio() {
        val codec=audioCodec ?: return
        val record=audioRecord ?: return
        val input=codec.dequeueInputBuffer(0)
        if(input<0) return
        val buffer=codec.getInputBuffer(input)!!; buffer.clear()
        val pcm=ByteArray(minOf(2048,buffer.remaining()))
        val n=record.read(pcm,0,pcm.size,AudioRecord.READ_NON_BLOCKING)
        if(n<0) error("audio read")
        if(audioBase<0 && n>0) audioBase=System.nanoTime()/1000-n/2*1_000_000L/16000
        var p=0
        while(p+1<n) {
            val value=((pcm[p].toInt() and 255) or (pcm[p+1].toInt() shl 8)).toShort().toInt()
            segmentPeak=maxOf(segmentPeak,abs(value)); p+=2
        }
        buffer.put(pcm,0,n.coerceAtLeast(0))
        val pts=if(audioBase<0) System.nanoTime()/1000 else audioBase+audioSamples*1_000_000/16000
        codec.queueInputBuffer(input,0,n.coerceAtLeast(0),pts,0)
        audioSamples+=n.coerceAtLeast(0)/2
    }
    private fun drain(codec: MediaCodec,isVideo: Boolean) {
        val info=MediaCodec.BufferInfo()
        repeat(80) {
            val index=codec.dequeueOutputBuffer(info,0)
            if(index==MediaCodec.INFO_TRY_AGAIN_LATER) return
            if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if(isVideo) videoFormat=codec.outputFormat else audioFormat=codec.outputFormat
            } else if(index>=0) {
                try {
                    if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                        val buffer=codec.getOutputBuffer(index)!!
                        buffer.position(info.offset); buffer.limit(info.offset+info.size)
                        val bytes=ByteArray(info.size); buffer.get(bytes)
                        val sample=Sample(bytes,info.presentationTimeUs,info.flags)
                        if(isVideo) videoSample(sample) else {
                            sound.add(sample)
                            while(sound.size>300) sound.removeFirst()
                        }
                    }
                } finally { codec.releaseOutputBuffer(index,false) }
            }
        }
    }
    private fun videoSample(sample: Sample) {
        if(closing) return
        val key=sample.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME!=0
        if(videoFormat==null || (audioCodec!=null && audioFormat==null)) return
        if(muxer==null) {
            if(!key) return
            begin(sample.pts)
        } else if(key && sample.pts-segmentStart>=5_000_000) {
            flushAudio(sample.pts)
            finish(sample.pts,true)
            begin(sample.pts)
        }
        lastVideo=sample.pts
        write(videoTrack,sample)
        flushAudio(sample.pts)
    }
    private fun begin(pts: Long) {
        segmentStart=pts; segmentPeak=0
        val dir=File(context.cacheDir,"video-segments").apply { mkdirs() }
        path=File.createTempFile("clip-",".mp4",dir)
        muxer=MediaMuxer(path!!.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
            videoTrack=it.addTrack(videoFormat!!)
            audioTrack=audioFormat?.let { f -> it.addTrack(f) } ?: -1
            it.start()
        }
        while(sound.isNotEmpty() && sound.first().pts<pts) sound.removeFirst()
    }
    private fun write(track: Int,sample: Sample) {
        if(track<0 || sample.pts<segmentStart) return
        muxer?.writeSampleData(track,ByteBuffer.wrap(sample.data),MediaCodec.BufferInfo().apply {
            set(0,sample.data.size,sample.pts-segmentStart,sample.flags)
        })
    }
    private fun flushAudio(until: Long) {
        while(sound.isNotEmpty() && sound.first().pts<until) write(audioTrack,sound.removeFirst())
    }
    private fun finish(end: Long,deliver: Boolean) {
        val m=muxer ?: return
        muxer=null
        val file=path!!; path=null
        val good=runCatching { m.stop() }.isSuccess
        runCatching { m.release() }
        if(good && deliver && file.length()>0) {
            val clip=VideoClip(file,clockOffset+segmentStart/1000,clockOffset+end/1000,audioTrack>=0,segmentPeak,width,height)
            main.post { onClip(clip) }
        } else file.delete()
        segmentStart=-1
    }
    fun close() {
        if(closing) return
        closing=true
        worker.post {
            worker.removeCallbacks(pump)
            finish(lastVideo,false) // Partial fragment is deliberately not submitted after pause/rotation.
            runCatching { audioRecord?.stop() }; runCatching { audioRecord?.release() }
            runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }
            runCatching { video.stop() }; runCatching { video.release() }; runCatching { surface.release() }
            thread.quitSafely()
        }
    }
}
