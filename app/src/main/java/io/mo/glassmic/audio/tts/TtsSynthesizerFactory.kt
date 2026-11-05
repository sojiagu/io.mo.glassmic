package io.mo.glassmic.audio.tts

import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.log.GlassLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按当前配置选择语音合成引擎。
 *
 * 优先级：
 * 1. AI TTS 开启 → [AiTtsSynthesizer]
 * 2. 内置 Piper/Sherpa 可用 → [OfflineTtsSynthesizer]（小米等机型默认走这里）
 * 3. 回退系统 [SystemTtsSynthesizer]
 */
@Singleton
class TtsSynthesizerFactory @Inject constructor(
    private val configStore: ConfigStore,
    private val offline: OfflineTtsSynthesizer,
    private val system: SystemTtsSynthesizer,
    private val ai: AiTtsSynthesizer,
) {
    /** 返回当前应使用的合成器（不持有状态，可直接调用 synthesize）。 */
    suspend fun current(): SpeechSynthesizer {
        val aiCfg = runCatching { configStore.current().tts.ai }.getOrNull()
        if (aiCfg != null && aiCfg.enabled) {
            GlassLog.b("Tts") { "使用 AI TTS: ${aiCfg.provider}" }
            return ai
        }
        if (offline.isAvailable()) {
            GlassLog.b("Tts") { "使用内置离线 Piper TTS" }
            return offline
        }
        GlassLog.b("Tts") { "内置离线不可用，回退系统 TTS" }
        return system
    }

    fun releaseAll() {
        runCatching { offline.release() }
        runCatching { system.release() }
        runCatching { ai.cancel() }
    }
}
