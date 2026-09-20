package io.github.yylsping.bilipartfix;

import java.util.Locale;

/** Pure decision engine for the 7040300-only Smart Auto policy. */
final class DecoderPolicy {
    enum Decision { USE_HOST, PREFER_HARDWARE, PREFER_SOFTWARE }

    enum Confidence { NONE, LOW, MEDIUM, HIGH }

    enum Reason {
        HOST_SCOPE_UNSUPPORTED, DRM_HOST_SAFE, HDR_HOST_SAFE, USER_V3, USER_SOFTWARE,
        STREAM_UNKNOWN, HW_SUPPORTED, HW_UNSUPPORTED, CAPABILITY_UNKNOWN,
        RUNTIME_KNOWN_BAD, HOST_DECISION_PRESERVED, LEGACY_POLICY_CONFLICT
    }

    enum Scope { NORMAL_UGC, HOST_UNSUPPORTED }

    enum VideoCodec {
        AVC("video/avc"), HEVC("video/hevc"), UNKNOWN("");

        final String mime;

        VideoCodec(String mime) { this.mime = mime; }
    }

    enum HdrState { SDR, HDR, UNKNOWN }

    interface CapabilityProvider {
        CodecCapability.Assessment assess(StreamInfo stream);
    }

    interface FailureMemory {
        boolean isKnownBad(StreamInfo stream, String decoderName);
    }

    enum LegacyReason {
        NONE,
        LEGACY_CODEC_RANK,
        BLACKLIST_UNKNOWN_RATIONALE,
        OTHER_UNKNOWN
    }

    /** Evidence that explains why a host software preference is obsolete for this stream. */
    static final class LegacyPolicyConflict {
        final LegacyReason reason;
        final String legacyDecision;
        final String modernDecoder;
        final boolean sameStreamRuntimeSucceeded;
        final Confidence confidence;

        LegacyPolicyConflict(LegacyReason reason, String legacyDecision,
                             String modernDecoder, boolean sameStreamRuntimeSucceeded,
                             Confidence confidence) {
            this.reason = reason == null ? LegacyReason.NONE : reason;
            this.legacyDecision = legacyDecision == null ? "" : legacyDecision;
            this.modernDecoder = modernDecoder == null ? "" : modernDecoder;
            this.sameStreamRuntimeSucceeded = sameStreamRuntimeSucceeded;
            this.confidence = confidence == null ? Confidence.NONE : confidence;
        }

        static LegacyPolicyConflict none() {
            return new LegacyPolicyConflict(LegacyReason.NONE, "", "", false,
                    Confidence.NONE);
        }

        boolean isActionableFor(CodecCapability.Assessment assessment) {
            if (reason != LegacyReason.LEGACY_CODEC_RANK
                    || confidence != Confidence.HIGH
                    || !sameStreamRuntimeSucceeded
                    || assessment == null
                    || assessment.support != CodecCapability.Support.SUPPORTED) {
                return false;
            }
            return modernDecoder.isEmpty()
                    || modernDecoder.equalsIgnoreCase(assessment.decoderName);
        }
    }

    static final class StreamInfo {
        final VideoCodec codec;
        final int profile;
        final int level;
        final int width;
        final int height;
        final float fps;
        final int bitDepth;
        final HdrState hdr;
        final boolean protectedContent;
        final int qualityId;

        StreamInfo(VideoCodec codec, int profile, int level, int width, int height,
                   float fps, int bitDepth, HdrState hdr, boolean protectedContent,
                   int qualityId) {
            this.codec = codec == null ? VideoCodec.UNKNOWN : codec;
            this.profile = profile;
            this.level = level;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bitDepth = bitDepth;
            this.hdr = hdr == null ? HdrState.UNKNOWN : hdr;
            this.protectedContent = protectedContent;
            this.qualityId = qualityId;
        }

        static StreamInfo unknown() {
            return new StreamInfo(VideoCodec.UNKNOWN, 0, 0, 0, 0, 0f, 0,
                    HdrState.UNKNOWN, false, 0);
        }

        boolean hasEssentialMetadata() {
            return codec != VideoCodec.UNKNOWN && width > 0 && height > 0 && fps > 0f;
        }

        String failureKey(String decoderName) {
            return String.format(Locale.ROOT, "%s|%s|p%d|l%d|b%d|%dx%d|f%d|%s",
                    decoderName == null ? "?" : decoderName, codec.mime, profile, level,
                    bitDepth, bucket(width, 256), bucket(height, 128),
                    bucket(Math.round(fps), 15), hdr);
        }

        private static int bucket(int value, int step) {
            if (value <= 0) return 0;
            return ((value + step - 1) / step) * step;
        }
    }

    static final class DecisionResult {
        final Decision decision;
        final Reason reason;
        final Confidence confidence;
        final CodecCapability.Assessment capability;
        final boolean failureCacheHit;

        DecisionResult(Decision decision, Reason reason, Confidence confidence,
                       CodecCapability.Assessment capability, boolean failureCacheHit) {
            this.decision = decision;
            this.reason = reason;
            this.confidence = confidence == null ? Confidence.NONE : confidence;
            this.capability = capability;
            this.failureCacheHit = failureCacheHit;
        }

        static DecisionResult of(Decision decision, Reason reason) {
            return new DecisionResult(decision, reason, Confidence.NONE,
                    CodecCapability.Assessment.unknown("not queried"), false);
        }

        static DecisionResult of(Decision decision, Reason reason, Confidence confidence) {
            return new DecisionResult(decision, reason, confidence,
                    CodecCapability.Assessment.unknown("not queried"), false);
        }
    }

    DecisionResult decide(CodecModeStore.Mode mode, Scope scope, StreamInfo stream,
                          CapabilityProvider capabilityProvider,
                          boolean hostHardwarePreference, FailureMemory failureMemory,
                          LegacyPolicyConflict legacyConflict) {
        CodecModeStore.Mode safeMode = mode == null ? CodecModeStore.Mode.AUTO : mode;
        StreamInfo safeStream = stream == null ? StreamInfo.unknown() : stream;

        if (scope != Scope.NORMAL_UGC) {
            return DecisionResult.of(Decision.USE_HOST, Reason.HOST_SCOPE_UNSUPPORTED);
        }
        if (safeStream.protectedContent) {
            return DecisionResult.of(Decision.USE_HOST, Reason.DRM_HOST_SAFE);
        }
        // In Auto, missing selected-representation metadata is the primary reason
        // to fail open. Do this before interpreting an UNKNOWN HDR field so logs
        // distinguish "stream was not resolved" from a real HDR safety decision.
        if (safeMode == CodecModeStore.Mode.AUTO && !safeStream.hasEssentialMetadata()) {
            return DecisionResult.of(Decision.USE_HOST, Reason.STREAM_UNKNOWN);
        }
        // HDR/Dolby owns renderer and codec overrides downstream in 7040300.
        if (safeStream.hdr != HdrState.SDR) {
            return DecisionResult.of(Decision.USE_HOST, Reason.HDR_HOST_SAFE);
        }
        if (safeMode == CodecModeStore.Mode.V3_HW) {
            return DecisionResult.of(Decision.PREFER_HARDWARE, Reason.USER_V3,
                    Confidence.HIGH);
        }
        if (safeMode == CodecModeStore.Mode.SOFTWARE) {
            return DecisionResult.of(Decision.PREFER_SOFTWARE, Reason.USER_SOFTWARE,
                    Confidence.HIGH);
        }
        if (capabilityProvider == null) {
            return DecisionResult.of(Decision.USE_HOST, Reason.STREAM_UNKNOWN);
        }

        CodecCapability.Assessment assessment;
        try {
            assessment = capabilityProvider.assess(safeStream);
        } catch (Throwable throwable) {
            assessment = CodecCapability.Assessment.unknown(
                    "capability provider threw " + throwable.getClass().getSimpleName());
        }
        if (assessment == null) {
            assessment = CodecCapability.Assessment.unknown("null capability result");
        }

        boolean knownBad = failureMemory != null
                && failureMemory.isKnownBad(safeStream, assessment.decoderName);
        if (knownBad) {
            return new DecisionResult(Decision.PREFER_SOFTWARE,
                    Reason.RUNTIME_KNOWN_BAD, Confidence.HIGH, assessment, true);
        }

        if (hostHardwarePreference) {
            if (assessment.support == CodecCapability.Support.SUPPORTED) {
                return new DecisionResult(Decision.PREFER_HARDWARE,
                        Reason.HW_SUPPORTED, Confidence.NONE, assessment, false);
            }
            if (assessment.support == CodecCapability.Support.UNKNOWN) {
                return new DecisionResult(Decision.USE_HOST,
                        Reason.CAPABILITY_UNKNOWN, Confidence.NONE, assessment, false);
            }
            // OEM capability tables can under-report support and IJK retains a native
            // fallback. Do not pre-empt a host hardware choice from size/rate alone.
            return new DecisionResult(Decision.USE_HOST,
                    Reason.HOST_DECISION_PRESERVED, Confidence.NONE, assessment, false);
        }

        LegacyPolicyConflict safeConflict = legacyConflict == null
                ? LegacyPolicyConflict.none() : legacyConflict;
        if (safeConflict.isActionableFor(assessment)) {
            return new DecisionResult(Decision.PREFER_HARDWARE,
                    Reason.LEGACY_POLICY_CONFLICT, Confidence.HIGH, assessment, false);
        }
        // A host software decision is preserved until its exact selector/blacklist
        // cause is both understood and disproved by same-stream runtime evidence.
        return new DecisionResult(Decision.USE_HOST,
                assessment.support == CodecCapability.Support.UNKNOWN
                        ? Reason.CAPABILITY_UNKNOWN : Reason.HOST_DECISION_PRESERVED,
                Confidence.NONE, assessment, false);
    }
}
