package ui;

import service.HistoryService;
import service.StatsService;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Lightweight stats tab: recompute from history on demand.
 */
public final class StatsPanel extends JPanel {
    private final HistoryService historyService;
    private final JTextArea area = new JTextArea();
    private final Consumer<String> onInfo;

    public StatsPanel(HistoryService historyService, Consumer<String> onInfo) {
        super(new BorderLayout(0, 8));
        this.historyService = historyService;
        this.onInfo = onInfo != null ? onInfo : s -> {};
        setBackground(UiTheme.COLOR_BG);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        area.setEditable(false);
        // Use CJK-capable font (Consolas/FONT_LOG cannot render 中文 → tofu/garbled glyphs).
        area.setFont(UiTheme.FONT_DIAG);
        area.setBackground(UiTheme.COLOR_DARK);
        area.setForeground(Color.WHITE);
        area.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);

        JButton refresh = new JButton("刷新统计");
        refresh.setFont(UiTheme.FONT_CN);
        refresh.addActionListener(e -> refreshStats());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        top.setOpaque(false);
        top.add(refresh);
        JLabel hint = new JLabel("基于历史记录汇总（成功/失败/常见结果）");
        hint.setFont(UiTheme.FONT_CN_SMALL);
        hint.setForeground(UiTheme.COLOR_HINT);
        top.add(hint);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(area), BorderLayout.CENTER);
        refreshStats();
    }

    public void refreshStats() {
        if (historyService != null) {
            historyService.ensureLoaded();
            StatsService.Summary s = StatsService.summarize(historyService.records());
            area.setText(s.reportText);
            onInfo.accept("统计已刷新：拨号 " + s.dialAttempts + " 次，成功 " + s.dialSuccess);
        } else {
            area.setText("(无历史服务)");
        }
        area.setCaretPosition(0);
    }
}
