package service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoReconnectServiceTest {

    @Test
    void clampIntervalSecondsEnforcesMinimumFive() {
        assertEquals(5, AutoReconnectService.clampIntervalSeconds(0));
        assertEquals(5, AutoReconnectService.clampIntervalSeconds(4));
        assertEquals(5, AutoReconnectService.clampIntervalSeconds(5));
        assertEquals(30, AutoReconnectService.clampIntervalSeconds(30));
    }

    @Test
    void shouldAttemptReconnectDialOnlyWhenOfflineAndIdle() {
        assertTrue(AutoReconnectService.shouldAttemptReconnectDial(false, false));
        assertFalse(AutoReconnectService.shouldAttemptReconnectDial(true, false));
        assertFalse(AutoReconnectService.shouldAttemptReconnectDial(false, true));
        assertFalse(AutoReconnectService.shouldAttemptReconnectDial(true, true));
    }
}
