package storage;

import model.SettingsSnapshot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * JSON settings document. Missing file = first start (caller applies defaults);
 * malformed JSON / unknown schemaVersion throw {@link StorageException}.
 */
public final class SettingsStore {
    public static final int SCHEMA_VERSION = 1;

    private final Path file;

    public SettingsStore(File file) {
        this.file = file.toPath();
    }

    /** @return stored snapshot, or null when the file does not exist yet. */
    public SettingsSnapshot load() throws StorageException {
        Document doc = JsonFiles.read(file, SCHEMA_VERSION, Document.class);
        if (doc == null) {
            return null;
        }
        if (doc.data == null) {
            return SettingsSnapshot.defaults();
        }
        return doc.data.toBuilder().build();
    }

    public void save(SettingsSnapshot snapshot) throws IOException {
        JsonFiles.write(file, SCHEMA_VERSION, new Document(new SnapshotData(snapshot)));
    }

    public File getFile() {
        return file.toFile();
    }

    /** Mutable mirror of the snapshot for Gson (Gson must not mutate final fields). */
    private static final class SnapshotData {
        private int intervalSeconds;
        private boolean autoReconnect;
        private boolean autoStart;
        private boolean startMinimized;
        private int accountIndex;
        private boolean scheduledDial;
        private int scheduledDialHour;
        private int scheduledDialMinute;
        private boolean scheduledDisconnect;
        private int scheduledDisconnectHour;
        private int scheduledDisconnectMinute;
        private String probeMode;
        private String probeHost;
        private String probeHttpUrl;
        private int probeAttempts;
        private int probeDelayMs;
        private boolean disconnectOnNoInternet;
        private boolean updateCheckEnabled;

        SnapshotData() {
        }

        SnapshotData(SettingsSnapshot s) {
            intervalSeconds = s.intervalSeconds;
            autoReconnect = s.autoReconnect;
            autoStart = s.autoStart;
            startMinimized = s.startMinimized;
            accountIndex = s.accountIndex;
            scheduledDial = s.scheduledDial;
            scheduledDialHour = s.scheduledDialHour;
            scheduledDialMinute = s.scheduledDialMinute;
            scheduledDisconnect = s.scheduledDisconnect;
            scheduledDisconnectHour = s.scheduledDisconnectHour;
            scheduledDisconnectMinute = s.scheduledDisconnectMinute;
            probeMode = s.probeMode;
            probeHost = s.probeHost;
            probeHttpUrl = s.probeHttpUrl;
            probeAttempts = s.probeAttempts;
            probeDelayMs = s.probeDelayMs;
            disconnectOnNoInternet = s.disconnectOnNoInternet;
            updateCheckEnabled = s.updateCheckEnabled;
        }

        SettingsSnapshot.Builder toBuilder() {
            return new SettingsSnapshot.Builder()
                .intervalSeconds(intervalSeconds)
                .autoReconnect(autoReconnect)
                .autoStart(autoStart)
                .startMinimized(startMinimized)
                .accountIndex(accountIndex)
                .scheduledDial(scheduledDial, scheduledDialHour, scheduledDialMinute)
                .scheduledDisconnect(scheduledDisconnect, scheduledDisconnectHour, scheduledDisconnectMinute)
                .probe(probeMode, probeHost, probeHttpUrl, probeAttempts, probeDelayMs)
                .disconnectOnNoInternet(disconnectOnNoInternet)
                .updateCheckEnabled(updateCheckEnabled);
        }
    }

    /** Serialized to {@code {"schemaVersion":1,"data":{...snapshot fields...}}}. */
    private static final class Document {
        // non-final: populated reflectively by Gson on load
        private SnapshotData data;

        Document() {
        }

        Document(SnapshotData data) {
            this.data = data;
        }
    }
}
