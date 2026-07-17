package ui;

import util.ConnectivityConfirm;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Probe settings tab: icmp / http / auto mode, host, URL, attempts, delay,
 * and a no-dial 「测试连通」 action.
 */
public class ProbeSettingsPanel extends JPanel {
    public interface Host {
        void onProbeSettingsChanged();

        void saveSettings();

        /**
         * Run connectivity confirm off-EDT. Must not dial/RAS.
         * Invoke {@code onDone} on EDT with the outcome (ok + timing).
         */
        void runConnectivityTest(ConnectivityConfirm.Config config,
                                 java.util.function.Consumer<util.ProbeOutcome> onDone);
    }

    private final JComboBox<String> cmbMode = new JComboBox<>(new String[]{
        ConnectivityConfirm.MODE_AUTO,
        ConnectivityConfirm.MODE_ICMP,
        ConnectivityConfirm.MODE_HTTP
    });
    private final JTextField txtHost = new JTextField(ConnectivityConfirm.DEFAULT_HOST, 24);
    private final JTextField txtHttpUrl = new JTextField(ConnectivityConfirm.DEFAULT_HTTP_URL, 24);
    private final JSpinner spnAttempts = new JSpinner(new SpinnerNumberModel(
        ConnectivityConfirm.DEFAULT_ATTEMPTS, 1, 10, 1));
    private final JSpinner spnDelayMs = new JSpinner(new SpinnerNumberModel(
        (int) ConnectivityConfirm.DEFAULT_DELAY_MS, 0, 10000, 100));
    private final JButton btnTest = new JButton("测试连通");
    private final JLabel lblResult = new JLabel(" ");
    private final Host host;
    private volatile boolean testRunning;

    public ProbeSettingsPanel(Host host) {
        this.host = host;
        setLayout(new BorderLayout());
        setBackground(UiTheme.COLOR_BG);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(UiTheme.COLOR_BG);
        content.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        content.add(buildFormCard(host));
        content.add(Box.createVerticalStrut(10));
        content.add(buildHintCard());
        content.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);
    }

    private JPanel buildFormCard(Host host) {
        JPanel card = createCard();
        card.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 8, 6, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;
        addRow(card, gbc, row++, "探测模式:", cmbMode);
        addRow(card, gbc, row++, "ICMP 主机:", txtHost);
        addRow(card, gbc, row++, "HTTP URL:", txtHttpUrl);
        addRow(card, gbc, row++, "尝试次数:", spnAttempts);
        addRow(card, gbc, row++, "间隔(ms):", spnDelayMs);

        cmbMode.setFont(UiTheme.FONT_CN);
        txtHost.setFont(UiTheme.FONT_CN);
        txtHttpUrl.setFont(UiTheme.FONT_CN);
        spnAttempts.setFont(UiTheme.FONT_CN);
        spnDelayMs.setFont(UiTheme.FONT_CN);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        testRow.setOpaque(false);
        btnTest.setFont(UiTheme.FONT_CN);
        lblResult.setFont(UiTheme.FONT_CN);
        lblResult.setForeground(UiTheme.COLOR_HINT);
        btnTest.addActionListener(e -> startConnectivityTest());
        testRow.add(btnTest);
        testRow.add(lblResult);
        card.add(testRow, gbc);

        Runnable notify = () -> {
            host.onProbeSettingsChanged();
            host.saveSettings();
        };
        cmbMode.addActionListener(e -> notify.run());
        txtHost.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) { notify.run(); }
        });
        txtHttpUrl.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) { notify.run(); }
        });
        spnAttempts.addChangeListener(e -> notify.run());
        spnDelayMs.addChangeListener(e -> notify.run());

        return card;
    }

    private void startConnectivityTest() {
        if (testRunning) return;
        host.onProbeSettingsChanged();
        ConnectivityConfirm.Config cfg = ConnectivityConfirm.Config.from(
            getProbeMode(), getProbeHost(), getProbeHttpUrl(), getProbeAttempts(), getProbeDelayMs());
        testRunning = true;
        btnTest.setEnabled(false);
        lblResult.setForeground(UiTheme.COLOR_INFO);
        lblResult.setText("测试中… mode=" + cfg.mode);
        host.runConnectivityTest(cfg, outcome -> {
            testRunning = false;
            btnTest.setEnabled(true);
            if (outcome != null && outcome.ok) {
                lblResult.setForeground(UiTheme.COLOR_SUCCESS);
                lblResult.setText("结果: " + outcome.shortLine());
            } else {
                lblResult.setForeground(UiTheme.COLOR_ERROR);
                String line = outcome != null ? outcome.shortLine() : ("不通 mode=" + cfg.mode);
                lblResult.setText("结果: " + line);
            }
        });
    }

    private JPanel buildHintCard() {
        JPanel card = createCard();
        card.setLayout(new BorderLayout());
        JTextArea hint = new JTextArea(
            "auto：每次先 ICMP/ping，失败再 HTTP。\n"
                + "icmp：仅探测主机（默认 223.5.5.5）。\n"
                + "http：仅访问 URL（默认 generate_204）。\n"
                + "校园网禁 ICMP 时请用 auto 或 http。\n"
                + "「测试连通」只跑探测、不拨号。\n"
                + "「无外网时自动断开」在主页选项中设置。"
        );
        hint.setEditable(false);
        hint.setOpaque(false);
        hint.setFont(UiTheme.FONT_CN_SMALL);
        hint.setForeground(UiTheme.COLOR_HINT);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        card.add(hint, BorderLayout.CENTER);
        return card;
    }

    private static void addRow(JPanel panel, GridBagConstraints gbc, int row, String label, JComponent field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
        JLabel lbl = new JLabel(label);
        lbl.setFont(UiTheme.FONT_CN);
        panel.add(lbl, gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(field, gbc);
    }

    private static JPanel createCard() {
        JPanel card = new JPanel();
        card.setBackground(UiTheme.COLOR_CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UiTheme.COLOR_BORDER),
            BorderFactory.createEmptyBorder(12, 12, 12, 12)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        return card;
    }

    public void applyFrom(String mode, String host, String httpUrl, int attempts, int delayMs) {
        String m = ConnectivityConfirm.normalizeMode(mode);
        cmbMode.setSelectedItem(m);
        txtHost.setText(host != null ? host : ConnectivityConfirm.DEFAULT_HOST);
        txtHttpUrl.setText(httpUrl != null ? httpUrl : ConnectivityConfirm.DEFAULT_HTTP_URL);
        spnAttempts.setValue(Math.max(1, attempts));
        spnDelayMs.setValue(Math.max(0, delayMs));
    }

    public String getProbeMode() {
        Object v = cmbMode.getSelectedItem();
        return ConnectivityConfirm.normalizeMode(v != null ? v.toString() : ConnectivityConfirm.MODE_AUTO);
    }

    public String getProbeHost() {
        String h = txtHost.getText() != null ? txtHost.getText().trim() : "";
        return h.isEmpty() ? ConnectivityConfirm.DEFAULT_HOST : h;
    }

    public String getProbeHttpUrl() {
        String u = txtHttpUrl.getText() != null ? txtHttpUrl.getText().trim() : "";
        return u.isEmpty() ? ConnectivityConfirm.DEFAULT_HTTP_URL : u;
    }

    public int getProbeAttempts() {
        return (int) spnAttempts.getValue();
    }

    public int getProbeDelayMs() {
        return (int) spnDelayMs.getValue();
    }

    public ConnectivityConfirm.Config toConfig() {
        return ConnectivityConfirm.Config.from(
            getProbeMode(), getProbeHost(), getProbeHttpUrl(), getProbeAttempts(), getProbeDelayMs());
    }
}
