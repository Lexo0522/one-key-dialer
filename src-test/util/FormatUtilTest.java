package util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FormatUtilTest {
    @Test
    void formatBytesAndSpeed() {
        assertTrue(FormatUtil.formatBytes(512).endsWith(" B"));
        assertTrue(FormatUtil.formatBytes(2048).contains("KB"));
        assertTrue(FormatUtil.formatSpeed(2048).contains("KB/s"));
        assertEquals("00:01:05", FormatUtil.formatDuration(65));
    }
}
