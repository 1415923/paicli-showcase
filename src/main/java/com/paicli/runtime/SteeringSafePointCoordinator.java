package com.paicli.runtime;

import com.paicli.state.StateStore;
import com.paicli.state.SteeringStore;
import com.paicli.state.StructuredPlanModels.Revision;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One authority predicate for every R1D candidate boundary. */
public final class SteeringSafePointCoordinator {
    public enum Candidate {
        PROVIDER_REQUEST_BEFORE,
        PROVIDER_RESPONSE_BEFORE_TOOL,
        TOOL_OCCURRENCE_TERMINAL,
        HOOK_OCCURRENCE_TERMINAL,
        STEP_ATTEMPT_TERMINAL,
        VERIFICATION_FACTS_COMMITTED,
        NEXT_STEP_ATTEMPT_BEFORE,
        COMPLETION_GATE_BEFORE
    }
    public record Result(SteeringStore.Status status, boolean applied, boolean pauseExecution) {
        public static Result none(SteeringStore.Status status) { return new Result(status,false,false); }
    }
    @FunctionalInterface public interface MaterialPlanFactory {
        Revision generate(SteeringStore.MaterialPlanRequest request);
    }
    @FunctionalInterface public interface AttemptPauseFactory {
        SteeringStore.AttemptPause current(int endIteration);
    }

    private final StateStore store;
    private final FencedExecutionGuard guard;
    private final BooleanSupplier approvalPending;
    private final MaterialPlanFactory materialPlanFactory;
    private final AttemptPauseFactory attemptPauseFactory;

    public SteeringSafePointCoordinator(StateStore store,FencedExecutionGuard guard,
            BooleanSupplier approvalPending){
        this(store,guard,approvalPending,null,iteration->null);
    }

    public SteeringSafePointCoordinator(StateStore store,FencedExecutionGuard guard,
            BooleanSupplier approvalPending,MaterialPlanFactory materialPlanFactory,
            AttemptPauseFactory attemptPauseFactory){
        this.store=Objects.requireNonNull(store);this.guard=Objects.requireNonNull(guard);
        this.approvalPending=Objects.requireNonNull(approvalPending);
        this.materialPlanFactory=materialPlanFactory;
        this.attemptPauseFactory=Objects.requireNonNull(attemptPauseFactory);
    }

    public Result check(Candidate candidate){
        return check(candidate,0);
    }

    public Result check(Candidate candidate,int endIteration){
        Objects.requireNonNull(candidate);
        guard.heartbeat("STEERING_SAFE_POINT_"+candidate.name());
        SteeringStore.AttemptPause attemptPause=attemptPauseFactory.current(endIteration);
        var result=store.steerings.applyOldest(guard.current().snapshot(),guard.current().heartbeatAt(),
                candidate.name(),approvalPending.getAsBoolean(),null,attemptPause);
        if(result.status()==SteeringStore.Status.MATERIAL_PLAN_REQUIRED
                &&materialPlanFactory!=null){
            Revision draft;
            try{draft=materialPlanFactory.generate(result.materialPlanRequest());}
            catch(RuntimeException generationFailure){
                return new Result(SteeringStore.Status.MATERIAL_PLAN_REQUIRED,false,true);
            }
            result=store.steerings.applyOldest(guard.current().snapshot(),
                    guard.current().heartbeatAt(),candidate.name(),
                    approvalPending.getAsBoolean(),draft,attemptPause);
        }
        if(result.status()==SteeringStore.Status.NOT_FOUND)return Result.none(result.status());
        boolean pauseExecution=result.executionPaused()
                ||result.status()!=SteeringStore.Status.SUCCESS;
        return new Result(result.status(),result.status()==SteeringStore.Status.SUCCESS,
                pauseExecution);
    }

    /** Provider requests always resolve the active durable Constraint authority afresh. */
    public String activeConstraintContext(){
        guard.validate("STEERING_CONTEXT_READ");
        return store.steerings.activeConstraintContext(guard.current().taskId());
    }

    public boolean blocksCompletion(){return store.steerings.blocksCompletion(guard.current().taskId());}
}
