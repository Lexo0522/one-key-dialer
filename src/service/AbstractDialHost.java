package service;

/**
 * Named DialOrchestrator host adapters live next to the orchestrator for reuse in tests.
 * Production shell implements callbacks; this base provides no-op defaults for unused hooks.
 */
public abstract class AbstractDialHost implements DialOrchestrator.Host {
    @Override
    public void saveSettingsAfterSuccess() {
    }

    @Override
    public void notifyUser(String title, String message) {
    }

    @Override
    public void setDialControlsEnabled(boolean enabled) {
    }
}
