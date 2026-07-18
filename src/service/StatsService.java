package service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aggregate dial history rows into a simple stats report.
 * History columns: [time, operation, account, result, duration, traffic]
 */
public final class StatsService {
    private StatsService() {
    }

    public static final class Summary {
        public final int totalOps;
        public final int dialAttempts;
        public final int dialSuccess;
        public final int dialFail;
        public final int disconnects;
        public final Map<String, Integer> topErrorHints;
        public final String reportText;

        public Summary(int totalOps, int dialAttempts, int dialSuccess, int dialFail,
                       int disconnects, Map<String, Integer> topErrorHints, String reportText) {
            this.totalOps = totalOps;
            this.dialAttempts = dialAttempts;
            this.dialSuccess = dialSuccess;
            this.dialFail = dialFail;
            this.disconnects = disconnects;
            this.topErrorHints = topErrorHints;
            this.reportText = reportText;
        }

        public double successRate() {
            return dialAttempts == 0 ? 0.0 : (100.0 * dialSuccess / dialAttempts);
        }
    }

    public static Summary summarize(List<String[]> records) {
        int total = 0;
        int dialAttempts = 0;
        int dialSuccess = 0;
        int dialFail = 0;
        int disconnects = 0;
        Map<String, Integer> fails = new HashMap<>();

        if (records != null) {
            synchronized (records) {
                for (String[] row : records) {
                    if (row == null || row.length < 4) continue;
                    total++;
                    String op = safe(row[1]);
                    String result = safe(row[3]);
                    boolean isDial = op.contains("拨号");
                    boolean isDisc = op.contains("断开");
                    if (isDial) {
                        dialAttempts++;
                        if (isSuccessResult(result)) {
                            dialSuccess++;
                        } else {
                            dialFail++;
                            String key = result.isEmpty() ? "未知失败" : result;
                            fails.merge(key, 1, Integer::sum);
                        }
                    } else if (isDisc) {
                        disconnects++;
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("===== 拨号统计 =====\n");
        sb.append("历史条目: ").append(total).append('\n');
        sb.append("拨号次数: ").append(dialAttempts).append('\n');
        sb.append("拨号成功: ").append(dialSuccess).append('\n');
        sb.append("拨号失败: ").append(dialFail).append('\n');
        if (dialAttempts > 0) {
            sb.append(String.format(Locale.ROOT, "成功率: %.1f%%\n", 100.0 * dialSuccess / dialAttempts));
        } else {
            sb.append("成功率: --\n");
        }
        sb.append("断开次数: ").append(disconnects).append('\n');
        if (!fails.isEmpty()) {
            sb.append("常见结果:\n");
            fails.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(5)
                .forEach(e -> sb.append("  · ").append(e.getKey()).append(" ×").append(e.getValue()).append('\n'));
        }
        return new Summary(total, dialAttempts, dialSuccess, dialFail, disconnects, fails, sb.toString());
    }

    static boolean isSuccessResult(String result) {
        if (result == null) return false;
        String r = result.trim();
        if (r.isEmpty()) return false;
        if (r.contains("失败")) return false;
        if (r.contains("RAS成功无外网")) return false;
        return r.equals("成功") || r.startsWith("成功");
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }
}
