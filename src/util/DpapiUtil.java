package util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Optional Windows DPAPI helpers via PowerShell + .NET ProtectedData.
 * No JNA: works on stock Windows with PowerShell. Failures are non-fatal for callers.
 */
public final class DpapiUtil {
    private static final long TIMEOUT_SEC = 15;

    private DpapiUtil() {
    }

    public static boolean isLikelyWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase().contains("win");
    }

    /**
     * Protect raw bytes for the current Windows user. Returns null if unavailable.
     */
    public static byte[] protect(byte[] plain) {
        if (plain == null || plain.length == 0 || !isLikelyWindows()) return null;
        String b64 = Base64.getEncoder().encodeToString(plain);
        String script =
            "$ErrorActionPreference='Stop';"
                + "Add-Type -AssemblyName System.Security;"
                + "$plain=[Convert]::FromBase64String('" + b64 + "');"
                + "$prot=[System.Security.Cryptography.ProtectedData]::Protect("
                + "$plain,$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser);"
                + "[Convert]::ToBase64String($prot)";
        String out = runPowerShell(script);
        if (out == null || out.isEmpty()) return null;
        try {
            return Base64.getDecoder().decode(out.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Unprotect DPAPI blob for the current Windows user. Returns null if unavailable.
     */
    public static byte[] unprotect(byte[] protectedBytes) {
        if (protectedBytes == null || protectedBytes.length == 0 || !isLikelyWindows()) return null;
        String b64 = Base64.getEncoder().encodeToString(protectedBytes);
        String script =
            "$ErrorActionPreference='Stop';"
                + "Add-Type -AssemblyName System.Security;"
                + "$prot=[Convert]::FromBase64String('" + b64 + "');"
                + "$plain=[System.Security.Cryptography.ProtectedData]::Unprotect("
                + "$prot,$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser);"
                + "[Convert]::ToBase64String($plain)";
        String out = runPowerShell(script);
        if (out == null || out.isEmpty()) return null;
        try {
            return Base64.getDecoder().decode(out.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String runPowerShell(String script) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-Command",
                script
            );
            pb.redirectErrorStream(true);
            p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
            }
            boolean finished = p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
