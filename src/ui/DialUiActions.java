package ui;

import model.AppFiles;
import model.DialSnapshot;
import model.PasswordChars;
import service.DialPrecheck;
import service.DialPrecheck.Failure;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.Color;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Pre-dial validation + EDT credential snapshot for the main shell.
 */
public final class DialUiActions {
    public interface Host {
        Component dialogOwner();

        MainHomePanel homePanel();

        BooleanSupplier isOnline();

        BooleanSupplier hasCurrentAccount();

        void log(String message, Color color);
    }

    private final Host host;

    public DialUiActions(Host host) {
        this.host = host;
    }

    /**
     * Validate credentials / session before dial.
     * @param interactive show JOptionPane for user-facing failures
     * @return true if dial may proceed
     */
    public boolean validateBeforeDial(boolean interactive) {
        MainHomePanel home = host.homePanel();
        if (home == null) return false;

        char[] password = home.getTxtPassword().getPassword();
        try {
            Optional<Failure> fail = DialPrecheck.check(
                host.isOnline().getAsBoolean(),
                host.hasCurrentAccount().getAsBoolean(),
                home.getTxtUsername().getText(),
                password);
            if (!fail.isPresent()) return true;

            Failure f = fail.get();
            Color color = f == Failure.ALREADY_ONLINE
                ? UiTheme.COLOR_INFO
                : (f == Failure.NO_ACCOUNT ? UiTheme.COLOR_ERROR : UiTheme.COLOR_WARNING);
            host.log(DialPrecheck.logMessage(f), color);

            if (interactive && DialPrecheck.showDialog(f)) {
                JOptionPane.showMessageDialog(host.dialogOwner(),
                    DialPrecheck.dialogMessage(f), "拨号失败", JOptionPane.WARNING_MESSAGE);
            }
            return false;
        } finally {
            PasswordChars.clear(password);
        }
    }

    /** Must run on EDT. Password cleared inside snapshot factory path. */
    public DialSnapshot captureDialSnapshotOnEdt() {
        MainHomePanel home = host.homePanel();
        if (home == null) {
            return new DialSnapshot(AppFiles.RAS_CONNECTION, "", new char[0]);
        }
        String user = home.getTxtUsername().getText().trim();
        char[] pw = home.getTxtPassword().getPassword();
        try {
            return new DialSnapshot(AppFiles.RAS_CONNECTION, user, pw);
        } finally {
            PasswordChars.clear(pw);
        }
    }
}
