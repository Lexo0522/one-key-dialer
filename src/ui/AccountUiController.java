package ui;

import model.AccountInfo;
import service.AccountSession;
import service.BackgroundExecutor;
import model.DialLifecycle;
import service.DialOrchestrator;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Account combo / manager glue for the main shell. No credential caching:
 * the orchestrator captures one-shot credentials at dial time.
 */
public final class AccountUiController {
    public interface Host {
        MainHomePanel homePanel();

        AccountSession accountSession();

        DialOrchestrator dialOrchestrator();

        DialLifecycle dialLifecycle();

        AtomicBoolean isOnline();

        BackgroundExecutor backgroundExecutor();

        TrayController trayController();

        void markTooltipDirty();

        void saveSettings();

        void logInfo(String message);

        void logWarning(String message);
    }

    private final Host host;

    public AccountUiController(Host host) {
        this.host = host;
    }

    public void refreshAccountComboBox() {
        MainHomePanel home = host.homePanel();
        AccountSession session = host.accountSession();
        home.getCmbAccounts().removeAllItems();
        for (AccountInfo a : session.accounts()) {
            home.getCmbAccounts().addItem(a.toString());
        }
        if (!session.accounts().isEmpty()) {
            int idx = session.currentIndex();
            if (idx < 0 || idx >= session.accounts().size()) {
                session.setCurrentIndex(0);
                idx = 0;
            }
            home.getCmbAccounts().setSelectedIndex(idx);
        }
    }

    public void onAccountChanged() {
        MainHomePanel home = host.homePanel();
        AccountSession session = host.accountSession();
        int i = home.getCmbAccounts().getSelectedIndex();
        if (i >= 0 && i < session.accounts().size()) {
            session.setCurrentIndex(i);
            session.applyCurrentToUi(
                home.getTxtConnectionName()::setText,
                home.getTxtUsername()::setText,
                chars -> PasswordFields.setPassword(home.getTxtPassword(), chars)
            );
            host.markTooltipDirty();
            TrayController tray = host.trayController();
            if (tray != null) tray.refreshAccountMenu();
        }
    }

    public void openAccountManager(java.awt.Window owner) {
        AccountSession session = host.accountSession();
        AccountManagerDialog.show(
            owner,
            session.accounts(),
            maxIdx -> session.clampIndexAfterListChange(),
            () -> {
                refreshAccountComboBox();
            },
            () -> {
                session.setDirty(true);
                if (session.save()) {
                    session.setDirty(false);
                }
            }
        );
        onAccountChanged();
    }

    public void saveCurrentAccount() {
        MainHomePanel home = host.homePanel();
        if (home == null) return;
        char[] pw = home.getTxtPassword().getPassword();
        host.accountSession().saveCurrentIfNeeded(
            home.getTxtConnectionName().getText(),
            home.getTxtUsername().getText(),
            pw
        );
    }

    /** Tray: switch account; if online, disconnect and wait for completion, then redial. */
    public void switchToAccountFromTray(int index) {
        AccountSession session = host.accountSession();
        if (index < 0 || index >= session.accounts().size()) return;
        if (host.dialLifecycle().isBusy()) {
            host.logWarning("正在处理连接操作，暂无法切换账号");
            return;
        }
        boolean wasOnline = host.isOnline().get();
        Runnable apply = () -> {
            session.setCurrentIndex(index);
            refreshAccountComboBox();
            onAccountChanged();
            host.saveSettings();
            host.logInfo("已切换账号: " + session.currentName());
            if (wasOnline) {
                host.dialOrchestrator().redialAfterDisconnect();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) apply.run();
        else SwingUtilities.invokeLater(apply);
    }
}
