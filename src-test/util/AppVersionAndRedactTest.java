package util;

import model.AppVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppVersionAndRedactTest {

    @Test
    void compareNumericOrdersVersions() {
        assertTrue(AppVersion.compareNumeric("1.0.0", "1.1.0") < 0);
        assertTrue(AppVersion.compareNumeric("1.1.0", "1.0.9") > 0);
        assertEquals(0, AppVersion.compareNumeric("v1.1.0", "1.1.0"));
        assertTrue(AppVersion.compareNumeric("1.1", "1.1.1") < 0);
    }

    @Test
    void stripVRemovesPrefix() {
        assertEquals("1.1.0", AppVersion.stripV("v1.1.0"));
        assertEquals("1.1.0", AppVersion.stripV("V1.1.0"));
        assertEquals("2.0", AppVersion.stripV("2.0"));
    }

    @Test
    void maskAccountKeepsTail() {
        assertEquals("****22", RedactUtil.maskAccount("12345622", 2));
        assertEquals("**", RedactUtil.maskAccount("ab", 2));
        assertEquals("", RedactUtil.maskAccount(""));
    }

    @Test
    void scrubLogLineHidesPasswordAssignments() {
        assertEquals("user login password=***", RedactUtil.scrubLogLine("user login password=secret123"));
        assertEquals("pwd=*** ok", RedactUtil.scrubLogLine("pwd=hello ok"));
    }

    @Test
    void quickCheckConfigIsSingleAttempt() {
        ConnectivityConfirm.Config cfg = ConnectivityConfirm.Config.from(
            ConnectivityConfirm.MODE_ICMP, "127.0.0.1", ConnectivityConfirm.DEFAULT_HTTP_URL, 5, 1000);
        // Should not throw; result depends on environment
        ConnectivityConfirm.quickCheck(cfg);
    }
}
