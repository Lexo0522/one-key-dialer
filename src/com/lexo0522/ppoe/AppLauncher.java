package com.lexo0522.ppoe;

import model.AppVersion;
import service.StartupService;
import ui.LookAndFeelInstaller;
import ui.UiTheme;
import util.CryptoUtil;

import javax.swing.SwingUtilities;

/**
 * Process entry: L&F, optional autostart delay, create shell, first-run log lines.
 */
public final class AppLauncher {
    private AppLauncher() {
    }

    public static void main(String[] args) {
        LookAndFeelInstaller.install();

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

    static void launchOnEdt(boolean fromAutostart) {
        PPoEDialer dialer = new PPoEDialer();
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
        if (CryptoUtil.isKeyDpapiProtected()) {
            dialer.log("主密钥已使用 Windows DPAPI 保护", UiTheme.COLOR_INFO);
        }
        dialer.log("作者：Lexo0522", UiTheme.COLOR_INFO);
        dialer.log("仓库：" + AppVersion.GITHUB_URL, UiTheme.COLOR_INFO);
    }
}
