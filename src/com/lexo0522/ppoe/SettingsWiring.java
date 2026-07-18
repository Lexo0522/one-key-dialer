package com.lexo0522.ppoe;

import service.SettingsCoordinator;
import ui.MainHomePanel;
import ui.MainTabsController;
import ui.ProbeSettingsPanel;
import ui.SchedulePanel;
import ui.SettingsUiBridge;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Builds {@link SettingsCoordinator} + {@link SettingsUiBridge} for the shell
 * without burying a large anonymous Host inside {@code PPoEDialer}.
 */
public final class SettingsWiring {
    private SettingsWiring() {
    }

    public static SettingsCoordinator create(
            AppServices services,
            Supplier<MainHomePanel> homePanel,
            Supplier<MainTabsController> tabs,
            Class<?> appClass,
            Runnable syncScheduleCacheFromUi,
            Consumer<ProbeSettingsPanel> syncProbeFromUi,
            Consumer<String> logWarning,
            Consumer<String> logError) {
        SettingsUiBridge bridge = new SettingsUiBridge(new SettingsUiBridge.Host() {
            @Override
            public MainHomePanel homePanel() {
                return homePanel.get();
            }

            @Override
            public SchedulePanel schedulePanel() {
                MainTabsController t = tabs.get();
                return t != null ? t.schedulePanel() : null;
            }

            @Override
            public ProbeSettingsPanel probePanel() {
                MainTabsController t = tabs.get();
                return t != null ? t.probePanel() : null;
            }

            @Override
            public service.RuntimeSettings runtimeSettings() {
                return services.runtimeSettings;
            }

            @Override
            public service.DialOrchestrator dialOrchestrator() {
                return services.dialOrchestrator;
            }

            @Override
            public boolean isAutoStartEnabledInRegistry() {
                return services.startupService.isAutoStartEnabled();
            }

            @Override
            public boolean ensureAutoStartHealthy() {
                return services.startupService.ensureAutoStartHealthy(appClass, true);
            }

            @Override
            public void syncScheduleCacheFromUi() {
                syncScheduleCacheFromUi.run();
            }

            @Override
            public void syncProbeFromUi() {
                MainTabsController t = tabs.get();
                ProbeSettingsPanel p = t != null ? t.probePanel() : null;
                syncProbeFromUi.accept(p);
            }

            @Override
            public void logWarning(String message) {
                logWarning.accept(message);
            }

            @Override
            public void logError(String message) {
                logError.accept(message);
            }
        });
        return new SettingsCoordinator(
            services.settingsStore,
            services.runtimeSettings,
            services.accountSession,
            bridge);
    }
}
