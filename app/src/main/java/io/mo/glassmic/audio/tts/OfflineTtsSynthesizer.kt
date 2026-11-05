package io.mo.glassmic.audio.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.log.GlassLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内置离线 TTS：Sherpa-ONNX + Piper 中文（xiao_ya）。
 *
 * 不依赖系统 TextToSpeech，适合国内小米等未装 Google TTS / 引擎不回调 PCM 的机型。
 * 模型放在 assets/sherpa-tts/ 下，经 AssetManager 加载。
 */
@Singleton
class OfflineTtsSynthesizer @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechSynthesizer {

    private val lock = Any()
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var initError: String? = null
    private val cancelFlag = AtomicBoolean(false)

    /** 引擎是否可用（懒加载成功）。 */
    fun isAvailable(): Boolean {
        ensureInit()
        return tts != null
    }

    private fun ensureInit() {
        if (tts != null || initError != null) return
        synchronized(lock) {
            if (tts != null || initError != null) return
            runCatching {
                val dir = MODEL_DIR
                val vits = OfflineTtsVitsModelConfig(
                    model = "$dir/$MODEL_ONNX",
                    lexicon = "$dir/$MODEL_LEXICON",
                    tokens = "$dir/$MODEL_TOKENS",
                    dataDir = "",
                    dictDir = "",
                    noiseScale = 0.667f,
                    noiseScaleW = 0.8f,
                    lengthScale = 1.0f,
                )
                val model = OfflineTtsModelConfig(
                    vits = vits,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                )
                val config = OfflineTtsConfig(
                    model = model,
                    ruleFsts = "$dir/$FST_PHONE,$dir/$FST_DATE,$dir/$FST_NUMBER",
                    ruleFars = "",
                    maxNumSentences = 2,
                    silenceScale = 0.2f,
                )
                // AssetManager 构造：路径相对 assets/
                val engine = OfflineTts(assetManager = context.assets, config = config)
                tts = engine
                GlassLog.b("Tts") {
                    "离线 Piper TTS 就绪: $MODEL_DIR sr=${engine.sampleRate()} speakers=${engine.numSpeakers()}"
                }
            }.onFailure {
                initError = it.message ?: "init failed"
                GlassLog.b("Tts") { "离线 Piper TTS 初始化失败: ${it.message}" }
            }
        }
    }

    override fun synthesize(request: TtsRequest, sink: PcmSink) {
        cancelFlag.set(false)
        ensureInit()
        val engine = tts
        if (engine == null) {
            sink.onError(initError ?: "离线 TTS 不可用")
            return
        }
        if (request.text.isBlank()) {
            sink.onError("文本为空")
            return
        }

        runCatching {
            val speed = request.rate.takeIf { it > 0f } ?: 1.0f
            val audio = engine.generate(
                text = request.text.trim(),
                sid = 0,
                speed = speed.coerceIn(0.5f, 2.0f),
            )
            if (cancelFlag.get()) {
                sink.onError("已取消")
                return
            }
            val samples = audio.samples
            if (samples.isEmpty()) {
                sink.onError("合成结果为空")
                return
            }
            val sampleRate = audio.sampleRate.coerceAtLeast(8_000)
            sink.onFormat(sampleRate, 1)
            // 分块回调，避免一次塞过大数组
            val pcm = floatSamplesToPcm16(samples)
            val chunk = 32_768
            var off = 0
            while (off < pcm.size) {
                if (cancelFlag.get()) {
                    sink.onError("已取消")
                    return
                }
                val end = (off + chunk).coerceAtMost(pcm.size)
                sink.onPcm(pcm.copyOfRange(off, end))
                off = end
            }
            sink.onDone()
        }.onFailure {
            GlassLog.b("Tts") { "离线 TTS 合成失败: ${it.message}" }
            sink.onError(it.message ?: "合成失败")
        }
    }

    override fun cancel() {
        cancelFlag.set(true)
    }

    override fun release() {
        synchronized(lock) {
            runCatching { tts?.release() }
            tts = null
            initError = null
        }
    }

    companion object {
        private const val MODEL_DIR = "sherpa-tts/vits-piper-zh_CN-xiao_ya-medium"
        private const val MODEL_ONNX = "zh_CN-xiao_ya-medium.onnx"
        private const val MODEL_LEXICON = "lexicon.txt"
        private const val MODEL_TOKENS = "tokens.txt"
        private const val FST_PHONE = "phone.fst"
        private const val FST_DATE = "date.fst"
        private const val FST_NUMBER = "number.fst"

        /** float32 [-1,1] 交错单声道 → PCM16 LE。 */
        fun floatSamplesToPcm16(samples: FloatArray): ByteArray {
            val out = ByteArray(samples.size * 2)
            val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                val v = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                buf.putShort(v)
            }
            return out
        }
    }
}
