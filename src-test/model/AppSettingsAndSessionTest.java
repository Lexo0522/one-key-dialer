package model;

import org.junit.jupiter.api.Test;
import util.ConnectivityConfirm;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppSettingsTest {
    @Test
    void roundTripMapPreservesProbeKeys() {
        AppSettings s = new AppSettings();
        s.intervalSeconds = 45;
        s.probeMode = ConnectivityConfirm.MODE_HTTP;
        s.probeHost = "1.1.1.1";
        s.probeHttpUrl = "http://example.com/204";
        s.probeAttempts = 2;
        s.probeDelayMs = 500;
        s.disconnectOnNoInternet = true;
        Map<String, String> map = s.toMap();
        AppSettings loaded = AppSettings.fromMap(map);
        assertEquals(45, loaded.intervalSeconds);
        assertEquals(ConnectivityConfirm.MODE_HTTP, loaded.probeMode);
        assertEquals("1.1.1.1", loaded.probeHost);
        assertEquals("http://example.com/204", loaded.probeHttpUrl);
        assertEquals(2, loaded.probeAttempts);
        assertEquals(500, loaded.probeDelayMs);
        assertTrue(loaded.disconnectOnNoInternet);
        ConnectivityConfirm.Config cfg = loaded.toProbeConfig();
        assertEquals(ConnectivityConfirm.MODE_HTTP, ConnectivityConfirm.normalizeMode(cfg.mode));
    }
}

class AccountInfoPasswordTest {
    @Test
    void passwordCharArrayClearAndEquals() {
        AccountInfo a = new AccountInfo("n", "u", "secret", "");
        assertEquals("secret", a.getPassword());
        assertTrue(a.passwordEquals("secret"));
        a.clearPassword();
        assertTrue(a.isPasswordEmpty());
        a.setPassword("x");
        char[] copy = a.copyPasswordChars();
        assertArrayEquals(new char[]{'x'}, copy);
        java.util.Arrays.fill(copy, '\0');
        assertEquals("x", a.getPassword());
    }
}

class SessionTrafficTest {
    @Test
    void sampleAndSessionBytes() {
        SessionTraffic t = new SessionTraffic();
        t.applySample(100, 40);
        t.markSessionStart();
        // session baseline: down=100, up=40; then +50 down / +10 up in-session
        t.applySample(50, 10);
        assertEquals(150, t.totalDownload().get());
        assertEquals(50, t.totalUpload().get());
        // sessionTrafficBytes = (150-100) + (50-40) = 60
        assertEquals(60, t.sessionTrafficBytes());
        t.markOffline();
        assertEquals(0, t.connectTimeMillis().get());
    }
}
