package ui;

import model.AppSettings;
import model.AccountInfo;
import service.BackgroundExecutor;
import service.DialService;
import service.HistoryService;
import service.RasPhonebook;
import service.RuntimeSettings;
import service.ScheduleService;
import util.ConnectivityConfirm;
import util.FormatUtil;
import util.ProbeOutcome;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Lazy secondary tabs (schedule / probe / history / stats / diag).
 */
public final class MainTabsController {
    public interface Host {
        MainHomePanel homePanel();

        HistoryService historyService();

        RuntimeSettings runtimeSettings();

        ScheduleService scheduleService();

        DialService dialService();

        BackgroundExecutor backgroundExecutor();

        BooleanSupplier isOnline();

        AccountInfo currentAccount();

        long connectTimeMillis();

        long totalDownload();

        long totalUpload();

        long speedDown();

        long speedUp();

        boolean isUiActive();

        void flushPendingPersistence();

        void saveSettings();

        void log(String message, java.awt.Color color);

        void syncScheduleCacheFromUi();

        void syncProbeFromUi(ProbeSettingsPanel panel);

        JFrame frame();
    }

    private final Host host;
    private final JTabbedPane tabbedPane = new JTabbedPane();
    private SchedulePanel schedulePanelUi;
    private ProbeSettingsPanel probeSettingsPanel;
    private StatsPanel statsPanel;
    private DiagPanel diagPanelComponent;
    private JPanel historyPanel;
    private JPanel diagPanel;
    private boolean historyLoaded;
    private boolean diagInitialized;
    private boolean scheduleUiLoaded;
    private boolean probeUiLoaded;
    private boolean statsUiLoaded;

    public MainTabsController(Host host) {
        this.host = host;
        tabbedPane.setFont(UiTheme.FONT_CN);
        tabbedPane.addTab("主页", host.homePanel());
        tabbedPane.addTab("定时任务", lazyPlaceholder("定时任务设置将在打开时加载..."));
        tabbedPane.addTab("网络探测", lazyPlaceholder("网络探测设置将在打开时加载..."));
        historyPanel = lazyPlaceholder("历史记录将在打开时加载...");
        tabbedPane.addTab("历史记录", historyPanel);
        tabbedPane.addTab("统计", lazyPlaceholder("拨号统计将在打开时加载..."));
        diagPanel = lazyPlaceholder("网络诊断工具将在打开时初始化...");
        tabbedPane.addTab("网络诊断", diagPanel);
        tabbedPane.addChangeListener(e -> ensureLazyTabContent());
    }

    public JTabbedPane getTabbedPane() {
        return tabbedPane;
    }

    public SchedulePanel schedulePanel() {
        return schedulePanelUi;
    }

    public ProbeSettingsPanel probePanel() {
        return probeSettingsPanel;
    }

    public void ensureLazyTabContent() {
        if (!host.isUiActive()) return;
        int index = tabbedPane.getSelectedIndex();
        if (index < 0) return;
        String title = tabbedPane.getTitleAt(index);
        if ("定时任务".equals(title) && !scheduleUiLoaded) {
            tabbedPane.setComponentAt(index, createSchedulePanel());
            scheduleUiLoaded = true;
        } else if ("网络探测".equals(title) && !probeUiLoaded) {
            tabbedPane.setComponentAt(index, createProbeSettingsPanel());
            probeUiLoaded = true;
        } else if ("历史记录".equals(title) && !historyLoaded) {
            if (host.historyService().dirtyFlag().get()) host.flushPendingPersistence();
            historyPanel = new HistoryPanel(
                host.historyService(),
                host::isUiActive,
                (msg, color) -> host.log(msg, color),
                host.frame()
            );
            tabbedPane.setComponentAt(index, historyPanel);
            host.historyService().bindTableFromMemory();
            historyLoaded = true;
        } else if ("统计".equals(title) && !statsUiLoaded) {
            host.historyService().ensureLoaded();
            statsPanel = new StatsPanel(host.historyService(),
                msg -> host.log(msg, UiTheme.COLOR_INFO));
            tabbedPane.setComponentAt(index, statsPanel);
            statsUiLoaded = true;
        } else if ("网络诊断".equals(title) && !diagInitialized) {
            diagPanel = createDiagPanel();
            tabbedPane.setComponentAt(index, diagPanel);
            diagInitialized = true;
        } else if (!"网络诊断".equals(title) && diagPanelComponent != null) {
            host.flushPendingPersistence();
            diagPanelComponent.pauseBuffer();
        }
    }

    private JPanel createSchedulePanel() {
        schedulePanelUi = new SchedulePanel(new SchedulePanel.Host() {
            @Override
            public void onScheduleChanged() {
                host.syncScheduleCacheFromUi();
                host.scheduleService().restart();
            }

            @Override
            public void saveSettings() {
                host.saveSettings();
            }
        });
        AppSettings scheduleSnap = new AppSettings();
        host.runtimeSettings().writeScheduleTo(scheduleSnap);
        schedulePanelUi.applyFrom(scheduleSnap);
        return schedulePanelUi;
    }

    private JPanel createProbeSettingsPanel() {
        probeSettingsPanel = new ProbeSettingsPanel(new ProbeSettingsPanel.Host() {
            @Override
            public void onProbeSettingsChanged() {
                host.syncProbeFromUi(probeSettingsPanel);
            }

            @Override
            public void saveSettings() {
                host.saveSettings();
            }

            @Override
            public void runConnectivityTest(ConnectivityConfirm.Config config,
                                            Consumer<ProbeOutcome> onDone) {
                ConnectivityConfirm.Config cfg = config != null
                    ? config : host.runtimeSettings().toProbeConfig();
                host.log("开始连通测试: mode=" + cfg.mode + " host=" + cfg.host, UiTheme.COLOR_INFO);
                host.backgroundExecutor().submit(() -> {
                    ProbeOutcome outcome;
                    try {
                        outcome = ConnectivityConfirm.confirmDetailed(cfg, "manual-test");
                    } catch (Exception ex) {
                        outcome = new ProbeOutcome(false, 0, cfg.mode, cfg.host, cfg.httpUrl,
                            cfg.attempts, "manual-test", System.currentTimeMillis());
                        host.log("连通测试异常: " + ex.getMessage(), UiTheme.COLOR_WARNING);
                    }
                    host.runtimeSettings().recordProbeOutcome(outcome);
                    final ProbeOutcome result = outcome;
                    SwingUtilities.invokeLater(() -> {
                        host.log("连通测试: " + result.shortLine(),
                            result.ok ? UiTheme.COLOR_SUCCESS : UiTheme.COLOR_ERROR);
                        if (onDone != null) onDone.accept(result);
                    });
                });
            }
        });
        RuntimeSettings rs = host.runtimeSettings();
        probeSettingsPanel.applyFrom(
            rs.getProbeMode(), rs.getProbeHost(), rs.getProbeHttpUrl(),
            rs.getProbeAttempts(), rs.getProbeDelayMs()
        );
        return probeSettingsPanel;
    }

    private JPanel createDiagPanel() {
        diagPanelComponent = new DiagPanel(new DiagPanel.StatusSnapshot() {
            @Override public boolean isOnline() { return host.isOnline().getAsBoolean(); }
            @Override public AccountInfo currentAccount() { return host.currentAccount(); }
            @Override public long connectTimeMillis() { return host.connectTimeMillis(); }
            @Override public long totalDownload() { return host.totalDownload(); }
            @Override public long totalUpload() { return host.totalUpload(); }
            @Override public long speedDown() { return host.speedDown(); }
            @Override public long speedUp() { return host.speedUp(); }
            @Override public String phonebookReport() {
                return RasPhonebook.formatStatus(host.dialService().phonebook().snapshotStatus());
            }
            @Override public String probeReport() {
                return host.runtimeSettings().probeReportLine();
            }
            @Override public java.util.List<RasPhonebook.DeviceHint> listPppoeDevices() {
                return host.dialService().phonebook().listDeviceOptions();
            }
            @Override public String applyPppoeDevice(RasPhonebook.DeviceHint hint, boolean rewrite) {
                if (hint != null) {
                    host.dialService().phonebook().setPreferredDevice(hint);
                }
                if (rewrite) {
                    boolean ok = host.dialService().phonebook().rewriteEntry();
                    return ok
                        ? "电话簿条目已重写"
                            + (hint != null ? (" → " + hint.device + " / " + hint.port) : "")
                        : "电话簿重写失败，请查看运行日志";
                }
                if (hint != null) {
                    return "已记住设备 " + hint.device + " / " + hint.port + "（下次创建条目时使用）";
                }
                return "未更改";
            }
            @Override public String formatBytes(long bytes) { return FormatUtil.formatBytes(bytes); }
            @Override public String formatSpeed(long bytesPerSec) {
                return FormatUtil.formatSpeed(bytesPerSec);
            }
            @Override public StringBuilder appendTime(StringBuilder sb, long totalSeconds) {
                return FormatUtil.appendTime(sb, totalSeconds);
            }
        }, host::isUiActive, host.backgroundExecutor());
        diagPanelComponent.resumeBuffer();
        return diagPanelComponent;
    }

    private static JPanel lazyPlaceholder(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(UiTheme.COLOR_BG);
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(UiTheme.FONT_CN);
        label.setForeground(UiTheme.COLOR_HINT);
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }
}
