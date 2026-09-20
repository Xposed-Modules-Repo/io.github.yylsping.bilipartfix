package io.github.yylsping.bilipartfix;

/** Initializes and caches the MediaCodec capability snapshot on first policy demand. */
final class LazyCapabilityProvider implements DecoderPolicy.CapabilityProvider {
    interface Factory {
        DecoderPolicy.CapabilityProvider create();
    }

    private final Factory factory;
    private volatile DecoderPolicy.CapabilityProvider delegate;

    LazyCapabilityProvider(Factory factory) {
        if (factory == null) throw new NullPointerException("factory");
        this.factory = factory;
    }

    @Override
    public CodecCapability.Assessment assess(DecoderPolicy.StreamInfo stream) {
        return getOrCreate().assess(stream);
    }

    boolean isInitialized() {
        return delegate != null;
    }

    private DecoderPolicy.CapabilityProvider getOrCreate() {
        DecoderPolicy.CapabilityProvider current = delegate;
        if (current != null) return current;
        synchronized (this) {
            current = delegate;
            if (current == null) {
                try {
                    current = factory.create();
                    if (current == null) {
                        current = stream -> CodecCapability.Assessment.unknown(
                                "capability factory returned null");
                    }
                } catch (Throwable throwable) {
                    String detail = "capability initialization failed: "
                            + throwable.getClass().getSimpleName();
                    current = stream -> CodecCapability.Assessment.unknown(detail);
                    XposedBridge.log("SmartAuto " + detail, throwable);
                }
                delegate = current;
            }
        }
        return current;
    }
}
