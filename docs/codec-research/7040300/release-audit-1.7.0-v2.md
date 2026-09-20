# BiliPartFix 1.7.0 / 7040300 Smart Auto V2 发布审计

审计日期：2026-09-20
目标宿主：哔哩哔哩 7.4.0（7040300）
实机：OnePlus PLQ110 / Android 16 / arm64-v8a

## 结论

Smart Auto V2 达到本轮发布协议的最低发布标准。两个 P1 已修复；P2 已实现或如实标为 dormant；VOD override 已收紧为证据驱动；直播研究未发现值得产品特制化的高置信冲突，因此保持 `USE_HOST`，没有把实验 hook 带进 release 行为。

## 代码与策略审计

- `NormalUgcScope` 使用 `WeakIdentitySet`：比较使用 `==`，hash 使用 `System.identityHashCode`，通过 `ReferenceQueue` 清理；equal-but-not-identical 对象不会共享 scope。
- `host=true`：现代能力为 `UNSUPPORTED` 或 `UNKNOWN` 时均保持宿主，只有同流 runtime known-bad 才偏好软件。
- `host=false`：只有 `HIGH` 置信度的 `LEGACY_CODEC_RANK` 冲突、现代硬解明确支持、且同流 V3 已成功时才可偏好硬件。生产环境本轮没有发现这类冲突，因此没有新增 host=false override。
- 决策结果包含 `decision + reason + confidence`；`UNKNOWN` fail-open。
- API 29+ 使用 `MediaCodecInfo.isHardwareAccelerated()`；API 27/28 仅把明确软件名判为软件、其余 OMX 判为硬件，陌生非 OMX 名称判为 `UNKNOWN`。
- capability provider 仅在首次普通 UGC 自动评估时初始化，线程安全且进程内只初始化一次；非 UGC 与强制模式不会触发初始化。
- failure-memory 数据结构仍存在，但 production recorder 未挂接，状态为 `dormant-partial`。原因是 `:ijkservice` 的 native 初始化失败目前无法可靠关联到主进程的具体 `MediaResource`/stream key；未伪造跨进程归因。
- HDR、DRM、OGV/PGC、story、互动、直播、商城等特殊链继续由宿主处理；模块不改变画质或服务端 codec preference；native fallback 保留。
- 设置根页读取现有 Preference 的最小 order 后取前一位；实机确认 `bili-part-fix` 位于“账号资料”上方。入口不设置 summary，当前解码模式说明只保留在模块子页内。

## VOD selector 与 cloud 结论

直接静态证据确认 7040300 使用 `IjkCodecHelper.getBestCodecName` 与 `IjkMediaCodecInfo.setupCandidate`。selector 存在 OMX 时代偏置：所有非 `omx.*` 名称统一 rank 100，而可接受阈值为 600，因此原始 `c2.qti.*` 与 `c2.android.*` 无法在 rank 层区分；当前设备最终由 `OMX.qcom.*` alias 入选，再映射到实际 `c2.qti.*` 硬件组件。

已检查 decoder 相关 cloud/config，包括 fake-name、H.265 CPU blacklist、weak-H.265、variable-codec blacklist、NDK/power blacklist、low-latency device policy 与 hardware fallback。当前设备未观察到现代 C2 被错误 blacklist，也未找到真实的 `host=false -> 同流 V3 稳定硬解` 案例。OnePlus low-latency policy会把 VOD low-latency alias 降到 rank 600，但标准 OMX alias 仍以更高 rank 正常选择；没有绕过任何 blacklist。

## 直播结论

- backend：`com.bilibili.bililive.playercore.media.ijk.c` 创建 `tv.danmaku.ijk.media.player.IjkMediaPlayer`。
- selector：直播复用 `IjkCodecHelper.getBestCodecName`，因此也继承相同的 OMX/C2 rank 模型。
- 推荐直播间实测 HEVC：Java selector 选择 `OMX.qcom.video.decoder.hevc.low_latency`（rank 700），实际组件为 `c2.qti.hevc.decoder.low_latency`，1280x720、约 30 fps，稳定样本 discard=0。
- VOD-first 后直播也观察到标准 `c2.qti.hevc.decoder`，说明共享进程内的 low-latency device 状态会影响 alias 排名，但两种路径均为硬解。
- 未观察到不必要的软件解码，未发现 live-specific 的高置信 legacy conflict；AVC 实际直播 decoder 未取得，按未知项记录。
- 因宿主直播已经稳定选择现代 vendor C2 硬解，本轮没有进行无必要的五分钟强制 override 功耗实验，也未实现 Live Smart Auto。最终建议：`USE_HOST`。

## 测试与构建

使用 JDK 17 执行：

```text
.\gradlew.bat clean testDebugUnitTest assembleRelease --no-daemon
```

结果：`BUILD SUCCESSFUL`，68 个 Gradle task；debug unit tests 共 37 个，failures=0、errors=0、skipped=0。覆盖 weak identity、保守 override、API 27/28 uncertain identity、lazy capability，以及既有协议/稍后再看回归测试。由于未实现 Live policy，协议所述 Live policy 单测不适用。

## 最终 APK

| 字段 | 值 |
|---|---|
| 路径 | `dist/BiliPartFix-v1.7.0-7040300-smart-auto-v2-release.apk` |
| package | `io.github.yylsping.bilipartfix` |
| versionName | `1.7.0` |
| versionCode | `11` |
| variant | `release` |
| debuggable | `false` |
| 大小 | `69,493 bytes` |
| APK SHA-256 | `c2725f905844df7253f27ccd013b7f2f76934b8013b24c109e7f4c6b4b8f7760` |
| 签名 | APK Signature Scheme v2，验证通过，1 signer |
| certificate DN | `CN=BiliPartFix, OU=Release, O=yylsping, C=CN` |
| certificate SHA-256 | `6f5db5717ea593d309074c527b72b8882ed34f343f5ff72a7f99c43b50ab6fef` |

`apksigner verify --verbose --print-certs` 返回 `Verifies`；使用仓库既有 release 身份。未记录或输出私钥、密码。

## 已知限制

- 当 DASH representation 没有稳定暴露字段时，profile/level/bitDepth 仍为未知；这不会放宽 override。
- failure-memory 等待可靠的跨进程失败归因点后才可启用 production recorder。
- 未取得真实 VOD host=false 冲突案例，因此本版明确保持宿主，而非臆造纠正规则。
- 直播 AVC actual decoder、稳定的端到端 latency 与五分钟对照功耗未测；已验证的 HEVC host path 无异常，不构成阻塞发布的理由。
