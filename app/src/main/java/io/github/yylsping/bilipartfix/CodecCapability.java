package io.github.yylsping.bilipartfix;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Per-MIME, tri-state hardware decoder capability snapshot for Smart Auto. */
final class CodecCapability implements DecoderPolicy.CapabilityProvider {
    enum Support { SUPPORTED, UNSUPPORTED, UNKNOWN }
    enum HardwareIdentity { HARDWARE, SOFTWARE, UNKNOWN }

    static final class Assessment {
        final Support support;
        final String decoderName;
        final String detail;

        Assessment(Support support, String decoderName, String detail) {
            this.support = support == null ? Support.UNKNOWN : support;
            this.decoderName = decoderName == null ? "" : decoderName;
            this.detail = detail == null ? "" : detail;
        }

        static Assessment unknown(String detail) {
            return new Assessment(Support.UNKNOWN, "", detail);
        }
    }

    static class DecoderCandidate {
        final String decoderName;
        final String mime;
        final boolean hardware;
        final MediaCodecInfo.CodecProfileLevel[] profileLevels;
        final MediaCodecInfo.VideoCapabilities videoCapabilities;
        final boolean capabilityProbeFailed;
        final String probeDetail;

        DecoderCandidate(String decoderName, String mime, boolean hardware,
                         MediaCodecInfo.CodecProfileLevel[] profileLevels,
                         MediaCodecInfo.VideoCapabilities videoCapabilities,
                         boolean capabilityProbeFailed, String probeDetail) {
            this.decoderName = decoderName;
            this.mime = mime;
            this.hardware = hardware;
            this.profileLevels = profileLevels == null
                    ? new MediaCodecInfo.CodecProfileLevel[0] : profileLevels.clone();
            this.videoCapabilities = videoCapabilities;
            this.capabilityProbeFailed = capabilityProbeFailed;
            this.probeDetail = probeDetail == null ? "" : probeDetail;
        }

        Assessment assess(DecoderPolicy.StreamInfo stream) {
            if (capabilityProbeFailed || videoCapabilities == null) {
                return new Assessment(Support.UNKNOWN, decoderName,
                        probeDetail.isEmpty() ? "VideoCapabilities unavailable" : probeDetail);
            }
            try {
                if (stream.profile > 0) {
                    if (profileLevels.length == 0) {
                        return new Assessment(Support.UNKNOWN, decoderName,
                                "profile requested but OEM profileLevels is empty");
                    }
                    boolean profileMatch = false;
                    for (MediaCodecInfo.CodecProfileLevel entry : profileLevels) {
                        if (entry.profile == stream.profile
                                && (stream.level <= 0 || entry.level >= stream.level)) {
                            profileMatch = true;
                            break;
                        }
                    }
                    if (!profileMatch) {
                        return new Assessment(Support.UNSUPPORTED, decoderName,
                                "profile/level is outside advertised capability");
                    }
                }
                boolean supported = stream.fps > 0f
                        ? videoCapabilities.areSizeAndRateSupported(
                                stream.width, stream.height, stream.fps)
                        : videoCapabilities.isSizeSupported(stream.width, stream.height);
                return new Assessment(supported ? Support.SUPPORTED : Support.UNSUPPORTED,
                        decoderName, supported ? "size/rate supported" : "size/rate unsupported");
            } catch (Throwable throwable) {
                return new Assessment(Support.UNKNOWN, decoderName,
                        "size/rate probe failed: " + throwable.getClass().getSimpleName());
            }
        }
    }

    private static final class MimeRecord {
        final List<DecoderCandidate> candidates = new ArrayList<>();
        boolean enumerationUncertain;
    }

    private final Map<DecoderPolicy.VideoCodec, MimeRecord> records;

    private CodecCapability(Map<DecoderPolicy.VideoCodec, MimeRecord> records) {
        this.records = records;
    }

    static CodecCapability detect() {
        EnumMap<DecoderPolicy.VideoCodec, MimeRecord> records = new EnumMap<>(
                DecoderPolicy.VideoCodec.class);
        records.put(DecoderPolicy.VideoCodec.AVC, new MimeRecord());
        records.put(DecoderPolicy.VideoCodec.HEVC, new MimeRecord());

        MediaCodecInfo[] infos;
        try {
            infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        } catch (Throwable throwable) {
            markUncertain(records);
            XposedBridge.log("SmartAuto capability enumeration unavailable", throwable);
            return new CodecCapability(records);
        }

        for (MediaCodecInfo info : infos) {
            if (info == null) continue;
            String decoderName = safeName(info);
            // Smart Auto only handles ordinary, non-DRM UGC.  A secure-only codec
            // cannot back the non-secure playback session used by that path, so do
            // not let it produce a false SUPPORTED result when no normal decoder is
            // available. DRM/Widevine items are kept entirely on the host path.
            if (isSecureOnlyName(decoderName)) continue;
            try {
                if (info.isEncoder()) continue;
            } catch (Throwable throwable) {
                markUncertain(records);
                logProbeFailure("unable to classify encoder", decoderName, throwable);
                continue;
            }

            String[] types;
            try {
                types = info.getSupportedTypes();
            } catch (Throwable throwable) {
                markUncertain(records);
                logProbeFailure("unable to enumerate MIME types", decoderName, throwable);
                continue;
            }

            HardwareIdentity identity;
            try {
                identity = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? (info.isHardwareAccelerated()
                        ? HardwareIdentity.HARDWARE : HardwareIdentity.SOFTWARE)
                        : classifyLegacyDecoderName(decoderName);
            } catch (Throwable throwable) {
                markUncertain(records, types);
                logProbeFailure("unable to classify codec hardware identity",
                        decoderName, throwable);
                continue;
            }
            if (identity == HardwareIdentity.SOFTWARE) continue;
            if (identity == HardwareIdentity.UNKNOWN) {
                // API 27/28 has no authoritative hardware flag. An unfamiliar name is
                // uncertainty, not affirmative hardware evidence.
                markUncertain(records, types);
                continue;
            }

            for (String type : types) {
                DecoderPolicy.VideoCodec codec = codecForMime(type);
                if (codec == DecoderPolicy.VideoCodec.UNKNOWN) continue;
                MimeRecord record = records.get(codec);
                try {
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    record.candidates.add(new DecoderCandidate(decoderName, type, true,
                            caps == null ? null : caps.profileLevels,
                            caps == null ? null : caps.getVideoCapabilities(),
                            caps == null, caps == null ? "null CodecCapabilities" : ""));
                } catch (Throwable throwable) {
                    // Isolate failure to this decoder/MIME entry. Another candidate may
                    // still prove support; otherwise this MIME remains UNKNOWN.
                    record.candidates.add(new DecoderCandidate(decoderName, type, true,
                            null, null, true,
                            "capability probe failed: " + throwable.getClass().getSimpleName()));
                    logProbeFailure("unable to query codec capabilities",
                            decoderName + '/' + type, throwable);
                }
            }
        }
        return new CodecCapability(records);
    }

    static boolean isSecureOnlyName(String decoderName) {
        String normalized = decoderName == null
                ? "" : decoderName.toLowerCase(Locale.US);
        return normalized.endsWith(".secure") || normalized.contains(".secure.");
    }

    static CodecCapability fixed(DecoderPolicy.VideoCodec codec, Support support,
                                 String decoderName) {
        EnumMap<DecoderPolicy.VideoCodec, MimeRecord> map = new EnumMap<>(
                DecoderPolicy.VideoCodec.class);
        map.put(DecoderPolicy.VideoCodec.AVC, new MimeRecord());
        map.put(DecoderPolicy.VideoCodec.HEVC, new MimeRecord());
        MimeRecord record = map.get(codec);
        if (support == Support.UNKNOWN) record.enumerationUncertain = true;
        if (support != Support.UNSUPPORTED) {
            // Tests use a deterministic override through fixedAssessments below.
            record.candidates.add(new FixedCandidate(decoderName, codec.mime, support));
        }
        return new CodecCapability(map);
    }

    @Override
    public Assessment assess(DecoderPolicy.StreamInfo stream) {
        if (stream == null || stream.codec == DecoderPolicy.VideoCodec.UNKNOWN) {
            return Assessment.unknown("stream MIME unknown");
        }
        MimeRecord record = records.get(stream.codec);
        if (record == null) return Assessment.unknown("MIME was not enumerated");
        if (record.candidates.isEmpty()) {
            return record.enumerationUncertain
                    ? Assessment.unknown("decoder enumeration incomplete")
                    : new Assessment(Support.UNSUPPORTED, "", "no hardware decoder for MIME");
        }

        Assessment firstUnsupported = null;
        Assessment firstUnknown = null;
        for (DecoderCandidate candidate : record.candidates) {
            Assessment result = candidate.assess(stream);
            if (result.support == Support.SUPPORTED) return result;
            if (result.support == Support.UNKNOWN && firstUnknown == null) firstUnknown = result;
            if (result.support == Support.UNSUPPORTED && firstUnsupported == null) {
                firstUnsupported = result;
            }
        }
        if (firstUnknown != null || record.enumerationUncertain) {
            return firstUnknown != null ? firstUnknown
                    : Assessment.unknown("decoder enumeration incomplete");
        }
        return firstUnsupported != null ? firstUnsupported
                : Assessment.unknown("no conclusive hardware decoder result");
    }

    List<String> decoderNames(DecoderPolicy.VideoCodec codec) {
        MimeRecord record = records.get(codec);
        if (record == null) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        for (DecoderCandidate candidate : record.candidates) {
            names.add(candidate.decoderName);
        }
        return Collections.unmodifiableList(names);
    }

    private static final class FixedCandidate extends DecoderCandidate {
        private final Support fixedSupport;

        FixedCandidate(String decoderName, String mime, Support support) {
            super(decoderName, mime, true, null, null, false, "fixed test capability");
            fixedSupport = support;
        }

        @Override
        Assessment assess(DecoderPolicy.StreamInfo stream) {
            return new Assessment(fixedSupport, decoderName, "fixed test capability");
        }
    }

    private static void markUncertain(Map<DecoderPolicy.VideoCodec, MimeRecord> records) {
        for (MimeRecord record : records.values()) record.enumerationUncertain = true;
    }

    private static void markUncertain(Map<DecoderPolicy.VideoCodec, MimeRecord> records,
                                      String[] types) {
        if (types == null) {
            markUncertain(records);
            return;
        }
        for (String type : types) {
            MimeRecord record = records.get(codecForMime(type));
            if (record != null) record.enumerationUncertain = true;
        }
    }

    private static DecoderPolicy.VideoCodec codecForMime(String mime) {
        if (DecoderPolicy.VideoCodec.AVC.mime.equalsIgnoreCase(mime)) {
            return DecoderPolicy.VideoCodec.AVC;
        }
        if (DecoderPolicy.VideoCodec.HEVC.mime.equalsIgnoreCase(mime)) {
            return DecoderPolicy.VideoCodec.HEVC;
        }
        return DecoderPolicy.VideoCodec.UNKNOWN;
    }

    static HardwareIdentity classifyLegacyDecoderName(String decoderName) {
        String name = decoderName == null ? "" : decoderName.toLowerCase(Locale.ROOT);
        if (name.isEmpty() || "<unknown>".equals(name)) return HardwareIdentity.UNKNOWN;
        if (name.startsWith("omx.google.")
                || name.startsWith("omx.pv.")
                || name.startsWith("c2.android.")
                || name.startsWith("c2.google.")
                || name.contains("ffmpeg")
                || name.contains("software")
                || name.contains("ittiam")
                || name.contains(".sw.")) {
            return HardwareIdentity.SOFTWARE;
        }
        // On API 27/28 an OMX component name is positive platform-era evidence of a
        // vendor codec after explicit software implementations are removed. Other
        // naming schemes remain UNKNOWN rather than being guessed hardware.
        return name.startsWith("omx.")
                ? HardwareIdentity.HARDWARE : HardwareIdentity.UNKNOWN;
    }

    private static String safeName(MediaCodecInfo info) {
        try {
            return info.getName();
        } catch (Throwable ignored) {
            return "<unknown>";
        }
    }

    private static void logProbeFailure(String message, String subject, Throwable throwable) {
        if (BuildConfig.DEBUG) {
            XposedBridge.log("BiliPartFix/SmartAuto: " + message + " for " + subject,
                    throwable);
        }
    }
}
