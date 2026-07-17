package service;

import util.ProcessIO;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Windows logon auto-start via HKCU\...\Run → direct EXE or javaw -jar.
 * No VBS/WSH. Optional {@link #AUTOSTART_FLAG} lets the app delay tray init after logon.
 */
public class StartupService {
    public static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    /** Flag appended to the Run command so the app can distinguish logon launches. */
    public static final String AUTOSTART_FLAG = "--autostart";
    /**
     * Brief pause before UI when launched with {@link #AUTOSTART_FLAG}
     * so Explorer/tray are more likely to be ready.
     */
    public static final int AUTOSTART_DELAY_MS = 3_000;
    /** @deprecated Prefer {@link #AUTOSTART_DELAY_MS}; kept for any external refs. */
    public static final int STARTUP_DELAY_MS = AUTOSTART_DELAY_MS;
    private static final String[] LEGACY_RUN_VALUE_NAMES = {"自动PPoE拨号"};
    private static final String LEGACY_VBS_FILE = "pppoe_startup.vbs";

    private final String appExeName;
    private final Runnable onRegistered;
    private final Runnable onUnregistered;
    private final BiConsumer<String, Boolean> logger;

    public StartupService(String appExeName,
                          Runnable onRegistered,
                          Runnable onUnregistered,
                          BiConsumer<String, Boolean> logger) {
        this.appExeName = appExeName;
        this.onRegistered = onRegistered;
        this.onUnregistered = onUnregistered;
        this.logger = logger != null ? logger : (m, ok) -> { };
    }

    // ==================== Pure helpers (unit-testable) ====================

    /** Quote a Windows path for embedding in a Run REG_SZ command line. */
    public static String quoteWinArg(String path) {
        if (path == null) return "\"\"";
        String p = path;
        if (p.indexOf('"') >= 0) {
            p = p.replace("\"", "\\\"");
        }
        return "\"" + p + "\"";
    }

    /** {@code "C:\path\PPoEDialer.exe" --autostart} */
    public static String buildExeRunCommand(String exeAbsolutePath) {
        return quoteWinArg(exeAbsolutePath) + " " + AUTOSTART_FLAG;
    }

    /** {@code "C:\...\javaw.exe" -jar "C:\...\app.jar" --autostart} */
    public static String buildJarRunCommand(String javawAbsolutePath, String jarAbsolutePath) {
        return quoteWinArg(javawAbsolutePath) + " -jar " + quoteWinArg(jarAbsolutePath)
            + " " + AUTOSTART_FLAG;
    }

    public static boolean isLegacyVbsCommand(String cmd) {
        if (cmd == null) return false;
        String lower = cmd.toLowerCase(Locale.ROOT);
        return lower.contains("wscript") && lower.contains(".vbs");
    }

    public static boolean isDirectLaunchCommand(String cmd) {
        if (cmd == null) return false;
        String c = cmd.trim();
        if (c.isEmpty() || isLegacyVbsCommand(c)) return false;
        String lower = c.toLowerCase(Locale.ROOT);
        if (lower.contains("javaw") && lower.contains("-jar")) return true;
        return lower.contains(".exe");
    }

    public static boolean isPlausibleStartupCommand(String cmd) {
        if (cmd == null) return false;
        String c = cmd.trim();
        if (c.isEmpty()) return false;
        if (isLegacyVbsCommand(c)) return true;
        return isDirectLaunchCommand(c);
    }

    /**
     * Parse {@code reg query ... /v name} stdout for the REG_SZ data of {@code valueName}.
     * Returns null if not found.
     */
    public static String parseRegQueryValue(String regOutput, String valueName) {
        if (regOutput == null || valueName == null) return null;
        for (String line : regOutput.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            // Typical: "    PPoEDialer    REG_SZ    \"C:\\...\\PPoEDialer.exe\" --autostart"
            if (!t.startsWith(valueName)) continue;
            // Split on 2+ spaces / tabs
            String[] parts = t.split("\\s{2,}|\\t+");
            if (parts.length >= 3 && parts[0].equals(valueName)) {
                StringBuilder data = new StringBuilder(parts[2]);
                for (int i = 3; i < parts.length; i++) {
                    data.append("  ").append(parts[i]);
                }
                return data.toString().trim();
            }
            // Fallback: after REG_SZ
            int idx = t.toUpperCase(Locale.ROOT).indexOf("REG_SZ");
            if (idx >= 0) {
                String data = t.substring(idx + "REG_SZ".length()).trim();
                if (!data.isEmpty()) return data;
            }
        }
        return null;
    }

    public static boolean isJavaLauncherExe(String processCmd) {
        if (processCmd == null || processCmd.isEmpty()) return true;
        String name = new File(processCmd).getName().toLowerCase(Locale.ROOT);
        return "java.exe".equals(name) || "javaw.exe".equals(name);
    }

    public static boolean argsContainAutostart(String[] args) {
        if (args == null) return false;
        for (String a : args) {
            if (AUTOSTART_FLAG.equals(a)) return true;
        }
        return false;
    }

    // ==================== Public API ====================

    public void enableAutoStart(Class<?> appClass) {
        try {
            LaunchTarget target = resolveTarget(appClass);
            if (target == null) {
                logger.accept("注册失败: 无法确定启动路径（请使用打包后的 PPoEDialer.exe 或 JAR 运行后再勾选）", false);
                onUnregistered.run();
                return;
            }

            String startCmd = target.kind == LaunchTarget.Kind.EXE
                ? buildExeRunCommand(target.primaryPath)
                : buildJarRunCommand(target.javawPath, target.primaryPath);

            deleteAllKnownRunValues();
            deleteLegacyStartupArtifacts();

            int code = runReg(new String[]{
                "reg", "add", RUN_KEY,
                "/v", appExeName, "/t", "REG_SZ", "/d", startCmd, "/f"
            }, true);

            if (code == 0 && isRegistrationHealthy(appClass)) {
                logger.accept("已注册开机自启动 (直接启动, 无 VBS)", true);
                logger.accept("启动命令: " + startCmd, true);
                onRegistered.run();
            } else {
                logger.accept("注册失败: 写入后校验未通过 (exit=" + code + ")", false);
                onUnregistered.run();
            }
        } catch (Exception e) {
            logger.accept("注册失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(), false);
            onUnregistered.run();
        }
    }

    public void disableAutoStart() {
        try {
            deleteAllKnownRunValues();
            deleteLegacyStartupArtifacts();
            logger.accept("已取消开机自启动", true);
            onUnregistered.run();
        } catch (Exception e) {
            logger.accept("取消开机自启动失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(), false);
            if (isAutoStartEnabled()) onRegistered.run();
            else onUnregistered.run();
        }
    }

    /**
     * True if any known Run value is present with a plausible command.
     * Legacy Chinese name or old VBS registration still counts as enabled (heal will migrate).
     */
    public boolean isAutoStartEnabled() {
        try {
            for (String name : allValueNames()) {
                String data = queryRunValue(name);
                if (data == null) continue;
                if (!isPlausibleStartupCommand(data)) continue;
                if (commandTargetLooksPresent(data)) return true;
                // Key present with plausible cmd but missing file — still "enabled" for UI truth of intent
                return true;
            }
        } catch (Exception e) {
            logger.accept("查询开机自启动状态失败: " + e.getClass().getSimpleName(), false);
        }
        return false;
    }

    /**
     * Healthy = ASCII value name present, points at direct EXE/javaw (not VBS),
     * and launch target file still exists.
     */
    public boolean isRegistrationHealthy(Class<?> appClass) {
        try {
            String data = queryRunValue(appExeName);
            if (data == null || !isDirectLaunchCommand(data)) return false;
            LaunchTarget target = resolveTarget(appClass);
            if (target == null) {
                return commandTargetLooksPresent(data);
            }
            if (!new File(target.primaryPath).isFile()) return false;
            if (target.kind == LaunchTarget.Kind.JAR) {
                return target.javawPath != null && new File(target.javawPath).isFile();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * If user wants auto-start (settings) but registration is missing/unhealthy, re-register once.
     * @return true if healthy after this call (or already healthy)
     */
    public boolean ensureAutoStartHealthy(Class<?> appClass, boolean settingsWantAutoStart) {
        if (!settingsWantAutoStart) {
            return isRegistrationHealthy(appClass);
        }
        if (isRegistrationHealthy(appClass)) {
            return true;
        }
        logger.accept("检测到开机自启动配置异常，正在重新注册…", true);
        enableAutoStart(appClass);
        boolean ok = isRegistrationHealthy(appClass);
        if (!ok) {
            logger.accept("自动修复开机自启动失败，请用打包版 PPoEDialer.exe 重新勾选「开机自动启动」", false);
        } else {
            logger.accept("开机自启动已修复", true);
        }
        return ok;
    }

    // ==================== Internals ====================

    private List<String> allValueNames() {
        List<String> names = new ArrayList<>();
        names.add(appExeName);
        names.addAll(Arrays.asList(LEGACY_RUN_VALUE_NAMES));
        if (!"PPoEDialer".equals(appExeName)) {
            names.add("PPoEDialer");
        }
        return names;
    }

    private void deleteAllKnownRunValues() throws Exception {
        for (String name : allValueNames()) {
            runReg(new String[]{"reg", "delete", RUN_KEY, "/v", name, "/f"}, false);
        }
    }

    /** Best-effort cleanup of old VBS helper under %APPDATA%\PPoEDialer. */
    private void deleteLegacyStartupArtifacts() {
        File vbs = getLegacyStartupScriptFile();
        if (vbs != null && vbs.isFile()) {
            if (!vbs.delete()) {
                logger.accept("旧启动脚本删除失败: " + vbs.getAbsolutePath(), false);
            }
        }
    }

    private File getLegacyStartupScriptFile() {
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            return new File(new File(appData, "PPoEDialer"), LEGACY_VBS_FILE);
        }
        return new File(System.getProperty("user.dir"), LEGACY_VBS_FILE);
    }

    private String queryRunValue(String valueName) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "reg", "query", RUN_KEY, "/v", valueName);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = ProcessIO.readAll(p.getInputStream(), ProcessIO.childCharset());
        int code = ProcessIO.waitOrKill(p, 10, TimeUnit.SECONDS);
        if (code != 0) return null;
        return parseRegQueryValue(out, valueName);
    }

    private boolean commandTargetLooksPresent(String cmd) {
        if (cmd == null) return false;
        String lower = cmd.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int q1 = cmd.indexOf('"', from);
            if (q1 < 0) break;
            int q2 = cmd.indexOf('"', q1 + 1);
            if (q2 < 0) break;
            String path = cmd.substring(q1 + 1, q2);
            String pl = path.toLowerCase(Locale.ROOT);
            if (pl.endsWith(".vbs") || pl.endsWith(".exe") || pl.endsWith(".jar")) {
                if (new File(path).isFile()) return true;
            }
            from = q2 + 1;
        }
        if (lower.endsWith(".exe") || lower.contains(".exe ")) {
            // Unquoted trailing / simple path
            String path = cmd.trim().replace("\"", "");
            int space = path.indexOf(' ');
            if (space > 0) path = path.substring(0, space);
            if (path.toLowerCase(Locale.ROOT).endsWith(".exe") && new File(path).isFile()) return true;
        }
        return false;
    }

    private int runReg(String[] cmd, boolean logOutput) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = ProcessIO.readAll(p.getInputStream(), ProcessIO.childCharset());
        int code = ProcessIO.waitOrKill(p, 15, TimeUnit.SECONDS);
        if (logOutput && code != 0 && out != null && !out.trim().isEmpty()) {
            logger.accept(out.trim(), false);
        }
        return code;
    }

    private LaunchTarget resolveTarget(Class<?> appClass) {
        try {
            String processCmd = ProcessHandle.current().info().command().orElse("");
            if (!processCmd.isEmpty()
                && processCmd.toLowerCase(Locale.ROOT).endsWith(".exe")
                && !isJavaLauncherExe(processCmd)) {
                File exe = new File(processCmd).getAbsoluteFile();
                if (exe.isFile()) {
                    File parent = exe.getParentFile();
                    String workDir = parent != null ? parent.getAbsolutePath() : exe.getParent();
                    return new LaunchTarget(LaunchTarget.Kind.EXE, exe.getAbsolutePath(), workDir, null);
                }
            }
        } catch (Exception ignored) {
        }

        try {
            File codeSource = new File(appClass.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getAbsoluteFile();
            if (codeSource.isFile() && codeSource.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                File jar = codeSource;
                File baseDir = jar.getParentFile();
                String workDir = baseDir != null ? baseDir.getAbsolutePath() : jar.getParent();
                File javaw = new File(System.getProperty("java.home"), "bin\\javaw.exe");
                if (!javaw.isFile()) {
                    javaw = new File(System.getProperty("java.home"), "bin/javaw.exe");
                }
                if (!javaw.isFile()) {
                    logger.accept("注册失败: 找不到 javaw.exe", false);
                    return null;
                }
                return new LaunchTarget(LaunchTarget.Kind.JAR, jar.getAbsolutePath(), workDir, javaw.getAbsolutePath());
            }
        } catch (Exception e) {
            logger.accept("解析启动路径失败: " + e.getMessage(), false);
        }
        return null;
    }

    private static final class LaunchTarget {
        enum Kind { EXE, JAR }

        final Kind kind;
        final String primaryPath;
        final String workDir;
        final String javawPath;

        LaunchTarget(Kind kind, String primaryPath, String workDir, String javawPath) {
            this.kind = kind;
            this.primaryPath = primaryPath;
            this.workDir = workDir;
            this.javawPath = javawPath;
        }
    }
}
