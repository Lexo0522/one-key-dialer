package service;

import model.AppSettings;
import util.ConnectivityConfirm;
import util.ProbeOutcome;

/**
 * Off-EDT readable snapshots of schedule + probe settings + last probe outcome.
 * Update only on the EDT when UI/settings change; background services read volatiles.
 */
public final class RuntimeSettings {
    private volatile boolean scheduledDialEnabled;
    private volatile boolean scheduledDisconnectEnabled;
    private volatile int scheduledDialHour = 8;
    private volatile int scheduledDialMinute;
    private volatile int scheduledDisconnectHour = 23;
    private volatile int scheduledDisconnectMinute;
    private volatile String probeMode = ConnectivityConfirm.MODE_AUTO;
    private volatile String probeHost = ConnectivityConfirm.DEFAULT_HOST;
    private volatile String probeHttpUrl = ConnectivityConfirm.DEFAULT_HTTP_URL;
    private volatile int probeAttempts = ConnectivityConfirm.DEFAULT_ATTEMPTS;
    private volatile int probeDelayMs = (int) ConnectivityConfirm.DEFAULT_DELAY_MS;
    private volatile boolean disconnectOnNoInternet;
    private volatile ProbeOutcome lastProbeOutcome;

    public boolean isScheduledDialEnabled() {
        return scheduledDialEnabled;
    }

    public boolean isScheduledDisconnectEnabled() {
        return scheduledDisconnectEnabled;
    }

    public int getScheduledDialHour() {
        return scheduledDialHour;
    }

    public int getScheduledDialMinute() {
        return scheduledDialMinute;
    }

    public int getScheduledDisconnectHour() {
        return scheduledDisconnectHour;
    }

    public int getScheduledDisconnectMinute() {
        return scheduledDisconnectMinute;
    }

    public String getProbeMode() {
        return probeMode;
    }

    public String getProbeHost() {
        return probeHost;
    }

    public String getProbeHttpUrl() {
        return probeHttpUrl;
    }

    public int getProbeAttempts() {
        return probeAttempts;
    }

    public int getProbeDelayMs() {
        return probeDelayMs;
    }

    public boolean isDisconnectOnNoInternet() {
        return disconnectOnNoInternet;
    }

    public void setDisconnectOnNoInternet(boolean enabled) {
        disconnectOnNoInternet = enabled;
    }

    public void setSchedule(boolean dialEnabled, int dialHour, int dialMinute,
                            boolean disconnectEnabled, int disconnectHour, int disconnectMinute) {
        scheduledDialEnabled = dialEnabled;
        scheduledDialHour = dialHour;
        scheduledDialMinute = dialMinute;
        scheduledDisconnectEnabled = disconnectEnabled;
        scheduledDisconnectHour = disconnectHour;
        scheduledDisconnectMinute = disconnectMinute;
    }

    public void setProbe(String mode, String host, String httpUrl, int attempts, int delayMs) {
        probeMode = ConnectivityConfirm.normalizeMode(mode);
        probeHost = host != null && !host.trim().isEmpty() ? host.trim() : ConnectivityConfirm.DEFAULT_HOST;
        probeHttpUrl = httpUrl != null && !httpUrl.trim().isEmpty()
            ? httpUrl.trim() : ConnectivityConfirm.DEFAULT_HTTP_URL;
        probeAttempts = Math.max(1, attempts);
        probeDelayMs = Math.max(0, delayMs);
    }

    public ConnectivityConfirm.Config toProbeConfig() {
        return ConnectivityConfirm.Config.from(probeMode, probeHost, probeHttpUrl, probeAttempts, probeDelayMs);
    }

    public String probeSummaryLine() {
        return ConnectivityConfirm.normalizeMode(probeMode) + " / " + probeHost;
    }

    public void recordProbeOutcome(ProbeOutcome outcome) {
        if (outcome != null) {
            lastProbeOutcome = outcome;
        }
    }

    public ProbeOutcome lastProbeOutcome() {
        return lastProbeOutcome;
    }

    public String probeReportLine() {
        String base = "mode=" + probeMode + " host=" + probeHost + " http=" + probeHttpUrl
            + " attempts=" + probeAttempts + " delayMs=" + probeDelayMs
            + " disconnectOnNoInternet=" + disconnectOnNoInternet;
        ProbeOutcome last = lastProbeOutcome;
        if (last != null) {
            base += " | last=[" + last.detailLine() + "]";
        }
        return base;
    }

    public void applyFrom(AppSettings s) {
        if (s == null) return;
        setSchedule(
            s.scheduledDial, s.scheduledDialHour, s.scheduledDialMinute,
            s.scheduledDisconnect, s.scheduledDisconnectHour, s.scheduledDisconnectMinute
        );
        setProbe(s.probeMode, s.probeHost, s.probeHttpUrl, s.probeAttempts, s.probeDelayMs);
        disconnectOnNoInternet = s.disconnectOnNoInternet;
    }

    public void writeScheduleTo(AppSettings s) {
        if (s == null) return;
        s.scheduledDial = scheduledDialEnabled;
        s.scheduledDialHour = scheduledDialHour;
        s.scheduledDialMinute = scheduledDialMinute;
        s.scheduledDisconnect = scheduledDisconnectEnabled;
        s.scheduledDisconnectHour = scheduledDisconnectHour;
        s.scheduledDisconnectMinute = scheduledDisconnectMinute;
    }

    public void writeProbeTo(AppSettings s) {
        if (s == null) return;
        s.probeMode = probeMode;
        s.probeHost = probeHost;
        s.probeHttpUrl = probeHttpUrl;
        s.probeAttempts = probeAttempts;
        s.probeDelayMs = probeDelayMs;
        s.disconnectOnNoInternet = disconnectOnNoInternet;
    }
}
