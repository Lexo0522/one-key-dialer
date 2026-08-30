package ui;

import model.AppVersion;
import service.BackgroundExecutor;
import service.UpdateModule;
import service.UpdateModule.Asset;
import service.UpdateModule.CheckResult;
import service.UpdateModule.PreparedUpdate;
import service.UpdateModule.Progress;
import service.UpdateModule.Release;
import service.UpdateModule.VerifiedPackage;

import javax.swing.JOptionPane;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * UI for update check / download / apply, backed by {@link UpdateModule}.
 * Network and file IO stay off the EDT; the app exits only after the installer
 * process was confirmed started.
 */
public final class UpdateCheckUi {
    public interface Host {
        Component dialogOwner();

        BackgroundExecutor backgroundExecutor();

        UpdateModule updateModule();

        void invokeIfUiActive(Runnable action);

        void logInfo(String message);

        void logSuccess(String message);

        void logWarning(String message);

        void logError(String message);

        /** Persist settings / accounts before replace-on-exit. */
        void prepareForUpdateApply();

        /** Hard exit after apply script launched. */
        void exitForUpdate();
    }

    private final Host host;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    public UpdateCheckUi(Host host) {
        this.host = host;
    }

    public void check(boolean interactive) {
        if (!busy.compareAndSet(false, true)) {
            host.logWarning("更新检查已在进行中…");
            return;
        }
        host.logInfo("正在检查更新…");
        host.backgroundExecutor().submit(() -> {
            CheckResult result = host.updateModule().check(AppVersion.NUMERIC);
            host.invokeIfUiActive(() -> {
                try {
                    present(result, interactive);
                } finally {
                    busy.set(false);
                }
            });
        });
    }

    public void present(CheckResult result, boolean interactive) {
        if (result == null) return;
        if (result.updateAvailable) {
            host.logWarning(result.message.replace('\n', ' '));
        } else if (result.message != null && result.message.startsWith("检查更新失败")) {
            host.logWarning(result.message);
        } else {
            host.logSuccess(result.message != null ? result.message : "检查完成");
        }
        if (!interactive) {
            // Quiet mode: only prompt when update exists
            if (result.updateAvailable) {
                offerUpdateActions(result, true);
            }
            return;
        }
        if (!result.updateAvailable) {
            JOptionPane.showMessageDialog(host.dialogOwner(),
                result.message != null ? result.message : "检查完成",
                "检查更新", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        offerUpdateActions(result, false);
    }

    private void offerUpdateActions(CheckResult result, boolean quietBanner) {
        boolean canDownload = result.hasInstallableAsset();
        String base = result.message != null ? result.message : "发现新版本";
        if (quietBanner) {
            base = "启动检查：" + base;
        }
        if (canDownload) {
            Asset asset = result.release.preferredWindowsAsset().get();
            Object[] options = {"下载并安装", "打开发布页", "稍后"};
            int opt = JOptionPane.showOptionDialog(host.dialogOwner(),
                base + "\n\n推荐安装包: " + asset.name
                    + (asset.sizeBytes > 0 ? " (" + formatSize(asset.sizeBytes) + ")" : "")
                    + "\n下载到: %APPDATA%\\PPoEDialer\\updates\\"
                    + "\n\n安装时程序会退出并由脚本覆盖/启动安装包。",
                "发现新版本",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                null, options, options[0]);
            if (opt == 0) {
                startDownload(result, asset);
            } else if (opt == 1) {
                openUrl(result.releaseUrl);
            }
        } else {
            Object[] options = {"打开发布页", "关闭"};
            int opt = JOptionPane.showOptionDialog(host.dialogOwner(),
                base + "\n\n当前 Release 未找到可自动安装的 Windows 包，请到网页手动下载。",
                "发现新版本",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                null, options, options[0]);
            if (opt == 0) openUrl(result.releaseUrl);
        }
    }

    private void startDownload(CheckResult result, Asset asset) {
        if (!busy.compareAndSet(false, true)) {
            host.logWarning("已有下载任务进行中");
            return;
        }
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        bar.setString("准备下载…");
        javax.swing.JDialog dlg = new javax.swing.JDialog(
            parentWindow(host.dialogOwner()), "下载更新");
        dlg.setModal(false);
        dlg.setLayout(new BorderLayout(8, 8));
        dlg.add(bar, BorderLayout.CENTER);
        javax.swing.JButton cancelBtn = new javax.swing.JButton("取消");
        AtomicBoolean cancel = new AtomicBoolean(false);
        cancelBtn.addActionListener(e -> {
            cancel.set(true);
            cancelBtn.setEnabled(false);
            bar.setString("正在取消…");
        });
        dlg.add(cancelBtn, BorderLayout.SOUTH);
        dlg.setSize(420, 100);
        dlg.setLocationRelativeTo(host.dialogOwner());
        dlg.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        dlg.setVisible(true);

        Release release = result.release;
        Progress progress = new Progress() {
            @Override
            public void onProgress(long downloaded, long total) {
                host.invokeIfUiActive(() -> {
                    if (total > 0) {
                        int pct = (int) Math.min(100, (downloaded * 100) / total);
                        bar.setIndeterminate(false);
                        bar.setValue(pct);
                        bar.setString(formatSize(downloaded) + " / " + formatSize(total)
                            + " (" + pct + "%)");
                    } else {
                        bar.setIndeterminate(true);
                        bar.setString(formatSize(downloaded));
                    }
                });
            }

            @Override
            public void onStatus(String message) {
                host.invokeIfUiActive(() -> {
                    host.logInfo(message);
                    if (message != null && message.startsWith("正在下载")) {
                        bar.setString(message);
                    }
                });
            }
        };

        host.backgroundExecutor().submitLong(() -> {
            VerifiedPackage pkg = null;
            Exception error = null;
            try {
                pkg = host.updateModule().download(release, asset, progress, cancel);
            } catch (Exception ex) {
                error = ex;
            }
            final VerifiedPackage finalPkg = pkg;
            final Exception finalError = error;
            host.invokeIfUiActive(() -> {
                busy.set(false);
                dlg.dispose();
                if (finalError != null) {
                    String msg = finalError.getMessage() != null
                        ? finalError.getMessage() : finalError.getClass().getSimpleName();
                    host.logError("更新下载失败: " + msg);
                    JOptionPane.showMessageDialog(host.dialogOwner(),
                        "下载失败: " + msg, "更新", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                afterDownload(finalPkg);
            });
        });
    }

    private void afterDownload(VerifiedPackage pkg) {
        host.logSuccess("更新包已下载并通过 SHA-256 校验: " + pkg.file.getAbsolutePath());
        Object[] options = {"立即安装并重启", "仅保留文件", "打开发布页"};
        int opt = JOptionPane.showOptionDialog(host.dialogOwner(),
            "下载完成:\n" + pkg.file.getAbsolutePath()
                + "\n\n「立即安装」将退出程序，由更新脚本覆盖安装目录或启动安装包。",
            "安装更新",
            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
            null, options, options[0]);
        if (opt == 1) return;
        if (opt == 2) {
            openUrl(pkg.release != null ? pkg.release.htmlUrl : null);
            return;
        }
        try {
            host.prepareForUpdateApply();
            PreparedUpdate prepared = host.updateModule().prepare(pkg);
            host.logInfo("即将应用更新: " + prepared.applyScript.getAbsolutePath());
            if (!host.updateModule().launchInstall(prepared)) {
                // Installer never started — keep running and report.
                host.logError("安装启动失败，程序继续运行；请手动运行安装包");
                JOptionPane.showMessageDialog(host.dialogOwner(),
                    "安装启动失败，程序继续运行。\n可手动运行已下载的安装包。",
                    "更新", JOptionPane.ERROR_MESSAGE);
                return;
            }
            host.exitForUpdate();
        } catch (Exception ex) {
            host.logError("准备安装失败: " + ex.getMessage());
            JOptionPane.showMessageDialog(host.dialogOwner(),
                "准备安装失败: " + ex.getMessage(), "更新", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void scheduleQuietCheck(long delayMs) {
        host.backgroundExecutor().schedule(() -> check(false), delayMs);
    }

    private void openUrl(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ex) {
            host.logWarning("无法打开浏览器: " + ex.getMessage());
        }
    }

    private static java.awt.Window parentWindow(Component c) {
        if (c instanceof java.awt.Window) return (java.awt.Window) c;
        return c != null ? SwingUtilities.getWindowAncestor(c) : null;
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
