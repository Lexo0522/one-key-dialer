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
public class MainHomePanel extends JPanel implements Themeable {
    public interface Host {
        void onAccountSelected();

        void openAccountManager();

        void onAutoReconnectToggled(boolean enabled);

        void onAutoStartToggled();

        void saveSettings();

        void onDialToggle();

        /** Optional: live-apply the theme picked in {@code cmbTheme} (EDT). */
        default void onThemeSelected(String theme) {
        }

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
    /** Hover base color of styled buttons; updated on state changes (see setOnlineStatus). */
    private static final String BUTTON_BASE_COLOR = "ppoe.buttonBaseColor";

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

    private JPanel southPanel;
    private JPanel centerPanel;
    private JPanel accountRow;
    private JPanel configPanel;
    private JPanel optionPanel;
    private JPanel logWrapper;
    private JLabel themeHintLabel;
    private JLabel autostartHintLabel;

    /** Dial-control state, replayed by {@link #restyle()} after a theme switch. */
    private boolean online;
    private boolean dialBusy;
    private String dialProgressLabel;

    public MainHomePanel(Host host, LogService logService) {
        super(new BorderLayout(0, 8));
        this.host = host;
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        buildStatusBar();
        add(buildCenter(), BorderLayout.CENTER);

        btnDial = createStyledButton(Messages.get("home.dial.connect"), UiTheme.COLOR_INFO);
        btnDial.setPreferredSize(new Dimension(300, 45));
        btnDial.addActionListener(e -> host.onDialToggle());
        southPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 5));
        southPanel.add(btnDial);
        add(southPanel, BorderLayout.SOUTH);

        logPane.setEditable(false);
        // CJK-capable font — the log stream is Chinese; monospace western fonts tofu it.
        logPane.setFont(UiTheme.FONT_DIAG);
        logPane.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        StyledDocument doc = logPane.getStyledDocument();
        logService.attach(logPane, doc);

        wireFields();
        restyle();
    }

    /** Always-visible top status strip (place on frame NORTH). */
    public JPanel getStatusBar() {
        return statusBar;
    }

    private void buildStatusBar() {
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
        cmbTheme.addActionListener(e -> {
            host.saveSettings();
            host.onThemeSelected(selectedTheme());
        });
    }

    private JPanel buildCenter() {
        centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.add(buildAccountRow());
        centerPanel.add(Box.createVerticalStrut(5));
        centerPanel.add(buildConfigPanel());
        centerPanel.add(Box.createVerticalStrut(5));
        centerPanel.add(buildOptionPanel());
        centerPanel.add(Box.createVerticalStrut(5));
        centerPanel.add(buildLogPanel());
        return centerPanel;
    }

    private JPanel buildAccountRow() {
        accountRow = new JPanel(new BorderLayout(5, 0));
        JButton btnAccountConfig = new JButton(Messages.get("home.account.config"));
        btnAccountConfig.setFont(UiTheme.FONT_CN);
        btnAccountConfig.addActionListener(e -> host.openAccountManager());
        JLabel lbl = new JLabel(Messages.get("home.account.label"));
        lbl.setFont(UiTheme.FONT_CN);
        accountRow.add(lbl, BorderLayout.WEST);
        accountRow.add(cmbAccounts, BorderLayout.CENTER);
        accountRow.add(btnAccountConfig, BorderLayout.EAST);
        return accountRow;
    }

    private JPanel buildConfigPanel() {
        configPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 5, 4, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        addLabeled(configPanel, gbc, row++, Messages.get("home.nickname.label"), txtConnectionName);
        addLabeled(configPanel, gbc, row++, Messages.get("home.username.label"), txtUsername);
        addLabeled(configPanel, gbc, row++, Messages.get("home.password.label"), txtPassword);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
        JLabel n4 = new JLabel(Messages.get("home.interval.label"));
        n4.setFont(UiTheme.FONT_CN);
        configPanel.add(n4, gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        configPanel.add(spnInterval, gbc);
        return configPanel;
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
        optionPanel = new JPanel();
        optionPanel.setLayout(new BoxLayout(optionPanel, BoxLayout.Y_AXIS));

        JPanel themeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        themeRow.setOpaque(false);
        JLabel themeLabel = new JLabel(Messages.get("theme.label") + " ");
        themeLabel.setFont(UiTheme.FONT_CN);
        themeRow.add(themeLabel);
        themeRow.add(cmbTheme);
        themeHintLabel = new JLabel(Messages.get("theme.liveHint"));
        themeHintLabel.setFont(UiTheme.FONT_CN_SMALL);
        themeRow.add(themeHintLabel);
        optionPanel.add(wrapLeft(themeRow));

        optionPanel.add(wrapLeft(chkAutoReconnect));
        optionPanel.add(wrapLeft(chkAutoStart));
        optionPanel.add(wrapLeft(chkStartMinimized));
        optionPanel.add(wrapLeft(chkDisconnectOnNoInternet));
        optionPanel.add(wrapLeft(chkUpdateCheck));
        autostartHintLabel = new JLabel(Messages.get("home.autostartHint"));
        autostartHintLabel.setFont(UiTheme.FONT_CN_SMALL);
        optionPanel.add(wrapLeft(autostartHintLabel));
        return optionPanel;
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

        logWrapper = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(WINDOW_WIDTH - 30, 140);
            }
        };
        logWrapper.add(sp, BorderLayout.CENTER);
        return logWrapper;
    }

    /** Re-apply every themed color from {@link UiTheme} (EDT). */
    @Override
    public void restyle() {
        setBackground(UiTheme.COLOR_BG);
        if (southPanel != null) southPanel.setBackground(UiTheme.COLOR_BG);
        if (centerPanel != null) centerPanel.setBackground(UiTheme.COLOR_BG);
        logPane.setBackground(UiTheme.COLOR_CONSOLE_BG);
        restyleCard(accountRow, 8);
        restyleCard(configPanel, 8);
        restyleCard(optionPanel, 6);
        if (themeHintLabel != null) themeHintLabel.setForeground(UiTheme.COLOR_HINT);
        if (autostartHintLabel != null) autostartHintLabel.setForeground(UiTheme.COLOR_HINT);
        if (logWrapper != null) {
            logWrapper.setBackground(UiTheme.COLOR_CARD);
            logWrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiTheme.COLOR_BORDER_LIGHT),
                BorderFactory.createTitledBorder(
                    BorderFactory.createEmptyBorder(5, 5, 0, 5),
                    Messages.get("home.log.title"),
                    TitledBorder.LEFT,
                    TitledBorder.TOP,
                    UiTheme.FONT_CN_BOLD,
                    UiTheme.COLOR_TITLED_BORDER)));
        }
        replayDialState();
    }

    private static void restyleCard(JPanel card, int verticalInset) {
        if (card == null) return;
        card.setBackground(UiTheme.COLOR_CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UiTheme.COLOR_BORDER),
            BorderFactory.createEmptyBorder(verticalInset, 10, verticalInset, 10)));
    }

    private static JButton createStyledButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(UiTheme.FONT_CN_BOLD);
        btn.setPreferredSize(new Dimension(140, 40));
        btn.setForeground(Color.BLACK);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(true);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        setButtonBaseColor(btn, bg);
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                Color base = buttonBaseColor(btn);
                if (base != null) btn.setBackground(base.darker());
            }

            public void mouseExited(MouseEvent e) {
                Color base = buttonBaseColor(btn);
                if (base != null) btn.setBackground(base);
            }
        });
        return btn;
    }

    private static Color buttonBaseColor(JButton btn) {
        return btn.getClientProperty(BUTTON_BASE_COLOR) instanceof Color c ? c : null;
    }

    private static void setButtonBaseColor(JButton btn, Color c) {
        btn.putClientProperty(BUTTON_BASE_COLOR, c);
        btn.setBackground(c);
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
        this.online = online;
        dialBusy = false;
        if (online) {
            lblStatus.setText(Messages.get("home.status.connected"));
            lblStatusDot.setForeground(Color.WHITE);
            statusBar.setBackground(UiTheme.COLOR_STATUS_ONLINE);
            btnDial.setText(Messages.get("home.dial.disconnect"));
            setButtonBaseColor(btnDial, UiTheme.COLOR_ERROR);
        } else {
            lblStatus.setText(Messages.get("home.status.disconnected"));
            lblStatusDot.setForeground(Color.WHITE);
            statusBar.setBackground(UiTheme.COLOR_INFO);
            btnDial.setText(Messages.get("home.dial.connect"));
            setButtonBaseColor(btnDial, UiTheme.COLOR_INFO);
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
        dialBusy = true;
        btnDial.setEnabled(false);
        if (label != null) {
            dialProgressLabel = label;
            btnDial.setText(label);
        }
        if (bg != null) {
            setButtonBaseColor(btnDial, bg);
        }
        btnDial.repaint();
    }

    /** Replay the recorded dial state so themed colors land on the right control state. */
    private void replayDialState() {
        if (dialBusy) {
            setDialProgress(dialProgressLabel, UiTheme.COLOR_WARNING);
        } else {
            setOnlineStatus(online);
        }
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
