package model;

import i18n.Messages;

/** Shared display words for dial history rows (replaces scattered literals). */
public enum DialOutcome {
    SUCCESS,
    FAILURE,
    DONE,
    RAS_NO_INTERNET;

    public String text() {
        switch (this) {
            case SUCCESS: return Messages.get("outcome.success");
            case FAILURE: return Messages.get("outcome.fail");
            case DONE: return Messages.get("outcome.done");
            case RAS_NO_INTERNET: return Messages.get("outcome.rasNoInternet");
            default: return name();
        }
    }
}
