package com.paicli.demo.workflow;
public final class CoreDemoScenarioDriftException extends RuntimeException {
    private final String category;
    public CoreDemoScenarioDriftException(String category,String message){
        super(category+": "+message);this.category=category;
    }
    public String category(){return category;}
}