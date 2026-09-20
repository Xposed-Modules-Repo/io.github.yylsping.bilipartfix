# 7040300 V3 与 Smart Auto 研究结论

> 研究与实测日期：2026-09-19 至 2026-09-20。目标 APK：哔哩哔哩 7.4.0（versionCode 7040300）。设备：OnePlus PLQ110 / Android 16 / arm64-v8a。证据等级：DIRECT 为直接运行时证据，STRONG 为静态链与相邻运行时证据一致。

## 结论

7040300 没有删除 V3：`IjkMediaPlayer`、`:ijkservice`、Java option 组装和 arm64 MediaCodec/NDK MediaCodec 实现仍在。BiliPartFix 恢复用户选择权，并把 Auto 收紧为只作用于普通 UGC VOD 的 Smart Auto V2；它不替换播放器、不改 qn/服务端 codec 请求，也不拦截 IJK 的 MediaCodec → FFmpeg fallback。

V2 证明宿主 selector 确有 OMX-era bias：所有非 `OMX.*` 名称（包括 vendor C2 与 software C2）rank 都为 100。但当前设备有 `OMX.qcom.*` alias，Java 选择 alias 后实际仍创建 `c2.qti.*`，没有真实 host=false 案例。结论是 `No actionable legacy conflict found`，生产版不新增 selector 或 blacklist 绕过。

| 模式 | 发布语义 | 真机结果 |
|---|---|---|
| 自动选择（推荐） | 默认信任宿主；capability 不能单独把 host=false 改成硬解，必须再有 HIGH legacy conflict + 同流 V3 证据；UNKNOWN 沿用宿主 | 当前无新增反向 override；既有 AVC/HEVC/4K case 仍稳定硬解（DIRECT） |
| V3硬解优先（ijkplayer） | 普通非 HDR/DRM UGC 直接给宿主 IJK 硬解偏好，保留 native fallback | AVC 实测 `USER_V3`，`c2.qti.avc.decoder`（DIRECT） |
| 软解优先（兼容模式） | 普通非 HDR/DRM UGC 优先 FFmpeg；是兼容选项，不承诺 HDR/DRM 强制软件解 | H.264 1080P 无 video MediaCodec client、`ff_video_dec`×10（DIRECT） |

## 实际流与决策点

决策边界为 `tv.danmaku.videoplayer.coreV2.transformer.b.a(MediaResource,d,h$b)`。原方法调用 `MediaResource.R()` 完成实际 representation 选择；模块从返回的 `IjkMediaAsset.getDefaultVideoId()` 取得 qn，再在 `MediaResource.h()` 的 DASH 列表中匹配 `DashMediaIndex.n()`，读取：

- `k()`：codec id（7=AVC，12=HEVC）；
- `getWidth()` / `getHeight()`；
- `b().frame_rate`，并以 7040300 精确字段作容错读取；
- `d.C()` / `d.A()`：HDR/Dolby；
- `MediaResource.E()`：受保护内容。

profile、level、bitDepth 在该精确版本的稳定对象上不可可靠获得，保持 0=UNKNOWN，绝不伪造为 8-bit。实际流或 capability 不完整时 `USE_HOST`。

## Scope 与安全边界

`NormalUgcScope` 只把 `NormalVideoPlayHandler$d/$e` resolve 回调及其 preload 回调产生的同一个 `MediaResource` 实例标为普通 UGC；`WeakIdentitySet` 使用 `==`、identity hash、弱引用和 ReferenceQueue，equal-but-not-identical 对象不会跨 scope。番剧/OGV、story、互动、直播、商城、DRM、HDR/Dolby 不被 Smart Auto 粗暴改写。

## 直播结论

直播独立 factory 创建 `IjkMediaPlayer`，主动注册 Java selector，并复用同一个 `IjkCodecHelper`。冷启动直进推荐房间时，selector 选择 rank 700 的 `OMX.qcom.video.decoder.hevc.low_latency`，实际为 `c2.qti.hevc.decoder.low_latency`；1280×720 稳定约 30fps、discard=0。未观察到不必要软件解或可操作 blacklist 冲突，所以直播继续 `USE_HOST`，没有加入三模式。

## 设置与持久化

- 原生设置根页最上方入口：`bili-part-fix`，位于“账号资料”等宿主原生项之前且不显示限定模块用途的 summary；当前子页条目为 `解码模式选择`。
- `SharedPreferences: bili_part_fix.xml`，key `decoder_mode`，值 `auto` / `v3_hw` / `software`；缺失、损坏或未知值回退 `auto`。
- 模式从下一个新 media item 生效，不强拆当前或预加载 item。
- 子页使用新建、未附着的 PreferenceScreen 完整构造后一次性替换，不再 `removeAll()` 宿主屏幕；连续进入/退出、HOME 返回、旋转重建、应用重启和更新安装均已验证。

模块只持久化解码模式，不保存 Cookie/Token 等敏感数据。停用模块后该 preference 可能仍留在哔哩哔哩私有数据目录中，但不会产生后台行为。

## 运行时失败记忆

代码提供 32 项 LRU、进程内、不落盘的 `RuntimeFailureMemory`，key 包含 decoder、MIME、profile/level/bitDepth、分桶尺寸/fps 与 HDR 状态；策略、原因码与单元测试已接通。7040300 的可靠失败事件发生在隔离的 `:ijkservice` native MediaCodec 路径，主进程当前没有可准确归因回原 `MediaResource` 的稳定回调，因此生产路径暂不伪造 `recordFailure`。已验证 native fallback 继续有效；接口留给未来能可靠关联的错误回调。

## 交付索引

- `delta-from-6070800.md`：跨版本差异
- `player-codec-chain.md`：Java/进程调用链
- `native-arm64.md`：native 能力与 fallback
- `auto-policy.md`：发布决策表
- `smart-auto.md`：设计与边界完整说明
- `hook-design.md`：Hook、scope、设置与失败隔离
- `runtime-matrix.md`：真机矩阵与生命周期
- `release-audit-1.7.0.md`：Smart Auto V1 历史制品审计
- `legacy-selector.md` / `cloud-blacklist.md`：OMX/C2 与云控审计
- `smart-auto-v2.md`：保守 override 与 confidence
- `live-decoder.md` / `live-selector-matrix.md`：直播链和实机证据
- `release-audit-1.7.0-v2.md`：当前 V2 最终制品审计
- `machine-readable-summary.json`：机器可读结论
