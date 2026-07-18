package ui;

import model.AccountInfo;
import service.BackgroundExecutor;
import util.ProcessIO;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Network diagnostics tab: command runners + connection status report.
 * Work runs on the shared {@link BackgroundExecutor} (one job at a time via {@code running}).
 */
public class DiagPanel extends JPanel {
    private static final int MAX_DIAG_LINES = 2000;
    private static final long DIAG_TIMEOUT_SEC = 60;
    private static final DateTimeFormatter FMT_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public interface StatusSnapshot {
        boolean isOnline();

        AccountInfo currentAccount();

        long connectTimeMillis();

        long totalDownload();

        long totalUpload();

        long speedDown();

        long speedUp();

        String formatBytes(long bytes);

        String formatSpeed(long bytesPerSec);

        StringBuilder appendTime(StringBuilder sb, long totalSeconds);

        /** Optional RAS phonebook diagnostics; may be empty. */
        default String phonebookReport() { return ""; }

        /** Optional probe config summary. */
        default String probeReport() { return ""; }

        /** List PPPoE device options for user selection; empty if unsupported. */
        default java.util.List<service.RasPhonebook.DeviceHint> listPppoeDevices() {
            return java.util.Collections.emptyList();
        }

        /**
         * Apply preferred device and optionally rewrite phonebook entry.
         * @return status message for the log
         */
        default String applyPppoeDevice(service.RasPhonebook.DeviceHint hint, boolean rewrite) {
            return "当前构建不支持设备选择";
        }
    }

    private final StatusSnapshot status;
    private final Supplier<Boolean> uiActive;
    private final BackgroundExecutor executor;
    private final JTextArea output = new JTextArea();
    private final List<JButton> actionButtons = new ArrayList<>();
    private final Deque<Integer> lineLengths = new ArrayDeque<>();
    private final StringBuilder batch = new StringBuilder(4096);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private int lineCount = 0;
    private volatile boolean bufferEnabled = true;
    private volatile boolean batchPending = false;
    private volatile Future<?> workerFuture;

    public DiagPanel(StatusSnapshot status, Supplier<Boolean> uiActive, BackgroundExecutor executor) {
        super(new BorderLayout(0, 5));
        this.status = status;
        this.uiActive = uiActive != null ? uiActive : () -> true;
        if (executor == null) {
            throw new IllegalArgumentException("BackgroundExecutor required");
        }
        this.executor = executor;

        setBackground(UiTheme.COLOR_BG);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel bp = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        bp.setBackground(UiTheme.COLOR_BG);

        JButton btnPing = actionButton("Ping测试", () -> runCommand("ping -n 4 223.5.5.5"));
        JButton btnIP = actionButton("IP配置", () -> runCommand("ipconfig /all"));
        JButton btnTrace = actionButton("路由追踪", () -> runCommand("tracert -d 223.5.5.5"));
        JButton btnDNS = actionButton("DNS刷新", () -> runCommand("ipconfig /flushdns"));
        JButton btnConn = actionButton("连接状态", this::showConnectionStatus);
        JButton btnPbk = actionButton("电话簿/探测", this::showPhonebookAndProbe);
        JButton btnDevice = actionButton("选择设备", this::choosePppoeDevice);
        JButton btnRewrite = actionButton("重写电话簿", this::rewritePhonebook);
        JButton btnClear = new JButton("清空");
        btnClear.setFont(UiTheme.FONT_CN);
        btnClear.addActionListener(e -> clearOutput());

        bp.add(btnPing);
        bp.add(btnIP);
        bp.add(btnTrace);
        bp.add(btnDNS);
        bp.add(btnConn);
        bp.add(btnPbk);
        bp.add(btnDevice);
        bp.add(btnRewrite);
        bp.add(btnClear);
        add(bp, BorderLayout.NORTH);

        output.setFont(UiTheme.FONT_DIAG);
        output.setEditable(false);
        output.setBackground(UiTheme.COLOR_DARK);
        output.setForeground(Color.WHITE);
        JScrollPane sp = new JScrollPane(output);
        sp.setBorder(BorderFactory.createLineBorder(UiTheme.COLOR_BORDER));
        add(sp, BorderLayout.CENTER);
    }

    private JButton actionButton(String text, Runnable action) {
        JButton btn = new JButton(text);
        btn.setFont(UiTheme.FONT_CN);
        btn.addActionListener(e -> action.run());
        actionButtons.add(btn);
        return btn;
    }

    public void pauseBuffer() {
        bufferEnabled = false;
        batchPending = false;
        synchronized (batch) {
            batch.setLength(0);
        }
        lineCount = 0;
        lineLengths.clear();
        output.setText("");
    }

    public void resumeBuffer() {
        bufferEnabled = true;
    }

    public void clearOutput() {
        output.setText("");
        lineCount = 0;
        lineLengths.clear();
        synchronized (batch) {
            batch.setLength(0);
        }
    }

    public void showPhonebookAndProbe() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("═══════════════════════════════════════\n");
        sb.append("     电话簿 / 外网探测配置\n");
        sb.append("═══════════════════════════════════════\n\n");
        sb.append("【探测】\n  ").append(status.probeReport()).append("\n\n");
        sb.append("【RAS 电话簿】\n  ").append(status.phonebookReport()).append("\n");
        sb.append("\n═══════════════════════════════════════\n");
        append(sb.toString());
    }

    public void choosePppoeDevice() {
        java.util.List<service.RasPhonebook.DeviceHint> options = status.listPppoeDevices();
        if (options == null || options.isEmpty()) {
            append("\n[未找到可用 PPPoE 设备提示]\n");
            return;
        }
        String[] labels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) {
            service.RasPhonebook.DeviceHint h = options.get(i);
            labels[i] = h.device + "  [" + h.port + "]" + (h.fromExisting ? "" : " (默认)");
        }
        Object picked = JOptionPane.showInputDialog(
            this, "选择用于写入电话簿的 PPPoE 设备：", "PPPoE 设备",
            JOptionPane.QUESTION_MESSAGE, null, labels, labels[0]);
        if (picked == null) return;
        int idx = -1;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(picked)) { idx = i; break; }
        }
        if (idx < 0) return;
        service.RasPhonebook.DeviceHint chosen = options.get(idx);
        int rewrite = JOptionPane.showConfirmDialog(this,
            "是否立即重写电话簿连接条目？\n（否则仅记住选择，下次自动创建时生效）",
            "重写电话簿", JOptionPane.YES_NO_CANCEL_OPTION);
        if (rewrite == JOptionPane.CANCEL_OPTION) return;
        String msg = status.applyPppoeDevice(chosen, rewrite == JOptionPane.YES_OPTION);
        append("\n[设备] " + msg + "\n");
    }

    public void rewritePhonebook() {
        int ok = JOptionPane.showConfirmDialog(this,
            "将删除并重建本程序使用的 RAS 连接条目，是否继续？",
            "重写电话簿", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;
        String msg = status.applyPppoeDevice(null, true);
        append("\n[电话簿] " + msg + "\n");
    }

    public void showConnectionStatus() {
        if (!running.compareAndSet(false, true)) {
            append("\n[诊断进行中，请稍候]\n");
            return;
        }
        setButtonsEnabled(false);
        clearOutput();
        bufferEnabled = true;

        StringBuilder summary = new StringBuilder(512);
        summary.append("═══════════════════════════════════════\n");
        summary.append("          网络连接状态报告\n");
        summary.append("═══════════════════════════════════════\n\n");
        summary.append("【基本状态】\n");
        summary.append("  连接状态: ").append(status.isOnline() ? "● 已连接" : "○ 未连接").append('\n');

        AccountInfo acc = status.currentAccount();
        if (acc != null) {
            summary.append("  当前账号: ").append(acc.username).append('\n');
            summary.append("  昵称: ").append(acc.name).append('\n');
        }

        if (status.isOnline() && status.connectTimeMillis() > 0) {
            long sec = (System.currentTimeMillis() - status.connectTimeMillis()) / 1000;
            summary.append("  连接时长: ");
            status.appendTime(summary, sec).append('\n');
        }

        summary.append("\n【流量统计】\n");
        summary.append("  ↓ 下行: ").append(status.formatBytes(status.totalDownload())).append('\n');
        summary.append("  ↑ 上行: ").append(status.formatBytes(status.totalUpload())).append('\n');
        summary.append("  总 计: ").append(status.formatBytes(status.totalDownload() + status.totalUpload())).append('\n');
        summary.append("\n【当前速度】\n");
        summary.append("  ↓ 下行: ").append(status.formatSpeed(status.speedDown())).append('\n');
        summary.append("  ↑ 上行: ").append(status.formatSpeed(status.speedUp())).append('\n');
        summary.append("\n═══════════════════════════════════════\n");
        append(summary.toString());

        workerFuture = executor.submitLong(() -> {
            try {
                StringBuilder detail = new StringBuilder(512);
                detail.append("\n【网络适配器】\n");
                try {
                    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                    boolean foundAdapter = false;
                    while (interfaces != null && interfaces.hasMoreElements()) {
                        NetworkInterface nif = interfaces.nextElement();
                        if (nif == null || !nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;

                        List<String> addresses = new ArrayList<>();
                        Enumeration<InetAddress> inetAddresses = nif.getInetAddresses();
                        while (inetAddresses.hasMoreElements()) {
                            InetAddress addr = inetAddresses.nextElement();
                            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                addresses.add(addr.getHostAddress());
                            }
                        }
                        if (addresses.isEmpty()) continue;

                        foundAdapter = true;
                        detail.append("  ").append(nif.getDisplayName()).append('\n');
                        for (String address : addresses) {
                            detail.append("    IPv4: ").append(address).append('\n');
                        }
                    }
                    if (!foundAdapter) {
                        detail.append("  未发现已启用的 IPv4 网络适配器\n");
                    }
                } catch (Exception ex) {
                    detail.append("  获取网络适配器信息失败: ").append(ex.getClass().getSimpleName())
                        .append(": ").append(ex.getMessage()).append('\n');
                }

                detail.append("\n【连通性测试】\n");
                Process p = null;
                try {
                    ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "ping -n 2 223.5.5.5");
                    pb.redirectErrorStream(true);
                    p = pb.start();
                    boolean hasOutput = false;
                    try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), ProcessIO.childCharset()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            String trimmed = line.trim();
                            if (!trimmed.isEmpty()) {
                                hasOutput = true;
                                detail.append("  ").append(trimmed).append('\n');
                            }
                        }
                    }
                    boolean finished = p.waitFor(DIAG_TIMEOUT_SEC, TimeUnit.SECONDS);
                    if (!finished) {
                        p.destroyForcibly();
                        detail.append("  Ping 超时\n");
                    } else {
                        int exitCode = p.exitValue();
                        if (!hasOutput) {
                            detail.append("  Ping 未返回可显示的输出\n");
                        }
                        detail.append("  结果: ").append(exitCode == 0 ? "连通" : "可能不通或存在丢包").append('\n');
                    }
                } finally {
                    if (p != null) p.destroy();
                }

                detail.append("\n═══════════════════════════════════════\n");
                detail.append("报告生成时间: ").append(LocalDateTime.now().format(FMT_TIME)).append('\n');
                append(detail.toString());
            } catch (Exception e) {
                append("\n获取详细信息失败: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n");
            } finally {
                finishRun();
            }
        });
    }

    public void runCommand(String command) {
        if (!running.compareAndSet(false, true)) {
            append("\n[诊断进行中，请稍候]\n");
            return;
        }
        setButtonsEnabled(false);
        clearOutput();
        bufferEnabled = true;

        StringBuilder header = new StringBuilder(96);
        header.append("═══════════════════════════════════════\n");
        header.append("执行命令: ").append(command).append('\n');
        header.append("═══════════════════════════════════════\n\n");
        append(header.toString());

        workerFuture = executor.submitLong(() -> {
            Process p = null;
            try {
                ProcessBuilder pb = new ProcessBuilder("cmd", "/c", command);
                pb.redirectErrorStream(true);
                p = pb.start();

                StringBuilder local = new StringBuilder(512);
                int batchLines = 0;
                try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), ProcessIO.childCharset()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        local.append("  ").append(line).append('\n');
                        batchLines++;
                        if (batchLines >= 16) {
                            append(local.toString());
                            local.setLength(0);
                            batchLines = 0;
                        }
                    }
                }
                if (local.length() > 0) append(local.toString());
                boolean finished = p.waitFor(DIAG_TIMEOUT_SEC, TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    append("\n═══════════════════════════════════════\n执行超时\n");
                } else {
                    append("\n═══════════════════════════════════════\n执行完毕\n");
                }
            } catch (Exception e) {
                append("\n执行失败: " + e.getMessage() + "\n");
            } finally {
                if (p != null) p.destroy();
                finishRun();
            }
        });
    }

    private void finishRun() {
        running.set(false);
        SwingUtilities.invokeLater(() -> setButtonsEnabled(true));
    }

    private void setButtonsEnabled(boolean enabled) {
        for (JButton b : actionButtons) {
            b.setEnabled(enabled);
        }
    }

    private void append(String text) {
        if (!bufferEnabled || !Boolean.TRUE.equals(uiActive.get())) return;
        synchronized (batch) {
            batch.append(text);
            int lineStart = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    lineLengths.addLast(i - lineStart + 1);
                    lineCount++;
                    lineStart = i + 1;
                }
            }
            if (!batchPending) {
                batchPending = true;
                SwingUtilities.invokeLater(() -> {
                    if (!bufferEnabled || !Boolean.TRUE.equals(uiActive.get())) {
                        batchPending = false;
                        return;
                    }
                    String content;
                    synchronized (batch) {
                        content = batch.toString();
                        batch.setLength(0);
                        batchPending = false;
                    }
                    output.append(content);
                    if (lineCount > MAX_DIAG_LINES) {
                        int cutLen = 0;
                        int linesToCut = lineCount - MAX_DIAG_LINES;
                        for (int i = 0; i < linesToCut && !lineLengths.isEmpty(); i++) {
                            cutLen += lineLengths.removeFirst();
                        }
                        if (cutLen > 0) {
                            output.replaceRange("", 0, Math.min(cutLen, output.getDocument().getLength()));
                            lineCount = MAX_DIAG_LINES;
                        }
                    }
                });
            }
        }
    }
}
