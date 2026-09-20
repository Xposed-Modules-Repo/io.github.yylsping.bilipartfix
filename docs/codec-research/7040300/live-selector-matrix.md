# 7040300 live selector matrix

| Live case | Host decoder | Selector reason | Low-latency | Experimental decoder | Stable | CPU/Power | Conflict |
|---|---|---|---|---|---|---|---|
| HEVC 720P，cold direct-live | `c2.qti.hevc.decoder.low_latency` | OMX low-latency alias rank 700 | yes | - | 30fps / discard 0 | baseline only | no |
| HEVC 720P，VOD-first same process | `c2.qti.hevc.decoder` | VOD OnePlus unused-low-latency config lowers alias to rank 600 | no | - | 30fps / discard 0 | baseline only | no |
| AVC live | 未实际触发 | cache=`OMX.qcom.video.decoder.avc`; raw C2 rank 100 | not observed | - | not measured | not measured | unknown, not actionable |

没有 host software case，故没有实施实验性强制硬解，也没有可比较的 5min CPU/温度/功耗窗口。两个 HEVC case 都是稳定 vendor C2 硬解，不满足 `LIVE_LEGACY_CONFLICT=HIGH` 的首要条件。
