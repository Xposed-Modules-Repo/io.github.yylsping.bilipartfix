# questions-20.md — goal 指定的 20 个问题逐条回答

标注：[HIGH]=静态+运行时双证；[MEDIUM]=单类直接证据；[LOW]=推断；[UNKNOWN]=未验证。
实机=OnePlus PLQ110/Android 16（非 Xperia 1 III）。

**1. 6070800 的"V3硬解优先（ijkplayer）"是否确实等于 IjkMediaPlayer + Android MediaCodec？**
是。[HIGH] 决策层 mode=4 → IJK_PLAYER + use_ijk_media_codec=true → `mediacodec=1`(+hevc) 下发；实机 actual decoder = c2.qti.hevc.decoder.low_latency。

**2. Auto 的 stored value？**
`"0"`（String）。[HIGH]（资源 entryValues + 实机 UI 切换写文件验证）

**3. V3 的 stored value？**
`"4"`（String）。[HIGH]

**4. Software 的 stored value？**
`"1"`（String）。[HIGH]

**5. 三种模式如何映射到 player/backend/options？**
全部 IJK_PLAYER。Auto(0)/V3(4)：`use_ijk_media_codec=true` → category3 `mediacodec=1`（+HEVC 时 `mediacodec-hevc=1`，+selector 非空时 default-name 组）；SW(1)：use_ijk_media_codec=false → 不下发 mediacodec 键，纯 FFmpeg。[HIGH]

**6. Software 是否仍使用 IjkMediaPlayer？**
是。[HIGH] 决策层同为 IJK_PLAYER；实机 SW 播放时 :ijkservice 内为 ff_* 线程、无任何 MediaCodec/CCodec 活动。

**7. V3 设置哪些 ijk option？**
codec 相关：`mediacodec=1`、`mediacodec-hevc=1`（HEVC 开关开时）、`async-init-decoder=1`+`video-mime-type`+`mediacodec-default-name`（selector 选出时，c2 设备不触发）、`mediacodec-default-avc-name`、`mediacodec-default-hevc-name`（HEVC 流）；常规：`framedrop=1`、`enable-accurate-seek`、`buffering-water-mark-string=500,1000,2000,4000,5000`、opensles、loop、render-after-prepare 等。时机：prepareAsync 前。[HIGH]

**8. H.264 和 HEVC 是否使用不同 option？**
是，增量式：`mediacodec=1` 为底座；HEVC 追加 `mediacodec-hevc=1` 与 `mediacodec-default-hevc-name`；H.264 对应 `mediacodec-default-avc-name`。[HIGH（静态）；H.264 实机未测]

**9. 是否存在 `mediacodec-hevc`？**
存在。Java 组装层 + native option 字符串均有；实机 HEVC 流硬解成功。[HIGH]

**10. Auto 实际考虑哪些条件？**
Java 层只读 codecMode（与 V3 等价）。能力/黑白名单在 selector 与云控层：MediaCodecList 枚举+rank、fake-name 正则、h265-cpu-blacklist、buvid 灰度名单、isUhdSupport(HEVC)（仅用于 4K 可用性）。未见读取分辨率/码率/HDR/thermal。[HIGH]

**11. Xperia 1 III 是否命中 Sony 专属 decoder policy？**
不存在 Sony 专属 decoder policy。[HIGH]（详见 sony-xperia1iii.md：仅渠道读取、品牌名映射、索尼音乐内容源标记）

**12. Xperia 1 III Auto + H.264 1080P 实际 decoder？**
[UNKNOWN] 两重偏差：本机非 Xperia；测试视频服务端只下发 HEVC 512x288（新版视频被"版本过低"拒绝），H.264 1080P 用例未能构造。本机 Auto + HEVC 512x288 = c2.qti.hevc.decoder.low_latency。

**13. V3 模式实际 hardware decoder name？**
本机实测：`c2.qti.hevc.decoder.low_latency`（HEVC）。[HIGH（HEVC，本机）；H.264 未实测] dumpsys media.resource_manager Client Name 直读。

**14. Software 模式实际 software decoder？**
FFmpeg 软解（ijkplayer 内 avcodec，ff_video_dec 线程）。[HIGH]（线程+无 MediaCodec 证据；具体 avcodec decoder 名未打印，推断为 ffhevc — [MEDIUM]）

**15. MediaCodec 初始化失败怎么 fallback？**
第一级 native：mediacodec 报错 → `"will try fallback to ffplay decoder"` → 同播放器内退 FFmpeg（实机命中，[HIGH]）。第二级 Java：SwitchablePlayerAdapter 重试≤2 次 → 策略表 (IJK,mc=true)→(IJK,mc=false) 重建播放器 + BasePlayerEventCodecConfigChanged 事件（静态，[MEDIUM]，实机未触发到该层）。单次 native fallback 非无限重试；未见 codec 级本地 blacklist 缓存 [LOW]。

**16. 切清晰度是否重建 player/decoder？**
[UNKNOWN] UI 自动化未能完成切清晰度（竖屏控制条无清晰度入口）。相邻证据：dash manifest 切换有专门日志；切 P（换流）时 decoder 重建而 player 复用。

**17. 切 P 是否重建 player/decoder？**
player 不重建（无新 prepare），decoder 重建（旧 MediaCodec teardown → 新 CCodecConfig）。[MEDIUM-HIGH] 自动连播下一 P 行为相同。直播共用同一 mode 语义（mEnableHwCodec=(mode==0||mode==4)）[MEDIUM]。

**18. arm64 native 中 MediaCodec decoder 创建入口？**
libijkplayer.so 内 ffpipeline mediacodec 路径（option table 驱动；SDL_AMediaCodecJava_createDecoderByType/createByCodecName 系列 JNI 桥）。stripped，未做函数级 RVA 标注；字符串锚点：`amc: use default avc codec`、`mediacodec-default-name`。[MEDIUM]

**19. 哪些锚点最适合去 7040300 搜索？**
真名类：IjkMediaPlayer/IjkCodecHelper/IjkMediaCodecInfo/IjkMediaPlayerService/IjkMediaPlayerClient。字符串：`mediacodec`、`mediacodec-hevc`、`pref_player_codecMode_key`、`use_ijk_media_codec`、`V3硬解`。native：`will try fallback to ffplay decoder`、`amc: use default`、`onSelectCodec`。详见 migration-to-7040300.md。[HIGH]

**20. 6.7 Auto 中哪些值得 7.4 参考，哪些不应照搬？**
参考：Auto=V3+多级兜底结构、云控 blacklist 对接、二级重建兜底。不应照搬：omx. 中心 selector rank 表（c2 设备失效）、HEVC UHD 阈值（2021 标准）。详见 auto-policy.md。[HIGH]
