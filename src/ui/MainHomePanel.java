package ui;

import i18n.Messages;
import model.SettingsSnapshot;
import service.LogService;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Main tab content (account / config / options / log / dial) plus a detachable status bar.
 */
public class MainHomePanel extends JPanel {
    public interface Host {
        void onAccountSelected();

        void openAccountManager();

        void onAutoReconnectToggled(boolean enabled);

        void onAutoStartToggled();

        void saveSettings();

        void onDialToggle();

        /** Optional: no-internet disconnect policy changed. */
        default void onDisconnectOnNoInternetToggled(boolean enabled) {
            saveSettings();
        }

        /** Optional: startup quiet update-check policy changed. */
        default void onUpdateCheckToggled(boolean enabled) {
            saveSettings();
        }
    }

    private static final int WINDOW_WIDTH = 580;
    private static final int DEFAULT_INTERVAL = 30;

    private final Host host;
    private final JPanel statusBar = new JPanel(new BorderLayout());

    private final JComboBox<String> cmbAccounts = new JComboBox<>();
    private final JComboBox<String> cmbTheme = new JComboBox<>(new String[]{
        Messages.get("theme.system"), Messages.get("theme.light"), Messages.get("theme.dark")});
    private final JTextField txtConnectionName = new JTextField(20);
    private final JTextField txtUsername = new JTextField(20);
    private final JPasswordField txtPassword = new JPasswordField(20);
    private final JSpinner spnInterval = new JSpinner(new SpinnerNumberModel(DEFAULT_INTERVAL, 5, 3600, 5));
    private final JCheckBox chkAutoReconnect = new JCheckBox(Messages.get("home.autoReconnect"));
    private final JCheckBox chkAutoStart = new JCheckBox(Messages.get("home.autoStart"));
    private final JCheckBox chkStartMinimized = new JCheckBox(Messages.get("home.startMinimized"));
    private final JCheckBox chkDisconnectOnNoInternet = new JCheckBox(Messages.get("home.disconnectNoInternet"));
    private final JCheckBox chkUpdateCheck = new JCheckBox(Messages.get("home.updateCheck"));
    private final JTextPane logPane = new JTextPane();
    private final JButton btnDial;
    private final JLabel lblStatus = new JLabel(Messages.get("home.status.disconnected"));
    private final JLabel lblStatusDot = new JLabel("●");
    private final JLabel lblSpeed = new JLabel("↓ -- ↑ --");
    private final JLabel lblUptime = new JLabel("时长: --");

    public MainHomePanel(Host host, LogService logService) {
        super(new BorderLayout(0, 8));
        this.host = host;
        setBackground(UiTheme.COLOR_BG);
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        buildStatusBar();
        add(buildCenter(), BorderLayout.CENTER);

        btnDial = createStyledButton(Messages.get("home.dial.connect"), UiTheme.COLOR_INFO);
        btnDial.setPreferredSize(new Dimension(300, 45));
        btnDial.addActionListener(e -> host.onDialToggle());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 5));
        south.setBackground(UiTheme.COLOR_BG);
        south.add(btnDial);
        add(south, BorderLayout.SOUTH);

        logPane.setEditable(false);
        logPane.setBackground(UiTheme.COLOR_DARK);
        // CJK-capable font — the log stream is Chinese; monospace western fonts tofu it.
        logPane.setFont(UiTheme.FONT_DIAG);
        logPane.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        StyledDocument doc = logPane.getStyledDocument();
        logService.attach(logPane, doc);

        wireFields();
    }

    /** Always-visible top status strip (place on frame NORTH). */
    public JPanel getStatusBar() {
        return statusBar;
    }

    private void buildStatusBar() {
        statusBar.setBackground(UiTheme.COLOR_INFO);
        statusBar.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        lblStatusDot.setFont(new Font("Arial", Font.BOLD, 16));
        lblStatusDot.setForeground(Color.WHITE);
        lblStatus.setFont(UiTheme.FONT_CN_BOLD);
        lblStatus.setForeground(Color.WHITE);
        left.add(lblStatusDot);
        left.add(lblStatus);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 0));
        right.setOpaque(false);
        lblSpeed.setFont(UiTheme.FONT_CN_SMALL);
        lblSpeed.setForeground(new Color(255, 255, 255, 200));
        lblUptime.setFont(UiTheme.FONT_CN_SMALL);
        lblUptime.setForeground(new Color(255, 255, 255, 200));
        right.add(lblSpeed);
        right.add(lblUptime);

        statusBar.add(left, BorderLayout.WEST);
        statusBar.add(right, BorderLayout.EAST);
    }

    private void wireFields() {
        cmbAccounts.setFont(UiTheme.FONT_CN);
        cmbAccounts.setPreferredSize(new Dimension(200, 30));
        cmbAccounts.addActionListener(e -> host.onAccountSelected());

        txtConnectionName.setFont(UiTheme.FONT_CN);
        txtUsername.setFont(UiTheme.FONT_CN);
        txtPassword.setFont(UiTheme.FONT_CN);
        spnInterval.setFont(UiTheme.FONT_CN);

        chkAutoReconnect.setFont(UiTheme.FONT_CN);
        chkAutoReconnect.addActionListener(e -> {
            host.onAutoReconnectToggled(chkAutoReconnect.isSelected());
            host.saveSettings();
        });
        chkAutoStart.setFont(UiTheme.FONT_CN);
        chkAutoStart.setToolTipText("以 Windows 注册表 Run 项为准；设置中 auto.start 仅用于启动时修复注册");
        // Host.toggle saves settings; avoid double saveSettings here
        chkAutoStart.addActionListener(e -> host.onAutoStartToggled());
        chkStartMinimized.setFont(UiTheme.FONT_CN);
        chkStartMinimized.setSelected(false);
        chkStartMinimized.addActionListener(e -> host.saveSettings());
        chkDisconnectOnNoInternet.setFont(UiTheme.FONT_CN);
        chkDisconnectOnNoInternet.setToolTipText(
            "拨号 RAS 成功但外网探测失败时自动 rasdial 断开；默认关闭以保留校园内网");
        chkDisconnectOnNoInternet.addActionListener(e ->
            host.onDisconnectOnNoInternetToggled(chkDisconnectOnNoInternet.isSelected()));
        chkUpdateCheck.setFont(UiTheme.FONT_CN);
        chkUpdateCheck.setSelected(true);
        chkUpdateCheck.setToolTipText(
            "启动数秒后静默查询 GitHub Releases；关闭后仍可在托盘「检查更新」手动检查");
        chkUpdateCheck.addActionListener(e ->
            host.onUpdateCheckToggled(chkUpdateCheck.isSelected()));
        cmbTheme.setFont(UiTheme.FONT_CN);
        cmbTheme.setToolTipText("重启应用后生效");
        cmbTheme.addActionListener(e -> host.saveSettings());
    }

    private JPanel buildCenter() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(UiTheme.COLOR_BG);
        panel.add(buildAccountRow());
        panel.add(Box.createVerticalStrut(5));
        panel.add(buildConfigPanel());
        panel.add(Box.createVerticalStrut(5));
        panel.add(buildOptionPanel());
        panel.add(Box.createVerticalStrut(5));
        panel.add(buildLogPanel());
        return panel;
    }

    private JPanel buildAccountRow() {
        JPanel panel = new JPanel(new BorderLayout(5, 0));
        panel.setBackground(UiTheme.COLOR_CARD);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UiTheme.COLOR_BORDER),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        JButton btnAccountConfig = new JButton(Messages.get("home.account.config"));
        btnAccountConfig.setFont(UiTheme.FONT_CN);
        btnAccountConfig.addActionListener(e -> host.openAccountManager());
        JLabel lbl = new JLabel(Messages.get("home.account.label"));
        lbl.setFont(UiTheme.FONT_CN);
        panel.add(lbl, BorderLayout.WEST);
        panel.add(cmbAccounts, BorderLayout.CENTER);
        panel.add(btnAccountConfig, BorderLayout.EAST);
        return panel;
    }

    private JPanel buildConfigPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(UiTheme.COLOR_CARD);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UiTheme.COLOR_BORDER),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 5, 4, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        addLabeled(panel, gbc, row++, Messages.get("home.nickname.label"), txtConnectionName);
        addLabeled(panel, gbc, row++, Messages.get("home.username.label"), txtUsername);
        addLabeled(panel, gbc, row++, Messages.get("home.password.label"), txtPassword);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
        JLabel n4 = new JLabel(Messages.get("home.interval.label"));
        n4.setFont(UiTheme.FONT_CN);
        panel.add(n4, gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(spnInterval, gbc);
        return panel;
    }

    private static void addLabeled(JPanel panel, GridBagConstraints gbc, int row, String label, JComponent field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
        JLabel n = new JLabel(label);
        n.setFont(UiTheme.FONT_CN);
        panel.add(n, gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        panel.add(field, gbc);
    }

    private JPanel buildOptionPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(UiTheme.COLOR_CARD);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UiTheme.COLOR_BORDER),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        JPanel themeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        themeRow.setOpaque(false);
        JLabel themeLabel = new JLabel(Messages.get("theme.label") + " ");
        themeLabel.setFont(UiTheme.FONT_CN);
        themeRow.add(themeLabel);
        themeRow.add(cmbTheme);
        JLabel themeHint = new JLabel(Messages.get("theme.restartHint"));
        themeHint.setFont(UiTheme.FONT_CN_SMALL);
        themeHint.setForeground(UiTheme.COLOR_HINT);
        themeRow.add(themeHint);
        panel.add(wrapLeft(themeRow));

        panel.add(wrapLeft(chkAutoReconnect));
        panel.add(wrapLeft(chkAutoStart));
        panel.add(wrapLeft(chkStartMinimized));
        panel.add(wrapLeft(chkDisconnectOnNoInternet));
        panel.add(wrapLeft(chkUpdateCheck));
        JLabel autostartHint = new JLabel(Messages.get("home.autostartHint"));
        autostartHint.setFont(UiTheme.FONT_CN_SMALL);
        autostartHint.setForeground(UiTheme.COLOR_HINT);
        panel.add(wrapLeft(autostartHint));
        return panel;
    }

    private static JPanel wrapLeft(JComponent c) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        p.setOpaque(false);
        p.add(c);
        return p;
    }

    private JPanel buildLogPanel() {
        JScrollPane sp = new JScrollPane(logPane);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setPreferredSize(new Dimension(WINDOW_WIDTH - 30, 100));
        sp.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        sp.getVerticalScrollBar().setUnitIncrement(16);

        JPanel wrapper = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(WINDOW_WIDTH - 30, 140);
            }
        };
        wrapper.setBackground(UiTheme.COLOR_CARD);
        wrapper.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UiTheme.COLOR_BORDER_LIGHT),
            BorderFactory.createTitledBorder(
                BorderFactory.createEmptyBorder(5, 5, 0, 5),
                Messages.get("home.log.title"),
                TitledBorder.LEFT,
                TitledBorder.TOP,
                UiTheme.FONT_CN_BOLD,
                new Color(100, 100, 100))));
        wrapper.add(sp, BorderLayout.CENTER);
        return wrapper;
    }

    private static JButton createStyledButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(UiTheme.FONT_CN_BOLD);
        btn.setPreferredSize(new Dimension(140, 40));
        btn.setBackground(bg);
        btn.setForeground(Color.BLACK);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(true);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            final Color original = bg;

            public void mouseEntered(MouseEvent e) {
                btn.setBackground(bg.darker());
            }

            public void mouseExited(MouseEvent e) {
                btn.setBackground(original);
            }
        });
        return btn;
    }

    /** Read option controls into the builder (EDT). */
    public void captureSettings(SettingsSnapshot.Builder builder) {
        builder.intervalSeconds((Integer) spnInterval.getValue())
            .autoReconnect(chkAutoReconnect.isSelected())
            .autoStart(chkAutoStart.isSelected())
            .startMinimized(chkStartMinimized.isSelected())
            .disconnectOnNoInternet(chkDisconnectOnNoInternet.isSelected())
            .updateCheckEnabled(chkUpdateCheck.isSelected())
            .uiTheme(selectedTheme());
    }

    /** Write option controls from the snapshot (EDT). */
    public void applySettings(SettingsSnapshot s) {
        if (s == null) return;
        spnInterval.setValue(Math.max(SettingsSnapshot.MIN_INTERVAL_SECONDS, s.intervalSeconds));
        chkAutoReconnect.setSelected(s.autoReconnect);
        chkAutoStart.setSelected(s.autoStart);
        chkStartMinimized.setSelected(s.startMinimized);
        chkDisconnectOnNoInternet.setSelected(s.disconnectOnNoInternet);
        chkUpdateCheck.setSelected(s.updateCheckEnabled);
        applyThemeSelection(s.uiTheme);
    }

    private String selectedTheme() {
        switch (cmbTheme.getSelectedIndex()) {
            case 1: return SettingsSnapshot.THEME_LIGHT;
            case 2: return SettingsSnapshot.THEME_DARK;
            default: return SettingsSnapshot.THEME_SYSTEM;
        }
    }

    private void applyThemeSelection(String theme) {
        if (SettingsSnapshot.THEME_LIGHT.equals(theme)) {
            cmbTheme.setSelectedIndex(1);
        } else if (SettingsSnapshot.THEME_DARK.equals(theme)) {
            cmbTheme.setSelectedIndex(2);
        } else {
            cmbTheme.setSelectedIndex(0);
        }
    }

    public void setOnlineStatus(boolean online) {
        if (online) {
            lblStatus.setText(Messages.get("home.status.connected"));
            lblStatusDot.setForeground(Color.WHITE);
            statusBar.setBackground(new Color(22, 163, 74));
            btnDial.setText(Messages.get("home.dial.disconnect"));
            btnDial.setBackground(UiTheme.COLOR_ERROR);
        } else {
            lblStatus.setText(Messages.get("home.status.disconnected"));
            lblStatusDot.setForeground(Color.WHITE);
            statusBar.setBackground(UiTheme.COLOR_INFO);
            btnDial.setText(Messages.get("home.dial.connect"));
            btnDial.setBackground(UiTheme.COLOR_INFO);
            lblSpeed.setText("↓ -- ↑ --");
            lblUptime.setText("时长: 未连接");
        }
        btnDial.setEnabled(true);
        btnDial.repaint();
    }

    /**
     * Busy state for the main dial button (disabled + phase label).
     * @param label e.g. {@code 连接中…} / {@code 断开中…}
     * @param bg button background while busy
     */
    public void setDialProgress(String label, Color bg) {
        btnDial.setEnabled(false);
        if (label != null) btnDial.setText(label);
        if (bg != null) btnDial.setBackground(bg);
        btnDial.repaint();
    }

    public void setDialEnabled(boolean enabled) {
        btnDial.setEnabled(enabled);
    }

    public void setSpeedText(String text) {
        lblSpeed.setText(text);
    }

    public void setUptimeText(String text) {
        lblUptime.setText(text);
    }

    public JComboBox<String> getCmbAccounts() {
        return cmbAccounts;
    }

    public JTextField getTxtConnectionName() {
        return txtConnectionName;
    }

    public JTextField getTxtUsername() {
        return txtUsername;
    }

    public JPasswordField getTxtPassword() {
        return txtPassword;
    }

    public JSpinner getSpnInterval() {
        return spnInterval;
    }

    public JCheckBox getChkAutoReconnect() {
        return chkAutoReconnect;
    }

    public JCheckBox getChkAutoStart() {
        return chkAutoStart;
    }

    public JCheckBox getChkStartMinimized() {
        return chkStartMinimized;
    }

    public JCheckBox getChkDisconnectOnNoInternet() {
        return chkDisconnectOnNoInternet;
    }

    public JCheckBox getChkUpdateCheck() {
        return chkUpdateCheck;
    }

    public JButton getBtnDial() {
        return btnDial;
    }
}
