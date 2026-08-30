package com.lexo0522.ppoe;

import model.AppFiles;
import model.DialLifecycle;
import model.SessionTraffic;
import service.AccountSession;
import service.AutoReconnectService;
import service.BackgroundExecutor;
import service.DialEnvironment;
import service.DialOrchestrator;
import service.DialView;
import service.HistoryService;
import service.LogService;
import service.NetworkMonitorService;
import service.ScheduleService;
import service.SettingsManager;
import service.StartupService;
import service.UpdateModule;
import service.WindowsRasModule;
import storage.AccountStore;
import storage.DpapiSecretProtector;
import storage.HistoryStore;
import storage.SecretProtector;
import storage.SettingsStore;
import ui.MainHomePanel;
import ui.TrayController;
import ui.UiTheme;
import util.AppPaths;
import util.ConnectivityConfirm;
import util.FormatUtil;
import util.ProbeOutcome;
import util.TrafficSampler;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Composition root for non-UI services. Constructed once with a live {@link ShellBridge}
 * that also implements {@link DialView}.
 */
public final class AppServices {
    public final SettingsStore settingsStore;
    public final HistoryStore historyStore;
    public final AccountStore accountStore;
    public final LogService logService;
    public final SettingsManager settingsManager;
    public final HistoryService historyService;
    public final AccountSession accountSession;
    public final BackgroundExecutor backgroundExecutor;
    public final StartupService startupService;
    public final WindowsRasModule rasModule;
    public final DialOrchestrator dialOrchestrator;
    public final AutoReconnectService autoReconnectService;
    public final NetworkMonitorService networkMonitorService;
    public final ScheduleService scheduleService;
    public final UpdateModule updateModule;
    public final DialLifecycle dialLifecycle = new DialLifecycle();
    public final SessionTraffic sessionTraffic = new SessionTraffic();
    public final AtomicBoolean isOnline = new AtomicBoolean(false);

    private final TrafficSampler trafficSampler;
    private volatile ProbeOutcome lastProbeOutcome;
    private volatile boolean tooltipDirty;

    public AppServices(ShellBridge bridge, DialView dialView) {
        File dataDir = AppPaths.getDataDir(PPoEDialer.class);
        SecretProtector protector = new DpapiSecretProtector();
        accountStore = new AccountStore(new File(dataDir, AppFiles.ACCOUNTS), protector);
        historyStore = new HistoryStore(new File(dataDir, AppFiles.HISTORY));
        settingsStore = new SettingsStore(new File(dataDir, AppFiles.SETTINGS));
        logService = new LogService(new File(dataDir, AppFiles.LOG));

        backgroundExecutor = new BackgroundExecutor();
        backgroundExecutor.setErrorReporter(t ->
            logService.logThrowable("后台任务异常", t, UiTheme.COLOR_ERROR));

        settingsManager = new SettingsManager(settingsStore,
            msg -> bridge.log(msg, UiTheme.COLOR_WARNING));
        historyService = new HistoryService(historyStore,
            msg -> bridge.log(msg, UiTheme.COLOR_WARNING));
        accountSession = new AccountSession(accountStore, new AccountSession.Logger() {
            @Override public void info(String message) {
                bridge.log(message, UiTheme.COLOR_INFO);
            }
            @Override public void error(String message) {
                bridge.log(message, UiTheme.COLOR_ERROR);
            }
        }, backgroundExecutor);

        trafficSampler = new TrafficSampler(
            msg -> bridge.log(msg, UiTheme.COLOR_WARNING));

        startupService = new StartupService(
            "PPoEDialer",
            () -> bridge.invokeIfUiActive(() -> {
                MainHomePanel h = homePanel(bridge);
                if (h != null) h.getChkAutoStart().setSelected(true);
            }),
            () -> bridge.invokeIfUiActive(() -> {
                MainHomePanel h = homePanel(bridge);
                if (h != null) h.getChkAutoStart().setSelected(false);
            }),
            (message, success) -> bridge.log(message,
                success ? UiTheme.COLOR_SUCCESS : UiTheme.COLOR_ERROR)
        );

        rasModule = new WindowsRasModule(AppFiles.RAS_CONNECTION, true);

        dialOrchestrator = new DialOrchestrator(
            rasModule, dialView, dialEnvironment(bridge), dialLifecycle, sessionTraffic);

        autoReconnectService = new AutoReconnectService(
            dialLifecycle::isBusy,
            () -> ConnectivityConfirm.quickCheck(settingsManager.current().toProbeConfig()),
            dialOrchestrator::dialAuto,
            () -> {
                bridge.log("网络已恢复", UiTheme.COLOR_SUCCESS);
                TrayController tray = bridge.trayController();
                if (tray != null) tray.displayMessage("网络恢复", "已自动重连");
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
                    MainHomePanel h = homePanel(bridge);
                    if (h != null) {
                        h.setSpeedText("↓" + FormatUtil.formatSpeedLabel(sample.downBytes)
                            + "  ↑" + FormatUtil.formatSpeedLabel(sample.upBytes));
                    }
                });
                tooltipDirty = true;
            },
            () -> bridge.invokeIfUiActive(() -> {
                MainHomePanel h = homePanel(bridge);
                if (h != null) h.setSpeedText("↓ -- ↑ --");
            }),
            () -> {
                if (tooltipDirty) {
                    tooltipDirty = false;
                    bridge.invokeIfUiActive(() -> {
                        TrayController t = bridge.trayController();
                        if (t != null) t.updateTooltip();
                    });
                }
            },
            connTime -> bridge.invokeIfUiActive(() -> {
                MainHomePanel h = homePanel(bridge);
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
            () -> settingsManager.current().scheduledDial,
            () -> settingsManager.current().scheduledDisconnect,
            () -> settingsManager.current().scheduledDialHour,
            () -> settingsManager.current().scheduledDialMinute,
            () -> settingsManager.current().scheduledDisconnectHour,
            () -> settingsManager.current().scheduledDisconnectMinute,
            isOnline::get,
            dialLifecycle::isBusy,
            dialOrchestrator::dialAuto,
            dialOrchestrator::disconnectScheduled,
            () -> bridge.log("定时拨号触发", UiTheme.COLOR_INFO),
            () -> bridge.log("定时断开触发", UiTheme.COLOR_INFO),
            msg -> bridge.log(msg, UiTheme.COLOR_WARNING),
            backgroundExecutor
        );

        updateModule = new UpdateModule(UpdateModule.defaultUpdatesDir(), null, null, null);
    }

    private DialEnvironment dialEnvironment(ShellBridge bridge) {
        return new DialEnvironment() {
            @Override public boolean isOnline() {
                return isOnline.get();
            }

            @Override public long connectTimeMillis() {
                return sessionTraffic.connectTimeMillis().get();
            }

            @Override public long sessionTrafficBytes() {
                return sessionTraffic.sessionTrafficBytes();
            }

            @Override public String currentAccountName() {
                return accountSession.currentName();
            }

            @Override public ConnectivityConfirm.Config probeConfig() {
                return settingsManager.current().toProbeConfig();
            }

            @Override public boolean disconnectOnNoInternet() {
                return settingsManager.current().disconnectOnNoInternet;
            }

            @Override public void addHistory(String operation, String account, String result,
                                             String duration, String traffic) {
                historyService.addRecord(operation, account, result, duration, traffic);
            }

            @Override public void persistAfterSuccess() {
                bridge.saveSettings();
            }

            @Override public void recordProbeOutcome(ProbeOutcome outcome) {
                AppServices.this.recordProbeOutcome(outcome);
            }
        };
    }

    private static MainHomePanel homePanel(ShellBridge bridge) {
        return bridge.homePanel();
    }

    /** Aggregate dial history into the diag probe/status report line. */
    public String probeReportLine() {
        model.SettingsSnapshot s = settingsManager.current();
        String base = "mode=" + s.probeMode + " host=" + s.probeHost + " http=" + s.probeHttpUrl
            + " attempts=" + s.probeAttempts + " delayMs=" + s.probeDelayMs
            + " disconnectOnNoInternet=" + s.disconnectOnNoInternet;
        ProbeOutcome last = lastProbeOutcome;
        if (last != null) {
            base += " | last=[" + last.detailLine() + "]";
        }
        return base;
    }

    public void recordProbeOutcome(ProbeOutcome outcome) {
        if (outcome != null) {
            lastProbeOutcome = outcome;
        }
    }

    public void markTooltipDirty() {
        tooltipDirty = true;
    }

    public void shutdownRuntime() {
        autoReconnectService.stop();
        scheduleService.stop();
        networkMonitorService.stop();
        dialOrchestrator.shutdown();
        backgroundExecutor.shutdown();
    }
}
