package io.mo.glassmic.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.audio.BufferedPcmSource
import io.mo.glassmic.audio.FileAudioSource
import io.mo.glassmic.audio.Pcm16Converter
import io.mo.glassmic.audio.SharedPcmPublisher
import io.mo.glassmic.audio.SilenceSource
import io.mo.glassmic.audio.tts.PcmSink
import io.mo.glassmic.audio.tts.TtsRequest
import io.mo.glassmic.audio.tts.TtsSynthesizerFactory
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.memory.MemoryPressure
import io.mo.glassmic.memory.MemoryPressureBus
import io.mo.glassmic.memory.MemoryReleasable
import io.mo.glassmic.proto.PlaybackPolicy as ProtoPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 UI 的"设为当前音源"动作衔接到音频管线。
 *
 * - 修改 RuntimeStateHolder 当前音源
 * - 通知 SharedPcmPublisher 切换 source
 * - 持久化当前选中 ID 到 ConfigStore（用于重启后恢复显示）
 *
 * 不负责 BootGate / SafeMode 判断——这些在 EffectiveSourceResolver 里。
 */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val publisher: SharedPcmPublisher,
    private val resolver: AudioFileResolver,
    private val configStore: ConfigStore,
    private val runtime: RuntimeStateHolder,
    private val dao: AudioDao,
    private val ttsFactory: TtsSynthesizerFactory
) : MemoryReleasable {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var speakerJob: Job? = null
    @Volatile private var speakerTrack: AudioTrack? = null
    @Volatile private var filePreviewPlayer: MediaPlayer? = null
    /** TTS 扬声器试听用的 PCM 快照，便于 seek 后从偏移处重播。 */
    @Volatile private var speakerPcm: ByteArray? = null
    /** 配置缓存：pause/resume 等非挂起路径也会读。未写入过时默认开。 */
    @Volatile private var speakerPreviewEnabledCache: Boolean = true
    @Volatile private var loopPlaybackCache: Boolean = true

    init {
        // 本类持有整个进程里最大的一块可丢弃内存（TTS 生成的 PCM），登记到内存压力总线
        MemoryPressureBus.register(this)
        // 目标 App 一开麦就停本机试听，减少「语音被占用」类冲突
        publisher.onFirstConsumerAttached = {
            GlassLog.b("Playback") { "录音客户端接入，停止扬声器试听" }
            stopSpeakerPreview()
        }
        scope.launch {
            configStore.flow.collect { cfg ->
                speakerPreviewEnabledCache = if (cfg.floatingWindow.hasSpeakerPreviewEnabled()) {
                    cfg.floatingWindow.speakerPreviewEnabled
                } else {
                    true
                }
                loopPlaybackCache = cfg.playbackPolicy == ProtoPolicy.LOOP
                if (!speakerPreviewEnabledCache) stopSpeakerPreview()
            }
        }
    }

    /** 本机扬声器试听是否开启。配置未写过时默认开。 */
    fun isSpeakerPreviewEnabled(): Boolean = speakerPreviewEnabledCache

    /** 开关关掉后立刻停掉本机试听（虚拟麦不受影响）。 */
    fun stopSpeakerPreviewIfDisabled() {
        if (!isSpeakerPreviewEnabled()) stopSpeakerPreview()
    }

    /** 立刻停掉本机扬声器试听（虚拟麦继续）。 */
    fun stopSpeakerPreviewNow() = stopSpeakerPreview()

    /** 设置某个片段为当前虚拟麦克风音源。文件不存在则自动清掉数据库记录并返回 false。 */
    suspend fun setCurrentClip(clipId: String, startPaused: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val clip = dao.findClip(clipId) ?: return@withContext false
        val file = resolver.fileFor(clip.relativePath)
        if (!file.exists()) {
            GlassLog.b("Playback") { "音频文件丢失，清理记录: ${clip.relativePath}" }
            dao.deleteClip(clipId)
            return@withContext false
        }
        val src = runCatching { FileAudioSource(clip.toModel(), file.absolutePath) }
            .onFailure { GlassLog.b("Playback") { "FileAudioSource 创建失败: ${it.message}" } }
            .getOrNull() ?: return@withContext false

        publisher.setSource(src, groupId = clip.groupId, audioId = clip.id)
        publisher.setPaused(startPaused)
        configStore.update {
            it.setCurrentGroupId(clip.groupId)
            it.setCurrentAudioId(clip.id)
        }
        // 本地文件：MediaPlayer 同步扬声器试听（与虚拟麦解码互不干扰）
        startFileSpeakerPreview(file.absolutePath, startPaused = startPaused)
        true
    }

    // ============ 文字转语音（先生成后播放） ============
    enum class TtsGen { IDLE, GENERATING, READY, FAILED }

    private val _ttsGen = MutableStateFlow(TtsGen.IDLE)
    /** 生成状态：供悬浮窗决定「播放」按钮是否可用。 */
    val ttsGen: StateFlow<TtsGen> = _ttsGen.asStateFlow()

    @Volatile private var generatedTtsPcm: ByteArray? = null

    /**
     * 生成 [text] 的语音并缓存为主格式 PCM（不立即播放）。成功返回 true。
     * 之后调用 [playGeneratedTts] 才真正喂给目标 App，可重复播放。
     */
    suspend fun generateTts(text: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext false
        _ttsGen.value = TtsGen.GENERATING
        generatedTtsPcm = null
        val ttsCfg = configStore.current().tts
        val synth = ttsFactory.current()
        val req = TtsRequest(
            text = trimmed,
            rate = ttsCfg.speechRate.takeIf { it > 0f } ?: 1f,
            pitch = ttsCfg.pitch.takeIf { it > 0f } ?: 1f,
            voice = ttsCfg.voice
        )
        val pcm = withTimeoutOrNull(60_000L) { synthesizeToMasterPcm(synth, req) }
        if (pcm != null && pcm.isNotEmpty()) {
            val peak = pcmPeakAbs(pcm)
            if (peak < 200) {
                // 近乎静音：引擎跑通了但没合成出有效语音（常见于词表/编码问题）
                _ttsGen.value = TtsGen.FAILED
                GlassLog.b("Playback") {
                    "TTS 生成近乎静音 peak=$peak (${pcm.size} B): ${trimmed.take(20)}"
                }
                false
            } else {
                generatedTtsPcm = pcm
                _ttsGen.value = TtsGen.READY
                GlassLog.b("Playback") {
                    "TTS 已生成: ${trimmed.take(20)} (${pcm.size} B, peak=$peak)"
                }
                true
            }
        } else {
            _ttsGen.value = TtsGen.FAILED
            GlassLog.b("Playback") { "TTS 生成失败/超时: ${trimmed.take(20)}" }
            false
        }
    }

    private val _ttsDelayRemainingMs = MutableStateFlow(0L)
    /** 延时播放倒计时剩余毫秒，0 表示当前没有待播放的语音。供悬浮窗显示倒计时。 */
    val ttsDelayRemainingMs: StateFlow<Long> = _ttsDelayRemainingMs.asStateFlow()

    /**
     * 把上次生成的语音喂给目标 App，可重复调用重播。
     *
     * 配置了 tts.delay_ms 时先倒计时再出声，留出切到目标 App 的时间；
     * 倒计时期间可用 [cancelDelayedTts] 取消。返回 true 表示语音已真正开始播放
     * （被取消则返回 false）。
     */
    suspend fun playGeneratedTts(): Boolean = withContext(Dispatchers.IO) {
        val pcm = generatedTtsPcm ?: return@withContext false

        val delayMs = configStore.current().tts.delayMs.toLong().coerceAtLeast(0L)
        if (delayMs > 0) {
            // 已有倒计时在跑时不叠加，交给调用方先取消
            if (_ttsDelayRemainingMs.value > 0L) return@withContext false
            val deadline = System.currentTimeMillis() + delayMs
            try {
                var remaining = delayMs
                while (remaining > 0) {
                    _ttsDelayRemainingMs.value = remaining
                    // 100ms 一档刷新，倒计时读数够跟手又不至于频繁重组
                    delay(remaining.coerceAtMost(COUNTDOWN_TICK_MS))
                    remaining = deadline - System.currentTimeMillis()
                }
            } catch (e: CancellationException) {
                // 取消后要清干净，否则下次播放会被上面的"已有倒计时"分支挡掉
                _ttsDelayRemainingMs.value = 0L
                GlassLog.b("Playback") { "TTS 延时播放已取消" }
                throw e
            }
            _ttsDelayRemainingMs.value = 0L
        }

        publisher.setSource(BufferedPcmSource(pcm))
        publisher.setPaused(false)
        configStore.update {
            it.setCurrentGroupId("")
            it.setCurrentAudioId("")
        }
        // 同步本机扬声器试听：虚拟麦要等目标 App 开麦才有消费者；
        // 用户点「播放」时如果扬声器也不出声，会误判成 TTS 坏了。
        startPcmSpeakerPreview(pcm, startOffsetBytes = 0)
        true
    }

    /** 倒计时归零，[playGeneratedTts] 的协程被取消后由调用方触发。 */
    fun clearTtsDelayCountdown() {
        _ttsDelayRemainingMs.value = 0L
        stopSpeakerPreview()
    }

    /** 本机扬声器播放一段主时钟 PCM（不阻塞虚拟麦管线）。 */
    private fun startPcmSpeakerPreview(pcm: ByteArray, startOffsetBytes: Int) {
        stopSpeakerPreview()
        if (!isSpeakerPreviewEnabled()) return
        speakerPcm = pcm
        val aligned = (startOffsetBytes - (startOffsetBytes % 2)).coerceIn(0, pcm.size)
        speakerJob = scope.launch {
            val sampleRate = MASTER_SAMPLE_RATE
            val channels = MASTER_CHANNELS
            val chMask = AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                GlassLog.b("Playback") { "AudioTrack 不可用: minBuf=$minBuf" }
                return@launch
            }
            // USAGE_ASSISTANCE_SONIFICATION：弱音频焦点，少抢微信等通话通路
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(chMask)
                    .build(),
                maxOf(minBuf, sampleRate * channels * 2),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            speakerTrack = track
            try {
                track.play()
                var off = aligned
                while (off < pcm.size) {
                    val n = track.write(pcm, off, (pcm.size - off).coerceAtMost(minBuf * 4))
                    if (n <= 0) break
                    off += n
                }
                val remainMs = (pcm.size - aligned).toLong() * 1000 /
                    (sampleRate.toLong() * channels * 2)
                delay(remainMs.coerceAtMost(120_000L) + 200)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                GlassLog.b("Playback") { "扬声器试听失败: ${t.message}" }
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
                if (speakerTrack === track) speakerTrack = null
            }
        }
    }

    /** 本地文件扬声器试听：独立 MediaPlayer，与虚拟麦 FileAudioSource 并行。 */
    private fun startFileSpeakerPreview(path: String, startPaused: Boolean, positionMs: Long = 0L) {
        stopSpeakerPreview()
        if (!isSpeakerPreviewEnabled()) return
        runCatching {
            val loop = loopPlaybackCache
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(path)
                isLooping = loop
                prepare()
                if (positionMs > 0L) {
                    seekTo(positionMs.coerceAtMost(duration.toLong()).toInt())
                }
                if (!startPaused) start()
            }
            filePreviewPlayer = mp
            GlassLog.b("Playback") { "文件扬声器试听已就绪 paused=$startPaused" }
        }.onFailure {
            GlassLog.b("Playback") { "文件扬声器试听失败: ${it.message}" }
            filePreviewPlayer = null
        }
    }

    private fun stopSpeakerPreview() {
        speakerJob?.cancel()
        speakerJob = null
        speakerTrack?.let {
            runCatching { it.pause() }
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        speakerTrack = null
        speakerPcm = null
        filePreviewPlayer?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        filePreviewPlayer = null
    }

    private fun pauseSpeakerPreview() {
        runCatching { speakerTrack?.pause() }
        runCatching {
            filePreviewPlayer?.let { if (it.isPlaying) it.pause() }
        }
    }

    private fun resumeSpeakerPreview() {
        if (!isSpeakerPreviewEnabled()) {
            stopSpeakerPreview()
            return
        }
        runCatching { speakerTrack?.play() }
        runCatching { filePreviewPlayer?.start() }
    }

    private fun pcmPeakAbs(pcm: ByteArray): Int {
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val abs = if (s < 0) -s else s
            if (abs > peak) peak = abs
            i += 2
        }
        return peak
    }

    /** 合成并转成主时钟格式（48kHz 单声道 PCM16）。 */
    private suspend fun synthesizeToMasterPcm(
        synth: io.mo.glassmic.audio.tts.SpeechSynthesizer,
        req: TtsRequest
    ): ByteArray? = suspendCancellableCoroutine { cont ->
        val out = ByteArrayOutputStream()
        var converter: Pcm16Converter? = null
        val sink = object : PcmSink {
            override fun onFormat(sampleRate: Int, channels: Int) {
                converter = Pcm16Converter(
                    sourceSampleRate = sampleRate.coerceAtLeast(8_000),
                    sourceChannels = channels.coerceAtLeast(1),
                    targetSampleRate = MASTER_SAMPLE_RATE,
                    targetChannels = MASTER_CHANNELS
                )
            }
            override fun onPcm(chunk: ByteArray) {
                val c = converter ?: return
                out.write(c.convert(chunk))
            }
            override fun onDone() {
                if (cont.isActive) cont.resumeWith(Result.success(out.toByteArray()))
            }
            override fun onError(message: String) {
                if (cont.isActive) cont.resumeWith(Result.success(null))
            }
        }
        synth.synthesize(req, sink)
        cont.invokeOnCancellation { synth.cancel() }
    }

    private companion object {
        const val MASTER_SAMPLE_RATE = 48_000
        const val MASTER_CHANNELS = 1
        const val COUNTDOWN_TICK_MS = 100L
    }

    /** 切回真实麦克风：释放当前 file source，运行态置 REAL_MIC，清空持久化选中。 */
    suspend fun setRealMic() = withContext(Dispatchers.IO) {
        stopSpeakerPreview()
        publisher.setPaused(false)
        publisher.setSource(SilenceSource)
        runtime.setSource(SourceType.REAL_MIC, groupId = null, audioId = null, durationMs = 0L)
        configStore.update {
            it.setCurrentGroupId("")
            it.setCurrentAudioId("")
        }
    }

    suspend fun restorePersistedClip(): Boolean {
        val clipId = configStore.current().currentAudioId.takeIf { it.isNotBlank() } ?: return false
        return setCurrentClip(clipId, startPaused = true)
    }

    val isPaused: Boolean get() = publisher.isPaused

    fun pause() {
        publisher.setPaused(true)
        pauseSpeakerPreview()
        GlassLog.b("Playback") { "暂停" }
    }

    fun resume() {
        publisher.setPaused(false)
        resumeSpeakerPreview()
        GlassLog.b("Playback") { "恢复" }
    }

    fun togglePause() {
        if (publisher.isPaused) resume() else pause()
    }

    suspend fun seekTo(positionMs: Long) = withContext(Dispatchers.IO) {
        publisher.seekCurrent(positionMs)
        val mp = filePreviewPlayer
        if (mp != null) {
            runCatching {
                val dur = mp.duration.coerceAtLeast(0)
                mp.seekTo(positionMs.coerceIn(0L, dur.toLong()).toInt())
            }
            return@withContext
        }
        val pcm = speakerPcm ?: generatedTtsPcm ?: return@withContext
        if (!isSpeakerPreviewEnabled()) return@withContext
        val bytePos = (positionMs * MASTER_SAMPLE_RATE * 2 / 1000)
            .coerceIn(0L, pcm.size.toLong())
            .toInt()
        val wasPaused = publisher.isPaused
        startPcmSpeakerPreview(pcm, startOffsetBytes = bytePos)
        if (wasPaused) pauseSpeakerPreview()
    }

    // ============ 公平运行内存：可回收内存 ============

    /**
     * 释放 TTS 生成缓存。
     *
     * 48kHz 单声道 PCM16 = 96KB/秒，一段一分钟的语音就是 5.6MB，是本进程最大的一块
     * 可丢弃内存。丢掉只影响「重复播放上一段语音」，用户重新生成即可恢复。
     *
     * 正在合成、或这段 PCM 正是当前音源（目标 App 正在读它）时绝不丢——那会直接打断
     * 核心功能。正在出声的音频管线在任何压力等级下都不动。
     */
    override fun onMemoryPressure(level: MemoryPressure): Long {
        val pcm = generatedTtsPcm ?: return 0L
        if (_ttsGen.value == TtsGen.GENERATING) return 0L
        if (publisher.playingBufferedPcm) return 0L
        val shouldDrop = when (level) {
            // 只是切到后台/系统轻度吃紧，用户可能马上回来重播，留着
            MemoryPressure.LIGHT -> false
            // 没有 App 在读 PCM 时才丢，避免录音过程中用户点重播落空
            MemoryPressure.MODERATE -> publisher.consumerCount == 0
            else -> true
        }
        if (!shouldDrop) return 0L
        generatedTtsPcm = null
        _ttsGen.value = TtsGen.IDLE
        GlassLog.b("Playback") { "内存压力($level)：释放 TTS 缓存 ${pcm.size / 1024}KB" }
        return pcm.size.toLong()
    }
}
