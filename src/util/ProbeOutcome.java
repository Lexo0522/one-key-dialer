package util;

/**
 * Result of one connectivity probe run (manual test or post-dial).
 */
public final class ProbeOutcome {
    public final boolean ok;
    public final long durationMs;
    public final String mode;
    public final String host;
    public final String httpUrl;
    public final int attempts;
    public final String source;
    public final long atEpochMs;

    public ProbeOutcome(boolean ok, long durationMs, String mode, String host, String httpUrl,
                        int attempts, String source, long atEpochMs) {
        this.ok = ok;
        this.durationMs = Math.max(0L, durationMs);
        this.mode = mode != null ? mode : ConnectivityConfirm.MODE_AUTO;
        this.host = host != null ? host : "";
        this.httpUrl = httpUrl != null ? httpUrl : "";
        this.attempts = Math.max(1, attempts);
        this.source = source != null ? source : "probe";
        this.atEpochMs = atEpochMs > 0 ? atEpochMs : System.currentTimeMillis();
    }

    public String shortLine() {
        return (ok ? "连通" : "不通")
            + " mode=" + ConnectivityConfirm.normalizeMode(mode)
            + " " + durationMs + "ms"
            + " src=" + source;
    }

    public String detailLine() {
        return shortLine()
            + " host=" + host
            + " http=" + httpUrl
            + " attempts=" + attempts
            + " at=" + atEpochMs;
    }
}
