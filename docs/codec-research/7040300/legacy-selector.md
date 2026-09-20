# 7040300 VOD legacy codec selector

> 结论日期：2026-09-20。`DIRECT` 表示 7040300 smali 或本机运行时直接证据；`INFERENCE` 表示由多项直接证据共同推出。

## 选择链

普通 UGC 的实际链为：选中的 DASH codec → `transformer.d.g()` 宿主硬解偏好 → `IjkMediaPlayerItem` → `IjkCodecHelper.getBestCodecName*()` → `MediaCodecList` → `IjkMediaCodecInfo.setupCandidate()` → Java codec name → Android codec alias 对应的实际 C2 component。

候选直接来自 `MediaCodecList.getCodecCount/getCodecInfoAt`，遍历所有支持 MIME 的 decoder，以 rank 排序并取最高项；`getBestCodecNameInner` 拒绝 rank `< 600` 的候选。

## Rank 模型与 naming 假设

| 条件 | Rank | 7040300 行为 |
|---|---:|---|
| 名称不以 `omx.` 开头 | 100 | `c2.*` 全部落入 non-standard，随后被 `<600` 门槛拒绝 |
| 已知软件 OMX | 200 | 软件 decoder |
| secure | 300 | secure 候选 |
| low-latency 且设备在 unused 列表 | 600 | last chance |
| 未知但可用的 OMX | 700 | acceptable |
| 已知 tested OMX / MTK 特例 | 800 | tested |

这是明确的 OMX-era bias（DIRECT）：selector 不能从名称区分 `c2.qti.*`、`c2.mtk.*`、`c2.exynos.*` 等 vendor C2 与 `c2.android.*`、`c2.google.*` 软件 C2；它们都先得到 rank 100。代码没有现代 C2 vendor whitelist，也没有大规模 vendor C2 blacklist。

## 当前设备

OnePlus PLQ110 同时暴露现代 C2 名称及 OMX alias。VOD 云控将 OnePlus low-latency alias 降到 600 后：

| MIME | 现代 C2 | Rank | OMX alias | Rank | Java 最终名 | 实际 component |
|---|---|---:|---|---:|---|---|
| AVC | `c2.qti.avc.decoder` | 100 | `OMX.qcom.video.decoder.avc` | 800 | OMX alias | `c2.qti.avc.decoder` |
| HEVC | `c2.qti.hevc.decoder` | 100 | `OMX.qcom.video.decoder.hevc` | 700 | OMX alias | `c2.qti.hevc.decoder` |

因此本机存在历史偏置，但 alias 把它屏蔽了。普通 UGC 的三个硬解偏好设置点都显式写入 `true`，没有找到可重复的 `host=false` selector 案例，也没有找到 `Host Auto=SW、同流 V3=稳定 HW` 的对照。

## 可操作性结论

`No actionable legacy conflict found`。不得因为“原始 C2 rank=100”就绕过宿主：本机实际仍选中硬件，且不能证明其他设备的宿主 false 原因是过时 rank 而非 crash workaround、profile/HDR/DRM 或云控安全规则。Smart Auto V2 只保留抽象的 `LegacyPolicyConflict` 门槛，生产调用当前传入 `none()`。
