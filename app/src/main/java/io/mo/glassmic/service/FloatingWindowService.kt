package io.mo.glassmic.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.mo.glassmic.R
import io.mo.glassmic.core.Constants
import io.mo.glassmic.core.model.SourceType
import io.mo.glassmic.data.audio.AudioImportRepository
import io.mo.glassmic.data.audio.FloatingIconStore
import io.mo.glassmic.data.audio.LocalMp3Scanner
import io.mo.glassmic.data.audio.PlaybackController
import io.mo.glassmic.data.config.AppLocale
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.db.AudioDao
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.data.runtime.VolumeShortcutRepository
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.proto.AppConfig
import io.mo.glassmic.proto.FloatingSize
import io.mo.glassmic.ui.theme.LocalGlassEnabled
import io.mo.glassmic.ui.theme.LocalReduceMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 悬浮窗服务（Compose 版）。
 *
 * 三态：球态 / 迷你播放条 / 选曲菜单，全部渲染在同一个挂到 WindowManager 的 ComposeView 里。
 * 手势：
 * - 未播放点击球 → 选曲菜单；播放中点击球 → 迷你播放条
 * - 迷你条内「换音源」→ 选曲菜单；选中片段 → 3 秒倒计时后播放并回到迷你条
 * - 拖动球移动，松手贴边
 */
@AndroidEntryPoint
class FloatingWindowService : LifecycleService() {

    @Inject lateinit var runtime: RuntimeStateHolder
    @Inject lateinit var playback: PlaybackController
    @Inject lateinit var configStore: ConfigStore
    @Inject lateinit var audioDao: AudioDao
    @Inject lateinit var iconStore: FloatingIconStore
    @Inject lateinit var volumeShortcut: VolumeShortcutRepository
    @Inject lateinit var audioImport: AudioImportRepository
    @Inject lateinit var localMp3Scanner: LocalMp3Scanner

    private var windowManager: WindowManager? = null
    private var host: FloatingOverlayHost? = null
    private var params: WindowManager.LayoutParams? = null

    private val modeFlow = MutableStateFlow(FloatMode.BALL)
    private val localMp3Query = MutableStateFlow("")
    private val localMp3Results = MutableStateFlow<List<FloatLocalMp3Item>>(emptyList())
    private val localMp3Searching = MutableStateFlow(false)
    private val localMp3ImportingUri = MutableStateFlow<String?>(null)
    /** 本地 MP3 延时播放剩余整秒；0 表示未在倒计时。 */
    private val localMp3CountdownSec = MutableStateFlow(0)
    private var localMp3PlayJob: Job? = null
    /** 音频库片段延时播放：当前倒计时中的 clipId；null 表示未在倒计时。 */
    private val clipCountdownId = MutableStateFlow<String?>(null)
    private val clipCountdownSec = MutableStateFlow(0)
    private var clipPlayJob: Job? = null

    /**
     * 悬浮球的锚点位置（用户拖动决定）。
     * 展开态（菜单/迷你条/TTS）面板比球宽，靠右时会被 [clampExpandedX] 临时左移避让屏幕边缘，
     * 但该偏移不写回锚点；收起时按锚点还原，否则球会一次次被"推"到屏幕左侧。
     */
    private var anchorX = DEFAULT_X
    private var anchorY = DEFAULT_Y

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            GlassLog.b("Float") { "overlay permission missing; stop floating window" }
            stopSelf()
            return
        }
        showOverlay()
        runtime.setFloatingVisible(true)
        // 音量键快捷操作只在悬浮窗存活期间布防，开关变化即时生效
        lifecycleScope.launch {
            configStore.flow
                .map { it.shortcuts.volumeKeysEnabled }
                .distinctUntilChanged()
                .collect { applyVolumeShortcut(it) }
        }
        lifecycleScope.launch {
            @OptIn(FlowPreview::class)
            localMp3Query
                .debounce(280)
                .distinctUntilChanged()
                .collect { q ->
                    if (q.isBlank()) {
                        localMp3Searching.value = false
                        localMp3Results.value = emptyList()
                        return@collect
                    }
                    localMp3Searching.value = true
                    val hits = runCatching { localMp3Scanner.search(q) }
                        .onFailure { GlassLog.b("Float") { "本地 MP3 搜索失败: ${it.message}" } }
                        .getOrDefault(emptyList())
                    // 查询期间用户可能已改关键字，丢弃过期结果
                    if (localMp3Query.value != q) return@collect
                    localMp3Results.value = hits.map {
                        FloatLocalMp3Item(
                            uriString = it.uri.toString(),
                            name = it.displayName,
                            durationMs = it.durationMs,
                        )
                    }
                    localMp3Searching.value = false
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        // 先撤防再拆窗口：system_server 侧一旦撤防就立刻停止拦截音量键，
        // 不会留下「悬浮窗已关、音量键却还发涩」的空窗期。
        applyVolumeShortcut(false)
        host?.let { h ->
            runCatching { windowManager?.removeView(h.view) }
            h.onDestroy()
        }
        host = null
        runtime.setFloatingVisible(false)
        super.onDestroy()
    }

    // ============ 音量键双击快捷操作 ============

    /**
     * 接收 Xposed 侧（system_server）检测到的双击。
     *
     * 必须注册为 EXPORTED 才收得到来自 system_server 的广播，因此用 token 校验来源——
     * token 由本进程随机生成、写进只有模块读得到的 remote preferences，第三方 App 伪造不出来。
     */
    private val shortcutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Constants.ACTION_VOLUME_SHORTCUT) return
            if (!volumeShortcut.verifyToken(intent.getStringExtra(Constants.EXTRA_SHORTCUT_TOKEN))) {
                GlassLog.b("VolKey") { "丢弃 token 不匹配的快捷操作广播" }
                return
            }
            when (intent.getStringExtra(Constants.EXTRA_SHORTCUT_ACTION)) {
                Constants.SHORTCUT_PLAY -> onShortcutPlay()
                Constants.SHORTCUT_PAUSE -> playback.pause()
            }
        }
    }

    private var shortcutArmed = false

    private fun applyVolumeShortcut(enabled: Boolean) {
        if (enabled == shortcutArmed) return
        if (enabled) {
            val filter = IntentFilter(Constants.ACTION_VOLUME_SHORTCUT)
            val registered = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(shortcutReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(shortcutReceiver, filter)
                }
            }.onFailure {
                GlassLog.b("VolKey") { "注册快捷操作接收器失败: ${it.message}" }
            }.isSuccess
            if (!registered) return
            shortcutArmed = true
            // 先注册后布防：布防之后模块才会开始发广播，不会有收不到的一瞬
            volumeShortcut.setArmed(true)
        } else {
            volumeShortcut.setArmed(false)
            runCatching { unregisterReceiver(shortcutReceiver) }
            shortcutArmed = false
        }
    }

    /**
     * 双击音量上键 = 播放。
     *
     * 冷启动后还没选过音源时（运行态仍是默认的 REAL_MIC），先把上次持久化的片段装回来，
     * 否则 resume() 无声可出，用户会以为快捷键没反应。
     */
    private fun onShortcutPlay() {
        lifecycleScope.launch {
            val src = runtime.flow.value.currentSourceType
            if (src != SourceType.FILE && src != SourceType.TTS) {
                playback.restorePersistedClip()
            }
            playback.resume()
        }
    }

    private fun showOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchorX
            y = anchorY
        }
        params = lp

        // Service 不是 Activity，Android 13 以下不会自动跟随应用内切换的语言，
        // 这里手动包一层 Locale Context 传给 ComposeView，保证悬浮窗文案也能正确显示。
        val overlayHost = FloatingOverlayHost(AppLocale.wrap(this)).also { it.onCreate() }
        host = overlayHost
        overlayHost.setContent {
            // 悬浮窗以前只包了裸 MaterialTheme{}，Slider/Switch 会用 Material3 默认紫色，
            // 和面板里的绿色 Accent 打架。挂上 OverlayColorScheme 后它们自动跟随主色。
            MaterialTheme(colorScheme = OverlayColorScheme) {
                val rt by runtime.flow.collectAsState()
                val cfg by configStore.flow.collectAsState(initial = AppConfig.getDefaultInstance())
                val groups by audioDao.observeGroups().collectAsState(initial = emptyList())
                val allClips by audioDao.observeAllClips().collectAsState(initial = emptyList())
                val mode by modeFlow.collectAsState()
                val ttsGen by playback.ttsGen.collectAsState()
                val ttsDelayRemainingMs by playback.ttsDelayRemainingMs.collectAsState()
                val mp3Query by localMp3Query.collectAsState()
                val mp3Results by localMp3Results.collectAsState()
                val mp3Searching by localMp3Searching.collectAsState()
                val mp3Importing by localMp3ImportingUri.collectAsState()
                val mp3CountdownSec by localMp3CountdownSec.collectAsState()
                val clipCountingId by clipCountdownId.collectAsState()
                val clipCountingSec by clipCountdownSec.collectAsState()

                val activeFile = rt.currentSourceType == SourceType.FILE && rt.enabled && !rt.safeMode
                val currentId = cfg.currentAudioId
                val currentName = allClips.firstOrNull { it.id == currentId }?.displayName

                // 悬浮窗不走 GlassMicTheme，这两个外观开关得在这里手动注入
                CompositionLocalProvider(
                    LocalGlassEnabled provides cfg.appearance.glassEffect,
                    LocalReduceMotion provides cfg.appearance.reduceMotion,
                ) {
                FloatingBubbleRoot(
                    mode = mode,
                    activeFile = activeFile,
                    paused = rt.paused,
                    positionMs = rt.positionMs,
                    durationMs = rt.durationMs,
                    currentName = currentName,
                    sizeDp = sizeToDp(cfg.floatingWindow.size),
                    iconPath = cfg.floatingWindow.customIconPath.takeIf { it.isNotBlank() }
                        ?.let { iconStore.iconFile(it).absolutePath },
                    opacity = cfg.floatingWindow.opacity.takeIf { it > 0f } ?: 0.85f,
                    groups = groups.map { FloatGroupItem(it.id, it.emoji, it.name) },
                    clipsProvider = { gid ->
                        audioDao.observeClipsInGroup(gid).map { list ->
                            list.map { FloatClipItem(it.id, it.displayName, it.id == currentId) }
                        }
                    },
                    localMp3Query = mp3Query,
                    localMp3Results = mp3Results,
                    localMp3Searching = mp3Searching,
                    localMp3ImportingUri = mp3Importing,
                    localMp3CountdownSec = mp3CountdownSec,
                    clipCountdownId = clipCountingId,
                    clipCountdownSec = clipCountingSec,
                    onLocalMp3QueryChange = { localMp3Query.value = it },
                    onImportLocalMp3 = { uri, name, groupId ->
                        onImportLocalMp3(uri, name, groupId)
                    },
                    onBallTap = { onBallTap(activeFile) },
                    onTogglePause = { playback.togglePause() },
                    onSeek = { frac -> onSeek(frac, rt.durationMs) },
                    onOpenMenu = { setMode(FloatMode.MENU) },
                    onCollapse = { setMode(FloatMode.BALL) },
                    onSelectClip = { clipId -> onSelectClip(clipId) },
                    onOpenTts = { setMode(FloatMode.TTS) },
                    ttsGenerating = ttsGen == PlaybackController.TtsGen.GENERATING,
                    ttsReady = ttsGen == PlaybackController.TtsGen.READY,
                    ttsFailed = ttsGen == PlaybackController.TtsGen.FAILED,
                    onGenerateTts = { text -> onGenerateTts(text) },
                    onPlayTts = { onPlayTts() },
                    ttsProgressBarEnabled = cfg.tts.progressBarEnabled,
                    ttsActive = rt.currentSourceType == SourceType.TTS,
                    onOpenTtsSettings = { setMode(FloatMode.TTS_SETTINGS) },
                    onToggleTtsProgressBar = { enabled -> onToggleTtsProgressBar(enabled) },
                    speakerPreviewEnabled = if (cfg.floatingWindow.hasSpeakerPreviewEnabled()) {
                        cfg.floatingWindow.speakerPreviewEnabled
                    } else {
                        true
                    },
                    onToggleSpeakerPreview = { enabled -> onToggleSpeakerPreview(enabled) },
                    ttsDelayMs = cfg.tts.delayMs,
                    onSetTtsDelay = { ms -> onSetTtsDelay(ms) },
                    ttsDelayRemainingMs = ttsDelayRemainingMs,
                    onCancelDelayedTts = { onCancelDelayedTts() },
                    onSeekTts = { frac -> onSeek(frac, rt.durationMs) },
                    onCloseTtsSettings = { setMode(FloatMode.TTS) },
                    onDragBy = { dx, dy -> onDragBy(dx, dy) },
                    onDragEnd = { onDragEnd() },
                )
                }
            }
        }
        runCatching { wm.addView(overlayHost.view, lp) }
            .onFailure { GlassLog.b("Float") { "addView 失败: ${it.message}" } }
    }

    // ============ 手势 / 交互回调 ============
    private fun onBallTap(activeFile: Boolean) {
        setMode(if (activeFile) FloatMode.MINI_BAR else FloatMode.MENU)
    }

    /**
     * 音频库片段：先设为音源并暂停，倒计时 [LOCAL_MP3_PLAY_DELAY_SEC] 秒后再播放。
     * 倒计时期间再点同一项可取消。
     */
    private fun onSelectClip(clipId: String) {
        if (clipCountdownId.value == clipId && clipCountdownSec.value > 0) {
            clipPlayJob?.cancel()
            clipPlayJob = null
            clipCountdownId.value = null
            clipCountdownSec.value = 0
            playback.pause()
            GlassLog.b("Float") { "音频库片段延时播放已取消" }
            return
        }
        if (clipCountdownId.value != null) return
        if (localMp3ImportingUri.value != null) return
        clipPlayJob = lifecycleScope.launch {
            clipCountdownId.value = clipId
            clipCountdownSec.value = 0
            try {
                val ok = playback.setCurrentClip(clipId, startPaused = true)
                if (!ok) {
                    GlassLog.b("Float") { "选中片段失败: $clipId" }
                    return@launch
                }
                for (sec in LOCAL_MP3_PLAY_DELAY_SEC downTo 1) {
                    clipCountdownSec.value = sec
                    delay(1_000L)
                }
                clipCountdownSec.value = 0
                playback.resume()
                setMode(FloatMode.MINI_BAR)
            } catch (e: CancellationException) {
                clipCountdownSec.value = 0
                throw e
            } finally {
                if (clipCountdownId.value == clipId) {
                    clipCountdownId.value = null
                    clipCountdownSec.value = 0
                }
            }
        }
    }

    /**
     * 悬浮窗本地 MP3：导入到当前/首个/新建「本地导入」分组，先暂停设为当前音源，
     * 倒计时 [LOCAL_MP3_PLAY_DELAY_SEC] 秒后再播放。倒计时期间再点同一项可取消。
     */
    private fun onImportLocalMp3(uriString: String, displayName: String, preferredGroupId: String?) {
        // 倒计时中再点同一项 → 取消，不出声
        if (localMp3ImportingUri.value == uriString && localMp3CountdownSec.value > 0) {
            localMp3PlayJob?.cancel()
            localMp3PlayJob = null
            localMp3CountdownSec.value = 0
            localMp3ImportingUri.value = null
            playback.pause()
            GlassLog.b("Float") { "本地 MP3 延时播放已取消" }
            return
        }
        if (localMp3ImportingUri.value != null) return
        if (clipCountdownId.value != null) return
        localMp3PlayJob = lifecycleScope.launch {
            localMp3ImportingUri.value = uriString
            localMp3CountdownSec.value = 0
            try {
                val uri = Uri.parse(uriString)
                val groupId = resolveImportGroupId(preferredGroupId)
                val cleanName = displayName
                    .removeSuffix(".mp3").removeSuffix(".MP3").removeSuffix(".Mp3")
                    .ifBlank { displayName }
                val clip = audioImport.importFromUri(uri, groupId, displayNameOverride = cleanName)
                // 先设为音源但暂停，留出切 App 时间
                val ok = playback.setCurrentClip(clip.id, startPaused = true)
                if (!ok) {
                    GlassLog.b("Float") { "本地 MP3 已导入但设为音源失败: ${clip.displayName}" }
                    return@launch
                }
                for (sec in LOCAL_MP3_PLAY_DELAY_SEC downTo 1) {
                    localMp3CountdownSec.value = sec
                    delay(1_000L)
                }
                localMp3CountdownSec.value = 0
                playback.resume()
                localMp3Query.value = ""
                localMp3Results.value = emptyList()
                setMode(FloatMode.MINI_BAR)
                GlassLog.b("Float") { "本地 MP3 已导入并播放: ${clip.displayName}" }
            } catch (e: CancellationException) {
                localMp3CountdownSec.value = 0
                throw e
            } catch (t: Throwable) {
                GlassLog.b("Float") { "本地 MP3 导入失败: ${t.message}" }
            } finally {
                if (localMp3ImportingUri.value == uriString) {
                    localMp3ImportingUri.value = null
                    localMp3CountdownSec.value = 0
                }
            }
        }
    }

    private suspend fun resolveImportGroupId(preferredGroupId: String?): String {
        preferredGroupId?.takeIf { it.isNotBlank() }?.let { id ->
            if (audioDao.findGroup(id) != null) return id
        }
        audioDao.listGroups().firstOrNull()?.id?.let { return it }
        return audioImport.createGroup(
            name = getString(R.string.float_local_mp3_section),
            emoji = "📁",
            policy = null,
        ).id
    }

    private fun onGenerateTts(text: String) {
        lifecycleScope.launch {
            val ok = playback.generateTts(text)
            if (!ok) GlassLog.b("Float") { "TTS 生成失败（文本为空或引擎不可用）" }
        }
    }

    /** 延时播放期间持有倒计时协程，供再次点击时取消。 */
    private var ttsPlayJob: Job? = null

    private fun onPlayTts() {
        ttsPlayJob = lifecycleScope.launch {
            val ok = playback.playGeneratedTts()
            if (!ok) GlassLog.b("Float") { "TTS 播放失败（尚未生成）" }
        }
    }

    /** 倒计时中再点一次播放按钮 → 取消，不出声。 */
    private fun onCancelDelayedTts() {
        ttsPlayJob?.cancel()
        ttsPlayJob = null
        playback.clearTtsDelayCountdown()
    }

    private fun onToggleTtsProgressBar(enabled: Boolean) {
        lifecycleScope.launch {
            configStore.update {
                it.setTts(it.tts.toBuilder().setProgressBarEnabled(enabled).build())
            }
        }
    }

    private fun onToggleSpeakerPreview(enabled: Boolean) {
        lifecycleScope.launch {
            configStore.update {
                it.setFloatingWindow(
                    it.floatingWindow.toBuilder().setSpeakerPreviewEnabled(enabled).build()
                )
            }
            playback.stopSpeakerPreviewIfDisabled()
        }
    }

    private fun onSetTtsDelay(delayMs: Int) {
        lifecycleScope.launch {
            configStore.update {
                it.setTts(it.tts.toBuilder().setDelayMs(delayMs.coerceIn(0, MAX_TTS_DELAY_MS)).build())
            }
        }
    }

    private fun onSeek(frac: Float, durationMs: Long) {
        if (durationMs <= 0) return
        val target = (durationMs * frac).toLong().coerceIn(0L, durationMs)
        lifecycleScope.launch { playback.seekTo(target) }
    }

    private fun onDragBy(dx: Float, dy: Float) {
        val lp = params ?: return
        val h = host ?: return
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
    }

    private fun onDragEnd() {
        // 松手后停在原地，仅做边界兜底，允许自由悬停（不贴边）
        clampToBounds()
        // 用户主动拖动即视为重新选定位置：同步锚点，收起时才不会跳回旧位置
        params?.let { anchorX = it.x; anchorY = it.y }
    }

    private fun setMode(mode: FloatMode) {
        modeFlow.value = mode
        // TTS / TTS 设置 / 音频库（本地 MP3 搜索框）需要输入法焦点，其它态保持不可聚焦
        setWindowFocusable(
            mode == FloatMode.TTS || mode == FloatMode.TTS_SETTINGS || mode == FloatMode.MENU
        )
        // 展开态（迷你条/菜单/TTS）确保不超出屏幕右边；收起时还原到球的锚点
        if (mode != FloatMode.BALL) clampExpandedX() else restoreAnchor()
    }

    /**
     * 收起为球态时还原用户拖动过的锚点位置。
     * 边界兜底放到 post 里执行：此刻 view 仍是展开态的宽度，需等重新测量成球的尺寸后再 clamp，
     * 否则会用面板宽度去约束球，把靠右的球再次推向左边。
     */
    private fun restoreAnchor() {
        val lp = params ?: return
        val h = host ?: return
        lp.x = anchorX
        lp.y = anchorY
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
        h.view.post { clampToBounds() }
    }

    /**
     * 切换悬浮窗是否可获得焦点。
     * TTS 输入需要软键盘 → 去掉 FLAG_NOT_FOCUSABLE；其它态恢复不可聚焦，避免拦截系统触摸。
     */
    private fun setWindowFocusable(focusable: Boolean) {
        val lp = params ?: return
        val h = host ?: return
        lp.flags = if (focusable) {
            // 可聚焦（软键盘）但仍放行窗口外的触摸：FLAG_NOT_FOCUSABLE 会隐式带上
            // FLAG_NOT_TOUCH_MODAL，去掉它后必须显式补回，否则整屏触摸都会被悬浮窗吞掉
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
    }

    /** 边界兜底：把悬浮球约束在屏幕内，但不贴边，保留自由悬停位置。 */
    private fun clampToBounds() {
        val lp = params ?: return
        val h = host ?: return
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val viewW = h.view.width.takeIf { it > 0 } ?: dpToPx(56)
        val viewH = h.view.height.takeIf { it > 0 } ?: dpToPx(56)
        lp.x = lp.x.coerceIn(0, (screenW - viewW).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screenH - viewH).coerceAtLeast(0))
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
    }

    private fun clampExpandedX() {
        val lp = params ?: return
        val h = host ?: return
        val screenW = resources.displayMetrics.widthPixels
        val panelW = dpToPx(EXPANDED_PANEL_WIDTH_DP)
        if (lp.x + panelW > screenW) lp.x = (screenW - panelW).coerceAtLeast(8)
        if (lp.x < 8) lp.x = 8
        runCatching { windowManager?.updateViewLayout(h.view, lp) }
    }

    private fun sizeToDp(size: FloatingSize): Dp = when (size) {
        FloatingSize.SMALL -> 44.dp
        FloatingSize.LARGE -> 72.dp
        else -> 56.dp   // STANDARD / UNRECOGNIZED
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        private const val DEFAULT_X = 24
        private const val DEFAULT_Y = 300

        /** 悬浮窗选曲 / 本地 MP3：设为音源后延时再播放的秒数。 */
        private const val LOCAL_MP3_PLAY_DELAY_SEC = 3

        /** 自定义延时上限 60s：再长就该用别的方式了，也避免误输入把语音永远压住。 */
        const val MAX_TTS_DELAY_MS = 60_000

        fun start(ctx: Context) {
            if (!Settings.canDrawOverlays(ctx)) return
            ctx.startService(Intent(ctx, FloatingWindowService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FloatingWindowService::class.java))
        }
    }
}
