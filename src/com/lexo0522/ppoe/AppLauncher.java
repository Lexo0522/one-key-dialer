package com.lexo0522.ppoe;

import model.AppFiles;
import model.AppVersion;
import model.SettingsSnapshot;
import service.StartupService;
import storage.SettingsStore;
import ui.LookAndFeelInstaller;
import ui.UiTheme;
import util.AppPaths;

import javax.swing.SwingUtilities;
import java.io.File;

/**
 * Process entry: theme, L&F, optional autostart delay, create shell, first-run log lines.
 */
public final class AppLauncher {
    private AppLauncher() {
    }

    public static void main(String[] args) {
        // Resolve the theme before any component exists — components capture the
        // palette at construction; a theme change therefore needs a restart.
        String themePref = loadThemePreference();
        UiTheme.init(themePref);
        LookAndFeelInstaller.install(UiTheme.isDark());

        final boolean fromAutostart = StartupService.argsContainAutostart(args);
        if (fromAutostart && StartupService.AUTOSTART_DELAY_MS > 0) {
            try {
                Thread.sleep(StartupService.AUTOSTART_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        SwingUtilities.invokeLater(() -> launchOnEdt(fromAutostart));
    }

    /** Best-effort early read of the theme preference; the full load happens in the shell. */
    static String loadThemePreference() {
        try {
            File settingsFile = new File(AppPaths.getDataDir(PPoEDialer.class), AppFiles.SETTINGS);
            SettingsSnapshot stored = new SettingsStore(settingsFile).load();
            if (stored != null && stored.uiTheme != null) {
                return stored.uiTheme;
            }
        } catch (Exception ignored) {
        }
        return SettingsSnapshot.THEME_SYSTEM;
    }

    static void launchOnEdt(boolean fromAutostart) {
        PPoEDialer dialer = PPoEDialer.create();
        if (!dialer.isStartMinimizedSelected()) {
            dialer.setVisible(true);
        }
        logStartupBanner(dialer, fromAutostart);
    }

    static void logStartupBanner(PPoEDialer dialer, boolean fromAutostart) {
        dialer.log("PPPoE校园网拨号工具 " + AppVersion.DISPLAY + " 已启动", UiTheme.COLOR_SUCCESS);
        if (fromAutostart) {
            dialer.log("通过开机自启动启动 (延迟 "
                + (StartupService.AUTOSTART_DELAY_MS / 1000) + "s)", UiTheme.COLOR_INFO);
        }
        dialer.log("作者：Lexo0522", UiTheme.COLOR_INFO);
        dialer.log("仓库：" + AppVersion.GITHUB_URL, UiTheme.COLOR_INFO);
    }
}
