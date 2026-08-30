package com.lexo0522.ppoe;

import ui.MainHomePanel;
import ui.TrayController;

import java.awt.Color;

/**
 * UI-facing callbacks used by {@link AppServices} so the composition root does not
 * hard-depend on {@link PPoEDialer} fields. Dial-time UI interaction goes through
 * {@link service.DialView}, which {@code PPoEDialer} also implements.
 */
public interface ShellBridge {
    MainHomePanel homePanel();

    TrayController trayController();

    boolean isUiActive();

    void invokeIfUiActive(Runnable action);

    void log(String message, Color color);

    void updateStatus(boolean online);

    /** Capture current UI state into a snapshot, update runtime, persist (EDT). */
    void saveSettings();
}
