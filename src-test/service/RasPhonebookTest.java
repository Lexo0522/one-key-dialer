package service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RasPhonebookTest {

    @Test
    void removeSectionDropsTargetKeepsOthers() {
        String in = "[keep]\nA=1\n\n[pppoe_native_java]\nB=2\n\n[other]\nC=3\n";
        String out = RasPhonebook.removeSection(in, "pppoe_native_java");
        assertTrue(out.contains("[keep]"));
        assertTrue(out.contains("[other]"));
        assertFalse(out.contains("[pppoe_native_java]"));
        assertFalse(out.contains("B=2"));
    }

    @Test
    void contentContainsSection() {
        assertTrue(RasPhonebook.contentContainsSection("[x]\n", "x"));
        assertFalse(RasPhonebook.contentContainsSection("[xy]\n", "x"));
    }

    @Test
    void findPppoeDeviceHintPrefersPreferredPort() {
        String content = "PreferredPort=PPPoE3-0\nPreferredDevice=WAN Miniport (PPPOE)\n";
        RasPhonebook.DeviceHint h = RasPhonebook.findPppoeDeviceHint(content);
        assertNotNull(h);
        assertEquals("PPPoE3-0", h.port);
        assertTrue(h.device.toUpperCase().contains("PPPOE"));
        assertTrue(h.fromExisting);
    }

    @Test
    void buildEntryContainsNamePortDevice() {
        String e = RasPhonebook.buildPhoneBookEntry("pppoe_native_java", "PPPoE5-0", "WAN Miniport (PPPOE)");
        assertTrue(e.startsWith("[pppoe_native_java]"));
        assertTrue(e.contains("PreferredPort=PPPoE5-0"));
        assertTrue(e.contains("Device=WAN Miniport (PPPOE)"));
        assertTrue(e.contains("MEDIA=rastapi"));
    }

    @Test
    void detectCharsetUtf8Bom() throws Exception {
        java.io.File tmp = java.nio.file.Files.createTempFile("pbk", ".pbk").toFile();
        tmp.deleteOnExit();
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a'};
        java.nio.file.Files.write(tmp.toPath(), bom);
        assertEquals(StandardCharsets.UTF_8, RasPhonebook.detectPbkCharset(tmp));
    }
}
