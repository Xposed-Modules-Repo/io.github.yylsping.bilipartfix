package io.github.yylsping.bilipartfix;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/** Reads the selected DASH representation already resolved by Bilibili 7040300. */
final class StreamInfoExtractor {
    private StreamInfoExtractor() {}

    static DecoderPolicy.StreamInfo extract(Object mediaResource, Object itemOptions) {
        int qualityId = intMethod(itemOptions, "y", 0);
        if (qualityId <= 0) {
            try {
                // MediaResource.R() uses PlayIndex.stat as the selected default when
                // itemOptions.videoId is -1. Mirror that exact branch without calling R().
                Object playIndex = XposedHelpers.callMethod(mediaResource, "o");
                if (playIndex != null) {
                    qualityId = XposedHelpers.getIntField(playIndex, "f110922b");
                }
            } catch (Throwable ignored) {
                // Missing selection remains UNKNOWN and therefore fail-open.
            }
        }

        return extract(mediaResource, itemOptions, qualityId);
    }

    static DecoderPolicy.StreamInfo extract(Object mediaResource, Object itemOptions,
                                            int qualityId) {
        boolean protectedContent = booleanMethod(mediaResource, "E", false);
        DecoderPolicy.HdrState hdr = hdrState(itemOptions);
        Object selected = findSelectedDash(mediaResource, qualityId);
        if (selected == null) {
            return new DecoderPolicy.StreamInfo(DecoderPolicy.VideoCodec.UNKNOWN,
                    0, 0, 0, 0, 0f, 0, hdr, protectedContent, qualityId);
        }

        int codecId = intMethod(selected, "k", 0);
        DecoderPolicy.VideoCodec codec = codecId == 7
                ? DecoderPolicy.VideoCodec.AVC
                : codecId == 12 ? DecoderPolicy.VideoCodec.HEVC
                : DecoderPolicy.VideoCodec.UNKNOWN;
        int width = intMethod(selected, "getWidth", 0);
        int height = intMethod(selected, "getHeight", 0);
        float fps = readFrameRate(selected);
        // DashMediaIndex in this exact build exposes codec-id/size/frame-rate, but not
        // stable profile, level or bit-depth fields. Zero means unknown, never 8-bit.
        return new DecoderPolicy.StreamInfo(codec, 0, 0, width, height, fps, 0,
                hdr, protectedContent, qualityId);
    }

    private static Object findSelectedDash(Object mediaResource, int qualityId) {
        try {
            Object dash = XposedHelpers.callMethod(mediaResource, "h");
            if (dash == null) return null;
            Object value = XposedHelpers.callMethod(dash, "h");
            if (!(value instanceof List)) return null;
            for (Object index : (List<?>) value) {
                if (index != null && intMethod(index, "n", Integer.MIN_VALUE) == qualityId) {
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

    private static DecoderPolicy.HdrState hdrState(Object options) {
        try {
            boolean hdr = Boolean.TRUE.equals(XposedHelpers.callMethod(options, "C"));
            boolean dolby = Boolean.TRUE.equals(XposedHelpers.callMethod(options, "A"));
            return hdr || dolby ? DecoderPolicy.HdrState.HDR : DecoderPolicy.HdrState.SDR;
        } catch (Throwable ignored) {
            return DecoderPolicy.HdrState.UNKNOWN;
        }
    }

    private static float readFrameRate(Object dashIndex) {
        String value = null;
        try {
            Object json = XposedHelpers.callMethod(dashIndex, "b");
            if (json instanceof JSONObject) value = ((JSONObject) json).optString("frame_rate");
        } catch (Throwable ignored) {
            // Exact-version private field fallback below.
        }
        if (value == null || value.trim().isEmpty()) {
            try {
                Object raw = XposedHelpers.getObjectField(dashIndex, "f110847i");
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

    private static boolean booleanMethod(Object target, String method, boolean fallback) {
        try {
            Object value = XposedHelpers.callMethod(target, method);
            return value instanceof Boolean ? (Boolean) value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
