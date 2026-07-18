package com.lexo0522.ppoe;

import service.AccountSession;
import service.HistoryService;
import service.LogService;
import ui.MainHomePanel;
import ui.TrayController;

import java.util.function.Supplier;

/**
 * Ordered teardown: persist → stop services → wipe secrets → tray → dispose → exit.
 */
public final class ShellShutdown {
    public interface Host {
        void saveSettings();

        void saveCurrentAccount();

        HistoryService historyService();

        LogService logService();

        AppServices services();

        AccountSession accountSession();

        Supplier<MainHomePanel> homePanel();

        Supplier<TrayController> trayController();

        /** Dispose the main window (no System.exit). */
        void disposeWindow();
    }

    private final Host host;

    public ShellShutdown(Host host) {
        this.host = host;
    }

    public void flushPendingPersistence() {
        host.logService().flush();
        host.historyService().saveIfDirty();
    }

    /**
     * Full user-initiated exit. Never returns on success ({@code System.exit(0)}).
     */
    public void exitProgram() {
        try {
            host.saveSettings();
        } catch (Exception ignored) {
        }
        try {
            host.saveCurrentAccount();
        } catch (Exception ignored) {
        }
        try {
            host.historyService().saveIfDirty();
        } catch (Exception ignored) {
        }
        try {
            host.logService().flush();
        } catch (Exception ignored) {
        }
        try {
            host.services().shutdownRuntime();
        } catch (Exception ignored) {
        }
        try {
            host.accountSession().clearPasswordsInMemory();
        } catch (Exception ignored) {
        }
        try {
            MainHomePanel home = host.homePanel().get();
            if (home != null) home.getTxtPassword().setText("");
        } catch (Exception ignored) {
        }
        try {
            TrayController tray = host.trayController().get();
            if (tray != null) tray.remove();
        } catch (Exception ignored) {
        }
        try {
            host.disposeWindow();
        } catch (Exception ignored) {
        }
        System.exit(0);
    }

    /**
     * Best-effort persist for JVM shutdown hook (do not stop services or System.exit).
     */
    public void onJvmShutdownHook() {
        try {
            host.saveSettings();
            host.saveCurrentAccount();
            host.logService().flush();
            host.historyService().saveIfDirty();
        } catch (Exception ignored) {
        }
    }

    public void installJvmShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::onJvmShutdownHook, "ShutdownHook"));
    }
}
