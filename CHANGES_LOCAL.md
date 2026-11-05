# 相对官方 `16-1.3.4` 的本地改动摘要

基线：[`lm060719/io.mo.glassmic`](https://github.com/lm060719/io.mo.glassmic) 标签 `16-1.3.4`。

本分支版本号：`1.3.4-api100`（`versionCode=17`）。

## 主要功能改动

1. **内置离线 Piper TTS（Sherpa-ONNX）**
   - 新增 `OfflineTtsSynthesizer`，默认使用 assets 内 `vits-piper-zh_CN-xiao_ya-medium`。
   - `TtsSynthesizerFactory` 优先级：AI TTS → 内置 Piper → 系统 TTS。
   - 解决国内机型（如小米）缺少 Google TTS / 系统引擎不回调 PCM 时无法离线合成的问题。

2. **悬浮窗本地 MP3 搜索**
   - 新增 `LocalMp3Scanner`（MediaStore / 常见目录扫描）。
   - 悬浮窗菜单可搜索本机 MP3 并直接设为音源播放。

3. **播放 / PCM 链路小改**
   - `PlaybackController`、`SharedPcmPublisher` 等配合离线 TTS 与本地选曲流程调整。

4. **构建与 Xposed 兼容**
   - `settings.gradle.kts` / `gradle` 调整以支持本地依赖与 Sherpa AAR。
   - `versionName` 改为 `1.3.4-api100`；`module.prop` 同步。
   - 移除 `META-INF/xposed/java_init.list`（按本地构建目标调整入口清单）。

## 未包含

- 构建产物（`build/`、`.gradle/`、`*.apk`）未入库。
- 未跟进官方后续 `1.4.x` 主线；本分支停在 `1.3.4` 基线上的本地增强。

## 随仓库附带的本地依赖

- `app/libs/sherpa-onnx.aar`：离线 Piper TTS 运行库
- `local-m2/.../api-82.*`：Xposed API 82 离线兜底（`api.xposed.info` 不可用时）
