package service;

import model.SettingsSnapshot;
import storage.SettingsStore;
import storage.StorageException;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Settings module: load, save, and runtime snapshot reads.
 * {@link #current()} is the single state carrier for background services;
 * {@link #update} swaps it on the EDT after a UI capture, {@link #loadFromDisk}
 * applies stored values (defaults on first start) and {@link #saveToDisk}
 * persists atomically. Errors are reported once through {@code onError} and fall
 * back safely — malformed JSON or an unknown schemaVersion is never repaired or
 * reinterpreted.
 */
public final class SettingsManager {
    private final SettingsStore store;
    private final Consumer<String> onError;
    private volatile SettingsSnapshot current = SettingsSnapshot.defaults();

    public SettingsManager(SettingsStore store, Consumer<String> onError) {
        this.store = Objects.requireNonNull(store, "store");
        this.onError = onError != null ? onError : msg -> { };
    }

    /** Runtime snapshot for services (thread-safe read). */
    public SettingsSnapshot current() {
        return current;
    }

    /** Swap the runtime snapshot (after UI capture or disk load). */
    public void update(SettingsSnapshot snapshot) {
        if (snapshot != null) {
            this.current = snapshot.normalized();
        }
    }

    /**
     * Load persisted settings. First start (missing file) keeps defaults.
     * @return the snapshot now active
     */
    public SettingsSnapshot loadFromDisk() {
        SettingsSnapshot loaded;
        try {
            loaded = store.load();
        } catch (StorageException e) {
            report("加载设置失败（使用默认设置）: " + e.getMessage());
            current = SettingsSnapshot.defaults();
            return current;
        } catch (IOException e) {
            report("加载设置失败（使用默认设置）: " + e.getMessage());
            current = SettingsSnapshot.defaults();
            return current;
        }
        if (loaded == null) {
            current = SettingsSnapshot.defaults();
        } else {
            current = loaded;
        }
        return current;
    }

    /** Persist a snapshot atomically. @return true on success; errors are reported. */
    public boolean saveToDisk(SettingsSnapshot snapshot) {
        if (snapshot == null) return false;
        try {
            store.save(snapshot.normalized());
            return true;
        } catch (IOException e) {
            report("保存设置失败: " + e.getMessage());
            return false;
        }
    }

    public String settingsFilePath() {
        return store.getFile().getAbsolutePath();
    }

    private void report(String message) {
        try {
            onError.accept(message);
        } catch (Exception ignored) {
        }
    }
}
