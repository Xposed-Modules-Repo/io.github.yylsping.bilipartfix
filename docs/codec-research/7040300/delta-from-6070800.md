# delta-from-6070800.md — 6070800（6.7.0 Sony 定制版）→ 7040300（7.4.0）解码链路差异报告

> 研究日期：2026-09-19。证据基线：7040300 DEX 静态（JADX，`history-apks/哔哩哔哩_7.4.0.apk`）+ 7040300 arm64 native 字符串（`research/7040300/native/`）。
> 规则遵循：6070800 仅作搜索地图；下表"7040300"列全部为本版本独立验证结果。

## 7040300 验证清单（migration anchors 复核结果）

| 检查项 | 结果 | 证据 |
|---|---|---|
| `IjkMediaPlayer` 是否仍存在 | **存在**（真名 `tv.danmaku.ijk.media.player.IjkMediaPlayer`） | JADX get_class_source |
| 6070800 mediacodec option strings 是否仍存在 | **全部存在**（Java 组装层 + native 双侧） | `IjkMediaPlayerItem.setItemOptions`；libijkplayer.so 字符串 |
| codec mode config reader 是否还存在 | **已删除**。旧 key 仅被设置迁移类读取；新 proto 字段零消费者 | 见下"设置链路" |
| player factory 是否仍为同 lineage | **否**。6070800 `tv.danmaku.biliplayer.basic.adapter.*` 包整体移除；7040300 走 `tv.danmaku.videoplayer.coreV2.transformer.*` | 全库类搜索 |
| JNI 路径是否仍保留 | **保留**。`IjkMediaPlayerService` + `:ijkservice` 进程仍在 Manifest；`IjkMediaPlayerItem` 仍是 AIDL 代理 | Manifest / 类源码 |
| arm64 native MediaCodec decoder 是否仍存在 | **存在**，且新增 NDK MediaCodec（AMediaCodec）路径 | libijkplayer.so: AMediaCodec×58, enable_ndk_mediacodec×2 |
| Auto selector 是否被删除/替换 | **设置层 Auto 已删除**；Java selector（IjkCodecHelper rank 表）保留且规则与 6070800 完全一致 | IjkMediaCodecInfo.setupCandidate |
| hardware fallback 是否仍存在 | **native 层保留且实机故障注入通过**；Java 层 SwitchablePlayerAdapter 已移除 | libijkplayer.so；invalid codec name 后无 HW client、`ff_video_dec`×10 且进程继续 |

## 总表

| Layer | 6070800 | 7040300 | Same lineage? | Impact |
|---|---|---|---|---|
| Settings UI | `PreferenceTools$CodecModeFragment` + `RadioGroupPreference`（播放设置→解码模式选择，三选项，埋点 `player.player.decoding-mode.0.click`） | **完全移除**：无"V3硬解"/"软解优先"字符串、无 decoding-mode 埋点、无对应 preference | 否 | 设置入口需从零注入（settings2 新架构） |
| stored values | `pref_player_codecMode_key` String "0"/"4"/"1"（bili_main_settings_preferences.xml） | 旧 key 仅被 `com.bilibili.app.comm.list.common.migration.c$e` 一次性迁移到 proto `PlayConfig.playerCodecModeKey`（映射 0→0, 4→1, 1→2）；**该 proto 字段在全 DEX 零读取者**（get/has 均只有 Builder 内部引用） | 否 | 宿主 codec mode 是死配置；模块必须自建持久化（符合 prompt §13） |
| config reader | `m3.a.c.h.b.c.a(Context)` 读 key 得 int | 不存在等价物 | 否 | — |
| player factory / 决策点 | `tv.danmaku.biliplayer.basic.adapter.h.c.d()`：mode==1‖2 → {IJK, mc=false}，else → {IJK, mc=true} | 整个 `tv.danmaku.biliplayer.basic.adapter` 包移除。新链路：`tv.danmaku.biliplayerv2.utils.f.b()`（NormalVideoPlayHandler/OGV/story/互动视频/商城 全部调用）构建 `transformer.d.a`，**`i(true)` 硬编码** → `transformer.b.a()`（IjkMediaItemTransformer）`mEnableHwCodec = dVar.g()`（builder 默认 true） | 否（架构换代），但语义后果：7040300 UGC 恒硬解优先 | 模块的 V3优先 = 保持默认；软解优先 = 覆盖 `d.g()` 或 `mEnableHwCodec` 为 false |
| IjkMediaPlayer | `tv.danmaku.ijk.media.player.IjkMediaPlayer` 真名 | **同名同类保留** | 是 | 真名锚点有效 |
| setOption wrapper / option 组装 | `IjkMediaPlayerItem` category Bundle 下发，cat3: mediacodec=1 / mediacodec-hevc=1 / mediacodec-default-* | `tv.danmaku.ijk.media.player.IjkMediaPlayerItem.setItemOptions()` 结构一致：mEnableHwCodec→`mediacodec=1`(+`mediacodec-hevc=1`)；getCodecName 非空→`async-init-decoder`/`video-mime-type`/`mediacodec-default-name`；另新增 `enable_ndk_mediacodec(_async)`、HDR、loudnorm 等 | 是（扩展） | option 组装点 Hook 仍可用 |
| MediaCodec options（native option table） | mediacodec/-avc/-hevc/-mpeg2/-mpeg4/-auto-rotate/-handle-resolution-change/-sync/-default-* | 全部保留（libijkplayer.so 逐字符串命中） | 是 | — |
| codec selector | `IjkCodecHelper.getBestCodecName` + `IjkMediaCodecInfo.setupCandidate` rank 制：非 omx. 前缀=100（<600 拒绝），c2-only 设备选空 | **rank 规则逐条一致**（含 known-list、cloud block 正则、unused-low-latency 品牌表）；c2-only 设备仍选空 | 是 | 恢复 V3 不依赖 Java selector；native 默认 codec 路径即可 |
| JNI / 进程模型 | AIDL：主进程 `IjkMediaPlayerItem` → `:ijkservice`（IjkMediaPlayerService/ItemClient，native _setOption） | 同。Manifest 仍声明 `IjkMediaPlayerService` process=`:ijkservice`；`onSelectCodec` JNI 字符串保留（libijkplayer×2, libijksdl×11） | 是 | — |
| native decoder creation | ffpipeline mediacodec，option-table 驱动 | 保留（ffpipenode×20）+ 新增 NDK AMediaCodec 路径（enable_ndk_mediacodec，默认 false，云控 `ijk.d.l0()`） | 是（扩展） | NDK 路径是 7040300 新增变量，Auto policy 可考虑 |
| fallback | ① native：MediaCodec 失败→"will try fallback to ffplay decoder" 退 FFmpeg；② Java：SwitchablePlayerAdapter 重试≤2 次重建 (IJK,mc=true)→(IJK,mc=false) | ① native 字符串保留，且故障注入后实际退 `ff_video_dec`；② SwitchablePlayerAdapter 不存在，`BasePlayerEventCodecConfigChanged` 仅见于直播侧 | 部分 | 模块保留 native 一级兜底，不重建 Java 二级兜底 |
| profile gating | H264 HIGH_10/422/444/CAVLC444 禁硬解 | HIGH_10 字符串保留（×4），推定同策略 | 是 | — |
| Auto policy | Java 层 Auto≡V3；智能=native 兜底+Java 重建+云控 blacklist | 无 mode 概念，恒 `mEnableHwCodec=true`；云控 fake-name/blacklist 机制保留（mCodecFakeNameString ← `ijk.d.G()`） | 否 | 模块 Auto 需自建决策层（prompt §10/§11） |
| player lifecycle | seek flush 不重建；切 P 重建 decoder；后台重进完整 prepare | seek 保持硬解；切 P 在同一 `:ijkservice` 内移除旧 client 并创建新 decoder；前后台 PID 保持 | 部分相同 | 7040300 当前样本的前后台无需完整重建 |
| HEVC 开关 | `pref_key_is_ijkplayer_enable_h265`（bili_ijk_settings_preferences.xml） | 7040300：`mEnableH265Codec = ijk.d.u0(application)`（云控/能力检测，待确认细节） | 待细化 | — |

## V3 状态分类结论

**7040300 属于 C 类的变体：native + Java plumbing 完整保留，且宿主策略层已把 VOD 默认钉死在硬解优先（`enableHwCodec` 恒 true），同时删除了用户可见的 codec mode 选择层。**

对模块目标的直接影响：

1. "恢复 V3 硬解"在 7040300 上大概率无需强行注入——宿主默认即硬解。**重点转为**：提供"软解优先（兼容模式）"开关（覆盖 `transformer.d.g()`/builder `i()` 或 option 组装层），以及可控 Auto。
2. Hook 点候选（按稳定性）：
   - P0：`tv.danmaku.videoplayer.coreV2.transformer.d.g()`（boolean getter，单点控制 mEnableHwCodec 输入；所有 VOD 调用点共用）；
   - P0 备选：`tv.danmaku.videoplayer.coreV2.transformer.b.a()` 内 `mEnableHwCodec` 赋值后修改（字段直写，更下游）；
   - P1：`tv.danmaku.ijk.media.player.IjkMediaPlayerItem.setItemOptions()`（option 组装层，可增删 mediacodec 键，最接近 6070800 验证过的层）。
3. 直播链路（blps playerwrapper/xplayer）仍保留 PlayerCodecConfig 决策结构（`blps.playerwrapper.context.b`），与 VOD 分治；模块首版只覆盖 UGC VOD。
4. 注意 `IjkMediaPlayerItem` 中 HDR 分支（`mHdrVideoType != 0`）会强制 `mEnableHwCodec=true` 且 `mEnableH265Codec=true`——软解模式对 HDR 流会被宿主强制改回硬解，Auto/软解策略需记录该例外（静态实证，`setItemOptions` L2244-2249）。

## runtime 收敛状态

- [x] UGC actual decoder：AVC/HEVC 均由 resource manager 直读。
- [x] native fallback：MediaCodec 创建故障注入后退 FFmpeg。
- [x] seek/切 P/前后台/全屏生命周期。
- [~] 切清晰度：面板和 4K actual 已验证，面板内切换自动化未完成。
- [ ] VOD Java 二级重建兜底：未发现等价类，也未观察到该层事件。
- [ ] `ijk.d.u0()` 与 `ijk.d.l0()` 的完整云控来源；不影响当前 setter Hook 与 actual decoder 结论。
