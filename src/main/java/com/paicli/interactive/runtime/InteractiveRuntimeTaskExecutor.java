package com.paicli.interactive.runtime;

import com.paicli.config.Config;
import com.paicli.interactive.task.InteractiveTaskRequest;
import com.paicli.runtime.CodingTaskResult;
import com.paicli.state.StateStore;
import com.paicli.state.FollowUpModels.ChildStartClaim;

@FunctionalInterface
public interface InteractiveRuntimeTaskExecutor {
    record AdmittedChildExecutionBinding(Config config,
            com.paicli.state.FollowUpModels.ChildAuthority child,
            String provider, String model, Long launchVersion) {
        public AdmittedChildExecutionBinding {
            if (child == null) throw new IllegalArgumentException("Child authority is required");
        }
        public AdmittedChildExecutionBinding(Config config,
                com.paicli.state.FollowUpModels.ChildAuthority child,
                String provider,String model){this(config,child,provider,model,null);}
        public AdmittedChildExecutionBinding bindClaim(ChildStartClaim claim){
            if(claim==null||!child.equals(claim.child()))throw new IllegalArgumentException(
                    "Child start claim does not match execution binding");
            if(launchVersion!=null&&launchVersion.longValue()!=claim.launchVersion())
                throw new IllegalArgumentException("Child launch version does not match execution binding");
            return launchVersion==null?new AdmittedChildExecutionBinding(config,child,provider,model,
                    claim.launchVersion()):this;
        }
        public void requireClaim(ChildStartClaim claim){
            if(claim==null||!child.equals(claim.child())||launchVersion==null
                    ||launchVersion.longValue()!=claim.launchVersion())
                throw new IllegalArgumentException("Claimed Child execution binding is inconsistent");
        }
    }
    CodingTaskResult execute(String taskId, InteractiveTaskRequest request,
            StateStore stateStore, RuntimeEventBridge bridge) throws Exception;

    default CodingTaskResult resume(String taskId, StateStore stateStore,
            RuntimeEventBridge bridge) throws Exception {
        throw new UnsupportedOperationException("interactive resume is unavailable");
    }

    default CodingTaskResult executeAdmittedChild(String taskId,String goal,StateStore stateStore,
            RuntimeEventBridge bridge)throws Exception{
        throw new UnsupportedOperationException("interactive Child execution is unavailable");
    }

    default CodingTaskResult executeAdmittedChild(String taskId,String goal,StateStore stateStore,
            RuntimeEventBridge bridge,ChildStartClaim claim)throws Exception{
        throw new UnsupportedOperationException("preclaimed interactive Child execution is unavailable");
    }

    default AdmittedChildExecutionBinding prepareAdmittedChildExecution(String taskId,
            StateStore stateStore, RuntimeEventBridge bridge,
            com.paicli.state.FollowUpModels.ChildAuthority child) throws Exception {
        return new AdmittedChildExecutionBinding(null, child, null, null);
    }

    default CodingTaskResult executeAdmittedChild(String taskId,String goal,StateStore stateStore,
            RuntimeEventBridge bridge,ChildStartClaim claim,
            AdmittedChildExecutionBinding binding)throws Exception{
        return executeAdmittedChild(taskId,goal,stateStore,bridge,claim);
    }
}
