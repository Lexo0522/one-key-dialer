package model;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared dial/disconnect lifecycle for UI, schedule, and auto-reconnect.
 */
public final class DialLifecycle {
    public enum State {
        IDLE,
        DIALING,
        DISCONNECTING
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);

    public State get() {
        return state.get();
    }

    public boolean isIdle() {
        return state.get() == State.IDLE;
    }

    public boolean isBusy() {
        return state.get() != State.IDLE;
    }

    public boolean isDialing() {
        return state.get() == State.DIALING;
    }

    public boolean tryBeginDial() {
        return state.compareAndSet(State.IDLE, State.DIALING);
    }

    public boolean tryBeginDisconnect() {
        return state.compareAndSet(State.IDLE, State.DISCONNECTING);
    }

    public void end() {
        state.set(State.IDLE);
    }
}
