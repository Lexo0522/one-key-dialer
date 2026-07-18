package model;

import util.ConnectivityConfirm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed app settings (INI key/value). Unknown keys ignored on load.
 */
public final class AppSettings {
    public int intervalSeconds = 30;
    public boolean autoReconnect = false;
    public boolean autoStart = false;
    public boolean startMinimized = false;
    public int accountIndex = 0;
    public boolean scheduledDial = false;
    public int scheduledDialHour = 8;
    public int scheduledDialMinute = 0;
    public boolean scheduledDisconnect = false;
    public int scheduledDisconnectHour = 23;
    public int scheduledDisconnectMinute = 0;

    /** icmp | http | auto */
    public String probeMode = ConnectivityConfirm.MODE_AUTO;
    public String probeHost = ConnectivityConfirm.DEFAULT_HOST;
    public String probeHttpUrl = ConnectivityConfirm.DEFAULT_HTTP_URL;
    public int probeAttempts = ConnectivityConfirm.DEFAULT_ATTEMPTS;
    public int probeDelayMs = (int) ConnectivityConfirm.DEFAULT_DELAY_MS;
    /**
     * When true, after RAS success but probe failure, disconnect PPP
     * (history still records RAS成功无外网 / 已断开). Default false keeps intranet.
     */
    public boolean disconnectOnNoInternet = false;
    /**
     * When true, schedule a quiet GitHub Releases check a few seconds after start.
     * Manual tray 「检查更新」 is unaffected. Default true.
     */
    public boolean updateCheckEnabled = true;

    public static AppSettings fromMap(Map<String, String> map) {
        AppSettings s = new AppSettings();
        if (map == null || map.isEmpty()) return s;
        s.intervalSeconds = parseInt(map.get("interval"), s.intervalSeconds);
        s.autoReconnect = parseBool(map.get("auto.reconnect"), s.autoReconnect);
        s.autoStart = parseBool(map.get("auto.start"), s.autoStart);
        s.startMinimized = parseBool(map.get("start.minimized"), s.startMinimized);
        s.accountIndex = parseInt(map.get("account.index"), s.accountIndex);
        s.scheduledDial = parseBool(map.get("scheduled.dial"), s.scheduledDial);
        s.scheduledDialHour = parseInt(map.get("scheduled.dial.hour"), s.scheduledDialHour);
        s.scheduledDialMinute = parseInt(map.get("scheduled.dial.minute"), s.scheduledDialMinute);
        s.scheduledDisconnect = parseBool(map.get("scheduled.disconnect"), s.scheduledDisconnect);
        s.scheduledDisconnectHour = parseInt(map.get("scheduled.disconnect.hour"), s.scheduledDisconnectHour);
        s.scheduledDisconnectMinute = parseInt(map.get("scheduled.disconnect.minute"), s.scheduledDisconnectMinute);
        s.probeMode = firstNonEmpty(map.get("probe.mode"), s.probeMode);
        s.probeHost = firstNonEmpty(map.get("probe.host"), s.probeHost);
        s.probeHttpUrl = firstNonEmpty(map.get("probe.http.url"), s.probeHttpUrl);
        s.probeAttempts = parseInt(map.get("probe.attempts"), s.probeAttempts);
        s.probeDelayMs = parseInt(map.get("probe.delay.ms"), s.probeDelayMs);
        s.disconnectOnNoInternet = parseBool(
            map.get("probe.disconnect.on.no.internet"), s.disconnectOnNoInternet);
        s.updateCheckEnabled = parseBool(map.get("update.check"), s.updateCheckEnabled);
        return s;
    }

    public Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("interval", String.valueOf(intervalSeconds));
        m.put("auto.reconnect", String.valueOf(autoReconnect));
        m.put("auto.start", String.valueOf(autoStart));
        m.put("start.minimized", String.valueOf(startMinimized));
        m.put("account.index", String.valueOf(accountIndex));
        m.put("scheduled.dial", String.valueOf(scheduledDial));
        m.put("scheduled.dial.hour", String.valueOf(scheduledDialHour));
        m.put("scheduled.dial.minute", String.valueOf(scheduledDialMinute));
        m.put("scheduled.disconnect", String.valueOf(scheduledDisconnect));
        m.put("scheduled.disconnect.hour", String.valueOf(scheduledDisconnectHour));
        m.put("scheduled.disconnect.minute", String.valueOf(scheduledDisconnectMinute));
        m.put("probe.mode", probeMode != null ? probeMode : ConnectivityConfirm.MODE_AUTO);
        m.put("probe.host", probeHost != null ? probeHost : ConnectivityConfirm.DEFAULT_HOST);
        m.put("probe.http.url", probeHttpUrl != null ? probeHttpUrl : ConnectivityConfirm.DEFAULT_HTTP_URL);
        m.put("probe.attempts", String.valueOf(probeAttempts));
        m.put("probe.delay.ms", String.valueOf(probeDelayMs));
        m.put("probe.disconnect.on.no.internet", String.valueOf(disconnectOnNoInternet));
        m.put("update.check", String.valueOf(updateCheckEnabled));
        return m;
    }

    public ConnectivityConfirm.Config toProbeConfig() {
        return ConnectivityConfirm.Config.from(
            probeMode, probeHost, probeHttpUrl, probeAttempts, probeDelayMs);
    }

    private static int parseInt(String raw, int def) {
        if (raw == null || raw.isEmpty()) return def;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean parseBool(String raw, boolean def) {
        if (raw == null || raw.isEmpty()) return def;
        return "true".equalsIgnoreCase(raw.trim());
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        return b;
    }
}
