package service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DialPrecheckTest {

    @Test
    void alreadyOnlineBlocks() {
        Optional<DialPrecheck.Failure> f = DialPrecheck.check(true, true, "u", "p".toCharArray());
        assertTrue(f.isPresent());
        assertEquals(DialPrecheck.Failure.ALREADY_ONLINE, f.get());
    }

    @Test
    void emptyUsernameBlocks() {
        Optional<DialPrecheck.Failure> f = DialPrecheck.check(false, true, "  ", "p".toCharArray());
        assertEquals(DialPrecheck.Failure.EMPTY_USERNAME, f.get());
    }

    @Test
    void emptyPasswordBlocks() {
        Optional<DialPrecheck.Failure> f = DialPrecheck.check(false, true, "u", new char[]{' ', '\t'});
        assertEquals(DialPrecheck.Failure.EMPTY_PASSWORD, f.get());
    }

    @Test
    void noAccountBlocks() {
        Optional<DialPrecheck.Failure> f = DialPrecheck.check(false, false, "u", "p".toCharArray());
        assertEquals(DialPrecheck.Failure.NO_ACCOUNT, f.get());
    }

    @Test
    void okWhenAllPresent() {
        assertFalse(DialPrecheck.check(false, true, "u", "secret".toCharArray()).isPresent());
    }

    @Test
    void messagesNonEmpty() {
        for (DialPrecheck.Failure f : DialPrecheck.Failure.values()) {
            assertFalse(DialPrecheck.logMessage(f).isEmpty());
            assertFalse(DialPrecheck.dialogMessage(f).isEmpty());
        }
    }
}
