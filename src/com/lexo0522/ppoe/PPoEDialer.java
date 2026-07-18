/*
 * PPPoE校园网自动拨号工具
 * Thin Swing shell: window + Host adapters. Composition in AppServices / UI controllers.
 * Author: Lexo0522 — https://github.com/Lexo0522/one-key-dialer
 */

package com.lexo0522.ppoe;

import model.AccountInfo;
import model.AppFiles;
import model.AppVersion;
import model.DialSnapshot;
import service.LegacyDataMigrator;
import service.SettingsCoordinator;
import service.StartupSelfCheck;
import ui.AccountUiController;
import ui.DialUiActions;
import ui.MainHomePanel;
import ui.MainTabsController;
import ui.ProbeSettingsPanel;
import ui.SchedulePanel;
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
 * Main window shell. Business logic lives in {@code service/*};
 * wiring lives in {@link AppServices}, tabs in {@link MainTabsController},
 * accounts in {@link AccountUiController}, dial gates in {@link DialUiActions},
 * exit in {@link ShellShutdown}, settings in {@link SettingsWiring},
 * updates in {@link UpdateCheckUi}, process entry in {@link AppLauncher}.
 */
@SuppressWarnings("serial")
public class PPoEDialer extends JFrame implements ShellBridge {

    public static final String APP_TITLE = "PPPoE校园网拨号工具";
    public static final String APP_VERSION = AppVersion.DISPLAY;
    private static final int WINDOW_WIDTH = 580;
    private static final int WINDOW_HEIGHT = 700;

    private MainHomePanel homePanel;
    private TrayController trayController;
    private MainTabsController tabs;
    private AccountUiController accountsUi;
    private AppServices services;
    private SettingsCoordinator settingsCoordinator;
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

        services = new AppServices(this, () -> homePanel, () -> trayController);

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
        accountsUi.bindCredentialCacheOnBlur();

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
            @Override public service.RuntimeSettings runtimeSettings() { return services.runtimeSettings; }
            @Override public service.ScheduleService scheduleService() { return services.scheduleService; }
            @Override public service.DialService dialService() { return services.dialService; }
            @Override public service.BackgroundExecutor backgroundExecutor() { return services.backgroundExecutor; }
            @Override public java.util.function.BooleanSupplier isOnline() { return services.isOnline::get; }
            @Override public AccountInfo currentAccount() { return services.accountSession.currentOrNull(); }
            @Override public long connectTimeMillis() { return services.sessionTraffic.connectTimeMillis().get(); }
            @Override public long totalDownload() { return services.sessionTraffic.totalDownload().get(); }
            @Override public long totalUpload() { return services.sessionTraffic.totalUpload().get(); }
            @Override public long speedDown() { return services.sessionTraffic.currentSpeedDown().get(); }
            @Override public long speedUp() { return services.sessionTraffic.currentSpeedUp().get(); }
            @Override public boolean isUiActive() { return PPoEDialer.this.isUiActive(); }
            @Override public void flushPendingPersistence() { shutdown.flushPendingPersistence(); }
            @Override public void saveSettings() { PPoEDialer.this.saveSettings(); }
            @Override public void log(String message, Color color) { PPoEDialer.this.log(message, color); }
            @Override public void syncScheduleCacheFromUi() { PPoEDialer.this.syncScheduleCacheFromUi(); }
            @Override public void syncProbeFromUi(ProbeSettingsPanel panel) {
                PPoEDialer.this.syncProbeFromUi(panel);
            }
            @Override public JFrame frame() { return PPoEDialer.this; }
        });

        updateCheckUi = new UpdateCheckUi(new UpdateCheckUi.Host() {
            @Override public Component dialogOwner() { return PPoEDialer.this; }
            @Override public service.BackgroundExecutor backgroundExecutor() {
                return services.backgroundExecutor;
            }
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

        LegacyDataMigrator.migrateIfNeeded(PPoEDialer.class,
            AppFiles.ACCOUNTS, AppFiles.SETTINGS, AppFiles.HISTORY, AppFiles.LOG);
        loadSettings();
        services.accountSession.load();
        accountsUi.refreshAccountComboBox();
        accountsUi.refreshDialCredentialCache();
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
            services.runtimeSettings.probeSummaryLine()
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
            if (services.runtimeSettings.isUpdateCheckEnabled()) {
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
                services.runtimeSettings.setDisconnectOnNoInternet(enabled);
                services.dialOrchestrator.setDisconnectOnNoInternet(enabled);
                PPoEDialer.this.saveSettings();
            }
            @Override public void onUpdateCheckToggled(boolean enabled) {
                services.runtimeSettings.setUpdateCheckEnabled(enabled);
                PPoEDialer.this.saveSettings();
            }
            @Override public void onDialToggle() {
                if (services.isOnline.get()) performDisconnect();
                else performDial();
            }
        }, services.logService);
    }

    // ---------- ShellBridge ----------

    @Override public Component dialogOwner() { return this; }
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

    @Override
    public void showNotification(String title, String message) {
        if (!isUiActive()) return;
        if (trayController != null) trayController.displayMessage(title, message);
    }

    @Override
    public void updateButtonState(boolean enabled) {
        invokeIfUiActive(() -> {
            if (homePanel == null) return;
            if (enabled) homePanel.setOnlineStatus(services.isOnline.get());
            else homePanel.setDialEnabled(false);
        });
    }

    @Override
    public void updateDialProgress(String phase) {
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

    @Override
    public boolean validateBeforeDial(boolean interactive) {
        return dialUi.validateBeforeDial(interactive);
    }

    @Override
    public DialSnapshot captureDialSnapshotOnEdt() {
        return dialUi.captureDialSnapshotOnEdt();
    }

    @Override
    public void saveCurrentAccount() {
        accountsUi.saveCurrentAccount();
    }

    @Override
    public void saveSettings() {
        ensureSettingsCoordinator();
        settingsCoordinator.save();
    }

    @Override
    public void addHistoryRecord(String operation, String account, String result,
                                 String duration, String totalTraffic) {
        services.historyService.addRecord(operation, account, result, duration, totalTraffic);
        if ((services.historyService.records().size() & 0x0F) == 0) {
            shutdown.flushPendingPersistence();
        }
    }

    @Override
    public void markTooltipDirty() {
        services.markTooltipDirty();
    }

    // ---------- shell actions ----------

    private void performDial() {
        accountsUi.refreshDialCredentialCache();
        services.dialOrchestrator.dialAsyncUser();
    }

    private void performDisconnect() {
        services.dialOrchestrator.disconnectAsyncUser();
    }

    private void startAutoReconnect() {
        if (services.autoReconnectService.isRunning()) return;
        accountsUi.refreshDialCredentialCache();
        services.autoReconnectService.start(
            (int) homePanel.getSpnInterval().getValue(), true);
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

    private void toggleAutoStart() {
        if (homePanel.getChkAutoStart().isSelected()) {
            services.startupService.enableAutoStart(PPoEDialer.class);
        } else {
            services.startupService.disableAutoStart();
        }
        saveSettings();
    }

    private void syncProbeFromUi(ProbeSettingsPanel panel) {
        if (panel == null) return;
        services.runtimeSettings.setProbe(
            panel.getProbeMode(), panel.getProbeHost(), panel.getProbeHttpUrl(),
            panel.getProbeAttempts(), panel.getProbeDelayMs()
        );
        services.dialOrchestrator.setProbeConfigSupplier(services.runtimeSettings::toProbeConfig);
    }

    private void syncScheduleCacheFromUi() {
        SchedulePanel p = tabs.schedulePanel();
        if (p == null) return;
        services.runtimeSettings.setSchedule(
            p.isDialEnabled(), p.dialHour(), p.dialMinute(),
            p.isDisconnectEnabled(), p.disconnectHour(), p.disconnectMinute()
        );
    }

    private void ensureSettingsCoordinator() {
        if (settingsCoordinator != null) return;
        settingsCoordinator = SettingsWiring.create(
            services,
            () -> homePanel,
            () -> tabs,
            PPoEDialer.class,
            this::syncScheduleCacheFromUi,
            this::syncProbeFromUi,
            msg -> log(msg, UiTheme.COLOR_WARNING),
            msg -> log(msg, UiTheme.COLOR_ERROR)
        );
    }

    private void loadSettings() {
        ensureSettingsCoordinator();
        settingsCoordinator.load();
    }

    public static void main(String[] args) {
        AppLauncher.main(args);
    }
}
