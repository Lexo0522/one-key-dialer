package service;

import model.DialCredentials;
import util.AtomicFiles;
import util.ProcessIO;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single Windows RAS module. Phonebook preparation, device selection, encoding,
 * rasdial execution, active-connection tracking, timeouts, and error mapping are all
 * internal; the outside sees {@link DialPort} plus a small diagnostics surface.
 * <p>
 * Internal test seams: {@link ProcessRunner} (child processes) and the phonebook
 * file location — automated tests use fakes and temp files, never a real network.
 */
public final class WindowsRasModule implements DialPort {
    private static final Pattern CONN_NAME_OK = Pattern.compile("^[A-Za-z0-9_\\-]{1,64}$");
    private static final Pattern SECTION = Pattern.compile("^\\[([^\\]]+)]\\s*$");
    private static final long DIAL_TIMEOUT_SECONDS = 60;
    private static final long DISCONNECT_TIMEOUT_SECONDS = 30;

    /** Executed-process seam. Production delegates to {@link ProcessIO}. */
    public interface ProcessRunner {
        ProcessIO.Result run(List<String> command, long timeout, TimeUnit unit,
                             Charset charset, Consumer<String> lineConsumer) throws Exception;
    }

    public static final class DeviceHint {
        public final String port;
        public final String device;
        public final boolean fromExisting;

        public DeviceHint(String port, String device, boolean fromExisting) {
            this.port = port;
            this.device = device;
            this.fromExisting = fromExisting;
        }
    }

    public static final class Status {
        public final File pbkFile;
        public final boolean exists;
        public final boolean hasEntry;
        public final String charsetName;
        public final String lastPort;
        public final String lastDevice;
        public final String lastWriteResult;
        public final long lastWriteMillis;

        public Status(File pbkFile, boolean exists, boolean hasEntry, String charsetName,
                      String lastPort, String lastDevice, String lastWriteResult, long lastWriteMillis) {
            this.pbkFile = pbkFile;
            this.exists = exists;
            this.hasEntry = hasEntry;
            this.charsetName = charsetName;
            this.lastPort = lastPort;
            this.lastDevice = lastDevice;
            this.lastWriteResult = lastWriteResult;
            this.lastWriteMillis = lastWriteMillis;
        }
    }

    /** One-line status for Diag / logs. */
    public static String formatStatus(Status st) {
        if (st == null) return "(无电话簿状态)";
        StringBuilder sb = new StringBuilder();
        sb.append("pbk=").append(st.pbkFile != null ? st.pbkFile.getAbsolutePath() : "(null)");
        sb.append(" exists=").append(st.exists);
        sb.append(" hasEntry=").append(st.hasEntry);
        sb.append(" charset=").append(st.charsetName);
        if (st.lastPort != null) sb.append(" port=").append(st.lastPort);
        if (st.lastDevice != null) sb.append(" device=").append(st.lastDevice);
        sb.append(" lastWrite=").append(st.lastWriteResult != null ? st.lastWriteResult : "-");
        return sb.toString();
    }

    private final String connectionName;
    private final ProcessRunner runner;
    private final File phonebookFile;
    private final AtomicRef activeConnection = new AtomicRef();
    /** True when the native RasDialW path may be tried before rasdial.exe. */
    private final boolean nativeDialPreferred;
    /** Optional preferred PPPoE device (port + device name); null = auto-detect / default. */
    private volatile DeviceHint preferredDevice;

    public WindowsRasModule(String connectionName) {
        this(connectionName, ProcessIO::run, defaultPbkFile());
    }

    /** Production entry: prefers the native RasDialW path (password never in argv). */
    public WindowsRasModule(String connectionName, boolean nativeDialPreferred) {
        this(connectionName, ProcessIO::run, defaultPbkFile(), nativeDialPreferred);
    }

    public WindowsRasModule(String connectionName, ProcessRunner runner, File phonebookFile) {
        this(connectionName, runner, phonebookFile, false);
    }

    private WindowsRasModule(String connectionName, ProcessRunner runner, File phonebookFile,
                             boolean nativeDialPreferred) {
        this.connectionName = connectionName;
        this.runner = runner;
        this.phonebookFile = phonebookFile;
        this.nativeDialPreferred = nativeDialPreferred;
    }

    public static File defaultPbkFile() {
        String appData = System.getenv("APPDATA");
        if (appData == null) return null;
        return new File(appData, "Microsoft\\Network\\Connections\\PBK\\rasphone.pbk");
    }

    @Override public String connectionName() {
        return connectionName;
    }

    public static boolean isValidConnectionName(String name) {
        return name != null && CONN_NAME_OK.matcher(name).matches();
    }

    // ==================== DialPort ====================

    @Override
    public DialResult connect(DialCredentials credentials) throws Exception {
        if (credentials == null) {
            return new DialResult(-1, "empty credentials");
        }
        try {
            if (!isValidConnectionName(connectionName)) {
                return new DialResult(-1, "invalid connection name");
            }
            if (!ensureEntry()) {
                return new DialResult(-1, "ensure connection failed");
            }
            activeConnection.set(connectionName);

            if (nativeDialPreferred) {
                Integer nativeCode = NativeRasDial.dial(connectionName, phonebookFile, credentials);
                if (nativeCode != null && nativeCode != NativeRasDial.ERROR_INVALID_STRUCT_SIZE) {
                    // error text is derived from the code; describeFailure maps it
                    return new DialResult(nativeCode, nativeCode == 0 ? "RasDial API" : "");
                }
                // 632 = the native struct was rejected by this Windows build
                // (e.g. an OS update changed the RAS layout) — fall back to
                // rasdial.exe below so dialing still works.
            }

            // argv form — never embed the password in a cmd string
            String username = credentials.username();
            String password = credentials.passwordAsString();
            ProcessIO.Result result = runner.run(
                Arrays.asList("rasdial", connectionName, username, password),
                DIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS, ProcessIO.childCharset(),
                null);
            return new DialResult(result.exitCode, result.output);
        } finally {
            // the credential instance is cleared by the orchestrator; this String
            // reference dies with the frame
        }
    }

    @Override
    public int disconnect() throws Exception {
        String target = activeConnection.get() != null ? activeConnection.get() : connectionName;
        if (!isValidConnectionName(target)) {
            return -1;
        }
        ProcessIO.Result result = runner.run(
            Arrays.asList("rasdial", target, "/disconnect"),
            DISCONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS, ProcessIO.childCharset(), null);
        if (result.exitCode == 0) {
            activeConnection.compareAndSet(target, null);
        }
        return result.exitCode;
    }

    /** The connection name a disconnect would currently target. */
    public String activeConnectionName() {
        String active = activeConnection.get();
        return active != null ? active : connectionName;
    }

    // ==================== failure mapping ====================

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
        if (code == 632 || outStr.contains("632")) {
            return "拨号接口与当前 Windows 版本不兼容（632）。请更新本程序到最新版，或暂时使用系统自带「宽带连接」拨号。";
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

    // ==================== phonebook ====================

    /**
     * Override Port/Device used when creating (or force-rewriting) the RAS entry.
     * Pass {@code null} to restore auto-detect from existing phonebook / defaults.
     */
    public void setPreferredDevice(DeviceHint hint) {
        this.preferredDevice = hint;
    }

    public DeviceHint getPreferredDevice() {
        return preferredDevice;
    }

    /**
     * List PPPoE-like device hints from the phonebook (and a safe default).
     */
    public List<DeviceHint> listDeviceOptions() {
        LinkedHashMap<String, DeviceHint> map = new LinkedHashMap<>();
        if (phonebookFile != null && phonebookFile.exists()) {
            try {
                Charset cs = detectPbkCharset(phonebookFile);
                String content = new String(Files.readAllBytes(phonebookFile.toPath()), cs);
                for (DeviceHint h : collectPppoeDevices(content)) {
                    String key = h.port + "|" + h.device;
                    map.putIfAbsent(key, h);
                }
            } catch (Exception ignored) {
            }
        }
        DeviceHint def = new DeviceHint("PPPoE5-0", "WAN Miniport (PPPOE)", false);
        map.putIfAbsent(def.port + "|" + def.device, def);
        return new ArrayList<>(map.values());
    }

    /** Force rewrite of the connection section (e.g. after user picks another device). */
    public boolean rewriteEntry() {
        if (phonebookFile == null) {
            return false;
        }
        try {
            if (phonebookFile.exists()) {
                Charset charset = detectPbkCharset(phonebookFile);
                String existing = new String(Files.readAllBytes(phonebookFile.toPath()), charset);
                String without = removeSection(existing, connectionName);
                AtomicFiles.writeString(phonebookFile.toPath(), without, charset);
            }
            return ensureEntry();
        } catch (Exception e) {
            return false;
        }
    }

    public Status snapshotStatus() {
        if (phonebookFile == null) {
            return new Status(null, false, false, "-", null, null, "APPDATA 不可用", 0L);
        }
        boolean exists = phonebookFile.exists();
        Charset cs = detectPbkCharset(phonebookFile);
        boolean has = false;
        DeviceHint hint = null;
        try {
            if (exists) {
                String content = new String(Files.readAllBytes(phonebookFile.toPath()), cs);
                has = contentContainsSection(content, connectionName);
                hint = findPppoeDeviceHint(content);
            }
        } catch (Exception e) {
            return new Status(phonebookFile, exists, false, cs.name(), null, null,
                "读取失败: " + e.getMessage(), System.currentTimeMillis());
        }
        return new Status(phonebookFile, exists, has, cs.name(),
            hint != null ? hint.port : null,
            hint != null ? hint.device : null,
            "尚未写入", 0L);
    }

    public boolean hasEntry() {
        if (phonebookFile == null) return false;
        try {
            return phonebookFile.exists()
                && contentContainsSection(readPbk(phonebookFile), connectionName);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Ensure the connection section exists; create via atomic rewrite if missing.
     * @return true if entry present after call
     */
    public boolean ensureEntry() {
        if (!isValidConnectionName(connectionName)) {
            return false;
        }
        if (phonebookFile == null) {
            return false;
        }
        try {
            if (hasEntry()) {
                return true;
            }
            File pbkDir = phonebookFile.getParentFile();
            if (pbkDir != null && !pbkDir.exists() && !pbkDir.mkdirs()) {
                return false;
            }

            Charset charset = detectPbkCharset(phonebookFile);
            String existing = phonebookFile.exists() ? readPbk(phonebookFile) : "";

            if (phonebookFile.exists()) {
                File backupPbk = new File(pbkDir, "rasphone.pbk.bak");
                try {
                    Files.copy(phonebookFile.toPath(), backupPbk.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) {
                }
            }

            String without = removeSection(existing, connectionName);
            DeviceHint hint = preferredDevice;
            if (hint == null) {
                hint = findPppoeDeviceHint(without);
            }
            if (hint == null) {
                hint = new DeviceHint("PPPoE5-0", "WAN Miniport (PPPOE)", false);
            }

            String entry = buildPhoneBookEntry(connectionName, hint.port, hint.device);
            String merged = without;
            if (!merged.isEmpty() && !merged.endsWith("\n")) merged += "\n";
            merged += entry;

            AtomicFiles.writeString(phonebookFile.toPath(), merged, charset);
            return contentContainsSection(readPbk(phonebookFile), connectionName);
        } catch (Exception e) {
            return false;
        }
    }

    private static String readPbk(File pbk) throws Exception {
        return new String(Files.readAllBytes(pbk.toPath()), detectPbkCharset(pbk));
    }

    // ==================== pure helpers (unit-testable) ====================

    public static boolean contentContainsSection(String content, String connName) {
        if (content == null || connName == null) return false;
        String target = "[" + connName + "]";
        for (String line : content.split("\\R", -1)) {
            if (line != null && line.trim().equals(target)) return true;
        }
        return false;
    }

    public static String removeSection(String content, String connName) {
        if (content == null || content.isEmpty()) return "";
        String[] lines = content.split("\\R", -1);
        StringBuilder sb = new StringBuilder(content.length());
        boolean skipping = false;
        String target = "[" + connName + "]";
        for (String line : lines) {
            Matcher m = SECTION.matcher(line.trim());
            if (m.matches()) {
                skipping = ("[" + m.group(1) + "]").equals(target);
                if (skipping) continue;
            }
            if (!skipping) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        }
        String out = sb.toString();
        while (out.endsWith("\n\n\n")) out = out.substring(0, out.length() - 1);
        return out;
    }

    public static DeviceHint findPppoeDeviceHint(String content) {
        if (content == null || content.isEmpty()) return null;
        String[] lines = content.split("\\R");
        String port = null;
        String device = null;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("PreferredPort=") && t.toUpperCase().contains("PPPOE")) {
                port = t.substring("PreferredPort=".length()).trim();
            } else if (t.startsWith("PreferredDevice=") && t.toUpperCase().contains("PPPOE")) {
                device = t.substring("PreferredDevice=".length()).trim();
            } else if (t.startsWith("Port=") && t.toUpperCase().contains("PPPOE") && port == null) {
                port = t.substring("Port=".length()).trim();
            } else if (t.startsWith("Device=") && t.toUpperCase().contains("PPPOE") && device == null) {
                device = t.substring("Device=".length()).trim();
            }
            if (port != null && device != null) return new DeviceHint(port, device, true);
        }
        if (port != null) {
            return new DeviceHint(port, device != null ? device : "WAN Miniport (PPPOE)", true);
        }
        return null;
    }

    /** Collect unique PreferredPort/PreferredDevice pairs that look like PPPoE. */
    public static List<DeviceHint> collectPppoeDevices(String content) {
        ArrayList<DeviceHint> out = new ArrayList<>();
        if (content == null || content.isEmpty()) return out;
        String port = null;
        String device = null;
        for (String line : content.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("[")) {
                if (port != null && device != null && looksPppoe(port, device)) {
                    out.add(new DeviceHint(port, device, true));
                }
                port = null;
                device = null;
                continue;
            }
            if (t.startsWith("PreferredPort=")) {
                port = t.substring("PreferredPort=".length()).trim();
            } else if (t.startsWith("PreferredDevice=")) {
                device = t.substring("PreferredDevice=".length()).trim();
            } else if (t.startsWith("Port=") && port == null) {
                port = t.substring("Port=".length()).trim();
            } else if (t.startsWith("Device=") && device == null) {
                device = t.substring("Device=".length()).trim();
            }
        }
        if (port != null && device != null && looksPppoe(port, device)) {
            out.add(new DeviceHint(port, device, true));
        }
        return out;
    }

    private static boolean looksPppoe(String port, String device) {
        String p = port != null ? port.toUpperCase() : "";
        String d = device != null ? device.toUpperCase() : "";
        return p.contains("PPPOE") || d.contains("PPPOE");
    }

    public static Charset detectPbkCharset(File pbkFile) {
        if (pbkFile == null || !pbkFile.exists()) {
            return Charset.defaultCharset();
        }
        try {
            byte[] head = Files.readAllBytes(pbkFile.toPath());
            if (head.length >= 2) {
                if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
                    return StandardCharsets.UTF_16LE;
                }
                if ((head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
                    return StandardCharsets.UTF_16BE;
                }
            }
            if (head.length >= 3
                && (head[0] & 0xFF) == 0xEF
                && (head[1] & 0xFF) == 0xBB
                && (head[2] & 0xFF) == 0xBF) {
                return StandardCharsets.UTF_8;
            }
            int zeros = 0;
            int limit = Math.min(head.length, 256);
            for (int i = 0; i < limit; i++) if (head[i] == 0) zeros++;
            if (zeros > limit / 4) return StandardCharsets.UTF_16LE;
        } catch (Exception ignored) {
        }
        return Charset.defaultCharset();
    }

    public static String buildPhoneBookEntry(String connName, String port, String device) {
        return "[" + connName + "]\n" +
            "Encoding=1\n" +
            "PBVersion=8\n" +
            "Type=5\n" +
            "AutoLogon=0\n" +
            "UseRasCredentials=0\n" +
            "LowDateTime=0\n" +
            "HighDateTime=0\n" +
            "DialParamsUID=0\n" +
            "Guid=" + UUID.randomUUID().toString().replace("-", "").toUpperCase() + "\n" +
            "VpnStrategy=0\n" +
            "ExcludedProtocols=0\n" +
            "LcpExtensions=1\n" +
            "DataEncryption=8\n" +
            "SwCompression=1\n" +
            "NegotiateMultilinkAlways=1\n" +
            "SkipDoubleDialDialog=0\n" +
            "DialMode=0\n" +
            "OverridePref=15\n" +
            "RedialAttempts=3\n" +
            "RedialSeconds=60\n" +
            "IdleDisconnectSeconds=0\n" +
            "RedialOnLinkFailure=1\n" +
            "CallbackMode=0\n" +
            "CustomDialDll=\n" +
            "CustomDialFunc=\n" +
            "CustomRasDialDll=\n" +
            "ForceSecureCompartment=0\n" +
            "DisableIKENameEkuCheck=0\n" +
            "AuthenticateServer=0\n" +
            "ShareMsFilePrint=1\n" +
            "BindMsNetClient=1\n" +
            "SharedPhoneNumbers=0\n" +
            "GlobalDeviceSettings=0\n" +
            "PrerequisiteEntry=\n" +
            "PrerequisitePbk=\n" +
            "PreferredPort=" + port + "\n" +
            "PreferredDevice=" + device + "\n" +
            "PreferredBps=0\n" +
            "PreferredHwFlow=0\n" +
            "PreferredProtocol=0\n" +
            "PreferredCompression=0\n" +
            "PreferredSpeaker=0\n" +
            "PreferredMdmProtocol=0\n" +
            "PreviewUserPw=1\n" +
            "PreviewDomain=0\n" +
            "PreviewPhoneNumber=0\n" +
            "ShowDialingProgress=1\n" +
            "ShowMonitorIconInTaskBar=1\n" +
            "CustomAuthKey=0\n" +
            "AuthRestrictions=552\n" +
            "IpPrioritizeRemote=1\n" +
            "IpInterfaceMetric=0\n" +
            "IpHeaderCompression=0\n" +
            "IpAddress=0.0.0.0\n" +
            "IpDnsAddress=0.0.0.0\n" +
            "IpDns2Address=0.0.0.0\n" +
            "IpWinsAddress=0.0.0.0\n" +
            "IpWins2Address=0.0.0.0\n" +
            "IpAssign=1\n" +
            "IpNameAssign=1\n" +
            "IpDnsFlags=0\n" +
            "IpNBTFlags=0\n" +
            "TcpWindowSize=0\n" +
            "UseFlags=3\n" +
            "IpSecFlags=0\n" +
            "IpDnsSuffix=\n" +
            "Ipv6Assign=1\n" +
            "Ipv6Address=::\n" +
            "Ipv6PrefixLength=0\n" +
            "Ipv6PrioritizeRemote=1\n" +
            "Ipv6InterfaceMetric=0\n" +
            "Ipv6NameAssign=1\n" +
            "Ipv6DnsAddress=::\n" +
            "Ipv6Dns2Address=::\n" +
            "Ipv6Prefix=0000000000000000\n" +
            "Ipv6InterfaceId=0000000000000000\n" +
            "DisableClassBasedDefaultRoute=0\n" +
            "DisableMobility=0\n" +
            "NetworkOutageTime=0\n" +
            "IDI=\n" +
            "IDR=\n" +
            "ImsConfig=0\n" +
            "IdiType=0\n" +
            "IdrType=0\n" +
            "ProvisionType=0\n" +
            "PreSharedKey=\n" +
            "CacheCredentials=0\n" +
            "NumCustomPolicy=0\n" +
            "NumEku=0\n" +
            "UseMachineRootCert=0\n" +
            "Disable_IKEv2_Fragmentation=0\n" +
            "PlumbIKEv2TSAsRoutes=0\n" +
            "NumServers=0\n" +
            "RouteVersion=1\n" +
            "NumRoutes=0\n" +
            "NumNrptRules=0\n" +
            "AutoTiggerCapable=0\n" +
            "NumAppIds=0\n" +
            "NumClassicAppIds=0\n" +
            "SecurityDescriptor=\n" +
            "ApnInfoProviderId=\n" +
            "ApnInfoUsername=\n" +
            "ApnInfoPassword=\n" +
            "ApnInfoAccessPoint=\n" +
            "ApnInfoAuthentication=1\n" +
            "ApnInfoCompression=0\n" +
            "DeviceComplianceEnabled=0\n" +
            "DeviceComplianceSsoEnabled=0\n" +
            "DeviceComplianceSsoEku=\n" +
            "DeviceComplianceSsoIssuer=\n" +
            "FlagsSet=0\n" +
            "Options=0\n" +
            "DisableDefaultDnsSuffixes=0\n" +
            "NumTrustedNetworks=0\n" +
            "NumDnsSearchSuffixes=0\n" +
            "PowershellCreatedProfile=0\n" +
            "ProxyFlags=0\n" +
            "ProxySettingsModified=0\n" +
            "ProvisioningAuthority=\n" +
            "AuthTypeOTP=0\n" +
            "GREKeyDefined=0\n" +
            "NumPerAppTrafficFilters=0\n" +
            "AlwaysOnCapable=0\n" +
            "DeviceTunnel=0\n" +
            "PrivateNetwork=0\n" +
            "ManagementApp=\n\n" +
            "NETCOMPONENTS=\n" +
            "ms_msclient=1\n" +
            "ms_server=1\n\n" +
            "MEDIA=rastapi\n" +
            "Port=" + port + "\n" +
            "Device=" + device + "\n\n" +
            "DEVICE=PPPoE\n" +
            "PhoneNumber=\n" +
            "AreaCode=\n" +
            "CountryCode=0\n" +
            "CountryID=0\n" +
            "UseDialingRules=0\n" +
            "Comment=\n" +
            "FriendlyName=\n" +
            "LastSelectedPhone=0\n" +
            "PromoteAlternates=0\n" +
            "TryNextAlternateOnFail=1\n\n";
    }

    /** Minimal single-slot reference for active-connection tracking. */
    private static final class AtomicRef {
        private volatile String value;

        String get() {
            return value;
        }

        void set(String v) {
            value = v;
        }

        void compareAndSet(String expected, String next) {
            synchronized (this) {
                if (java.util.Objects.equals(value, expected)) {
                    value = next;
                }
            }
        }
    }
}
