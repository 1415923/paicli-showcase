package com.paicli.interactive.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskContinuityOperationalStatusTest {
    @Test void waitingForUserPrecedesExecutingButNotIntegrityOrRecovery(){
        assertEquals(OperationalStatusView.Status.WAITING_FOR_USER,
                OperationalStatusPrecedence.select(facts(false,false,true)));
        assertEquals(OperationalStatusView.Status.RECOVERY_REQUIRED,
                OperationalStatusPrecedence.select(facts(false,true,true)));
        assertEquals(OperationalStatusView.Status.BLOCKED_INTEGRITY,
                OperationalStatusPrecedence.select(facts(true,false,true)));
    }

    private static OperationalStatusPrecedence.Facts facts(boolean integrity,
            boolean recovery,boolean waiting){
        return new OperationalStatusPrecedence.Facts(integrity,false,recovery,waiting,
                false,true,false,false,false,false,false,false,false,false);
    }
}
