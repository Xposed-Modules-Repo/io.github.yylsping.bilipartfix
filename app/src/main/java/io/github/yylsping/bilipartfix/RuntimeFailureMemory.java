package io.github.yylsping.bilipartfix;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded process-only memory for decoder/spec failures captured by reliable hooks. */
final class RuntimeFailureMemory implements DecoderPolicy.FailureMemory {
    private static final int MAX_ENTRIES = 32;
    private final Map<String, Boolean> failures = new LinkedHashMap<String, Boolean>(
            MAX_ENTRIES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    synchronized void recordFailure(DecoderPolicy.StreamInfo stream, String decoderName) {
        if (stream != null) failures.put(stream.failureKey(decoderName), Boolean.TRUE);
    }

    @Override
    public synchronized boolean isKnownBad(DecoderPolicy.StreamInfo stream,
                                           String decoderName) {
        return stream != null && failures.containsKey(stream.failureKey(decoderName));
    }

    synchronized int size() {
        return failures.size();
    }
}
