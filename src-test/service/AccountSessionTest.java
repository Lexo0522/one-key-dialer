package service;

import model.AccountInfo;
import model.PasswordChars;
import org.junit.jupiter.api.Test;
import storage.AccountStore;

import java.nio.file.Files;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class AccountSessionTest {
    @Test
    void pullFromUiMarksDirtyAndPersists() throws Exception {
        var file = Files.createTempFile("acc", ".ini").toFile();
        file.deleteOnExit();
        AccountStore store = new AccountStore(file);
        AccountSession session = new AccountSession(store, null);
        session.accounts().add(new AccountInfo("n", "u", "old", ""));
        session.setCurrentIndex(0);

        char[] pw = "newpw".toCharArray();
        assertTrue(session.pullFromUi("n2", "u2", pw));
        assertTrue(session.isDirty());
        assertEquals("n2", session.currentOrNull().name);
        assertEquals("u2", session.currentOrNull().username);
        assertTrue(session.currentOrNull().passwordEquals("newpw".toCharArray()));
        // input array cleared by pullFromUi
        assertTrue(PasswordChars.isBlank(pw));
    }

    @Test
    void loadCreatesDefaultWhenEmptyFileMissing() throws Exception {
        var dir = Files.createTempDirectory("acc-session");
        var file = dir.resolve("missing.ini").toFile();
        AccountSession session = new AccountSession(new AccountStore(file), null);
        session.load();
        assertFalse(session.accounts().isEmpty());
        assertNotNull(session.currentOrNull());
    }
}

class RuntimeSettingsTest {
    @Test
    void probeConfigSnapshot() {
        RuntimeSettings rs = new RuntimeSettings();
        rs.setProbe("HTTP", "8.8.8.8", "http://example/204", 4, 200);
        var cfg = rs.toProbeConfig();
        assertEquals("http", util.ConnectivityConfirm.normalizeMode(cfg.mode));
        assertEquals("8.8.8.8", cfg.host);
        assertEquals(4, cfg.attempts);
        assertTrue(rs.probeReportLine().contains("8.8.8.8"));
    }
}
