package service;

import model.AppSettings;
import storage.SettingsStore;
import util.FilePermissions;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Settings load/save bridge between UI panels, {@link RuntimeSettings}, and {@link SettingsStore}.
 * Autostart registry truth stays in {@link StartupService}; the shell reconciles it asynchronously
 * after the INI preference has been applied to the UI.
 */
public final class SettingsCoordinator {
    public interface Ui {
        int intervalSeconds();

        void setIntervalSeconds(int seconds);

        boolean autoReconnect();

        void setAutoReconnect(boolean v);

        boolean autoStartChecked();

        void setAutoStartChecked(boolean v);

        boolean startMinimized();

        void setStartMinimized(boolean v);

        boolean disconnectOnNoInternetChecked();

        void setDisconnectOnNoInternetChecked(boolean v);

        boolean updateCheckEnabledChecked();

        void setUpdateCheckEnabledChecked(boolean v);

        void applyScheduleFrom(AppSettings s);

        void writeScheduleTo(AppSettings s);

        void syncScheduleCacheFromUi();

        void syncProbeFromUi();

        void applyProbeFromRuntime();

        void onLoadWarning(String message);

        void onSaveError(String message);
    }

    private final SettingsStore store;
    private final RuntimeSettings runtime;
    private final AccountSession accounts;
    private final Ui ui;

    public SettingsCoordinator(SettingsStore store, RuntimeSettings runtime, AccountSession accounts, Ui ui) {
        this.store = Objects.requireNonNull(store, "store");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    public void save() {
        try {
            AppSettings s = capture();
            store.save(s.toMap());
            FilePermissions.restrictToOwner(store.getFile());
        } catch (IOException e) {
            ui.onSaveError("保存设置失败");
        }
    }

    public AppSettings capture() {
        AppSettings s = new AppSettings();
        s.intervalSeconds = ui.intervalSeconds();
        s.autoReconnect = ui.autoReconnect();
        s.autoStart = ui.autoStartChecked();
        s.startMinimized = ui.startMinimized();
        s.accountIndex = accounts.currentIndex();
        ui.syncScheduleCacheFromUi();
        ui.writeScheduleTo(s);
        ui.syncProbeFromUi();
        runtime.setDisconnectOnNoInternet(ui.disconnectOnNoInternetChecked());
        runtime.setUpdateCheckEnabled(ui.updateCheckEnabledChecked());
        runtime.writeProbeTo(s);
        return s;
    }

    public void load() {
        try {
            Map<String, String> settings = store.load();
            if (settings.isEmpty()) {
                return;
            }
            AppSettings s = AppSettings.fromMap(settings);
            try {
                ui.setIntervalSeconds(Math.max(5, s.intervalSeconds));
            } catch (Exception e) {
                ui.onLoadWarning("配置项 interval 无效，已保留默认值");
            }
            ui.setAutoReconnect(s.autoReconnect);
            // Registry I/O runs asynchronously in the shell after UI settings have loaded.
            ui.setAutoStartChecked(s.autoStart);
            ui.setStartMinimized(s.startMinimized);
            accounts.setCurrentIndex(s.accountIndex);
            ui.applyScheduleFrom(s);
            runtime.applyFrom(s);
            ui.applyProbeFromRuntime();
            ui.setDisconnectOnNoInternetChecked(runtime.isDisconnectOnNoInternet());
            ui.setUpdateCheckEnabledChecked(runtime.isUpdateCheckEnabled());
            ui.syncScheduleCacheFromUi();
        } catch (IOException e) {
            ui.onLoadWarning("加载设置失败: " + e.getMessage());
        }
    }
}
