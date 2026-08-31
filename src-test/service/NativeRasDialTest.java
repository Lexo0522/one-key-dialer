package service;

import model.DialCredentials;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    @Test
    void structCarriesRoomForThe24H2Tail() {
        NativeRasDial.RASDIALPARAMSW p = NativeRasDial.buildParams("e", null, null);
        // 64-bit SDK layout is 1952 bytes; the 24H2 variant accepted by the OS is
        // exactly 144 bytes larger. The buffer must fit both.
        int classic = NativeRasDial.candidateSizes(p.size(), 8)[1];
        assertEquals(1952, classic);
        assertEquals(2096, p.size());
    }

    @Test
    void sizeLadderCoversNewToOldLayouts() {
        assertArrayEquals(new int[] {2096, 1952, 1936},
            NativeRasDial.candidateSizes(2096, 8));
        assertArrayEquals(new int[] {2088, 1944, 1936},
            NativeRasDial.candidateSizes(2088, 4));
    }

    private static String text(char[] buf) {
        int end = 0;
        while (end < buf.length && buf[end] != '\0') end++;
        return new String(buf, 0, end);
    }
}
