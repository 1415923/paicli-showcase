package com.paicli.interactive.view;

public record SteeringView(String steeringId, String state,
        boolean pending, boolean confirmationRequired) { }
