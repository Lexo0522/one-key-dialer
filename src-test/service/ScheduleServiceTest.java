package service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleServiceTest {

    @Test
    void shouldFireDialWhenOfflineIdleAndMinuteMatches() {
        assertTrue(ScheduleService.shouldFireDial(true, false, false, true, 100L, -1L));
    }

    @Test
    void shouldNotFireDialWhenOnline() {
        assertFalse(ScheduleService.shouldFireDial(true, true, false, true, 100L, -1L));
    }

    @Test
    void shouldNotFireDialWhenBusy() {
        assertFalse(ScheduleService.shouldFireDial(true, false, true, true, 100L, -1L));
    }

    @Test
    void shouldNotFireDialWhenDisabledOrMinuteMismatch() {
        assertFalse(ScheduleService.shouldFireDial(false, false, false, true, 100L, -1L));
        assertFalse(ScheduleService.shouldFireDial(true, false, false, false, 100L, -1L));
    }

    @Test
    void shouldNotDoubleFireDialSameEpochMinute() {
        assertFalse(ScheduleService.shouldFireDial(true, false, false, true, 100L, 100L));
        assertTrue(ScheduleService.shouldFireDial(true, false, false, true, 101L, 100L));
    }

    @Test
    void shouldFireDisconnectWhenOnlineIdleAndMinuteMatches() {
        assertTrue(ScheduleService.shouldFireDisconnect(true, true, false, true, 50L, -1L));
    }

    @Test
    void shouldNotFireDisconnectWhenOfflineOrBusy() {
        assertFalse(ScheduleService.shouldFireDisconnect(true, false, false, true, 50L, -1L));
        assertFalse(ScheduleService.shouldFireDisconnect(true, true, true, true, 50L, -1L));
    }

    @Test
    void shouldNotDoubleFireDisconnectSameEpochMinute() {
        assertFalse(ScheduleService.shouldFireDisconnect(true, true, false, true, 50L, 50L));
    }
}
