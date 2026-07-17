/*
 * PPPoE校园网自动拨号工具 v1.0.0
 * 功能：一键拨号、断开网络、自动重连、开机自启动、多账号切换
 *       定时拨号/断开、拨号历史记录、网络流量监控
 *       导出/导入配置、网络诊断工具、拨号统计
 * 特性：密码加密、异常日志、智能检测、配置备份
 * 作者：Lexo0522
 *
 * Thin Swing shell: composition root + Host adapters. Business logic lives in service/*.
 */

import model.AccountInfo;
import model.AppFiles;
import model.AppSettings;
import model.DialLifecycle;
import model.DialSnapshot;
import model.PasswordChars;
import model.SessionTraffic;
import service.AccountSession;
import service.AutoReconnectService;
import service.BackgroundExecutor;
import service.DialOrchestrator;
import service.DialService;
import service.HistoryService;
import service.LegacyDataMigrator;
import service.LogService;
import service.NetworkMonitorService;
import service.RasPhonebook;
import service.RuntimeSettings;
import service.ScheduleService;
import service.SettingsCoordinator;
import service.StartupSelfCheck;
import service.StartupService;
import storage.AccountStore;
import storage.HistoryStore;
import storage.SettingsStore;
import ui.AccountManagerDialog;
import ui.DiagPanel;
import ui.HistoryPanel;
import ui.LookAndFeelInstaller;
import ui.MainHomePanel;
import ui.PasswordFields;
import ui.ProbeSettingsPanel;
import ui.SchedulePanel;
import ui.TrayController;
import ui.UiTheme;
import util.AppPaths;
import util.ConnectivityConfirm;
import util.CryptoUtil;
import util.FormatUtil;
import util.NetworkProbe;
import util.ProbeOutcome;
import util.TrafficSampler;

import javax.swing.*;
import java.awt.Color;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@SuppressWarnings("serial")
public class PPoEDialer extends JFrame {

    // ==================== 全局常量 ====================
    public static final String APP_TITLE = "PPPoE校园网拨号工具";
    public static final String APP_VERSION = "v1.0.0";
    private static final String APP_EXE_NAME = "PPoEDialer";
    private static final int WINDOW_WIDTH = 580;
    private static final int WINDOW_HEIGHT = 700;
    private static final String SETTINGS_FILE = AppFiles.SETTINGS;
    private static final String LOG_FILE = AppFiles.LOG;
    private static final String ACCOUNTS_FILE = AppFiles.ACCOUNTS;
    private static final String HISTORY_FILE = AppFiles.HISTORY;
    private static final String BACKUP_SUFFIX = AppFiles.SETTINGS_BACKUP_SUFFIX;
    private static final String PPPOE_CONN_NAME = AppFiles.RAS_CONNECTION;

    // ==================== UI ====================
    private MainHomePanel homePanel;
    private JTabbedPane tabbedPane;
    private TrayController trayController;
    private SchedulePanel schedulePanelUi;
    private ProbeSettingsPanel probeSettingsPanel;
    private final TrafficSampler trafficSampler = new TrafficSampler(msg -> log(msg, UiTheme.COLOR_WARNING));

    // ==================== Runtime ====================
    private final AtomicBoolean isOnline = new AtomicBoolean(false);
    private final SessionTraffic sessionTraffic = new SessionTraffic();
    private final DialLifecycle dialLifecycle = new DialLifecycle();
    private final RuntimeSettings runtimeSettings = new RuntimeSettings();
    private volatile boolean tooltipDirty = false;
    private volatile String activeConnName = PPPOE_CONN_NAME;

    // ==================== History / Diag / secondary tabs lazy ====================
    private JPanel historyPanel;
    private boolean historyLoaded = false;
    private HistoryService historyService;
    private DiagPanel diagPanelComponent;
    private JPanel diagPanel;
    private boolean diagInitialized = false;
    private boolean scheduleUiLoaded = false;
    private boolean probeUiLoaded = false;

    // ==================== Persistence / services ====================
    private final AccountStore accountStore;
    private final AccountSession accountSession;
    private final HistoryStore historyStore;
    private final SettingsStore settingsStore;
    private final LogService logService;
    private final StartupService startupService;
    private final BackgroundExecutor backgroundExecutor;
    private final DialService dialService;
    private final DialOrchestrator dialOrchestrator;
    private final AutoReconnectService autoReconnectService;
    private final NetworkMonitorService networkMonitorService;
    private final ScheduleService scheduleService;
    private SettingsCoordinator settingsCoordinator;

    {
        try {
            CryptoUtil.init(AppPaths.masterKeyFile(PPoEDialer.class));
        } catch (Exception e) {
            System.err.println("Crypto init failed: " + e.getMessage());
        }
        File dataDir = AppPaths.getDataDir(PPoEDialer.class);
        accountStore = new AccountStore(new File(dataDir, ACCOUNTS_FILE));
        historyStore = new HistoryStore(new File(dataDir, HISTORY_FILE));
        settingsStore = new SettingsStore(new File(dataDir, SETTINGS_FILE), BACKUP_SUFFIX);
        logService = new LogService(new File(dataDir, LOG_FILE));
        historyService = new HistoryService(historyStore, msg -> log(msg, UiTheme.COLOR_WARNING));
        accountSession = new AccountSession(accountStore, new AccountSession.Logger() {
            @Override public void info(String message) { log(message, UiTheme.COLOR_INFO); }
            @Override public void error(String message) { log(message, UiTheme.COLOR_ERROR); }
        });

        backgroundExecutor = new BackgroundExecutor();

        startupService = new StartupService(
            APP_EXE_NAME,
            () -> invokeIfUiActive(() -> homePanel.getChkAutoStart().setSelected(true)),
            () -> invokeIfUiActive(() -> homePanel.getChkAutoStart().setSelected(false)),
            (message, success) -> log(message, success ? UiTheme.COLOR_SUCCESS : UiTheme.COLOR_ERROR)
        );
        dialService = new DialService(
            PPPOE_CONN_NAME,
            () -> activeConnName,
            value -> activeConnName = value,
            () -> { },
            message -> log(message, UiTheme.COLOR_INFO),
            message -> log(message, UiTheme.COLOR_WARNING),
            message -> log(message, UiTheme.COLOR_ERROR)
        );
        dialOrchestrator = new DialOrchestrator(new ShellDialHost());
        dialOrchestrator.setProbeConfigSupplier(runtimeSettings::toProbeConfig);
        dialOrchestrator.setDisconnectOnNoInternet(runtimeSettings.isDisconnectOnNoInternet());
        dialOrchestrator.setOnProbeOutcome(runtimeSettings::recordProbeOutcome);
        autoReconnectService = new AutoReconnectService(
            dialLifecycle::isBusy,
            NetworkProbe::isOnline,
            dialOrchestrator::dialSyncAuto,
            () -> {
                log("网络已恢复", UiTheme.COLOR_SUCCESS);
                showNotification("网络恢复", "已自动重连");
                updateStatus(true);
            },
            () -> updateStatus(false),
            message -> log(message, UiTheme.COLOR_INFO),
            message -> log(message, UiTheme.COLOR_WARNING),
            message -> log(message, UiTheme.COLOR_ERROR),
            backgroundExecutor
        );
        networkMonitorService = new NetworkMonitorService(
            () -> isOnline.get(),
            () -> trafficSampler.sample(),
            () -> sessionTraffic.connectTimeMillis().get(),
            sample -> {
                sessionTraffic.applySample(sample.downBytes, sample.upBytes);
                invokeIfUiActive(() -> {
                    if (homePanel != null) {
                        homePanel.setSpeedText("↓" + FormatUtil.formatSpeedLabel(sample.downBytes)
                            + "  ↑" + FormatUtil.formatSpeedLabel(sample.upBytes));
                    }
                });
                tooltipDirty = true;
            },
            () -> invokeIfUiActive(() -> {
                if (homePanel != null) homePanel.setSpeedText("↓ -- ↑ --");
            }),
            () -> {
                if (tooltipDirty) {
                    tooltipDirty = false;
                    invokeIfUiActive(() -> {
                        if (trayController != null) trayController.updateTooltip();
                    });
                }
            },
            connTime -> invokeIfUiActive(() -> {
                if (homePanel == null) return;
                if (connTime > 0) {
                    long seconds = (System.currentTimeMillis() - connTime) / 1000;
                    homePanel.setUptimeText("时长: " + FormatUtil.formatDuration(seconds));
                } else {
                    homePanel.setUptimeText("时长: 未连接");
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
            () -> isOnline.get(),
            dialLifecycle::isBusy,
            dialOrchestrator::dialSyncAuto,
            dialOrchestrator::disconnectSyncScheduled,
            () -> log("定时拨号触发", UiTheme.COLOR_INFO),
            () -> log("定时断开触发", UiTheme.COLOR_INFO),
            msg -> log(msg, UiTheme.COLOR_WARNING),
            backgroundExecutor
        );
    }

    /** Named DialOrchestrator.Host adapter (keeps init block readable). */
    private final class ShellDialHost extends service.AbstractDialHost {
        @Override public DialLifecycle lifecycle() { return dialLifecycle; }
        @Override public DialService dialService() { return dialService; }
        @Override public String connectionName() { return PPPOE_CONN_NAME; }
        @Override public String activeConnectionName() { return activeConnName; }
        @Override public java.util.function.BooleanSupplier isOnline() { return isOnline::get; }
        @Override public java.util.function.LongSupplier connectTimeMillis() {
            return sessionTraffic.connectTimeMillis()::get;
        }
        @Override public java.util.function.LongSupplier sessionTrafficBytes() {
            return sessionTraffic::sessionTrafficBytes;
        }
        @Override public java.util.function.Supplier<String> currentAccountName() {
            return accountSession::currentName;
        }
        @Override public AtomicLong totalDialCount() { return sessionTraffic.totalDialCount(); }
        @Override public AtomicLong successDialCount() { return sessionTraffic.successDialCount(); }
        @Override public boolean validateBeforeDialInteractive() { return validateBeforeDial(true); }
        @Override public boolean validateBeforeDialQuiet() { return validateBeforeDial(false); }
        @Override public DialSnapshot captureSnapshotFromUi() { return captureDialSnapshotOnEdt(); }
        @Override public void saveCurrentAccount() { PPoEDialer.this.saveCurrentAccount(); }
        @Override public void updateStatus(boolean online) { PPoEDialer.this.updateStatus(online); }
        @Override public void setDialControlsEnabled(boolean enabled) { updateButtonState(enabled); }
        @Override public void logInfo(String message) { log(message, UiTheme.COLOR_INFO); }
        @Override public void logSuccess(String message) { log(message, UiTheme.COLOR_SUCCESS); }
        @Override public void logWarning(String message) { log(message, UiTheme.COLOR_WARNING); }
        @Override public void logError(String message) { log(message, UiTheme.COLOR_ERROR); }
        @Override public void notifyUser(String title, String message) { showNotification(title, message); }
        @Override public void addHistory(String operation, String account, String result,
                                         String duration, String traffic) {
            addHistoryRecord(operation, account, result, duration, traffic);
        }
        @Override public void saveSettingsAfterSuccess() {
            invokeIfUiActive(PPoEDialer.this::saveSettings);
        }
        @Override public boolean isEventDispatchThread() {
            return SwingUtilities.isEventDispatchThread();
        }
        @Override public void runOnEdtAndWait(Runnable action) throws Exception {
            SwingUtilities.invokeAndWait(action);
        }
    }

    @SuppressWarnings("this-escape")
    public PPoEDialer() {
        super(APP_TITLE + " " + APP_VERSION);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        setLocationRelativeTo(null);
        setResizable(false);

        homePanel = createHomePanel();
        bindHomeFields();

        JPanel mainPanel = new JPanel(new java.awt.BorderLayout(0, 0));
        mainPanel.setBackground(UiTheme.COLOR_BG);
        mainPanel.add(homePanel.getStatusBar(), java.awt.BorderLayout.NORTH);
        mainPanel.add(createTabbedPane(), java.awt.BorderLayout.CENTER);
        setContentPane(mainPanel);

        trayController = new TrayController(APP_TITLE, new TrayController.Host() {
            @Override public void showWindow() { PPoEDialer.this.showWindow(); }
            @Override public void exitProgram() { PPoEDialer.this.exitProgram(); }
            @Override public boolean isOnline() { return isOnline.get(); }
            @Override public AccountInfo currentAccount() { return accountSession.currentOrNull(); }
            @Override public long connectTimeMillis() { return sessionTraffic.connectTimeMillis().get(); }
            @Override public long currentSpeedDown() { return sessionTraffic.currentSpeedDown().get(); }
            @Override public long currentSpeedUp() { return sessionTraffic.currentSpeedUp().get(); }
            @Override public long totalDownload() { return sessionTraffic.totalDownload().get(); }
            @Override public long totalUpload() { return sessionTraffic.totalUpload().get(); }
            @Override public long sessionStartDownload() { return sessionTraffic.sessionStartDownload().get(); }
            @Override public long sessionStartUpload() { return sessionTraffic.sessionStartUpload().get(); }
        }, msg -> log(msg, UiTheme.COLOR_ERROR));

        LegacyDataMigrator.migrateIfNeeded(PPoEDialer.class,
            ACCOUNTS_FILE, SETTINGS_FILE, HISTORY_FILE, LOG_FILE);
        loadSettings();
        accountSession.load();
        refreshAccountComboBox();
        refreshDialCredentialCache();
        // History CSV loaded on first tab open / addRecord / save (HistoryService.ensureLoaded)
        networkMonitorService.start();
        scheduleService.restart();
        restoreAutoReconnect();
        // PATH/command probes can spawn processes — keep off the first-paint critical path
        backgroundExecutor.submit(() -> StartupSelfCheck.run(
            new StartupSelfCheck.Logger() {
                @Override public void info(String message) { log(message, UiTheme.COLOR_INFO); }
                @Override public void warn(String message) { log(message, UiTheme.COLOR_WARNING); }
            },
            settingsStore.getFile(),
            accountStore.getFile(),
            historyStore.getFile(),
            runtimeSettings.probeSummaryLine()
        ));

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) { minimizeToTray(); }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                saveSettings();
                saveCurrentAccount();
                logService.flush();
                historyService.saveIfDirty();
            } catch (Exception ignored) { }
        }, "ShutdownHook"));

        SwingUtilities.invokeLater(() -> {
            if (trayController != null) trayController.init();
            if (homePanel.getChkStartMinimized().isSelected()) {
                setVisible(false);
            }
        });
    }

    // ==================== Tabs ====================

    private JTabbedPane createTabbedPane() {
        tabbedPane = new JTabbedPane();
        tabbedPane.setFont(UiTheme.FONT_CN);
        tabbedPane.addTab("主页", homePanel);
        tabbedPane.addTab("定时任务", createLazyPlaceholder("定时任务设置将在打开时加载..."));
        tabbedPane.addTab("网络探测", createLazyPlaceholder("网络探测设置将在打开时加载..."));
        historyPanel = createLazyPlaceholder("历史记录将在打开时加载...");
        tabbedPane.addTab("历史记录", historyPanel);
        diagPanel = createLazyPlaceholder("网络诊断工具将在打开时初始化...");
        tabbedPane.addTab("网络诊断", diagPanel);
        tabbedPane.addChangeListener(e -> ensureLazyTabContent());
        return tabbedPane;
    }

    private MainHomePanel createHomePanel() {
        return new MainHomePanel(new MainHomePanel.Host() {
            @Override public void onAccountSelected() { onAccountChanged(); }
            @Override public void openAccountManager() { PPoEDialer.this.openAccountManager(); }
            @Override public void onAutoReconnectToggled(boolean enabled) {
                if (enabled) startAutoReconnect();
                else stopAutoReconnect();
            }
            @Override public void onAutoStartToggled() { toggleAutoStart(); }
            @Override public void saveSettings() { PPoEDialer.this.saveSettings(); }
            @Override public void onDisconnectOnNoInternetToggled(boolean enabled) {
                runtimeSettings.setDisconnectOnNoInternet(enabled);
                if (dialOrchestrator != null) {
                    dialOrchestrator.setDisconnectOnNoInternet(enabled);
                }
                PPoEDialer.this.saveSettings();
            }
            @Override public void onDialToggle() {
                if (isOnline.get()) performDisconnect();
                else performDial();
            }
        }, logService);
    }

    private void bindHomeFields() {
        FocusAdapter cacheOnBlur = new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                refreshDialCredentialCache();
            }
        };
        homePanel.getTxtUsername().addFocusListener(cacheOnBlur);
        homePanel.getTxtPassword().addFocusListener(cacheOnBlur);
    }

    private JPanel createLazyPlaceholder(String text) {
        JPanel panel = new JPanel(new java.awt.BorderLayout());
        panel.setBackground(UiTheme.COLOR_BG);
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(UiTheme.FONT_CN);
        label.setForeground(UiTheme.COLOR_HINT);
        panel.add(label, java.awt.BorderLayout.CENTER);
        return panel;
    }

    private void ensureLazyTabContent() {
        if (tabbedPane == null || !isUiActive()) return;
        int index = tabbedPane.getSelectedIndex();
        if (index < 0) return;
        String title = tabbedPane.getTitleAt(index);
        if ("定时任务".equals(title) && !scheduleUiLoaded) {
            JPanel panel = createSchedulePanel();
            tabbedPane.setComponentAt(index, panel);
            scheduleUiLoaded = true;
        } else if ("网络探测".equals(title) && !probeUiLoaded) {
            JPanel panel = createProbeSettingsPanel();
            tabbedPane.setComponentAt(index, panel);
            probeUiLoaded = true;
        } else if ("历史记录".equals(title) && !historyLoaded) {
            if (historyService.dirtyFlag().get()) flushPendingPersistence();
            historyPanel = createHistoryPanel();
            tabbedPane.setComponentAt(index, historyPanel);
            historyService.bindTableFromMemory();
            historyLoaded = true;
        } else if ("网络诊断".equals(title) && !diagInitialized) {
            diagPanel = createDiagPanel();
            tabbedPane.setComponentAt(index, diagPanel);
            diagInitialized = true;
        } else if (!"网络诊断".equals(title) && diagPanelComponent != null) {
            flushPendingPersistence();
            diagPanelComponent.pauseBuffer();
        }
    }

    private JPanel createSchedulePanel() {
        schedulePanelUi = new SchedulePanel(new SchedulePanel.Host() {
            @Override
            public void onScheduleChanged() {
                syncScheduleCacheFromUi();
                scheduleService.restart();
            }

            @Override
            public void saveSettings() {
                PPoEDialer.this.saveSettings();
            }
        });
        // Fill from runtime (settings already loaded); do not sync UI→runtime empties
        AppSettings scheduleSnap = new AppSettings();
        runtimeSettings.writeScheduleTo(scheduleSnap);
        schedulePanelUi.applyFrom(scheduleSnap);
        return schedulePanelUi;
    }

    private JPanel createProbeSettingsPanel() {
        probeSettingsPanel = new ProbeSettingsPanel(new ProbeSettingsPanel.Host() {
            @Override
            public void onProbeSettingsChanged() {
                syncProbeFromUi();
            }

            @Override
            public void saveSettings() {
                PPoEDialer.this.saveSettings();
            }

            @Override
            public void runConnectivityTest(ConnectivityConfirm.Config config,
                                            Consumer<ProbeOutcome> onDone) {
                ConnectivityConfirm.Config cfg = config != null ? config : runtimeSettings.toProbeConfig();
                log("开始连通测试: mode=" + cfg.mode + " host=" + cfg.host, UiTheme.COLOR_INFO);
                backgroundExecutor.submit(() -> {
                    ProbeOutcome outcome;
                    try {
                        outcome = ConnectivityConfirm.confirmDetailed(cfg, "manual-test");
                    } catch (Exception ex) {
                        outcome = new ProbeOutcome(false, 0, cfg.mode, cfg.host, cfg.httpUrl,
                            cfg.attempts, "manual-test", System.currentTimeMillis());
                        log("连通测试异常: " + ex.getMessage(), UiTheme.COLOR_WARNING);
                    }
                    runtimeSettings.recordProbeOutcome(outcome);
                    final ProbeOutcome result = outcome;
                    SwingUtilities.invokeLater(() -> {
                        log("连通测试: " + result.shortLine(),
                            result.ok ? UiTheme.COLOR_SUCCESS : UiTheme.COLOR_ERROR);
                        if (onDone != null) onDone.accept(result);
                    });
                });
            }
        });
        probeSettingsPanel.applyFrom(
            runtimeSettings.getProbeMode(),
            runtimeSettings.getProbeHost(),
            runtimeSettings.getProbeHttpUrl(),
            runtimeSettings.getProbeAttempts(),
            runtimeSettings.getProbeDelayMs()
        );
        return probeSettingsPanel;
    }

    private void syncProbeFromUi() {
        if (probeSettingsPanel == null) return;
        runtimeSettings.setProbe(
            probeSettingsPanel.getProbeMode(),
            probeSettingsPanel.getProbeHost(),
            probeSettingsPanel.getProbeHttpUrl(),
            probeSettingsPanel.getProbeAttempts(),
            probeSettingsPanel.getProbeDelayMs()
        );
        if (dialOrchestrator != null) {
            dialOrchestrator.setProbeConfigSupplier(runtimeSettings::toProbeConfig);
        }
    }

    private void syncScheduleCacheFromUi() {
        if (schedulePanelUi == null) return;
        runtimeSettings.setSchedule(
            schedulePanelUi.isDialEnabled(),
            schedulePanelUi.dialHour(),
            schedulePanelUi.dialMinute(),
            schedulePanelUi.isDisconnectEnabled(),
            schedulePanelUi.disconnectHour(),
            schedulePanelUi.disconnectMinute()
        );
    }

    private JPanel createHistoryPanel() {
        return new HistoryPanel(
            historyService,
            this::isUiActive,
            (msg, color) -> log(msg, color),
            this
        );
    }

    private JPanel createDiagPanel() {
        diagPanelComponent = new DiagPanel(new DiagPanel.StatusSnapshot() {
            @Override public boolean isOnline() { return PPoEDialer.this.isOnline.get(); }
            @Override public AccountInfo currentAccount() { return accountSession.currentOrNull(); }
            @Override public long connectTimeMillis() { return sessionTraffic.connectTimeMillis().get(); }
            @Override public long totalDownload() { return sessionTraffic.totalDownload().get(); }
            @Override public long totalUpload() { return sessionTraffic.totalUpload().get(); }
            @Override public long speedDown() { return sessionTraffic.currentSpeedDown().get(); }
            @Override public long speedUp() { return sessionTraffic.currentSpeedUp().get(); }
            @Override public String phonebookReport() {
                return RasPhonebook.formatStatus(dialService.phonebook().snapshotStatus());
            }
            @Override public String probeReport() {
                return runtimeSettings.probeReportLine();
            }
            @Override public String formatBytes(long bytes) { return FormatUtil.formatBytes(bytes); }
            @Override public String formatSpeed(long bytesPerSec) { return FormatUtil.formatSpeed(bytesPerSec); }
            @Override public StringBuilder appendTime(StringBuilder sb, long totalSeconds) {
                return FormatUtil.appendTime(sb, totalSeconds);
            }
        }, this::isUiActive, backgroundExecutor);
        diagPanelComponent.resumeBuffer();
        return diagPanelComponent;
    }

    // ==================== Accounts ====================

    private void refreshAccountComboBox() {
        homePanel.getCmbAccounts().removeAllItems();
        for (AccountInfo a : accountSession.accounts()) {
            homePanel.getCmbAccounts().addItem(a.toString());
        }
        if (!accountSession.accounts().isEmpty()) {
            int idx = accountSession.currentIndex();
            if (idx < 0 || idx >= accountSession.accounts().size()) {
                accountSession.setCurrentIndex(0);
                idx = 0;
            }
            homePanel.getCmbAccounts().setSelectedIndex(idx);
        }
    }

    private void onAccountChanged() {
        int i = homePanel.getCmbAccounts().getSelectedIndex();
        if (i >= 0 && i < accountSession.accounts().size()) {
            accountSession.setCurrentIndex(i);
            accountSession.applyCurrentToUi(
                homePanel.getTxtConnectionName()::setText,
                homePanel.getTxtUsername()::setText,
                chars -> PasswordFields.setPassword(homePanel.getTxtPassword(), chars)
            );
            tooltipDirty = true;
            refreshDialCredentialCache();
        }
    }

    private void refreshDialCredentialCache() {
        if (dialOrchestrator == null) return;
        if (homePanel == null) return;
        char[] pw = homePanel.getTxtPassword().getPassword();
        try {
            dialOrchestrator.updateCredentials(homePanel.getTxtUsername().getText(), pw);
        } finally {
            PasswordChars.clear(pw);
        }
    }

    private void openAccountManager() {
        AccountManagerDialog.show(
            this,
            accountSession.accounts(),
            maxIdx -> accountSession.clampIndexAfterListChange(),
            () -> {
                accountSession.setDirty(false);
                refreshAccountComboBox();
            },
            () -> {
                accountSession.setDirty(true);
                accountSession.save();
                accountSession.setDirty(false);
            }
        );
        onAccountChanged();
    }

    private void saveCurrentAccount() {
        if (homePanel == null) return;
        char[] pw = homePanel.getTxtPassword().getPassword();
        accountSession.saveCurrentIfNeeded(
            homePanel.getTxtConnectionName().getText(),
            homePanel.getTxtUsername().getText(),
            pw
        );
    }

    // ==================== Tray / exit ====================

    private void minimizeToTray() { setVisible(false); }

    private void showWindow() {
        if (trayController != null) trayController.ensureReady();
        setVisible(true);
        setExtendedState(JFrame.NORMAL);
        toFront();
    }

    private void exitProgram() {
        saveSettings();
        saveCurrentAccount();
        historyService.saveIfDirty();
        logService.flush();
        stopAutoReconnect();
        scheduleService.stop();
        networkMonitorService.stop();
        if (dialOrchestrator != null) {
            dialOrchestrator.clearCredentials();
            dialOrchestrator.shutdown();
        }
        if (backgroundExecutor != null) backgroundExecutor.shutdown();
        accountSession.clearPasswordsInMemory();
        if (homePanel != null) homePanel.getTxtPassword().setText("");
        if (trayController != null) trayController.remove();
        dispose();
        System.exit(0);
    }

    private void flushPendingPersistence() {
        logService.flush();
        historyService.saveIfDirty();
    }

    private void addHistoryRecord(String operation, String account, String result, String duration, String totalTraffic) {
        historyService.addRecord(operation, account, result, duration, totalTraffic);
        if ((historyService.records().size() & 0x0F) == 0) flushPendingPersistence();
    }

    // ==================== Status / dial ====================

    private void updateStatus(boolean online) {
        boolean wasOnline = isOnline.getAndSet(online);
        if (online) {
            if (!wasOnline || sessionTraffic.connectTimeMillis().get() == 0) {
                sessionTraffic.markSessionStart();
            }
        } else {
            sessionTraffic.markOffline();
        }
        invokeIfUiActive(() -> {
            if (homePanel != null) homePanel.setOnlineStatus(online);
            if (trayController != null) {
                trayController.updateOnlineIcon(online);
                trayController.updateTooltip();
            }
        });
        updateToggleButton();
    }

    private DialSnapshot captureDialSnapshotOnEdt() {
        String user = homePanel.getTxtUsername().getText().trim();
        char[] pw = homePanel.getTxtPassword().getPassword();
        try {
            return new DialSnapshot(PPPOE_CONN_NAME, user, pw);
        } finally {
            PasswordChars.clear(pw);
        }
    }

    private void performDial() {
        refreshDialCredentialCache();
        dialOrchestrator.dialAsyncUser();
    }

    private void performDisconnect() {
        dialOrchestrator.disconnectAsyncUser();
    }

    private void startAutoReconnect() {
        startAutoReconnect(true);
    }

    private void startAutoReconnect(boolean dialImmediately) {
        if (autoReconnectService.isRunning()) return;
        refreshDialCredentialCache();
        autoReconnectService.start((int) homePanel.getSpnInterval().getValue(), dialImmediately);
    }

    private void stopAutoReconnect() {
        boolean wasRunning = autoReconnectService.isRunning();
        autoReconnectService.stop();
        if (!wasRunning) {
            SwingUtilities.invokeLater(() -> homePanel.getChkAutoReconnect().setSelected(false));
            return;
        }
        invokeIfUiActive(() -> homePanel.getChkAutoReconnect().setSelected(false));
    }

    private void restoreAutoReconnect() {
        if (!homePanel.getChkAutoReconnect().isSelected()) return;
        startAutoReconnect(true);
    }

    private boolean validateBeforeDial(boolean interactive) {
        if (isOnline.get()) {
            log("当前已连接，无需重复拨号", UiTheme.COLOR_INFO);
            return false;
        }
        if (accountSession.currentOrNull() == null) {
            log("当前账号索引无效，请重新选择账号", UiTheme.COLOR_ERROR);
            if (interactive) {
                JOptionPane.showMessageDialog(this, "当前账号无效，请重新选择账号", "拨号失败", JOptionPane.WARNING_MESSAGE);
            }
            return false;
        }
        String username = homePanel.getTxtUsername().getText().trim();
        char[] password = homePanel.getTxtPassword().getPassword();
        try {
            if (username.isEmpty()) {
                log("拨号前校验失败: 学号/账号为空", UiTheme.COLOR_WARNING);
                if (interactive) {
                    JOptionPane.showMessageDialog(this, "请输入学号/账号", "拨号失败", JOptionPane.WARNING_MESSAGE);
                }
                return false;
            }
            if (PasswordChars.isBlank(password)) {
                log("拨号前校验失败: 密码为空", UiTheme.COLOR_WARNING);
                if (interactive) {
                    JOptionPane.showMessageDialog(this, "请输入密码", "拨号失败", JOptionPane.WARNING_MESSAGE);
                }
                return false;
            }
            return true;
        } finally {
            PasswordChars.clear(password);
        }
    }

    private void updateButtonState(boolean enabled) {
        invokeIfUiActive(() -> {
            if (homePanel != null) homePanel.setDialEnabled(enabled);
        });
    }

    private void updateToggleButton() {
        invokeIfUiActive(() -> {
            if (homePanel != null) homePanel.setOnlineStatus(isOnline.get());
        });
    }

    private void showNotification(String title, String message) {
        if (!isUiActive()) return;
        if (trayController != null) trayController.displayMessage(title, message);
    }

    private void invokeIfUiActive(Runnable action) {
        SwingUtilities.invokeLater(() -> {
            if (!isUiActive()) return;
            action.run();
        });
    }

    private boolean isUiActive() {
        return isDisplayable();
    }

    private void log(String message, Color color) {
        logService.log(message, color);
    }

    // ==================== Autostart ====================

    private void toggleAutoStart() {
        if (homePanel.getChkAutoStart().isSelected()) startupService.enableAutoStart(PPoEDialer.class);
        else startupService.disableAutoStart();
        saveSettings();
    }

    // ==================== Settings ====================

    private void ensureSettingsCoordinator() {
        if (settingsCoordinator != null) return;
        settingsCoordinator = new SettingsCoordinator(
            settingsStore, runtimeSettings, accountSession, new SettingsCoordinator.Ui() {
                @Override public int intervalSeconds() { return (int) homePanel.getSpnInterval().getValue(); }
                @Override public void setIntervalSeconds(int seconds) { homePanel.getSpnInterval().setValue(seconds); }
                @Override public boolean autoReconnect() { return homePanel.getChkAutoReconnect().isSelected(); }
                @Override public void setAutoReconnect(boolean v) { homePanel.getChkAutoReconnect().setSelected(v); }
                @Override public boolean autoStartChecked() { return homePanel.getChkAutoStart().isSelected(); }
                @Override public void setAutoStartChecked(boolean v) { homePanel.getChkAutoStart().setSelected(v); }
                @Override public boolean startMinimized() { return homePanel.getChkStartMinimized().isSelected(); }
                @Override public void setStartMinimized(boolean v) { homePanel.getChkStartMinimized().setSelected(v); }
                @Override public boolean disconnectOnNoInternetChecked() {
                    return homePanel != null && homePanel.getChkDisconnectOnNoInternet().isSelected();
                }
                @Override public void setDisconnectOnNoInternetChecked(boolean v) {
                    if (homePanel != null) homePanel.getChkDisconnectOnNoInternet().setSelected(v);
                    runtimeSettings.setDisconnectOnNoInternet(v);
                    if (dialOrchestrator != null) dialOrchestrator.setDisconnectOnNoInternet(v);
                }
                @Override public void applyScheduleFrom(AppSettings s) {
                    if (schedulePanelUi != null) schedulePanelUi.applyFrom(s);
                }
                @Override public void writeScheduleTo(AppSettings s) {
                    if (schedulePanelUi != null) schedulePanelUi.writeTo(s);
                    else runtimeSettings.writeScheduleTo(s);
                }
                @Override public void syncScheduleCacheFromUi() { PPoEDialer.this.syncScheduleCacheFromUi(); }
                @Override public void syncProbeFromUi() { PPoEDialer.this.syncProbeFromUi(); }
                @Override public void applyProbeFromRuntime() {
                    if (probeSettingsPanel != null) {
                        probeSettingsPanel.applyFrom(
                            runtimeSettings.getProbeMode(),
                            runtimeSettings.getProbeHost(),
                            runtimeSettings.getProbeHttpUrl(),
                            runtimeSettings.getProbeAttempts(),
                            runtimeSettings.getProbeDelayMs()
                        );
                    }
                    if (homePanel != null) {
                        homePanel.getChkDisconnectOnNoInternet().setSelected(runtimeSettings.isDisconnectOnNoInternet());
                    }
                    if (dialOrchestrator != null) {
                        dialOrchestrator.setProbeConfigSupplier(runtimeSettings::toProbeConfig);
                        dialOrchestrator.setDisconnectOnNoInternet(runtimeSettings.isDisconnectOnNoInternet());
                    }
                }
                @Override public boolean isAutoStartEnabledInRegistry() {
                    return startupService.isAutoStartEnabled();
                }
                @Override public boolean ensureAutoStartHealthy() {
                    return startupService.ensureAutoStartHealthy(PPoEDialer.class, true);
                }
                @Override public void onLoadWarning(String message) { log(message, UiTheme.COLOR_WARNING); }
                @Override public void onSaveError(String message) { log(message, UiTheme.COLOR_ERROR); }
                @Override public void onAutostartLegacyWarning(String message) { log(message, UiTheme.COLOR_WARNING); }
            });
    }

    private void saveSettings() {
        ensureSettingsCoordinator();
        settingsCoordinator.save();
    }

    private void loadSettings() {
        ensureSettingsCoordinator();
        settingsCoordinator.load();
    }

    // ==================== main ====================

    public static void main(String[] args) {
        LookAndFeelInstaller.install();

        final boolean fromAutostart = StartupService.argsContainAutostart(args);
        if (fromAutostart && StartupService.AUTOSTART_DELAY_MS > 0) {
            try {
                Thread.sleep(StartupService.AUTOSTART_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        SwingUtilities.invokeLater(() -> {
            PPoEDialer dialer = new PPoEDialer();
            if (!dialer.homePanel.getChkStartMinimized().isSelected()) {
                dialer.setVisible(true);
            }
            dialer.log("PPPoE校园网拨号工具 " + APP_VERSION + " 已启动", UiTheme.COLOR_SUCCESS);
            if (fromAutostart) {
                dialer.log("通过开机自启动启动 (延迟 "
                    + (StartupService.AUTOSTART_DELAY_MS / 1000) + "s)", UiTheme.COLOR_INFO);
            }
            if (CryptoUtil.isKeyDpapiProtected()) {
                dialer.log("主密钥已使用 Windows DPAPI 保护", UiTheme.COLOR_INFO);
            }
            dialer.log("作者：Lexo0522", UiTheme.COLOR_INFO);
            dialer.log("仓库：https://github.com/Lexo0522/one-key-dialer", UiTheme.COLOR_INFO);
        });
    }
}
