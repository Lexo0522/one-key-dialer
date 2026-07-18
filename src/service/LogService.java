package service;

import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Colored UI log + append-only file buffer.
 */
public class LogService {
    private static final int MAX_LOG_LINES = 500;
    private static final int LOG_FLUSH_THRESHOLD = 4096;
    private static final String FONT_NAME_EN = "Consolas";

    private final File logFile;
    private JTextPane logPane;
    private StyledDocument logDocument;

    private int logLineCount = 0;
    private final StringBuilder logFileBuffer = new StringBuilder(8192);
    private final Object logFileLock = new Object();
    private final Deque<Integer> logLineLengths = new ArrayDeque<>();
    private final Map<Color, AttributeSet> logAttrCache = new HashMap<>();
    private final StringBuilder logTimeSb = new StringBuilder(12);

    public LogService(File logFile) {
        this.logFile = logFile;
    }

    public void attach(JTextPane pane, StyledDocument document) {
        this.logPane = pane;
        this.logDocument = document;
    }

    public void log(String message, Color color) {
        final String safeMessage = util.RedactUtil.scrubLogLine(message);
        SwingUtilities.invokeLater(() -> {
            if (logDocument == null || logPane == null) return;
            try {
                String ts = fastTimestamp();
                String full = "[" + ts + "] " + safeMessage + "\n";
                AttributeSet a = getLogAttributeSet(color);
                logDocument.insertString(logDocument.getLength(), full, a);
                logLineCount++;
                logLineLengths.addLast(full.length());
                if (logLineCount > MAX_LOG_LINES) {
                    int cutLen = 0;
                    int linesToCut = logLineCount - MAX_LOG_LINES;
                    for (int i = 0; i < linesToCut && !logLineLengths.isEmpty(); i++) {
                        cutLen += logLineLengths.removeFirst();
                    }
                    if (cutLen > 0) logDocument.remove(0, cutLen);
                    logLineCount = MAX_LOG_LINES;
                }
                logPane.setCaretPosition(logDocument.getLength());
                writeLogFile(full);
            } catch (BadLocationException ignored) {
            }
        });
    }

    public void flush() {
        String content;
        synchronized (logFileLock) {
            if (logFileBuffer.length() == 0) return;
            content = logFileBuffer.toString();
            logFileBuffer.setLength(0);
        }
        try {
            File target = logFile != null ? logFile : new File("pppoe_log.txt");
            Files.write(target.toPath(), content.getBytes("UTF-8"),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private AttributeSet getLogAttributeSet(Color color) {
        return logAttrCache.computeIfAbsent(color, c -> {
            SimpleAttributeSet a = new SimpleAttributeSet();
            StyleConstants.setForeground(a, c);
            StyleConstants.setFontFamily(a, FONT_NAME_EN);
            StyleConstants.setFontSize(a, 12);
            return a;
        });
    }

    private String fastTimestamp() {
        LocalTime now = LocalTime.now();
        logTimeSb.setLength(0);
        int h = now.getHour();
        if (h < 10) logTimeSb.append('0');
        logTimeSb.append(h).append(':');
        int m = now.getMinute();
        if (m < 10) logTimeSb.append('0');
        logTimeSb.append(m).append(':');
        int s = now.getSecond();
        if (s < 10) logTimeSb.append('0');
        logTimeSb.append(s);
        return logTimeSb.toString();
    }

    private void writeLogFile(String msg) {
        boolean shouldFlush = false;
        synchronized (logFileLock) {
            logFileBuffer.append(msg);
            shouldFlush = logFileBuffer.length() >= LOG_FLUSH_THRESHOLD;
        }
        if (shouldFlush) flush();
    }
}
