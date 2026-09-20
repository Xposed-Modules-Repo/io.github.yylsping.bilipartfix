# BiliPartFix 1.7.0 release audit

> 本文是 2026-09-19 Smart Auto V1 制品的历史审计，不代表当前待发布 APK。当前制品请以 `release-audit-1.7.0-v2.md` 为准。

> 审计日期：2026-09-19；目标宿主：哔哩哔哩 7.4.0 / 7040300；真机：OnePlus PLQ110 / Android 16 / arm64-v8a。

## 发布制品

| 字段 | 值 |
|---|---|
| APK | `dist/BiliPartFix-v1.7.0-7040300-smart-auto-release.apk` |
| variant | `release` |
| applicationId | `io.github.yylsping.bilipartfix` |
| versionCode | `11` |
| versionName | `1.7.0` |
| debuggable | `false`（`apkanalyzer manifest debuggable`） |
| APK SHA-256 | `D12D8650A1773E7850DB270132188C7E63C30AF51CEF0483AA25E7663CA9F7AD` |
| 签名 | 仓库既有 release 配置，APK Signature Scheme v2，1 signer |
| 证书 SHA-256 | `6f5db5717ea593d309074c527b72b8882ed34f343f5ff72a7f99c43b50ab6fef` |
| 证书主体 | `CN=BiliPartFix, OU=Release, O=yylsping, C=CN` |

未创建、替换或提交 keystore；未打印签名口令。`apksigner verify --verbose --print-certs` 返回 `Verifies`。

## 构建与自动检查

- JDK：Microsoft OpenJDK 17.0.20.8。
- Android compileSdk 36 / minSdk 27 / targetSdk 35。
- 干净执行：`clean testDebugUnitTest assembleRelease --no-daemon`。
- 结果：BUILD SUCCESSFUL，68 actionable tasks 全部执行；release lintVital 通过。
- 31 项单元测试、0 failures、0 errors。DecoderPolicy 矩阵覆盖：UNKNOWN/UNSUPPORTED、probe error、AVC/HEVC、1080P60、4K60、宿主保守纠正、known-bad、HDR/DRM、显式 V3/Software、非 UGC、损坏 preference、fps rational、secure-only decoder 名称。
- `machine-readable-summary.json` 已通过 JSON 解析检查。

## P1/P2 审计

- [x] Capability `UNKNOWN` 与 `UNSUPPORTED` 分离；probe error fail-open 到 `USE_HOST`。
- [x] AVC/HEVC 与每个 decoder/MIME 查询隔离；secure-only decoder 不证明普通 UGC 支持。
- [x] Hook scope 从共享 transformer 路径收窄到 `NormalVideoPlayHandler` 产生的精确 `MediaResource`；OGV 实机为 `HOST_SCOPE_UNSUPPORTED`。
- [x] HDR/DRM 在三模式之前保持宿主安全语义；“软件”仅承诺普通视频优先软件解。
- [x] 设置子页使用原子 screen 替换；连续进入、HOME 返回、旋转重建、应用重启、模块更新后均通过。
- [x] README 准确记录 `bili_part_fix.xml` / `decoder_mode` 以及停用后的 preference 残留语义。

## Smart Auto 审计

- [x] 使用实际选中 DASH representation，而非永久 `StreamInfo.unknown()` 或 UI 标签。
- [x] 稳定读取 MIME、width、height、fps、qn；profile/level/bitDepth 不可得时保持 UNKNOWN。
- [x] capability 使用 profile/level（若已知）和 `areSizeAndRateSupported`。
- [x] 决策输出 `USE_HOST/PREFER_HARDWARE/PREFER_SOFTWARE` 与 Reason。
- [x] UNKNOWN 不主动软件降级；明确 SUPPORTED 可纠正普通 UGC 的保守宿主 preference；明确 UNSUPPORTED 才软件优先。
- [x] 不改用户画质、qn、服务端 codec/码率；4K 不按分辨率粗暴转软件。
- [x] HDR/Dolby/DRM 与非 UGC 保持宿主路径；V3/Software 不受 Auto 条件干扰。
- [x] IJK native fallback 未拦截，forced invalid codec name 已实际回退 FFmpeg。
- [x] 32 项进程内 failure-memory 数据结构、key、策略和日志字段已实现；因跨进程 native 失败无法可靠归因，生产 recorder 暂不伪造。
- [x] Debug 每新 media item 最多一次 `BiliPartFix/SmartAuto:`；release 逐 item 详细日志关闭。

## 真机门禁

| 项目 | 结果 |
|---|---|
| Auto AVC 1080P / 1080P60 | PASS，`c2.qti.avc.decoder`，1080P60 render 60、discard 0 |
| Auto HEVC 1080P | PASS，`c2.qti.hevc.decoder` |
| Auto 4K-class | PASS，AVC 3456×2160@约60，render 60、discard 0 |
| V3 AVC | PASS，`USER_V3/PREFER_HARDWARE`，actual AVC HW |
| Software AVC | PASS，`USER_SOFTWARE/PREFER_SOFTWARE`，`ff_video_dec`×10 |
| capability UNKNOWN / UNSUPPORTED | PASS（确定性合成 provider） |
| MediaCodec forced failure | PASS，FFmpeg fallback、进程不崩溃 |
| seek / 切 P / 前后台 / 全屏 | PASS |
| 人工清晰度切换 | PASS，HEVC 1080P→720P，decoder 重建为 1280×720、render≈30、discard 0 |
| 设置持久化与 lifecycle | PASS，最终设备恢复 `auto` |
| 非 UGC scope | PASS，番剧 ss41410 `USE_HOST` |

## 已知且接受的限制

- profile/level/bitDepth 尚无稳定实际流字段，不做猜测。
- failure memory 的生产记录等待可靠的 `:ijkservice` → 主进程 stream 关联点；当前只保留正确接口与决策，不虚构命中。
- 当前宿主配置未触发片尾自动下一 P；既有切 P、seek 和 decoder 重建已通过。
- 设备电流传感器返回 0，不能提交可信毫安/功率结论；CPU ticks 仅为同流方向性证据。
