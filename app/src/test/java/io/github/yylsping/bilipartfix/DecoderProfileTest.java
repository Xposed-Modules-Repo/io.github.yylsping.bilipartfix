package io.github.yylsping.bilipartfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DecoderProfileTest {
    @Test
    public void bothSupportedHostsHaveProfiles() {
        assertNotNull(DecoderProfile.forHost(HostVersion.V7040300));
        assertNotNull(DecoderProfile.forHost(HostVersion.V7420400));
        assertNull(DecoderProfile.forHost(HostVersion.UNSUPPORTED));
    }

    @Test
    public void versionSymbolsDoNotCross() {
        DecoderProfile legacy = DecoderProfile.forHost(HostVersion.V7040300);
        DecoderProfile modern = DecoderProfile.forHost(HostVersion.V7420400);

        // The item options class was renamed between the two hosts.
        assertEquals("tv.danmaku.videoplayer.coreV2.transformer.d",
                legacy.itemOptionsClass);
        assertEquals("tv.danmaku.videoplayer.coreV2.transformer.MediaItemParams",
                modern.itemOptionsClass);

        // Every remapped getter must differ so a profile mix-up can never
        // silently "succeed" against the wrong host build.
        assertSymbolsDiffer(legacy, modern);
    }

    private static void assertSymbolsDiffer(DecoderProfile a, DecoderProfile b) {
        assertTrue(!a.hardwarePreferenceGetter.equals(b.hardwarePreferenceGetter));
        assertTrue(!a.assetBuildMethod.equals(b.assetBuildMethod));
        assertTrue(!a.playIndexGetter.equals(b.playIndexGetter));
        assertTrue(!a.playIndexStatField.equals(b.playIndexStatField));
        assertTrue(!a.protectedContentMethod.equals(b.protectedContentMethod));
        assertTrue(!a.optionsQualityGetter.equals(b.optionsQualityGetter));
        assertTrue(!a.hdrGetter.equals(b.hdrGetter));
        assertTrue(!a.dolbyGetter.equals(b.dolbyGetter));
        assertTrue(!a.dashQualityGetter.equals(b.dashQualityGetter));
        assertTrue(!a.dashCodecGetter.equals(b.dashCodecGetter));
        assertTrue(!a.frameRateField.equals(b.frameRateField));
        assertTrue(!a.scopeTaskClass.equals(b.scopeTaskClass));
    }

    @Test
    public void scopeCallbacksStayInsideNormalVideoPlayHandler() {
        for (HostVersion host : new HostVersion[]{
                HostVersion.V7040300, HostVersion.V7420400}) {
            DecoderProfile profile = DecoderProfile.forHost(host);
            assertTrue(profile.scopeCallbackClasses.length > 0);
            for (String callback : profile.scopeCallbackClasses) {
                assertTrue(callback.startsWith(
                        "tv.danmaku.biliplayerv2.service.NormalVideoPlayHandler$"));
            }
        }
    }
}
