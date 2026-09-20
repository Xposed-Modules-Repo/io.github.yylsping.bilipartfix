# BiliPartFix 7040300 Smart Auto

## 设计目标

BiliPartFix 的 Auto 不再只是恢复旧 B 站 Auto，而是针对 7040300 和现代 Android 硬件设计的 Smart Auto：在保持画质和原生 fallback 的前提下，优先利用现代硬件解码能力，只在有明确证据时覆盖宿主决策。

它只处理当前已经选中的普通 UGC VOD 本地 decoder preference。它不改 qn、清晰度、服务端 AVC/HEVC 选流、码率、账号权限、试看范围或播放器实现。

## 为什么不直接照搬宿主 Auto

7040300 已删除旧版 codec mode 设置/消费者，普通 UGC 默认偏好硬解。V2 仍读取现代 capability，但不再把 size/rate supported 当作宿主过时的证明：宿主若选择软件，必须先识别原因，并用同流 V3 成功等运行时证据证明它属于旧 selector 冲突。否则照搬宿主决定。

## 实际 StreamInfo 来源

在 `transformer.b.a(MediaResource,d,h$b)` 内等待原方法的 `MediaResource.R()` 选完流，从返回 `IjkMediaAsset.getDefaultVideoId()` 取得实际 qn，并在同一资源的 `DashResource.h()` 列表匹配 `DashMediaIndex.n()`：

- codec id `k()`：7=AVC、12=HEVC；
- `getWidth()` / `getHeight()`；
- JSON `frame_rate`，支持整数、小数和 rational；
- item options `C()` / `A()` 的 HDR/Dolby 状态；
- `MediaResource.E()` 的受保护内容状态。

profile、level、bitDepth 在 7040300 该稳定对象中没有可信字段，保持 UNKNOWN。模块不根据画质按钮、视频标题或 qn 猜 codec/规格。

## Capability 模型与三态语义

每个 AVC/HEVC hardware decoder 是独立 `DecoderCandidate`，保留 decoderName、MIME、profileLevels、VideoCapabilities 与 probe 结果。Android 10+ 采用 `isHardwareAccelerated()`；API 27–28 排除明确的软件实现命名；普通 UGC 排除 secure-only decoder。

- `SUPPORTED`：至少一个候选的 profile/level（若已知）以及 size/rate 明确支持。
- `UNSUPPORTED`：候选集合被完整枚举，且全部明确否定当前规格，或该 MIME 明确没有硬件 decoder。
- `UNKNOWN`：枚举/查询抛错、OEM capability 缺失、profile 数据不完整或候选结果仍不确定。

UNKNOWN 是“无法证明”，不是“不支持”；它永远 fail-open 到 `USE_HOST`。AVC 和 HEVC、不同 decoder/MIME 的异常互不污染。

## 决策流程

```text
非普通 UGC                         -> USE_HOST
DRM/受保护内容                     -> USE_HOST
Auto 且实际流不完整                -> USE_HOST
HDR/Dolby 或 HDR 状态不确定         -> USE_HOST
用户 V3                            -> PREFER_HARDWARE
用户软件模式                        -> PREFER_SOFTWARE
Auto + failure-cache hit                         -> PREFER_SOFTWARE / HIGH
Auto + host=true + capability SUPPORTED          -> PREFER_HARDWARE
Auto + host=true + UNKNOWN/UNSUPPORTED            -> USE_HOST
Auto + host=false + HIGH proven legacy conflict  -> PREFER_HARDWARE / HIGH
Auto + host=false + 其它情况                       -> USE_HOST
```

当宿主 preference=false 时，必须同时具备已识别的 `LEGACY_CODEC_RANK`、现代硬件明确支持、同设备同流 V3 成功、decoder 一致和 HIGH confidence 才可纠正。当前没有找到真实案例，生产接线传入 `LegacyPolicyConflict.none()`。输出同时带稳定 `Reason + Confidence`。

## 高级画质策略

- AVC 1080P/1080P60、HEVC 1080P：能力明确支持即硬解。
- 4K/4K60：不使用“4K→软件”的粗糙规则；实际 codec、尺寸和 fps 能力明确支持才硬解。
- 本机已验证 AVC 3456×2160@58.824、4096×1716 及 AVC 1080P60 均可由 `c2.qti.avc.decoder` 稳定处理。
- 不为追求低码率修改服务端 codec preference；功耗优化限定为同一已选码流的本地解码方式。

## HDR 策略

HDR/Main10/10-bit 还牵涉 Surface、色彩 metadata、renderer 和 7040300 下游强制硬解逻辑。当前 representation 无法可靠给出 profile/bitDepth，因此 HDR、Dolby 或 HDR 状态不确定均 `USE_HOST`。软件模式文案是“普通视频优先软件解”，不承诺 HDR/DRM 强制软件；模块不清空 HDR/DRM 标记。

## Runtime failure memory

实现了上限 32 项的进程内 LRU，key 包括 decoderName、MIME、profile/level/bitDepth、分桶宽高/fps、HDR；只记失败、不记永久成功，进程退出即清空，不落盘、不含用户数据。命中时输出 `RUNTIME_KNOWN_BAD / PREFER_SOFTWARE`。

当前可靠 MediaCodec init/error 发生在隔离的 `:ijkservice` native 层，尚无稳定回调能把该事件精确映射回主进程的 `MediaResource` 与 stream key。发布实现因此没有伪造失败记录；数据结构、接口、决策和单测保留，待发现可靠关联点再接入。IJK 自身的 MediaCodec failure → FFmpeg fallback 已直接验证且未被删除。

## Scope guard

只有 `NormalVideoPlayHandler$d/$e` resolve 及 normal preload 回调产生的同一 `MediaResource` 被弱引用标记为普通 UGC。未标记的 OGV/PGC、story、互动、直播、商城和其他特殊路径 `USE_HOST`。番剧 ss41410 试看实测为 `HOST_SCOPE_UNSUPPORTED`。

## 实际测试结果

| 场景 | Smart Auto / actual 结果 |
|---|---|
| AVC 1080P60 | 1920×1080@62.5、qn116，`SUPPORTED/HW_SUPPORTED`；actual AVC HW，render 60、discard 0 |
| HEVC 1080P | 1920×1080@25/30.303，`SUPPORTED/HW_SUPPORTED`；actual HEVC HW |
| 4K-class | AVC 3456×2160@58.824、qn120，`SUPPORTED/HW_SUPPORTED`；actual AVC HW，render 60、discard 0 |
| V3 AVC | `USER_V3/PREFER_HARDWARE`；actual `c2.qti.avc.decoder` |
| Software AVC | `USER_SOFTWARE/PREFER_SOFTWARE`；无 video MediaCodec client，`ff_video_dec`×10 |
| OGV | `scope=host/HOST_SCOPE_UNSUPPORTED/USE_HOST` |
| 1080P→720P | UI 成功落到 720P；actual HEVC HW 重建为 1280×720，render≈30、discard 0 |
| forced MediaCodec failure | invalid codec name 后无 HW client，FFmpeg 线程存活且播放进程不崩溃 |

单元测试还覆盖 UNKNOWN/UNSUPPORTED、AVC/HEVC、1080P60/4K60、failure cache、HDR、DRM、显式模式、非 UGC、损坏 preference、fps rational 和 secure-only decoder 名称。

## 已知限制

- profile/level/bitDepth 当前只能保持 UNKNOWN；如未来获得可信字段，capability 已支持接入。
- failure memory 尚无可靠生产记录源，不会凭 native 泛化错误伪造 known-bad。
- 每个新 `MediaResource` 最多一条 Debug 明细；同 item 画质切换可能只从 codec 运行时证据观察到重建。
- 实机功耗传感器 `current_now` 返回 0；短窗口 CPU ticks 仅说明同流硬解比软件解方向上更省 CPU，不外推跨设备绝对功耗。
