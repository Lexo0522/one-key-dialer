import model.AccountInfo;
import model.AppSettings;
import model.DialLifecycle;
import model.PasswordChars;
import model.SessionTraffic;
import service.AutoReconnectService;
import service.DialService;
import service.HistoryService;
import service.RasPhonebook;
import service.RuntimeSettings;
import service.ScheduleService;
import service.StartupService;
import storage.AccountStore;
import storage.HistoryStore;
import util.ConnectivityConfirm;
import util.CryptoUtil;
import util.FormatUtil;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Zero-dependency smoke tests. Run:
 *   javac -encoding UTF-8 -d bin-test -cp bin test/SelfTest.java
 *   java -cp bin-test;bin SelfTest
 */
public class SelfTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testDialLifecycle();
        testConnNameValidation();
        testDialFailureDescribe();
        testCsvRoundTrip();
        testHistoryService();
        testFormatUtil();
        testCryptoAesAndLegacy();
        testStartupHelpers();
        testScheduleAndReconnectHelpers();
        testConnectivityConfirm();
        testHardeningHelpers();
        testAccountCsvLayout();
        System.out.println("----");
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void assertTrue(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }

    private static void testDialLifecycle() {
        DialLifecycle life = new DialLifecycle();
        assertTrue("idle initially", life.isIdle());
        assertTrue("begin dial", life.tryBeginDial());
        assertTrue("busy while dialing", life.isBusy());
        assertTrue("cannot double dial", !life.tryBeginDial());
        assertTrue("cannot disconnect while dialing", !life.tryBeginDisconnect());
        life.end();
        assertTrue("begin disconnect", life.tryBeginDisconnect());
        life.end();
        assertTrue("idle after end", life.isIdle());
    }

    private static void testConnNameValidation() {
        assertTrue("valid name", DialService.isValidConnectionName("pppoe_native_java"));
        assertTrue("reject brackets", !DialService.isValidConnectionName("a[b]"));
        assertTrue("reject empty", !DialService.isValidConnectionName(""));
        assertTrue("reject space", !DialService.isValidConnectionName("bad name"));
    }

    private static void testDialFailureDescribe() {
        String m691 = DialService.describeFailure(new DialService.DialResult(691, ""));
        assertTrue("691 map", m691 != null && m691.contains("账号或密码"));
        String m678 = DialService.describeFailure(new DialService.DialResult(0, "error 678"));
        assertTrue("678 from output", m678 != null && m678.contains("678"));
        String timeout = DialService.describeFailure(new DialService.DialResult(-1, ""));
        assertTrue("timeout -1", timeout != null && timeout.contains("超时"));
        String other = DialService.describeFailure(new DialService.DialResult(42, "x"));
        assertTrue("unknown code message", other != null && other.contains("42"));
    }

    private static void testCsvRoundTrip() throws Exception {
        File tmp = Files.createTempFile("hist", ".csv").toFile();
        tmp.deleteOnExit();
        HistoryStore store = new HistoryStore(tmp);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"2026-01-01 12:00:00", "拨号", "acc,1", "成功", "--", "1,2 MB"});
        store.save(rows);
        List<String[]> loaded = new ArrayList<>();
        store.load(loaded);
        assertTrue("csv rows=1", loaded.size() == 1);
        assertTrue("csv account with comma", "acc,1".equals(loaded.get(0)[2]));
        assertTrue("csv traffic join", loaded.get(0)[5].contains("MB"));
    }

    private static void testHistoryService() throws Exception {
        File tmp = Files.createTempFile("hist-svc", ".csv").toFile();
        tmp.deleteOnExit();
        AtomicReference<String> warn = new AtomicReference<>();
        HistoryService svc = new HistoryService(new HistoryStore(tmp), warn::set);
        svc.addRecord("拨号", "u1", "成功", "--", "--");
        assertTrue("history dirty", svc.dirtyFlag().get());
        assertTrue("history saveIfDirty", svc.saveIfDirty());
        assertTrue("history clean after save", !svc.dirtyFlag().get());
        HistoryService loaded = new HistoryService(new HistoryStore(tmp), warn::set);
        loaded.load();
        assertTrue("history loaded 1", loaded.records().size() == 1);
        assertTrue("history op", "拨号".equals(loaded.records().get(0)[1]));
        assertTrue("history no warn", warn.get() == null);
    }

    private static void testFormatUtil() {
        assertTrue("bytes B", FormatUtil.formatBytes(10).endsWith(" B"));
        assertTrue("bytes KB", FormatUtil.formatBytes(2048).contains("KB"));
        assertTrue("speed", FormatUtil.formatSpeed(2048).contains("KB/s"));
        assertTrue("duration", "00:01:05".equals(FormatUtil.formatDuration(65)));
    }

    private static void testCryptoAesAndLegacy() throws Exception {
        File key = Files.createTempFile("master", ".key").toFile();
        key.deleteOnExit();
        Files.deleteIfExists(key.toPath());
        CryptoUtil.init(key);
        String plain = "campus-pass-测试";
        String enc = CryptoUtil.encrypt(plain);
        assertTrue("aes prefix", CryptoUtil.isAesFormat(enc));
        assertTrue("aes decrypt", plain.equals(CryptoUtil.decrypt(enc)));
        String legacy = CryptoUtil.encryptLegacyXor(plain, "PPoE2026SecretKey");
        assertTrue("legacy migrate decrypt", plain.equals(CryptoUtil.decrypt(legacy)));
        boolean threw = false;
        try {
            CryptoUtil.decrypt("AES1:not-valid-base64!!!");
        } catch (Exception e) {
            threw = true;
        }
        assertTrue("aes fail closed", threw);
        // On Windows, master.key should preferably be DPAPI-wrapped after init
        if (util.DpapiUtil.isLikelyWindows()) {
            assertTrue("dpapi key preferred on windows", CryptoUtil.isKeyDpapiProtected() || key.length() == 32);
        }
    }

    private static void testStartupHelpers() {
        assertTrue("quote path",
            "\"C:\\app\\PPoEDialer.exe\"".equals(StartupService.quoteWinArg("C:\\app\\PPoEDialer.exe")));
        assertTrue("quote spaces",
            "\"C:\\Program Files\\PPoEDialer\\PPoEDialer.exe\"".equals(
                StartupService.quoteWinArg("C:\\Program Files\\PPoEDialer\\PPoEDialer.exe")));

        String work = "C:\\Users\\Me\\App With Space\\PPoEDialer";
        String exe = work + "\\PPoEDialer.exe";
        String exeCmd = StartupService.buildExeRunCommand(exe);
        assertTrue("exe cmd quoted", exeCmd.startsWith("\"" + exe + "\""));
        assertTrue("exe cmd flag", exeCmd.endsWith(" " + StartupService.AUTOSTART_FLAG));
        assertTrue("exe no wscript", !exeCmd.toLowerCase().contains("wscript"));

        String jarCmd = StartupService.buildJarRunCommand(
            "C:\\jdk\\bin\\javaw.exe", "D:\\app\\PPoEDialer.jar");
        assertTrue("jar javaw", jarCmd.contains("javaw.exe"));
        assertTrue("jar -jar", jarCmd.contains(" -jar "));
        assertTrue("jar flag", jarCmd.endsWith(" " + StartupService.AUTOSTART_FLAG));

        assertTrue("plausible legacy vbs",
            StartupService.isPlausibleStartupCommand("wscript.exe //B \"C:\\a\\pppoe_startup.vbs\""));
        assertTrue("plausible exe",
            StartupService.isPlausibleStartupCommand("\"C:\\path\\PPoEDialer.exe\" --autostart"));
        assertTrue("direct exe",
            StartupService.isDirectLaunchCommand("\"C:\\path\\PPoEDialer.exe\" --autostart"));
        assertTrue("legacy vbs not direct",
            !StartupService.isDirectLaunchCommand("wscript.exe //B \"C:\\a\\pppoe_startup.vbs\""));
        assertTrue("reject empty", !StartupService.isPlausibleStartupCommand(""));
        assertTrue("reject null", !StartupService.isPlausibleStartupCommand(null));

        String regOut = "\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\n"
            + "    PPoEDialer    REG_SZ    \"C:\\Users\\x\\PPoEDialer.exe\" --autostart\n";
        String parsed = StartupService.parseRegQueryValue(regOut, "PPoEDialer");
        assertTrue("parse reg value",
            parsed != null && parsed.contains("PPoEDialer.exe") && parsed.contains("--autostart"));
        assertTrue("parse missing",
            StartupService.parseRegQueryValue(regOut, "NoSuch") == null);

        assertTrue("java launcher", StartupService.isJavaLauncherExe("C:\\Program Files\\Java\\bin\\java.exe"));
        assertTrue("javaw launcher", StartupService.isJavaLauncherExe("C:\\j\\javaw.exe"));
        assertTrue("packaged not java",
            !StartupService.isJavaLauncherExe("C:\\Users\\x\\Desktop\\PPoEDialer\\PPoEDialer.exe"));
        assertTrue("args autostart",
            StartupService.argsContainAutostart(new String[]{"--autostart"}));
    }

    private static void testScheduleAndReconnectHelpers() {
        assertTrue("schedule dial fire",
            ScheduleService.shouldFireDial(true, false, false, true, 100L, -1L));
        assertTrue("schedule dial skip online",
            !ScheduleService.shouldFireDial(true, true, false, true, 100L, -1L));
        assertTrue("schedule dial skip busy",
            !ScheduleService.shouldFireDial(true, false, true, true, 100L, -1L));
        assertTrue("schedule dial no double",
            !ScheduleService.shouldFireDial(true, false, false, true, 100L, 100L));
        assertTrue("schedule disconnect fire",
            ScheduleService.shouldFireDisconnect(true, true, false, true, 50L, -1L));
        assertTrue("schedule disconnect skip offline",
            !ScheduleService.shouldFireDisconnect(true, false, false, true, 50L, -1L));

        assertTrue("reconnect clamp min", AutoReconnectService.clampIntervalSeconds(1) == 5);
        assertTrue("reconnect clamp keep", AutoReconnectService.clampIntervalSeconds(12) == 12);
        assertTrue("reconnect attempt offline idle",
            AutoReconnectService.shouldAttemptReconnectDial(false, false));
        assertTrue("reconnect skip when busy",
            !AutoReconnectService.shouldAttemptReconnectDial(false, true));
        assertTrue("reconnect skip when online",
            !AutoReconnectService.shouldAttemptReconnectDial(true, false));
    }

    private static void testConnectivityConfirm() {
        final int[] calls = {0};
        assertTrue("confirm eventually ok",
            ConnectivityConfirm.confirm(
                host -> ++calls[0] >= 2,
                "x",
                3,
                0L,
                ms -> {
                }));
        assertTrue("confirm attempts partial", calls[0] == 2);

        calls[0] = 0;
        assertTrue("confirm all fail",
            !ConnectivityConfirm.confirm(
                host -> {
                    calls[0]++;
                    return false;
                },
                "x",
                3,
                0L,
                ms -> {
                }));
        assertTrue("confirm attempts=3", calls[0] == 3);

        assertTrue("history success",
            ConnectivityConfirm.HISTORY_STATUS_SUCCESS.equals(
                ConnectivityConfirm.historyStatus(true, true, 0)));
        assertTrue("history ras no net",
            ConnectivityConfirm.HISTORY_STATUS_RAS_NO_INTERNET.equals(
                ConnectivityConfirm.historyStatus(true, false, 0)));
        assertTrue("history fail code",
            "失败:691".equals(ConnectivityConfirm.historyStatus(false, false, 691)));
    }

    private static void testHardeningHelpers() throws Exception {
        assertTrue("mode auto",
            ConnectivityConfirm.MODE_AUTO.equals(ConnectivityConfirm.normalizeMode("AUTO")));
        final int[] icmp = {0};
        final int[] http = {0};
        boolean ok = ConnectivityConfirm.confirm(
            ConnectivityConfirm.Config.from(ConnectivityConfirm.MODE_AUTO, "h", "http://x", 1, 0),
            host -> { icmp[0]++; return false; },
            url -> { http[0]++; return true; },
            ms -> {});
        assertTrue("auto http fallback", ok);
        assertTrue("icmp tried", icmp[0] == 1);
        assertTrue("http tried", http[0] == 1);

        String content = "[keep]\nA=1\n\n[pppoe_native_java]\nB=2\n";
        String stripped = RasPhonebook.removeSection(content, "pppoe_native_java");
        assertTrue("pbk remove", stripped.contains("[keep]") && !stripped.contains("pppoe_native_java"));
        assertTrue("pbk contains", RasPhonebook.contentContainsSection("[x]\n", "x"));
        RasPhonebook.DeviceHint hint = RasPhonebook.findPppoeDeviceHint(
            "PreferredPort=PPPoE1-0\nPreferredDevice=WAN Miniport (PPPOE)\n");
        assertTrue("pbk hint", hint != null && "PPPoE1-0".equals(hint.port));

        AppSettings s = new AppSettings();
        s.probeMode = ConnectivityConfirm.MODE_HTTP;
        s.probeHost = "8.8.8.8";
        AppSettings s2 = AppSettings.fromMap(s.toMap());
        assertTrue("settings probe mode", ConnectivityConfirm.MODE_HTTP.equals(s2.probeMode));
        assertTrue("settings probe host", "8.8.8.8".equals(s2.probeHost));

        SessionTraffic tr = new SessionTraffic();
        tr.applySample(10, 5);
        tr.markSessionStart();
        tr.applySample(3, 1);
        assertTrue("session bytes", tr.sessionTrafficBytes() == 4);

        AppSettings pol = new AppSettings();
        pol.disconnectOnNoInternet = true;
        AppSettings pol2 = AppSettings.fromMap(pol.toMap());
        assertTrue("disconnect policy", pol2.disconnectOnNoInternet);

        AccountInfo acc = new AccountInfo("n", "u", "pw", "");
        assertTrue("pwd get", "pw".equals(acc.getPassword()));
        acc.clearPassword();
        assertTrue("pwd cleared", acc.isPasswordEmpty());

        char[] blank = new char[]{' ', '\t', '\0'};
        assertTrue("pwd blank scan", PasswordChars.isBlank(blank));
        char[] secret = "secret".toCharArray();
        AccountInfo b = new AccountInfo("n", "u", "", "");
        b.setPasswordChars(secret);
        assertTrue("pwd equals chars", b.passwordEquals(secret));
        PasswordChars.clear(secret);
        assertTrue("pwd still stored", b.passwordEquals("secret".toCharArray()));

        char[] encIn = "char-path-测试".toCharArray();
        String enc2 = CryptoUtil.encrypt(encIn);
        PasswordChars.clear(encIn);
        char[] dec = CryptoUtil.decryptToChars(enc2);
        assertTrue("char encrypt roundtrip", "char-path-测试".equals(new String(dec)));
        PasswordChars.clear(dec);

        RuntimeSettings rs = new RuntimeSettings();
        rs.setProbe(ConnectivityConfirm.MODE_HTTP, "1.1.1.1", "http://x/204", 2, 100);
        ConnectivityConfirm.Config cfg = rs.toProbeConfig();
        assertTrue("runtime probe mode", ConnectivityConfirm.MODE_HTTP.equals(
            ConnectivityConfirm.normalizeMode(cfg.mode)));
        assertTrue("runtime probe host", "1.1.1.1".equals(cfg.host));

        util.ProbeOutcome po = new util.ProbeOutcome(true, 42, "http", "1.1.1.1", "http://x", 2, "manual-test", 1L);
        rs.recordProbeOutcome(po);
        assertTrue("last probe in report", rs.probeReportLine().contains("42ms"));
        assertTrue("probe outcome short", po.shortLine().contains("连通"));

        service.BackgroundExecutor be = new service.BackgroundExecutor();
        java.util.concurrent.atomic.AtomicBoolean longRan = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        be.submitLong(() -> {
            longRan.set(true);
            latch.countDown();
        });
        assertTrue("submitLong await", latch.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue("submitLong ran", longRan.get());
        be.shutdown();

        assertTrue("pbk format null", RasPhonebook.formatStatus(null).contains("无"));
    }

    private static void testAccountCsvLayout() {
        assertTrue("header safe3",
            storage.AccountStore.CsvLayout.SAFE_3 == AccountStore.detectCsvHeaderLayout("昵称,账号,备注"));
        assertTrue("header pass4",
            AccountStore.CsvLayout.WITH_PASSWORD_4 == AccountStore.detectCsvHeaderLayout("昵称,账号,密码,备注"));
        model.AccountInfo a = AccountStore.accountFromCsvParts(
            new String[]{"n", "u", "x"}, AccountStore.CsvLayout.UNKNOWN);
        assertTrue("3col remark not pass", "".equals(a.getPassword()) && "x".equals(a.remark));
        model.AccountInfo b = AccountStore.accountFromCsvParts(
            new String[]{"n", "u", "p", "r"}, AccountStore.CsvLayout.WITH_PASSWORD_4);
        assertTrue("4col pass", "p".equals(b.getPassword()) && "r".equals(b.remark));
    }
}
