package util;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Samples host network traffic counters via {@code netstat -e}.
 */
public final class TrafficSampler {
    private static final long[] EMPTY = {0, 0};

    // netstat -e rows are "label received sent" in every locale; the Bytes/字节 row is
    // always the first data row, so taking the first counter row is locale-independent
    // and immune to childCharset label decoding.
    private static final Pattern COUNTER_ROW = Pattern.compile("^(.+?)\\s+(\\d+)\\s+(\\d+)$");

    private final Consumer<String> onWarn;
    private boolean warned = false;

    public TrafficSampler(Consumer<String> onWarn) {
        this.onWarn = onWarn;
    }

    /** @return [receivedBytes, sentBytes], EMPTY when sampling fails */
    public long[] sample() {
        try {
            ProcessIO.Result result = ProcessIO.run(
                java.util.Arrays.asList("cmd", "/c", "netstat -e"),
                5, TimeUnit.SECONDS, ProcessIO.childCharset(), null);
            long[] counters = parse(result.output);
            if (counters != null) {
                warned = false;
                return counters;
            }
            warnOnce("netstat -e 输出解析失败，流量速度不可用");
        } catch (Exception e) {
            warnOnce("读取网卡流量失败: " + e.getClass().getSimpleName());
        }
        return EMPTY;
    }

    /** First "label + two counters" row of {@code netstat -e} output; null when absent. */
    static long[] parse(String output) {
        for (String line : output.split("\\R")) {
            Matcher m = COUNTER_ROW.matcher(line.trim());
            if (m.matches()) {
                try {
                    return new long[]{Long.parseLong(m.group(2)), Long.parseLong(m.group(3))};
                } catch (NumberFormatException ignored) {
                    // \d+ overflow past long range is not a realistic counter value
                }
            }
        }
        return null;
    }

    private void warnOnce(String message) {
        if (onWarn == null || warned) return;
        warned = true;
        onWarn.accept(message);
    }
}
