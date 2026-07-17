package util;

/**
 * Shared human-readable formatters for speed / bytes / duration.
 */
public final class FormatUtil {
    private FormatUtil() {
    }

    public static void appendSpeed(StringBuilder sb, long bytesPerSec) {
        if (bytesPerSec > 1048576) {
            sb.append(String.format("%.1f", bytesPerSec / 1048576.0)).append(" MB/s");
        } else if (bytesPerSec > 1024) {
            sb.append(String.format("%.1f", bytesPerSec / 1024.0)).append(" KB/s");
        } else {
            sb.append(bytesPerSec).append(" B/s");
        }
    }

    public static void appendBytes(StringBuilder sb, long bytes) {
        if (bytes > 1073741824L) {
            sb.append(String.format("%.2f", bytes / 1073741824.0)).append(" GB");
        } else if (bytes > 1048576) {
            sb.append(String.format("%.1f", bytes / 1048576.0)).append(" MB");
        } else if (bytes > 1024) {
            sb.append(String.format("%.1f", bytes / 1024.0)).append(" KB");
        } else {
            sb.append(bytes).append(" B");
        }
    }

    public static StringBuilder appendTime(StringBuilder sb, long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h < 10) sb.append('0');
        sb.append(h).append(':');
        if (m < 10) sb.append('0');
        sb.append(m).append(':');
        if (s < 10) sb.append('0');
        sb.append(s);
        return sb;
    }

    public static String formatSpeedLabel(long bytesPerSec) {
        if (bytesPerSec > 1048576) return String.format("%.1f MB/s", bytesPerSec / 1048576.0);
        if (bytesPerSec > 1024) return String.format("%.1f KB/s", bytesPerSec / 1024.0);
        return bytesPerSec + " B/s";
    }

    public static String formatSpeed(long bytesPerSec) {
        StringBuilder sb = new StringBuilder(16);
        appendSpeed(sb, bytesPerSec);
        return sb.toString();
    }

    public static String formatBytes(long bytes) {
        StringBuilder sb = new StringBuilder(16);
        appendBytes(sb, bytes);
        return sb.toString();
    }

    public static String formatDuration(long totalSeconds) {
        StringBuilder sb = new StringBuilder(12);
        appendTime(sb, totalSeconds);
        return sb.toString();
    }
}
