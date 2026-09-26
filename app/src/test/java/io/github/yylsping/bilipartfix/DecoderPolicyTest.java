package io.github.yylsping.bilipartfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DecoderPolicyTest {
    private final DecoderPolicy policy = new DecoderPolicy();
    private final RuntimeFailureMemory noFailures = new RuntimeFailureMemory();

    @Test
    public void unknownStreamUsesHost() {
        assertDecision(DecoderPolicy.Decision.USE_HOST, DecoderPolicy.Reason.STREAM_UNKNOWN,
                CodecModeStore.Mode.AUTO, DecoderPolicy.Scope.NORMAL_UGC,
                DecoderPolicy.StreamInfo.unknown(), supported(DecoderPolicy.VideoCodec.AVC),
                noFailures);
    }

    @Test
    public void incompleteSdrStreamUsesHost() {
        DecoderPolicy.StreamInfo stream = stream(DecoderPolicy.VideoCodec.AVC,
                0, 0, 0f, DecoderPolicy.HdrState.SDR);
        assertDecision(DecoderPolicy.Decision.USE_HOST, DecoderPolicy.Reason.STREAM_UNKNOWN,
                CodecModeStore.Mode.AUTO, DecoderPolicy.Scope.NORMAL_UGC, stream,
                supported(DecoderPolicy.VideoCodec.AVC), noFailures);
    }

    @Test
    public void probeFailureDoesNotBecomeUnsupported() {
        DecoderPolicy.StreamInfo stream = stream(DecoderPolicy.VideoCodec.AVC,
                1920, 1080, 30f, DecoderPolicy.HdrState.SDR);
        assertDecision(DecoderPolicy.Decision.USE_HOST,
                DecoderPolicy.Reason.CAPABILITY_UNKNOWN, CodecModeStore.Mode.AUTO,
                DecoderPolicy.Scope.NORMAL_UGC, stream,
                CodecCapability.fixed(DecoderPolicy.VideoCodec.AVC,
                        CodecCapability.Support.UNKNOWN, "broken-probe"), noFailures);
    }

    @Test
    public void avcSupportedUsesHardware() {
        assertAutoCapability(DecoderPolicy.VideoCodec.AVC, 1920, 1080, 30f,
                CodecCapability.Support.SUPPORTED, DecoderPolicy.Decision.PREFER_HARDWARE);
    }

    @Test
    public void avcUnsupportedUsesSoftware() {
        assertAutoCapability(DecoderPolicy.VideoCodec.AVC, 1920, 1080, 30f,
                CodecCapability.Support.UNSUPPORTED, DecoderPolicy.Decision.USE_HOST);
    }

    @Test
    public void hevcSupportedUsesHardware() {
        assertAutoCapability(DecoderPolicy.VideoCodec.HEVC, 1920, 1080, 30f,
                CodecCapability.Support.SUPPORTED, DecoderPolicy.Decision.PREFER_HARDWARE);
    }

    @Test
    public void hevcUnsupportedUsesSoftware() {
        assertAutoCapability(DecoderPolicy.VideoCodec.HEVC, 1920, 1080, 30f,
                CodecCapability.Support.UNSUPPORTED, DecoderPolicy.Decision.USE_HOST);
    }

    @Test
    public void fullHd60SupportedUsesHardware() {
        assertAutoCapability(DecoderPolicy.VideoCodec.AVC, 1920, 1080, 60f,
                CodecCapability.Support.SUPPORTED, DecoderPolicy.Decision.PREFER_HARDWARE);
    }

    @Test
    public void fourK60SupportedUsesHardware() {
        assertAutoCapability(DecoderPolicy.VideoCodec.HEVC, 3840, 2160, 60f,
                CodecCapability.Support.SUPPORTED, DecoderPolicy.Decision.PREFER_HARDWARE);
    }

    @Test
    public void fourK60ClearlyUnsupportedUsesSoftware() {
        assertAutoCapability(DecoderPolicy.VideoCodec.HEVC, 3840, 2160, 60f,
                CodecCapability.Support.UNSUPPORTED, DecoderPolicy.Decision.USE_HOST);
    }

    @Test
    public void supportedStreamDoesNotOverrideUnexplainedHostSoftwarePreference() {
        DecoderPolicy.StreamInfo stream = stream(DecoderPolicy.VideoCodec.AVC,
                1920, 1080, 60f, DecoderPolicy.HdrState.SDR);
        DecoderPolicy.DecisionResult result = policy.decide(CodecModeStore.Mode.AUTO,
                DecoderPolicy.Scope.NORMAL_UGC, stream,
                supported(DecoderPolicy.VideoCodec.AVC), false, noFailures,
                DecoderPolicy.LegacyPolicyConflict.none());
        assertEquals(DecoderPolicy.Decision.USE_HOST, result.decision);
        assertEquals(DecoderPolicy.Reason.HOST_DECISION_PRESERVED, result.reason);
        assertEquals(DecoderPolicy.Confidence.NONE, result.confidence);
    }

    @Test
    public void provenLegacyRankConflictCanOverrideHostSoftwarePreference() {
        DecoderPolicy.StreamInfo stream = stream(DecoderPolicy.VideoCodec.AVC,
                1920, 1080, 60f, DecoderPolicy.HdrState.SDR);
        DecoderPolicy.LegacyPolicyConflict conflict = new DecoderPolicy.LegacyPolicyConflict(
                DecoderPolicy.LegacyReason.LEGACY_CODEC_RANK,
                "non-OMX rank rejected vendor C2", "c2.qti.avc.decoder", true,
                DecoderPolicy.Confidence.HIGH);
        DecoderPolicy.DecisionResult result = policy.decide(CodecModeStore.Mode.AUTO,
                DecoderPolicy.Scope.NORMAL_UGC, stream,
                supported(DecoderPolicy.VideoCodec.AVC), false, noFailures, conflict);
        assertEquals(DecoderPolicy.Decision.PREFER_HARDWARE, result.decision);
        assertEquals(DecoderPolicy.Reason.LEGACY_POLICY_CONFLICT, result.reason);
        assertEquals(DecoderPolicy.Confidence.HIGH, result.confidence);
    }

    @Test
    public void blacklistWithUnknownRationaleDoesNotOverrideHost() {
        DecoderPolicy.StreamInfo stream = stream(DecoderPolicy.VideoCodec.HEVC,
                1920, 1080, 30f, DecoderPolicy.HdrState.SDR);
        DecoderPolicy.LegacyPolicyConflict conflict = new DecoderPolicy.LegacyPolicyConflict(
                DecoderPolicy.LegacyReason.BLACKLIST_UNKNOWN_RATIONALE,
                "cloud blacklist", "c2.qti.hevc.decoder", true,
                DecoderPolicy.Confidence.HIGH);
        DecoderPolicy.DecisionResult result = policy.decide(CodecModeStore.Mode.AUTO,
                DecoderPolicy.Scope.NORMAL_UGC, stream,
                supported(DecoderPolicy.VideoCodec.HEVC), false, noFailures, conflict);
        assertEquals(DecoderPolicy.Decision.USE_HOST, result.decision);
        assertEquals(DecoderPolicy.Reason.HOST_DECISION_PRESERVED, result.reason);
    }

    @Test
    public void runtimeKnownBadUsesSoftware() {
        DecoderPolicy.StreamInfo stream = stream(DecoderPolicy.VideoCodec.HEVC,
                3840, 2160, 60f, DecoderPolicy.HdrState.SDR);
        RuntimeFailureMemory failures = new RuntimeFailureMemory();
        failures.recordFailure(stream, "c2.qti.hevc.decoder");
        assertDecision(DecoderPolicy.Decision.PREFER_SOFTWARE,
                DecoderPolicy.Reason.RUNTIME_KNOWN_BAD, CodecModeStore.Mode.AUTO,
                DecoderPolicy.Scope.NORMAL_UGC, stream,
                supported(DecoderPolicy.VideoCodec.HEVC), failures);
        assertEquals(1, failures.size());
    }

    @Test
    public void hdrUnknownStaysWithHost() {
        DecoderPolicy.StreamInfo stream = stream(DecoderPolicy.VideoCodec.HEVC,
                3840, 2160, 60f, DecoderPolicy.HdrState.UNKNOWN);
        assertDecision(DecoderPolicy.Decision.USE_HOST, DecoderPolicy.Reason.HDR_HOST_SAFE,
                CodecModeStore.Mode.AUTO, DecoderPolicy.Scope.NORMAL_UGC, stream,
                supported(DecoderPolicy.VideoCodec.HEVC), noFailures);
    }

    @Test
    public void hdrSoftwareModeStillPreservesHostSafetyPolicy() {
        DecoderPolicy.StreamInfo stream = stream(DecoderPolicy.VideoCodec.HEVC,
                3840, 2160, 60f, DecoderPolicy.HdrState.HDR);
        assertDecision(DecoderPolicy.Decision.USE_HOST, DecoderPolicy.Reason.HDR_HOST_SAFE,
                CodecModeStore.Mode.SOFTWARE, DecoderPolicy.Scope.NORMAL_UGC, stream,
                supported(DecoderPolicy.VideoCodec.HEVC), noFailures);
    }

    @Test
    public void explicitV3BypassesSmartAuto() {
        DecoderPolicy.StreamInfo stream = stream(DecoderPolicy.VideoCodec.HEVC,
                3840, 2160, 60f, DecoderPolicy.HdrState.SDR);
        assertDecision(DecoderPolicy.Decision.PREFER_HARDWARE, DecoderPolicy.Reason.USER_V3,
                CodecModeStore.Mode.V3_HW, DecoderPolicy.Scope.NORMAL_UGC, stream,
                CodecCapability.fixed(DecoderPolicy.VideoCodec.HEVC,
                        CodecCapability.Support.UNSUPPORTED, ""), noFailures);
    }

    @Test
    public void explicitSoftwareBypassesSmartAuto() {
        DecoderPolicy.StreamInfo stream = stream(DecoderPolicy.VideoCodec.AVC,
                1920, 1080, 30f, DecoderPolicy.HdrState.SDR);
        assertDecision(DecoderPolicy.Decision.PREFER_SOFTWARE,
                DecoderPolicy.Reason.USER_SOFTWARE, CodecModeStore.Mode.SOFTWARE,
                DecoderPolicy.Scope.NORMAL_UGC, stream,
                supported(DecoderPolicy.VideoCodec.AVC), noFailures);
    }

    @Test
    public void nonUgcScopeAlwaysUsesHost() {
        DecoderPolicy.StreamInfo stream = stream(DecoderPolicy.VideoCodec.AVC,
                1920, 1080, 60f, DecoderPolicy.HdrState.SDR);
        assertDecision(DecoderPolicy.Decision.USE_HOST,
                DecoderPolicy.Reason.HOST_SCOPE_UNSUPPORTED, CodecModeStore.Mode.V3_HW,
                DecoderPolicy.Scope.HOST_UNSUPPORTED, stream,
                supported(DecoderPolicy.VideoCodec.AVC), noFailures);
    }

    @Test
    public void drmAlwaysUsesHost() {
        DecoderPolicy.StreamInfo stream = new DecoderPolicy.StreamInfo(
                DecoderPolicy.VideoCodec.HEVC, 0, 0, 1920, 1080, 30f, 0,
                DecoderPolicy.HdrState.SDR, DecoderPolicy.ProtectionState.PROTECTED, 80);
        assertDecision(DecoderPolicy.Decision.USE_HOST, DecoderPolicy.Reason.DRM_HOST_SAFE,
                CodecModeStore.Mode.V3_HW, DecoderPolicy.Scope.NORMAL_UGC, stream,
                supported(DecoderPolicy.VideoCodec.HEVC), noFailures);
    }

    @Test
    public void unknownProtectionStateUsesHost() {
        // A failed DRM/protected read must fail open exactly like a confirmed
        // protected stream, in every decoder mode.
        DecoderPolicy.StreamInfo stream = new DecoderPolicy.StreamInfo(
                DecoderPolicy.VideoCodec.AVC, 0, 0, 1920, 1080, 30f, 0,
                DecoderPolicy.HdrState.SDR, DecoderPolicy.ProtectionState.UNKNOWN, 80);
        assertDecision(DecoderPolicy.Decision.USE_HOST, DecoderPolicy.Reason.DRM_HOST_SAFE,
                CodecModeStore.Mode.AUTO, DecoderPolicy.Scope.NORMAL_UGC, stream,
                supported(DecoderPolicy.VideoCodec.AVC), noFailures);
        assertDecision(DecoderPolicy.Decision.USE_HOST, DecoderPolicy.Reason.DRM_HOST_SAFE,
                CodecModeStore.Mode.V3_HW, DecoderPolicy.Scope.NORMAL_UGC, stream,
                supported(DecoderPolicy.VideoCodec.AVC), noFailures);
        assertDecision(DecoderPolicy.Decision.USE_HOST, DecoderPolicy.Reason.DRM_HOST_SAFE,
                CodecModeStore.Mode.SOFTWARE, DecoderPolicy.Scope.NORMAL_UGC, stream,
                supported(DecoderPolicy.VideoCodec.AVC), noFailures);
    }

    @Test
    public void clearProtectionContinuesDecoderPolicy() {
        DecoderPolicy.StreamInfo stream = new DecoderPolicy.StreamInfo(
                DecoderPolicy.VideoCodec.AVC, 0, 0, 1920, 1080, 30f, 0,
                DecoderPolicy.HdrState.SDR, DecoderPolicy.ProtectionState.CLEAR, 80);
        assertDecision(DecoderPolicy.Decision.PREFER_HARDWARE, DecoderPolicy.Reason.HW_SUPPORTED,
                CodecModeStore.Mode.AUTO, DecoderPolicy.Scope.NORMAL_UGC, stream,
                supported(DecoderPolicy.VideoCodec.AVC), noFailures);
    }

    @Test
    public void invalidStoredValueFallsBackToAuto() {
        assertEquals(CodecModeStore.Mode.AUTO,
                CodecModeStore.Mode.fromStoredValue("future-or-corrupt-value"));
        assertEquals(CodecModeStore.Mode.AUTO, CodecModeStore.Mode.fromStoredValue(null));
    }

    @Test
    public void frameRateParsesIntegerAndRationalForms() {
        assertEquals(60f, StreamInfoExtractor.parseFrameRate("60"), 0.001f);
        assertEquals(59.94f, StreamInfoExtractor.parseFrameRate("60000/1001"), 0.01f);
        assertTrue(StreamInfoExtractor.parseFrameRate("bad") == 0f);
    }

    @Test
    public void secureOnlyDecoderNamesAreExcludedFromOrdinaryUgc() {
        assertTrue(CodecCapability.isSecureOnlyName("c2.qti.hevc.decoder.secure"));
        assertTrue(CodecCapability.isSecureOnlyName("OMX.vendor.secure.avc.decoder"));
        assertTrue(!CodecCapability.isSecureOnlyName("c2.qti.hevc.decoder"));
    }

    @Test
    public void protectionReadMapsOnlyRealBooleans() {
        assertEquals(DecoderPolicy.ProtectionState.PROTECTED,
                StreamInfoExtractor.protectionState(new FakeProtection(Boolean.TRUE), "E"));
        assertEquals(DecoderPolicy.ProtectionState.CLEAR,
                StreamInfoExtractor.protectionState(new FakeProtection(Boolean.FALSE), "E"));
        // A non-Boolean return value is not evidence of clear content.
        assertEquals(DecoderPolicy.ProtectionState.UNKNOWN,
                StreamInfoExtractor.protectionState(new FakeProtection("not-a-boolean"), "E"));
        // A reflection failure (missing/renamed method) must stay UNKNOWN so the
        // policy fails open instead of assuming clear.
        assertEquals(DecoderPolicy.ProtectionState.UNKNOWN,
                StreamInfoExtractor.protectionState(new FakeProtection(Boolean.FALSE), "missing"));
    }

    /** Stands in for the obfuscated MediaResource protection getter. */
    public static final class FakeProtection {
        private final Object value;

        FakeProtection(Object value) {
            this.value = value;
        }

        public Object E() {
            return value;
        }
    }

    private void assertAutoCapability(DecoderPolicy.VideoCodec codec, int width, int height,
                                      float fps, CodecCapability.Support support,
                                      DecoderPolicy.Decision expected) {
        DecoderPolicy.StreamInfo stream = stream(codec, width, height, fps,
                DecoderPolicy.HdrState.SDR);
        DecoderPolicy.DecisionResult result = policy.decide(CodecModeStore.Mode.AUTO,
                DecoderPolicy.Scope.NORMAL_UGC, stream,
                CodecCapability.fixed(codec, support, "test.hw.decoder"), true, noFailures,
                DecoderPolicy.LegacyPolicyConflict.none());
        assertEquals(expected, result.decision);
    }

    private void assertDecision(DecoderPolicy.Decision expectedDecision,
                                DecoderPolicy.Reason expectedReason,
                                CodecModeStore.Mode mode, DecoderPolicy.Scope scope,
                                DecoderPolicy.StreamInfo stream,
                                DecoderPolicy.CapabilityProvider capability,
                                DecoderPolicy.FailureMemory failures) {
        DecoderPolicy.DecisionResult result = policy.decide(mode, scope, stream,
                capability, true, failures, DecoderPolicy.LegacyPolicyConflict.none());
        assertEquals(expectedDecision, result.decision);
        assertEquals(expectedReason, result.reason);
    }

    private CodecCapability supported(DecoderPolicy.VideoCodec codec) {
        return CodecCapability.fixed(codec, CodecCapability.Support.SUPPORTED,
                codec == DecoderPolicy.VideoCodec.AVC
                        ? "c2.qti.avc.decoder" : "c2.qti.hevc.decoder");
    }

    private DecoderPolicy.StreamInfo stream(DecoderPolicy.VideoCodec codec,
                                            int width, int height, float fps,
                                            DecoderPolicy.HdrState hdr) {
        return new DecoderPolicy.StreamInfo(codec, 0, 0, width, height, fps, 0,
                hdr, DecoderPolicy.ProtectionState.CLEAR, 80);
    }
}
