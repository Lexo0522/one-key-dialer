package service;

import util.AtomicFiles;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure + file IO helpers for Windows rasphone.pbk editing.
 * DialService stays a thin rasdial wrapper.
 */
public final class RasPhonebook {
    private static final Pattern SECTION = Pattern.compile("^\\[([^\\]]+)]\\s*$");

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
    private final Consumer<String> infoLogger;
    private final Consumer<String> warnLogger;
    private final Consumer<String> errorLogger;
    private final AtomicReference<Status> lastStatus = new AtomicReference<>();

    public RasPhonebook(String connectionName,
                        Consumer<String> infoLogger,
                        Consumer<String> warnLogger,
                        Consumer<String> errorLogger) {
        this.connectionName = connectionName;
        this.infoLogger = infoLogger != null ? infoLogger : m -> {};
        this.warnLogger = warnLogger != null ? warnLogger : m -> {};
        this.errorLogger = errorLogger != null ? errorLogger : m -> {};
    }

    public static File defaultPbkFile() {
        String appData = System.getenv("APPDATA");
        if (appData == null) return null;
        return new File(appData, "Microsoft\\Network\\Connections\\PBK\\rasphone.pbk");
    }

    public Status getLastStatus() {
        return lastStatus.get();
    }

    public Status snapshotStatus() {
        File pbk = defaultPbkFile();
        if (pbk == null) {
            Status s = new Status(null, false, false, "-", null, null, "APPDATA 不可用", 0L);
            lastStatus.set(s);
            return s;
        }
        boolean exists = pbk.exists();
        Charset cs = detectPbkCharset(pbk);
        boolean has = false;
        DeviceHint hint = null;
        try {
            if (exists) {
                String content = new String(Files.readAllBytes(pbk.toPath()), cs);
                has = contentContainsSection(content, connectionName);
                hint = findPppoeDeviceHint(content);
            }
        } catch (Exception e) {
            Status s = new Status(pbk, exists, false, cs.name(), null, null,
                "读取失败: " + e.getMessage(), System.currentTimeMillis());
            lastStatus.set(s);
            return s;
        }
        Status prev = lastStatus.get();
        Status s = new Status(
            pbk, exists, has, cs.name(),
            hint != null ? hint.port : (prev != null ? prev.lastPort : null),
            hint != null ? hint.device : (prev != null ? prev.lastDevice : null),
            prev != null ? prev.lastWriteResult : "尚未写入",
            prev != null ? prev.lastWriteMillis : 0L
        );
        lastStatus.set(s);
        return s;
    }

    /**
     * Ensure connection section exists; create via atomic rewrite if missing.
     * @return true if entry present after call
     */
    public boolean ensureEntry() {
        if (!DialService.isValidConnectionName(connectionName)) {
            errorLogger.accept("连接名不合法: " + connectionName);
            return false;
        }
        File pbkFile = defaultPbkFile();
        if (pbkFile == null) {
            errorLogger.accept("无法获取 APPDATA");
            return false;
        }
        try {
            if (hasEntry(pbkFile, connectionName)) {
                recordStatus(pbkFile, true, true, detectPbkCharset(pbkFile).name(),
                    null, null, "已存在，未改写", System.currentTimeMillis());
                return true;
            }

            infoLogger.accept("连接 \"" + connectionName + "\" 不存在，正在自动创建...");
            File pbkDir = pbkFile.getParentFile();
            if (pbkDir != null && !pbkDir.exists() && !pbkDir.mkdirs()) {
                errorLogger.accept("无法创建电话簿目录");
                return false;
            }

            Charset charset = detectPbkCharset(pbkFile);
            String existing = pbkFile.exists()
                ? new String(Files.readAllBytes(pbkFile.toPath()), charset)
                : "";

            if (pbkFile.exists()) {
                File backupPbk = new File(pbkDir, "rasphone.pbk.bak");
                try {
                    Files.copy(pbkFile.toPath(), backupPbk.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ex) {
                    warnLogger.accept("电话簿备份失败: " + ex.getMessage());
                }
            }

            String without = removeSection(existing, connectionName);
            DeviceHint hint = findPppoeDeviceHint(without);
            if (hint == null) {
                hint = new DeviceHint("PPPoE5-0", "WAN Miniport (PPPOE)", false);
                warnLogger.accept("未找到现有 PPPoE 设备条目，使用默认 Port=" + hint.port
                    + "（若创建失败请检查适配器名称）");
            } else {
                infoLogger.accept("复用现有 PPPoE 设备: " + hint.device + " / " + hint.port);
            }

            String entry = buildPhoneBookEntry(connectionName, hint.port, hint.device);
            String merged = without;
            if (!merged.isEmpty() && !merged.endsWith("\n")) merged += "\n";
            merged += entry;

            AtomicFiles.writeString(pbkFile.toPath(), merged, charset);

            boolean ok = hasEntry(pbkFile, connectionName);
            if (ok) {
                infoLogger.accept("PPPoE连接 \"" + connectionName + "\" 创建成功");
                recordStatus(pbkFile, true, true, charset.name(), hint.port, hint.device,
                    "创建成功", System.currentTimeMillis());
            } else {
                warnLogger.accept("连接已写入但验证失败，请重启程序后重试");
                recordStatus(pbkFile, true, false, charset.name(), hint.port, hint.device,
                    "写入后验证失败", System.currentTimeMillis());
            }
            return ok;
        } catch (Exception e) {
            warnLogger.accept("创建失败: " + e.getMessage());
            recordStatus(pbkFile, pbkFile.exists(), false, "-", null, null,
                "异常: " + e.getMessage(), System.currentTimeMillis());
            return false;
        }
    }

    public boolean hasEntry() {
        File pbk = defaultPbkFile();
        if (pbk == null) return false;
        try {
            return hasEntry(pbk, connectionName);
        } catch (Exception e) {
            errorLogger.accept("检测电话簿失败: " + e.getMessage());
            return false;
        }
    }

    // ---------- pure helpers (unit-testable) ----------

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

    private boolean hasEntry(File pbkFile, String connName) throws Exception {
        if (!pbkFile.exists()) return false;
        Charset charset = detectPbkCharset(pbkFile);
        List<String> lines = Files.readAllLines(pbkFile.toPath(), charset);
        String target = "[" + connName + "]";
        for (String line : lines) {
            if (line != null && line.trim().equals(target)) return true;
        }
        return false;
    }

    private void recordStatus(File pbk, boolean exists, boolean hasEntry, String charset,
                              String port, String device, String writeResult, long millis) {
        Status prev = lastStatus.get();
        lastStatus.set(new Status(
            pbk, exists, hasEntry, charset,
            port != null ? port : (prev != null ? prev.lastPort : null),
            device != null ? device : (prev != null ? prev.lastDevice : null),
            writeResult, millis
        ));
    }
}
