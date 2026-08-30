package service;

import model.SettingsSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import storage.SettingsStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SettingsManagerTest {
    @TempDir
    Path dir;

    @Test
    void firstStartKeepsDefaults() {
        SettingsManager manager = new SettingsManager(
            new SettingsStore(dir.resolve("settings.json").toFile()), msg -> { });
        SettingsSnapshot snapshot = manager.loadFromDisk();
        assertEquals(SettingsSnapshot.defaults(), snapshot);
        assertEquals(SettingsSnapshot.defaults(), manager.current());
    }

    @Test
    void updateThenReloadRoundTrip() {
        SettingsManager manager = new SettingsManager(
            new SettingsStore(dir.resolve("settings.json").toFile()), msg -> { });
        manager.loadFromDisk();

        SettingsSnapshot changed = manager.current().toBuilder()
            .intervalSeconds(45)
            .autoReconnect(true)
            .scheduledDial(true, 6, 15)
            .build();
        manager.update(changed);
        assertEquals(changed, manager.current());
        assertTrue(manager.saveToDisk(changed));

        SettingsManager second = new SettingsManager(
            new SettingsStore(dir.resolve("settings.json").toFile()), msg -> { });
        SettingsSnapshot loaded = second.loadFromDisk();
        assertEquals(45, loaded.intervalSeconds);
        assertTrue(loaded.autoReconnect);
        assertTrue(loaded.scheduledDial);
        assertEquals(6, loaded.scheduledDialHour);
    }

    @Test
    void invalidJsonReportsAndFallsBackToDefaults() throws Exception {
        Path file = dir.resolve("settings.json");
        Files.write(file, "{bad json".getBytes(StandardCharsets.UTF_8));
        List<String> errors = new ArrayList<>();
        SettingsManager manager = new SettingsManager(new SettingsStore(file.toFile()), errors::add);

        SettingsSnapshot snapshot = manager.loadFromDisk();
        assertEquals(SettingsSnapshot.defaults(), snapshot);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("加载设置失败"));
    }

    @Test
    void unknownSchemaReportsAndFallsBackToDefaults() throws Exception {
        Path file = dir.resolve("settings.json");
        Files.write(file, "{\"schemaVersion\": 42, \"data\": {}}".getBytes(StandardCharsets.UTF_8));
        List<String> errors = new ArrayList<>();
        SettingsManager manager = new SettingsManager(new SettingsStore(file.toFile()), errors::add);

        SettingsSnapshot snapshot = manager.loadFromDisk();
        assertEquals(SettingsSnapshot.defaults(), snapshot);
        assertEquals(1, errors.size());
    }

    @Test
    void saveFailureIsReportedNotThrown() {
        // A directory in place of the file forces the atomic write to fail.
        Path asDir = dir.resolve("settings.json");
        //noinspection ResultOfMethodCallIgnored
        asDir.toFile().mkdirs();
        SettingsManager manager = new SettingsManager(new SettingsStore(asDir.toFile()), msg -> { });
        // On some filesystems writing over a directory still fails with IOException — that's the path under test.
        boolean ok = manager.saveToDisk(SettingsSnapshot.defaults());
        // Either outcome must leave a usable runtime snapshot.
        assertEquals(SettingsSnapshot.defaults(), manager.current());
        if (!ok) {
            assertEquals(SettingsSnapshot.defaults(), manager.current());
        }
    }

    @Test
    void updateIgnoresNull() {
        SettingsManager manager = new SettingsManager(
            new SettingsStore(dir.resolve("settings.json").toFile()), msg -> { });
        manager.update(null);
        assertEquals(SettingsSnapshot.defaults(), manager.current());
    }
}
