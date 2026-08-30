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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Colored UI log + append-only file buffer. File writes never run on the EDT:
 * the buffer is filled on the calling thread and flushed by a dedicated writer
 * thread (threshold) or synchronously via {@link #flush()} at exit.
 */
public class LogService {
    private static final int MAX_LOG_LINES = 500;
    private static final int LOG_FLUSH_THRESHOLD = 4096;
    private static final String FONT_NAME_EN = "Consolas";
    private static final Color DEFAULT_ERROR_COLOR = new Color(0xC62828);

    private final File logFile;
    private final ExecutorService fileWriter;
    private JTextPane logPane;
    private StyledDocument logDocument;

    private int logLineCount = 0;
    private final StringBuilder logFileBuffer = new StringBuilder(8192);
    private final Object logFileLock = new Object();
    private final Deque<Integer> logLineLengths = new ArrayDeque<>();
    private final Map<Color, AttributeSet> logAttrCache = new HashMap<>();

    public LogService(File logFile) {
        this.logFile = logFile;
        this.fileWriter = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LogFileWriter");
            t.setDaemon(true);
            return t;
        });
    }

    public void attach(JTextPane pane, StyledDocument document) {
        this.logPane = pane;
        this.logDocument = document;
    }

    public void log(String message, Color color) {
        final String safeMessage = util.RedactUtil.scrubLogLine(message);
        final String full = "[" + fastTimestamp() + "] " + safeMessage + "\n";
        appendToFileBuffer(full);
        SwingUtilities.invokeLater(() -> appendToUiDocument(full, color));
    }

    /** Message + full (scrubbed, truncated) stack trace into UI and file log. */
    public void logThrowable(String message, Throwable t, Color color) {
        if (t == null) {
            log(message, color);
            return;
        }
        log(message + "\n" + util.Throwables.stackTrace(t),
            color != null ? color : DEFAULT_ERROR_COLOR);
    }

    private void appendToFileBuffer(String full) {
        boolean shouldFlush = false;
        synchronized (logFileLock) {
            logFileBuffer.append(full);
            shouldFlush = logFileBuffer.length() >= LOG_FLUSH_THRESHOLD;
        }
        if (shouldFlush) {
            try {
                fileWriter.execute(this::flush);
            } catch (RejectedExecutionException e) {
                flush();
            }
        }
    }

    private void appendToUiDocument(String full, Color color) {
        if (logDocument == null || logPane == null) return;
        try {
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
        } catch (BadLocationException ignored) {
        }
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
        // Called from arbitrary threads — must not touch the shared builder.
        LocalTime now = LocalTime.now();
        StringBuilder sb = new StringBuilder(12);
        int h = now.getHour();
        if (h < 10) sb.append('0');
        sb.append(h).append(':');
        int m = now.getMinute();
        if (m < 10) sb.append('0');
        sb.append(m).append(':');
        int s = now.getSecond();
        if (s < 10) sb.append('0');
        sb.append(s);
        return sb.toString();
    }
}
