package util;

/**
 * Lightweight connectivity probe used by auto-reconnect and legacy callers.
 * Delegates to {@link ConnectivityConfirm} so probe mode / host / HTTP URL stay consistent
 * with post-dial confirmation and the 「网络探测」 settings tab.
 * <p>
 * Prefer {@link ConnectivityConfirm#quickCheck(ConnectivityConfirm.Config)} with
 * {@link service.RuntimeSettings#toProbeConfig()} when a live config is available.
 */
public final class NetworkProbe {
    private NetworkProbe() {
    }

    /**
     * Defaults-only check (auto mode, default host/URL). Prefer settings-driven
     * {@link ConnectivityConfirm#quickCheck(ConnectivityConfirm.Config)}.
     */
    public static boolean isOnline() {
        return ConnectivityConfirm.quickCheckDefault();
    }

    /**
     * ICMP/ping reachability for a bare host (legacy helper).
     * Does not honor HTTP mode — use {@link ConnectivityConfirm} for full probe modes.
     */
    public static boolean isOnline(String host) {
        String h = (host == null || host.isEmpty())
            ? ConnectivityConfirm.DEFAULT_HOST : host;
        ConnectivityConfirm.Config cfg = new ConnectivityConfirm.Config(
            ConnectivityConfirm.MODE_ICMP, h, ConnectivityConfirm.DEFAULT_HTTP_URL,
            1, 0L, ConnectivityConfirm.DEFAULT_HTTP_TIMEOUT_MS);
        return ConnectivityConfirm.confirm(cfg);
    }

    /**
     * ICMP via {@link java.net.InetAddress#isReachable} then {@code ping -n 1}.
     * Kept public for tests / Diag; production paths should use {@link ConnectivityConfirm}.
     */
    public static boolean icmpReachable(String host) {
        boolean lightweightReachable = false;
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(host);
            lightweightReachable = addr.isReachable(1000);
            if (lightweightReachable) return true;
        } catch (Exception ignored) {
        }

        Process p = null;
        try {
            p = new ProcessBuilder("ping", "-n", "1", "-w", "1000", host).start();
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return lightweightReachable;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
