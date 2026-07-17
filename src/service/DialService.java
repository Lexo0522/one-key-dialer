package service;

import model.DialSnapshot;
import util.ProcessIO;

import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Windows rasdial integration. Phonebook ensure/create lives in {@link RasPhonebook}.
 * Uses argument vectors (no cmd string concat with secrets).
 */
public class DialService {
    private static final Pattern CONN_NAME_OK = Pattern.compile("^[A-Za-z0-9_\\-]{1,64}$");

    private final String connectionName;
    private final Supplier<String> activeConnectionNameSupplier;
    private final Consumer<String> activeConnectionNameSetter;
    private final Runnable resetConnectionCache;
    private final Consumer<String> infoLogger;
    private final Consumer<String> warnLogger;
    private final Consumer<String> errorLogger;
    private final RasPhonebook phonebook;

    public DialService(String connectionName,
                       Supplier<String> activeConnectionNameSupplier,
                       Consumer<String> activeConnectionNameSetter,
                       Runnable resetConnectionCache,
                       Consumer<String> infoLogger,
                       Consumer<String> warnLogger,
                       Consumer<String> errorLogger) {
        this.connectionName = connectionName;
        this.activeConnectionNameSupplier = activeConnectionNameSupplier;
        this.activeConnectionNameSetter = activeConnectionNameSetter;
        this.resetConnectionCache = resetConnectionCache;
        this.infoLogger = infoLogger;
        this.warnLogger = warnLogger;
        this.errorLogger = errorLogger;
        this.phonebook = new RasPhonebook(connectionName, infoLogger, warnLogger, errorLogger);
    }

    public RasPhonebook phonebook() {
        return phonebook;
    }

    public static class DialResult {
        public final int code;
        public final String output;

        public DialResult(int code, String output) {
            this.code = code;
            this.output = output != null ? output : "";
        }

        public boolean isSuccess() {
            return code == 0;
        }
    }

    public static boolean isValidConnectionName(String name) {
        return name != null && CONN_NAME_OK.matcher(name).matches();
    }

    /** Prefer exit code; fall back to output substrings for localized rasdial text. */
    public static String describeFailure(DialResult result) {
        if (result == null) {
            return "拨号失败：未知错误。请查看运行日志，或到「网络诊断」检查网卡/电话簿。";
        }
        String outStr = result.output == null ? "" : result.output;
        int code = result.code;
        if (code == 691 || outStr.contains("691")) {
            return "账号或密码错误（691）。请核对学号/账号与密码后重试。";
        }
        if (code == 678 || outStr.contains("678")) {
            return "服务器无响应（678）。请确认已插网线/连上校园网，稍后再试。";
        }
        if (code == 651 || outStr.contains("651")) {
            return "调制解调器/宽带设备出错（651）。请检查网卡驱动或重启电脑。";
        }
        if (code == 623 || outStr.contains("623")) {
            return "找不到宽带连接（623）。程序会尝试写入电话簿；可到「网络诊断 → 电话簿/探测」查看。";
        }
        if (code == 633 || outStr.contains("633")) {
            return "设备正忙或配置异常（633）。请关闭其他拨号程序后重试。";
        }
        if (code == 676 || outStr.contains("676")) {
            return "线路忙（676）。请稍后再拨。";
        }
        if (code == 680 || outStr.contains("680")) {
            return "无拨号音/链路未就绪（680）。请检查网线或校园网端口。";
        }
        if (code == 720 || outStr.contains("720")) {
            return "PPP 配置错误（720）。可尝试重启网卡或联系校园网运维。";
        }
        if (code == 734 || outStr.contains("734")) {
            return "PPP 链路被服务器终止（734）。常见于认证失败或会话冲突。";
        }
        if (code == 735 || outStr.contains("735")) {
            return "地址被服务器拒绝（735）。请稍后重试或更换网络环境。";
        }
        if (code == 797 || outStr.contains("797")) {
            return "找不到调制解调器驱动（797）。请在设备管理器检查 WAN Miniport (PPPOE)。";
        }
        if (code == -1) {
            return "拨号超时或流程异常。请查看日志，或到「网络诊断」运行 Ping/IP 配置。";
        }
        if (code != 0) {
            return "拨号失败（错误码 " + code + "）。可到「网络诊断」排查，或把日志末尾发给支持人员。";
        }
        return "拨号未成功。请查看运行日志。";
    }

    /**
     * Run rasdial with credentials from snapshot. Password becomes a process argv
     * {@link String} here (required by {@link ProcessBuilder}); it may appear in local
     * process listings until the child exits. Prefer keeping secrets as {@code char[]}
     * everywhere else and clearing the snapshot in {@code finally}.
     */
    public DialResult dial(DialSnapshot snapshot) throws Exception {
        if (snapshot == null) {
            return new DialResult(-1, "empty snapshot");
        }
        String username = snapshot.username;
        String password = snapshot.passwordAsString();
        try {
            if (!isValidConnectionName(connectionName)) {
                errorLogger.accept("非法连接名: " + connectionName);
                return new DialResult(-1, "invalid connection name");
            }
            if (!phonebook.ensureEntry()) {
                return new DialResult(-1, "ensure connection failed");
            }
            activeConnectionNameSetter.accept(connectionName);
            resetConnectionCache.run();

            String activeConnName = activeConnectionNameSupplier.get();
            infoLogger.accept("开始拨号... 连接: " + activeConnName);

            // argv form — never embed password in cmd /c
            ProcessBuilder pb = new ProcessBuilder("rasdial", activeConnName, username, password);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            Charset cs = ProcessIO.childCharset();
            try {
                ProcessIO.drainLines(p.getInputStream(), cs, line -> {
                    out.append(line).append('\n');
                    infoLogger.accept("  > " + line);
                });
                int code = ProcessIO.waitOrKill(p, 60, TimeUnit.SECONDS);
                return new DialResult(code, out.toString());
            } finally {
                if (p.isAlive()) p.destroyForcibly();
            }
        } finally {
            snapshot.clear();
        }
    }

    public int disconnect(String activeConnName) throws Exception {
        return runDisconnect(activeConnName, true);
    }

    public int disconnectSync(String activeConnName) throws Exception {
        return runDisconnect(activeConnName, false);
    }

    private int runDisconnect(String activeConnName, boolean logLines) throws Exception {
        if (activeConnName == null || activeConnName.isEmpty() || !isValidConnectionName(activeConnName)) {
            errorLogger.accept("断开失败: 非法连接名");
            return -1;
        }
        ProcessBuilder pb = new ProcessBuilder("rasdial", activeConnName, "/disconnect");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try {
            Charset cs = ProcessIO.childCharset();
            ProcessIO.drainLines(p.getInputStream(), cs, line -> {
                if (logLines) infoLogger.accept("  > " + line);
            });
            return ProcessIO.waitOrKill(p, 30, TimeUnit.SECONDS);
        } finally {
            if (p.isAlive()) p.destroyForcibly();
        }
    }
}
