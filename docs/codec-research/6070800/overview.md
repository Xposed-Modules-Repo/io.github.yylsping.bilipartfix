# 6070800（哔哩哔哩 6.7.0 Sony Xperia 1 III 定制版）解码链路研究 — 总览

> 研究日期：2026-09-18。证据基线：静态 JADX 反编译（与设备安装包同 SHA-256）+ 实机运行时验证。
> **重要环境说明**：本次实机为 OnePlus PLQ110（SM8750 / 骁龙 8 Elite，Android 16，arm64-v8a），**不是 Xperia 1 III**。Sony 专属运行时分支（若有）在此机不会被触发；本报告中"实机证实"均指该机。静态（DEX/native/资源）结论与机型无关。

## 样本身份（Confidence: HIGH）

| 项 | 值 |
|---|---|
| packageName | tv.danmaku.bili |
| versionName / versionCode | 6.7.0 / 6070800 |
| targetSdk / minSdk | 29 / 17 |
| APK | 单 base.apk，无 split，46,011,619 bytes |
| APK SHA-256 | `e1143c19d3cb46bfe56fa26c076127a2a6c740664c747114d59190728bd43595`（本地样本与设备安装包一致） |
| ABI | 仅 arm64-v8a（无 armeabi-v7a） |
| 进程 bitness | 主进程 64-bit（/proc maps 实证） |
| 签名 | v2，证书摘要 id 1b503d7e |
| Sony 渠道机制 | 运行时读取 `/oem/deletable-app/sony_bili_channel.txt`（预装渠道归因），APK 内无渠道文件 |

## 核心结论一页版

- **V3 真实存在且 = IjkMediaPlayer + Android MediaCodec**（Confidence: HIGH）。UI 文案"V3硬解优先 (ijkplayer)"，stored value = `"4"`。
- **三模式存储值**（key `pref_player_codecMode_key`，String 类型，文件 `bili_main_settings_preferences.xml`，默认 `"0"`）：
  - Auto（自动选择（推荐））= `"0"`
  - V3（V3硬解优先 (ijkplayer)）= `"4"`
  - Software（软解优先（兼容模式））= `"1"`
- **Auto 与 V3 在 Java 决策层完全一致**（HIGH）：`tv.danmaku.biliplayer.basic.adapter.h.c.d()` 只在 mode==1 或 2 时关闭硬解；0 和 4 走同一条 `IJK_PLAYER + use_ijk_media_codec=true` 路径。"Auto" 的"智能"不在 Java 选择层，而在：native mediacodec 失败自动退 ffplay + Java 层 SwitchablePlayerAdapter 重试重建 + 云控 blacklist。
- **实际 decoder（本机实机，HIGH）**：
  - Auto/V3：`c2.qti.hevc.decoder.low_latency`（dumpsys media.resource_manager 直接证据，512x288 HEVC 流）
  - SW：FFmpeg 软解（ijkservice 内 ff_read/ff_video_dec/ff_vout 全套线程，无任何 MediaCodec 活动）
- **MediaCodec 失败 fallback（实机命中，HIGH）**：QC2 v4l2 HEVC buffer 分配失败 → `MediaCodec:AMEDIACODEC__UNKNOWN_ERROR` → native 字符串 `"will try fallback to ffplay decoder"` → ijk 内部退 FFmpeg 软解；Java 层另有 SwitchablePlayerAdapter 重试 2 次后 (IJK,mc=true)→(IJK,mc=false) 重建的二级兜底（静态）。
- **Java codec selector 在 Android 10+ c2-only 设备上失效**（HIGH）：`IjkCodecHelper` rank 规则中非 `omx.` 前缀 codec 一律 rank=100（<600 拒绝），c2.qti.* 全部落选 → `mediacodec-default-name` 不下发 → native 走 "use default codec"（平台默认选择）。
- **Sony 特化：不存在 codec 层特化**（HIGH）。全库仅发现：预装渠道读取（g0 → /oem/deletable-app/sony_bili_channel.txt）、品牌名显示映射（sony→索尼）、索尼音乐**内容源**标记（bundle_key_sony_video_source，禁后台听视频）。无 Sony decoder policy、无 V3 默认值修改、无 codec 黑白名单差异。
- **播放器进程模型**：UI/决策在主进程，native 播放器经 AIDL 跑在 `:ijkservice` 进程（IjkMediaPlayerService / IjkMediaPlayerItemClient，native _setOption）。

## 对 7040300（7.4.0）V3 恢复最重要的迁移结论

1. 最短 Hook 点候选：player 决策点（6070800 的 `h.c.d()` 结构：输入 codecMode int，输出 {player backend, use_ijk_media_codec boolean}）或 IjkMediaPlayer option 组装点（`mediacodec`/`mediacodec-hevc`/`mediacodec-default-name` 字符串定位）。
2. 真名锚点（7.4 极可能仍在）：`tv.danmaku.ijk.media.player.IjkMediaPlayer`、`IjkMediaCodecInfo`、`IjkCodecHelper`、`IjkMediaPlayerService`（:ijkservice）。
3. native 锚点字符串：`mediacodec`、`mediacodec-hevc`、`will try fallback to ffplay decoder`、`amc: use default`。
4. 不要指望 Java selector 在新设备上选出 c2.* codec —— 恢复 V3 的关键是让 `mediacodec=1` 下发 + native 默认 codec 路径可用。

## 文档索引

- `settings-chain.md` — UI→资源→key→存储→读取→决策 全链
- `player-codec-chain.md` — Settings→Player→IjkMediaPlayer→JNI→native→MediaCodec 全链
- `native-arm64.md` — so 清单/哈希/字符串/JNI/fallback
- `runtime-matrix.md` — 三模式实机对照表
- `auto-policy.md` — Auto 决策还原（FACT/INFERENCE/UNKNOWN 标注）
- `sony-xperia1iii.md` — Sony 特化排查
- `migration-to-7040300.md` — 迁移报告（含 P0/P1/P2）
- `machine-readable-summary.json` — 机器可读摘要
