package io.github.yylsping.bilipartfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.ref.WeakReference;

public final class WeakIdentitySetTest {
    @Test
    public void equalButNonIdenticalObjectsDoNotCrossScope() {
        WeakIdentitySet<EqualValue> set = new WeakIdentitySet<>();
        EqualValue first = new EqualValue(7);
        EqualValue equalCopy = new EqualValue(7);

        assertFalse(first == equalCopy);
        assertTrue(first.equals(equalCopy));
        set.add(first);

        assertTrue(set.contains(first));
        assertFalse(set.contains(equalCopy));
        assertEquals(1, set.size());
    }

    @Test
    public void collectedIdentityIsRemovedThroughReferenceQueue() throws Exception {
        WeakIdentitySet<EqualValue> set = new WeakIdentitySet<>();
        WeakReference<EqualValue> observed = addTemporaryValue(set);

        for (int attempt = 0; attempt < 100 && observed.get() != null; attempt++) {
            System.gc();
            System.runFinalization();
            Thread.sleep(5L);
        }

        assertNull("temporary key should be collectable", observed.get());
        assertEquals("queue cleanup should remove the stale entry", 0, set.size());
    }

    private static WeakReference<EqualValue> addTemporaryValue(
            WeakIdentitySet<EqualValue> set) {
        EqualValue value = new EqualValue(11);
        set.add(value);
        return new WeakReference<>(value);
    }

    private static final class EqualValue {
        private final int value;

        EqualValue(int value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualValue && value == ((EqualValue) other).value;
        }

        @Override
        public int hashCode() {
            return value;
        }
    }
}
