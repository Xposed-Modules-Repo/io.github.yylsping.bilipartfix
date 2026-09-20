package io.github.yylsping.bilipartfix;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Thread-safe weak set whose key semantics are object identity rather than equals/hashCode.
 */
final class WeakIdentitySet<T> {
    private final ReferenceQueue<T> collected = new ReferenceQueue<>();
    private final Map<IdentityWeakReference<T>, Boolean> entries = new HashMap<>();

    synchronized boolean add(T value) {
        if (value == null) return false;
        removeCollectedEntries();
        return entries.put(new IdentityWeakReference<>(value, collected), Boolean.TRUE) == null;
    }

    synchronized boolean contains(T value) {
        if (value == null) return false;
        removeCollectedEntries();
        return entries.containsKey(new IdentityWeakReference<>(value));
    }

    synchronized int size() {
        removeCollectedEntries();
        return entries.size();
    }

    @SuppressWarnings("unchecked")
    private void removeCollectedEntries() {
        IdentityWeakReference<T> reference;
        while ((reference = (IdentityWeakReference<T>) collected.poll()) != null) {
            entries.remove(reference);
        }
    }

    private static final class IdentityWeakReference<T> extends WeakReference<T> {
        private final int identityHash;

        IdentityWeakReference(T referent, ReferenceQueue<T> queue) {
            super(referent, queue);
            identityHash = System.identityHashCode(referent);
        }

        IdentityWeakReference(T referent) {
            super(referent);
            identityHash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityWeakReference)) return false;
            Object mine = get();
            return mine != null && mine == ((IdentityWeakReference<?>) other).get();
        }
    }
}
