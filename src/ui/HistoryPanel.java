package ui;

import service.HistoryService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * History tab: table + export/clear actions.
 */
public class HistoryPanel extends JPanel {
    private final DefaultTableModel tableModel;
    private final JTable table;

    public HistoryPanel(HistoryService historyService,
                        Supplier<Boolean> uiActive,
                        BiConsumer<String, Color> log,
                        Component parent) {
        super(new BorderLayout(0, 8));
        setBackground(UiTheme.COLOR_BG);
        setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        titlePanel.setOpaque(false);
        JLabel titleLabel = new JLabel("拨号历史记录");
        titleLabel.setFont(UiTheme.FONT_CN_BOLD);
        titlePanel.add(titleLabel);
        add(titlePanel, BorderLayout.NORTH);

        String[] columns = {"时间", "操作", "账号", "结果", "连接时长", "流量总和"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setFont(UiTheme.FONT_CN_SMALL);
        table.setRowHeight(28);
        table.setShowGrid(true);
        table.setGridColor(UiTheme.COLOR_TABLE_GRID);
        table.setSelectionBackground(UiTheme.COLOR_TABLE_SEL);
        table.setSelectionForeground(Color.BLACK);
        table.getTableHeader().setFont(UiTheme.FONT_CN);
        table.getTableHeader().setBackground(UiTheme.COLOR_TABLE_HEADER);
        table.getTableHeader().setPreferredSize(new Dimension(0, 32));
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(1).setPreferredWidth(55);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.getColumnModel().getColumn(3).setPreferredWidth(55);
        table.getColumnModel().getColumn(4).setPreferredWidth(75);
        table.getColumnModel().getColumn(5).setPreferredWidth(100);

        historyService.bindTable(tableModel, uiActive);

        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createLineBorder(UiTheme.COLOR_BORDER_LIGHT));
        sp.getViewport().setBackground(Color.WHITE);
        add(sp, BorderLayout.CENTER);

        JPanel bp = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        bp.setOpaque(false);

        JButton btnExport = new JButton("导出CSV");
        btnExport.setFont(UiTheme.FONT_CN);
        btnExport.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("pppoe_history_export.csv"));
            if (fc.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
                try {
                    historyService.exportTo(fc.getSelectedFile());
                    log.accept("历史记录已导出: " + fc.getSelectedFile().getName(), UiTheme.COLOR_SUCCESS);
                } catch (IOException ex) {
                    log.accept("导出失败: " + ex.getMessage(), UiTheme.COLOR_ERROR);
                }
            }
        });

        JButton btnClear = new JButton("清空记录");
        btnClear.setFont(UiTheme.FONT_CN);
        btnClear.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(parent,
                "确定要清空所有历史记录吗？", "确认清空", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                historyService.clear();
                log.accept("历史记录已清空", UiTheme.COLOR_INFO);
            }
        });

        bp.add(btnExport);
        bp.add(btnClear);
        add(bp, BorderLayout.SOUTH);
    }

    public DefaultTableModel getTableModel() {
        return tableModel;
    }
}
