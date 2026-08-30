/*
 * PPPoE校园网自动拨号工具
 * Thin Swing shell: window + UI adapters. Composition in AppServices / UI controllers.
 * Author: Lexo0522 — https://github.com/Lexo0522/one-key-dialer
 */

package com.lexo0522.ppoe;

import model.AccountInfo;
import model.DialCredentials;
import model.SettingsSnapshot;
import service.BackgroundExecutor;
import service.DialView;
import service.SettingsManager;
import service.StartupSelfCheck;
import ui.AccountUiController;
import ui.DialUiActions;
import ui.MainHomePanel;
import ui.MainTabsController;
import ui.ProbeSettingsPanel;
import ui.TrayController;
import ui.UiTheme;
import ui.UpdateCheckUi;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Main window shell. Business logic lives in {@code service/*}; wiring lives in
 * {@link AppServices}, tabs in {@link MainTabsController}, accounts in
 * {@link AccountUiController}, dial gates in {@link DialUiActions},
 * exit in {@link ShellShutdown}, updates in {@link UpdateCheckUi},
 * process entry in {@link AppLauncher}.
 */
@SuppressWarnings("serial")
public class PPoEDialer extends JFrame implements ShellBridge, DialView {

    public static final String APP_TITLE = "PPPoE校园网拨号工具";
    public static final String APP_VERSION = model.AppVersion.DISPLAY;
    private static final int WINDOW_WIDTH = 580;
    private static final int WINDOW_HEIGHT = 700;

    private MainHomePanel homePanel;
    private TrayController trayController;
    private MainTabsController tabs;
    private AccountUiController accountsUi;
    private AppServices services;
    private UpdateCheckUi updateCheckUi;
    private DialUiActions dialUi;
    private ShellShutdown shutdown;

    @SuppressWarnings("this-escape")
    public PPoEDialer() {
        super(APP_TITLE + " " + APP_VERSION);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        setLocationRelativeTo(null);
        setResizable(true);
        setMinimumSize(new Dimension(520, 560));

        services = new AppServices(this, this);

        homePanel = createHomePanel();
        accountsUi = new AccountUiController(new AccountUiController.Host() {
            @Override public MainHomePanel homePanel() { return homePanel; }
            @Override public service.AccountSession accountSession() { return services.accountSession; }
            @Override public service.DialOrchestrator dialOrchestrator() { return services.dialOrchestrator; }
            @Override public model.DialLifecycle dialLifecycle() { return services.dialLifecycle; }
            @Override public java.util.concurrent.atomic.AtomicBoolean isOnline() { return services.isOnline; }
            @Override public service.BackgroundExecutor backgroundExecutor() { return services.backgroundExecutor; }
            @Override public TrayController trayController() { return trayController; }
            @Override public void markTooltipDirty() { services.markTooltipDirty(); }
            @Override public void saveSettings() { PPoEDialer.this.saveSettings(); }
            @Override public void logInfo(String message) { log(message, UiTheme.COLOR_INFO); }
            @Override public void logWarning(String message) { log(message, UiTheme.COLOR_WARNING); }
        });

        dialUi = new DialUiActions(new DialUiActions.Host() {
            @Override public Component dialogOwner() { return PPoEDialer.this; }
            @Override public MainHomePanel homePanel() { return homePanel; }
            @Override public java.util.function.BooleanSupplier isOnline() { return services.isOnline::get; }
            @Override public java.util.function.BooleanSupplier hasCurrentAccount() {
                return () -> services.accountSession.currentOrNull() != null;
            }
            @Override public void log(String message, Color color) { PPoEDialer.this.log(message, color); }
        });

        tabs = new MainTabsController(new MainTabsController.Host() {
            @Override public MainHomePanel homePanel() { return homePanel; }
            @Override public service.HistoryService historyService() { return services.historyService; }
            @Override public SettingsManager settingsManager() { return services.settingsManager; }
            @Override public service.ScheduleService scheduleService() { return services.scheduleService; }
            @Override public service.WindowsRasModule rasModule() { return services.rasModule; }
            @Override public service.BackgroundExecutor backgroundExecutor() { return services.backgroundExecutor; }
            @Override public java.util.function.BooleanSupplier isOnline() { return services.isOnline::get; }
            @Override public AccountInfo currentAccount() { return services.accountSession.currentOrNull(); }
            @Override public long connectTimeMillis() { return services.sessionTraffic.connectTimeMillis().get(); }
            @Override public long totalDownload() { return services.sessionTraffic.totalDownload().get(); }
            @Override public long totalUpload() { return services.sessionTraffic.totalUpload().get(); }
            @Override public long speedDown() { return services.sessionTraffic.currentSpeedDown().get(); }
            @Override public long speedUp() { return services.sessionTraffic.currentSpeedUp().get(); }
            @Override public String probeReport() { return services.probeReportLine(); }
            @Override public boolean isUiActive() { return PPoEDialer.this.isUiActive(); }
            @Override public void flushPendingPersistence() { shutdown.flushPendingPersistence(); }
            @Override public void saveSettings() { PPoEDialer.this.saveSettings(); }
            @Override public void log(String message, Color color) { PPoEDialer.this.log(message, color); }
            @Override public JFrame frame() { return PPoEDialer.this; }
        });

        updateCheckUi = new UpdateCheckUi(new UpdateCheckUi.Host() {
            @Override public Component dialogOwner() { return PPoEDialer.this; }
            @Override public service.BackgroundExecutor backgroundExecutor() {
                return services.backgroundExecutor;
            }
            @Override public service.UpdateModule updateModule() { return services.updateModule; }
            @Override public void invokeIfUiActive(Runnable action) {
                PPoEDialer.this.invokeIfUiActive(action);
            }
            @Override public void logInfo(String message) { log(message, UiTheme.COLOR_INFO); }
            @Override public void logSuccess(String message) { log(message, UiTheme.COLOR_SUCCESS); }
            @Override public void logWarning(String message) { log(message, UiTheme.COLOR_WARNING); }
            @Override public void logError(String message) { log(message, UiTheme.COLOR_ERROR); }
            @Override public void prepareForUpdateApply() {
                try {
                    saveSettings();
                    accountsUi.saveCurrentAccount();
                    services.historyService.saveIfDirty();
                    services.logService.flush();
                } catch (Exception ignored) {
                }
            }
            @Override public void exitForUpdate() {
                // Prefer ordered shutdown so services stop before process dies
                if (shutdown != null) {
                    shutdown.exitProgram();
                } else {
                    System.exit(0);
                }
            }
        });

        shutdown = new ShellShutdown(new ShellShutdown.Host() {
            @Override public void saveSettings() { PPoEDialer.this.saveSettings(); }
            @Override public void saveCurrentAccount() { accountsUi.saveCurrentAccount(); }
            @Override public service.HistoryService historyService() { return services.historyService; }
            @Override public service.LogService logService() { return services.logService; }
            @Override public AppServices services() { return services; }
            @Override public service.AccountSession accountSession() { return services.accountSession; }
            @Override public java.util.function.Supplier<MainHomePanel> homePanel() { return () -> homePanel; }
            @Override public java.util.function.Supplier<TrayController> trayController() {
                return () -> trayController;
            }
            @Override public void disposeWindow() { dispose(); }
        });

        JPanel mainPanel = new JPanel(new BorderLayout(0, 0));
        mainPanel.setBackground(UiTheme.COLOR_BG);
        mainPanel.add(homePanel.getStatusBar(), BorderLayout.NORTH);
        mainPanel.add(tabs.getTabbedPane(), BorderLayout.CENTER);
        setContentPane(mainPanel);

        trayController = new TrayController(APP_TITLE, new TrayController.Host() {
            @Override public void showWindow() { PPoEDialer.this.showWindow(); }
            @Override public void exitProgram() { shutdown.exitProgram(); }
            @Override public boolean isOnline() { return services.isOnline.get(); }
            @Override public AccountInfo currentAccount() { return services.accountSession.currentOrNull(); }
            @Override public long connectTimeMillis() { return services.sessionTraffic.connectTimeMillis().get(); }
            @Override public long currentSpeedDown() { return services.sessionTraffic.currentSpeedDown().get(); }
            @Override public long currentSpeedUp() { return services.sessionTraffic.currentSpeedUp().get(); }
            @Override public long totalDownload() { return services.sessionTraffic.totalDownload().get(); }
            @Override public long totalUpload() { return services.sessionTraffic.totalUpload().get(); }
            @Override public long sessionStartDownload() { return services.sessionTraffic.sessionStartDownload().get(); }
            @Override public long sessionStartUpload() { return services.sessionTraffic.sessionStartUpload().get(); }
            @Override public java.util.List<AccountInfo> accounts() { return services.accountSession.accounts(); }
            @Override public void switchToAccount(int index) { accountsUi.switchToAccountFromTray(index); }
            @Override public void dialNow() {
                if (!services.isOnline.get() && !services.dialLifecycle.isBusy()) performDial();
            }
            @Override public void disconnectNow() {
                if (services.isOnline.get() && !services.dialLifecycle.isBusy()) performDisconnect();
            }
            @Override public void checkForUpdates() { updateCheckUi.check(true); }
        }, msg -> log(msg, UiTheme.COLOR_ERROR));

        // Settings: load snapshot, apply to controls + runtime.
        applySettings(services.settingsManager.loadFromDisk());
        reconcileAutoStartAsync();
        services.accountSession.load();
        accountsUi.refreshAccountComboBox();
        services.networkMonitorService.start();
        services.scheduleService.restart();
        restoreAutoReconnect();
        services.backgroundExecutor.submit(() -> StartupSelfCheck.run(
            new StartupSelfCheck.Logger() {
                @Override public void info(String message) { log(message, UiTheme.COLOR_INFO); }
                @Override public void warn(String message) { log(message, UiTheme.COLOR_WARNING); }
            },
            services.settingsStore.getFile(),
            services.accountStore.getFile(),
            services.historyStore.getFile(),
            services.settingsManager.current().probeMode + " / "
                + services.settingsManager.current().probeHost
        ));

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) { setVisible(false); }
        });

        shutdown.installJvmShutdownHook();

        SwingUtilities.invokeLater(() -> {
            if (trayController != null) trayController.init();
            if (homePanel.getChkStartMinimized().isSelected()) {
                setVisible(false);
            }
            // Quiet GitHub check only when user left the option enabled.
            if (services.settingsManager.current().updateCheckEnabled) {
                updateCheckUi.scheduleQuietCheck(5000L);
            }
        });
    }

    /** Used by {@link AppLauncher} to decide initial visibility. */
    boolean isStartMinimizedSelected() {
        return homePanel != null && homePanel.getChkStartMinimized().isSelected();
    }

    private MainHomePanel createHomePanel() {
        return new MainHomePanel(new MainHomePanel.Host() {
            @Override public void onAccountSelected() { accountsUi.onAccountChanged(); }
            @Override public void openAccountManager() { accountsUi.openAccountManager(PPoEDialer.this); }
            @Override public void onAutoReconnectToggled(boolean enabled) {
                if (enabled) startAutoReconnect();
                else stopAutoReconnect();
            }
            @Override public void onAutoStartToggled() { toggleAutoStart(); }
            @Override public void saveSettings() { PPoEDialer.this.saveSettings(); }
            @Override public void onDisconnectOnNoInternetToggled(boolean enabled) {
                PPoEDialer.this.saveSettings();
            }
            @Override public void onUpdateCheckToggled(boolean enabled) {
                PPoEDialer.this.saveSettings();
            }
            @Override public void onDialToggle() {
                if (services.isOnline.get()) performDisconnect();
                else performDial();
            }
        }, services.logService);
    }

    // ---------- settings capture / apply (EDT) ----------

    /** Merge current control state into one snapshot and swap it in as runtime state. */
    private SettingsSnapshot captureSettingsFromUi() {
        SettingsSnapshot.Builder builder =
            services.settingsManager.current().toBuilder();
        homePanel.captureSettings(builder);
        ui.SchedulePanel schedulePanel = tabs.schedulePanel();
        if (schedulePanel != null) schedulePanel.captureSettings(builder);
        ProbeSettingsPanel probePanel = tabs.probePanel();
        if (probePanel != null) probePanel.captureSettings(builder);
        builder.accountIndex(services.accountSession.currentIndex());
        return builder.build();
    }

    /** Push a snapshot into all created panels (runtime state is swapped by the caller). */
    private void applySettingsToUi(SettingsSnapshot s) {
        homePanel.applySettings(s);
        ui.SchedulePanel schedulePanel = tabs.schedulePanel();
        if (schedulePanel != null) schedulePanel.applySettings(s);
        ProbeSettingsPanel probePanel = tabs.probePanel();
        if (probePanel != null) probePanel.applySettings(s);
    }

    private void applySettings(SettingsSnapshot s) {
        services.settingsManager.update(s);
        applySettingsToUi(s);
    }

    @Override
    public void saveSettings() {
        SettingsSnapshot snapshot = captureSettingsFromUi();
        services.settingsManager.update(snapshot);
        services.settingsManager.saveToDisk(snapshot);
    }

    // ---------- ShellBridge ----------

    @Override public MainHomePanel homePanel() { return homePanel; }
    @Override public TrayController trayController() { return trayController; }

    @Override
    public boolean isUiActive() {
        return isDisplayable();
    }

    @Override
    public void invokeIfUiActive(Runnable action) {
        SwingUtilities.invokeLater(() -> {
            if (!isUiActive()) return;
            action.run();
        });
    }

    @Override
    public void log(String message, Color color) {
        services.logService.log(message, color);
    }

    @Override
    public void updateStatus(boolean online) {
        boolean wasOnline = services.isOnline.getAndSet(online);
        if (online) {
            if (!wasOnline || services.sessionTraffic.connectTimeMillis().get() == 0) {
                services.sessionTraffic.markSessionStart();
            }
        } else {
            services.sessionTraffic.markOffline();
        }
        invokeIfUiActive(() -> {
            if (homePanel != null) homePanel.setOnlineStatus(online);
            if (trayController != null) {
                trayController.updateOnlineIcon(online);
                trayController.updateTooltip();
            }
        });
    }

    // ---------- DialView ----------

    @Override public boolean onEventDispatchThread() {
        return SwingUtilities.isEventDispatchThread();
    }

    @Override public void runOnEdt(Runnable action) {
        invokeIfUiActive(action);
    }

    @Override public void runOnEdtAndWait(Runnable action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeAndWait(action);
        }
    }

    @Override public DialCredentials captureDialCredentials() {
        return dialUi.captureDialCredentials();
    }

    @Override public boolean validateDialInput(boolean interactive) {
        return dialUi.validateDialInput(interactive);
    }

    @Override public void onDialPhase(String phase) {
        invokeIfUiActive(() -> {
            if (homePanel == null) return;
            if ("dialing".equals(phase)) {
                homePanel.setDialProgress("连接中…", UiTheme.COLOR_WARNING);
            } else if ("disconnecting".equals(phase)) {
                homePanel.setDialProgress("断开中…", UiTheme.COLOR_WARNING);
            } else {
                homePanel.setOnlineStatus(services.isOnline.get());
            }
        });
    }

    @Override public void onConnectionState(boolean online) {
        updateStatus(online);
    }

    @Override public void notifyUser(String title, String message) {
        if (!isUiActive()) return;
        if (trayController != null) trayController.displayMessage(title, message);
    }

    @Override public void log(DialView.Level level, String message) {
        Color color;
        switch (level) {
            case SUCCESS: color = UiTheme.COLOR_SUCCESS; break;
            case WARNING: color = UiTheme.COLOR_WARNING; break;
            case ERROR: color = UiTheme.COLOR_ERROR; break;
            case INFO:
            default: color = UiTheme.COLOR_INFO; break;
        }
        log(message, color);
    }

    // ---------- shell actions ----------

    private void performDial() {
        services.dialOrchestrator.dialAsyncUser();
    }

    private void performDisconnect() {
        services.dialOrchestrator.disconnectAsyncUser();
    }

    private void startAutoReconnect() {
        if (services.autoReconnectService.isRunning()) return;
        services.autoReconnectService.start(
            services.settingsManager.current().intervalSeconds, true);
    }

    private void stopAutoReconnect() {
        boolean wasRunning = services.autoReconnectService.isRunning();
        services.autoReconnectService.stop();
        if (!wasRunning) {
            SwingUtilities.invokeLater(() -> homePanel.getChkAutoReconnect().setSelected(false));
            return;
        }
        invokeIfUiActive(() -> homePanel.getChkAutoReconnect().setSelected(false));
    }

    private void restoreAutoReconnect() {
        if (!homePanel.getChkAutoReconnect().isSelected()) return;
        startAutoReconnect();
    }

    private void showWindow() {
        if (trayController != null) trayController.ensureReady();
        setVisible(true);
        setExtendedState(JFrame.NORMAL);
        toFront();
    }

    private void reconcileAutoStartAsync() {
        if (homePanel == null) return;
        final boolean settingsWantAutoStart = homePanel.getChkAutoStart().isSelected();
        homePanel.getChkAutoStart().setEnabled(false);
        services.backgroundExecutor.submitLong(() -> {
            boolean registered = services.startupService.isAutoStartEnabled();
            if (settingsWantAutoStart || registered) {
                registered = services.startupService.ensureAutoStartHealthy(PPoEDialer.class, true);
            }
            final boolean enabled = registered;
            SwingUtilities.invokeLater(() -> {
                if (homePanel == null) return;
                homePanel.getChkAutoStart().setSelected(enabled);
                homePanel.getChkAutoStart().setEnabled(true);
                if (settingsWantAutoStart != enabled) saveSettings();
            });
        });
    }

    private void toggleAutoStart() {
        if (homePanel == null) return;
        final boolean requested = homePanel.getChkAutoStart().isSelected();
        homePanel.getChkAutoStart().setEnabled(false);
        services.backgroundExecutor.submitLong(() -> {
            if (requested) {
                services.startupService.enableAutoStart(PPoEDialer.class);
            } else {
                services.startupService.disableAutoStart();
            }
            final boolean enabled = services.startupService.isAutoStartEnabled();
            SwingUtilities.invokeLater(() -> {
                if (homePanel == null) return;
                homePanel.getChkAutoStart().setSelected(enabled);
                homePanel.getChkAutoStart().setEnabled(true);
                saveSettings();
            });
        });
    }

    public static void main(String[] args) {
        AppLauncher.main(args);
    }
}
