package ui;

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
    }

    private static final int WINDOW_WIDTH = 580;
    private static final int DEFAULT_INTERVAL = 30;

    private final Host host;
    private final JPanel statusBar = new JPanel(new BorderLayout());

    private final JComboBox<String> cmbAccounts = new JComboBox<>();
    private final JTextField txtConnectionName = new JTextField(20);
    private final JTextField txtUsername = new JTextField(20);
    private final JPasswordField txtPassword = new JPasswordField(20);
    private final JSpinner spnInterval = new JSpinner(new SpinnerNumberModel(DEFAULT_INTERVAL, 5, 3600, 5));
    private final JCheckBox chkAutoReconnect = new JCheckBox("断网自动重连");
    private final JCheckBox chkAutoStart = new JCheckBox("开机自动启动");
    private final JCheckBox chkStartMinimized = new JCheckBox("启动时最小化到托盘");
    private final JCheckBox chkDisconnectOnNoInternet = new JCheckBox("无外网时自动断开宽带");
    private final JTextPane logPane = new JTextPane();
    private final JButton btnDial;
    private final JLabel lblStatus = new JLabel("未连接");
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

        btnDial = createStyledButton("连接宽带", UiTheme.COLOR_INFO);
        btnDial.setPreferredSize(new Dimension(300, 45));
        btnDial.addActionListener(e -> host.onDialToggle());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 5));
        south.setBackground(UiTheme.COLOR_BG);
        south.add(btnDial);
        add(south, BorderLayout.SOUTH);

        logPane.setEditable(false);
        logPane.setBackground(UiTheme.COLOR_DARK);
        logPane.setFont(UiTheme.FONT_LOG);
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
        chkAutoStart.setToolTipText("以 Windows 注册表 Run 项为准；INI 中 auto.start 仅用于启动时修复注册");
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
        JButton btnAccountConfig = new JButton("账号配置");
        btnAccountConfig.setFont(UiTheme.FONT_CN);
        btnAccountConfig.addActionListener(e -> host.openAccountManager());
        JLabel lbl = new JLabel("  账号:");
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

        addLabeled(panel, gbc, row++, "昵称:", txtConnectionName);
        addLabeled(panel, gbc, row++, "学号/账号:", txtUsername);
        addLabeled(panel, gbc, row++, "密 码:", txtPassword);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
        JLabel n4 = new JLabel("检测间隔(秒):");
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
        panel.add(wrapLeft(chkAutoReconnect));
        panel.add(wrapLeft(chkAutoStart));
        panel.add(wrapLeft(chkStartMinimized));
        panel.add(wrapLeft(chkDisconnectOnNoInternet));
        JLabel autostartHint = new JLabel("  开机自启以注册表为准（非仅 INI）");
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
                "  运行日志",
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

    public void setOnlineStatus(boolean online) {
        if (online) {
            lblStatus.setText("已连接");
            lblStatusDot.setForeground(Color.WHITE);
            statusBar.setBackground(new Color(22, 163, 74));
            btnDial.setText("断开连接");
            btnDial.setBackground(UiTheme.COLOR_ERROR);
        } else {
            lblStatus.setText("未连接");
            lblStatusDot.setForeground(Color.WHITE);
            statusBar.setBackground(UiTheme.COLOR_INFO);
            btnDial.setText("连接宽带");
            btnDial.setBackground(UiTheme.COLOR_INFO);
            lblSpeed.setText("↓ -- ↑ --");
            lblUptime.setText("时长: 未连接");
        }
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

    public JButton getBtnDial() {
        return btnDial;
    }
}
