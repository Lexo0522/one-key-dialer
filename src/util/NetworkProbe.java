package util;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight connectivity probe (ICMP / isReachable) used by auto-reconnect.
 */
public final class NetworkProbe {
    private static final String DEFAULT_HOST = "223.5.5.5";

    private NetworkProbe() {
    }

    public static boolean isOnline() {
        return isOnline(DEFAULT_HOST);
    }

    public static boolean isOnline(String host) {
        boolean lightweightReachable = false;
        try {
            InetAddress addr = InetAddress.getByName(host);
            lightweightReachable = addr.isReachable(1000);
            if (lightweightReachable) return true;
        } catch (Exception ignored) {
        }

        Process p = null;
        try {
            p = new ProcessBuilder("ping", "-n", "1", "-w", "1000", host).start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return lightweightReachable;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
