package ui;

import model.AccountInfo;
import storage.AccountStore;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Modal account list manager: add/edit/delete/reorder/import/export.
 */
public final class AccountManagerDialog {
    private AccountManagerDialog() {
    }

    /**
     * @param owner           parent window
     * @param accounts        live list (mutated in place)
     * @param clampIndex      called after delete to keep current selection valid
     * @param onListChanged   refresh main combo / dirty flag
     * @param saveAccounts    persist accounts to disk
     */
    public static void show(Window owner,
                            List<AccountInfo> accounts,
                            IntConsumer clampIndex,
                            Runnable onListChanged,
                            Runnable saveAccounts) {
        JDialog dialog = new JDialog(owner, "账号管理", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setSize(700, 450);
        dialog.setLocationRelativeTo(owner);

        JPanel mainPanel = new JPanel(new BorderLayout(0, 10));
        mainPanel.setBackground(UiTheme.COLOR_BG);
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        String[] columns = {"ID", "昵称", "账号", "密码", "备注"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        JTable table = new JTable(model);
        table.setFont(UiTheme.FONT_CN_SMALL);
        table.setRowHeight(28);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setFont(UiTheme.FONT_CN);
        table.getTableHeader().setPreferredSize(new Dimension(0, 32));
        table.getColumnModel().getColumn(0).setPreferredWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(140);
        table.getColumnModel().getColumn(3).setPreferredWidth(120);
        table.getColumnModel().getColumn(4).setPreferredWidth(150);

        refreshTable(model, accounts);

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createLineBorder(UiTheme.COLOR_BORDER_LIGHT));
        mainPanel.add(tableScroll, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        btnPanel.setOpaque(false);

        JButton btnAdd = new JButton("增加");
        btnAdd.setFont(UiTheme.FONT_CN);
        btnAdd.addActionListener(e -> {
            if (showAccountForm(dialog, accounts, null)) {
                afterMutate(model, accounts, onListChanged, saveAccounts);
            }
        });

        JButton btnEdit = new JButton("修改");
        btnEdit.setFont(UiTheme.FONT_CN);
        btnEdit.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                JOptionPane.showMessageDialog(dialog, "请先选择一个账号");
                return;
            }
            if (showAccountForm(dialog, accounts, accounts.get(row))) {
                afterMutate(model, accounts, onListChanged, saveAccounts);
            }
        });

        JButton btnDelete = new JButton("删除");
        btnDelete.setFont(UiTheme.FONT_CN);
        btnDelete.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                JOptionPane.showMessageDialog(dialog, "请先选择一个账号");
                return;
            }
            if (accounts.size() <= 1) {
                JOptionPane.showMessageDialog(dialog, "至少保留一个账号");
                return;
            }
            int confirm = JOptionPane.showConfirmDialog(dialog, "确定删除该账号？", "确认", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                accounts.remove(row);
                if (clampIndex != null) clampIndex.accept(accounts.size() - 1);
                afterMutate(model, accounts, onListChanged, saveAccounts);
            }
        });

        JButton btnUp = new JButton("上移");
        btnUp.setFont(UiTheme.FONT_CN);
        btnUp.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row <= 0) return;
            Collections.swap(accounts, row, row - 1);
            afterMutate(model, accounts, onListChanged, saveAccounts);
            table.setRowSelectionInterval(row - 1, row - 1);
        });

        JButton btnDown = new JButton("下移");
        btnDown.setFont(UiTheme.FONT_CN);
        btnDown.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0 || row >= accounts.size() - 1) return;
            Collections.swap(accounts, row, row + 1);
            afterMutate(model, accounts, onListChanged, saveAccounts);
            table.setRowSelectionInterval(row + 1, row + 1);
        });

        JButton btnExport = new JButton("导出");
        btnExport.setFont(UiTheme.FONT_CN);
        btnExport.addActionListener(e -> exportAccounts(dialog, accounts));

        JButton btnImport = new JButton("导入");
        btnImport.setFont(UiTheme.FONT_CN);
        btnImport.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                try {
                    List<AccountInfo> imported = AccountStore.loadCsv(fc.getSelectedFile());
                    accounts.addAll(imported);
                    afterMutate(model, accounts, onListChanged, saveAccounts);
                    JOptionPane.showMessageDialog(dialog, "导入成功！");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, "导入失败: " + ex.getMessage());
                }
            }
        });

        btnPanel.add(btnAdd);
        btnPanel.add(btnEdit);
        btnPanel.add(btnDelete);
        btnPanel.add(Box.createHorizontalStrut(10));
        btnPanel.add(btnUp);
        btnPanel.add(btnDown);
        btnPanel.add(Box.createHorizontalStrut(10));
        btnPanel.add(btnExport);
        btnPanel.add(btnImport);

        mainPanel.add(btnPanel, BorderLayout.SOUTH);
        dialog.setContentPane(mainPanel);
        dialog.setVisible(true);
    }

    private static void afterMutate(DefaultTableModel model,
                                    List<AccountInfo> accounts,
                                    Runnable onListChanged,
                                    Runnable saveAccounts) {
        refreshTable(model, accounts);
        if (saveAccounts != null) saveAccounts.run();
        if (onListChanged != null) onListChanged.run();
    }

    private static void refreshTable(DefaultTableModel model, List<AccountInfo> accounts) {
        model.setRowCount(0);
        for (int i = 0; i < accounts.size(); i++) {
            AccountInfo a = accounts.get(i);
            model.addRow(new Object[]{i + 1, a.name, a.username, "********", a.remark});
        }
    }

    private static void exportAccounts(JDialog dialog, List<AccountInfo> accounts) {
        int mode = JOptionPane.showOptionDialog(dialog,
            "默认导出不含密码（推荐）。\n若需导出密码，请选择「含密码导出」并妥善保管文件。",
            "导出账号",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            new Object[]{"安全导出（无密码）", "含密码导出", "取消"},
            "安全导出（无密码）");
        if (mode != 0 && mode != 1) return;
        boolean withPassword = mode == 1;
        if (withPassword) {
            int confirm = JOptionPane.showConfirmDialog(dialog,
                "导出文件将包含明文密码，确定继续？",
                "安全警告", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(withPassword
            ? "pppoe_accounts_export_WITH_PASSWORDS.csv"
            : "pppoe_accounts_export.csv"));
        if (fc.showSaveDialog(dialog) == JFileChooser.APPROVE_OPTION) {
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(fc.getSelectedFile()), "UTF-8"))) {
                if (withPassword) {
                    pw.println("昵称,账号,密码,备注");
                    for (AccountInfo a : accounts) pw.println(AccountStore.toCsvLineWithPassword(a));
                } else {
                    pw.println("昵称,账号,备注");
                    for (AccountInfo a : accounts) pw.println(AccountStore.toCsvLineSafe(a));
                }
                JOptionPane.showMessageDialog(dialog, "导出成功！");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "导出失败: " + ex.getMessage());
            }
        }
    }

    /** @return true if user confirmed and data was applied */
    private static boolean showAccountForm(JDialog parent, List<AccountInfo> accounts, AccountInfo existing) {
        boolean isEdit = existing != null;
        final boolean[] confirmed = {false};
        JDialog form = new JDialog(parent, isEdit ? "修改账号" : "新增账号", true);
        form.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        form.setSize(380, 280);
        form.setLocationRelativeTo(parent);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(UiTheme.COLOR_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(15, 20, 10, 20));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        JLabel nameLabel = new JLabel("昵称:");
        nameLabel.setFont(UiTheme.FONT_CN);
        panel.add(nameLabel, gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        JTextField txtName = new JTextField(20);
        txtName.setFont(UiTheme.FONT_CN);
        if (isEdit) txtName.setText(existing.name);
        panel.add(txtName, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        JLabel userLabel = new JLabel("账号:");
        userLabel.setFont(UiTheme.FONT_CN);
        panel.add(userLabel, gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        JTextField txtUser = new JTextField(20);
        txtUser.setFont(UiTheme.FONT_CN);
        if (isEdit) txtUser.setText(existing.username);
        panel.add(txtUser, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        JLabel passLabel = new JLabel("密码:");
        passLabel.setFont(UiTheme.FONT_CN);
        panel.add(passLabel, gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        JPasswordField txtPass = new JPasswordField(20);
        txtPass.setFont(UiTheme.FONT_CN);
        if (isEdit) {
            char[] existingPw = existing.copyPasswordChars();
            try {
                PasswordFields.setPassword(txtPass, existingPw);
            } finally {
                model.PasswordChars.clear(existingPw);
            }
        }
        panel.add(txtPass, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0;
        JLabel remarkLabel = new JLabel("备注:");
        remarkLabel.setFont(UiTheme.FONT_CN);
        panel.add(remarkLabel, gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        JTextField txtRemark = new JTextField(20);
        txtRemark.setFont(UiTheme.FONT_CN);
        if (isEdit) txtRemark.setText(existing.remark);
        panel.add(txtRemark, gbc);

        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 2;
        JLabel hint = new JLabel("  昵称为空时自动使用账号，无账号则显示\"未设置\"");
        hint.setFont(UiTheme.FONT_CN_SMALL);
        hint.setForeground(UiTheme.COLOR_HINT);
        panel.add(hint, gbc);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnBar.setOpaque(false);
        JButton btnCancel = new JButton("取消");
        btnCancel.setFont(UiTheme.FONT_CN);
        btnCancel.addActionListener(e -> form.dispose());
        JButton btnOk = new JButton("确定");
        btnOk.setFont(UiTheme.FONT_CN);
        btnOk.addActionListener(e -> {
            String name = txtName.getText().trim();
            String user = txtUser.getText().trim();
            String remark = txtRemark.getText().trim();
            char[] passRaw = txtPass.getPassword();
            char[] pass = model.PasswordChars.trimmedCopy(passRaw);
            try {
                if (name.isEmpty()) {
                    name = user.isEmpty() ? "未设置" : user;
                }
                if (isEdit) {
                    existing.name = name;
                    existing.username = user;
                    existing.setPasswordChars(pass);
                    existing.remark = remark;
                } else {
                    AccountInfo created = new AccountInfo(name, user, "", remark);
                    created.setPasswordChars(pass);
                    accounts.add(created);
                }
                confirmed[0] = true;
                form.dispose();
            } finally {
                model.PasswordChars.clear(pass);
                model.PasswordChars.clear(passRaw);
            }
        });
        btnBar.add(btnCancel);
        btnBar.add(btnOk);

        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.SOUTHEAST;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(btnBar, gbc);

        form.setContentPane(panel);
        form.setVisible(true);
        return confirmed[0];
    }
}
