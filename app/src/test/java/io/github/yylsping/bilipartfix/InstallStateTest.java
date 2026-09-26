package io.github.yylsping.bilipartfix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InstallStateTest {
    @Test
    public void firstInitializerWins() {
        InstallState state = new InstallState();
        assertFalse(state.isInitialized());
        assertTrue(state.beginInitialization());
        assertTrue(state.isInitialized());
    }

    @Test
    public void initializationIsOneWay() {
        InstallState state = new InstallState();
        assertTrue(state.beginInitialization());
        // Simulates a later Application.attach after a single feature failed:
        // the state must never re-open, otherwise hooks could be installed twice.
        assertFalse(state.beginInitialization());
        assertFalse(state.beginInitialization());
        assertTrue(state.isInitialized());
    }
}
