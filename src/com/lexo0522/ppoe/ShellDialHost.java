package com.lexo0522.ppoe;

import model.AppFiles;
import model.DialLifecycle;
import model.DialSnapshot;
import model.SessionTraffic;
import service.AbstractDialHost;
import service.AccountSession;
import service.DialService;
import ui.UiTheme;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * {@link service.DialOrchestrator.Host} adapter for the Swing shell.
 */
public final class ShellDialHost extends AbstractDialHost {
    private final ShellBridge bridge;
    private final DialLifecycle dialLifecycle;
    private final DialService dialService;
    private final SessionTraffic sessionTraffic;
    private final AccountSession accountSession;
    private final AtomicBoolean isOnline;
    private final Supplier<String> activeConnName;

    public ShellDialHost(ShellBridge bridge,
                         DialLifecycle dialLifecycle,
                         DialService dialService,
                         SessionTraffic sessionTraffic,
                         AccountSession accountSession,
                         AtomicBoolean isOnline,
                         Supplier<String> activeConnName) {
        this.bridge = bridge;
        this.dialLifecycle = dialLifecycle;
        this.dialService = dialService;
        this.sessionTraffic = sessionTraffic;
        this.accountSession = accountSession;
        this.isOnline = isOnline;
        this.activeConnName = activeConnName;
    }

    @Override public DialLifecycle lifecycle() { return dialLifecycle; }
    @Override public DialService dialService() { return dialService; }
    @Override public String connectionName() { return AppFiles.RAS_CONNECTION; }
    @Override public String activeConnectionName() { return activeConnName.get(); }
    @Override public BooleanSupplier isOnline() { return isOnline::get; }
    @Override public LongSupplier connectTimeMillis() {
        return sessionTraffic.connectTimeMillis()::get;
    }
    @Override public LongSupplier sessionTrafficBytes() {
        return sessionTraffic::sessionTrafficBytes;
    }
    @Override public Supplier<String> currentAccountName() {
        return accountSession::currentName;
    }
    @Override public AtomicLong totalDialCount() { return sessionTraffic.totalDialCount(); }
    @Override public AtomicLong successDialCount() { return sessionTraffic.successDialCount(); }
    @Override public boolean validateBeforeDialInteractive() {
        return bridge.validateBeforeDial(true);
    }
    @Override public boolean validateBeforeDialQuiet() {
        return bridge.validateBeforeDial(false);
    }
    @Override public DialSnapshot captureSnapshotFromUi() {
        return bridge.captureDialSnapshotOnEdt();
    }
    @Override public void saveCurrentAccount() { bridge.saveCurrentAccount(); }
    @Override public void updateStatus(boolean online) { bridge.updateStatus(online); }
    @Override public void setDialControlsEnabled(boolean enabled) {
        bridge.updateButtonState(enabled);
    }
    @Override public void setDialProgress(String phase) { bridge.updateDialProgress(phase); }
    @Override public void logInfo(String message) { bridge.log(message, UiTheme.COLOR_INFO); }
    @Override public void logSuccess(String message) { bridge.log(message, UiTheme.COLOR_SUCCESS); }
    @Override public void logWarning(String message) { bridge.log(message, UiTheme.COLOR_WARNING); }
    @Override public void logError(String message) { bridge.log(message, UiTheme.COLOR_ERROR); }
    @Override public void notifyUser(String title, String message) {
        bridge.showNotification(title, message);
    }
    @Override public void addHistory(String operation, String account, String result,
                                     String duration, String traffic) {
        bridge.addHistoryRecord(operation, account, result, duration, traffic);
    }
    @Override public void saveSettingsAfterSuccess() {
        bridge.invokeIfUiActive(bridge::saveSettings);
    }
    @Override public boolean isEventDispatchThread() {
        return SwingUtilities.isEventDispatchThread();
    }
    @Override public void runOnEdtAndWait(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }
}
