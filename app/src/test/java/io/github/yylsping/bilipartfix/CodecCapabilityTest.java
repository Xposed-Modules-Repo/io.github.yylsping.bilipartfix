package io.github.yylsping.bilipartfix;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CodecCapabilityTest {
    @Test
    public void api27And28LegacyNamesRequirePositiveHardwareEvidence() {
        assertEquals(CodecCapability.HardwareIdentity.HARDWARE,
                CodecCapability.classifyLegacyDecoderName("OMX.qcom.video.decoder.avc"));
        assertEquals(CodecCapability.HardwareIdentity.SOFTWARE,
                CodecCapability.classifyLegacyDecoderName("OMX.google.h264.decoder"));
        assertEquals(CodecCapability.HardwareIdentity.SOFTWARE,
                CodecCapability.classifyLegacyDecoderName("c2.android.avc.decoder"));
        assertEquals(CodecCapability.HardwareIdentity.UNKNOWN,
                CodecCapability.classifyLegacyDecoderName("c2.qti.avc.decoder"));
        assertEquals(CodecCapability.HardwareIdentity.UNKNOWN,
                CodecCapability.classifyLegacyDecoderName("vendor.decoder.avc"));
    }
}
