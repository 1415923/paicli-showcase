package com.paicli.interactive.steering;

/** Bounded prompt fact for an UNKNOWN Steering awaiting its input owner. */
public record SteeringConfirmationRequest(String steeringId,String taskId,String diagnostic) {
    public SteeringConfirmationRequest {
        if(steeringId==null||!steeringId.matches("str_[0-9a-f]{32}")
                ||taskId==null||!taskId.matches("tsk_[0-9a-f]{32}"))
            throw new IllegalArgumentException("Invalid Steering confirmation identity");
        diagnostic=diagnostic==null?"impact unknown":diagnostic.substring(0,Math.min(128,diagnostic.length()));
    }
}
