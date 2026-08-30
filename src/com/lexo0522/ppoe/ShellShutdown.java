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

        /** Synchronous write of a debounced pending settings save. */
        void flushPendingSettingsSave();

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
        host.flushPendingSettingsSave();
        host.logService().flush();
        host.historyService().saveIfDirty();
    }

    /**
     * Full user-initiated exit. Never returns on success ({@code System.exit(0)}).
     */
    public void exitProgram() {
        try {
            host.saveSettings();
        } catch (Exception e) {
            logError("退出时保存设置失败", e);
        }
        try {
            host.flushPendingSettingsSave();
        } catch (Exception e) {
            logError("退出时写入设置失败", e);
        }
        try {
            host.saveCurrentAccount();
        } catch (Exception e) {
            logError("退出时保存账号失败", e);
        }
        try {
            host.historyService().saveIfDirty();
        } catch (Exception e) {
            logError("退出时保存历史失败", e);
        }
        try {
            host.logService().flush();
        } catch (Exception e) {
            logError("退出时刷新日志失败", e);
        }
        try {
            host.services().shutdownRuntime();
        } catch (Exception e) {
            logError("退出时停止服务失败", e);
        }
        try {
            host.accountSession().clearPasswordsInMemory();
        } catch (Exception e) {
            logError("退出时清除内存密码失败", e);
        }
        try {
            MainHomePanel home = host.homePanel().get();
            if (home != null) home.getTxtPassword().setText("");
        } catch (Exception e) {
            logError("退出时清空密码框失败", e);
        }
        try {
            TrayController tray = host.trayController().get();
            if (tray != null) tray.remove();
        } catch (Exception e) {
            logError("退出时移除托盘失败", e);
        }
        try {
            host.disposeWindow();
        } catch (Exception e) {
            logError("退出时关闭窗口失败", e);
        }
        System.exit(0);
    }

    /**
     * Best-effort persist for JVM shutdown hook (do not stop services or System.exit).
     */
    public void onJvmShutdownHook() {
        try {
            host.saveSettings();
            host.flushPendingSettingsSave();
            host.saveCurrentAccount();
            host.logService().flush();
            host.historyService().saveIfDirty();
        } catch (Exception e) {
            logError("JVM 关闭钩子持久化失败", e);
        }
    }

    private void logError(String message, Exception e) {
        try {
            host.logService().logThrowable(message, e, null);
        } catch (Exception ignored) {
        }
    }

    public void installJvmShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::onJvmShutdownHook, "ShutdownHook"));
    }
}
