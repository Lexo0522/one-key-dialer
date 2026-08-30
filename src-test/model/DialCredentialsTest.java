package model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** One-shot dial credentials: char[] storage and mandatory zeroing. */
class DialCredentialsTest {
    @Test
    void storesTrimmedUsernameAndCopiesPassword() {
        char[] input = "secret".toCharArray();
        DialCredentials c = new DialCredentials("  user1  ", input);
        assertEquals("user1", c.username());
        assertTrue(c.passwordEquals("secret".toCharArray()));

        // The constructor owns a copy — mutating the input does not affect it.
        Arrays.fill(input, 'x');
        assertTrue(c.passwordEquals("secret".toCharArray()));
    }

    @Test
    void clearZeroesPassword() {
        DialCredentials c = new DialCredentials("user", "topsecret".toCharArray());
        assertTrue(c.hasPassword());
        c.clear();
        assertFalse(c.hasPassword());
        assertFalse(c.hasUsername() && c.hasPassword());
    }

    @Test
    void copyIsDefensive() {
        DialCredentials c = new DialCredentials("user", "pw".toCharArray());
        char[] copy = c.copyPassword();
        Arrays.fill(copy, '\0');
        assertTrue(c.passwordEquals("pw".toCharArray()));
    }

    @Test
    void passwordAsStringForRasdialArgvOnly() {
        DialCredentials c = new DialCredentials("user", " pw ".toCharArray());
        assertEquals("pw", c.passwordAsString());
        c.clear();
        assertEquals("", c.passwordAsString());
    }

    @Test
    void nullInputAccepted() {
        DialCredentials c = new DialCredentials(null, null);
        assertEquals("", c.username());
        assertFalse(c.hasUsername());
        assertFalse(c.hasPassword());
        c.clear(); // must not throw
    }

    @Test
    void passwordEqualsCloneHelper() {
        DialCredentials c = new DialCredentials("u", "abc".toCharArray());
        assertTrue(c.passwordEquals(new char[]{'a', 'b', 'c'}));
        assertFalse(c.passwordEquals(new char[]{'a', 'b', 'x'}));
    }
}
