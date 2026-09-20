# 7040300 实机解码矩阵

> 日期：2026-09-19。设备：OnePlus PLQ110（SM8750，Android 16，arm64-v8a）。模块：BiliPartFix 1.7.0 debug + 用于暴露会员画质选项的 bili hook。actual decoder 以 `dumpsys media.resource_manager` client Name 为主证据，并用 `/proc` 线程和 OplusFeedbackInfo 交叉验证；UI 标签和 `mediacodec=1` 均不单独算硬解证据。

## 编解码与模式主表

| Case | 样本/流 | 模式/干预 | actual decoder 与帧率 | CPU/温度短样本 | 结果 |
|---|---|---|---|---|---|
| B0 | av170001，HEVC 512×288 | 宿主原始基线 | `c2.qti.hevc.decoder` | 旧基线 | 宿主 7040300 默认硬解（DIRECT） |
| H1 | av80433022，H.264 1080P | Frida 固定 qn80/H264，硬解 | `c2.qti.avc.decoder`，1920×1080 | 70 ticks/5s | V3 H.264 1080P PoC 成功（DIRECT） |
| S1 | 同 H1 | setter 强制 false | 无 video codec client；`ff_video_dec`×10 | 254 ticks/5s | 纯软件视频解码，约为同流硬解 CPU ticks 的 3.6 倍（DIRECT，短样本） |
| F1 | 同 H1 | V3；把请求 codec name 改成不存在值 | 无 video codec client；`ff_video_dec`×10，播放进程继续 | 291 ticks/5s | MediaCodec 初始化失败后 native 回退软件（DIRECT） |
| A1 | av80433022，HEVC 1080P | 模块 Auto | `c2.qti.hevc.decoder`，1920×1080；input/output≈24，render≈25，discard=0 | 未同流对照 | Auto 硬解正常（DIRECT） |
| A2 | av20244965，H.264 1080P60 | 模块 Auto | Smart Auto：AVC 1920×1080@62.5、qn116、`SUPPORTED/HW_SUPPORTED`；actual `c2.qti.avc.decoder`，60/60/60fps，discard=0 | 198 ticks/5s，33.7°C | 1080P60 硬解正常（DIRECT） |
| A3 | BV1Pv4y1q7H5，画质面板选 4K | 模块 Auto | 标题称 HEVC，但 actual 为 `c2.qti.avc.decoder`；3456×2160，output 59–61fps，render 60，discard=0 | 287 ticks/5s，33.6°C | 4K-class 60 硬解正常；以 actual 为准（DIRECT） |
| A4 | BV1eD4y1D77L，多 P 4K60 测试 | 模块 Auto | `c2.qti.avc.decoder`，4096×1716 | 未对照 | 超 4K 宽度硬解正常（DIRECT） |
| A5 | 长视频，1080P→720P | 模块 Auto，人工画质切换 | 切换前 HEVC 1920×1080@30.303、qn112、`SUPPORTED/HW_SUPPORTED`；切换后 actual `c2.qti.hevc.decoder` 1280×720、render≈30、discard=0 | 未对照 | UI 显示“切换中”后落到 720P，decoder 重建且播放正常（DIRECT） |
| G1 | 番剧 ss41410 试看 | 模块 Auto | `scope=host, decision=USE_HOST, reason=HOST_SCOPE_UNSUPPORTED` | 不适用 | OGV 不受普通 UGC Smart Auto 改写（DIRECT） |

设备 `/sys/class/power_supply/battery/current_now` 始终返回 0，无法给出可信的平均电流/功率。CPU ticks 是相同服务进程的短窗口方向性数据，不能跨样本比较绝对功耗。

## 三模式真机切换

| 模式 | 持久化 | 新 media item 日志 | 资源证据 |
|---|---|---|---|
| `software` | XML 为 `software` | `PREFER_SOFTWARE / USER_SOFTWARE` | H.264 1080P 无视频 MediaCodec，`ff_video_dec`×10 |
| `v3_hw` | XML 为 `v3_hw` | AVC 1280×720@29.412，`PREFER_HARDWARE / USER_V3` | `c2.qti.avc.decoder`，render≈30、discard=0 |
| `auto` | XML 为 `auto` | 实际流 + `SUPPORTED / HW_SUPPORTED` | HEVC/AVC/4K60 均有 c2.qti HW client |

最终设备状态已恢复 `auto`。模式切换的确定边界是冷启动或新 media item；当前/预加载 item 可能不会重跑 transformer。

## 生命周期与兼容性

| 项目 | 结果 | 证据/限制 |
|---|---|---|
| 切清晰度 | **PASS** | 人工执行 HEVC 1080P→720P；先出现“切换中，请稍候”，随后 UI 为 720P；同一 `:ijkservice` 中旧 1920×1080 client 被新 `c2.qti.hevc.decoder` 1280×720 client 替代，render≈30、discard=0，播放正常。切换复用同一 `MediaResource`，按“每新 item 一次”去重规则没有第二条 Smart Auto 明细 |
| 切 P | **PASS** | BV1eD4y1D77L P1→P2；`:ijkservice` PID 8533 不变，旧 client 移除、新 client 创建，decoder 重建为 AVC 4096×1716 |
| seek | **PASS** | V3 样本 1:02→1:25，进程/硬解线程保持 |
| pause/resume | **PASS (STRONG)** | resume 后硬解 client/线程仍在；UI 图标不作为 decoder 证据 |
| 前后台 | **PASS** | HOME→返回，主进程与 `:ijkservice` PID 均保持，硬解线程仍在 |
| 全屏 | **PASS** | 普通横屏全屏与故事式全屏均保持硬解资源 |
| 连播 | **EXECUTED / NOT TRIGGERED** | 多 P P2 seek 到 1:13/1:13；当前宿主配置停在片尾，未自动进入 P3 |
| PlayerUnite fallback 视频 | **NO REGRESSION EVIDENCE** | `PlayerUniteFix` 未改动、安装日志存在、相关单元测试通过；本轮样本未触发 compatibility resolved |
| 试看 | **NOT TRIGGERED** | 本轮账号/样本未返回试看；未修改 PreviewNotice、权限或协议映射 |
| hardware init failure fallback | **PASS** | invalid AVC codec name → 无 HW client + `ff_video_dec`×10，进程继续 |
| 三模式重新播放 | **PASS** | software/v3_hw/auto 均通过 UI 持久化后冷启动/新视频验证；最终恢复 auto |
| 非 UGC scope | **PASS** | 番剧 ss41410 试看实际进入 transformer，但日志明确 `scope=host / HOST_SCOPE_UNSUPPORTED / USE_HOST` |
| capability unknown | **PASS（合成）** | provider probe error → `CAPABILITY_UNKNOWN / USE_HOST`，单元测试覆盖 |
| capability unsupported + host=true | **PASS（合成）** | `HOST_DECISION_PRESERVED / USE_HOST`；OEM capability 表可能低报，保留 native fallback |
| capability supported + host=false | **PASS（合成）** | 原因未知时 `HOST_DECISION_PRESERVED / USE_HOST`；只有 HIGH legacy conflict 才可纠正 |

## V2 selector / live 增量（2026-09-20）

| Case | 结果 | 证据 |
|---|---|---|
| VOD raw C2 rank | vendor/software `c2.*` 均 rank 100；`OMX.qcom` alias rank 700/800 | 7040300 `IjkMediaCodecInfo.setupCandidate` + debug candidate 枚举 |
| VOD actual mapping | Java 选 OMX alias，实际为 `c2.qti.avc/hevc.decoder` | `dumpsys media.resource_manager` |
| Live cold direct | `OMX.qcom.video.decoder.hevc.low_latency` rank 700 → actual `c2.qti.hevc.decoder.low_latency` | debug selector + resource manager；1280×720≈30fps、稳定段 discard=0 |
| Live VOD-first | OnePlus unused-low-latency 状态把 alias 降到 600，可落普通 `c2.qti.hevc.decoder` | 同进程只读快照 + runtime |
| Actionable conflict | VOD、live 均未发现 | 没有 host software + same-stream stable V3 case；继续 USE_HOST |

## 设置 UI

- 模块更新安装并重启宿主进程后，根设置页第一项为 `bili-part-fix`，下一项为“账号资料”；入口不显示 summary（2026-09-20 实机 UI XML 与截图验证）。
- 子页标题为 `bili-part-fix`，唯一条目为 `解码模式选择`。
- 单选项依次为 `自动选择（推荐）`、`V3硬解优先（ijkplayer）`、`软解优先（兼容模式）`。
- XML 在 `software`、`v3_hw`、`auto` 三值间按 UI 选择持久化；无底层修改 LSPosed 数据库。
- 连续进入/退出 3 次、HOME 返回、旋转导致 Activity 重建、应用 force-stop、模块更新安装后重新进入均无空白/错页；当前值与 summary 正确恢复。

## 已知例外

`IjkMediaPlayerItem.setItemOptions()` 在 `mHdrVideoType != 0` 时会强制恢复 `mEnableHwCodec=true`。因此软件模式的直接结论限定为普通 UGC 非 HDR；发布策略对 HDR/Dolby、DRM 和 HDR 状态不确定项主动 `USE_HOST`，没有通过清空安全标记来伪造软件模式。failure memory 的数据结构与策略已实现，但 native 失败尚无可靠的主进程 stream 归因回调，发布版不伪造生产记录。
