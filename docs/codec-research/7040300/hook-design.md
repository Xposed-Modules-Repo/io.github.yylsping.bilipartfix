# 7040300 Hook 设计

## 解码决策边界

最终边界：

```text
tv.danmaku.videoplayer.coreV2.transformer.b
  .a(MediaResource, transformer.d, coreV2.h$b)
```

模块在方法进入时保存宿主 `d.g()` 硬解偏好与上下文；原方法调用 `MediaResource.R(int,int)` 并返回已经选定的 `IjkMediaAsset` 时，读取 `getDefaultVideoId()`，从同一 `MediaResource` 的 DASH 列表提取实际 representation，执行一次 `DecoderPolicy`。随后只在该调用线程内临时改写 `d.g()` 的返回值。退出时清理并恢复嵌套上下文。

这比全局改写 `d$a.i(boolean)` 更窄：没有决策上下文时 getter 完全返回宿主值；不改 `IjkMediaPlayerItem` Bundle、audio option、qn、网络请求或 native error。IJK 原生 MediaCodec → FFmpeg fallback 保持原样。

## 普通 UGC scope guard

`NormalUgcScope` 在以下 7040300 精确回调中记录传入 transformer 的同一 `MediaResource` 实例：

```text
NormalVideoPlayHandler$d.c(resolve.n)
NormalVideoPlayHandler$e.c(resolve.n)
NormalVideoPlayHandler$playPreloadRes$1.invokeSuspend(...)
```

自有 `WeakIdentitySet` 以 `System.identityHashCode` 和 referent `==` 实现 key 语义，以 `WeakReference + ReferenceQueue` 回收，并对每次访问同步。它不会因宿主 `equals/hashCode` 合并不同 item，也不强持有 Activity/MediaResource。未被普通 UGC resolve/preload 标记的资源统一 `USE_HOST`。

## StreamInfo

选中 qn 来自 `MediaResource.R()` 返回 asset，不从画质 UI 文案推测。`StreamInfoExtractor` 以 qn 匹配 `DashResource.h()` 列表，读取 codec id、width、height 与 frame_rate；HDR/Dolby 来自 item options，DRM/受保护状态来自 `MediaResource.E()`。缺字段即 UNKNOWN，策略 fail-open 到宿主。

## 设置注入

```text
BiliPreferencesActivity$BiliPreferencesFragment.onCreatePreferences(...)
PlaySettingPrefFragment.onCreatePreferences(...)
```

根页注入普通 `androidx.preference.Preference`，读取现有根级 Preference 的最小 order 后取前一位，因此 `bili-part-fix` 位于“账号资料”等原生设置之前；入口不设置 summary，避免把模块后续能力限定为解码设置。通过 extras marker 区分模块子页。子页先创建未附着的 module-owned PreferenceScreen，完整加入“解码模式选择”后再一次性 `setPreferenceScreen`；任何反射步骤失败时宿主原 screen 保持不动。没有 `removeAll()` 窗口。

- 文件：`bili_part_fix.xml`
- key：`decoder_mode`
- 值：`auto` / `v3_hw` / `software`
- 默认、损坏或未知值：`auto`
- 写入：同步 `commit()`，下一新 media item 可见

## 失败隔离和日志

capability 由 `LazyCapabilityProvider` 在第一个普通 UGC Auto assessment 时 detect once 并缓存；Application attach、非 UGC、V3 与软件模式都不枚举。整体失败只把 AVC/HEVC 标成 UNKNOWN；单 decoder/MIME 查询失败只污染该候选。Debug 的逐 item 明细统一 `BiliPartFix/SmartAuto:`，并按真正 weak identity 去重；Release 不输出这些高频明细。

`RuntimeFailureMemory` 已实现进程内 32 项 LRU 与策略原因码。当前 7040300 可直接观察的初始化失败位于 `:ijkservice` native 层，不能可靠映射回主进程具体 stream key，因此生产代码暂不注册宽泛/错误归因的失败 Hook；native fallback 的直接验证仍成立。

`LegacyCodecResearch` 与 `LiveCodecResearch` 只在 debug build 执行，只读记录 cloud、candidate/rank 与 live selector 结果；release 由 `BuildConfig.DEBUG` 屏蔽。live 产品逻辑没有被挂接到 `DecoderPolicy`。

## 不采用的点

- 全局 `d$a.i(boolean)`：覆盖 story/互动/商城等路径过宽。
- `IjkMediaPlayerItem.setItemOptions()`：字段和 Bundle 面大，易误伤 HDR/DRM/audio。
- 全局 `Bundle.putLong`、AIDL option 或 native error 拦截：影响面及跨进程归因风险过大。
- UI 画质标签：真实测试中标题/标签与 actual codec/fps 不总是一致。
