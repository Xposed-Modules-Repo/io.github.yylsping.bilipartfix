package io.github.yylsping.bilipartfix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HostVersionTest {
    @Test
    public void recognizes7040300() {
        HostVersion version = HostVersion.from(7040300L);
        assertEquals(HostVersion.V7040300, version);
        assertTrue(version.isSupported());
        assertEquals("w1", version.dataSourceRouterMethod());
    }

    @Test
    public void recognizes7420400() {
        HostVersion version = HostVersion.from(7420400L);
        assertEquals(HostVersion.V7420400, version);
        assertTrue(version.isSupported());
        assertEquals("A1", version.dataSourceRouterMethod());
    }

    @Test
    public void rejectsUnknownVersions() {
        assertEquals(HostVersion.UNSUPPORTED, HostVersion.from(0L));
        assertEquals(HostVersion.UNSUPPORTED, HostVersion.from(-1L));
        assertEquals(HostVersion.UNSUPPORTED, HostVersion.from(7040301L));
        assertEquals(HostVersion.UNSUPPORTED, HostVersion.from(7420401L));
        assertEquals(HostVersion.UNSUPPORTED, HostVersion.from(7070000L));
        assertFalse(HostVersion.UNSUPPORTED.isSupported());
        assertNull(HostVersion.UNSUPPORTED.dataSourceRouterMethod());
    }

    @Test
    public void routerMethodsAreVersionSpecificAndNotCrossed() {
        assertFalse(HostVersion.V7040300.dataSourceRouterMethod()
                .equals(HostVersion.V7420400.dataSourceRouterMethod()));
    }

    @Test
    public void featurePlanIsVersionSpecific() {
        // 7040300 keeps the full legacy fix set.
        assertTrue(HostVersion.V7040300.installsLegacyFixes());
        // 7420400 installs multipart + watch-later + decoder mode fixes (see
        // FeatureInstallSummaryTest for the result-reporting side).
        assertFalse(HostVersion.V7420400.installsLegacyFixes());
        assertFalse(HostVersion.UNSUPPORTED.installsLegacyFixes());
    }
}
