# 7040300 播放器与解码调用链

## 普通 UGC VOD 主链

```text
NormalVideoPlayHandler / PreloadResolverKt / story / 互动视频 / 商城
  -> tv.danmaku.biliplayerv2.utils.f.b(...)
  -> tv.danmaku.videoplayer.coreV2.transformer.d$a.i(true)
       ^ BiliPartFix PlayerCodecFix 在这里应用三模式 policy
  -> tv.danmaku.videoplayer.coreV2.transformer.b.a(...)
  -> IjkMediaConfigParams.mEnableHwCodec
  -> IjkMediaPlayerItem.setItemOptions()
  -> IIjkMediaPlayerItem.setOptionBundle(category=3, Bundle)
  -> :ijkservice / native _setOption
  -> IJK MediaCodec 或 FFmpeg video decoder
```

以上类、方法和 option 组装均在 7040300 DEX 独立确认；不是直接照搬 6070800 映射（STRONG）。`d$a.i(boolean)` 的 true/false 到 `mEnableHwCodec`，再到 actual decoder 的双向 PoC 已完成（DIRECT）。

## 关键 option

`IjkMediaPlayerItem.setItemOptions()` 在 `mEnableHwCodec=true` 时写入：

- `mediacodec=1`
- `mediacodec-hevc=1`（H.265 开启时）
- `async-init-decoder=1`
- `video-mime-type`
- `mediacodec-default-name`
- `mediacodec-default-avc-name`
- `mediacodec-default-hevc-name`

7040300 还保留/新增 `enable_ndk_mediacodec` 与 `enable_ndk_mediacodec_async`。设备 Java selector 可返回 `OMX.qcom.video.decoder.avc/hevc` 别名，最终资源管理器中实际 client 为 `c2.qti.avc.decoder` 或 `c2.qti.hevc.decoder`。

## 决策语义

- `auto`：尊重宿主 false；宿主 true 且设备枚举出相关硬件能力时保持 true。
- `v3_hw`：把 setter 参数改成 true，但不改 native error recovery。
- `software`：把 setter 参数改成 false；普通 UGC 进入 FFmpeg video decoder。

设置变更不热切当前 item。原因是预加载 item 可以复用已经构造的 config，setter 不一定再次执行；冷启动或打开新 media item 是确定的验证边界。

## 生命周期实测

- seek：同一 `:ijkservice` 继续工作，硬解线程保留（DIRECT）。
- 前后台：HOME 后回到应用，主进程和 `:ijkservice` PID 未变，codec 线程保留（DIRECT）。
- 手动切 P：`:ijkservice` PID 保持 8533；旧 resource client 被移除，新 client 在同 PID 创建，解码器重建为 `c2.qti.avc.decoder` 4096×1716（DIRECT）。
- 全屏/竖屏故事式全屏：均保持硬解资源（DIRECT）。
- pause/resume：恢复后硬解线程存在；交互截图只作辅助，不把 UI 图标当成 decoder 证据（STRONG）。

## 与 PlayerUniteFix 的边界

`PlayerUniteFix` 只负责“新服务端响应 → 7040300 legacy player 可消费的数据”，并保留服务端的试看/权限约束。`PlayerCodecFix` 只改本地 video decoder preference，不改 `fnval`、`preferCodecType`、账号权限、DRM 或服务端码流选择。两者没有共享状态，也未在本次变更中修改 `PlayerUniteFix`。
