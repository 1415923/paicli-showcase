package com.paicli.demo.runtime;

@FunctionalInterface
public interface CoreDemoWorkflow extends AutoCloseable {
    CoreDemoWorkflowResult execute(CoreDemoRunContext context) throws Exception;
    @Override default void close() throws Exception { }
}
