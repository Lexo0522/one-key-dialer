package service;

import model.DialCredentials;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Struct building only — never calls RasDialW (that would attempt a real dial).
 * The offsets are the contract with the OS and are pinned against native memory:
 * pre-24H2 builds use the documented x64 SDK layout, while 24H2 (build 26100+)
 * inserts 146 opaque bytes before the credentials and drops dwIfIndex/guidId.
 * Writing SDK offsets with dwSize=2096 makes the OS read a truncated username
 * suffixed with password bytes and every dial fails with 691 — that bug class
 * is pinned out here.
 */
class NativeRasDialTest {

    @Test
    void sdkLayoutOffsetsMatchRasDotH() {
        NativeRasDial.ParamsSdk p = new NativeRasDial.ParamsSdk();
        fillAndWrite(p);
        com.sun.jna.Pointer mem = p.getPointer();
        assertEquals(NativeRasDial.SDK_STRUCT_SIZE, p.size());
        assertEquals("entry", cstr(mem.getCharArray(4, 257)));    // szEntryName @4
        assertEquals("user1", cstr(mem.getCharArray(888, 257)));  // szUserName @888
        assertEquals("pass1", cstr(mem.getCharArray(1402, 257))); // szPassword @1402
        assertEquals("", cstr(mem.getCharArray(1916, 16)));       // szDomain @1916
    }

    @Test
    void layout24H2OffsetsMatchTheOsLayout() {
        NativeRasDial.Params24H2 p = new NativeRasDial.Params24H2();
        fillAndWrite(p);
        com.sun.jna.Pointer mem = p.getPointer();
        assertEquals(NativeRasDial.STRUCT_SIZE_24H2, p.size());
        assertEquals("entry", cstr(mem.getCharArray(4, 257)));     // szEntryName @4
        assertEquals("user1", cstr(mem.getCharArray(1034, 257)));  // szUserName @1034
        assertEquals("pass1", cstr(mem.getCharArray(1548, 257)));  // szPassword @1548
        assertEquals("", cstr(mem.getCharArray(2062, 16)));        // szDomain @2062
    }

    @Test
    void buildParamsFillsEntryUserPasswordAndSizeForThisOs() {
        DialCredentials creds = new DialCredentials("user1", "pass1".toCharArray());
        NativeRasDial.DialParams p = NativeRasDial.newParams(
            "pppoe_native_java", creds.username().toCharArray(), creds.copyPassword());
        p.self().write(); // sync to native memory, as the RasDialW call would
        assertEquals(p.self().size(), (int) (Integer) p.self().readField("dwSize"));
        assertEquals("pppoe_native_java", text(field(p, "szEntryName")));
        assertEquals("user1", text(field(p, "szUserName")));
        assertEquals("pass1", text(field(p, "szPassword")));
        assertEquals("", text(field(p, "szPhoneNumber")));
        assertEquals("", text(field(p, "szDomain")));
        int osSize = p.self().size();
        assertTrue(osSize == NativeRasDial.SDK_STRUCT_SIZE
            || osSize == NativeRasDial.STRUCT_SIZE_24H2);
    }

    @Test
    void buildParamsTruncatesOversizedPassword() {
        char[] longPassword = new char[400];
        java.util.Arrays.fill(longPassword, 'x');
        NativeRasDial.ParamsSdk p = new NativeRasDial.ParamsSdk();
        p.credentials("u".toCharArray(), longPassword);
        assertEquals(257, text(p.szPassword).length());
    }

    @Test
    void structGenerationFollowsOsBuild() {
        assertFalse(NativeRasDial.uses24H2Layout(19045)); // Windows 10 22H2
        assertFalse(NativeRasDial.uses24H2Layout(22631)); // Windows 11 23H2
        assertTrue(NativeRasDial.uses24H2Layout(26100));  // Windows 11 24H2
        assertTrue(NativeRasDial.uses24H2Layout(26200));  // future builds inherit
    }

    private static void fillAndWrite(NativeRasDial.DialParams p) {
        p.entry("entry");
        p.credentials("user1".toCharArray(), "pass1".toCharArray());
        p.initSize();
        p.self().write();
    }

    private static char[] field(NativeRasDial.DialParams p, String name) {
        return (char[]) p.self().readField(name);
    }

    private static String text(char[] buf) {
        int end = 0;
        while (end < buf.length && buf[end] != '\0') end++;
        return new String(buf, 0, end);
    }

    private static String cstr(char[] buf) {
        return text(buf);
    }
}
