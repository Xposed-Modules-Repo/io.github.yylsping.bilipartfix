package io.github.yylsping.bilipartfix;

import org.junit.Test;

import static org.junit.Assert.*;

public class CodecModeStoreTest {
    @Test public void storedValuesAreStable() {
        // Existing installs already persisted these values; never change them.
        assertEquals("auto", CodecModeStore.Mode.AUTO.storedValue);
        assertEquals("v3_hw", CodecModeStore.Mode.V3_HW.storedValue);
        assertEquals("software", CodecModeStore.Mode.SOFTWARE.storedValue);
    }

    @Test public void knownValuesRoundTrip() {
        for (CodecModeStore.Mode mode : CodecModeStore.Mode.values()) {
            assertEquals(mode, CodecModeStore.Mode.fromStoredValue(mode.storedValue));
        }
    }

    @Test public void unknownAndNullValuesFallBackToAuto() {
        assertEquals(CodecModeStore.Mode.AUTO,
                CodecModeStore.Mode.fromStoredValue("bogus"));
        assertEquals(CodecModeStore.Mode.AUTO,
                CodecModeStore.Mode.fromStoredValue(""));
        assertEquals(CodecModeStore.Mode.AUTO,
                CodecModeStore.Mode.fromStoredValue(null));
    }
}
