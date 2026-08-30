package ui;

import model.DialCredentials;
import model.PasswordChars;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Non-visual verification of pre-dial gates and one-shot credential capture. */
class DialUiActionsTest {

    private DialUiActions actions(MainHomePanel panel, boolean online) {
        return new DialUiActions(new DialUiActions.Host() {
            @Override public java.awt.Component dialogOwner() { return null; }
            @Override public MainHomePanel homePanel() { return panel; }
            @Override public java.util.function.BooleanSupplier isOnline() { return () -> online; }
            @Override public java.util.function.BooleanSupplier hasCurrentAccount() { return () -> true; }
            @Override public void log(String message, Color color) { }
        });
    }

    private MainHomePanel panel() {
        return new MainHomePanel(new MainHomePanel.Host() {
            @Override public void onAccountSelected() { }
            @Override public void openAccountManager() { }
            @Override public void onAutoReconnectToggled(boolean enabled) { }
            @Override public void onAutoStartToggled() { }
            @Override public void saveSettings() { }
            @Override public void onDialToggle() { }
        }, new service.LogService(null));
    }

    @Test
    void emptyUsernameFailsQuietValidation() {
        MainHomePanel panel = panel();
        panel.getTxtUsername().setText("");
        panel.getTxtPassword().setText("pw");
        assertFalse(actions(panel, false).validateDialInput(false));
    }

    @Test
    void emptyPasswordFailsQuietValidation() {
        MainHomePanel panel = panel();
        panel.getTxtUsername().setText("2023001");
        panel.getTxtPassword().setText("");
        assertFalse(actions(panel, false).validateDialInput(false));
    }

    @Test
    void alreadyOnlineFailsQuietValidation() {
        MainHomePanel panel = panel();
        panel.getTxtUsername().setText("2023001");
        panel.getTxtPassword().setText("pw");
        assertFalse(actions(panel, true).validateDialInput(false));
    }

    @Test
    void validCredentialsPassValidation() {
        MainHomePanel panel = panel();
        panel.getTxtUsername().setText("2023001");
        panel.getTxtPassword().setText("pw");
        assertTrue(actions(panel, false).validateDialInput(false));
    }

    @Test
    void captureReturnsMatchingOneShotCredentials() {
        MainHomePanel panel = panel();
        panel.getTxtUsername().setText("  2023001 ");
        panel.getTxtPassword().setText("secret");

        DialUiActions actions = actions(panel, false);
        assertTrue(actions.validateDialInput(false));
        DialCredentials credentials = actions.captureDialCredentials();

        assertEquals("2023001", credentials.username());
        assertTrue(credentials.passwordEquals("secret".toCharArray()));
        // The intermediate field copy is cleared inside capture; the credential owns its own.
        credentials.clear();
        assertFalse(credentials.hasPassword());
    }

    @Test
    void nullHomePanelRejectsDialAndCapturesEmptyCredentials() {
        DialUiActions actions = new DialUiActions(new DialUiActions.Host() {
            @Override public java.awt.Component dialogOwner() { return null; }
            @Override public MainHomePanel homePanel() { return null; }
            @Override public java.util.function.BooleanSupplier isOnline() { return () -> false; }
            @Override public java.util.function.BooleanSupplier hasCurrentAccount() { return () -> false; }
            @Override public void log(String message, Color color) { }
        });
        assertFalse(actions.validateDialInput(true));
        DialCredentials c = actions.captureDialCredentials();
        assertFalse(c.hasUsername());
        assertFalse(c.hasPassword());
    }

    @Test
    void precheckHelperClassifications() {
        assertEquals(service.DialPrecheck.Failure.ALREADY_ONLINE,
            service.DialPrecheck.check(true, true, "u", "p".toCharArray()).orElse(null));
        assertEquals(service.DialPrecheck.Failure.NO_ACCOUNT,
            service.DialPrecheck.check(false, false, "u", "p".toCharArray()).orElse(null));
        assertEquals(service.DialPrecheck.Failure.EMPTY_USERNAME,
            service.DialPrecheck.check(false, true, "", "p".toCharArray()).orElse(null));
        assertEquals(service.DialPrecheck.Failure.EMPTY_PASSWORD,
            service.DialPrecheck.check(false, true, "u", new char[0]).orElse(null));
        assertFalse(service.DialPrecheck.check(false, true, "u", "p".toCharArray()).isPresent());
        assertTrue(service.DialPrecheck.showDialog(service.DialPrecheck.Failure.EMPTY_PASSWORD));
        assertFalse(service.DialPrecheck.showDialog(service.DialPrecheck.Failure.ALREADY_ONLINE));
    }
}
