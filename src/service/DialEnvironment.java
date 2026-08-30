package service;

import util.ConnectivityConfirm;
import util.ProbeOutcome;

/**
 * Environment state and persistence hooks the orchestrator reads (never UI).
 * Kept deliberately narrow: online state, session context, probe configuration,
 * policy flags, history writes, and post-success persistence.
 */
public interface DialEnvironment {
    boolean isOnline();

    long connectTimeMillis();

    /** Session download+upload bytes for the history traffic column. */
    long sessionTrafficBytes();

    String currentAccountName();

    ConnectivityConfirm.Config probeConfig();

    /** After RAS success but probe failure, disconnect the PPP session. */
    boolean disconnectOnNoInternet();

    /** Record one history row — exactly once per operation. */
    void addHistory(String operation, String account, String result, String duration, String traffic);

    /** Persist current selection/settings after a successful user-initiated dial. */
    void persistAfterSuccess();

    /** Report the outcome of a post-dial probe (diagnostics). */
    void recordProbeOutcome(ProbeOutcome outcome);
}
