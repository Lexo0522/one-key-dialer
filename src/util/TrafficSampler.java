package util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Samples host network traffic via MXBean or {@code netstat -e} fallback.
 */
public final class TrafficSampler {
    private static final long[] EMPTY = {0, 0};

    private Object osBean;
    private java.lang.reflect.Method mGetReceived;
    private java.lang.reflect.Method mGetSent;
    private boolean mxBeanTried = false;
    private final Consumer<String> onWarn;

    public TrafficSampler() {
        this(null);
    }

    public TrafficSampler(Consumer<String> onWarn) {
        this.onWarn = onWarn;
    }

    /** @return [receivedBytes, sentBytes] */
    public long[] sample() {
        if (!mxBeanTried) {
            mxBeanTried = true;
            try {
                Class<?> clz = Class.forName("com.sun.management.OperatingSystemMXBean");
                osBean = clz.cast(ManagementFactory.getOperatingSystemMXBean());
                mGetReceived = clz.getMethod("getNetworkInterfaceBytesReceived");
                mGetSent = clz.getMethod("getNetworkInterfaceBytesSent");
            } catch (Exception e) {
                osBean = null;
            }
        }
        if (osBean != null && mGetReceived != null && mGetSent != null) {
            try {
                long received = (long) mGetReceived.invoke(osBean);
                long sent = (long) mGetSent.invoke(osBean);
                if (received >= 0 && sent >= 0) return new long[]{received, sent};
            } catch (Exception e) {
                if (onWarn != null) {
                    onWarn.accept("读取网卡流量失败: " + e.getClass().getSimpleName());
                }
                osBean = null;
                mGetReceived = null;
                mGetSent = null;
            }
        }

        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "netstat -e");
            pb.redirectErrorStream(true);
            p = pb.start();
            try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), ProcessIO.childCharset()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.matches("^\\d+\\s+\\d+.*")) {
                        String[] parts = trimmed.split("\\s+");
                        if (parts.length >= 2) {
                            long received = Long.parseLong(parts[0]);
                            long sent = Long.parseLong(parts[1]);
                            return new long[]{received, sent};
                        }
                    }
                }
            }
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) return EMPTY;
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.destroy();
        }
        return EMPTY;
    }
}
