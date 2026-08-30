package storage;

import model.SettingsSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SettingsStoreJsonTest {
    @TempDir
    Path dir;

    private File file() {
        return dir.resolve("settings.json").toFile();
    }

    @Test
    void firstStartReturnsNull() throws Exception {
        SettingsStore store = new SettingsStore(file());
        assertNull(store.load());
    }

    @Test
    void saveAndReloadRoundTrip() throws Exception {
        SettingsStore store = new SettingsStore(file());
        SettingsSnapshot snapshot = SettingsSnapshot.defaults().toBuilder()
            .intervalSeconds(120)
            .autoReconnect(true)
            .startMinimized(true)
            .accountIndex(2)
            .scheduledDial(true, 7, 30)
            .scheduledDisconnect(true, 22, 5)
            .probe("http", "1.2.3.4", "http://example.test/204", 5, 250)
            .disconnectOnNoInternet(true)
            .updateCheckEnabled(false)
            .build();

        store.save(snapshot);
        SettingsSnapshot loaded = store.load();

        assertNotNull(loaded);
        assertEquals(120, loaded.intervalSeconds);
        assertTrue(loaded.autoReconnect);
        assertTrue(loaded.startMinimized);
        assertEquals(2, loaded.accountIndex);
        assertTrue(loaded.scheduledDial);
        assertEquals(7, loaded.scheduledDialHour);
        assertEquals(30, loaded.scheduledDialMinute);
        assertTrue(loaded.scheduledDisconnect);
        assertEquals(22, loaded.scheduledDisconnectHour);
        assertEquals(5, loaded.scheduledDisconnectMinute);
        assertEquals("http", loaded.probeMode);
        assertEquals("1.2.3.4", loaded.probeHost);
        assertEquals("http://example.test/204", loaded.probeHttpUrl);
        assertEquals(5, loaded.probeAttempts);
        assertEquals(250, loaded.probeDelayMs);
        assertTrue(loaded.disconnectOnNoInternet);
        assertFalse(loaded.updateCheckEnabled);
    }

    @Test
    void documentCarriesSchemaVersion() throws Exception {
        SettingsStore store = new SettingsStore(file());
        store.save(SettingsSnapshot.defaults());
        String json = new String(Files.readAllBytes(file().toPath()), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schemaVersion\": 1"), json);
    }

    @Test
    void invalidJsonIsRejectedNotRepaired() throws Exception {
        Files.write(file().toPath(), "{not valid json".getBytes(StandardCharsets.UTF_8));
        SettingsStore store = new SettingsStore(file());
        StorageException e = assertThrows(StorageException.class, store::load);
        assertEquals(StorageException.Kind.INVALID_JSON, e.kind());
    }

    @Test
    void unknownSchemaVersionIsRejected() throws Exception {
        Files.write(file().toPath(),
            "{\"schemaVersion\": 99, \"data\": {}}".getBytes(StandardCharsets.UTF_8));
        SettingsStore store = new SettingsStore(file());
        StorageException e = assertThrows(StorageException.class, store::load);
        assertEquals(StorageException.Kind.UNKNOWN_SCHEMA, e.kind());
    }

    @Test
    void missingSchemaVersionIsRejected() throws Exception {
        Files.write(file().toPath(),
            "{\"data\": {\"intervalSeconds\": 60}}".getBytes(StandardCharsets.UTF_8));
        SettingsStore store = new SettingsStore(file());
        StorageException e = assertThrows(StorageException.class, store::load);
        assertEquals(StorageException.Kind.UNKNOWN_SCHEMA, e.kind());
    }

    @Test
    void outOfRangeValuesAreNormalizedOnLoad() throws Exception {
        SettingsStore store = new SettingsStore(file());
        store.save(new SettingsSnapshot.Builder().intervalSeconds(1).accountIndex(-5).build());
        SettingsSnapshot loaded = store.load();
        assertEquals(SettingsSnapshot.MIN_INTERVAL_SECONDS, loaded.intervalSeconds);
        assertEquals(0, loaded.accountIndex);
    }

    @Test
    void legacyIniFileIsIgnored() throws Exception {
        // Old INI/CSV data stays where it is; the JSON store must not read or transform it.
        Files.write(dir.resolve("pppoe_settings.ini"),
            "interval=999\nauto.reconnect=true\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("pppoe_settings.ini.bak"),
            "interval=777\n".getBytes(StandardCharsets.UTF_8));
        SettingsStore store = new SettingsStore(file());
        assertNull(store.load());
    }

    @Test
    void saveIsAtomicNoTempLeftBehind() throws Exception {
        SettingsStore store = new SettingsStore(file());
        store.save(SettingsSnapshot.defaults());
        store.save(SettingsSnapshot.defaults().toBuilder().intervalSeconds(99).build());
        assertEquals(99, store.load().intervalSeconds);
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        } catch (IOException ignored) {
            fail("unexpected");
        }
    }
}
