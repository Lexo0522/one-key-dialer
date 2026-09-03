package service;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Windows logon auto-start via HKCU\\...\\Run → direct EXE or javaw -jar.
 * Optional {@link #AUTOSTART_FLAG} lets the app delay tray init after logon.
 */
public class StartupService {
    public static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String RUN_SUBKEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final int ERROR_SUCCESS = 0;
    private static final int ERROR_FILE_NOT_FOUND = 2;
    private static final int ERROR_MORE_DATA = 234;
    private static final int REG_SZ = 1;
    private static final int KEY_SET_VALUE = 0x0002;
    private static final int KEY_WRITE = 0x20006;
    private static final int KEY_READ = 0x20019;
    private static final int REG_OPTION_NON_VOLATILE = 0;
    private static final Pointer HKEY_CURRENT_USER = Pointer.createConstant((int) 0x80000001);

    /** Flag appended to the Run command so the app can distinguish logon launches. */
    public static final String AUTOSTART_FLAG = "--autostart";
    /** Brief pause before UI when launched with {@link #AUTOSTART_FLAG}. */
    public static final int AUTOSTART_DELAY_MS = 3_000;

    private final String appExeName;
    private final Runnable onRegistered;
    private final Runnable onUnregistered;
    private final BiConsumer<String, Boolean> logger;
    private final RegistryStore registry;
    private final TargetResolver targetResolver;

    public StartupService(String appExeName,
                          Runnable onRegistered,
                          Runnable onUnregistered,
                          BiConsumer<String, Boolean> logger) {
        this(appExeName, onRegistered, onUnregistered, logger,
            new Win32RegistryStore(), null);
    }

    StartupService(String appExeName,
                   Runnable onRegistered,
                   Runnable onUnregistered,
                   BiConsumer<String, Boolean> logger,
                   RegistryStore registry,
                   TargetResolver targetResolver) {
        this.appExeName = appExeName;
        this.onRegistered = onRegistered != null ? onRegistered : () -> { };
        this.onUnregistered = onUnregistered != null ? onUnregistered : () -> { };
        this.logger = logger != null ? logger : (m, ok) -> { };
        this.registry = registry != null ? registry : new Win32RegistryStore();
        this.targetResolver = targetResolver != null ? targetResolver : this::resolveTarget;
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

    /** {@code "C:\\path\\PPoEDialer.exe" --autostart} */
    public static String buildExeRunCommand(String exeAbsolutePath) {
        return quoteWinArg(exeAbsolutePath) + " " + AUTOSTART_FLAG;
    }

    /** {@code "C:\\...\\javaw.exe" -jar "C:\\...\\app.jar" --autostart} */
    public static String buildJarRunCommand(String javawAbsolutePath, String jarAbsolutePath) {
        return quoteWinArg(javawAbsolutePath) + " -jar " + quoteWinArg(jarAbsolutePath)
            + " " + AUTOSTART_FLAG;
    }

    public static boolean isDirectLaunchCommand(String cmd) {
        if (cmd == null) return false;
        String c = cmd.trim();
        if (c.isEmpty()) return false;
        String lower = c.toLowerCase(Locale.ROOT);
        if (lower.contains("wscript") || lower.contains("cscript")) return false;
        if (lower.contains("javaw") && lower.contains("-jar")) return true;
        return lower.contains(".exe");
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
            LaunchTarget target = targetResolver.resolve(appClass);
            if (target == null) {
                logger.accept("注册失败: 无法确定启动路径（请使用打包后的 PPoEDialer.exe 或 JAR 运行后再勾选）", false);
                onUnregistered.run();
                return;
            }

            String startCmd = target.kind == LaunchTarget.Kind.EXE
                ? buildExeRunCommand(target.primaryPath)
                : buildJarRunCommand(target.javawPath, target.primaryPath);

            registry.write(appExeName, startCmd);

            if (isRegistrationHealthy(appClass)) {
                logger.accept("已注册开机自启动 (直接启动, 无 VBS)", true);
                logger.accept("启动命令: " + startCmd, true);
                onRegistered.run();
            } else {
                logger.accept("注册失败: 写入后校验未通过", false);
                onUnregistered.run();
            }
        } catch (Exception e) {
            logger.accept("注册失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(), false);
            onUnregistered.run();
        }
    }

    public void disableAutoStart() {
        try {
            registry.delete(appExeName);
            logger.accept("已取消开机自启动", true);
            onUnregistered.run();
        } catch (Exception e) {
            logger.accept("取消开机自启动失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(), false);
            if (isAutoStartEnabled()) onRegistered.run();
            else onUnregistered.run();
        }
    }

    /** True when the app's Run value contains a direct launch command. */
    public boolean isAutoStartEnabled() {
        try {
            String data = registry.read(appExeName);
            return data != null && isDirectLaunchCommand(data);
        } catch (Exception e) {
            logger.accept("查询开机自启动状态失败: " + e.getClass().getSimpleName(), false);
            return false;
        }
    }

    /**
     * Healthy = the Run value points at the current direct EXE/javaw target and the files exist.
     * @return true if healthy after this call (or already healthy)
     */
    public boolean isRegistrationHealthy(Class<?> appClass) {
        try {
            String data = registry.read(appExeName);
            if (data == null || !isDirectLaunchCommand(data)) return false;
            LaunchTarget target = targetResolver.resolve(appClass);
            if (target == null) return commandTargetLooksPresent(data);
            if (!new File(target.primaryPath).isFile()) return false;
            if (target.kind == LaunchTarget.Kind.JAR) {
                return target.javawPath != null && new File(target.javawPath).isFile();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Re-register once when settings require auto-start but the Run value is unhealthy. */
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
            if ((pl.endsWith(".exe") || pl.endsWith(".jar")) && new File(path).isFile()) {
                return true;
            }
            from = q2 + 1;
        }
        if (lower.endsWith(".exe") || lower.contains(".exe ")) {
            String path = cmd.trim().replace("\"", "");
            int space = path.indexOf(' ');
            if (space > 0) path = path.substring(0, space);
            if (path.toLowerCase(Locale.ROOT).endsWith(".exe") && new File(path).isFile()) return true;
        }
        return false;
    }

    private LaunchTarget resolveTarget(Class<?> appClass) {
        try {
            String processCmd = ProcessHandle.current().info().command().orElse("");
            if (!processCmd.isEmpty()
                && processCmd.toLowerCase(Locale.ROOT).endsWith(".exe")
                && !isJavaLauncherExe(processCmd)) {
                File exe = new File(processCmd).getAbsoluteFile();
                if (exe.isFile()) {
                    return new LaunchTarget(LaunchTarget.Kind.EXE, exe.getAbsolutePath(), null);
                }
            }
        } catch (Exception ignored) {
        }

        try {
            File codeSource = new File(appClass.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getAbsoluteFile();
            if (codeSource.isFile() && codeSource.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                File javaw = new File(System.getProperty("java.home"), "bin\\javaw.exe");
                if (!javaw.isFile()) javaw = new File(System.getProperty("java.home"), "bin/javaw.exe");
                if (!javaw.isFile()) {
                    logger.accept("注册失败: 找不到 javaw.exe", false);
                    return null;
                }
                return new LaunchTarget(LaunchTarget.Kind.JAR, codeSource.getAbsolutePath(), javaw.getAbsolutePath());
            }
        } catch (Exception e) {
            logger.accept("解析启动路径失败: " + e.getMessage(), false);
        }
        return null;
    }

    interface RegistryStore {
        String read(String valueName) throws Exception;

        void write(String valueName, String value) throws Exception;

        void delete(String valueName) throws Exception;
    }

    interface TargetResolver {
        LaunchTarget resolve(Class<?> appClass);
    }

    static final class LaunchTarget {
        enum Kind { EXE, JAR }

        final Kind kind;
        final String primaryPath;
        final String javawPath;

        LaunchTarget(Kind kind, String primaryPath, String javawPath) {
            this.kind = kind;
            this.primaryPath = primaryPath;
            this.javawPath = javawPath;
        }
    }

    private interface Advapi32 extends StdCallLibrary {
        Advapi32 INSTANCE = Native.load("Advapi32", Advapi32.class);

        int RegCreateKeyExW(Pointer hKey, WString subKey, int reserved, Pointer clazz,
                            int options, int samDesired, Pointer security,
                            PointerByReference result, IntByReference disposition);

        int RegOpenKeyExW(Pointer hKey, WString subKey, int options, int samDesired,
                          PointerByReference result);

        int RegSetValueExW(Pointer key, WString valueName, int reserved, int type,
                           byte[] data, int dataSize);

        int RegQueryValueExW(Pointer key, WString valueName, int reserved,
                             IntByReference type, byte[] data, IntByReference dataSize);

        int RegDeleteValueW(Pointer key, WString valueName);

        int RegCloseKey(Pointer key);
    }

    private static final class Win32RegistryStore implements RegistryStore {
        @Override
        public String read(String valueName) {
            PointerByReference keyRef = new PointerByReference();
            int code = Advapi32.INSTANCE.RegOpenKeyExW(
                HKEY_CURRENT_USER, new WString(RUN_SUBKEY), 0, KEY_READ, keyRef);
            if (code == ERROR_FILE_NOT_FOUND) return null;
            check(code, "RegOpenKeyExW");
            Pointer key = keyRef.getValue();
            try {
                IntByReference type = new IntByReference();
                IntByReference size = new IntByReference();
                code = Advapi32.INSTANCE.RegQueryValueExW(
                    key, new WString(valueName), 0, type, null, size);
                if (code == ERROR_FILE_NOT_FOUND) return null;
                check(code, "RegQueryValueExW");
                if (type.getValue() != REG_SZ || size.getValue() <= 0) return null;
                byte[] data = new byte[size.getValue()];
                while (true) {
                    code = Advapi32.INSTANCE.RegQueryValueExW(
                        key, new WString(valueName), 0, type, data, size);
                    if (code == ERROR_FILE_NOT_FOUND) return null;
                    if (code != ERROR_MORE_DATA) break;
                    data = new byte[size.getValue()];
                }
                check(code, "RegQueryValueExW");
                if (type.getValue() != REG_SZ) return null;
                return decodeString(data, size.getValue());
            } finally {
                Advapi32.INSTANCE.RegCloseKey(key);
            }
        }

        @Override
        public void write(String valueName, String value) {
            PointerByReference keyRef = new PointerByReference();
            int code = Advapi32.INSTANCE.RegCreateKeyExW(
                HKEY_CURRENT_USER, new WString(RUN_SUBKEY), 0, null,
                REG_OPTION_NON_VOLATILE, KEY_WRITE, null, keyRef, new IntByReference());
            check(code, "RegCreateKeyExW");
            Pointer key = keyRef.getValue();
            try {
                byte[] data = encodeString(value);
                code = Advapi32.INSTANCE.RegSetValueExW(
                    key, new WString(valueName), 0, REG_SZ, data, data.length);
                check(code, "RegSetValueExW");
            } finally {
                Advapi32.INSTANCE.RegCloseKey(key);
            }
        }

        @Override
        public void delete(String valueName) {
            PointerByReference keyRef = new PointerByReference();
            int code = Advapi32.INSTANCE.RegOpenKeyExW(
                HKEY_CURRENT_USER, new WString(RUN_SUBKEY), 0, KEY_SET_VALUE, keyRef);
            if (code == ERROR_FILE_NOT_FOUND) return;
            check(code, "RegOpenKeyExW");
            Pointer key = keyRef.getValue();
            try {
                code = Advapi32.INSTANCE.RegDeleteValueW(key, new WString(valueName));
                if (code != ERROR_FILE_NOT_FOUND) check(code, "RegDeleteValueW");
            } finally {
                Advapi32.INSTANCE.RegCloseKey(key);
            }
        }

        private static void check(int code, String operation) {
            if (code != ERROR_SUCCESS) {
                throw new IllegalStateException(operation + " failed (error=" + code + ")");
            }
        }

        private static byte[] encodeString(String value) {
            byte[] text = (value + "\0").getBytes(StandardCharsets.UTF_16LE);
            return text;
        }

        private static String decodeString(byte[] data, int size) {
            int length = Math.min(size, data.length);
            String value = new String(data, 0, length, StandardCharsets.UTF_16LE);
            int nul = value.indexOf('\0');
            return nul >= 0 ? value.substring(0, nul) : value;
        }
    }
}
