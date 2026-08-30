package service;

import model.DialCredentials;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Struct building only — never calls RasDialW (that would attempt a real dial).
 * The native path itself stays behind the nativeDialPreferred flag in production.
 */
class NativeRasDialTest {

    @Test
    void buildParamsFillsEntryUserPasswordAndSize() {
        DialCredentials creds = new DialCredentials("user1", "pass1".toCharArray());
        NativeRasDial.RASDIALPARAMSW p = NativeRasDial.buildParams(
            "pppoe_native_java", creds.username().toCharArray(), creds.copyPassword());
        assertEquals(p.size(), p.dwSize);
        assertEquals("pppoe_native_java", text(p.szEntryName));
        assertEquals("user1", text(p.szUserName));
        assertEquals("pass1", text(p.szPassword));
        assertEquals("", text(p.szPhoneNumber));
        assertEquals("", text(p.szDomain));
    }

    @Test
    void buildParamsTruncatesOversizedPassword() {
        char[] longPassword = new char[400];
        java.util.Arrays.fill(longPassword, 'x');
        NativeRasDial.RASDIALPARAMSW p =
            NativeRasDial.buildParams("e", "u".toCharArray(), longPassword);
        assertEquals(257, text(p.szPassword).length());
    }

    @Test
    void nullAndEmptyFieldsAreAccepted() {
        NativeRasDial.RASDIALPARAMSW p = NativeRasDial.buildParams(null, null, null);
        assertEquals("", text(p.szEntryName));
        assertEquals("", text(p.szPassword));
    }

    private static String text(char[] buf) {
        int end = 0;
        while (end < buf.length && buf[end] != '\0') end++;
        return new String(buf, 0, end);
    }
}
