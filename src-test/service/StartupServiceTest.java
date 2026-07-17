package service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StartupServiceTest {

    @Test
    void quoteWinArgWrapsPaths() {
        assertEquals("\"C:\\app\\PPoEDialer.exe\"",
            StartupService.quoteWinArg("C:\\app\\PPoEDialer.exe"));
        assertEquals("\"C:\\Program Files\\PPoEDialer\\PPoEDialer.exe\"",
            StartupService.quoteWinArg("C:\\Program Files\\PPoEDialer\\PPoEDialer.exe"));
        assertEquals("\"\"", StartupService.quoteWinArg(null));
    }

    @Test
    void buildExeRunCommandIncludesFlag() {
        String cmd = StartupService.buildExeRunCommand(
            "C:\\Users\\Me\\App With Space\\PPoEDialer.exe");
        assertTrue(cmd.startsWith("\"C:\\Users\\Me\\App With Space\\PPoEDialer.exe\""));
        assertTrue(cmd.endsWith(" " + StartupService.AUTOSTART_FLAG));
        assertFalse(cmd.toLowerCase().contains("wscript"));
        assertFalse(cmd.toLowerCase().contains(".vbs"));
    }

    @Test
    void buildJarRunCommand() {
        String cmd = StartupService.buildJarRunCommand(
            "C:\\Program Files\\Java\\bin\\javaw.exe",
            "D:\\app\\PPoEDialer.jar");
        assertTrue(cmd.contains("javaw.exe"));
        assertTrue(cmd.contains(" -jar "));
        assertTrue(cmd.contains("PPoEDialer.jar"));
        assertTrue(cmd.endsWith(" " + StartupService.AUTOSTART_FLAG));
        assertTrue(cmd.startsWith("\""));
    }

    @Test
    void parseRegQueryValueReadsRegSzData() {
        String out = "\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\n"
            + "    PPoEDialer    REG_SZ    \"C:\\x\\PPoEDialer.exe\" --autostart\n";
        assertEquals(
            "\"C:\\x\\PPoEDialer.exe\" --autostart",
            StartupService.parseRegQueryValue(out, "PPoEDialer"));
        assertNull(StartupService.parseRegQueryValue(out, "Other"));
    }

    @Test
    void isPlausibleStartupCommand() {
        assertTrue(StartupService.isPlausibleStartupCommand(
            "wscript.exe //B \"C:\\a\\pppoe_startup.vbs\""));
        assertTrue(StartupService.isPlausibleStartupCommand(
            "\"C:\\path\\PPoEDialer.exe\" --autostart"));
        assertTrue(StartupService.isPlausibleStartupCommand(
            "\"C:\\j\\javaw.exe\" -jar \"C:\\a\\app.jar\" --autostart"));
        assertFalse(StartupService.isPlausibleStartupCommand(""));
        assertFalse(StartupService.isPlausibleStartupCommand(null));
    }

    @Test
    void legacyVsDirectCommands() {
        String vbs = "wscript.exe //B \"C:\\a\\pppoe_startup.vbs\"";
        String exe = "\"C:\\path\\PPoEDialer.exe\" --autostart";
        assertTrue(StartupService.isLegacyVbsCommand(vbs));
        assertFalse(StartupService.isDirectLaunchCommand(vbs));
        assertFalse(StartupService.isLegacyVbsCommand(exe));
        assertTrue(StartupService.isDirectLaunchCommand(exe));
    }

    @Test
    void isJavaLauncherExe() {
        assertTrue(StartupService.isJavaLauncherExe("C:\\jdk\\bin\\java.exe"));
        assertTrue(StartupService.isJavaLauncherExe("javaw.exe"));
        assertFalse(StartupService.isJavaLauncherExe("C:\\app\\PPoEDialer.exe"));
    }

    @Test
    void argsContainAutostart() {
        assertTrue(StartupService.argsContainAutostart(new String[]{"--autostart"}));
        assertTrue(StartupService.argsContainAutostart(new String[]{"x", "--autostart", "y"}));
        assertFalse(StartupService.argsContainAutostart(new String[]{}));
        assertFalse(StartupService.argsContainAutostart(null));
        assertFalse(StartupService.argsContainAutostart(new String[]{"--other"}));
    }
}
