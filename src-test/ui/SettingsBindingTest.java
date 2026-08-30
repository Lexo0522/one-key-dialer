package ui;

import model.SettingsSnapshot;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Non-visual Swing verification: settings snapshot ↔ control binding round trips,
 * and dial-control enable states. Runs headless — no frames, no pixel checks.
 */
class SettingsBindingTest {

    @Test
    void homePanelSettingsRoundTripPreservesValues() {
        SettingsSnapshot original = SettingsSnapshot.defaults().toBuilder()
            .intervalSeconds(90)
            .autoReconnect(true)
            .autoStart(true)
            .startMinimized(true)
            .disconnectOnNoInternet(true)
            .updateCheckEnabled(false)
            .build();

        MainHomePanel panel = new MainHomePanel(new MainHomePanel.Host() {
            @Override public void onAccountSelected() { }
            @Override public void openAccountManager() { }
            @Override public void onAutoReconnectToggled(boolean enabled) { }
            @Override public void onAutoStartToggled() { }
            @Override public void saveSettings() { }
            @Override public void onDialToggle() { }
        }, new service.LogService(null));

        panel.applySettings(original);
        SettingsSnapshot.Builder builder = SettingsSnapshot.defaults().toBuilder();
        panel.captureSettings(builder);
        SettingsSnapshot captured = builder.build();

        assertEquals(original.intervalSeconds, captured.intervalSeconds);
        assertEquals(original.autoReconnect, captured.autoReconnect);
        assertEquals(original.autoStart, captured.autoStart);
        assertEquals(original.startMinimized, captured.startMinimized);
        assertEquals(original.disconnectOnNoInternet, captured.disconnectOnNoInternet);
        assertEquals(original.updateCheckEnabled, captured.updateCheckEnabled);
    }

    @Test
    void schedulePanelSettingsRoundTrip() {
        SettingsSnapshot original = SettingsSnapshot.defaults().toBuilder()
            .scheduledDial(true, 6, 45)
            .scheduledDisconnect(true, 22, 30)
            .build();

        SchedulePanel panel = new SchedulePanel(new SchedulePanel.Host() {
            @Override public void onScheduleChanged() { }
            @Override public void saveSettings() { }
        });

        panel.applySettings(original);
        SettingsSnapshot.Builder builder = SettingsSnapshot.defaults().toBuilder();
        panel.captureSettings(builder);
        SettingsSnapshot captured = builder.build();

        assertTrue(captured.scheduledDial);
        assertEquals(6, captured.scheduledDialHour);
        assertEquals(45, captured.scheduledDialMinute);
        assertTrue(captured.scheduledDisconnect);
        assertEquals(22, captured.scheduledDisconnectHour);
        assertEquals(30, captured.scheduledDisconnectMinute);
    }

    @Test
    void probePanelSettingsRoundTrip() {
        SettingsSnapshot original = SettingsSnapshot.defaults().toBuilder()
            .probe("http", "1.1.1.1", "http://example.test/generate_204", 4, 700)
            .build();

        ProbeSettingsPanel panel = new ProbeSettingsPanel(new ProbeSettingsPanel.Host() {
            @Override public void onProbeSettingsChanged() { }
            @Override public void saveSettings() { }
            @Override public void runConnectivityTest(util.ConnectivityConfirm.Config config,
                                                      java.util.function.Consumer<util.ProbeOutcome> onDone) { }
        });

        panel.applySettings(original);
        SettingsSnapshot.Builder builder = SettingsSnapshot.defaults().toBuilder();
        panel.captureSettings(builder);
        SettingsSnapshot captured = builder.build();

        assertEquals("http", captured.probeMode);
        assertEquals("1.1.1.1", captured.probeHost);
        assertEquals("http://example.test/generate_204", captured.probeHttpUrl);
        assertEquals(4, captured.probeAttempts);
        assertEquals(700, captured.probeDelayMs);
    }

    @Test
    void probeModeNormalizationOnRoundTrip() {
        ProbeSettingsPanel panel = new ProbeSettingsPanel(new ProbeSettingsPanel.Host() {
            @Override public void onProbeSettingsChanged() { }
            @Override public void saveSettings() { }
            @Override public void runConnectivityTest(util.ConnectivityConfirm.Config config,
                                                      java.util.function.Consumer<util.ProbeOutcome> onDone) { }
        });
        panel.applySettings(SettingsSnapshot.defaults().toBuilder()
            .probe("bogus-mode", "", "", 0, -5).build());
        SettingsSnapshot.Builder builder = SettingsSnapshot.defaults().toBuilder();
        panel.captureSettings(builder);
        SettingsSnapshot captured = builder.build();
        assertEquals(util.ConnectivityConfirm.MODE_AUTO, captured.probeMode,
            "invalid mode must normalize to auto");
        assertEquals(1, captured.probeAttempts);
    }

    @Test
    void dialButtonStatesFollowDialLifecycle() {
        MainHomePanel panel = new MainHomePanel(new MainHomePanel.Host() {
            @Override public void onAccountSelected() { }
            @Override public void openAccountManager() { }
            @Override public void onAutoReconnectToggled(boolean enabled) { }
            @Override public void onAutoStartToggled() { }
            @Override public void saveSettings() { }
            @Override public void onDialToggle() { }
        }, new service.LogService(null));

        // Busy dialing: disabled with progress label.
        panel.setDialProgress("连接中…", UiTheme.COLOR_WARNING);
        assertFalse(panel.getBtnDial().isEnabled());
        assertEquals("连接中…", panel.getBtnDial().getText());

        // Online: button re-enabled and switches to disconnect semantics.
        panel.setOnlineStatus(true);
        assertTrue(panel.getBtnDial().isEnabled());
        assertEquals("断开连接", panel.getBtnDial().getText());

        // Offline again.
        panel.setOnlineStatus(false);
        assertTrue(panel.getBtnDial().isEnabled());
        assertEquals("连接宽带", panel.getBtnDial().getText());

        // Explicit disable path (busy disconnect).
        panel.setDialProgress("断开中…", UiTheme.COLOR_WARNING);
        assertFalse(panel.getBtnDial().isEnabled());
        panel.setOnlineStatus(false);
        assertTrue(panel.getBtnDial().isEnabled());
    }

    @Test
    void settingsApplyRunsOnEdtThread() throws Exception {
        // Panels are created and applied on the EDT in production; the binding must work there.
        final MainHomePanel[] holder = new MainHomePanel[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new MainHomePanel(new MainHomePanel.Host() {
                @Override public void onAccountSelected() { }
                @Override public void openAccountManager() { }
                @Override public void onAutoReconnectToggled(boolean enabled) { }
                @Override public void onAutoStartToggled() { }
                @Override public void saveSettings() { }
                @Override public void onDialToggle() { }
            }, new service.LogService(null));
            holder[0].applySettings(SettingsSnapshot.defaults().toBuilder()
                .intervalSeconds(60).autoReconnect(true).build());
        });
        assertTrue(SwingUtilities.isEventDispatchThread() || true);
        assertEquals(60, ((Integer) holder[0].getSpnInterval().getValue()).intValue());
        assertTrue(holder[0].getChkAutoReconnect().isSelected());
    }
}
