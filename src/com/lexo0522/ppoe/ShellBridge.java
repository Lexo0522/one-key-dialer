package com.lexo0522.ppoe;

import model.DialSnapshot;
import ui.MainHomePanel;
import ui.TrayController;

import java.awt.Color;
import java.awt.Component;

/**
 * UI-facing callbacks used by {@link AppServices} / {@link ShellDialHost}
 * so the composition root does not hard-depend on {@link PPoEDialer} fields.
 */
public interface ShellBridge {
    Component dialogOwner();

    MainHomePanel homePanel();

    TrayController trayController();

    boolean isUiActive();

    void invokeIfUiActive(Runnable action);

    void log(String message, Color color);

    void updateStatus(boolean online);

    void showNotification(String title, String message);

    void updateButtonState(boolean enabled);

    void updateDialProgress(String phase);

    boolean validateBeforeDial(boolean interactive);

    DialSnapshot captureDialSnapshotOnEdt();

    void saveCurrentAccount();

    void saveSettings();

    void addHistoryRecord(String operation, String account, String result,
                          String duration, String traffic);

    void markTooltipDirty();
}
