package io.github.yylsping.bilipartfix;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Reads the selected DASH representation already resolved by the host.
 * All version-specific symbols come from the DecoderProfile so the same
 * extractor serves both 7040300 and 7420400.
 */
final class StreamInfoExtractor {
    private final DecoderProfile profile;

    StreamInfoExtractor(DecoderProfile profile) {
        this.profile = profile;
    }

    DecoderPolicy.StreamInfo extract(Object mediaResource, Object itemOptions) {
        int qualityId = intMethod(itemOptions, profile.optionsQualityGetter, 0);
        if (qualityId <= 0) {
            try {
                // MediaResource's asset build uses PlayIndex.stat as the selected
                // default when the options video id is -1. Mirror that exact branch
                // without building the asset.
                Object playIndex = XposedHelpers.callMethod(mediaResource,
                        profile.playIndexGetter);
                if (playIndex != null) {
                    qualityId = XposedHelpers.getIntField(playIndex,
                            profile.playIndexStatField);
                }
            } catch (Throwable ignored) {
                // Missing selection remains UNKNOWN and therefore fail-open.
            }
        }

        return extract(mediaResource, itemOptions, qualityId);
    }

    DecoderPolicy.StreamInfo extract(Object mediaResource, Object itemOptions,
                                     int qualityId) {
        DecoderPolicy.ProtectionState protection =
                protectionState(mediaResource, profile.protectedContentMethod);
        DecoderPolicy.HdrState hdr = hdrState(itemOptions);
        Object selected = findSelectedDash(mediaResource, qualityId);
        if (selected == null) {
            return new DecoderPolicy.StreamInfo(DecoderPolicy.VideoCodec.UNKNOWN,
                    0, 0, 0, 0, 0f, 0, hdr, protection, qualityId);
        }

        int codecId = intMethod(selected, profile.dashCodecGetter, 0);
        DecoderPolicy.VideoCodec codec = codecId == 7
                ? DecoderPolicy.VideoCodec.AVC
                : codecId == 12 ? DecoderPolicy.VideoCodec.HEVC
                : DecoderPolicy.VideoCodec.UNKNOWN;
        int width = intMethod(selected, "getWidth", 0);
        int height = intMethod(selected, "getHeight", 0);
        float fps = readFrameRate(selected);
        // DashMediaIndex in the supported builds exposes codec-id/size/frame-rate,
        // but not stable profile, level or bit-depth fields. Zero means unknown,
        // never 8-bit.
        return new DecoderPolicy.StreamInfo(codec, 0, 0, width, height, fps, 0,
                hdr, protection, qualityId);
    }

    private Object findSelectedDash(Object mediaResource, int qualityId) {
        try {
            Object dash = XposedHelpers.callMethod(mediaResource, "h");
            if (dash == null) return null;
            Object value = XposedHelpers.callMethod(dash, "h");
            if (!(value instanceof List)) return null;
            for (Object index : (List<?>) value) {
                if (index != null
                        && intMethod(index, profile.dashQualityGetter,
                                Integer.MIN_VALUE) == qualityId) {
                    return index;
                }
            }
        } catch (Throwable throwable) {
            if (BuildConfig.DEBUG) {
                XposedBridge.log("BiliPartFix/SmartAuto: selected DASH lookup failed",
                        throwable);
            }
        }
        return null;
    }

    private DecoderPolicy.HdrState hdrState(Object options) {
        try {
            boolean hdr = Boolean.TRUE.equals(
                    XposedHelpers.callMethod(options, profile.hdrGetter));
            boolean dolby = Boolean.TRUE.equals(
                    XposedHelpers.callMethod(options, profile.dolbyGetter));
            return hdr || dolby ? DecoderPolicy.HdrState.HDR : DecoderPolicy.HdrState.SDR;
        } catch (Throwable ignored) {
            return DecoderPolicy.HdrState.UNKNOWN;
        }
    }

    private float readFrameRate(Object dashIndex) {
        String value = null;
        try {
            Object json = XposedHelpers.callMethod(dashIndex, "b");
            if (json instanceof JSONObject) value = ((JSONObject) json).optString("frame_rate");
        } catch (Throwable ignored) {
            // Exact-version private field fallback below.
        }
        if (value == null || value.trim().isEmpty()) {
            try {
                Object raw = XposedHelpers.getObjectField(dashIndex, profile.frameRateField);
                if (raw != null) value = String.valueOf(raw);
            } catch (Throwable ignored) {
                return 0f;
            }
        }
        return parseFrameRate(value);
    }

    static float parseFrameRate(String value) {
        if (value == null) return 0f;
        try {
            String clean = value.trim().toLowerCase(Locale.ROOT).replace("fps", "").trim();
            int slash = clean.indexOf('/');
            if (slash > 0) {
                float numerator = Float.parseFloat(clean.substring(0, slash).trim());
                float denominator = Float.parseFloat(clean.substring(slash + 1).trim());
                return denominator > 0f ? numerator / denominator : 0f;
            }
            return Float.parseFloat(clean);
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private static int intMethod(Object target, String method, int fallback) {
        try {
            Object value = XposedHelpers.callMethod(target, method);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    /**
     * Reads the host DRM/protected flag as a three-state value: only an actual
     * Boolean result maps to CLEAR/PROTECTED; any reflection failure or
     * unexpected return type stays UNKNOWN so the policy fails open.
     */
    static DecoderPolicy.ProtectionState protectionState(Object target,
                                                         String method) {
        Object value;
        try {
            value = XposedHelpers.callMethod(target, method);
        } catch (Throwable ignored) {
            return DecoderPolicy.ProtectionState.UNKNOWN;
        }
        if (!(value instanceof Boolean)) return DecoderPolicy.ProtectionState.UNKNOWN;
        return ((Boolean) value)
                ? DecoderPolicy.ProtectionState.PROTECTED
                : DecoderPolicy.ProtectionState.CLEAR;
    }
}
