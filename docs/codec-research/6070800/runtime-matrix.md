# runtime-matrix.md — 三模式实机对照

**测试机**：OnePlus PLQ110（SM8750 骁龙 8 Elite，Android 16，arm64-v8a）— **非 Xperia 1 III**，Sony 分支不触发。
**测试视频**：av170001 / BV17x411w7KC（服务端实际下发 HEVC 512x288；新版视频被服务端以"版本过低"拒绝）。
**环境干扰声明**：设备存在隐藏注入模块，播放 onInfo 回调链抛 ClassCastException 致主进程崩溃；运行时验证使用 Frida attach 主进程对 `tv.danmaku.biliplayerv2.service.b1$i.onInfo` 做 try/catch 吞异常后完成（不改变解码路径本身）。
**actual decoder 取证方式**：`dumpsys media.resource_manager`（codec client Name 直读）+ 进程线程清单（/proc/\<pid\>/task/*/comm）+ 过滤 logcat。未使用 `mediacodec=1` 作为硬解证据。

## 主表

| Case | Mode | Stored value | Codec | Resolution/FPS | Player | IJK options（关键） | Actual decoder | HW/SW | Fallback | Result |
|---|---|---:|---|---|---|---|---|---|---|---|
| A1 | Auto | "0" | HEVC | 512x288 | IJK_PLAYER (ijkservice) | mediacodec=1, mediacodec-hevc=1 | **c2.qti.hevc.decoder.low_latency** | HW | 未触发 | 正常播放 |
| V1 | V3 | "4" | HEVC | 512x288 | IJK_PLAYER (ijkservice) | 同上（与 Auto 完全一致） | **c2.qti.hevc.decoder.low_latency** | HW | 未触发 | 正常播放 |
| S1 | SW | "1" | HEVC | 512x288 | IJK_PLAYER (ijkservice) | 不下发 mediacodec 键 | **FFmpeg 软解**（ff_video_dec/ff_vout，无 MediaCodec 线程） | SW | N/A | 正常播放 |
| A0 | Auto | "0" | HEVC | 512x288 | IJK_PLAYER | mediacodec=1 | HW 初始化失败（QC2 buffer alloc Error:14，前序崩溃实例资源残留） → **native 退 ffplay 软解** | HW→SW | **触发**："AMEDIACODEC__UNKNOWN_ERROR … will try fallback to ffplay decoder" | 路径证实（随后被环境崩溃打断） |

证据文件：`research/6070800/runtime/{obs_sw,obs_v3,obs_auto2}.log`、`dumpsys_rm_v3_live.txt`、`dumpsys_rm_auto2.txt`、`logcat_auto_live.txt`、`obs_sw.png`。

## 生命周期矩阵

| 操作 | player 重建 | decoder 行为 | 证据 |
|---|---|---|---|
| seek | 否 | 仅 flush（dequeue cancelled + CCodecConfig query），不重建 | obs_lifecycle.log @21:15:59 |
| 手动切 P | 否（无新 prepare） | 旧 codec teardown → 新 CCodecConfig(video/hevc)，decoder 重建 | @21:15:07→21:15:11 |
| 自动连播下一 P | 否 | 同上，decoder 重建 | @21:17:59 |
| 后台 HOME → 深链重进 | **是**（Playback-ijk prepare） | 旧 MediaCodec stop → decoder 重建，codec mode 重新读取 | @21:19:06 |
| 切清晰度 | UNKNOWN | UNKNOWN（UI 自动化未达成；竖屏无清晰度入口） | — |
| 暂停/恢复 | 否（推定） | 不重建（推定） | LOW |

## 辅助观察

- SW 模式 CPU：ijkservice 20s 内 ~85 ticks（512x288 HEVC 软解，负载很低）。
- HW 模式 CPU：ijkservice 24s 内 ~110 ticks（含 AudioTrack 等，解码本身在 HW）。
- 该分辨率下 CPU 无法区分软硬解，故一律以 resource_manager / 线程 / CCodec 日志为准。
- H.264 1080P 用例缺失：测试视频服务端只下发 HEVC 512x288；新视频被"版本过低"拒绝 → H.264 路径 actual decoder 未实机验证（UNKNOWN）。静态上 H.264 走同一 mediacodec 路径（mediacodec=1 + mediacodec-default-avc-name）。
