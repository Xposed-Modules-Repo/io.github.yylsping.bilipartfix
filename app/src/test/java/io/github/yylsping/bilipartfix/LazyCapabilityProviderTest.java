package io.github.yylsping.bilipartfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public final class LazyCapabilityProviderTest {
    @Test
    public void initializesOnceOnFirstNormalUgcAutoDecision() {
        AtomicInteger creations = new AtomicInteger();
        LazyCapabilityProvider lazy = new LazyCapabilityProvider(() -> {
            creations.incrementAndGet();
            return CodecCapability.fixed(DecoderPolicy.VideoCodec.AVC,
                    CodecCapability.Support.SUPPORTED, "test.hw.decoder");
        });
        DecoderPolicy policy = new DecoderPolicy();
        DecoderPolicy.StreamInfo stream = new DecoderPolicy.StreamInfo(
                DecoderPolicy.VideoCodec.AVC, 0, 0, 1920, 1080, 60f, 0,
                DecoderPolicy.HdrState.SDR, DecoderPolicy.ProtectionState.CLEAR, 116);

        assertFalse(lazy.isInitialized());
        policy.decide(CodecModeStore.Mode.AUTO, DecoderPolicy.Scope.HOST_UNSUPPORTED,
                stream, lazy, true, null, DecoderPolicy.LegacyPolicyConflict.none());
        policy.decide(CodecModeStore.Mode.V3_HW, DecoderPolicy.Scope.NORMAL_UGC,
                stream, lazy, true, null, DecoderPolicy.LegacyPolicyConflict.none());
        assertFalse("install/non-auto paths must not enumerate codecs", lazy.isInitialized());
        assertEquals(0, creations.get());

        policy.decide(CodecModeStore.Mode.AUTO, DecoderPolicy.Scope.NORMAL_UGC,
                stream, lazy, true, null, DecoderPolicy.LegacyPolicyConflict.none());
        policy.decide(CodecModeStore.Mode.AUTO, DecoderPolicy.Scope.NORMAL_UGC,
                stream, lazy, true, null, DecoderPolicy.LegacyPolicyConflict.none());

        assertTrue(lazy.isInitialized());
        assertEquals(1, creations.get());
    }
}
