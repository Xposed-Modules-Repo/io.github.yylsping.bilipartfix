package io.github.yylsping.bilipartfix;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-way module initialization state for a single process. Once business
 * initialization has begun it never re-opens: a single feature failing to
 * install must not reset the whole module back to "not initialized", because
 * other features may already be hooked by then.
 */
final class InstallState {
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** Returns true exactly once per process, for the first initializer. */
    boolean beginInitialization() {
        return initialized.compareAndSet(false, true);
    }

    boolean isInitialized() {
        return initialized.get();
    }
}
