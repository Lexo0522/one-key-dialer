package model;

import util.ConnectivityConfirm;

/**
 * Immutable settings state — the single carrier for load, save, and runtime reads.
 * UI panels fill/apply via {@link Builder}; services read a volatile snapshot.
 */
public final class SettingsSnapshot {
    public static final int MIN_INTERVAL_SECONDS = 5;

    public final int intervalSeconds;
    public final boolean autoReconnect;
    public final boolean autoStart;
    public final boolean startMinimized;
    public final int accountIndex;
    public final boolean scheduledDial;
    public final int scheduledDialHour;
    public final int scheduledDialMinute;
    public final boolean scheduledDisconnect;
    public final int scheduledDisconnectHour;
    public final int scheduledDisconnectMinute;
    /** icmp | http | auto */
    public final String probeMode;
    public final String probeHost;
    public final String probeHttpUrl;
    public final int probeAttempts;
    public final int probeDelayMs;
    /** After RAS success but probe failure, disconnect the PPP session. */
    public final boolean disconnectOnNoInternet;
    /** Quiet startup GitHub Releases check; manual tray check unaffected. */
    public final boolean updateCheckEnabled;

    private SettingsSnapshot(Builder b) {
        this.intervalSeconds = b.intervalSeconds;
        this.autoReconnect = b.autoReconnect;
        this.autoStart = b.autoStart;
        this.startMinimized = b.startMinimized;
        this.accountIndex = b.accountIndex;
        this.scheduledDial = b.scheduledDial;
        this.scheduledDialHour = b.scheduledDialHour;
        this.scheduledDialMinute = b.scheduledDialMinute;
        this.scheduledDisconnect = b.scheduledDisconnect;
        this.scheduledDisconnectHour = b.scheduledDisconnectHour;
        this.scheduledDisconnectMinute = b.scheduledDisconnectMinute;
        this.probeMode = b.probeMode;
        this.probeHost = b.probeHost;
        this.probeHttpUrl = b.probeHttpUrl;
        this.probeAttempts = b.probeAttempts;
        this.probeDelayMs = b.probeDelayMs;
        this.disconnectOnNoInternet = b.disconnectOnNoInternet;
        this.updateCheckEnabled = b.updateCheckEnabled;
    }

    public static SettingsSnapshot defaults() {
        return new Builder().build();
    }

    /** Normalized copy: clamped ranges, trimmed probe fields, valid mode. */
    public SettingsSnapshot normalized() {
        return toBuilder().build();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.intervalSeconds = intervalSeconds;
        b.autoReconnect = autoReconnect;
        b.autoStart = autoStart;
        b.startMinimized = startMinimized;
        b.accountIndex = accountIndex;
        b.scheduledDial = scheduledDial;
        b.scheduledDialHour = scheduledDialHour;
        b.scheduledDialMinute = scheduledDialMinute;
        b.scheduledDisconnect = scheduledDisconnect;
        b.scheduledDisconnectHour = scheduledDisconnectHour;
        b.scheduledDisconnectMinute = scheduledDisconnectMinute;
        b.probeMode = probeMode;
        b.probeHost = probeHost;
        b.probeHttpUrl = probeHttpUrl;
        b.probeAttempts = probeAttempts;
        b.probeDelayMs = probeDelayMs;
        b.disconnectOnNoInternet = disconnectOnNoInternet;
        b.updateCheckEnabled = updateCheckEnabled;
        return b;
    }

    public ConnectivityConfirm.Config toProbeConfig() {
        return ConnectivityConfirm.Config.from(
            probeMode, probeHost, probeHttpUrl, probeAttempts, probeDelayMs);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SettingsSnapshot)) return false;
        SettingsSnapshot s = (SettingsSnapshot) o;
        return intervalSeconds == s.intervalSeconds
            && autoReconnect == s.autoReconnect
            && autoStart == s.autoStart
            && startMinimized == s.startMinimized
            && accountIndex == s.accountIndex
            && scheduledDial == s.scheduledDial
            && scheduledDialHour == s.scheduledDialHour
            && scheduledDialMinute == s.scheduledDialMinute
            && scheduledDisconnect == s.scheduledDisconnect
            && scheduledDisconnectHour == s.scheduledDisconnectHour
            && scheduledDisconnectMinute == s.scheduledDisconnectMinute
            && probeAttempts == s.probeAttempts
            && probeDelayMs == s.probeDelayMs
            && disconnectOnNoInternet == s.disconnectOnNoInternet
            && updateCheckEnabled == s.updateCheckEnabled
            && probeMode.equals(s.probeMode)
            && probeHost.equals(s.probeHost)
            && probeHttpUrl.equals(s.probeHttpUrl);
    }

    @Override
    public int hashCode() {
        int result = intervalSeconds;
        result = 31 * result + (autoReconnect ? 1 : 0);
        result = 31 * result + (autoStart ? 1 : 0);
        result = 31 * result + (startMinimized ? 1 : 0);
        result = 31 * result + accountIndex;
        result = 31 * result + (scheduledDial ? 1 : 0);
        result = 31 * result + scheduledDialHour;
        result = 31 * result + scheduledDialMinute;
        result = 31 * result + (scheduledDisconnect ? 1 : 0);
        result = 31 * result + scheduledDisconnectHour;
        result = 31 * result + scheduledDisconnectMinute;
        result = 31 * result + probeMode.hashCode();
        result = 31 * result + probeHost.hashCode();
        result = 31 * result + probeHttpUrl.hashCode();
        result = 31 * result + probeAttempts;
        result = 31 * result + probeDelayMs;
        result = 31 * result + (disconnectOnNoInternet ? 1 : 0);
        result = 31 * result + (updateCheckEnabled ? 1 : 0);
        return result;
    }

    public static final class Builder {
        private int intervalSeconds = 30;
        private boolean autoReconnect = false;
        private boolean autoStart = false;
        private boolean startMinimized = false;
        private int accountIndex = 0;
        private boolean scheduledDial = false;
        private int scheduledDialHour = 8;
        private int scheduledDialMinute = 0;
        private boolean scheduledDisconnect = false;
        private int scheduledDisconnectHour = 23;
        private int scheduledDisconnectMinute = 0;
        private String probeMode = ConnectivityConfirm.MODE_AUTO;
        private String probeHost = ConnectivityConfirm.DEFAULT_HOST;
        private String probeHttpUrl = ConnectivityConfirm.DEFAULT_HTTP_URL;
        private int probeAttempts = ConnectivityConfirm.DEFAULT_ATTEMPTS;
        private int probeDelayMs = (int) ConnectivityConfirm.DEFAULT_DELAY_MS;
        private boolean disconnectOnNoInternet = false;
        private boolean updateCheckEnabled = true;

        public Builder intervalSeconds(int v) {
            this.intervalSeconds = Math.max(MIN_INTERVAL_SECONDS, v);
            return this;
        }

        public Builder autoReconnect(boolean v) {
            this.autoReconnect = v;
            return this;
        }

        public Builder autoStart(boolean v) {
            this.autoStart = v;
            return this;
        }

        public Builder startMinimized(boolean v) {
            this.startMinimized = v;
            return this;
        }

        public Builder accountIndex(int v) {
            this.accountIndex = Math.max(0, v);
            return this;
        }

        public Builder scheduledDial(boolean enabled, int hour, int minute) {
            this.scheduledDial = enabled;
            this.scheduledDialHour = clampHour(hour);
            this.scheduledDialMinute = clampMinute(minute);
            return this;
        }

        public Builder scheduledDisconnect(boolean enabled, int hour, int minute) {
            this.scheduledDisconnect = enabled;
            this.scheduledDisconnectHour = clampHour(hour);
            this.scheduledDisconnectMinute = clampMinute(minute);
            return this;
        }

        public Builder probe(String mode, String host, String httpUrl, int attempts, int delayMs) {
            this.probeMode = ConnectivityConfirm.normalizeMode(mode);
            this.probeHost = host != null && !host.trim().isEmpty()
                ? host.trim() : ConnectivityConfirm.DEFAULT_HOST;
            this.probeHttpUrl = httpUrl != null && !httpUrl.trim().isEmpty()
                ? httpUrl.trim() : ConnectivityConfirm.DEFAULT_HTTP_URL;
            this.probeAttempts = Math.max(1, attempts);
            this.probeDelayMs = Math.max(0, delayMs);
            return this;
        }

        public Builder disconnectOnNoInternet(boolean v) {
            this.disconnectOnNoInternet = v;
            return this;
        }

        public Builder updateCheckEnabled(boolean v) {
            this.updateCheckEnabled = v;
            return this;
        }

        public SettingsSnapshot build() {
            this.intervalSeconds = Math.max(MIN_INTERVAL_SECONDS, this.intervalSeconds);
            this.accountIndex = Math.max(0, this.accountIndex);
            this.scheduledDialHour = clampHour(this.scheduledDialHour);
            this.scheduledDialMinute = clampMinute(this.scheduledDialMinute);
            this.scheduledDisconnectHour = clampHour(this.scheduledDisconnectHour);
            this.scheduledDisconnectMinute = clampMinute(this.scheduledDisconnectMinute);
            this.probeMode = ConnectivityConfirm.normalizeMode(this.probeMode);
            this.probeHost = this.probeHost != null && !this.probeHost.trim().isEmpty()
                ? this.probeHost.trim() : ConnectivityConfirm.DEFAULT_HOST;
            this.probeHttpUrl = this.probeHttpUrl != null && !this.probeHttpUrl.trim().isEmpty()
                ? this.probeHttpUrl.trim() : ConnectivityConfirm.DEFAULT_HTTP_URL;
            this.probeAttempts = Math.max(1, this.probeAttempts);
            this.probeDelayMs = Math.max(0, this.probeDelayMs);
            return new SettingsSnapshot(this);
        }

        private static int clampHour(int v) {
            return Math.min(23, Math.max(0, v));
        }

        private static int clampMinute(int v) {
            return Math.min(59, Math.max(0, v));
        }
    }
}
