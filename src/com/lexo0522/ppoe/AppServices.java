package com.lexo0522.ppoe;

import model.AppFiles;
import model.DialLifecycle;
import model.SessionTraffic;
import service.AccountSession;
import service.AutoReconnectService;
import service.BackgroundExecutor;
import service.DialOrchestrator;
import service.DialService;
import service.HistoryService;
import service.LogService;
import service.NetworkMonitorService;
import service.RuntimeSettings;
import service.ScheduleService;
import service.StartupService;
import storage.AccountStore;
import storage.HistoryStore;
import storage.SettingsStore;
import ui.MainHomePanel;
import ui.TrayController;
import ui.UiTheme;
import util.AppPaths;
import util.ConnectivityConfirm;
import util.CryptoUtil;
import util.FormatUtil;
import util.TrafficSampler;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Composition root for non-UI services. Constructed once with a live {@link ShellBridge}.
 */
public final class AppServices {
    public final AccountStore accountStore;
    public final HistoryStore historyStore;
    public final SettingsStore settingsStore;
    public final LogService logService;
    public final HistoryService historyService;
    public final AccountSession accountSession;
    public final BackgroundExecutor backgroundExecutor;
    public final StartupService startupService;
    public final DialService dialService;
    public final DialOrchestrator dialOrchestrator;
    public final AutoReconnectService autoReconnectService;
    public final NetworkMonitorService networkMonitorService;
    public final ScheduleService scheduleService;
    public final RuntimeSettings runtimeSettings = new RuntimeSettings();
    public final DialLifecycle dialLifecycle = new DialLifecycle();
    public final SessionTraffic sessionTraffic = new SessionTraffic();
    public final AtomicBoolean isOnline = new AtomicBoolean(false);
    public final AtomicReference<String> activeConnName =
        new AtomicReference<>(AppFiles.RAS_CONNECTION);

    private final TrafficSampler trafficSampler;
    private volatile boolean tooltipDirty;

    public AppServices(ShellBridge bridge, Supplier<MainHomePanel> homePanel,
                       Supplier<TrayController> tray) {
        try {
            CryptoUtil.init(AppPaths.masterKeyFile(PPoEDialer.class));
        } catch (Exception e) {
            System.err.println("Crypto init failed: " + e.getMessage());
        }

        File dataDir = AppPaths.getDataDir(PPoEDialer.class);
        accountStore = new AccountStore(new File(dataDir, AppFiles.ACCOUNTS));
        historyStore = new HistoryStore(new File(dataDir, AppFiles.HISTORY));
        settingsStore = new SettingsStore(new File(dataDir, AppFiles.SETTINGS),
            AppFiles.SETTINGS_BACKUP_SUFFIX);
        logService = new LogService(new File(dataDir, AppFiles.LOG));
        historyService = new HistoryService(historyStore,
            msg -> bridge.log(msg, UiTheme.COLOR_WARNING));
        accountSession = new AccountSession(accountStore, new AccountSession.Logger() {
            @Override public void info(String message) {
                bridge.log(message, UiTheme.COLOR_INFO);
            }
            @Override public void error(String message) {
                bridge.log(message, UiTheme.COLOR_ERROR);
            }
        });

        backgroundExecutor = new BackgroundExecutor();
        trafficSampler = new TrafficSampler(
            msg -> bridge.log(msg, UiTheme.COLOR_WARNING));

        startupService = new StartupService(
            "PPoEDialer",
            () -> bridge.invokeIfUiActive(() -> {
                MainHomePanel h = homePanel.get();
                if (h != null) h.getChkAutoStart().setSelected(true);
            }),
            () -> bridge.invokeIfUiActive(() -> {
                MainHomePanel h = homePanel.get();
                if (h != null) h.getChkAutoStart().setSelected(false);
            }),
            (message, success) -> bridge.log(message,
                success ? UiTheme.COLOR_SUCCESS : UiTheme.COLOR_ERROR)
        );

        dialService = new DialService(
            AppFiles.RAS_CONNECTION,
            activeConnName::get,
            activeConnName::set,
            () -> { },
            message -> bridge.log(message, UiTheme.COLOR_INFO),
            message -> bridge.log(message, UiTheme.COLOR_WARNING),
            message -> bridge.log(message, UiTheme.COLOR_ERROR)
        );

        dialOrchestrator = new DialOrchestrator(new ShellDialHost(
            bridge, dialLifecycle, dialService, sessionTraffic, accountSession,
            isOnline, activeConnName::get));
        dialOrchestrator.setProbeConfigSupplier(runtimeSettings::toProbeConfig);
        dialOrchestrator.setDisconnectOnNoInternet(runtimeSettings.isDisconnectOnNoInternet());
        dialOrchestrator.setOnProbeOutcome(runtimeSettings::recordProbeOutcome);

        autoReconnectService = new AutoReconnectService(
            dialLifecycle::isBusy,
            () -> ConnectivityConfirm.quickCheck(runtimeSettings.toProbeConfig()),
            dialOrchestrator::dialSyncAuto,
            () -> {
                bridge.log("网络已恢复", UiTheme.COLOR_SUCCESS);
                bridge.showNotification("网络恢复", "已自动重连");
                bridge.updateStatus(true);
            },
            () -> bridge.updateStatus(false),
            message -> bridge.log(message, UiTheme.COLOR_INFO),
            message -> bridge.log(message, UiTheme.COLOR_WARNING),
            message -> bridge.log(message, UiTheme.COLOR_ERROR),
            backgroundExecutor
        );

        networkMonitorService = new NetworkMonitorService(
            isOnline::get,
            trafficSampler::sample,
            () -> sessionTraffic.connectTimeMillis().get(),
            sample -> {
                sessionTraffic.applySample(sample.downBytes, sample.upBytes);
                bridge.invokeIfUiActive(() -> {
                    MainHomePanel h = homePanel.get();
                    if (h != null) {
                        h.setSpeedText("↓" + FormatUtil.formatSpeedLabel(sample.downBytes)
                            + "  ↑" + FormatUtil.formatSpeedLabel(sample.upBytes));
                    }
                });
                tooltipDirty = true;
            },
            () -> bridge.invokeIfUiActive(() -> {
                MainHomePanel h = homePanel.get();
                if (h != null) h.setSpeedText("↓ -- ↑ --");
            }),
            () -> {
                if (tooltipDirty) {
                    tooltipDirty = false;
                    bridge.invokeIfUiActive(() -> {
                        TrayController t = tray.get();
                        if (t != null) t.updateTooltip();
                    });
                }
            },
            connTime -> bridge.invokeIfUiActive(() -> {
                MainHomePanel h = homePanel.get();
                if (h == null) return;
                if (connTime > 0) {
                    long seconds = (System.currentTimeMillis() - connTime) / 1000;
                    h.setUptimeText("时长: " + FormatUtil.formatDuration(seconds));
                } else {
                    h.setUptimeText("时长: 未连接");
                }
            }),
            backgroundExecutor
        );

        scheduleService = new ScheduleService(
            runtimeSettings::isScheduledDialEnabled,
            runtimeSettings::isScheduledDisconnectEnabled,
            runtimeSettings::getScheduledDialHour,
            runtimeSettings::getScheduledDialMinute,
            runtimeSettings::getScheduledDisconnectHour,
            runtimeSettings::getScheduledDisconnectMinute,
            isOnline::get,
            dialLifecycle::isBusy,
            dialOrchestrator::dialSyncAuto,
            dialOrchestrator::disconnectSyncScheduled,
            () -> bridge.log("定时拨号触发", UiTheme.COLOR_INFO),
            () -> bridge.log("定时断开触发", UiTheme.COLOR_INFO),
            msg -> bridge.log(msg, UiTheme.COLOR_WARNING),
            backgroundExecutor
        );
    }

    public void markTooltipDirty() {
        tooltipDirty = true;
    }

    public void shutdownRuntime() {
        autoReconnectService.stop();
        scheduleService.stop();
        networkMonitorService.stop();
        dialOrchestrator.clearCredentials();
        dialOrchestrator.shutdown();
        backgroundExecutor.shutdown();
    }
}
