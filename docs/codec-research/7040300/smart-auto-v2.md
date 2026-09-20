# Smart Auto V2

## 发布语义

Smart Auto V2 的默认值是信任宿主。能力表只回答“现代硬件是否声明支持当前 size/rate/profile”，不能单独回答“宿主为什么选择软件”。决策结果现在同时包含 `decision`、`reason` 与 `confidence`。

| 宿主偏好 | Capability | 额外证据 | 结果 |
|---|---|---|---|
| hardware | SUPPORTED | 无 | `PREFER_HARDWARE / HW_SUPPORTED` |
| hardware | UNKNOWN | 无 | `USE_HOST / CAPABILITY_UNKNOWN` |
| hardware | UNSUPPORTED | 无 runtime known-bad | `USE_HOST / HOST_DECISION_PRESERVED` |
| 任意 | 任意 | 同 decoder/spec 的 runtime known-bad | `PREFER_SOFTWARE / HIGH` |
| software | SUPPORTED | 原因未知 | `USE_HOST / HOST_DECISION_PRESERVED` |
| software | SUPPORTED | 已识别 `LEGACY_CODEC_RANK` + 同流同设备 V3 成功 + decoder 一致 + HIGH | `PREFER_HARDWARE / LEGACY_POLICY_CONFLICT / HIGH` |
| software | 任意 | blacklist 原因未知 | `USE_HOST` |

当前生产接线始终传入 `LegacyPolicyConflict.none()`，因为研究没有发现真实 host=false 案例；所以 V2 没有新增猜测性 host=false→hardware 纠正。显式 V3/软件模式仍为 HIGH，但 HDR、DRM、非普通 UGC 与信息不足路径先行 `USE_HOST`。

## P1/P2

- `NormalUgcScope` 和逐 item 日志均使用 `WeakIdentitySet`：`==`、`System.identityHashCode`、`WeakReference`、`ReferenceQueue`、同步访问。
- API27/28 只把排除已知软件名后的 `omx.*` 视为硬件；陌生 non-OMX（包括 vendor-looking C2）为 UNKNOWN。
- capability 在第一个普通 UGC Auto 需要时线程安全初始化一次并缓存；V3、软件、非 UGC 不触发枚举。
- failure memory 仍为 dormant/partial：数据结构、key、策略均接通，但没有可可靠关联 `:ijkservice` native failure 到主进程 `MediaResource` 的生产 recorder。

## 不变量

不改 qn、fnval、服务端 codec preference、直播策略或 IJK MediaCodec→FFmpeg fallback。UNKNOWN、HDR/DRM、特殊链始终 fail-open 到宿主。
