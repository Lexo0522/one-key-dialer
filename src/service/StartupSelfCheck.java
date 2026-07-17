package service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Environment probes at startup (commands on PATH, writable data files).
 * Logging-only — does not alter dial / autostart registration.
 */
public final class StartupSelfCheck {
    public interface Logger {
        void info(String message);

        void warn(String message);
    }

    private StartupSelfCheck() {
    }

    /**
     * @param logger      message sink
     * @param settingsFile settings.ini path
     * @param accountsFile accounts.ini path
     * @param historyFile  history.csv path
     * @param probeSummary one-line probe config for the log (may be null)
     */
    public static void run(Logger logger, File settingsFile, File accountsFile, File historyFile,
                           String probeSummary) {
        if (logger == null) return;
        checkCommandAvailability(logger, "cmd", "/c", "where rasdial", "rasdial");
        checkCommandAvailability(logger, "cmd", "/c", "where ping", "ping");
        checkCommandAvailability(logger, "cmd", "/c", "where reg", "reg");
        checkWritablePath(logger, settingsFile, "设置文件");
        checkWritablePath(logger, accountsFile, "账号文件");
        checkWritablePath(logger, historyFile, "历史记录文件");
        if (probeSummary != null && !probeSummary.isEmpty()) {
            logger.info("探测配置: " + probeSummary);
        }
    }

    static void checkCommandAvailability(Logger logger, String... args) {
        if (args == null || args.length < 2) return;
        String label = args[args.length - 1];
        String[] command = Arrays.copyOf(args, args.length - 1);
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished || p.exitValue() != 0) {
                logger.warn("启动自检: 未检测到命令 " + label);
            }
        } catch (Exception e) {
            logger.warn("启动自检: 检查命令 " + label + " 失败: " + e.getClass().getSimpleName());
        } finally {
            if (p != null) p.destroy();
        }
    }

    static void checkWritablePath(Logger logger, File file, String label) {
        if (file == null) {
            logger.warn("启动自检: " + label + " 路径为空");
            return;
        }
        try {
            File target = file.getAbsoluteFile();
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warn("启动自检: 无法创建 " + label + " 目录");
                return;
            }
            if (target.exists()) {
                if (!target.canRead() || !target.canWrite()) {
                    logger.warn("启动自检: " + label + " 可能无读写权限");
                }
            } else {
                Files.write(target.toPath(), new byte[0], StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (!target.delete()) {
                    logger.warn("启动自检: " + label + " 测试文件删除失败");
                }
            }
        } catch (Exception e) {
            logger.warn("启动自检: " + label + " 不可写: " + e.getMessage());
        }
    }
}
