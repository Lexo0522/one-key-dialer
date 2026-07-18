package ui;

import model.AppSettings;
import service.DialOrchestrator;
import service.RuntimeSettings;
import service.SettingsCoordinator;

/**
 * Implements {@link SettingsCoordinator.Ui} so {@code PPoEDialer} stays a thin shell.
 * Callers supply live UI getters (panels may be lazily created).
 */
public final class SettingsUiBridge implements SettingsCoordinator.Ui {
    public interface Host {
        MainHomePanel homePanel();

        SchedulePanel schedulePanel();

        ProbeSettingsPanel probePanel();

        RuntimeSettings runtimeSettings();

        DialOrchestrator dialOrchestrator();

        boolean isAutoStartEnabledInRegistry();

        boolean ensureAutoStartHealthy();

        void syncScheduleCacheFromUi();

        void syncProbeFromUi();

        void logWarning(String message);

        void logError(String message);
    }

    private final Host host;

    public SettingsUiBridge(Host host) {
        this.host = host;
    }

    @Override
    public int intervalSeconds() {
        MainHomePanel h = host.homePanel();
        return h != null ? (int) h.getSpnInterval().getValue() : 30;
    }

    @Override
    public void setIntervalSeconds(int seconds) {
        MainHomePanel h = host.homePanel();
        if (h != null) h.getSpnInterval().setValue(seconds);
    }

    @Override
    public boolean autoReconnect() {
        MainHomePanel h = host.homePanel();
        return h != null && h.getChkAutoReconnect().isSelected();
    }

    @Override
    public void setAutoReconnect(boolean v) {
        MainHomePanel h = host.homePanel();
        if (h != null) h.getChkAutoReconnect().setSelected(v);
    }

    @Override
    public boolean autoStartChecked() {
        MainHomePanel h = host.homePanel();
        return h != null && h.getChkAutoStart().isSelected();
    }

    @Override
    public void setAutoStartChecked(boolean v) {
        MainHomePanel h = host.homePanel();
        if (h != null) h.getChkAutoStart().setSelected(v);
    }

    @Override
    public boolean startMinimized() {
        MainHomePanel h = host.homePanel();
        return h != null && h.getChkStartMinimized().isSelected();
    }

    @Override
    public void setStartMinimized(boolean v) {
        MainHomePanel h = host.homePanel();
        if (h != null) h.getChkStartMinimized().setSelected(v);
    }

    @Override
    public boolean disconnectOnNoInternetChecked() {
        MainHomePanel h = host.homePanel();
        return h != null && h.getChkDisconnectOnNoInternet().isSelected();
    }

    @Override
    public void setDisconnectOnNoInternetChecked(boolean v) {
        MainHomePanel h = host.homePanel();
        if (h != null) h.getChkDisconnectOnNoInternet().setSelected(v);
        RuntimeSettings rs = host.runtimeSettings();
        if (rs != null) rs.setDisconnectOnNoInternet(v);
        DialOrchestrator orch = host.dialOrchestrator();
        if (orch != null) orch.setDisconnectOnNoInternet(v);
    }

    @Override
    public boolean updateCheckEnabledChecked() {
        MainHomePanel h = host.homePanel();
        return h != null && h.getChkUpdateCheck().isSelected();
    }

    @Override
    public void setUpdateCheckEnabledChecked(boolean v) {
        MainHomePanel h = host.homePanel();
        if (h != null) h.getChkUpdateCheck().setSelected(v);
        RuntimeSettings rs = host.runtimeSettings();
        if (rs != null) rs.setUpdateCheckEnabled(v);
    }

    @Override
    public void applyScheduleFrom(AppSettings s) {
        SchedulePanel p = host.schedulePanel();
        if (p != null) p.applyFrom(s);
    }

    @Override
    public void writeScheduleTo(AppSettings s) {
        SchedulePanel p = host.schedulePanel();
        if (p != null) p.writeTo(s);
        else if (host.runtimeSettings() != null) host.runtimeSettings().writeScheduleTo(s);
    }

    @Override
    public void syncScheduleCacheFromUi() {
        host.syncScheduleCacheFromUi();
    }

    @Override
    public void syncProbeFromUi() {
        host.syncProbeFromUi();
    }

    @Override
    public void applyProbeFromRuntime() {
        RuntimeSettings rs = host.runtimeSettings();
        if (rs == null) return;
        ProbeSettingsPanel probe = host.probePanel();
        if (probe != null) {
            probe.applyFrom(
                rs.getProbeMode(),
                rs.getProbeHost(),
                rs.getProbeHttpUrl(),
                rs.getProbeAttempts(),
                rs.getProbeDelayMs()
            );
        }
        MainHomePanel h = host.homePanel();
        if (h != null) {
            h.getChkDisconnectOnNoInternet().setSelected(rs.isDisconnectOnNoInternet());
            h.getChkUpdateCheck().setSelected(rs.isUpdateCheckEnabled());
        }
        DialOrchestrator orch = host.dialOrchestrator();
        if (orch != null) {
            orch.setProbeConfigSupplier(rs::toProbeConfig);
            orch.setDisconnectOnNoInternet(rs.isDisconnectOnNoInternet());
        }
    }

    @Override
    public boolean isAutoStartEnabledInRegistry() {
        return host.isAutoStartEnabledInRegistry();
    }

    @Override
    public boolean ensureAutoStartHealthy() {
        return host.ensureAutoStartHealthy();
    }

    @Override
    public void onLoadWarning(String message) {
        host.logWarning(message);
    }

    @Override
    public void onSaveError(String message) {
        host.logError(message);
    }

    @Override
    public void onAutostartLegacyWarning(String message) {
        host.logWarning(message);
    }
}
