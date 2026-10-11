package com.paicli.interactive.session;

import java.util.concurrent.atomic.AtomicReference;

public final class TaskCancellationController {
    public enum Action { REQUEST_COOPERATIVE, FORCE_OWNED_PROCESSES, ALREADY_FORCE_REQUESTED }
    private enum State { RUNNING, REQUESTED, FORCE_REQUESTED }
    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);

    public Action request() {
        while (true) {
            State current=state.get();
            if(current==State.FORCE_REQUESTED)return Action.ALREADY_FORCE_REQUESTED;
            State next=current==State.RUNNING?State.REQUESTED:State.FORCE_REQUESTED;
            if(state.compareAndSet(current,next))return next==State.REQUESTED
                    ?Action.REQUEST_COOPERATIVE:Action.FORCE_OWNED_PROCESSES;
        }
    }

    public boolean cancellationRequested(){return state.get()!=State.RUNNING;}
    public boolean forceRequested(){return state.get()==State.FORCE_REQUESTED;}
}
