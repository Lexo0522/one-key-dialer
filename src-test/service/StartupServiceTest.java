package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
    void directLaunchCommands() {
        String vbs = "wscript.exe //B \"C:\\a\\ppoe_startup.vbs\"";
        String exe = "\"C:\\path\\PPoEDialer.exe\" --autostart";
        assertFalse(StartupService.isDirectLaunchCommand(vbs));
        assertTrue(StartupService.isDirectLaunchCommand(exe));
        assertFalse(StartupService.isDirectLaunchCommand(""));
        assertFalse(StartupService.isDirectLaunchCommand(null));
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

    @Test
    void enableAndDisablePreserveMsiPathWithSpaces(@TempDir Path tempDir) throws Exception {
        File exe = tempDir.resolve("Program Files").resolve("PPoEDialer.exe").toFile();
        assertTrue(exe.getParentFile().mkdirs());
        assertTrue(exe.createNewFile());

        MemoryRegistry registry = new MemoryRegistry();
        AtomicInteger registered = new AtomicInteger();
        AtomicInteger unregistered = new AtomicInteger();
        StartupService service = new StartupService(
            "PPoEDialer", registered::incrementAndGet, unregistered::incrementAndGet,
            null, registry, appClass -> new StartupService.LaunchTarget(
                StartupService.LaunchTarget.Kind.EXE, exe.getAbsolutePath(), null));

        service.enableAutoStart(StartupService.class);

        String expected = StartupService.buildExeRunCommand(exe.getAbsolutePath());
        assertEquals(expected, registry.read("PPoEDialer"));
        assertTrue(service.isAutoStartEnabled());
        assertTrue(service.isRegistrationHealthy(StartupService.class));
        assertEquals(1, registered.get());
        assertEquals(0, unregistered.get());

        service.disableAutoStart();

        assertNull(registry.read("PPoEDialer"));
        assertFalse(service.isAutoStartEnabled());
        assertEquals(1, unregistered.get());
    }

    @Test
    void enableFailureInvokesUnregistered() {
        AtomicInteger unregistered = new AtomicInteger();
        StartupService service = new StartupService(
            "PPoEDialer", () -> { }, unregistered::incrementAndGet, null,
            new MemoryRegistry() {
                @Override public void write(String valueName, String value) {
                    throw new IllegalStateException("write failed");
                }
            }, appClass -> new StartupService.LaunchTarget(
                StartupService.LaunchTarget.Kind.EXE, "C:\\missing\\PPoEDialer.exe", null));

        service.enableAutoStart(StartupService.class);

        assertEquals(1, unregistered.get());
    }

    private static class MemoryRegistry implements StartupService.RegistryStore {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public String read(String valueName) {
            return values.get(valueName);
        }

        @Override
        public void write(String valueName, String value) {
            values.put(valueName, value);
        }

        @Override
        public void delete(String valueName) {
            values.remove(valueName);
        }
    }
}
