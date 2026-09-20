# 7040300 Smart Auto policy

## 输入与输出

纯策略入口接收 `Mode`、`Scope`、实际 `StreamInfo`、三态 `CapabilityProvider`、宿主硬解偏好和进程内 failure memory，返回：

```text
Decision = USE_HOST | PREFER_HARDWARE | PREFER_SOFTWARE
Reason   = HOST_SCOPE_UNSUPPORTED | DRM_HOST_SAFE | HDR_HOST_SAFE |
           USER_V3 | USER_SOFTWARE | STREAM_UNKNOWN | HW_SUPPORTED |
           CAPABILITY_UNKNOWN | RUNTIME_KNOWN_BAD |
           HOST_DECISION_PRESERVED | LEGACY_POLICY_CONFLICT
Confidence = NONE | LOW | MEDIUM | HIGH
```

实际 `StreamInfo` 来自宿主已经解析并选中的 DASH representation，而非 UI 画质文字；稳定字段为 codec MIME、width、height、fps、qn，另读取 HDR/Dolby 和受保护内容状态。profile/level/bitDepth 取不到时保持 UNKNOWN。

## 发布决策表

| 条件（按优先顺序） | Decision | Reason |
|---|---|---|
| 非 `NormalVideoPlayHandler` 普通 UGC | `USE_HOST` | `HOST_SCOPE_UNSUPPORTED` |
| DRM/受保护内容 | `USE_HOST` | `DRM_HOST_SAFE` |
| Auto 且实际流信息不完整 | `USE_HOST` | `STREAM_UNKNOWN` |
| HDR/Dolby 或 HDR 状态不确定 | `USE_HOST` | `HDR_HOST_SAFE` |
| 用户 V3 | `PREFER_HARDWARE` | `USER_V3` |
| 用户软件兼容模式 | `PREFER_SOFTWARE` | `USER_SOFTWARE` |
| Auto 且相同 decoder/spec 失败记忆命中 | `PREFER_SOFTWARE` | `RUNTIME_KNOWN_BAD / HIGH` |
| 宿主=true，至少一个硬件 decoder 明确支持当前规格 | `PREFER_HARDWARE` | `HW_SUPPORTED`（与宿主一致，不是反向 override） |
| 宿主=true，所有候选声明不支持 | `USE_HOST` | `HOST_DECISION_PRESERVED` |
| 宿主=false，仅有 size/rate supported | `USE_HOST` | `HOST_DECISION_PRESERVED` |
| 宿主=false，HIGH legacy rank conflict + 同流 V3 成功 + decoder 一致 | `PREFER_HARDWARE` | `LEGACY_POLICY_CONFLICT / HIGH` |
| 枚举/查询异常、OEM 数据缺失或结果混合不确定 | `USE_HOST` | `CAPABILITY_UNKNOWN` |

`HW_SUPPORTED` 本身不再能纠正 host=false。只有能解释宿主 false 的旧策略原因、现代 capability、同流同设备 V3 成功与 HIGH confidence 同时成立时才允许纠正。当前生产接线没有真实 conflict，传入 `LegacyPolicyConflict.none()`。UNKNOWN 永远不当作 UNSUPPORTED；单个 codec/MIME 查询失败不会污染其他候选或另一个 MIME。

## Capability 模型

第一次普通 UGC Auto 真正查询时才枚举 `MediaCodecList.ALL_CODECS`，结果线程安全地缓存到进程结束。Android 10+ 使用 `isHardwareAccelerated()`；API 27–28 先排除明确软件名，只把剩余 `omx.*` 当作硬件，陌生 non-OMX 返回 UNKNOWN。普通非 DRM 路径排除 `.secure` secure-only decoder。

逐流判断先校验已知 profile/level，再调用 `areSizeAndRateSupported(width,height,fps)`。任何 API/OEM 异常返回 UNKNOWN；有任一候选明确支持即 SUPPORTED；存在 UNKNOWN 且没有支持结果时整体 UNKNOWN；只有所有结果都明确否定才 UNSUPPORTED。

## 高级画质和功耗

- AVC 1080P、1080P60、HEVC 1080P：能力明确支持则硬解。
- 4K/4K60：不按分辨率直接软件解；本机 3456×2160@58.824 和 4096×1716 已实际硬解。
- HDR/Main10/10-bit：当前稳定 representation 不提供 profile/bitDepth，且 HDR 牵涉 renderer/color metadata，所以沿用宿主，不强制软件。
- 不改 qn、fnval、服务端 AVC/HEVC 选流或码率。优化目标是在相同已选码流下降低本地 CPU 解码负载。

## Failure memory 与 fallback

`RuntimeFailureMemory` 是上限 32 项的进程内 LRU，重启清空，不落数据库/SharedPreferences。由于 native fallback 位于 `:ijkservice` 且当前没有可可靠关联主进程 stream key 的错误回调，发布版只提供已接通的接口、key 和决策原因，不伪造记录。IJK 原生 MediaCodec 初始化失败 → FFmpeg fallback 已用 invalid codec name 直接验证，模块未拦截该链。

## Debug 日志

Debug 每个新的 `MediaResource` 最多记录一次统一前缀 `BiliPartFix/SmartAuto:`，包含 mode、scope、host、codec/profile/level/bitDepth、size/fps/HDR/qn、decoder、capability、failure-cache、decision、reason。清晰度切换若复用同一 `MediaResource` 不重复刷日志；actual decoder 以资源管理器/codec 运行时证据确认。Release 关闭这些逐 item 明细。
