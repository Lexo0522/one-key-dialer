package ui;

import model.DialCredentials;
import model.PasswordChars;
import service.DialPrecheck;
import service.DialPrecheck.Failure;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.Color;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Pre-dial validation + one-shot credential capture for the main shell.
 * Both methods must run on the EDT.
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
    public boolean validateDialInput(boolean interactive) {
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

    /** Must run on EDT. The returned credentials are one-shot and cleared by the consumer. */
    public DialCredentials captureDialCredentials() {
        MainHomePanel home = host.homePanel();
        if (home == null) {
            return new DialCredentials("", new char[0]);
        }
        String user = home.getTxtUsername().getText().trim();
        char[] pw = home.getTxtPassword().getPassword();
        try {
            return new DialCredentials(user, pw);
        } finally {
            PasswordChars.clear(pw);
        }
    }
}
