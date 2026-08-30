package service;

import model.DialCredentials;

/**
 * UI-facing callbacks for the dial orchestrator. Implementations must marshal
 * state changes onto the EDT; the orchestrator guarantees that
 * {@link #captureDialCredentials()} and {@link #validateDialInput(boolean)} are
 * only invoked on (or marshalled to) the EDT.
 */
public interface DialView {
    enum Level { INFO, SUCCESS, WARNING, ERROR }

    boolean onEventDispatchThread();

    /** Fire-and-forget EDT dispatch. */
    void runOnEdt(Runnable action);

    /** EDT dispatch that blocks the caller (background credential capture). */
    void runOnEdtAndWait(Runnable action) throws Exception;

    /** EDT-only: collect one-shot credentials from the current account fields. */
    DialCredentials captureDialCredentials();

    /** EDT: pre-dial validation; {@code interactive} shows user dialogs. False aborts. */
    boolean validateDialInput(boolean interactive);

    /**
     * Main dial control state: {@code "dialing"} / {@code "disconnecting"} while
     * busy (controls disabled), {@code null} when idle (controls enabled).
     */
    void onDialPhase(String phase);

    /** Online/offline status change (dot, status bar, tray icon). */
    void onConnectionState(boolean online);

    void notifyUser(String title, String message);

    void log(Level level, String message);
}
