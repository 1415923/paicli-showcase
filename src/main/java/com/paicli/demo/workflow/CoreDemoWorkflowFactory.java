package com.paicli.demo.workflow;

import com.paicli.demo.runtime.CoreDemoWorkflow;

public final class CoreDemoWorkflowFactory {
    private CoreDemoWorkflowFactory(){}
    public static CoreDemoWorkflow production(){
        return new CoreDemoFullWorkflow();
    }
}