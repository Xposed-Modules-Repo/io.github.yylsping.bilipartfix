package io.github.yylsping.bilipartfix;

/**
 * Version-specific obfuscated symbols for the Smart Auto codec boundary.
 * Pure value logic, no Android or Xposed dependencies, so it can be unit
 * tested on the JVM. See docs/7420400-decoder-mapping.md for the static
 * evidence behind every symbol pair.
 */
final class DecoderProfile {
    /** Item options class consumed by the coreV2 transformer. */
    final String itemOptionsClass;
    /** Boolean getter on the item options that feeds IjkMediaConfigParams.mEnableHwCodec. */
    final String hardwarePreferenceGetter;
    /** MediaResource method that builds the IjkMediaAsset for the selected ids. */
    final String assetBuildMethod;
    /** MediaResource method returning the selected PlayIndex. */
    final String playIndexGetter;
    /** PlayIndex field holding the "stat" default quality id. */
    final String playIndexStatField;
    /** MediaResource method reporting DRM/protected content. */
    final String protectedContentMethod;
    /** Item options getter for the requested video quality id. */
    final String optionsQualityGetter;
    /** Item options getter for the HDR flag. */
    final String hdrGetter;
    /** Item options getter for the Dolby flag. */
    final String dolbyGetter;
    /** DashMediaIndex getter for the quality id. */
    final String dashQualityGetter;
    /** DashMediaIndex getter for the codec id (7 = AVC, 12 = HEVC). */
    final String dashCodecGetter;
    /** DashMediaIndex private field with the raw frame_rate string. */
    final String frameRateField;
    /** NormalVideoPlayHandler inner callback classes whose c(task) consumes a resolve result. */
    final String[] scopeCallbackClasses;
    /** Parameter type of the scope callback c(...) method (the resolve task base). */
    final String scopeTaskClass;

    private DecoderProfile(String itemOptionsClass, String hardwarePreferenceGetter,
                           String assetBuildMethod, String playIndexGetter,
                           String playIndexStatField, String protectedContentMethod,
                           String optionsQualityGetter, String hdrGetter, String dolbyGetter,
                           String dashQualityGetter, String dashCodecGetter,
                           String frameRateField, String[] scopeCallbackClasses,
                           String scopeTaskClass) {
        this.itemOptionsClass = itemOptionsClass;
        this.hardwarePreferenceGetter = hardwarePreferenceGetter;
        this.assetBuildMethod = assetBuildMethod;
        this.playIndexGetter = playIndexGetter;
        this.playIndexStatField = playIndexStatField;
        this.protectedContentMethod = protectedContentMethod;
        this.optionsQualityGetter = optionsQualityGetter;
        this.hdrGetter = hdrGetter;
        this.dolbyGetter = dolbyGetter;
        this.dashQualityGetter = dashQualityGetter;
        this.dashCodecGetter = dashCodecGetter;
        this.frameRateField = frameRateField;
        this.scopeCallbackClasses = scopeCallbackClasses;
        this.scopeTaskClass = scopeTaskClass;
    }

    private static final DecoderProfile V7040300 = new DecoderProfile(
            "tv.danmaku.videoplayer.coreV2.transformer.d",
            "g",
            "R",
            "o",
            "f110922b",
            "E",
            "y",
            "C",
            "A",
            "n",
            "k",
            "f110847i",
            new String[]{
                    "tv.danmaku.biliplayerv2.service.NormalVideoPlayHandler$d",
                    "tv.danmaku.biliplayerv2.service.NormalVideoPlayHandler$e"
            },
            "tv.danmaku.biliplayerv2.service.resolve.n");

    private static final DecoderProfile V7420400 = new DecoderProfile(
            "tv.danmaku.videoplayer.coreV2.transformer.MediaItemParams",
            "i",
            "S",
            "s",
            "b",
            "F",
            "A",
            "E",
            "C",
            "p",
            "l",
            "i",
            new String[]{
                    "tv.danmaku.biliplayerv2.service.NormalVideoPlayHandler$b",
                    "tv.danmaku.biliplayerv2.service.NormalVideoPlayHandler$c",
                    "tv.danmaku.biliplayerv2.service.NormalVideoPlayHandler$d"
            },
            "tv.danmaku.biliplayerv2.service.resolve.m");

    /** Returns the profile for a supported host, or null when the host has none. */
    static DecoderProfile forHost(HostVersion host) {
        if (host == HostVersion.V7040300) return V7040300;
        if (host == HostVersion.V7420400) return V7420400;
        return null;
    }
}
