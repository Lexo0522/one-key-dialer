package util;

/**
 * ICMP reachability probe (InetAddress, then {@code ping -n 1}) used by
 * {@link ConnectivityConfirm} auto-mode probing.
 */
public final class NetworkProbe {
    private NetworkProbe() {
    }

    public static boolean icmpReachable(String host) {
        boolean lightweightReachable = false;
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(host);
            lightweightReachable = addr.isReachable(1000);
            if (lightweightReachable) return true;
        } catch (Exception ignored) {
        }

        try {
            ProcessIO.Result result = ProcessIO.run(
                java.util.Arrays.asList("ping", "-n", "1", "-w", "1000", host),
                5, java.util.concurrent.TimeUnit.SECONDS, ProcessIO.childCharset(), null);
            return !result.timedOut && result.exitCode == 0;
        } catch (Exception e) {
            return lightweightReachable;
        }
    }
}
